// Production BridgeCallSink for iOS. Forwards RN bridge calls into the
// existing OTelMobileSDK.OTelMobile facade.
//
// This file is compiled ONLY when OTelMobileSDK is linkable. When the RN
// package is built standalone (CI cold-compile without a host app) the
// RCTDash0MobileModule falls back to NoopSink.

#if canImport(OTelMobileSDK)
import Foundation
import OpenTelemetryApi
import OTelMobileSDK

final class OTelMobileCallSink: BridgeCallSink {
    private var otel: OTelMobile?
    private let spanLock = NSLock()
    /// Upper bound on concurrently-live (started-but-not-ended) spans. A
    /// misbehaving JS layer that starts spans without ending them must not
    /// balloon native memory. Matches the historical ~2048 ceiling.
    private static let maxLiveSpans = 2048

    /// O(1) lookup-by-id AND O(1) oldest-eviction. Replaces the previous
    /// unbounded `[String: Span]` (which leaked on never-ended spans) — and
    /// avoids the O(n) `firstIndex` scan a naive bounded list would need on
    /// eviction. All access is serialized under `spanLock`.
    private let liveSpans = BoundedLiveSpanStore<String, Span>(capacity: maxLiveSpans)

    func start(_ config: BridgeStartConfig) {
        guard otel == nil else { return }

        // Dash0 OTLP ingress wants Bearer auth + Dash0-Dataset as explicit
        // headers, not as separate MobileConfig fields. Build them here so
        // the rest of the SDK sees a uniform headers map.
        var extraHeaders: [String: String] = [:]
        if let dataset = config.dataset, !dataset.isEmpty {
            extraHeaders["Dash0-Dataset"] = dataset
        }

        // UAT launch-arg overrides: when present, the native side controls
        // export mode and injects cell_id so the UAT matrix can drive all
        // 12 cells without rebuilding the app.
        let exportMode = Self.exportModeFromArgs() ?? .continuous
        var mergedAttrs = config.extraResourceAttributes
        if let mode = Self.launchArg("-DASH0_EXPORT_MODE") {
            mergedAttrs["dash0.test.export_mode"] = mode
        }
        if let cellId = Self.launchArg("-DASH0_CELL_ID"), !cellId.isEmpty {
            mergedAttrs["dash0.test.cell_id"] = cellId
        }

        let mobileConfig = MobileConfig(
            serviceName: config.serviceName,
            serviceVersion: config.serviceVersion ?? "unknown",
            endpoint: config.endpoint,
            authToken: config.authToken,
            exportMode: exportMode,
            extraHeaders: extraHeaders,
            // RN sampling default is .alwaysOn (Loper finding #4): RN manual
            // spans are root spans with arbitrary names, so the native SDK's
            // dynamic(0.1) default would silently drop ~90% of a user's first
            // span — and a dropped iOS span is a non-recording PropagatedSpan
            // whose end() is a silent no-op. The JS bridge sends always_on
            // unless the caller opts into sampling; rate-limiting for RN
            // belongs in the collector.
            samplingConfig: Self.samplingConfig(from: config.sampling),
            extraResourceAttributes: mergedAttrs
        )
        do {
            otel = try OTelMobile.start(config: mobileConfig)
        } catch {
            // Start failure is logged but non-fatal — JS side stays operational
            // and future emitBatch calls become no-ops until a successful start.
            // Do NOT interpolate the full error: it can embed the endpoint URL
            // (incl. credentials) or auth token. Log a static message + the
            // error code only, and only when debug logging is enabled.
            Self.debugLog("OTelMobile.start failed (code \((error as NSError).code))")
        }
    }

    /// Gate diagnostic NSLogs behind a debug flag so production builds don't
    /// emit potentially sensitive init/auth detail to the device log.
    private static func debugLog(_ message: @autoclosure () -> String) {
        #if DEBUG
        NSLog("[@dash0/mobile-react-native] %@", message())
        #endif
    }

    func emitLog(
        name: String,
        severity: Int,
        attributes: [String: Any],
        timeUnixNano: UInt64
    ) {
        guard let logger = otel?.logger else { return }
        var builder = logger.logRecordBuilder()
            .setBody(AttributeValue.string(name))
            .setSeverity(Self.mapSeverity(severity))
            .setTimestamp(Self.dateFromUnixNano(timeUnixNano))
        let otelAttrs = Self.toAttributeValues(attributes)
        if !otelAttrs.isEmpty {
            builder = builder.setAttributes(otelAttrs)
        }
        builder.emit()
    }

    func startSpan(
        spanId: String,
        parentSpanId: String?,
        name: String,
        spanKind: String,
        attributes: [String: Any],
        startTimeUnixNano: UInt64
    ) {
        guard let tracer = otel?.tracer else { return }

        // Resolve the parent span (if still live) under the lock, then build
        // OUTSIDE the lock — never hold spanLock across OTel calls.
        var parentSpan: Span?
        if let parentSpanId = parentSpanId {
            spanLock.lock()
            parentSpan = liveSpans.value(forKey: parentSpanId)
            spanLock.unlock()
        }

        let builder = tracer.spanBuilder(spanName: name)
            .setStartTime(time: Self.dateFromUnixNano(startTimeUnixNano))
            .setSpanKind(kind: Self.mapSpanKind(spanKind))
        if let parentSpan = parentSpan {
            // TODO: switch to the OTel-Swift parent-context API
            // (`builder.setParent(parentSpan)`) once the SpanBuilder overload
            // is confirmed against the pinned OTelMobileSDK version. Until then
            // record the parent linkage as an attribute so Dash0 can still
            // associate the spans rather than orphaning them.
            builder.setAttribute(key: "parent.span.id", value: .string(parentSpanId ?? ""))
            _ = parentSpan
        }
        let span = builder.startSpan()
        for (k, v) in Self.toAttributeValues(attributes) {
            span.setAttribute(key: k, value: v)
        }

        // Insert + enforce the LRU cap. Capture any victim under the lock,
        // then end it OUTSIDE the lock (span.end / status are OTel calls).
        let evictedTime: UInt64 = startTimeUnixNano
        spanLock.lock()
        // Inserting at capacity evicts the oldest live span (O(1)). End it so it
        // is not leaked (the JS side never called endSpan for the orphaned
        // spanStart). Marked ERROR so the dropped span is visibly attributable
        // rather than silently OK.
        let evicted = liveSpans.put(spanId, span)
        spanLock.unlock()
        if let evicted = evicted {
            evicted.status = .error(description: "span evicted: liveSpans cap (\(Self.maxLiveSpans)) reached (orphaned spanStart)")
            evicted.end(time: Self.dateFromUnixNano(evictedTime))
        }
    }

    func endSpan(
        spanId: String,
        status: String,
        statusMessage: String?,
        attributes: [String: Any],
        endTimeUnixNano: UInt64
    ) {
        spanLock.lock()
        let span = liveSpans.removeValue(forKey: spanId)
        spanLock.unlock()
        guard let span = span else { return }

        for (k, v) in Self.toAttributeValues(attributes) {
            span.setAttribute(key: k, value: v)
        }
        switch status {
        case "OK":
            span.status = .ok
        case "ERROR":
            span.status = .error(description: statusMessage ?? "")
        default:
            break
        }
        span.end(time: Self.dateFromUnixNano(endTimeUnixNano))
    }

    func recordMetric(
        name: String,
        instrumentType: String,
        value: Double,
        attributes: [String: Any],
        timeUnixNano: UInt64
    ) {
        guard let meter = otel?.meter else { return }
        let otelAttrs = Self.toAttributeValues(attributes)
        switch instrumentType {
        case "histogram":
            meter.histogramBuilder(name: name).build()
                .record(value: value, attributes: otelAttrs)
        case "gauge":
            // OTel-Swift async gauges require an observer callback; for the
            // bridge's fire-and-forget contract we record a single value via
            // a histogram of size 1 so the last-value aggregation in the
            // backend surfaces the reading. Purpose-built sync gauges land
            // if/when upstream exposes them.
            meter.histogramBuilder(name: name).build()
                .record(value: value, attributes: otelAttrs)
        default:
            // counter — integer values are the common case. Fractional
            // counters aren't expressible through OTel-Swift's long counter,
            // so truncate. `Int(value)` traps on NaN/Inf/out-of-range doubles,
            // so clamp into Int range first and drop non-finite values.
            guard value.isFinite else { return }
            let clamped = value.rounded(.towardZero)
            let intValue: Int
            if clamped >= Double(Int.max) {
                intValue = Int.max
            } else if clamped <= Double(Int.min) {
                intValue = Int.min
            } else {
                intValue = Int(clamped)
            }
            meter.counterBuilder(name: name).build()
                .add(value: intValue, attributes: otelAttrs)
        }
    }

    func flushWindow(minutes: Int) {
        guard let otel = otel else { return }
        Task.detached { [otel] in
            _ = await otel.flushWindow(minutes: UInt64(max(0, minutes)))
        }
    }

    func shutdown() {
        otel = nil
        spanLock.lock()
        _ = liveSpans.removeAll()
        spanLock.unlock()
    }

    /// Drain all RAM-buffered telemetry through the OTLP exporter,
    /// persisting on export failure. Called by `Dash0MobileBridgeDispatcher`
    /// after dispatching a FATAL-severity log emit, before continuing
    /// to the next payload in the same batch.
    ///
    /// `OTelMobile.forceFlush` is synchronous and routes both logs and
    /// pending spans through the configured persistence path
    /// (`MobileLogRecordProcessor` for logs, `BatchSpanProcessor`'s
    /// shutdown drain for spans). On RN's abort()/_exit() termination
    /// path this is the only thing that gives the FATAL log a chance
    /// to land in Dash0 — the willTerminate observer doesn't fire on
    /// abort().
    func forceFlush() {
        guard let otel = otel else { return }
        _ = otel.forceFlush()
    }

    // MARK: - Helpers

    /// Translate the bridge sampling config into the SDK's `SamplingConfig`.
    /// Nil (caller sent nothing) maps to `.alwaysOn` to preserve the RN
    /// default — see the `samplingConfig:` comment in `start`.
    private static func samplingConfig(from sampling: BridgeSamplingConfig?) -> SamplingConfig {
        switch sampling?.strategy {
        case .none, .alwaysOn:
            return .alwaysOn()
        case .alwaysOff:
            return .alwaysOff()
        case .dynamic:
            return .dynamic(
                normalRate: sampling?.normalRate ?? 0.05,
                highPriorityRate: sampling?.highPriorityRate ?? 1.0
            )
        }
    }

    private static func toAttributeValues(_ raw: [String: Any]) -> [String: AttributeValue] {
        var out: [String: AttributeValue] = [:]
        for (k, v) in raw {
            if let s = v as? String {
                out[k] = .string(s)
            } else if let b = v as? Bool {
                out[k] = .bool(b)
            } else if let i = v as? Int {
                out[k] = .int(i)
            } else if let n = v as? NSNumber {
                // React Native bridges numerics as NSNumber. Integer-valued
                // NSNumbers should land as ints so attribute keys like `qty`
                // don't surface as `2.0` in backends — mirrors the Android
                // OTelMobileCallSink behavior.
                if CFNumberIsFloatType(n) {
                    out[k] = .double(n.doubleValue)
                } else {
                    out[k] = .int(n.intValue)
                }
            } else if let d = v as? Double {
                out[k] = .double(d)
            } else {
                out[k] = .string(String(describing: v))
            }
        }
        return out
    }

    private static func dateFromUnixNano(_ nano: UInt64) -> Date {
        Date(timeIntervalSince1970: TimeInterval(nano) / 1_000_000_000.0)
    }

    private static func mapSeverity(_ raw: Int) -> Severity {
        // OTel severity numbers: 1=TRACE, 5=DEBUG, 9=INFO, 13=WARN, 17=ERROR,
        // 21=FATAL. JS sends the numeric value directly — fall back to INFO
        // when the value is outside the known range so log records never
        // get dropped silently.
        switch raw {
        case 1...4:   return .trace
        case 5...8:   return .debug
        case 9...12:  return .info
        case 13...16: return .warn
        case 17...20: return .error
        case 21...:   return .fatal
        default:      return .info
        }
    }

    private static func mapSpanKind(_ raw: String) -> SpanKind {
        switch raw {
        case "CLIENT":   return .client
        case "SERVER":   return .server
        case "PRODUCER": return .producer
        case "CONSUMER": return .consumer
        default:         return .internal
        }
    }

    // MARK: - UAT launch-arg helpers

    private static func launchArg(_ flag: String) -> String? {
        let args = CommandLine.arguments
        guard let idx = args.firstIndex(of: flag), idx + 1 < args.count else { return nil }
        return args[idx + 1]
    }

    private static func exportModeFromArgs() -> ExportMode? {
        guard let raw = launchArg("-DASH0_EXPORT_MODE") else { return nil }
        switch raw.lowercased() {
        case "cont", "continuous": return .continuous
        case "cond", "conditional": return .conditional
        case "hyb", "hybrid": return .hybrid
        default: return nil
        }
    }
}
#endif
