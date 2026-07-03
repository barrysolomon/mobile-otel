import Foundation
import Testing
@testable import ErrorsInstrumentation

/// Crash-loop self-disable (SDK_SAFETY): the SDK counts consecutive
/// crash-marker launches and refuses to initialize once the count reaches
/// `MobileConfig.crashLoopThreshold`. A clean launch (no crash marker)
/// resets the counter, so the guard self-clears without any external
/// intervention.
///
/// Mirrors Android `CrashLoopDetectorTest` — zero platform drift.
/// `.serialized` + `.isolatedCrashMarker` for the same reasons as
/// `CrashRecoveryTests`: the marker file is process-global.
@Suite("CrashLoopDetector", .serialized, .isolatedCrashMarker)
struct CrashLoopDetectorTests {

    /// Fresh, isolated UserDefaults per test so counter state never bleeds
    /// between tests or into the developer's real defaults.
    private func makeDefaults() -> UserDefaults {
        let suite = "crash-loop-tests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return defaults
    }

    private func setCrashMarker() {
        ErrorsInstrumentation.writeMarker(
            kind: "NSException", name: "Boom", reason: "test", frames: []
        )
    }

    @Test("clean launch proceeds with zero count")
    func cleanLaunch() {
        let detector = CrashLoopDetector(defaults: makeDefaults())
        #expect(detector.evaluateOnLaunch(threshold: 3) == .proceed)
        #expect(detector.consecutiveCrashCount == 0)
    }

    @Test("crash below threshold increments count and proceeds")
    func crashBelowThreshold() {
        let detector = CrashLoopDetector(defaults: makeDefaults())
        setCrashMarker()
        #expect(detector.evaluateOnLaunch(threshold: 3) == .proceed)
        #expect(detector.consecutiveCrashCount == 1)
    }

    @Test("proceeding leaves the crash marker for recovery to consume")
    func proceedKeepsMarker() {
        // The detector must NOT eat the marker on the proceed path —
        // emitAnyPendingCrash still needs it to emit app.crash.
        let detector = CrashLoopDetector(defaults: makeDefaults())
        setCrashMarker()
        _ = detector.evaluateOnLaunch(threshold: 3)
        guard let url = ErrorsInstrumentation.crashMarkerURL() else {
            Issue.record("crashMarkerURL returned nil")
            return
        }
        #expect(ErrorsInstrumentation.fileExistsForTesting(at: url))
    }

    @Test("reaching threshold disables and clears the marker")
    func thresholdDisables() {
        let detector = CrashLoopDetector(defaults: makeDefaults())
        setCrashMarker()
        _ = detector.evaluateOnLaunch(threshold: 3) // count 1
        setCrashMarker()
        _ = detector.evaluateOnLaunch(threshold: 3) // count 2
        setCrashMarker()
        #expect(detector.evaluateOnLaunch(threshold: 3) == .disabled)
        #expect(detector.consecutiveCrashCount == 3)
        // Marker is cleared on the disable path: with the SDK inert next
        // launch, nothing would ever consume it, and a stale marker would
        // keep the SDK disabled forever. Clearing it makes the next launch
        // count as clean, which resets the counter — self-clearing.
        guard let url = ErrorsInstrumentation.crashMarkerURL() else {
            Issue.record("crashMarkerURL returned nil")
            return
        }
        #expect(!ErrorsInstrumentation.fileExistsForTesting(at: url))
    }

    @Test("clean launch after crashes resets the counter")
    func cleanLaunchResets() {
        let detector = CrashLoopDetector(defaults: makeDefaults())
        setCrashMarker()
        _ = detector.evaluateOnLaunch(threshold: 3)
        setCrashMarker()
        _ = detector.evaluateOnLaunch(threshold: 3)
        #expect(detector.consecutiveCrashCount == 2)

        // No marker this time: previous session ended cleanly.
        ErrorsInstrumentation.removeMarkerForTesting()
        #expect(detector.evaluateOnLaunch(threshold: 3) == .proceed)
        #expect(detector.consecutiveCrashCount == 0)
    }

    @Test("threshold zero disables the guard entirely")
    func thresholdZeroIsOff() {
        let detector = CrashLoopDetector(defaults: makeDefaults())
        for _ in 0..<5 {
            setCrashMarker()
            #expect(detector.evaluateOnLaunch(threshold: 0) == .proceed)
        }
        // Guard off → no counting side effects either.
        #expect(detector.consecutiveCrashCount == 0)
    }

    @Test("count survives across evaluations via UserDefaults")
    func countPersists() {
        let defaults = makeDefaults()
        setCrashMarker()
        _ = CrashLoopDetector(defaults: defaults).evaluateOnLaunch(threshold: 10)
        setCrashMarker()
        _ = CrashLoopDetector(defaults: defaults).evaluateOnLaunch(threshold: 10)
        setCrashMarker()
        _ = CrashLoopDetector(defaults: defaults).evaluateOnLaunch(threshold: 10)
        #expect(CrashLoopDetector(defaults: defaults).consecutiveCrashCount == 3)
    }
}
