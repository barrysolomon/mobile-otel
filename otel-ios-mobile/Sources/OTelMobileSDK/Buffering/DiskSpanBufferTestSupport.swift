import Foundation
import SQLite3
import OpenTelemetryApi
import OpenTelemetrySdk

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
        // SQLite WAL/SHM sidecars are created alongside the .db once writes
        // begin (Task 3). Mirror the pattern from `DiskLogBufferTestSupport`.
        let walSidecar = URL(fileURLWithPath: url.path + "-wal")
        let shmSidecar = URL(fileURLWithPath: url.path + "-shm")
        try? fm.removeItem(at: walSidecar)
        try? fm.removeItem(at: shmSidecar)
        // Only remove the parent directory if we created it in `tempDbPath()`.
        // Guard against the helper being pointed at arbitrary paths.
        let parent = url.deletingLastPathComponent()
        if parent.lastPathComponent.hasPrefix("dash0-span-test-") {
            try? fm.removeItem(at: parent)
        }
    }

    public static func fileExists(_ url: URL) -> Bool {
        FileManager.default.fileExists(atPath: url.path)
    }

    /// Build a minimal `SpanData` for tests. `SpanData`'s memberwise init is
    /// internal to `OpenTelemetrySdk`, so we can't invoke it directly — we
    /// route through a real `TracerProviderSdk` and end the span to get a
    /// realistic `SpanData`. Unique traceId/spanId per call (upstream
    /// generates them).
    public static func fakeSpan(
        name: String,
        startSecondsAgo: TimeInterval = 5
    ) -> SpanData {
        let tracer = tracerProvider.get(
            instrumentationName: "DiskSpanBufferTestSupport",
            instrumentationVersion: nil
        )
        let span = tracer
            .spanBuilder(spanName: name)
            .setSpanKind(spanKind: .client)
            .setStartTime(time: Date().addingTimeInterval(-startSecondsAgo))
            .startSpan()
        span.end()
        // safe: alwaysOn sampler → PropagatedSpan never returned → ReadableSpan always.
        // `as!` is acceptable in test-support code and guarded by this
        // invariant; see SDK_SAFETY.md for SDK-source vs test-support policy.
        return (span as! ReadableSpan).toSpanData()
    }

    /// UPDATE the first row's `record_json` column to the given bytes. Test-only;
    /// used to exercise the corrupt-row path in `fetchAll`.
    public static func overwriteRecordJson(dbPath: URL, bytes: [UInt8]) {
        var db: OpaquePointer?
        guard sqlite3_open_v2(dbPath.path, &db,
                              SQLITE_OPEN_READWRITE, nil) == SQLITE_OK,
              let handle = db else { return }
        defer { sqlite3_close(handle) }

        let sql = "UPDATE buffered_spans SET record_json = ?, size_bytes = ? WHERE id = (SELECT MIN(id) FROM buffered_spans);"
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

    /// Byte-equality check for `BufferedSpan.recordData`. Lives here so test
    /// files don't need to `import Foundation` just to build a `Data`.
    public static func recordDataMatches(_ span: BufferedSpan, bytes: [UInt8]) -> Bool {
        span.recordData == Data(bytes)
    }

    // MARK: - Internal

    private static let tracerProvider: TracerProviderSdk = {
        // Pin `alwaysOn` so `SpanBuilderSdk.prepareSpan` never returns a
        // non-`ReadableSpan` `PropagatedSpan` — the `as! ReadableSpan` cast
        // in `fakeSpan` then stays safe.
        TracerProviderBuilder()
            .with(sampler: Samplers.alwaysOn)
            .build()
    }()
}
