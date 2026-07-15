/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation
import OTelMobileCore

public struct ScreenshotConfig: Sendable {
    public var enabled: Bool
    public var maxWidthPx: Int
    public var maxHeightPx: Int
    public var quality: Int
    public var format: ScreenshotFormat
    public var maxPayloadKb: Int
    public var maxCapturesPerMinute: Int
    /// When `true` (default), the capture walk masks sensitive regions before
    /// encoding: UIKit fields with `isSecureTextEntry == true`, any view tagged
    /// via `Dash0.redact(_:)` / `.dash0Redacted()`. See ``redactAllText`` to
    /// additionally mask *all* text. Setting this `false` disables redaction
    /// entirely (DEBUG/internal use only — never ship a build that captures
    /// unredacted screens of production data).
    public var redactTextFields: Bool
    /// When `true`, the redaction walk masks **every** text-bearing view
    /// (`UITextField`, `UITextView`, `UILabel`) in addition to secure and
    /// tagged views — the maximally-conservative mode. Default `false`:
    /// only secure fields and explicitly-tagged regions are masked, so
    /// non-sensitive UI text stays legible for debugging. Has no effect when
    /// ``redactTextFields`` is `false`.
    public var redactAllText: Bool
    /// Optional customer-owned consent gate. When non-`nil`, it is consulted
    /// **synchronously on the main thread immediately before each capture**
    /// (after the `enabled` and rate-limit checks, before any render). If it
    /// returns `false`, the capture is skipped entirely — no view-tree walk, no
    /// pixel render, no log emitted. When `nil`, capture follows ``enabled``
    /// alone (which is the explicit opt-in flag). The consent gate is an
    /// *additional* runtime gate layered on top of ``enabled``, not a
    /// replacement for it. Keep the closure cheap and non-blocking; it runs in
    /// the capture hot path on the main thread.
    public var shouldCapture: CaptureConsentGate?
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
        redactAllText: Bool = false,
        shouldCapture: CaptureConsentGate? = nil,
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
        self.redactAllText = redactAllText
        self.shouldCapture = shouldCapture
        self.captureOnScreenView = captureOnScreenView
        self.captureOnError = captureOnError
        self.captureOnPolicyMatch = captureOnPolicyMatch
        self.screenViewDelayMs = screenViewDelayMs
    }

    /// Builder-style setter for the consent gate. Returns a copy with
    /// ``shouldCapture`` set.
    ///
    /// ```swift
    /// let config = ScreenshotConfig(enabled: true)
    ///     .withConsentGate { ctx in ConsentManager.shared.allows(ctx) }
    /// ```
    public func withConsentGate(_ gate: @escaping CaptureConsentGate) -> ScreenshotConfig {
        var copy = self
        copy.shouldCapture = gate
        return copy
    }
}

public enum ScreenshotFormat: String, Sendable {
    case jpeg
    case png
}
