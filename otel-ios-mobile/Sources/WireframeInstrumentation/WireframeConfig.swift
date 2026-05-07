import Foundation

public struct WireframeConfig: Sendable {
    public var enabled: Bool
    public var maxCapturesPerMinute: Int
    public var maxDepth: Int
    public var captureOnScreenView: Bool
    public var captureOnTap: Bool
    public var captureOnError: Bool
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
        self.includeAccessibilityIdentifiers = includeAccessibilityIdentifiers
        self.includeTextHints = includeTextHints
        self.includeContentDescription = includeContentDescription
        self.includeInteractionState = includeInteractionState
    }
}
