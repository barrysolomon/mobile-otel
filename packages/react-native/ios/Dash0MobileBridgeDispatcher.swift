// Dispatches RN bridge payloads onto a BridgeCallSink.
//
// Kept as a free-standing type (not an RCTBridgeModule subclass) so it can
// be unit-tested without importing React/RCTBridgeModule.h. The
// RCTDash0MobileModule wrapper (Objective-C bridge + thin Swift shim) just
// calls through into this.

import Foundation

public enum Dash0MobileBridgeError: Error {
    case missingField(String)
    case invalidConfig(String)
}

public final class Dash0MobileBridgeDispatcher {
    private let sink: BridgeCallSink

    public init(sink: BridgeCallSink) {
        self.sink = sink
    }

    public func start(config: [String: Any]) throws {
        guard let serviceName = config["serviceName"] as? String else {
            throw Dash0MobileBridgeError.missingField("serviceName")
        }
        guard let endpoint = config["endpoint"] as? String else {
            throw Dash0MobileBridgeError.missingField("endpoint")
        }
        let extras: [String: String] = (config["extraResourceAttributes"] as? [String: Any])?
            .compactMapValues { $0 as? String } ?? [:]
        let nativeAutoCapture = (config["nativeAutoCapture"] as? [Any])?.compactMap { $0 as? String } ?? []
        let sampling = Self.parseSampling(config["sampling"])
        sink.start(BridgeStartConfig(
            serviceName: serviceName,
            serviceVersion: config["serviceVersion"] as? String,
            endpoint: endpoint,
            authToken: config["authToken"] as? String,
            dataset: config["dataset"] as? String,
            extraResourceAttributes: extras,
            nativeAutoCapture: nativeAutoCapture,
            sampling: sampling
        ))
    }

    /// Decode the JS `sampling` object into a `BridgeSamplingConfig`.
    /// Returns nil when absent so the sink applies the RN default
    /// (`.alwaysOn`). `normalRate` / `highPriorityRate` may arrive as
    /// Int, Double, or NSNumber across the RN bridge.
    private static func parseSampling(_ raw: Any?) -> BridgeSamplingConfig? {
        guard let map = raw as? [String: Any] else { return nil }
        return BridgeSamplingConfig(
            strategy: .fromToken(map["strategy"] as? String),
            normalRate: doubleOrNil(map["normalRate"]),
            highPriorityRate: doubleOrNil(map["highPriorityRate"])
        )
    }

    private static func doubleOrNil(_ v: Any?) -> Double? {
        if let n = v as? Double { return n }
        if let n = v as? Int { return Double(n) }
        if let n = v as? NSNumber { return n.doubleValue }
        return nil
    }

    public func emitBatch(_ payloads: [[String: Any]]) {
        for p in payloads {
            dispatch(p)
        }
    }

    public func flushWindow(minutes: Double) {
        sink.flushWindow(minutes: Int(minutes))
    }

    public func shutdown() {
        sink.shutdown()
    }

    private func dispatch(_ p: [String: Any]) {
        guard let kind = p["kind"] as? String else { return }
        let attrs = (p["attributes"] as? [String: Any]) ?? [:]
        switch kind {
        case "log":
            guard let name = p["name"] as? String else { return }
            let severity = intValue(p["severity"]) ?? 9
            sink.emitLog(
                name: name,
                severity: severity,
                attributes: attrs,
                timeUnixNano: stringAsUInt64(p["timeUnixNano"])
            )
            // FATAL-severity logs (OTel semconv 21..24) are the crash
            // path. JS-side bypasses the 50ms debounce via emitSync, but
            // the payload still sits in MobileLogRecordProcessor's RAM
            // buffer. The willTerminate observer (1a69c7e) doesn't fire
            // on RN's abort()/_exit() termination, so we eagerly flush
            // here BEFORE the next payload in the batch. The sink's
            // forceFlush() is synchronous: it drains RAM and persists
            // any export failures to disk so the next launch can recover.
            //
            // Lives in the dispatcher rather than each sink so every
            // BridgeCallSink consumer benefits — was previously wired
            // demo-app-locally in OTelMobileCallSink.emitLog, which left
            // non-RN consumers exposed to the same bridge-RAM-buffer
            // race.
            if severity >= 21 {
                sink.forceFlush()
            }
        case "spanStart":
            guard
                let spanId = p["spanId"] as? String,
                let name = p["name"] as? String
            else { return }
            sink.startSpan(
                spanId: spanId,
                parentSpanId: p["parentSpanId"] as? String,
                name: name,
                spanKind: (p["spanKind"] as? String) ?? "INTERNAL",
                attributes: attrs,
                startTimeUnixNano: stringAsUInt64(p["startTimeUnixNano"])
            )
        case "spanEnd":
            guard let spanId = p["spanId"] as? String else { return }
            sink.endSpan(
                spanId: spanId,
                status: (p["status"] as? String) ?? "UNSET",
                statusMessage: p["statusMessage"] as? String,
                attributes: attrs,
                endTimeUnixNano: stringAsUInt64(p["endTimeUnixNano"])
            )
        case "metric":
            guard let name = p["name"] as? String else { return }
            sink.recordMetric(
                name: name,
                instrumentType: (p["instrumentType"] as? String) ?? "counter",
                value: doubleValue(p["value"]) ?? 0,
                attributes: attrs,
                timeUnixNano: stringAsUInt64(p["timeUnixNano"])
            )
        default:
            // Unknown kinds silently dropped — forward-compatible with future
            // payload shapes the SDK's newer JS versions might send.
            return
        }
    }

    // NSNumber crossing the bridge may arrive as Int or Double. Be liberal.
    private func intValue(_ v: Any?) -> Int? {
        if let n = v as? Int { return n }
        if let n = v as? Double { return Int(n) }
        if let n = v as? NSNumber { return n.intValue }
        return nil
    }
    private func doubleValue(_ v: Any?) -> Double? {
        if let n = v as? Double { return n }
        if let n = v as? Int { return Double(n) }
        if let n = v as? NSNumber { return n.doubleValue }
        return nil
    }
    private func stringAsUInt64(_ v: Any?) -> UInt64 {
        if let s = v as? String, let n = UInt64(s) { return n }
        return 0
    }
}
