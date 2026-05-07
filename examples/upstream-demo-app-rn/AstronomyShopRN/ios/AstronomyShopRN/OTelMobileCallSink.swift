// Production BridgeCallSink for iOS. Forwards RN bridge calls into the
// existing OTelMobileSDK.OTelMobile facade.
//
// Lives in the app target (not the CocoaPods pod) because it needs the
// app's SwiftPM-delivered OTelMobileSDK. AppDelegate registers this with
// the bridge pod via `Dash0MobileModule.installSink { OTelMobileCallSink() }`.

import Foundation
import OpenTelemetryApi
import OTelMobileSDK
import Dash0Mobile

public final class OTelMobileCallSink: BridgeCallSink {
    private var otel: OTelMobile?
    private let spanLock = NSLock()
    private var liveSpans: [String: Span] = [:]

    public init() {}

    public func start(_ config: BridgeStartConfig) {
        // Dash0 OTLP ingress wants Bearer auth + Dash0-Dataset as explicit
        // headers, not as separate MobileConfig fields. Build them here so
        // the rest of the SDK sees a uniform headers map.
        var extraHeaders: [String: String] = [:]
        if let dataset = config.dataset, !dataset.isEmpty {
            extraHeaders["Dash0-Dataset"] = dataset
        }

        // Open dual disk buffers (logs + spans) synchronously via a
        // DispatchSemaphore bridge. The sink's start() is synchronous
        // (the RN bridge contract expects it), but DiskLogBuffer and
        // DiskSpanBuffer are actors with async init — same pattern the
        // iOS-native AstronomyShop demo uses in ShopBootstrap.swift.
        // 5s timeout lets a bricked sqlite path fall back to "no disk
        // buffer" rather than hanging RN startup forever.
        let diskBaseDir = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("io.dash0.mobile")
        try? FileManager.default.createDirectory(
            at: diskBaseDir, withIntermediateDirectories: true)
        let logDbPath = diskBaseDir.appendingPathComponent("buffer.db")
        let spanDbPath = diskBaseDir.appendingPathComponent("span-buffer.db")

        var logBuffer: DiskLogBuffer?
        var spanBuffer: DiskSpanBuffer?
        let openSemaphore = DispatchSemaphore(value: 0)
        Task {
            logBuffer = try? await DiskLogBuffer(dbPath: logDbPath)
            spanBuffer = try? await DiskSpanBuffer(dbPath: spanDbPath)
            openSemaphore.signal()
        }
        _ = openSemaphore.wait(timeout: .now() + 5)

        // UAT launch-arg overrides: simctl launch passes -DASH0_EXPORT_MODE
        // and -DASH0_CELL_ID as process arguments. These override the JS-side
        // config so the matrix runner can control export mode and tag records
        // with a per-cell identifier without touching the JS bundle.
        let exportMode = Self.exportModeFromLaunchArgs() ?? .continuous
        var mergedAttrs = config.extraResourceAttributes
        if let cellId = Self.launchArg("-DASH0_CELL_ID"), !cellId.isEmpty {
            mergedAttrs["dash0.test.cell_id"] = cellId
        }
        if let modeStr = Self.launchArg("-DASH0_EXPORT_MODE"), !modeStr.isEmpty {
            mergedAttrs["dash0.test.export_mode"] = modeStr
        }

        let mobileConfig = MobileConfig(
            serviceName: config.serviceName,
            serviceVersion: config.serviceVersion ?? "unknown",
            endpoint: config.endpoint,
            authToken: config.authToken,
            exportMode: exportMode,
            autoCaptureOptions: Self.parseAutoCaptureOptions(config.nativeAutoCapture),
            extraHeaders: extraHeaders,
            samplingConfig: .alwaysOn(),
            extraResourceAttributes: mergedAttrs
        )
        do {
            otel = try OTelMobile.start(
                config: mobileConfig,
                diskBuffer: logBuffer,
                spanDiskBuffer: spanBuffer)
        } catch {
            // Start failure is logged but non-fatal — JS side stays operational
            // and future emitBatch calls become no-ops until a successful start.
            NSLog("[@dash0/mobile-react-native] OTelMobile.start failed: \(error)")
        }
    }

    public func emitLog(
        name: String,
        severity: Int,
        attributes: [String: Any],
        timeUnixNano: UInt64
    ) {
        guard let instance = otel else { return }
        var builder = instance.logger.logRecordBuilder()
            .setBody(AttributeValue.string(name))
            .setSeverity(Self.mapSeverity(severity))
            .setTimestamp(Self.dateFromUnixNano(timeUnixNano))
        let otelAttrs = Self.toAttributeValues(attributes)
        if !otelAttrs.isEmpty {
            builder = builder.setAttributes(otelAttrs)
        }
        builder.emit()

        // NOTE: FATAL-severity (>=21) eager forceFlush is now centralized
        // in `Dash0MobileBridgeDispatcher` — it calls `sink.forceFlush()`
        // on the protocol after dispatching a FATAL emit, so every
        // BridgeCallSink consumer (including this demo sink) gets the
        // crash-path drain for free. Used to live here as a per-sink
        // hand-rolled `if severity >= 21 { _ = instance.forceFlush() }`
        // block; moved out so non-RN consumers writing their own sinks
        // don't have to re-derive the bridge-RAM-buffer race fix.
    }

    public func startSpan(
        spanId: String,
        parentSpanId: String?,
        name: String,
        spanKind: String,
        attributes: [String: Any],
        startTimeUnixNano: UInt64
    ) {
        guard let tracer = otel?.tracer else { return }
        var builder = tracer.spanBuilder(spanName: name)
            .setStartTime(time: Self.dateFromUnixNano(startTimeUnixNano))
            .setSpanKind(spanKind: Self.mapSpanKind(spanKind))
        // When the JS caller identifies a parent, wire it to the OTel-Swift
        // spanBuilder so the native tracer shares trace id + sets parent
        // span id on the child — Dash0's UI can then render a real
        // waterfall. Without this, every span becomes its own trace root.
        if let parentSpanId {
            spanLock.lock()
            let parent = liveSpans[parentSpanId]
            spanLock.unlock()
            if let parent {
                builder = builder.setParent(parent)
            }
        }
        let span = builder.startSpan()
        for (k, v) in Self.toAttributeValues(attributes) {
            span.setAttribute(key: k, value: v)
        }
        spanLock.lock()
        liveSpans[spanId] = span
        spanLock.unlock()
    }

    public func endSpan(
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

    public func recordMetric(
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
            // so truncate and log once if the JS side ever sends a non-int.
            meter.counterBuilder(name: name).build()
                .add(value: Int(value), attributes: otelAttrs)
        }
    }

    public func flushWindow(minutes: Int) {
        guard let otel = otel else { return }
        Task.detached { [otel] in
            _ = await otel.flushWindow(minutes: UInt64(max(0, minutes)))
        }
    }

    public func shutdown() {
        otel = nil
        spanLock.lock()
        liveSpans.removeAll()
        spanLock.unlock()
    }

    /// Synchronous drain of every buffered log + span through the OTLP
    /// exporter, persisting on failure. Invoked by
    /// `Dash0MobileBridgeDispatcher` after a FATAL-severity (>=21) log
    /// emit so the crash payload lands in Dash0 even when RN's fatal
    /// reporter terminates via abort()/_exit() and skips
    /// UIApplication.willTerminateNotification.
    public func forceFlush() {
        guard let instance = otel else { return }
        _ = instance.forceFlush()
    }

    // MARK: - Helpers

    /// Translate JS-side capability tokens into an `AutoCaptureOptions`.
    ///
    /// Lifecycle is on by default — it observes UIApplication / UIScene
    /// notifications via NotificationCenter, which doesn't touch the JS
    /// event loop, doesn't swizzle anything, and doesn't chain fatal
    /// handlers. Safe in RN.
    ///
    /// Other capabilities (network URLProtocol swizzle, NSException/signal
    /// handlers, screen swizzle) DO collide with the RN new-arch event loop
    /// and remain off-by-default. Apps that want them must opt in per
    /// capability from JS via `autoCapture: { network: true }` etc.
    ///
    /// Unknown tokens are silently dropped for forward compatibility (apps
    /// targeting newer SDKs than the host build can use).
    private static func parseAutoCaptureOptions(_ tokens: [String]) -> AutoCaptureOptions {
        var opts: AutoCaptureOptions = [.lifecycle]
        for token in tokens {
            switch token {
            case "tap":         opts.insert(.tap)
            case "scroll":      opts.insert(.scroll)
            case "textInput":   opts.insert(.textInput)
            case "lifecycle":   opts.insert(.lifecycle)
            case "screen":      opts.insert(.screen)
            case "network":     opts.insert(.network)
            case "errors":      opts.insert(.errors)
            case "freeze":      opts.insert(.freeze)
            case "vitals":      opts.insert(.vitals)
            case "screenshot":  opts.insert(.screenshot)
            case "wireframe":   opts.insert(.wireframe)
            case "deviceStats": opts.insert(.deviceStats)
            default: continue
            }
        }
        return opts
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

    private static func launchArg(_ flag: String) -> String? {
        let args = CommandLine.arguments
        guard let idx = args.firstIndex(of: flag), idx + 1 < args.count else { return nil }
        return args[idx + 1]
    }

    private static func exportModeFromLaunchArgs() -> ExportMode? {
        guard let raw = launchArg("-DASH0_EXPORT_MODE") else { return nil }
        switch raw.lowercased() {
        case "cont", "continuous": return .continuous
        case "cond", "conditional": return .conditional
        case "hyb", "hybrid": return .hybrid
        default: return nil
        }
    }
}
