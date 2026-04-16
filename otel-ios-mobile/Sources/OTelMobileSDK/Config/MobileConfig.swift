import OTelMobileCore

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

    public init(
        serviceName: String,
        serviceVersion: String = "1.0.0",
        endpoint: String,
        authToken: String? = nil,
        exportMode: ExportMode = .conditional,
        bufferConfig: BufferConfig = .default,
        privacyConfig: PrivacyConfig = .default,
        autoCaptureOptions: AutoCaptureOptions = .all,
        pollingIntervalSeconds: Int = 300,
        extraHeaders: [String: String] = [:]
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
    }
}
