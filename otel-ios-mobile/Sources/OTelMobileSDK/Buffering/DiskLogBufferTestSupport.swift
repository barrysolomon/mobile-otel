/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation

/// Test-only helpers for `DiskLogBuffer`. Lives in the SDK module so test
/// files can construct a buffer pointed at a temp-directory path without
/// importing Foundation directly — Swift Testing's `_Testing_Foundation`
/// cross-import overlay is shipped incomplete in the macOS Command Line
/// Tools, so test files can't `import Foundation`. Matches the precedent
/// set by `BufferedEventTestSupport.swift` and
/// `MobileLogRecordProcessorTestSupport.swift`.
extension DiskLogBuffer {
    /// Opaque handle tests hold across create/close cycles — bundles the
    /// URL that tests can't name directly because they cannot import
    /// Foundation. Treat it as a cookie. See below for the factory that
    /// mints one.
    public struct TestPath: Sendable {
        let url: URL
    }

    /// Returns an opaque handle referring to a throwaway temp-dir sqlite
    /// path. Matched with `DiskLogBuffer.makeForTesting(path:)` below.
    public static func makeTestPath(named name: String = "disk-log-buffer-test.db") -> TestPath {
        let tempDir = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        let uniqueSubdir = tempDir.appendingPathComponent(
            "dash0-ios-tests-\(UUID().uuidString)",
            isDirectory: true
        )
        try? FileManager.default.createDirectory(
            at: uniqueSubdir,
            withIntermediateDirectories: true
        )
        return TestPath(url: uniqueSubdir.appendingPathComponent(name, isDirectory: false))
    }

    /// Constructs a `DiskLogBuffer` from a test-only path handle. Mirrors
    /// the public `init(dbPath:...)` but lets test files avoid importing
    /// Foundation.
    public static func makeForTesting(
        path: TestPath,
        maxTotalBytes: Int = 50 * 1024 * 1024,
        retentionSeconds: Double = 24 * 3600
    ) async throws -> DiskLogBuffer {
        try await DiskLogBuffer(
            dbPath: path.url,
            maxTotalBytes: maxTotalBytes,
            retentionSeconds: retentionSeconds
        )
    }

    /// Deletes the backing sqlite file (and its WAL/SHM sidecars).
    public static func removeTestFiles(at path: TestPath) {
        let fm = FileManager.default
        try? fm.removeItem(at: path.url)
        try? fm.removeItem(at: path.url.appendingPathExtension("wal"))
        try? fm.removeItem(at: path.url.appendingPathExtension("shm"))
        let walSidecar = URL(fileURLWithPath: path.url.path + "-wal")
        let shmSidecar = URL(fileURLWithPath: path.url.path + "-shm")
        try? fm.removeItem(at: walSidecar)
        try? fm.removeItem(at: shmSidecar)
    }
}
