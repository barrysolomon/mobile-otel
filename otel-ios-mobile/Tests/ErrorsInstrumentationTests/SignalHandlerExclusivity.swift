import Testing

/// Scoping trait that serializes every test which mutates the process-global
/// crash-handler state — `NSSetUncaughtExceptionHandler`, per-signal
/// `sigaction` dispositions, and `ErrorsInstrumentation.shared`'s
/// install/uninstall state machine.
///
/// Signal handlers are process-wide singletons. Both
/// `CrashHandlerChainingTests` (which stages host-app handlers with raw
/// `sigaction` calls) and `ErrorsInstrumentationTests` (which calls
/// `install()`/`uninstall()` throughout) mutate them, and Swift Testing runs
/// suites in parallel — `.serialized` only orders tests *within* one suite.
/// The deadly interleaving: suite A's `install()` flips the singleton's
/// `installed` flag, so suite B's `install()` silently no-ops and captures
/// nothing — `previousSignalHandlers` then holds A's snapshot, not the host
/// handler B just staged, and B's capture/restore expectations all fail
/// (the pre-existing CI flake on the device-tests lane).
///
/// This trait wraps each test in a process-global FIFO async mutex, so at
/// most one signal-handler-mutating test runs at a time while unrelated
/// suites keep running in parallel. Waiters SUSPEND on a continuation —
/// never block — because parking a cooperative-pool thread behind a
/// semaphore is exactly the executor-starvation failure mode of issue #66.
struct SignalHandlerExclusivityTrait: TestTrait, SuiteTrait, TestScoping {
    var isRecursive: Bool { true }

    func provideScope(
        for test: Test,
        testCase: Test.Case?,
        performing function: @Sendable () async throws -> Void
    ) async throws {
        // With `isRecursive`, this scope is entered for the suite container
        // AND again for each test. The mutex is non-reentrant, so acquire
        // only at test granularity.
        guard testCase != nil else {
            try await function()
            return
        }
        await SignalHandlerGate.shared.acquire()
        do {
            try await function()
        } catch {
            await SignalHandlerGate.shared.release()
            throw error
        }
        await SignalHandlerGate.shared.release()
    }
}

extension Trait where Self == SignalHandlerExclusivityTrait {
    /// Serializes this test/suite against every other suite tagged with the
    /// same trait, so process-global signal/exception handler mutations never
    /// interleave. See `SignalHandlerExclusivityTrait`.
    static var exclusiveSignalHandlers: SignalHandlerExclusivityTrait { .init() }
}

/// Process-global FIFO mutex. `acquire()` suspends (does not block) until
/// the lock is free; `release()` hands the lock to the oldest waiter so a
/// steady stream of new arrivals can't starve one out.
private actor SignalHandlerGate {
    static let shared = SignalHandlerGate()

    private var locked = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func acquire() async {
        if !locked {
            locked = true
            return
        }
        await withCheckedContinuation { waiters.append($0) }
    }

    func release() {
        if waiters.isEmpty {
            locked = false
        } else {
            // Ownership transfers directly to the next waiter; `locked`
            // stays true across the handoff.
            waiters.removeFirst().resume()
        }
    }
}
