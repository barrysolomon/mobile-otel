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

    public init(
        serviceName: String,
        serviceVersion: String = "1.0.0",
        endpoint: String,
        authToken: String? = nil,
        exportMode: ExportMode = .conditional,
        bufferConfig: BufferConfig = .default,
        privacyConfig: PrivacyConfig = .default,
        autoCaptureOptions: AutoCaptureOptions = .all,
        pollingIntervalSeconds: Int = 300
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
    }
}
