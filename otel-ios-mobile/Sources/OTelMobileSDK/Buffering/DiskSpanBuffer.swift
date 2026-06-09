import Foundation
import SQLite3
import OTelMobileCore

/// Disk-backed buffer of failed OTLP trace requests. Actor-isolated, raw
/// `sqlite3` C API. Mirrors `DiskLogBuffer` but stores serialized OTLP
/// request bodies instead of log records.
///
/// Used by `PersistingTraceHTTPClient` when the upstream OTLP/HTTP trace
/// exporter's POST fails, and drained by `OTelMobile.recoverSpanRequests`
/// on next launch.
///
/// We persist **raw request bytes** (the pre-serialized, possibly gzipped
/// protobuf body) rather than decoded `SpanData` values. This avoids the
/// SpanExporter-level dead-signal problem: upstream's
/// `OtlpHttpTraceExporter.export()` returns `.success` synchronously and
/// only surfaces failures via its internal HTTP callback, so a
/// SpanExporter decorator cannot see them. Intercepting at the HTTPClient
/// layer gives us the real failure signal, but at that layer we have
/// bytes, not decoded spans. Storing the bytes is the right granularity.
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
        // At-rest protection: persisted spans can carry PII. Protect the DB
        // file (WAL/SHM inherit the directory's class) and exclude the
        // directory from backups. Crash-safe: helper logs + continues.
        FileProtectionHelper.protectDirectory(self.dbPath.deletingLastPathComponent())
        FileProtectionHelper.applyProtection(toFile: self.dbPath)
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
        guard sqlite3_prepare_v2(handle, "SELECT COUNT(*) FROM buffered_span_requests;", -1, &stmt, nil) == SQLITE_OK else { return 0 }
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_step(stmt) == SQLITE_ROW else { return 0 }
        return Int(sqlite3_column_int64(stmt, 0))
    }

    /// Persist a single failed OTLP request.
    public func persist(_ request: BufferedSpanRequest) async {
        guard !closed, db != nil else { return }
        _ = runSql("BEGIN IMMEDIATE TRANSACTION;")
        insertRow(request)
        _ = runSql("COMMIT;")
        pruneIfOverBudget()
    }

    /// Batched persist.
    public func persistBatch(_ requests: [BufferedSpanRequest]) async {
        guard !closed, db != nil, !requests.isEmpty else { return }
        _ = runSql("BEGIN IMMEDIATE TRANSACTION;")
        for req in requests {
            insertRow(req)
        }
        _ = runSql("COMMIT;")
        pruneIfOverBudget()
    }

    private func insertRow(_ req: BufferedSpanRequest) {
        let sql = """
            INSERT OR IGNORE INTO buffered_span_requests
                (request_key, body, session_id, size_bytes, created_at)
                VALUES (?, ?, ?, ?, ?);
        """
        var stmt: OpaquePointer?
        guard let handle = db,
              sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(stmt) }
        _ = req.requestKey.withCString { cstr in
            sqlite3_bind_text(stmt, 1, cstr, -1, Self.sqliteTransient)
        }
        _ = req.body.withUnsafeBytes { raw in
            sqlite3_bind_blob(stmt, 2, raw.baseAddress, Int32(req.body.count), Self.sqliteTransient)
        }
        _ = req.sessionId.withCString { cstr in
            sqlite3_bind_text(stmt, 3, cstr, -1, Self.sqliteTransient)
        }
        sqlite3_bind_int64(stmt, 4, Int64(req.sizeBytes))
        sqlite3_bind_int64(stmt, 5, Int64(req.createdAt.timeIntervalSince1970 * 1000))
        _ = sqlite3_step(stmt)
    }

    /// Read-only snapshot of up to `limit` rows, oldest first (by rowid).
    public func fetchAll(limit: Int) async -> [BufferedSpanRequest] {
        guard !closed, let handle = db, limit > 0 else { return [] }
        let sql = """
            SELECT id, request_key, body, session_id, created_at
            FROM buffered_span_requests
            ORDER BY id ASC
            LIMIT ?;
        """
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int(stmt, 1, Int32(limit))

        var out: [BufferedSpanRequest] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            let id = sqlite3_column_int64(stmt, 0)
            let requestKey = readCString(stmt, 1) ?? ""
            let blobPtr = sqlite3_column_blob(stmt, 2)
            let blobLen = Int(sqlite3_column_bytes(stmt, 2))
            let body = (blobPtr != nil && blobLen > 0)
                ? Data(bytes: blobPtr!, count: blobLen)
                : Data()
            let sessionId = readCString(stmt, 3) ?? ""
            let createdMs = sqlite3_column_int64(stmt, 4)
            let createdAt = Date(timeIntervalSince1970: TimeInterval(createdMs) / 1000)
            out.append(BufferedSpanRequest(
                id: id, requestKey: requestKey, body: body,
                sessionId: sessionId, createdAt: createdAt))
        }
        return out
    }

    /// Delete rows with `id <= anchor`.
    public func deleteUpTo(id anchor: Int64) async {
        guard !closed, let handle = db else { return }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, "DELETE FROM buffered_span_requests WHERE id <= ?;", -1, &stmt, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int64(stmt, 1, anchor)
        _ = sqlite3_step(stmt)
    }

    /// Delete a specific row by id.
    public func delete(id: Int64) async {
        guard !closed, let handle = db else { return }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, "DELETE FROM buffered_span_requests WHERE id = ?;", -1, &stmt, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int64(stmt, 1, id)
        _ = sqlite3_step(stmt)
    }

    /// Remove rows older than `retentionSeconds`.
    public func pruneByTTL() async {
        guard !closed, let handle = db else { return }
        let cutoffMs = Int64((Date().timeIntervalSince1970 - retentionSeconds) * 1000)
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, "DELETE FROM buffered_span_requests WHERE created_at < ?;", -1, &stmt, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int64(stmt, 1, cutoffMs)
        _ = sqlite3_step(stmt)
    }

    /// Current total size in bytes.
    public func totalSizeBytes() async -> Int {
        guard !closed, let handle = db else { return 0 }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, "SELECT COALESCE(SUM(size_bytes), 0) FROM buffered_span_requests;", -1, &stmt, nil) == SQLITE_OK else { return 0 }
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

        let sql = """
        WITH ranked AS (
            SELECT id, size_bytes,
                   SUM(size_bytes) OVER (ORDER BY id ASC) AS running
            FROM buffered_span_requests
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

        let deleteSql = "DELETE FROM buffered_span_requests WHERE id <= ?;"
        var delStmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, deleteSql, -1, &delStmt, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(delStmt) }
        sqlite3_bind_int64(delStmt, 1, deleteThreshold)
        _ = sqlite3_step(delStmt)
    }

    private func fallbackPruneBySize(targetBytes: Int) {
        guard let handle = db else { return }
        var guardIterations = 10_000
        while guardIterations > 0 {
            guardIterations -= 1
            if currentBytes() <= Int64(targetBytes) { return }
            let sql = "DELETE FROM buffered_span_requests WHERE id = (SELECT id FROM buffered_span_requests ORDER BY id ASC LIMIT 1);"
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
        guard sqlite3_prepare_v2(handle, "SELECT COALESCE(SUM(size_bytes), 0) FROM buffered_span_requests;", -1, &stmt, nil) == SQLITE_OK else { return 0 }
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
        // Schema rationale: we persist only the OTLP request BODY plus
        // identifying metadata (session id, timestamps). Endpoint and
        // headers are NOT stored — `recoverSpanRequests` replays to the
        // CURRENTLY configured endpoint with the CURRENTLY configured
        // headers (auth token, dataset, etc.), which is correct for
        // realistic lifecycle events the previous design failed at:
        // token rotation, region migration, dataset rename, and typo
        // fixes between the failed-export and recovery launches. The
        // body is byte-identical OTLP protobuf, so the collector still
        // sees the original spans intact — only the routing reflects
        // the user's current intent.
        _ = runSql("""
            CREATE TABLE IF NOT EXISTS buffered_span_requests (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                request_key   TEXT NOT NULL UNIQUE,
                body          BLOB NOT NULL,
                session_id    TEXT NOT NULL,
                size_bytes    INTEGER NOT NULL,
                created_at    INTEGER NOT NULL
            );
        """)
        _ = runSql("CREATE INDEX IF NOT EXISTS idx_created_at ON buffered_span_requests(created_at);")
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

    // MARK: - Column helpers

    private func readCString(_ stmt: OpaquePointer?, _ col: Int32) -> String? {
        guard let c = sqlite3_column_text(stmt, col) else { return nil }
        return String(cString: c)
    }

    // MARK: - sqlite destructor constants

    private static let sqliteTransient = unsafeBitCast(
        OpaquePointer(bitPattern: -1),
        to: sqlite3_destructor_type.self
    )
}
