import Foundation
import Testing
@testable import ErrorsInstrumentation

/// Scoping trait that points the process-global crash-marker file at a
/// unique per-test temp path.
///
/// The crash marker (`~/Library/Caches/io.dash0.mobile.crash-marker`) is a
/// process-wide singleton. Both `CrashRecoveryTests` and
/// `ErrorsInstrumentationTests` write/read/**delete** it, and Swift Testing
/// runs suites in parallel — so `.serialized` (which only orders tests
/// *within* a suite) does not prevent the two suites from racing on the file.
/// One suite's "empty marker" / "malformed marker" / delete-on-read step can
/// land between another suite's write and read, so the read sees empty or
/// missing data.
///
/// This trait gives every test its own marker file via a `@TaskLocal`
/// override, eliminating cross-suite contention. Production behavior is
/// unchanged — the override is only ever set here, never in the SDK.
struct CrashMarkerIsolationTrait: TestTrait, SuiteTrait, TestScoping {
    var isRecursive: Bool { true }

    func provideScope(
        for test: Test,
        testCase: Test.Case?,
        performing function: @Sendable () async throws -> Void
    ) async throws {
        // Unique directory per scope; keep the canonical file name so tests
        // asserting `lastPathComponent == "io.dash0.mobile.crash-marker"` and
        // "parent dir exists" still hold.
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("crash-marker-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        let marker = dir.appendingPathComponent("io.dash0.mobile.crash-marker", isDirectory: false)
        try await ErrorsInstrumentation.$crashMarkerURLOverrideForTesting.withValue(marker) {
            try await function()
        }
    }
}

extension Trait where Self == CrashMarkerIsolationTrait {
    /// Isolates the process-global crash-marker file to a per-test temp path
    /// so parallel suites don't race on it. See `CrashMarkerIsolationTrait`.
    static var isolatedCrashMarker: CrashMarkerIsolationTrait { .init() }
}
