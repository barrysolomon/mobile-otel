import Foundation
import SQLite3
import OpenTelemetrySdk

/// Disk-backed span buffer. Actor-isolated, raw `sqlite3` C API. Mirrors
/// `DiskLogBuffer` but stores spans in a separate `buffered_spans` table
/// with its own 50 MB / 24 h budget.
///
/// Used by `PersistingSpanExporter` on export failure and by
/// `OTelMobile.start`'s recovery block on next launch.
///
/// Design: `docs/superpowers/specs/2026-04-22-rn-span-disk-persist-design.md`.
public actor DiskSpanBuffer {
    public nonisolated let dbPath: URL
    public let maxTotalBytes: Int
    public let retentionSeconds: TimeInterval

    private var db: OpaquePointer?
    private var closed: Bool = false

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

    public func shutdown() async {
        if closed { return }
        closed = true
        if let handle = db {
            sqlite3_close(handle)
            self.db = nil
        }
    }

    public func rowCount() async -> Int {
        guard !closed, let handle = db else { return 0 }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, "SELECT COUNT(*) FROM buffered_spans;", -1, &stmt, nil) == SQLITE_OK else { return 0 }
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_step(stmt) == SQLITE_ROW else { return 0 }
        return Int(sqlite3_column_int64(stmt, 0))
    }

    /// Insert each span with INSERT OR IGNORE on span_key; re-presenting
    /// the same span list (e.g. during backoff retries) is a no-op.
    public func persist(_ spans: [SpanData], sessionId: String) async {
        guard !closed, db != nil, !spans.isEmpty else { return }
        let encoder = JSONEncoder()
        _ = runSql("BEGIN IMMEDIATE TRANSACTION;")
        for span in spans {
            guard let buffered = BufferedSpan.from(span, sessionId: sessionId, encoder: encoder) else {
                NSLog("[DiskSpanBuffer] skip span: encode failed")
                continue
            }
            insertRow(buffered)
        }
        _ = runSql("COMMIT;")
        pruneIfOverBudget()
    }

    private func insertRow(_ span: BufferedSpan) {
        let sql = """
            INSERT OR IGNORE INTO buffered_spans
                (span_key, start_time_ns, session_id, record_json, size_bytes, created_at)
                VALUES (?, ?, ?, ?, ?, ?);
        """
        var stmt: OpaquePointer?
        guard let handle = db,
              sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(stmt) }
        _ = span.spanKey.withCString { cstr in
            sqlite3_bind_text(stmt, 1, cstr, -1, Self.sqliteTransient)
        }
        // Signed cast OK: upstream timestamps stay well below 2^63 ns (≈ year 2262).
        sqlite3_bind_int64(stmt, 2, Int64(bitPattern: span.startTimeUnixNano))
        _ = span.sessionId.withCString { cstr in
            sqlite3_bind_text(stmt, 3, cstr, -1, Self.sqliteTransient)
        }
        _ = span.recordData.withUnsafeBytes { raw in
            sqlite3_bind_blob(stmt, 4, raw.baseAddress, Int32(span.recordData.count), Self.sqliteTransient)
        }
        sqlite3_bind_int64(stmt, 5, Int64(span.sizeBytes))
        sqlite3_bind_int64(stmt, 6, Int64(span.createdAt.timeIntervalSince1970 * 1000))
        _ = sqlite3_step(stmt)
    }

    /// Read-only snapshot of up to `limit` rows, oldest first (by rowid).
    /// Used by recovery; caller decides what to delete after a successful
    /// export (see `deleteUpTo(id:)`).
    public func fetchAll(limit: Int) async -> [BufferedSpan] {
        guard !closed, let handle = db, limit > 0 else { return [] }
        let sql = """
            SELECT id, span_key, start_time_ns, session_id, record_json, size_bytes, created_at
            FROM buffered_spans
            ORDER BY id ASC
            LIMIT ?;
        """
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int(stmt, 1, Int32(limit))

        var out: [BufferedSpan] = []
        let decoder = JSONDecoder()
        while sqlite3_step(stmt) == SQLITE_ROW {
            let id = sqlite3_column_int64(stmt, 0)
            let spanKey: String = {
                if let c = sqlite3_column_text(stmt, 1) { return String(cString: c) }
                return ""
            }()
            let startNs = UInt64(bitPattern: sqlite3_column_int64(stmt, 2))
            let sessionId: String = {
                if let c = sqlite3_column_text(stmt, 3) { return String(cString: c) }
                return ""
            }()
            let blobPtr = sqlite3_column_blob(stmt, 4)
            let blobLen = Int(sqlite3_column_bytes(stmt, 4))
            let data = (blobPtr != nil && blobLen > 0)
                ? Data(bytes: blobPtr!, count: blobLen)
                : Data()
            let createdMs = sqlite3_column_int64(stmt, 6)
            let createdAt = Date(timeIntervalSince1970: TimeInterval(createdMs) / 1000)
            let record = (try? decoder.decode(SpanData.self, from: data))
            out.append(BufferedSpan(
                id: id, spanKey: spanKey, startTimeUnixNano: startNs,
                sessionId: sessionId, record: record, recordData: data,
                createdAt: createdAt))
        }
        return out
    }

    /// Delete rows with `id <= anchor`. Called by recovery after a
    /// successful batch export.
    public func deleteUpTo(id anchor: Int64) async {
        guard !closed, let handle = db else { return }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, "DELETE FROM buffered_spans WHERE id <= ?;", -1, &stmt, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int64(stmt, 1, anchor)
        _ = sqlite3_step(stmt)
    }

    /// Remove rows older than `retentionSeconds`. Called from recovery.
    public func pruneByTTL() async {
        guard !closed, let handle = db else { return }
        let cutoffMs = Int64((Date().timeIntervalSince1970 - retentionSeconds) * 1000)
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, "DELETE FROM buffered_spans WHERE created_at < ?;", -1, &stmt, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int64(stmt, 1, cutoffMs)
        _ = sqlite3_step(stmt)
    }

    /// Current total size in bytes. Used by recovery stats + tests.
    public func totalSizeBytes() async -> Int {
        guard !closed, let handle = db else { return 0 }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, "SELECT COALESCE(SUM(size_bytes), 0) FROM buffered_spans;", -1, &stmt, nil) == SQLITE_OK else { return 0 }
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_step(stmt) == SQLITE_ROW else { return 0 }
        return Int(sqlite3_column_int64(stmt, 0))
    }

    private func pruneIfOverBudget() {
        guard !closed, db != nil else { return }
        let total = currentBytes()
        if total <= Int64(maxTotalBytes) { return }
        pruneBySizeInternal(maxBytes: maxTotalBytes)
    }

    private func pruneBySizeInternal(maxBytes: Int) {
        guard let handle = db else { return }
        let total = currentBytes()
        if total <= Int64(maxBytes) { return }
        let needToFree = total - Int64(maxBytes)

        // Window function requires SQLite 3.25+ which ships with iOS 14+.
        // We target iOS 15, so it's guaranteed available.
        // Rows ordered by id ASC is oldest-first because id is AUTOINCREMENT
        // and insertion order follows persist order within a process; on
        // recovery, earlier disk rows replay with lower ids.
        let sql = """
        WITH ranked AS (
            SELECT id, size_bytes,
                   SUM(size_bytes) OVER (ORDER BY id ASC) AS running
            FROM buffered_spans
        )
        SELECT id FROM ranked WHERE running >= ? ORDER BY running ASC LIMIT 1;
        """
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else {
            fallbackPruneBySize(targetBytes: maxBytes)
            return
        }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int64(stmt, 1, needToFree)

        var deleteThreshold: Int64 = -1
        if sqlite3_step(stmt) == SQLITE_ROW {
            deleteThreshold = sqlite3_column_int64(stmt, 0)
        }
        guard deleteThreshold >= 0 else { return }

        let deleteSql = "DELETE FROM buffered_spans WHERE id <= ?;"
        var delStmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, deleteSql, -1, &delStmt, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(delStmt) }
        sqlite3_bind_int64(delStmt, 1, deleteThreshold)
        _ = sqlite3_step(delStmt)
    }

    private func fallbackPruneBySize(targetBytes: Int) {
        guard let handle = db else { return }
        // Bounded at 10k iterations to guard against runaway loop.
        var guardIterations = 10_000
        while guardIterations > 0 {
            guardIterations -= 1
            if currentBytes() <= Int64(targetBytes) { return }
            let sql = "DELETE FROM buffered_spans WHERE id = (SELECT id FROM buffered_spans ORDER BY id ASC LIMIT 1);"
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return }
            let rc = sqlite3_step(stmt)
            sqlite3_finalize(stmt)
            if rc != SQLITE_DONE { return }
        }
    }

    private func currentBytes() -> Int64 {
        guard let handle = db else { return 0 }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, "SELECT COALESCE(SUM(size_bytes), 0) FROM buffered_spans;", -1, &stmt, nil) == SQLITE_OK else { return 0 }
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_step(stmt) == SQLITE_ROW else { return 0 }
        return sqlite3_column_int64(stmt, 0)
    }

    // MARK: - Internal

    private static func resolveDbPath(preferred: URL?) throws -> URL {
        if let preferred = preferred { return preferred }
        let base = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true)
        let dir = base.appendingPathComponent("io.dash0.mobile")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("span-buffer.db")
    }

    private func openAndPrepare() {
        var handle: OpaquePointer?
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        let rc = sqlite3_open_v2(dbPath.path, &handle, flags, nil)
        guard rc == SQLITE_OK, handle != nil else {
            let msg = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "rc=\(rc)"
            NSLog("[DiskSpanBuffer] sqlite3_open_v2 failed at \(dbPath.path): \(msg)")
            if handle != nil { sqlite3_close(handle) }
            return
        }
        self.db = handle

        _ = runSql("PRAGMA journal_mode=WAL;")
        _ = runSql("PRAGMA synchronous=NORMAL;")
        _ = runSql("PRAGMA temp_store=MEMORY;")
        _ = runSql("""
            CREATE TABLE IF NOT EXISTS buffered_spans (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                span_key        TEXT NOT NULL UNIQUE,
                start_time_ns   INTEGER NOT NULL,
                session_id      TEXT NOT NULL,
                record_json     BLOB NOT NULL,
                size_bytes      INTEGER NOT NULL,
                created_at      INTEGER NOT NULL
            );
        """)
        _ = runSql("CREATE INDEX IF NOT EXISTS idx_start_time ON buffered_spans(start_time_ns);")
    }

    @discardableResult
    fileprivate func runSql(_ sql: String) -> Bool {
        guard let handle = db else { return false }
        var err: UnsafeMutablePointer<CChar>?
        let rc = sqlite3_exec(handle, sql, nil, nil, &err)
        if rc != SQLITE_OK {
            if let err = err {
                NSLog("[DiskSpanBuffer] sqlite error: \(String(cString: err))")
                sqlite3_free(err)
            }
            return false
        }
        return true
    }

    // MARK: - sqlite destructor constants

    // SQLite C headers expose SQLITE_TRANSIENT as `((sqlite3_destructor_type)-1)`
    // which doesn't survive the Swift importer. We reconstruct it manually.
    // Matches the `sqliteTransient` pattern in `DiskLogBuffer` so grep finds both.
    private static let sqliteTransient = unsafeBitCast(
        OpaquePointer(bitPattern: -1),
        to: sqlite3_destructor_type.self
    )
}
