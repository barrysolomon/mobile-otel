import Foundation
import SQLite3
import OpenTelemetrySdk

// MARK: - DiskLogBuffer

/// Disk-backed log buffer — the iOS parity port of the Android
/// `DiskLogBuffer` (Room/SQLite). This is the second tier of the dual-tier
/// buffering pipeline: events evicted from the RAM buffer (or mirrored for
/// crash safety) are spilled here and survive process death, so the SDK can
/// recover and export them on next launch.
///
/// Implementation notes:
/// - Uses the raw `sqlite3` C API (`import SQLite3`) — no external
///   dependencies beyond what Apple ships. On iOS/macOS the system SDK
///   provides the `SQLite3` module, so no `.linkedLibrary("sqlite3")` is
///   required in `Package.swift`.
/// - `actor` isolation serialises all sqlite3 handles. No lock needed; all
///   prepared statements, binds and steps happen inside the actor's
///   execution context.
/// - `BufferedEvent.record` (OTel `ReadableLogRecord`) is serialised to JSON
///   using `JSONEncoder` — `ReadableLogRecord: Codable`. On recovery we
///   decode with `JSONDecoder` and re-hand the record to the OTel exporter.
/// - Every sqlite3 call path must fail softly — the SDK promise per
///   `docs/SDK_SAFETY.md` is that a buffer malfunction must never crash the
///   host app. All error paths log via `NSLog` and return empty/no-op.
///
/// Schema (created at open time):
/// ```
/// CREATE TABLE IF NOT EXISTS buffered_events (
///     id           INTEGER PRIMARY KEY AUTOINCREMENT,
///     seq_id       INTEGER NOT NULL,
///     timestamp_ms INTEGER NOT NULL,
///     session_id   TEXT NOT NULL,
///     record_json  BLOB NOT NULL,
///     size_bytes   INTEGER NOT NULL,
///     created_at   INTEGER NOT NULL
/// );
/// ```
///
/// Pragmas applied at open time:
/// - `journal_mode=WAL` — crash-safe, better concurrent read performance.
/// - `synchronous=NORMAL` — acceptable durability for observability data.
/// - `temp_store=MEMORY` — avoids cluttering the device sandbox.
public actor DiskLogBuffer {
    // MARK: - Public configuration

    /// Absolute path on disk where the sqlite file lives.
    public nonisolated let dbPath: URL

    /// Upper bound on the cumulative `size_bytes` column. The buffer self-prunes
    /// oldest events when inserts would exceed this. 50 MB default matches
    /// Android.
    public let maxTotalBytes: Int

    /// Events older than `now - retentionSeconds` are removed by
    /// `pruneByTTL()`. 24 hours default matches Android.
    public let retentionSeconds: TimeInterval

    // MARK: - sqlite3 state (actor-isolated)

    private var db: OpaquePointer?
    private var insertStmt: OpaquePointer?

    /// `true` once the handle has been closed. Further calls are no-ops.
    private var closed: Bool = false

    // MARK: - Lifecycle

    /// Opens (or creates) the sqlite file at `dbPath`. If `dbPath` is `nil`,
    /// uses `<Application Support>/io.dash0.mobile/buffer.db` (creating the
    /// directory if needed).
    ///
    /// Throws only if the backing directory cannot be created — every
    /// subsequent sqlite3 error is handled in-band (logged, no crash) to
    /// honour the SDK safety contract.
    public init(
        dbPath: URL? = nil,
        maxTotalBytes: Int = 50 * 1024 * 1024,
        retentionSeconds: TimeInterval = 24 * 3600
    ) async throws {
        self.dbPath = try Self.resolveDbPath(preferred: dbPath)
        self.maxTotalBytes = maxTotalBytes
        self.retentionSeconds = retentionSeconds
        self.openAndPrepare()
    }

    /// Closes the sqlite handle and finalises any cached prepared statements.
    /// Idempotent — safe to call multiple times. Any further public call
    /// becomes a no-op after shutdown.
    public func shutdown() async {
        if closed { return }
        closed = true
        if let stmt = insertStmt {
            sqlite3_finalize(stmt)
            insertStmt = nil
        }
        if let handle = db {
            sqlite3_close(handle)
            self.db = nil
        }
    }

    // MARK: - Public API

    /// Inserts a single event. Enforces `maxTotalBytes` after the insert.
    /// No-op on any sqlite3 error — the host app never crashes.
    public func insert(_ event: BufferedEvent) async {
        guard !closed, db != nil else { return }
        _ = insertRow(event)
        pruneIfOverBudgetInternal()
    }

    /// Inserts a batch of events inside a single transaction. Much cheaper
    /// than N `insert(_:)` calls when spilling a flush from RAM to disk.
    public func insertBatch(_ events: [BufferedEvent]) async {
        guard !closed, db != nil, !events.isEmpty else { return }
        execSQL("BEGIN IMMEDIATE TRANSACTION;")
        for event in events {
            _ = insertRow(event)
        }
        execSQL("COMMIT;")
        pruneIfOverBudgetInternal()
    }

    /// Returns the oldest `limit` events by `seq_id` ascending. Empty on error.
    public func fetchAll(limit: Int = 500) async -> [BufferedEvent] {
        guard !closed, db != nil else { return [] }
        let sql = "SELECT id, seq_id, timestamp_ms, session_id, record_json, size_bytes, created_at FROM buffered_events ORDER BY seq_id ASC LIMIT ?;"
        return fetchEvents(sql: sql, limit: limit)
    }

    /// Returns events with `timestamp_ms >= now - lastMs`. Bounded by `limit`.
    public func fetchWindow(lastMs: UInt64, limit: Int = 500) async -> [BufferedEvent] {
        guard !closed, db != nil else { return [] }
        let nowMs = UInt64(Date().timeIntervalSince1970 * 1000)
        let cutoff: Int64 = {
            if nowMs > lastMs {
                return Int64(nowMs - lastMs)
            }
            return 0
        }()
        let sql = "SELECT id, seq_id, timestamp_ms, session_id, record_json, size_bytes, created_at FROM buffered_events WHERE timestamp_ms >= ? ORDER BY seq_id ASC LIMIT ?;"
        return fetchEvents(sql: sql, timestampCutoff: cutoff, limit: limit)
    }

    /// Deletes every row whose `seq_id <= sequenceId`. Used after a
    /// successful export to clear the drained window.
    public func deleteUpTo(sequenceId: UInt64) async {
        guard !closed, db != nil else { return }
        let sql = "DELETE FROM buffered_events WHERE seq_id <= ?;"
        var stmt: OpaquePointer?
        guard prepare(sql, &stmt) else { return }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int64(stmt, 1, Int64(bitPattern: sequenceId))
        step(stmt)
    }

    /// Deletes rows by internal autoincrement id. Useful after a partial
    /// export where only some rows succeeded.
    public func deleteEvents(ids: [Int64]) async {
        guard !closed, db != nil, !ids.isEmpty else { return }
        execSQL("BEGIN IMMEDIATE TRANSACTION;")
        let sql = "DELETE FROM buffered_events WHERE id = ?;"
        var stmt: OpaquePointer?
        if prepare(sql, &stmt) {
            for id in ids {
                sqlite3_reset(stmt)
                sqlite3_bind_int64(stmt, 1, id)
                step(stmt)
            }
            sqlite3_finalize(stmt)
        }
        execSQL("COMMIT;")
    }

    /// Sum of `size_bytes` across all rows. 0 on error.
    public func totalSizeBytes() async -> Int {
        guard !closed, db != nil else { return 0 }
        return scalarInt(sql: "SELECT COALESCE(SUM(size_bytes), 0) FROM buffered_events;")
    }

    /// Total row count. 0 on error.
    public func rowCount() async -> Int {
        guard !closed, db != nil else { return 0 }
        return scalarInt(sql: "SELECT COUNT(*) FROM buffered_events;")
    }

    /// Deletes rows older than `now - retentionSeconds`.
    public func pruneByTTL() async {
        guard !closed, db != nil else { return }
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let cutoff = nowMs - Int64(retentionSeconds * 1000)
        let sql = "DELETE FROM buffered_events WHERE created_at < ?;"
        var stmt: OpaquePointer?
        guard prepare(sql, &stmt) else { return }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int64(stmt, 1, cutoff)
        step(stmt)
    }

    /// Evicts oldest events (by `seq_id` ascending) until cumulative
    /// `size_bytes` is at or below `maxBytes`.
    public func pruneBySize(maxBytes: Int) async {
        guard !closed, db != nil else { return }
        pruneBySizeInternal(maxBytes: maxBytes)
    }

    // MARK: - Offline budget enforcement

    /// Enforces the offline disk budget by evicting events until the db file
    /// size is at or below `config.maxOfflineDiskBytes`. Mirrors Android's
    /// `DiskLogBuffer.enforceOfflineBudget()`.
    ///
    /// After deletion, runs VACUUM to reclaim disk space — SQLite doesn't
    /// shrink the file on DELETE alone.
    public func enforceOfflineBudget(_ config: OfflineBudgetConfig) async {
        guard config.enabled, !closed, db != nil else { return }
        let currentSize = fileSizeBytes()
        guard currentSize > config.maxOfflineDiskBytes else { return }

        let rowTotal = scalarInt(sql: "SELECT COUNT(*) FROM buffered_events;")
        guard rowTotal > 0 else { return }

        let excessRatio = Double(currentSize - config.maxOfflineDiskBytes) / Double(currentSize)
        let evictCount = max(1, Int(Double(rowTotal) * excessRatio))

        switch config.evictionStrategy {
        case .oldestFirst:
            deleteOldest(count: evictCount)
        case .lowestSeverityFirst:
            deleteLowestSeverity(count: evictCount)
        }

        execSQL("VACUUM;")
    }

    /// Returns the actual file size on disk in bytes. 0 on error.
    public nonisolated func fileSizeBytes() -> Int {
        let path = dbPath.path
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: path),
              let size = attrs[.size] as? Int else { return 0 }
        return size
    }

    private func deleteOldest(count: Int) {
        let sql = "DELETE FROM buffered_events WHERE id IN (SELECT id FROM buffered_events ORDER BY seq_id ASC, id ASC LIMIT ?);"
        var stmt: OpaquePointer?
        guard prepare(sql, &stmt) else { return }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int(stmt, 1, Int32(count))
        step(stmt)
    }

    private func deleteLowestSeverity(count: Int) {
        let sql = """
        DELETE FROM buffered_events WHERE id IN (
            SELECT id FROM buffered_events ORDER BY
                CASE json_extract(record_json, '$.severity')
                    WHEN 'trace' THEN 0 WHEN 'debug' THEN 1 WHEN 'info' THEN 2
                    WHEN 'warn' THEN 3 WHEN 'error' THEN 4 WHEN 'fatal' THEN 5
                    WHEN 'TRACE' THEN 0 WHEN 'DEBUG' THEN 1 WHEN 'INFO' THEN 2
                    WHEN 'WARN' THEN 3 WHEN 'ERROR' THEN 4 WHEN 'FATAL' THEN 5
                    ELSE 1 END ASC,
                timestamp_ms ASC
            LIMIT ?
        );
        """
        var stmt: OpaquePointer?
        guard prepare(sql, &stmt) else {
            deleteOldest(count: count)
            return
        }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int(stmt, 1, Int32(count))
        step(stmt)
    }

    // MARK: - Private: open / prepare

    private nonisolated static func resolveDbPath(preferred: URL?) throws -> URL {
        if let preferred = preferred {
            let dir = preferred.deletingLastPathComponent()
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            return preferred
        }
        // Default: <Application Support>/io.dash0.mobile/buffer.db
        let fm = FileManager.default
        let supportDir: URL
        if let url = try? fm.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        ) {
            supportDir = url
        } else {
            // Fall back to the temp dir — still correct on device, just less
            // durable. We prefer this over throwing since the SDK should
            // degrade to "works, slightly less crash-safe" on pathological
            // filesystems.
            supportDir = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        }
        let dashDir = supportDir.appendingPathComponent("io.dash0.mobile", isDirectory: true)
        try fm.createDirectory(at: dashDir, withIntermediateDirectories: true)
        return dashDir.appendingPathComponent("buffer.db", isDirectory: false)
    }

    private func openAndPrepare() {
        let path = dbPath.path
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        var handle: OpaquePointer?
        let rc = sqlite3_open_v2(path, &handle, flags, nil)
        guard rc == SQLITE_OK, let handle = handle else {
            let msg = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "rc=\(rc)"
            NSLog("[DiskLogBuffer] sqlite3_open_v2 failed: %@", msg)
            if let handle = handle { sqlite3_close(handle) }
            db = nil
            return
        }
        db = handle

        // Apply pragmas. PRAGMAs go through sqlite3_exec.
        execSQL("PRAGMA journal_mode=WAL;")
        execSQL("PRAGMA synchronous=NORMAL;")
        execSQL("PRAGMA temp_store=MEMORY;")

        // Create schema.
        execSQL("""
        CREATE TABLE IF NOT EXISTS buffered_events (
            id           INTEGER PRIMARY KEY AUTOINCREMENT,
            seq_id       INTEGER NOT NULL,
            timestamp_ms INTEGER NOT NULL,
            session_id   TEXT NOT NULL,
            record_json  BLOB NOT NULL,
            size_bytes   INTEGER NOT NULL,
            created_at   INTEGER NOT NULL
        );
        """)
        execSQL("CREATE INDEX IF NOT EXISTS idx_timestamp ON buffered_events(timestamp_ms);")
        execSQL("CREATE INDEX IF NOT EXISTS idx_seq_id ON buffered_events(seq_id);")

        // Cache the insert statement — hot path on every buffer spill.
        let insertSql = "INSERT INTO buffered_events (seq_id, timestamp_ms, session_id, record_json, size_bytes, created_at) VALUES (?, ?, ?, ?, ?, ?);"
        var stmt: OpaquePointer?
        if prepare(insertSql, &stmt) {
            insertStmt = stmt
        }
    }

    // MARK: - Private: sqlite helpers

    private func execSQL(_ sql: String) {
        guard let handle = db else { return }
        var err: UnsafeMutablePointer<CChar>?
        let rc = sqlite3_exec(handle, sql, nil, nil, &err)
        if rc != SQLITE_OK {
            let msg = err.map { String(cString: $0) } ?? "rc=\(rc)"
            NSLog("[DiskLogBuffer] sqlite3_exec failed: %@ sql=%@", msg, sql)
        }
        if let err = err { sqlite3_free(err) }
    }

    private func prepare(_ sql: String, _ stmt: inout OpaquePointer?) -> Bool {
        guard let handle = db else { return false }
        let rc = sqlite3_prepare_v2(handle, sql, -1, &stmt, nil)
        if rc != SQLITE_OK {
            let msg = String(cString: sqlite3_errmsg(handle))
            NSLog("[DiskLogBuffer] sqlite3_prepare_v2 failed: %@ sql=%@", msg, sql)
            return false
        }
        return true
    }

    @discardableResult
    private func step(_ stmt: OpaquePointer?) -> Int32 {
        guard let stmt = stmt else { return SQLITE_MISUSE }
        let rc = sqlite3_step(stmt)
        if rc != SQLITE_DONE, rc != SQLITE_ROW {
            if let handle = db {
                let msg = String(cString: sqlite3_errmsg(handle))
                NSLog("[DiskLogBuffer] sqlite3_step rc=%d: %@", rc, msg)
            }
        }
        return rc
    }

    private func scalarInt(sql: String) -> Int {
        var stmt: OpaquePointer?
        guard prepare(sql, &stmt) else { return 0 }
        defer { sqlite3_finalize(stmt) }
        if sqlite3_step(stmt) == SQLITE_ROW {
            return Int(sqlite3_column_int64(stmt, 0))
        }
        return 0
    }

    // MARK: - Private: insert & fetch

    /// Inserts one row using the cached prepared statement. Returns the
    /// new rowid on success, 0 on failure.
    @discardableResult
    private func insertRow(_ event: BufferedEvent) -> Int64 {
        guard let handle = db, let stmt = insertStmt else { return 0 }
        sqlite3_reset(stmt)
        sqlite3_clear_bindings(stmt)

        let json = encodeRecordJSON(event)
        let sessionIdCString = event.sessionId

        sqlite3_bind_int64(stmt, 1, Int64(bitPattern: event.sequenceId))
        sqlite3_bind_int64(stmt, 2, Int64(bitPattern: event.timestampMs))
        // SQLITE_TRANSIENT tells sqlite to copy the string; safe across the
        // statement lifetime.
        _ = sessionIdCString.withCString { ptr in
            sqlite3_bind_text(stmt, 3, ptr, -1, Self.sqliteTransient)
        }
        _ = json.withUnsafeBytes { raw -> Int32 in
            if let base = raw.baseAddress, raw.count > 0 {
                return sqlite3_bind_blob(stmt, 4, base, Int32(raw.count), Self.sqliteTransient)
            }
            // Zero-length blobs still need a valid bind.
            return sqlite3_bind_zeroblob(stmt, 4, 0)
        }
        sqlite3_bind_int64(stmt, 5, Int64(json.count))
        sqlite3_bind_int64(stmt, 6, Int64(event.createdAt.timeIntervalSince1970 * 1000))

        let rc = step(stmt)
        if rc == SQLITE_DONE {
            return sqlite3_last_insert_rowid(handle)
        }
        return 0
    }

    private func fetchEvents(
        sql: String,
        timestampCutoff: Int64? = nil,
        limit: Int
    ) -> [BufferedEvent] {
        var stmt: OpaquePointer?
        guard prepare(sql, &stmt) else { return [] }
        defer { sqlite3_finalize(stmt) }

        var bindIndex: Int32 = 1
        if let cutoff = timestampCutoff {
            sqlite3_bind_int64(stmt, bindIndex, cutoff)
            bindIndex += 1
        }
        sqlite3_bind_int(stmt, bindIndex, Int32(limit))

        var results: [BufferedEvent] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            // Column order matches the SELECT list in the callers.
            let seqId = UInt64(bitPattern: sqlite3_column_int64(stmt, 1))
            let timestampMs = UInt64(bitPattern: sqlite3_column_int64(stmt, 2))
            let sessionId: String = {
                if let c = sqlite3_column_text(stmt, 3) {
                    return String(cString: c)
                }
                return ""
            }()
            let recordJSON: Data = {
                let size = Int(sqlite3_column_bytes(stmt, 4))
                if size > 0, let ptr = sqlite3_column_blob(stmt, 4) {
                    return Data(bytes: ptr, count: size)
                }
                return Data()
            }()
            let createdAtMs = sqlite3_column_int64(stmt, 6)
            let createdAt = Date(timeIntervalSince1970: TimeInterval(createdAtMs) / 1000.0)

            let record = decodeRecordJSON(recordJSON)
            let event = BufferedEvent(
                sequenceId: seqId,
                timestampMs: timestampMs,
                sessionId: sessionId,
                record: record,
                eventData: Data(),
                createdAt: createdAt
            )
            results.append(event)
        }
        return results
    }

    // MARK: - Private: pruning

    private func pruneIfOverBudgetInternal() {
        // Cheap size check — skip the prune query when under budget.
        let total = scalarInt(sql: "SELECT COALESCE(SUM(size_bytes), 0) FROM buffered_events;")
        if total <= maxTotalBytes { return }
        pruneBySizeInternal(maxBytes: maxTotalBytes)
    }

    private func pruneBySizeInternal(maxBytes: Int) {
        // Walk oldest → newest, deleting until cumulative size is under
        // budget. Doing this in one SQL query avoids N+1 deletions.
        //
        // Strategy: find the id threshold such that rows with id <= threshold
        // account for (total - maxBytes) bytes, then DELETE <= threshold.
        let total = scalarInt(sql: "SELECT COALESCE(SUM(size_bytes), 0) FROM buffered_events;")
        if total <= maxBytes { return }
        let needToFree = total - maxBytes

        // Window function requires SQLite 3.25+, shipped in iOS 14 (and on
        // macOS 10.14+). We're targeting iOS 15 / macOS 13 so this is safe.
        let sql = """
        WITH ranked AS (
            SELECT id, size_bytes,
                   SUM(size_bytes) OVER (ORDER BY seq_id ASC, id ASC) AS running
            FROM buffered_events
        )
        SELECT id FROM ranked WHERE running >= ? ORDER BY running ASC LIMIT 1;
        """
        var stmt: OpaquePointer?
        guard prepare(sql, &stmt) else {
            // Fall back to a naive approach: delete the oldest row one by
            // one until under budget. Slower but always correct.
            fallbackPruneBySize(targetBytes: maxBytes)
            return
        }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int64(stmt, 1, Int64(needToFree))

        var deleteThreshold: Int64 = -1
        if sqlite3_step(stmt) == SQLITE_ROW {
            deleteThreshold = sqlite3_column_int64(stmt, 0)
        }
        guard deleteThreshold >= 0 else { return }

        let deleteSql = "DELETE FROM buffered_events WHERE id <= ?;"
        var delStmt: OpaquePointer?
        if prepare(deleteSql, &delStmt) {
            defer { sqlite3_finalize(delStmt) }
            sqlite3_bind_int64(delStmt, 1, deleteThreshold)
            step(delStmt)
        }
    }

    private func fallbackPruneBySize(targetBytes: Int) {
        // Iteratively delete the oldest row until total is within budget.
        // Bounded at 10k iterations to guard against a runaway loop.
        var guardIterations = 10_000
        while guardIterations > 0 {
            guardIterations -= 1
            let total = scalarInt(sql: "SELECT COALESCE(SUM(size_bytes), 0) FROM buffered_events;")
            if total <= targetBytes { return }
            execSQL("DELETE FROM buffered_events WHERE id = (SELECT id FROM buffered_events ORDER BY seq_id ASC, id ASC LIMIT 1);")
        }
    }

    // MARK: - Private: record (de)serialisation

    private func encodeRecordJSON(_ event: BufferedEvent) -> Data {
        guard let record = event.record else { return Data() }
        let encoder = JSONEncoder()
        do {
            return try encoder.encode(record)
        } catch {
            NSLog("[DiskLogBuffer] record encode failed: %@", String(describing: error))
            return Data()
        }
    }

    private func decodeRecordJSON(_ data: Data) -> ReadableLogRecord? {
        guard !data.isEmpty else { return nil }
        let decoder = JSONDecoder()
        do {
            return try decoder.decode(ReadableLogRecord.self, from: data)
        } catch {
            NSLog("[DiskLogBuffer] record decode failed: %@", String(describing: error))
            return nil
        }
    }

    // MARK: - sqlite destructor constants

    // SQLite C headers expose SQLITE_TRANSIENT as `((sqlite3_destructor_type)-1)`
    // which doesn't survive the Swift importer. We reconstruct it manually.
    private static let sqliteTransient = unsafeBitCast(
        OpaquePointer(bitPattern: -1),
        to: sqlite3_destructor_type.self
    )
}
