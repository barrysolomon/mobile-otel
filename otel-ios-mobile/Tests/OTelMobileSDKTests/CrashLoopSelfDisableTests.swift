import Foundation
import Testing
@testable import OTelMobileSDK
@testable import ErrorsInstrumentation

/// Wiring test for crash-loop self-disable: the production
/// `OTelMobile.start(config:diskBuffer:spanDiskBuffer:)` must consult
/// `CrashLoopDetector` BEFORE building exporters or installing any
/// instrumentation, and on a `.disabled` verdict return an inert instance
/// (`crashLoopDisabled == true`, no-op exporter, zero instrumentation
/// installs) instead of throwing into the host.
///
/// The guard deliberately lives ONLY on the production path — the internal
/// `start(config:exporter:)` test harness stays unguarded so a stale crash
/// marker on a dev machine can never self-disable the test suite.
@Suite("CrashLoopSelfDisable", .serialized)
struct CrashLoopSelfDisableTests {

    @Test("production start returns inert instance when crash loop threshold is reached")
    func startDegradesOnCrashLoop() async throws {
        // Isolate the marker file exactly like CrashMarkerIsolationTrait does.
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("crash-loop-wiring-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let marker = dir.appendingPathComponent("io.dash0.mobile.crash-marker", isDirectory: false)

        // Detector state lives in UserDefaults.standard on the production
        // path (the test-runner process's own domain) — clean it up.
        defer { UserDefaults.standard.removeObject(forKey: CrashLoopDetector.countKey) }
        UserDefaults.standard.removeObject(forKey: CrashLoopDetector.countKey)

        try await ErrorsInstrumentation.$crashMarkerURLOverrideForTesting.withValue(marker) {
            ErrorsInstrumentation.writeMarker(
                kind: "NSException", name: "Boom", reason: "loop", frames: []
            )
            let config = MobileConfig(
                serviceName: "crash-loop-test",
                endpoint: "https://unused.invalid",
                crashLoopThreshold: 1
            )
            let mobile = try OTelMobile.start(config: config)
            #expect(mobile.crashLoopDisabled)
        }
    }

    @Test("production start proceeds normally on a clean launch")
    func startProceedsWhenClean() async throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("crash-loop-wiring-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let marker = dir.appendingPathComponent("io.dash0.mobile.crash-marker", isDirectory: false)

        defer { UserDefaults.standard.removeObject(forKey: CrashLoopDetector.countKey) }
        UserDefaults.standard.removeObject(forKey: CrashLoopDetector.countKey)

        try await ErrorsInstrumentation.$crashMarkerURLOverrideForTesting.withValue(marker) {
            // No marker written — clean previous session. Auto-capture is
            // turned off so this start doesn't install process-global
            // instrumentation (signal handlers, swizzles) into the test
            // runner; the assertion targets only the crash-loop gate.
            let config = MobileConfig(
                serviceName: "crash-loop-test",
                endpoint: "https://unused.invalid",
                autoCaptureOptions: [],
                enablePolicyPolling: false,
                crashLoopThreshold: 1
            )
            let mobile = try OTelMobile.start(config: config)
            #expect(!mobile.crashLoopDisabled)
        }
    }
}
