import Foundation
import Testing
@testable import ErrorsInstrumentation

// Top-level @convention(c)-compatible recording handlers. Signal-handler and
// NSException-handler ABIs require C function pointers, which can't capture
// state — so the evidence lives in nonisolated(unsafe) globals.
nonisolated(unsafe) var previousExceptionHandlerRan = false
nonisolated(unsafe) var previousSignalHandlerRan = false

private func recordingExceptionHandler(_ exception: NSException) {
    previousExceptionHandlerRan = true
}

private func recordingSignalHandler(_ sig: Int32) {
    previousSignalHandlerRan = true
}

/// Crash-handler coexistence (the iOS mirror of Android's
/// `CrashHandlerChainingTest`, deferred at P0 and closed here): an app
/// running Sentry / Firebase Crashlytics / PLCrashReporter installs crash
/// handlers BEFORE or AFTER this SDK — both must keep working.
///
/// What's provable in-process:
/// - NSException path: the full chain (our trampoline runs, then the
///   previously-installed handler) — handlers are plain function calls.
/// - Signal path: the sigaction STATE MACHINE — install captures the
///   previous disposition (it used to capture nothing), uninstall restores
///   it (it used to reset to SIG_DFL, silently uninstalling the host's
///   crash reporter). Actually delivering a fatal signal would kill the
///   test process; the in-handler chain call is exercised by the
///   real-crash device legs (`run-platform-e2e.sh`).
@Suite(.serialized, .exclusiveSignalHandlers) struct CrashHandlerChainingTests {

    /// `ErrorsInstrumentation.shared` is a process-wide singleton and other
    /// suites install() without a matching uninstall(), so `installed` can
    /// arrive here `true` — making our install() a silent no-op that
    /// captures nothing. Reset to a known "not installed" state first
    /// (safe: `.exclusiveSignalHandlers` guarantees nobody re-installs
    /// concurrently, and each test stages its own handlers after this).
    private func resetToUninstalled() {
        ErrorsInstrumentation.shared.uninstall()
    }

    // MARK: NSException path

    @Test("a pre-installed NSException handler still runs after ours (chained)")
    func nsExceptionChainsToPreviousHandler() throws {
        resetToUninstalled()
        previousExceptionHandlerRan = false
        let original = NSGetUncaughtExceptionHandler()
        defer { NSSetUncaughtExceptionHandler(original) }

        // Host app installs its crash reporter FIRST…
        NSSetUncaughtExceptionHandler(recordingExceptionHandler)
        // …then the SDK installs.
        let processor = LogCapture()
        ErrorsInstrumentation.shared.install(logger: makeLogger(processor: processor))
        defer { ErrorsInstrumentation.shared.uninstall() }

        // The SDK must have taken over the slot…
        let installed = try #require(NSGetUncaughtExceptionHandler())
        // …and a crash flowing through it must reach the host's reporter too.
        installed(NSException(name: .genericException, reason: "chain-test", userInfo: nil))
        #expect(previousExceptionHandlerRan,
                "the previously-installed exception handler must still receive the crash")
    }

    @Test("uninstall restores the previously-installed NSException handler")
    func nsExceptionUninstallRestores() {
        resetToUninstalled()
        let original = NSGetUncaughtExceptionHandler()
        defer { NSSetUncaughtExceptionHandler(original) }

        NSSetUncaughtExceptionHandler(recordingExceptionHandler)
        let processor = LogCapture()
        ErrorsInstrumentation.shared.install(logger: makeLogger(processor: processor))
        ErrorsInstrumentation.shared.uninstall()

        let restored = NSGetUncaughtExceptionHandler()
        let restoredPtr = restored.map { unsafeBitCast($0, to: Int.self) } ?? 0
        let expectedPtr = unsafeBitCast(
            recordingExceptionHandler as @convention(c) (NSException) -> Void, to: Int.self)
        #expect(restoredPtr == expectedPtr,
                "uninstall must hand the slot back to the host's reporter, not clear it")
    }

    // MARK: Signal path (sigaction state machine)

    @Test("install captures the host's signal handler; uninstall restores it")
    func signalDispositionCapturedAndRestored() throws {
        resetToUninstalled()
        previousSignalHandlerRan = false
        let sig = SIGTRAP // one of ErrorsInstrumentation.fatalSignals

        // Remember the test runner's own disposition and restore it at the end.
        var runnerOriginal = sigaction()
        sigaction(sig, nil, &runnerOriginal)
        defer { var restore = runnerOriginal; sigaction(sig, &restore, nil) }

        // Host app's crash reporter installs its signal handler FIRST…
        var hostAction = sigaction()
        hostAction.__sigaction_u.__sa_handler = recordingSignalHandler
        sigemptyset(&hostAction.sa_mask)
        hostAction.sa_flags = 0
        sigaction(sig, &hostAction, nil)

        // …then the SDK installs.
        let processor = LogCapture()
        ErrorsInstrumentation.shared.install(logger: makeLogger(processor: processor))

        // 1. The SDK owns the live disposition now…
        var live = sigaction()
        sigaction(sig, nil, &live)
        let liveHandler = unsafeBitCast(live.__sigaction_u.__sa_handler, to: Int.self)
        let hostHandler = unsafeBitCast(hostAction.__sigaction_u.__sa_handler, to: Int.self)
        #expect(liveHandler != hostHandler, "SDK must have installed its own handler")

        // 2. …and it CAPTURED the host's handler for chaining (this was the
        //    bug: nothing was captured, so the host's crash reporter was
        //    silently disconnected).
        let captured = try #require(ErrorsInstrumentation.previousSignalHandlers[Int(sig)],
                                    "install must capture the previous disposition")
        let capturedHandler = unsafeBitCast(captured.__sigaction_u.__sa_handler, to: Int.self)
        #expect(capturedHandler == hostHandler,
                "the captured handler must be the host's, ready to be chained at crash time")

        // 3. Uninstall hands the disposition back to the host's reporter
        //    (it used to reset to SIG_DFL).
        ErrorsInstrumentation.shared.uninstall()
        var after = sigaction()
        sigaction(sig, nil, &after)
        let afterHandler = unsafeBitCast(after.__sigaction_u.__sa_handler, to: Int.self)
        #expect(afterHandler == hostHandler,
                "uninstall must restore the host's signal handler, not SIG_DFL")
    }

    @Test("install with no prior handler captures SIG_DFL and uninstall returns to it")
    func signalDispositionDefaultRoundTrip() {
        resetToUninstalled()
        let sig = SIGPIPE
        var runnerOriginal = sigaction()
        sigaction(sig, nil, &runnerOriginal)
        defer { var restore = runnerOriginal; sigaction(sig, &restore, nil) }

        // Ensure a clean SIG_DFL starting state.
        signal(sig, SIG_DFL)

        let processor = LogCapture()
        ErrorsInstrumentation.shared.install(logger: makeLogger(processor: processor))
        ErrorsInstrumentation.shared.uninstall()

        var after = sigaction()
        sigaction(sig, nil, &after)
        #expect(sigactionHandlerIsDefaultOrIgnore(after),
                "with no host handler, uninstall must land back on SIG_DFL")
    }
}
