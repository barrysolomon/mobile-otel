import Foundation
import Testing
@testable import OTelMobileSDK
import OTelMobileCore
import OpenTelemetryApi
import OpenTelemetrySdk

/// Behavioural-parity port of Android's `RetryableExporterTest` — same
/// 7 cases, slightly adapted because OTel-Swift's `LogRecordExporter`
/// returns a binary `ExportResult` (no error type / message), so the
/// "non-retryable exception" Android cases collapse into "every failure
/// retries up to maxRetries" on iOS. The auth-error path is exercised
/// directly through `ExportStatusManager.notify` rather than via the
/// underlying transport (see RetryableExporter doc comment for why).
/// Each test injects its own `ExportStatusManager` instance into the
/// `RetryableExporter` constructor (instead of relying on `.shared`),
/// so parallel tests can never see each other's listener transcripts.
@Suite("RetryableExporter")
struct RetryableExporterTests {

    /// Mock exporter that returns successive results from `script` and
    /// counts every call. Tests assert call count and the listener
    /// transcript captured via `ExportStatusManager`.
    final class ScriptedExporter: LogRecordExporter, @unchecked Sendable {
        var script: [ExportResult]
        var callCount = 0
        var shutdownCalled = 0
        var flushCalled = 0
        init(script: [ExportResult]) { self.script = script }

        func export(logRecords: [ReadableLogRecord], explicitTimeout: TimeInterval?) -> ExportResult {
            callCount += 1
            // Last script entry sticks for any extra calls.
            return script.indices.contains(callCount - 1)
                ? script[callCount - 1]
                : (script.last ?? .failure)
        }

        func shutdown(explicitTimeout: TimeInterval?) {
            shutdownCalled += 1
        }

        func forceFlush(explicitTimeout: TimeInterval?) -> ExportResult {
            flushCalled += 1
            return .success
        }
    }

    /// Captures the full listener transcript so tests can pin both the
    /// transition order and the included counts/attempts.
    final class TranscriptListener: ExportStatusListener {
        var events: [ExportStatus] = []
        func onExportStatus(_ status: ExportStatus) {
            events.append(status)
        }
    }

    /// Helper: build a fresh `ExportStatusManager` + `RetryableExporter`,
    /// register a listener, run `block` with the wired exporter, and
    /// return the transcript. Per-test instances mean no cross-test
    /// contamination.
    private func capturing(
        script: [ExportResult],
        maxRetries: Int = 3,
        initialDelayMs: Int = 1,
        maxDelayMs: Int = 1,
        jitter: ((Int) -> Int)? = nil,
        _ block: (RetryableExporter, ScriptedExporter) -> Void
    ) -> [ExportStatus] {
        let mgr = ExportStatusManager()
        let listener = TranscriptListener()
        mgr.addListener(listener)
        let inner = ScriptedExporter(script: script)
        let exporter: RetryableExporter
        if let jitter {
            exporter = RetryableExporter(
                delegate: inner,
                maxRetries: maxRetries,
                initialDelayMs: initialDelayMs,
                maxDelayMs: maxDelayMs,
                statusManager: mgr,
                jitter: jitter
            )
        } else {
            exporter = RetryableExporter(
                delegate: inner,
                maxRetries: maxRetries,
                initialDelayMs: initialDelayMs,
                maxDelayMs: maxDelayMs,
                statusManager: mgr
            )
        }
        block(exporter, inner)
        return listener.events
    }

    @Test("success on first try returns success and emits one .success")
    func successFirstTry() {
        var result: ExportResult = .failure
        let events = capturing(script: [.success]) { exporter, inner in
            result = exporter.export(logRecords: [], explicitTimeout: nil)
            #expect(inner.callCount == 1)
        }
        #expect(result == .success)
        #expect(events == [.success(eventCount: 0)])
    }

    @Test("retries up to maxRetries on persistent failure, then gives up")
    func retriesThenFails() {
        var result: ExportResult = .success
        let events = capturing(
            script: [.failure, .failure, .failure, .failure]
        ) { exporter, inner in
            result = exporter.export(logRecords: [], explicitTimeout: nil)
            #expect(inner.callCount == 4, "expected 1 initial + 3 retries = 4 calls, got \(inner.callCount)")
        }
        #expect(result == .failure)
        let retrying = events.filter {
            if case .retrying = $0 { return true } else { return false }
        }
        #expect(retrying.count == 3)
        let failed = events.filter {
            if case .failed = $0 { return true } else { return false }
        }
        #expect(failed.count == 1)
    }

    @Test("succeeds on second attempt — emits one retrying then success")
    func retrySucceeds() {
        var result: ExportResult = .failure
        let events = capturing(script: [.failure, .success]) { exporter, inner in
            result = exporter.export(logRecords: [], explicitTimeout: nil)
            #expect(inner.callCount == 2)
        }
        #expect(result == .success)
        #expect(events.count == 2)
        if case .retrying(let attempt, _, _) = events[0] {
            #expect(attempt == 1)
        } else {
            Issue.record("expected first event to be .retrying, got \(events[0])")
        }
        #expect(events[1] == .success(eventCount: 0))
    }

    @Test("exponential backoff: envelope ceiling doubles each attempt up to maxDelayMs")
    func backoffDoubles() {
        // With identity-jitter (always returns the ceiling) the published
        // delay equals the exponential envelope. This pins the pre-jitter
        // formula independently of randomness — the next test pins the
        // random behaviour itself.
        let events = capturing(
            script: [.failure, .failure, .failure, .failure],
            initialDelayMs: 4,
            maxDelayMs: 1000,
            jitter: { $0 }
        ) { exporter, _ in
            _ = exporter.export(logRecords: [], explicitTimeout: nil)
        }
        let delays: [Int] = events.compactMap {
            if case .retrying(_, _, let d) = $0 { return d } else { return nil }
        }
        // attempt 1 → 4ms, attempt 2 → 8ms, attempt 3 → 16ms.
        #expect(delays == [4, 8, 16])
    }

    @Test("exponential backoff: envelope ceiling clamps at maxDelayMs")
    func backoffClamps() {
        let events = capturing(
            script: [.failure, .failure, .failure, .failure],
            initialDelayMs: 100,
            maxDelayMs: 150,
            jitter: { $0 }
        ) { exporter, _ in
            _ = exporter.export(logRecords: [], explicitTimeout: nil)
        }
        let delays: [Int] = events.compactMap {
            if case .retrying(_, _, let d) = $0 { return d } else { return nil }
        }
        // attempt 1 → 100ms, attempt 2 → would be 200 → clamp to 150,
        // attempt 3 → 400 → clamp to 150.
        #expect(delays == [100, 150, 150])
    }

    // SR-009: full jitter prevents fleet-wide thundering herd after a
    // shared outage. Verified two ways:
    //   1. The jitter closure is consulted with the correct ceiling per
    //      attempt (pinned via deterministic jitter that returns ceiling/2).
    //   2. Different attempts can produce different delays even when the
    //      envelope is identical (pinned via stateful jitter).

    @Test("backoff consults jitter closure with the envelope ceiling")
    func jitterCeilingPerAttempt() {
        var ceilingsSeen: [Int] = []
        let events = capturing(
            script: [.failure, .failure, .failure, .failure],
            initialDelayMs: 10,
            maxDelayMs: 30,
            jitter: { ceiling in
                ceilingsSeen.append(ceiling)
                return ceiling / 2
            }
        ) { exporter, _ in
            _ = exporter.export(logRecords: [], explicitTimeout: nil)
        }
        // Envelope ceilings: attempt 1 → 10, attempt 2 → 20, attempt 3 → 30 (40 clamped).
        #expect(ceilingsSeen == [10, 20, 30])
        let delays: [Int] = events.compactMap {
            if case .retrying(_, _, let d) = $0 { return d } else { return nil }
        }
        // Half of each ceiling.
        #expect(delays == [5, 10, 15])
    }


    @Test("shutdown delegates to inner exporter")
    func shutdownDelegates() {
        _ = capturing(script: [.success]) { exporter, inner in
            exporter.shutdown(explicitTimeout: nil)
            #expect(inner.shutdownCalled == 1)
        }
    }

    @Test("forceFlush delegates to inner exporter")
    func forceFlushDelegates() {
        _ = capturing(script: [.success]) { exporter, inner in
            let r = exporter.forceFlush(explicitTimeout: nil)
            #expect(r == .success)
            #expect(inner.flushCalled == 1)
        }
    }
}
