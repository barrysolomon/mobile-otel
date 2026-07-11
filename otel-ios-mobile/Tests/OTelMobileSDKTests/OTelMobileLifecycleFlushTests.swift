import Dispatch
import Foundation
import Testing
@testable import OTelMobileSDK
import OTelMobileCore

// MARK: - Test doubles

/// Exporter that blocks for `delayNanoseconds` before returning success.
/// Used to prove the caller thread is NOT held waiting for the export.
fileprivate final class SlowExporter: BufferedEventExporter, @unchecked Sendable {
    private let lock = NSLock()
    private let delayNanoseconds: UInt64
    private var _exportCallCount = 0

    init(delayNanoseconds: UInt64 = 3_000_000_000) {  // 3 s default
        self.delayNanoseconds = delayNanoseconds
    }

    var exportCallCount: Int {
        get async { lock.lock(); defer { lock.unlock() }; return _exportCallCount }
    }

    func export(_ events: [BufferedEvent]) -> BufferExportResult {
        lock.lock(); _exportCallCount += 1; lock.unlock()
        // Blocking sleep on purpose: export is a synchronous sink and this
        // double simulates slow network I/O holding the EXPORTING thread
        // (never the caller of the async surfaces).
        Thread.sleep(forTimeInterval: Double(delayNanoseconds) / 1_000_000_000)
        return .success
    }
}

// MARK: - Suite

/// Regression suite for the "lifecycle flush blocks main thread" bug.
///
/// Root cause: `OTelMobile.start()` registered three `NotificationCenter`
/// observers with `queue: nil` (= posting thread = main thread for UIKit
/// lifecycle notifications). Each observer synchronously called `forceFlush()`,
/// which calls `MobileLogRecordProcessor.forceFlushBuffered()`, which blocks
/// on a `DispatchSemaphore.wait()` until an async export completes. Result:
/// main thread stalls for the full network round-trip duration on every
/// background/terminate transition.
///
/// Fix: `triggerAutoFlush()` dispatches the flush work onto
/// `OTelMobile.autoFlushQueue` (utility QoS) before calling `forceFlush()`,
/// so the notification handler returns immediately regardless of network speed.
///
/// See also: `onNetworkRestored()` in `MobileLogRecordProcessor` — the
/// correct async-hop pattern existed in the same file; it just wasn't applied
/// to the lifecycle observers.
@Suite("OTelMobile lifecycle flush – non-blocking dispatch")
struct OTelMobileLifecycleFlushTests {

    private func makeSdk(exporter: some BufferedEventExporter) throws -> OTelMobile {
        try OTelMobile.start(
            config: MobileConfig(
                serviceName: "lifecycle-flush-test",
                endpoint: "https://example.com"
            ),
            exporter: exporter
        )
    }

    // MARK: - Non-blocking dispatch

    @Test("triggerAutoFlush returns immediately without blocking the caller thread")
    func triggerAutoFlushIsNonBlocking() throws {
        let slow = SlowExporter(delayNanoseconds: 3_000_000_000)  // 3 s
        let sdk = try makeSdk(exporter: slow)

        // Give the flush something to do.
        sdk.emit(body: "test-event", severity: .info)

        // Measure wall-clock time for triggerAutoFlush() to return.
        // Use DispatchTime (import Dispatch) — not Foundation, which has a CLT overlay issue.
        let before = DispatchTime.now()
        sdk.triggerAutoFlush()
        let elapsedNs = DispatchTime.now().uptimeNanoseconds - before.uptimeNanoseconds

        // 200 ms generous threshold (200_000_000 ns). If forceFlush() ran
        // synchronously, it would block for the full 3 s export delay.
        #expect(elapsedNs < 200_000_000,
                "triggerAutoFlush blocked the caller for \(elapsedNs / 1_000_000)ms — must return immediately")
    }

    @Test("triggerAutoFlush eventually calls the exporter (dispatch is fire-and-observe)")
    func triggerAutoFlushEventuallyExports() async throws {
        let slow = SlowExporter(delayNanoseconds: 50_000_000)  // 50 ms — fast enough to wait for
        let sdk = try makeSdk(exporter: slow)
        sdk.emit(body: "queued-event", severity: .info)

        sdk.triggerAutoFlush()

        // Deadline-based poll, generous on purpose: `autoFlushQueue` is a
        // static SERIAL queue shared by every OTelMobile instance in the
        // process, and the sibling test `triggerAutoFlushIsNonBlocking`
        // parks a 3 s blocking forceFlush() on it — under Swift Testing's
        // parallel execution this test's flush can queue behind that
        // residue. A fixed 2 s poll flaked 3/20 suite runs (2026-07-09,
        // always failing at ~2.1 s with count=0). 15 s covers the 3 s
        // block plus full-suite load margin; the loop exits on the first
        // observed export, so the happy path stays ~200 ms.
        let deadline = DispatchTime.now() + .seconds(15)
        var count = 0
        while count < 1 && DispatchTime.now() < deadline {
            try await Task.sleep(nanoseconds: 100_000_000)  // 100 ms
            count = await slow.exportCallCount
        }
        #expect(count >= 1, "export must be called after triggerAutoFlush — got \(count) calls")
    }

    // MARK: - Queue identity

    @Test("autoFlushQueue is not the main queue")
    func autoFlushQueueIsNotMain() {
        // The queue label is the observable identity without resorting to
        // unsupported DispatchQueue comparison APIs.
        let label = OTelMobile.autoFlushQueueLabel
        #expect(label == "io.dash0.mobile.auto-flush",
                "unexpected queue label: \(label)")
    }
}
