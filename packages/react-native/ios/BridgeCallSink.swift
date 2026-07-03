// Seam between the RN bridge and the native iOS OTel Mobile SDK.
//
// Production: OTelMobileCallSink forwards into OTelMobileSDK.OTelMobile.
// Tests: a fake sink records calls so we can assert the module's dispatch
// behavior without standing up the full SDK.

import Foundation

public protocol BridgeCallSink: AnyObject {
    func start(_ config: BridgeStartConfig)
    func emitLog(name: String, severity: Int, attributes: [String: Any], timeUnixNano: UInt64)
    func startSpan(spanId: String, parentSpanId: String?, name: String, spanKind: String, attributes: [String: Any], startTimeUnixNano: UInt64)
    func endSpan(spanId: String, status: String, statusMessage: String?, attributes: [String: Any], endTimeUnixNano: UInt64)
    func recordMetric(name: String, instrumentType: String, value: Double, attributes: [String: Any], timeUnixNano: UInt64)
    func flushWindow(minutes: Int)
    func shutdown()

    /// Synchronously flush every buffered telemetry record through the
    /// underlying SDK's exporter, persisting any in-flight records to
    /// disk on export failure. Called by `Dash0MobileBridgeDispatcher`
    /// immediately after dispatching a FATAL-severity (>=21) log emit,
    /// before continuing to the next payload in the batch.
    ///
    /// **Why this is in the protocol, not the dispatcher:** the
    /// dispatcher could call something like `sink.flushWindow(...)`,
    /// but that's selective + async. FATAL needs synchronous flush of
    /// everything because the process is about to die on the calling
    /// thread (RN's fatal reporter terminates via `abort()` / `_exit()`,
    /// skipping `UIApplication.willTerminateNotification`). The sink
    /// has the underlying SDK handle and knows how to do a real
    /// synchronous flush; the dispatcher only knows the protocol.
    ///
    /// Default implementation is a no-op — sinks that don't have an
    /// underlying SDK to flush (e.g. `NoopSink`, lightweight test
    /// fakes) inherit safe behavior. Production sinks like
    /// `OTelMobileCallSink` override.
    func forceFlush()
}

extension BridgeCallSink {
    public func forceFlush() {
        // Default no-op. Override in concrete sinks that wrap a real
        // exporter (e.g. OTelMobileCallSink calls `OTelMobile.forceFlush`).
    }
}

/// Bridge-side mirror of the JS `SamplingConfig`. Decoded from the RN
/// `start()` payload and translated to the native SDK's `SamplingConfig`
/// in `OTelMobileCallSink.start`. Kept SDK-independent so the dispatcher
/// and its tests compile without linking `OTelMobileSDK`.
public struct BridgeSamplingConfig: Equatable {
    public enum Strategy: String, Equatable {
        case alwaysOn = "always_on"
        case alwaysOff = "always_off"
        case dynamic

        /// Maps the JS `strategy` string; unknown values fall back to
        /// `.alwaysOn` (the RN default — see `BridgeStartConfig.sampling`).
        public static func fromToken(_ raw: String?) -> Strategy {
            switch raw {
            case "always_off": return .alwaysOff
            case "dynamic": return .dynamic
            default: return .alwaysOn
            }
        }
    }

    public let strategy: Strategy
    /// Baseline rate for `.dynamic`. Nil = native default.
    public let normalRate: Double?
    /// High-priority rate for `.dynamic`. Nil = native default.
    public let highPriorityRate: Double?

    public init(strategy: Strategy, normalRate: Double? = nil, highPriorityRate: Double? = nil) {
        self.strategy = strategy
        self.normalRate = normalRate
        self.highPriorityRate = highPriorityRate
    }
}

public struct BridgeStartConfig: Equatable {
    public let serviceName: String
    public let serviceVersion: String?
    public let endpoint: String
    public let authToken: String?
    public let dataset: String?
    /// Extra resource attributes from the JS caller (RN bridge populates
    /// `telemetry.distro.name` / `telemetry.distro.version` by default).
    public let extraResourceAttributes: [String: String]
    /// Native iOS auto-capture suites the JS caller wants enabled. Empty array
    /// (default) means the iOS SDK installs no UI/network/error instrumentation
    /// — RN apps get those signals from the JS-side shims (fetch + XHR +
    /// ErrorUtils + unhandledRejection + withTapTelemetry). Apps wanting the
    /// native iOS suite can opt in via `Dash0Mobile.start({ autoCapture: { vitals: true } })`.
    ///
    /// Supported tokens: "network", "errors", "lifecycle", "freeze", "vitals",
    /// "screen", "deviceStats". Unknown tokens are ignored (forward compat).
    public let nativeAutoCapture: [String]

    /// Trace sampling strategy from the JS caller, mapped onto the native
    /// `SamplingConfig` in `OTelMobileCallSink.start`.
    ///
    /// The RN bridge defaults this to `.alwaysOn` when the JS caller omits
    /// `sampling`, rather than inheriting the native SDK's `dynamic(0.1)`
    /// default. RN manual spans are root spans with arbitrary names, so a
    /// 10% baseline silently drops ~90% of a user's first span (Loper
    /// finding #4). Nil means the caller sent nothing — the sink falls back
    /// to `.alwaysOn` to preserve the RN default; in practice the JS bridge
    /// always sends a value.
    public let sampling: BridgeSamplingConfig?

    /// Base URL of the mobile-otel gateway serving `/config?dsl_version=2`.
    /// Enables the native RemoteGate kill switch + policy polling for RN apps
    /// whose `endpoint` points at plain OTLP ingest (no /config route). Nil
    /// (default) leaves the native SDK polling `endpoint` as before.
    public let gatewayEndpoint: String?
    /// Whether the native SDK should poll for remote policy config. Nil
    /// (default) keeps the native SDK's own default (`true`); `false`
    /// disables remote config entirely.
    public let enablePolicyPolling: Bool?
    /// Poll interval in seconds. Nil = native SDK default (300).
    public let configPollIntervalSeconds: Int?

    public init(
        serviceName: String,
        serviceVersion: String?,
        endpoint: String,
        authToken: String?,
        dataset: String?,
        extraResourceAttributes: [String: String] = [:],
        nativeAutoCapture: [String] = [],
        sampling: BridgeSamplingConfig? = nil,
        gatewayEndpoint: String? = nil,
        enablePolicyPolling: Bool? = nil,
        configPollIntervalSeconds: Int? = nil
    ) {
        self.serviceName = serviceName
        self.serviceVersion = serviceVersion
        self.endpoint = endpoint
        self.authToken = authToken
        self.dataset = dataset
        self.extraResourceAttributes = extraResourceAttributes
        self.nativeAutoCapture = nativeAutoCapture
        self.sampling = sampling
        self.gatewayEndpoint = gatewayEndpoint
        self.enablePolicyPolling = enablePolicyPolling
        self.configPollIntervalSeconds = configPollIntervalSeconds
    }
}
