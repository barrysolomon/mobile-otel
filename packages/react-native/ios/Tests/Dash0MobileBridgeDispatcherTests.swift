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
}

// ─── test double ──────────────────────────────────────────────────────────

private struct LogCall { let name: String; let severity: Int; let attributes: [String: Any]; let timeUnixNano: UInt64 }
private struct SpanStartCall { let spanId: String; let name: String; let spanKind: String; let attributes: [String: Any]; let startTimeUnixNano: UInt64 }
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

    func start(_ config: BridgeStartConfig) { starts.append(config) }
    func emitLog(name: String, severity: Int, attributes: [String: Any], timeUnixNano: UInt64) {
        logs.append(LogCall(name: name, severity: severity, attributes: attributes, timeUnixNano: timeUnixNano))
    }
    func startSpan(spanId: String, name: String, spanKind: String, attributes: [String: Any], startTimeUnixNano: UInt64) {
        spanStarts.append(SpanStartCall(spanId: spanId, name: name, spanKind: spanKind, attributes: attributes, startTimeUnixNano: startTimeUnixNano))
    }
    func endSpan(spanId: String, status: String, statusMessage: String?, attributes: [String: Any], endTimeUnixNano: UInt64) {
        spanEnds.append(SpanEndCall(spanId: spanId, status: status, statusMessage: statusMessage, attributes: attributes, endTimeUnixNano: endTimeUnixNano))
    }
    func recordMetric(name: String, instrumentType: String, value: Double, attributes: [String: Any], timeUnixNano: UInt64) {
        metrics.append(MetricCall(name: name, instrumentType: instrumentType, value: value, attributes: attributes, timeUnixNano: timeUnixNano))
    }
    func flushWindow(minutes: Int) { flushMinutes.append(minutes) }
    func shutdown() { shutdowns += 1 }
}
