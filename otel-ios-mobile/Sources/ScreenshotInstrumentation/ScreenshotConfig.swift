import Foundation

public struct ScreenshotConfig: Sendable {
    public var enabled: Bool
    public var maxWidthPx: Int
    public var maxHeightPx: Int
    public var quality: Int
    public var format: ScreenshotFormat
    public var maxPayloadKb: Int
    public var maxCapturesPerMinute: Int
    public var redactTextFields: Bool
    public var captureOnScreenView: Bool
    public var captureOnError: Bool
    /// Capture a screenshot whenever a buffered-export policy fires
    /// (crash-recovery, ui-freeze, http-error, etc.). Default `true` — every
    /// server-side incident gets an attached visual artifact. Rate limited
    /// via `maxCapturesPerMinute`. Mirrors Android's
    /// `ScreenshotConfig.captureOnPolicyMatch`.
    public var captureOnPolicyMatch: Bool
    public var screenViewDelayMs: Int

    public init(
        enabled: Bool = true,
        maxWidthPx: Int = 480,
        maxHeightPx: Int = 960,
        quality: Int = 60,
        format: ScreenshotFormat = .jpeg,
        maxPayloadKb: Int = 256,
        maxCapturesPerMinute: Int = 5,
        redactTextFields: Bool = true,
        captureOnScreenView: Bool = false,
        captureOnError: Bool = true,
        captureOnPolicyMatch: Bool = true,
        screenViewDelayMs: Int = 300
    ) {
        self.enabled = enabled
        self.maxWidthPx = maxWidthPx
        self.maxHeightPx = maxHeightPx
        self.quality = quality
        self.format = format
        self.maxPayloadKb = maxPayloadKb
        self.maxCapturesPerMinute = maxCapturesPerMinute
        self.redactTextFields = redactTextFields
        self.captureOnScreenView = captureOnScreenView
        self.captureOnError = captureOnError
        self.captureOnPolicyMatch = captureOnPolicyMatch
        self.screenViewDelayMs = screenViewDelayMs
    }
}

public enum ScreenshotFormat: String, Sendable {
    case jpeg
    case png
}
