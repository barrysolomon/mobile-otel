import Foundation
import OTelMobileCore

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
    /// When `true` (default), the module hashes each captured wireframe JSON
    /// and emits a lightweight `ui.wireframe.ref` log carrying only the
    /// prior `mobile.wireframe.id` if the hash matches the previous capture.
    /// Saves the 1–5 KB payload on no-op screen-resume / tap captures.
    /// Mirrors Android's `WireframeConfig.dedupeByContentHash`.
    public var dedupeByContentHash: Bool
    public var includeAccessibilityIdentifiers: Bool
    public var includeTextHints: Bool
    public var includeContentDescription: Bool
    public var includeInteractionState: Bool
    /// Optional customer-owned consent gate. When non-`nil`, it is consulted
    /// **synchronously on the main thread immediately before each capture**
    /// (after the `enabled` and rate-limit checks, before the view-tree walk).
    /// If it returns `false`, the capture is skipped entirely — no walk, no
    /// log emitted. When `nil`, capture follows ``enabled`` alone. The consent
    /// gate is an *additional* runtime gate on top of ``enabled``, not a
    /// replacement. Keep the closure cheap and non-blocking; it runs in the
    /// capture hot path on the main thread.
    public var shouldCapture: CaptureConsentGate?

    public init(
        enabled: Bool = true,
        maxCapturesPerMinute: Int = 30,
        maxDepth: Int = 20,
        captureOnScreenView: Bool = true,
        captureOnTap: Bool = false,
        captureOnError: Bool = true,
        captureOnPolicyMatch: Bool = true,
        dedupeByContentHash: Bool = true,
        includeAccessibilityIdentifiers: Bool = true,
        includeTextHints: Bool = false,
        includeContentDescription: Bool = true,
        includeInteractionState: Bool = true,
        shouldCapture: CaptureConsentGate? = nil
    ) {
        self.enabled = enabled
        self.maxCapturesPerMinute = maxCapturesPerMinute
        self.maxDepth = maxDepth
        self.captureOnScreenView = captureOnScreenView
        self.captureOnTap = captureOnTap
        self.captureOnError = captureOnError
        self.captureOnPolicyMatch = captureOnPolicyMatch
        self.dedupeByContentHash = dedupeByContentHash
        self.includeAccessibilityIdentifiers = includeAccessibilityIdentifiers
        self.includeTextHints = includeTextHints
        self.includeContentDescription = includeContentDescription
        self.includeInteractionState = includeInteractionState
        self.shouldCapture = shouldCapture
    }

    /// Builder-style setter for the consent gate. Returns a copy with
    /// ``shouldCapture`` set.
    public func withConsentGate(_ gate: @escaping CaptureConsentGate) -> WireframeConfig {
        var copy = self
        copy.shouldCapture = gate
        return copy
    }
}
