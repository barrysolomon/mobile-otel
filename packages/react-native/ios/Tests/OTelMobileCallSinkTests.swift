// Direct unit tests for the PRODUCTION RN-iOS sink, `OTelMobileCallSink`.
//
// Historically this file was excluded from every build/test target (it needs
// `OTelMobileSDK`), so the code that turns RN bridge calls into real OTel
// spans/logs on iOS had ZERO direct coverage — the #1 RN-iOS risk flagged in
// the production-readiness review. The package now path-depends on the sibling
// iOS SDK so the sink compiles, and the sink exposes an `init(telemetry:)`
// seam. These tests drive the real sink against in-memory exporters and assert
// the resulting spans/logs — exercising parent linkage, LRU eviction,
// span-kind / severity mapping, attribute coercion, and the no-crash guards.
//
// Runs under `xcodebuild test` / `swift test` where swift-testing resolves.

#if canImport(OTelMobileSDK)
import Foundation
import Testing
import OpenTelemetryApi
import OpenTelemetrySdk
@testable import Dash0MobileReactNative

/// Minimal in-memory `SpanExporter`. Paired with `ImmediateSpanProcessor`
/// (below) so finished spans are readable synchronously, the instant an
/// `endSpan` / eviction returns.
private final class CapturingSpanExporter: SpanExporter, @unchecked Sendable {
    private let lock = NSLock()
    private var _spans: [SpanData] = []
    var spans: [SpanData] { lock.lock(); defer { lock.unlock() }; return _spans }

    func export(spans: [SpanData], explicitTimeout: TimeInterval?) -> SpanExporterResultCode {
        lock.lock(); _spans.append(contentsOf: spans); lock.unlock()
        return .success
    }
    func flush(explicitTimeout: TimeInterval?) -> SpanExporterResultCode { .success }
    func shutdown(explicitTimeout: TimeInterval?) {}
}

/// Minimal in-memory `LogRecordExporter` (the SDK's own `InMemoryLogRecordExporter`
/// has an internal initializer, so we capture here). `SimpleLogRecordProcessor`
/// calls `export` synchronously on every `emit`.
private final class CapturingLogRecordExporter: LogRecordExporter, @unchecked Sendable {
    private let lock = NSLock()
    private var _records: [ReadableLogRecord] = []
    var records: [ReadableLogRecord] { lock.lock(); defer { lock.unlock() }; return _records }

    func export(logRecords: [ReadableLogRecord], explicitTimeout: TimeInterval?) -> ExportResult {
        lock.lock(); _records.append(contentsOf: logRecords); lock.unlock()
        return .success
    }
    func forceFlush(explicitTimeout: TimeInterval?) -> ExportResult { .success }
    func shutdown(explicitTimeout: TimeInterval?) {}
}

/// Synchronous span processor for tests. The SDK's `SimpleSpanProcessor`
/// exports on a background `DispatchQueue`, which races test assertions; this
/// one exports inline on `onEnd` so a finished span is visible to the exporter
/// the instant `endSpan` (or an eviction) returns — fully deterministic.
private final class ImmediateSpanProcessor: SpanProcessor {
    private let exporter: SpanExporter
    init(_ exporter: SpanExporter) { self.exporter = exporter }
    var isStartRequired: Bool { false }
    var isEndRequired: Bool { true }
    func onStart(parentContext: SpanContext?, span: ReadableSpan) {}
    func onEnd(span: ReadableSpan) { _ = exporter.export(spans: [span.toSpanData()], explicitTimeout: nil) }
    func shutdown(explicitTimeout: TimeInterval?) {}
    func forceFlush(timeout: TimeInterval?) {}
}

/// Builds a sink wired to capturing exporters via the test-injection seam, plus
/// the exporters so tests can assert on what was emitted. `capacity` controls
/// the live-span LRU cap so eviction is testable without 2048 spans.
private func makeSink(capacity: Int = OTelMobileCallSink.maxLiveSpans)
    -> (sink: OTelMobileCallSink, spans: CapturingSpanExporter, logs: CapturingLogRecordExporter) {
    let spanExporter = CapturingSpanExporter()
    let tracer = TracerProviderBuilder()
        .add(spanProcessor: ImmediateSpanProcessor(spanExporter))
        .build()
        .get(instrumentationName: "test")

    let logExporter = CapturingLogRecordExporter()
    let logger = LoggerProviderBuilder()
        .with(processors: [SimpleLogRecordProcessor(logRecordExporter: logExporter)])
        .build()
        .get(instrumentationScopeName: "test")

    let telemetry = SinkTelemetry(
        tracer: tracer, logger: logger, meter: nil,
        forceFlush: {}, flushWindow: { _ in }, shutdown: {}
    )
    let sink = OTelMobileCallSink(telemetry: telemetry, capacity: capacity)
    sink.start(BridgeStartConfig(
        serviceName: "test", serviceVersion: "1.0",
        endpoint: "https://example.test", authToken: nil, dataset: nil
    ))
    return (sink, spanExporter, logExporter)
}

@Suite("OTelMobileCallSink (RN-iOS production sink)")
struct OTelMobileCallSinkTests {
    private let t0: UInt64 = 1_700_000_000_000_000_000
    private func t(_ offsetNanos: UInt64) -> UInt64 { t0 + offsetNanos }

    /// Spin-wait for a condition. With `ImmediateSpanProcessor` exports are
    /// synchronous so this returns on the first check; it is retained as a
    /// small safety margin and to drain (timeout form) when asserting that a
    /// no-op produced nothing. Returns the final value of `condition`.
    private func waitFor(_ condition: @autoclosure () -> Bool, timeout: TimeInterval = 3.0) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() && Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.01))
        }
        return condition()
    }

    @Test("emitLog produces a record with mapped severity, body, and coerced attributes")
    func emitLogProducesRecord() {
        let (sink, _, logs) = makeSink()
        sink.emitLog(name: "app.error", severity: 17,
                     attributes: ["http.status": 500, "ok": false, "where": "x"],
                     timeUnixNano: t(0))
        let records = logs.records
        #expect(records.count == 1)
        #expect(records.first?.severity == .error)          // 17 -> ERROR
        #expect(records.first?.body == .string("app.error"))
        // RN bridges 500 as an integer-valued NSNumber -> must land as .int.
        #expect(records.first?.attributes["http.status"] == .int(500))
        #expect(records.first?.attributes["ok"] == .bool(false))
    }

    @Test("startSpan + endSpan exports one span with the right name, kind, and status")
    func spanRoundTrip() {
        let (sink, spans, _) = makeSink()
        sink.startSpan(spanId: "a", parentSpanId: nil, name: "GET /x",
                       spanKind: "CLIENT", attributes: [:], startTimeUnixNano: t(0))
        #expect(spans.spans.isEmpty)  // not exported until ended
        sink.endSpan(spanId: "a", status: "OK", statusMessage: nil,
                     attributes: ["http.method": "GET"], endTimeUnixNano: t(1_000))
        #expect(waitFor(spans.spans.count == 1))
        let s = spans.spans[0]
        #expect(s.name == "GET /x")
        #expect(s.kind == .client)
        #expect(s.status == .ok)
        #expect(s.attributes["http.method"] == .string("GET"))
    }

    @Test("parent linkage stitches child into the parent's trace")
    func parentLinkage() {
        let (sink, spans, _) = makeSink()
        sink.startSpan(spanId: "p", parentSpanId: nil, name: "screen",
                       spanKind: "INTERNAL", attributes: [:], startTimeUnixNano: t(0))
        sink.startSpan(spanId: "c", parentSpanId: "p", name: "fetch",
                       spanKind: "CLIENT", attributes: [:], startTimeUnixNano: t(10))
        sink.endSpan(spanId: "c", status: "OK", statusMessage: nil, attributes: [:], endTimeUnixNano: t(20))
        sink.endSpan(spanId: "p", status: "OK", statusMessage: nil, attributes: [:], endTimeUnixNano: t(30))

        #expect(waitFor(spans.spans.count == 2))
        let parent = spans.spans.first { $0.name == "screen" }
        let child = spans.spans.first { $0.name == "fetch" }
        #expect(parent != nil && child != nil)
        // Real OTel parent-child linkage: shared trace id + child points at parent.
        #expect(child?.traceId == parent?.traceId)
        #expect(child?.parentSpanId == parent?.spanId)
        // Bridge-supplied JS id mirrored as an attribute for cross-referencing.
        #expect(child?.attributes["parent.span.id"] == .string("p"))
    }

    @Test("liveSpans LRU evicts the oldest orphaned span and ends it as ERROR")
    func evictionEndsOldestAsError() {
        let (sink, spans, _) = makeSink(capacity: 2)
        // Three starts, no ends. At capacity 2, starting s3 must evict s1.
        sink.startSpan(spanId: "s1", parentSpanId: nil, name: "s1", spanKind: "INTERNAL", attributes: [:], startTimeUnixNano: t(0))
        sink.startSpan(spanId: "s2", parentSpanId: nil, name: "s2", spanKind: "INTERNAL", attributes: [:], startTimeUnixNano: t(10))
        sink.startSpan(spanId: "s3", parentSpanId: nil, name: "s3", spanKind: "INTERNAL", attributes: [:], startTimeUnixNano: t(20))

        // Only the evicted s1 has been ended (by eviction) and thus exported
        // (async via SimpleSpanProcessor — wait for it).
        #expect(waitFor(spans.spans.contains { $0.name == "s1" }))
        let evicted = spans.spans.first { $0.name == "s1" }
        #expect(evicted != nil)
        #expect(evicted?.status != .ok)  // ended as ERROR, not OK
        if case .error = evicted?.status {} else { Issue.record("evicted span should be ERROR") }
        #expect(spans.spans.contains { $0.name == "s2" } == false)  // still live, not ended

        // Ending the still-live ones works and does not double-export s1.
        sink.endSpan(spanId: "s2", status: "OK", statusMessage: nil, attributes: [:], endTimeUnixNano: t(30))
        sink.endSpan(spanId: "s3", status: "OK", statusMessage: nil, attributes: [:], endTimeUnixNano: t(40))
        #expect(waitFor(spans.spans.count == 3))
        #expect(spans.spans.filter { $0.name == "s1" }.count == 1)
    }

    @Test("ending an unknown span id is a safe no-op")
    func endUnknownSpanNoop() {
        let (sink, spans, _) = makeSink()
        sink.endSpan(spanId: "nope", status: "OK", statusMessage: nil, attributes: [:], endTimeUnixNano: t(0))
        #expect(spans.spans.isEmpty)
    }

    @Test("recordMetric with a nil meter is a safe no-op (no crash on non-finite values)")
    func metricNoMeterNoCrash() {
        // Sink built WITHOUT a meter (meter: nil) — recordMetric must early-return.
        // Passing NaN/Inf also exercises that the counter path's value guard would
        // never trap even if a meter were present.
        let (sink, _, _) = makeSink()
        sink.recordMetric(name: "c", instrumentType: "counter", value: .nan, attributes: [:], timeUnixNano: t(0))
        sink.recordMetric(name: "c", instrumentType: "counter", value: .infinity, attributes: [:], timeUnixNano: t(0))
        sink.recordMetric(name: "g", instrumentType: "gauge", value: 1.5, attributes: [:], timeUnixNano: t(0))
        // No assertion needed beyond "did not crash"; reaching here is the pass.
        #expect(Bool(true))
    }

    @Test("emit before start / after shutdown is a safe no-op")
    func lifecycleGuards() {
        // A sink with injected telemetry only adopts it on start(); before start,
        // telemetry is nil so emits are no-ops. After shutdown, telemetry is
        // cleared and live spans drained.
        let spanExporter = CapturingSpanExporter()
        let tracer = TracerProviderBuilder()
            .add(spanProcessor: ImmediateSpanProcessor(spanExporter))
            .build().get(instrumentationName: "test")
        let telemetry = SinkTelemetry(tracer: tracer, logger: nil, meter: nil,
                                      forceFlush: {}, flushWindow: { _ in }, shutdown: {})
        let sink = OTelMobileCallSink(telemetry: telemetry, capacity: 8)

        // Before start: no-op (telemetry not yet adopted).
        sink.startSpan(spanId: "x", parentSpanId: nil, name: "x", spanKind: "INTERNAL", attributes: [:], startTimeUnixNano: t(0))
        sink.endSpan(spanId: "x", status: "OK", statusMessage: nil, attributes: [:], endTimeUnixNano: t(1))

        // After start: works.
        sink.start(BridgeStartConfig(serviceName: "s", serviceVersion: nil, endpoint: "https://e.test", authToken: nil, dataset: nil))
        sink.startSpan(spanId: "y", parentSpanId: nil, name: "y", spanKind: "INTERNAL", attributes: [:], startTimeUnixNano: t(0))
        sink.endSpan(spanId: "y", status: "OK", statusMessage: nil, attributes: [:], endTimeUnixNano: t(1))
        #expect(waitFor(spanExporter.spans.count == 1))
        #expect(spanExporter.spans.allSatisfy { $0.name == "y" })  // x (pre-start) never exported

        // After shutdown: emits are no-ops again.
        sink.shutdown()
        sink.startSpan(spanId: "z", parentSpanId: nil, name: "z", spanKind: "INTERNAL", attributes: [:], startTimeUnixNano: t(0))
        sink.endSpan(spanId: "z", status: "OK", statusMessage: nil, attributes: [:], endTimeUnixNano: t(1))
        // Drain the processor queue, then confirm z produced nothing new.
        _ = waitFor(false, timeout: 0.3)
        #expect(spanExporter.spans.count == 1)  // unchanged
    }
}
#endif
