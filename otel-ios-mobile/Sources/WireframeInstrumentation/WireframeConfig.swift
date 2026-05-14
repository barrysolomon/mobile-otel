import Foundation

public struct WireframeConfig: Sendable {
    public var enabled: Bool
    public var maxCapturesPerMinute: Int
    public var maxDepth: Int
    public var captureOnScreenView: Bool
    public var captureOnTap: Bool
    public var captureOnError: Bool
    /// Capture a wireframe whenever a buffered-export policy fires
    /// (crash-recovery, ui-freeze, http-error, etc.). The wireframe gets
    /// emitted into the same flush window, giving every server-side incident
    /// an attached "what was on screen" artifact. Default `true`. Mirrors
    /// Android's `WireframeConfig.captureOnPolicyMatch`.
    public var captureOnPolicyMatch: Bool
    public var includeAccessibilityIdentifiers: Bool
    public var includeTextHints: Bool
    public var includeContentDescription: Bool
    public var includeInteractionState: Bool

    public init(
        enabled: Bool = true,
        maxCapturesPerMinute: Int = 30,
        maxDepth: Int = 20,
        captureOnScreenView: Bool = true,
        captureOnTap: Bool = false,
        captureOnError: Bool = true,
        captureOnPolicyMatch: Bool = true,
        includeAccessibilityIdentifiers: Bool = true,
        includeTextHints: Bool = false,
        includeContentDescription: Bool = true,
        includeInteractionState: Bool = true
    ) {
        self.enabled = enabled
        self.maxCapturesPerMinute = maxCapturesPerMinute
        self.maxDepth = maxDepth
        self.captureOnScreenView = captureOnScreenView
        self.captureOnTap = captureOnTap
        self.captureOnError = captureOnError
        self.captureOnPolicyMatch = captureOnPolicyMatch
        self.includeAccessibilityIdentifiers = includeAccessibilityIdentifiers
        self.includeTextHints = includeTextHints
        self.includeContentDescription = includeContentDescription
        self.includeInteractionState = includeInteractionState
    }
}
