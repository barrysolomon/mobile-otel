/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation

/// What kind of visual artifact a capture produces.
///
/// Passed to a `shouldCapture` consent gate so a customer can make a different
/// decision for a pixel screenshot vs. a structural wireframe (e.g. allow
/// wireframes, which carry no text, but never allow screenshots on a screen
/// showing a card number).
public enum CaptureKind: String, Sendable, Equatable, CaseIterable {
    /// A rendered, compressed screenshot of the key window (`ui.screenshot`).
    case screenshot
    /// A structural view-hierarchy tree with no pixels (`ui.wireframe`).
    case wireframe
}

/// Why a capture is being attempted.
///
/// The raw value is the wire string used in `mobile.*.trigger` attributes so a
/// `CaptureContext` round-trips losslessly to/from the emitted log. Triggers
/// that originate from a buffered-export policy match (crash recovery, ui
/// freeze, http error, …) are normalized to ``policy`` with the specific
/// policy name available via ``CaptureContext/policyName``.
public enum CaptureTrigger: Sendable, Equatable {
    /// An error/exception was recorded.
    case error
    /// A buffered-export policy fired. `name` is the originating policy, e.g.
    /// `"crash_recovery"`, `"ui_freeze"`, `"http_error"`.
    case policy(name: String)
    /// A new screen became active.
    case screenView
    /// A user tap (wireframe `captureOnTap`).
    case tap
    /// An explicit `capture()` call from customer code.
    case manual
    /// Any trigger string not recognized by the SDK. Carries the raw value so
    /// no information is lost.
    case other(String)

    /// Build a ``CaptureTrigger`` from the internal trigger string used across
    /// the capture instrumentations. `policy_*` prefixed strings map to
    /// ``policy(name:)`` with the prefix stripped.
    public init(rawTrigger: String) {
        switch rawTrigger {
        case "error":
            self = .error
        case "screen_view":
            self = .screenView
        case "tap":
            self = .tap
        case "manual":
            self = .manual
        default:
            if rawTrigger.hasPrefix("policy_") {
                self = .policy(name: String(rawTrigger.dropFirst("policy_".count)))
            } else {
                self = .other(rawTrigger)
            }
        }
    }

    /// The canonical wire string for this trigger (inverse of
    /// ``init(rawTrigger:)``).
    public var rawValue: String {
        switch self {
        case .error: return "error"
        case .policy(let name): return "policy_\(name)"
        case .screenView: return "screen_view"
        case .tap: return "tap"
        case .manual: return "manual"
        case .other(let raw): return raw
        }
    }
}

/// Context handed to a `shouldCapture` consent gate immediately before a
/// screenshot or wireframe capture is rendered.
///
/// The gate is consulted **synchronously on the main thread** right before any
/// rendering work happens. Returning `false` skips the capture entirely — no
/// view-tree walk, no pixel render, no log. The closure must be cheap and must
/// not block (it runs on the main thread in the capture hot path).
///
/// `CaptureContext` is `Sendable` and immutable so it can be safely passed to a
/// `@Sendable` closure.
public struct CaptureContext: Sendable, Equatable {
    /// Why the capture is happening.
    public let trigger: CaptureTrigger
    /// What kind of artifact the capture will produce.
    public let kind: CaptureKind
    /// The current screen name if known to the instrumentation, else `nil`.
    public let screenName: String?

    public init(trigger: CaptureTrigger, kind: CaptureKind, screenName: String? = nil) {
        self.trigger = trigger
        self.kind = kind
        self.screenName = screenName
    }

    /// Convenience: the originating policy name when ``trigger`` is
    /// ``CaptureTrigger/policy(name:)``, else `nil`.
    public var policyName: String? {
        if case .policy(let name) = trigger { return name }
        return nil
    }
}

/// A consent gate closure. Consulted synchronously on the main thread before
/// each capture; return `false` to skip the capture entirely.
public typealias CaptureConsentGate = @Sendable (CaptureContext) -> Bool
