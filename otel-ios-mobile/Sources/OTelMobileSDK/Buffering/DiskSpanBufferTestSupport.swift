import Foundation
import SQLite3

/// Test helpers for `DiskSpanBuffer`. Lives in the SDK target (not the test
/// target) because CLT's `_Testing_Foundation` overlay ships without its
/// `Modules/` directory — test files can't `import Foundation`. See
/// `otel-ios-mobile/CLAUDE.md` gotcha #1.
public enum DiskSpanBufferTestSupport {
    public static func tempDbPath() -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("dash0-span-test-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(
            at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("spans.db")
    }

    public static func removeFile(_ url: URL) {
        let fm = FileManager.default
        try? fm.removeItem(at: url)
        let walSidecar = URL(fileURLWithPath: url.path + "-wal")
        let shmSidecar = URL(fileURLWithPath: url.path + "-shm")
        try? fm.removeItem(at: walSidecar)
        try? fm.removeItem(at: shmSidecar)
        let parent = url.deletingLastPathComponent()
        if parent.lastPathComponent.hasPrefix("dash0-span-test-") {
            try? fm.removeItem(at: parent)
        }
    }

    public static func fileExists(_ url: URL) -> Bool {
        FileManager.default.fileExists(atPath: url.path)
    }

    /// Build a fake pending `BufferedSpanRequest` for tests. Endpoint
    /// and headers are no longer part of the persisted shape — replays
    /// route to the user's current `MobileConfig` at recovery time, so
    /// only the body + session-id need to round-trip through disk.
    public static func fakeRequest(
        bodyBytes: [UInt8] = [0x08, 0x01]
    ) -> BufferedSpanRequest {
        BufferedSpanRequest.pending(
            body: Data(bodyBytes),
            sessionId: "test-session"
        )
    }

    /// UPDATE the first row's `body` column to the given bytes. Test-only;
    /// used to exercise corrupt-row paths.
    public static func overwriteBody(dbPath: URL, bytes: [UInt8]) {
        var db: OpaquePointer?
        guard sqlite3_open_v2(dbPath.path, &db,
                              SQLITE_OPEN_READWRITE, nil) == SQLITE_OK,
              let handle = db else { return }
        defer { sqlite3_close(handle) }

        let sql = "UPDATE buffered_span_requests SET body = ?, size_bytes = ? WHERE id = (SELECT MIN(id) FROM buffered_span_requests);"
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(stmt) }

        let sqliteTransient = unsafeBitCast(
            OpaquePointer(bitPattern: -1),
            to: sqlite3_destructor_type.self)
        _ = bytes.withUnsafeBufferPointer { buf in
            sqlite3_bind_blob(stmt, 1, buf.baseAddress, Int32(bytes.count), sqliteTransient)
        }
        sqlite3_bind_int64(stmt, 2, Int64(bytes.count))
        _ = sqlite3_step(stmt)
    }

    /// Byte-equality check for `BufferedSpanRequest.body`. Lives here so
    /// test files don't need to `import Foundation` to build a `Data`.
    public static func bodyMatches(_ req: BufferedSpanRequest, bytes: [UInt8]) -> Bool {
        req.body == Data(bytes)
    }
}
