import Foundation
import OTelMobileCore
import ScreenshotInstrumentation
import WireframeInstrumentation

public struct MobileConfig: Sendable {
    public let serviceName: String
    public let serviceVersion: String
    public let endpoint: String
    public let authToken: String?
    public let exportMode: ExportMode
    public let bufferConfig: BufferConfig
    public let privacyConfig: PrivacyConfig
    public let autoCaptureOptions: AutoCaptureOptions
    public let pollingIntervalSeconds: Int
    /// Additional headers to merge onto OTLP/HTTP exports. Use for things like
    /// `Dash0-Dataset` where the backend requires a header beyond bearer auth.
    /// Matches the Android SDK's `headers: Map<String, String>` config.
    public let extraHeaders: [String: String]

    /// If true, `OTelMobile.start(config:)` constructs a `ConfigPoller`
    /// against `<endpoint>/config?dsl_version=2` and feeds it to the
    /// `PolicyEvaluator` and the shared `RemoteGate`.
    ///
    /// **Default true.** This is an intentional behaviour change (see
    /// `docs/design/remote-kill-switch.md` §Polling defaults): the SDK now
    /// polls remote config by default so the remote kill switch + global
    /// sampling override are functional out of the box without an explicit
    /// opt-in. Poll interval, ephemeral session, 15s timeout and exponential
    /// backoff are unchanged. Set `false` to disable remote config entirely.
    public let enablePolicyPolling: Bool

    /// Sampling cadence for the device-stats gauge loop when
    /// `AutoCaptureOptions.deviceStats` is enabled. Default 15 seconds —
    /// low enough to see trends, high enough to avoid metric storm.
    public let deviceStatsIntervalSeconds: UInt64

    /// When true, `OTelMobile.start(config:)` constructs and starts a
    /// `PredictiveExportPolicy`. Off by default because predictive export
    /// adds a periodic prediction cycle that emits DEBUG/WARN logs even
    /// when the app is otherwise idle.
    public let enablePredictiveExport: Bool

    /// How often the predictive-export cycle runs when
    /// `enablePredictiveExport` is true.
    public let predictiveExportIntervalSeconds: UInt64

    /// Sampling configuration for the trace pipeline. Defaults to a
    /// `SamplingConfig.dynamic(0.1, 1.0)` — 10% baseline for high-volume
    /// spans, 100% for `page.*` and `app.startup` (so the trace
    /// waterfall stays intact for every screen). Override with
    /// `SamplingConfig.alwaysOn()` for development or
    /// `.production(rate:)` for fixed-rate trace-id sampling.
    public let samplingConfig: SamplingConfig

    /// Extra resource attributes merged into the SDK's built-in resource.
    /// Use this for vendor/framework identifiers — the React Native bridge
    /// populates `telemetry.distro.name` / `telemetry.distro.version` here
    /// so Dash0 can distinguish RN-originated telemetry. Mirrors Android's
    /// `MobileConfig.extraResourceAttributes`.
    public let extraResourceAttributes: [String: String]

    /// Offline disk budget configuration. Controls how much disk space the
    /// buffer may consume during offline periods and which events to evict.
    public let offlineBudgetConfig: OfflineBudgetConfig

    /// Offline policy controlling what gets buffered when the device has
    /// no network connectivity.
    public let offlinePolicy: OfflinePolicy

    /// Cadence for the CONTINUOUS-mode periodic log flush. In CONTINUOUS
    /// mode the buffer processor drains its RAM ring through the OTLP
    /// exporter every N seconds so long-running apps don't rely on a
    /// policy trigger or backgrounding event for logs to land in the
    /// backend. Ignored in CONDITIONAL and HYBRID modes — those flush only
    /// on policy match.
    ///
    /// Default 30s matches Android's `traceExportIntervalSeconds` so both
    /// platforms have the same observable latency characteristics.
    public let logExportIntervalSeconds: UInt64

    /// Configuration for the optional screenshot instrumentation. Only used
    /// when `AutoCaptureOptions.screenshot` is enabled. Mirrors Android's
    /// `MobileConfig.screenshotConfig`.
    public let screenshotConfig: ScreenshotConfig

    /// Configuration for the optional wireframe instrumentation. Only used
    /// when `AutoCaptureOptions.wireframe` is enabled. Mirrors Android's
    /// `MobileConfig.wireframeConfig`.
    public let wireframeConfig: WireframeConfig

    /// Permit cleartext (`http://`) transport to a non-loopback host for the
    /// OTLP exporters AND the config poller. **Default false** — a cleartext
    /// non-localhost endpoint is rejected and the corresponding pipeline is
    /// disabled (the SDK never crashes the host). Loopback/localhost endpoints
    /// are always permitted for local-collector development regardless of this
    /// flag. Set `true` only for a deliberate, network-isolated deployment.
    /// Mirrors Android's `MobileConfig.allowInsecureTransport`.
    public let allowInsecureTransport: Bool

    /// Optional certificate / public-key pinning applied to BOTH the OTLP
    /// export connections and the config-poller connection. When `nil` (the
    /// default) no pinning is performed. A pin mismatch fails only that
    /// connection (fail-closed for the connection), never the host. Mirrors
    /// Android's `MobileConfig.pinningConfig`.
    public let pinning: TransportSecurity.PinningConfig?

    /// Optional HMAC-SHA256 shared secret used to verify the integrity of
    /// fetched remote-config payloads before applying them (closes the
    /// kill-switch MITM/OTA-abuse vector — see
    /// `docs/design/remote-kill-switch.md` §Config integrity). When `nil` (the
    /// default) config is applied as before (backward compatible). When set,
    /// the poller verifies the `X-Dash0-Config-Signature` header over the raw
    /// body and **keeps the last-applied config** on verification failure
    /// (fail toward availability — never disables telemetry on a bad
    /// signature). Mirrors Android's `MobileConfig.configSigningKey`.
    public let configSigningKey: Data?

    public init(
        serviceName: String,
        serviceVersion: String = "1.0.0",
        endpoint: String,
        authToken: String? = nil,
        exportMode: ExportMode = .hybrid,
        bufferConfig: BufferConfig = .default,
        privacyConfig: PrivacyConfig = .default,
        autoCaptureOptions: AutoCaptureOptions = .default,
        pollingIntervalSeconds: Int = 300,
        extraHeaders: [String: String] = [:],
        enablePolicyPolling: Bool = true,
        deviceStatsIntervalSeconds: UInt64 = 15,
        enablePredictiveExport: Bool = false,
        predictiveExportIntervalSeconds: UInt64 = 30,
        samplingConfig: SamplingConfig = .dynamic(normalRate: 0.1, highPriorityRate: 1.0),
        extraResourceAttributes: [String: String] = [:],
        logExportIntervalSeconds: UInt64 = 30,
        offlineBudgetConfig: OfflineBudgetConfig = .default,
        offlinePolicy: OfflinePolicy = .bufferAll,
        screenshotConfig: ScreenshotConfig = ScreenshotConfig(),
        wireframeConfig: WireframeConfig = WireframeConfig(),
        allowInsecureTransport: Bool = false,
        pinning: TransportSecurity.PinningConfig? = nil,
        configSigningKey: Data? = nil
    ) {
        self.serviceName = serviceName
        self.serviceVersion = serviceVersion
        self.endpoint = endpoint
        self.authToken = authToken
        self.exportMode = exportMode
        self.bufferConfig = bufferConfig
        self.privacyConfig = privacyConfig
        self.autoCaptureOptions = autoCaptureOptions
        self.pollingIntervalSeconds = pollingIntervalSeconds
        self.extraHeaders = extraHeaders
        self.enablePolicyPolling = enablePolicyPolling
        self.deviceStatsIntervalSeconds = deviceStatsIntervalSeconds
        self.enablePredictiveExport = enablePredictiveExport
        self.predictiveExportIntervalSeconds = predictiveExportIntervalSeconds
        self.samplingConfig = samplingConfig
        self.extraResourceAttributes = extraResourceAttributes
        self.logExportIntervalSeconds = logExportIntervalSeconds
        self.offlineBudgetConfig = offlineBudgetConfig
        self.offlinePolicy = offlinePolicy
        self.screenshotConfig = screenshotConfig
        self.wireframeConfig = wireframeConfig
        self.allowInsecureTransport = allowInsecureTransport
        self.pinning = pinning
        self.configSigningKey = configSigningKey
    }
}
