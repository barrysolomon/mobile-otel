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

    public init(
        serviceName: String,
        serviceVersion: String?,
        endpoint: String,
        authToken: String?,
        dataset: String?,
        extraResourceAttributes: [String: String] = [:],
        nativeAutoCapture: [String] = []
    ) {
        self.serviceName = serviceName
        self.serviceVersion = serviceVersion
        self.endpoint = endpoint
        self.authToken = authToken
        self.dataset = dataset
        self.extraResourceAttributes = extraResourceAttributes
        self.nativeAutoCapture = nativeAutoCapture
    }
}
