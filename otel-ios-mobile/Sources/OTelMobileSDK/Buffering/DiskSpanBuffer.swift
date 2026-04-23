import Foundation
import SQLite3
import OpenTelemetrySdk

private let SQLITE_TRANSIENT_FN = unsafeBitCast(
    OpaquePointer(bitPattern: -1),
    to: sqlite3_destructor_type.self
)

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
        guard sqlite3_open_v2(dbPath.path, &handle, flags, nil) == SQLITE_OK, handle != nil else {
            NSLog("[DiskSpanBuffer] sqlite3_open_v2 failed at \(dbPath.path)")
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
}
