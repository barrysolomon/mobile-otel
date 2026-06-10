// RN-013 unit tests for Dash0MobileBridgeDispatcher.
//
// Uses Swift Testing (aligns with the rest of otel-ios-mobile). These tests
// drive the dispatcher through the same payload fixtures the Android module
// tests use, to keep the cross-platform bridge contract aligned.

import Testing
@testable import Dash0MobileReactNative

@Suite("Dash0MobileBridgeDispatcher")
struct Dash0MobileBridgeDispatcherTests {

    // ── start ─────────────────────────────────────────────────────────────

    @Test
    func start_forwards_all_fields() throws {
        let sink = RecordingSink()
        let d = Dash0MobileBridgeDispatcher(sink: sink)
        try d.start(config: [
            "serviceName": "otel-rn-astronomy-shop",
            "endpoint": "https://ingress/v1/logs",
            "serviceVersion": "1.2.3",
            "authToken": "tok",
            "dataset": "otel-mobile",
        ])
        #expect(sink.starts.count == 1)
        let got = sink.starts[0]
        #expect(got.serviceName == "otel-rn-astronomy-shop")
        #expect(got.endpoint == "https://ingress/v1/logs")
        #expect(got.serviceVersion == "1.2.3")
        #expect(got.authToken == "tok")
        #expect(got.dataset == "otel-mobile")
    }

    @Test
    func start_forwards_nativeAutoCapture_tokens() throws {
        let sink = RecordingSink()
        let d = Dash0MobileBridgeDispatcher(sink: sink)
        try d.start(config: [
            "serviceName": "s",
            "endpoint": "https://e",
            "nativeAutoCapture": ["vitals", "deviceStats"],
        ])
        #expect(sink.starts.count == 1)
        #expect(sink.starts[0].nativeAutoCapture == ["vitals", "deviceStats"])
    }

    @Test
    func start_defaults_nativeAutoCapture_to_empty_when_absent() throws {
        let sink = RecordingSink()
        let d = Dash0MobileBridgeDispatcher(sink: sink)
        try d.start(config: [
            "serviceName": "s",
            "endpoint": "https://e",
        ])
        #expect(sink.starts[0].nativeAutoCapture == [])
    }

    // ── start: sampling (Loper finding #4) ─────────────────────────────────

    @Test
    func start_decodes_alwaysOff_sampling() throws {
        let sink = RecordingSink()
        let d = Dash0MobileBridgeDispatcher(sink: sink)
        try d.start(config: [
            "serviceName": "s",
            "endpoint": "https://e",
            "sampling": ["strategy": "always_off"],
        ])
        #expect(sink.starts[0].sampling == BridgeSamplingConfig(strategy: .alwaysOff))
    }

    @Test
    func start_decodes_dynamic_sampling_with_rates() throws {
        let sink = RecordingSink()
        let d = Dash0MobileBridgeDispatcher(sink: sink)
        try d.start(config: [
            "serviceName": "s",
            "endpoint": "https://e",
            "sampling": ["strategy": "dynamic", "normalRate": 0.1, "highPriorityRate": 1.0],
        ])
        #expect(sink.starts[0].sampling == BridgeSamplingConfig(
            strategy: .dynamic, normalRate: 0.1, highPriorityRate: 1.0
        ))
    }

    @Test
    func start_decodes_alwaysOn_sampling() throws {
        let sink = RecordingSink()
        let d = Dash0MobileBridgeDispatcher(sink: sink)
        try d.start(config: [
            "serviceName": "s",
            "endpoint": "https://e",
            "sampling": ["strategy": "always_on"],
        ])
        #expect(sink.starts[0].sampling == BridgeSamplingConfig(strategy: .alwaysOn))
    }

    @Test
    func start_sampling_nil_when_absent() throws {
        // The JS bridge always sends `sampling`, but the dispatcher must
        // decode a missing field as nil (the sink then applies the RN
        // .alwaysOn default).
        let sink = RecordingSink()
        let d = Dash0MobileBridgeDispatcher(sink: sink)
        try d.start(config: ["serviceName": "s", "endpoint": "https://e"])
        #expect(sink.starts[0].sampling == nil)
    }

    @Test
    func start_unknown_sampling_strategy_falls_back_to_alwaysOn() throws {
        let sink = RecordingSink()
        let d = Dash0MobileBridgeDispatcher(sink: sink)
        try d.start(config: [
            "serviceName": "s",
            "endpoint": "https://e",
            "sampling": ["strategy": "martian"],
        ])
        #expect(sink.starts[0].sampling?.strategy == .alwaysOn)
    }

    @Test
    func start_throws_when_serviceName_missing() {
        let sink = RecordingSink()
        let d = Dash0MobileBridgeDispatcher(sink: sink)
        #expect(throws: Dash0MobileBridgeError.self) {
            try d.start(config: ["endpoint": "e"])
        }
        #expect(sink.starts.isEmpty)
    }

    // ── emitBatch: log ────────────────────────────────────────────────────

    @Test
    func emitBatch_dispatches_log() {
        let sink = RecordingSink()
        let d = Dash0MobileBridgeDispatcher(sink: sink)
        d.emitBatch([[
            "kind": "log",
            "name": "cart.add_item",
            "severity": 9,
            "timeUnixNano": "1713600000000000000",
            "attributes": ["shop.item_id": "abc", "qty": 2, "urgent": true],
        ]])
        #expect(sink.logs.count == 1)
        let l = sink.logs[0]
        #expect(l.name == "cart.add_item")
        #expect(l.severity == 9)
        #expect(l.timeUnixNano == 1_713_600_000_000_000_000)
        #expect(l.attributes["shop.item_id"] as? String == "abc")
        #expect(l.attributes["urgent"] as? Bool == true)
    }

    // ── emitBatch: span pair ──────────────────────────────────────────────

    @Test
    func emitBatch_dispatches_spanStart_then_spanEnd() {
        let sink = RecordingSink()
        let d = Dash0MobileBridgeDispatcher(sink: sink)
        d.emitBatch([
            [
                "kind": "spanStart",
                "spanId": "abc123def456789a",
                "name": "checkout",
                "spanKind": "INTERNAL",
                "startTimeUnixNano": "0",
                "attributes": ["shop.cart_size": 3],
            ],
            [
                "kind": "spanEnd",
                "spanId": "abc123def456789a",
                "status": "OK",
                "endTimeUnixNano": "50",
                "attributes": ["http.response.status_code": 200],
            ],
        ])
        #expect(sink.spanStarts.count == 1)
        #expect(sink.spanEnds.count == 1)
        #expect(sink.spanStarts[0].spanId == sink.spanEnds[0].spanId)
        #expect(sink.spanEnds[0].status == "OK")
    }

    @Test
    func emitBatch_dispatches_ERROR_with_statusMessage() {
        let sink = RecordingSink()
        let d = Dash0MobileBridgeDispatcher(sink: sink)
        d.emitBatch([[
            "kind": "spanEnd",
            "spanId": "aaaaaaaaaaaaaaaa",
            "status": "ERROR",
            "statusMessage": "nope",
            "endTimeUnixNano": "0",
            "attributes": [:],
        ]])
        #expect(sink.spanEnds[0].status == "ERROR")
        #expect(sink.spanEnds[0].statusMessage == "nope")
    }

    // ── emitBatch: metric ─────────────────────────────────────────────────

    @Test
    func emitBatch_dispatches_counter_histogram_gauge() {
        let sink = RecordingSink()
        let d = Dash0MobileBridgeDispatcher(sink: sink)
        for t in ["counter", "histogram", "gauge"] {
            d.emitBatch([[
                "kind": "metric",
                "name": "shop.x",
                "instrumentType": t,
                "value": 42.5,
                "timeUnixNano": "0",
                "attributes": [:],
            ]])
        }
        #expect(sink.metrics.count == 3)
        #expect(sink.metrics.map { $0.instrumentType } == ["counter", "histogram", "gauge"])
        #expect(sink.metrics[0].value == 42.5)
    }

    // ── forward-compat ────────────────────────────────────────────────────

    @Test
    func emitBatch_silently_drops_unknown_kind() {
        let sink = RecordingSink()
        let d = Dash0MobileBridgeDispatcher(sink: sink)
        d.emitBatch([["kind": "martian", "name": "x"]])
        #expect(sink.logs.isEmpty)
        #expect(sink.metrics.isEmpty)
        #expect(sink.spanStarts.isEmpty)
        #expect(sink.spanEnds.isEmpty)
    }

    // ── flush / shutdown ──────────────────────────────────────────────────

    @Test
    func flushWindow_forwards_rounded_minutes() {
        let sink = RecordingSink()
        let d = Dash0MobileBridgeDispatcher(sink: sink)
        d.flushWindow(minutes: 5.0)
        #expect(sink.flushMinutes == [5])
    }

    @Test
    func shutdown_forwards() {
        let sink = RecordingSink()
        let d = Dash0MobileBridgeDispatcher(sink: sink)
        d.shutdown()
        #expect(sink.shutdowns == 1)
    }

    // MARK: - FATAL-severity forceFlush hook

    @Test
    func emitLog_fatalSeverity_triggersForceFlush() {
        let sink = RecordingSink()
        let d = Dash0MobileBridgeDispatcher(sink: sink)
        d.emitBatch([[
            "kind": "log",
            "name": "app.error",
            "severity": 21,
            "attributes": [:],
            "timeUnixNano": "1700000000000000000"
        ]])
        #expect(sink.logs.count == 1)
        #expect(sink.forceFlushes == 1)
    }

    @Test
    func emitLog_belowFatal_doesNotTriggerForceFlush() {
        let sink = RecordingSink()
        let d = Dash0MobileBridgeDispatcher(sink: sink)
        // 17 = ERROR in OTel semconv. Should NOT flush.
        d.emitBatch([[
            "kind": "log",
            "name": "app.error",
            "severity": 17,
            "attributes": [:],
            "timeUnixNano": "1700000000000000000"
        ]])
        #expect(sink.logs.count == 1)
        #expect(sink.forceFlushes == 0)
    }

    @Test
    func emitLog_fatalSeverity_flushOrderingIsPostEmitPrePeer() {
        let sink = RecordingSink()
        let d = Dash0MobileBridgeDispatcher(sink: sink)
        // FATAL log followed by another payload in the same batch. The
        // dispatcher must emit the FATAL, force-flush, THEN dispatch
        // the next payload — never the other way around. This preserves
        // the invariant that the FATAL gets out of the RAM buffer before
        // the next bridge call could clobber it (or the process dies).
        d.emitBatch([
            [
                "kind": "log",
                "name": "app.error",
                "severity": 21,
                "attributes": [:],
                "timeUnixNano": "1700000000000000000"
            ],
            [
                "kind": "log",
                "name": "app.error.context",
                "severity": 9,
                "attributes": [:],
                "timeUnixNano": "1700000000000000001"
            ]
        ])
        // Expected order: emit FATAL, forceFlush, emit context.
        #expect(sink.actionLog == [
            "emitLog(app.error,21)",
            "forceFlush",
            "emitLog(app.error.context,9)"
        ])
    }

    @Test
    func emitLog_multipleFatalsInBatch_eachTriggersFlush() {
        let sink = RecordingSink()
        let d = Dash0MobileBridgeDispatcher(sink: sink)
        // Two FATALs in a row — each gets its own flush. Wasteful but
        // safer than batching: a flush after the first FATAL might
        // succeed-then-die before reaching the second; flushing after
        // each ensures both have a chance to reach disk independently.
        d.emitBatch([
            ["kind": "log", "name": "a", "severity": 21, "attributes": [:], "timeUnixNano": "1"],
            ["kind": "log", "name": "b", "severity": 22, "attributes": [:], "timeUnixNano": "2"]
        ])
        #expect(sink.forceFlushes == 2)
    }

    @Test
    func emitLog_fatalSeverityRangeBoundary() {
        // OTel semconv: FATAL severity range is 21..24. Anything >= 21
        // is FATAL. The dispatcher's threshold (>= 21) covers all.
        let sink = RecordingSink()
        let d = Dash0MobileBridgeDispatcher(sink: sink)
        d.emitBatch([
            ["kind": "log", "name": "fatal2", "severity": 22, "attributes": [:], "timeUnixNano": "1"],
            ["kind": "log", "name": "fatal3", "severity": 23, "attributes": [:], "timeUnixNano": "2"],
            ["kind": "log", "name": "fatal4", "severity": 24, "attributes": [:], "timeUnixNano": "3"],
            ["kind": "log", "name": "warn",   "severity": 13, "attributes": [:], "timeUnixNano": "4"]
        ])
        #expect(sink.forceFlushes == 3)
    }
}

// ─── test double ──────────────────────────────────────────────────────────

private struct LogCall { let name: String; let severity: Int; let attributes: [String: Any]; let timeUnixNano: UInt64 }
private struct SpanStartCall { let spanId: String; let parentSpanId: String?; let name: String; let spanKind: String; let attributes: [String: Any]; let startTimeUnixNano: UInt64 }
private struct SpanEndCall { let spanId: String; let status: String; let statusMessage: String?; let attributes: [String: Any]; let endTimeUnixNano: UInt64 }
private struct MetricCall { let name: String; let instrumentType: String; let value: Double; let attributes: [String: Any]; let timeUnixNano: UInt64 }

private final class RecordingSink: BridgeCallSink {
    var starts: [BridgeStartConfig] = []
    var logs: [LogCall] = []
    var spanStarts: [SpanStartCall] = []
    var spanEnds: [SpanEndCall] = []
    var metrics: [MetricCall] = []
    var flushMinutes: [Int] = []
    var shutdowns = 0
    /// Records the order of `(action, payloadIndex)` so tests can assert
    /// that `forceFlush` runs AFTER the FATAL emit but BEFORE the next
    /// payload in the same batch. The dispatcher contract says: dispatch
    /// the log first (so it lands in the buffer), then force-flush so
    /// the buffer drains before the next payload (which might carry a
    /// span end the FATAL log's trace context references).
    var actionLog: [String] = []
    var forceFlushes = 0

    func start(_ config: BridgeStartConfig) {
        starts.append(config)
        actionLog.append("start")
    }
    func emitLog(name: String, severity: Int, attributes: [String: Any], timeUnixNano: UInt64) {
        logs.append(LogCall(name: name, severity: severity, attributes: attributes, timeUnixNano: timeUnixNano))
        actionLog.append("emitLog(\(name),\(severity))")
    }
    func startSpan(spanId: String, parentSpanId: String?, name: String, spanKind: String, attributes: [String: Any], startTimeUnixNano: UInt64) {
        spanStarts.append(SpanStartCall(spanId: spanId, parentSpanId: parentSpanId, name: name, spanKind: spanKind, attributes: attributes, startTimeUnixNano: startTimeUnixNano))
        actionLog.append("startSpan(\(name))")
    }
    func endSpan(spanId: String, status: String, statusMessage: String?, attributes: [String: Any], endTimeUnixNano: UInt64) {
        spanEnds.append(SpanEndCall(spanId: spanId, status: status, statusMessage: statusMessage, attributes: attributes, endTimeUnixNano: endTimeUnixNano))
        actionLog.append("endSpan(\(spanId))")
    }
    func recordMetric(name: String, instrumentType: String, value: Double, attributes: [String: Any], timeUnixNano: UInt64) {
        metrics.append(MetricCall(name: name, instrumentType: instrumentType, value: value, attributes: attributes, timeUnixNano: timeUnixNano))
        actionLog.append("recordMetric(\(name))")
    }
    func flushWindow(minutes: Int) {
        flushMinutes.append(minutes)
        actionLog.append("flushWindow(\(minutes))")
    }
    func shutdown() {
        shutdowns += 1
        actionLog.append("shutdown")
    }
    func forceFlush() {
        forceFlushes += 1
        actionLog.append("forceFlush")
    }
}
