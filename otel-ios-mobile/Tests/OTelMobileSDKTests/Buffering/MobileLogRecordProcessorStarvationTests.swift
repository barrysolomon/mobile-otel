import Testing
import Foundation
import OpenTelemetrySdk
@testable import OTelMobileSDK
import OTelMobileCore

// MARK: - Support

fileprivate final class StarvationMockSessionProvider: SessionProvider, @unchecked Sendable {
    var sessionId: String { "starvation-session" }
    func rotateSession() -> String { "starvation-session" }
}

/// Lock-based recording exporter. Deliberately NOT an actor: these tests read
/// `receivedCount` from a libdispatch thread while the cooperative pool is
/// intentionally saturated, so the mock itself must never need a pool slot.
/// (A synchronous method satisfies an `async` protocol requirement, so this
/// conforms regardless of whether `BufferedEventExporter` is async or sync.)
fileprivate final class LockedRecordingExporter: BufferedEventExporter, @unchecked Sendable {
    private let lock = NSLock()
    private var received: [BufferedEvent] = []

    func export(_ events: [BufferedEvent]) -> BufferExportResult {
        lock.lock(); defer { lock.unlock() }
        received.append(contentsOf: events)
        return .success
    }

    var receivedCount: Int {
        lock.lock(); defer { lock.unlock() }
        return received.count
    }
}

/// Minimal lock-based flag readable from any thread without suspending.
fileprivate final class AtomicFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false
    func set() { lock.lock(); value = true; lock.unlock() }
    func get() -> Bool { lock.lock(); defer { lock.unlock() }; return value }
}

/// Saturates the cooperative executor with non-suspending busy-waits until
/// `stop` is set (or `deadline` passes). Spawns one spinner per CPU core
/// plus margin, at DEFAULT priority (the pool is QoS-bucketed — a
/// high-priority spinner would not contend with default-priority tasks).
/// Under `LIBDISPATCH_COOPERATIVE_POOL_STRICT=1` the pool cannot grow past
/// its width, so while these run, NOTHING else can execute on the
/// cooperative executor — precisely the condition the SDK's emit/flush
/// paths must survive.
fileprivate func hogCooperativePool(started: AtomicFlag, stop: AtomicFlag, deadline: Date) {
    for _ in 0..<(ProcessInfo.processInfo.activeProcessorCount + 2) {
        Task.detached {
            started.set()
            while !stop.get() && Date() < deadline {
                // Deliberately no await / no sleep: hold the executor thread.
            }
        }
    }
}

/// Blocks the calling (libdispatch) thread until `flag` is set, up to
/// `timeout` seconds. Returns whether the flag was observed.
fileprivate func waitOnDispatchThread(for flag: AtomicFlag, timeout: TimeInterval) -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
        if flag.get() { return true }
        Thread.sleep(forTimeInterval: 0.001)
    }
    return flag.get()
}

// MARK: - Suite

/// Regression spec for the residual half of issue #66: the SDK's synchronous
/// drain surface and the `onEmit` hot path must make progress WITHOUT a
/// cooperative-executor slot. Named so the ios-ci "Executor-starvation guard"
/// filter (`MobileLogRecordProcessor`) picks this suite up.
///
/// Both tests are only discriminating under
/// `LIBDISPATCH_COOPERATIVE_POOL_STRICT=1` (pool width 1) — exactly the
/// guard lane's environment. Under a normal-width pool the spinner occupies
/// one of N threads and the tests pass trivially.
/// `.serialized`: each test's precondition is exclusive ownership of the
/// saturated pool — running both at once makes them fight over slots.
@Suite("MobileLogRecordProcessorStarvationTests", .serialized)
struct MobileLogRecordProcessorStarvationTests {

    /// The sync drain surface must not require cooperative-pool progress.
    /// Pre-fix: `forceFlushBuffered()` semaphore-waits on a `Task.detached`
    /// that can't run while the pool is hogged → elapsed ≈ spinner lifetime.
    @Test("forceFlushBuffered completes while the cooperative pool is saturated")
    func syncFlushIsPoolIndependent() async {
        let buffer = RAMEventBuffer(capacity: 100)
        let exporter = LockedRecordingExporter()
        let processor = MobileLogRecordProcessor(
            buffer: buffer,
            exporter: exporter,
            sessionProvider: StarvationMockSessionProvider()
        )
        // Seed the buffer BEFORE saturating the pool (injectEvent hops
        // through the buffer, which may need the pool pre-fix).
        await processor.injectEvent(
            BufferedEvent.makeForTesting(
                sequenceId: processor.nextSequenceId(),
                timestampMs: BufferedEvent.currentTimestampMs(),
                payload: "seeded"
            )
        )

        let started = AtomicFlag()
        let stop = AtomicFlag()
        hogCooperativePool(started: started, stop: stop,
                           deadline: Date().addingTimeInterval(6))

        let elapsed: TimeInterval = await withCheckedContinuation { cont in
            DispatchQueue.global().async {
                defer { stop.set() }
                guard waitOnDispatchThread(for: started, timeout: 2) else {
                    cont.resume(returning: -1) // spinner never ran; see #expect below
                    return
                }
                // Let the remaining spinners claim freed pool slots.
                Thread.sleep(forTimeInterval: 0.25)
                let t0 = Date()
                _ = processor.forceFlushBuffered()
                cont.resume(returning: Date().timeIntervalSince(t0))
            }
        }

        #expect(elapsed >= 0, "cooperative-pool spinner never started")
        #expect(elapsed < 3,
                "sync flush stalled \(elapsed)s waiting for a cooperative-pool slot (issue #66 starvation pattern)")
        #expect(exporter.receivedCount == 1)
    }

    /// The emit→flush pipeline must make progress while the pool is
    /// saturated. Pre-fix: every `onEmit` spawns a `Task.detached` append
    /// that can't run while the pool is hogged, so an immediate flush
    /// exports nothing until the spinner dies.
    @Test("events emitted while the pool is saturated are immediately flushable")
    func emitPipelineIsPoolIndependent() async {
        let buffer = RAMEventBuffer(capacity: 100)
        let exporter = LockedRecordingExporter()
        let processor = MobileLogRecordProcessor(
            buffer: buffer,
            exporter: exporter,
            sessionProvider: StarvationMockSessionProvider()
        )

        let started = AtomicFlag()
        let stop = AtomicFlag()
        hogCooperativePool(started: started, stop: stop,
                           deadline: Date().addingTimeInterval(6))

        let outcome: (elapsed: TimeInterval, exported: Int) = await withCheckedContinuation { cont in
            DispatchQueue.global().async {
                defer { stop.set() }
                guard waitOnDispatchThread(for: started, timeout: 2) else {
                    cont.resume(returning: (-1, 0))
                    return
                }
                // Let the remaining spinners claim freed pool slots.
                Thread.sleep(forTimeInterval: 0.25)
                let t0 = Date()
                for i in 0..<10 {
                    let record = ReadableLogRecord(
                        resource: Resource(),
                        instrumentationScopeInfo: InstrumentationScopeInfo(name: "starvation-test"),
                        timestamp: Date(),
                        severity: .info,
                        body: .string("burst-\(i)"),
                        attributes: [:]
                    )
                    processor.onEmit(logRecord: record)
                }
                _ = processor.forceFlushBuffered()
                cont.resume(returning: (Date().timeIntervalSince(t0), exporter.receivedCount))
            }
        }

        #expect(outcome.elapsed >= 0, "cooperative-pool spinner never started")
        #expect(outcome.elapsed < 3,
                "emit+flush stalled \(outcome.elapsed)s waiting for a cooperative-pool slot (issue #66 starvation pattern)")
        #expect(outcome.exported == 10,
                "expected all 10 events flushed; got \(outcome.exported) — onEmit appends were not synchronously durable")
    }
}
