import Foundation

#if canImport(UIKit) && (os(iOS) || os(tvOS))
import UIKit
#endif

#if canImport(SwiftUI)
import SwiftUI
#endif

/// Deterministic redaction API for screenshot & wireframe capture.
///
/// Replaces the previous best-effort approach (mask every `UITextField`, plus a
/// brittle class-name string match for SwiftUI secure fields) with two
/// reliable, non-heuristic signals the capture walk can trust:
///
/// 1. **UIKit secure fields** — `UITextField`/`UITextView` whose
///    `isSecureTextEntry` is `true` are masked deterministically. This is the
///    OS-level secure-entry flag, not a guess.
/// 2. **Explicit tagging** — any `UIView` marked via ``Dash0/redact(_:)`` /
///    `UIView.dash0MarkSensitive()` carries an associated-object flag that the
///    walk reads directly. SwiftUI views opt in through the
///    `.dash0Redacted()` modifier, which installs a backing `UIView` carrying
///    the same flag — so SwiftUI gets a robust, non-heuristic redaction path
///    that does not depend on private SwiftUI class names.
///
/// The legacy class-name string match is demoted to a conservative,
/// opt-in-only last resort (see ``Dash0/conservativeClassNameFallbackEnabled``)
/// and is **off by default**.
///
/// ### Thread-safety
///
/// The redaction tag is stored as a `UIView` associated object. UIKit view
/// state is main-thread-only, and the capture walk runs on the main thread, so
/// all tag reads/writes happen on the main thread. The public mutators are
/// documented as main-thread API.
public enum Dash0 {
    /// Marks a view (and, by inclusion, the on-screen region it occupies) as
    /// containing sensitive content. The capture walk masks the view's frame in
    /// screenshots and emits `redacted: true` (with no text) in wireframes.
    ///
    /// Idempotent. Call on the main thread (UIKit view state is main-thread
    /// only; the capture walk that reads the mark also runs on the main
    /// thread).
    ///
    /// ```swift
    /// Dash0.redact(cardNumberLabel)
    /// ```
    #if canImport(UIKit) && (os(iOS) || os(tvOS))
    public static func redact(_ view: UIView) {
        view.dash0MarkSensitive()
    }

    /// Removes a previously applied sensitive mark. Call on the main thread.
    public static func unredact(_ view: UIView) {
        view.dash0UnmarkSensitive()
    }
    #endif

    /// When `true`, the capture walk additionally masks views whose runtime
    /// class name contains a known-sensitive token (e.g. SwiftUI's private
    /// secure-field host) as a **last-resort** conservative fallback. This is
    /// the demoted form of the old heuristic: it can over-mask and can break
    /// silently across OS versions, so it is **disabled by default**. Prefer
    /// ``redact(_:)`` and `.dash0Redacted()`.
    ///
    /// Reads/writes are not synchronized; set it once during configuration
    /// before any capture runs.
    nonisolated(unsafe) public static var conservativeClassNameFallbackEnabled = false
}

#if canImport(UIKit) && (os(iOS) || os(tvOS))

private enum Dash0RedactionAssociatedKeys {
    nonisolated(unsafe) static var sensitive: UInt8 = 0
}

public extension UIView {
    /// Marks this view as containing sensitive content for capture redaction.
    /// See ``Dash0/redact(_:)``. Idempotent; main-thread API.
    func dash0MarkSensitive() {
        objc_setAssociatedObject(
            self,
            &Dash0RedactionAssociatedKeys.sensitive,
            true,
            .OBJC_ASSOCIATION_RETAIN_NONATOMIC
        )
    }

    /// Clears a previously set sensitive mark. Main-thread API.
    func dash0UnmarkSensitive() {
        objc_setAssociatedObject(
            self,
            &Dash0RedactionAssociatedKeys.sensitive,
            nil,
            .OBJC_ASSOCIATION_RETAIN_NONATOMIC
        )
    }

    /// `true` when this view was explicitly marked sensitive via
    /// ``dash0MarkSensitive()`` / ``Dash0/redact(_:)``. Main-thread read.
    var dash0IsMarkedSensitive: Bool {
        (objc_getAssociatedObject(self, &Dash0RedactionAssociatedKeys.sensitive) as? Bool) ?? false
    }
}

/// Centralizes the *decision* of whether a given view must be masked, so the
/// screenshot walk and the wireframe walk share one source of truth.
public enum Dash0RedactionPolicy {
    /// Known-sensitive class-name tokens for the conservative last-resort
    /// fallback. Matched case-insensitively against the view's runtime class
    /// name. Only consulted when ``Dash0/conservativeClassNameFallbackEnabled``
    /// is `true`.
    static let conservativeSensitiveClassTokens = ["securefield", "securetext"]

    /// Decide whether `view` must be masked.
    ///
    /// - Parameter redactAllText: when `true`, every text-bearing view
    ///   (`UITextField`, `UITextView`, `UILabel`) is masked regardless of the
    ///   secure flag (the "redact all text" mode). When `false`, only secure
    ///   fields and explicitly-tagged views are masked.
    /// - Returns: `true` if the view's region should be masked.
    ///
    /// Main-thread only (reads UIKit view state + the associated-object tag).
    public static func shouldRedact(_ view: UIView, redactAllText: Bool) -> Bool {
        // 1. Explicit tag — the robust, deterministic path (covers SwiftUI via
        //    `.dash0Redacted()` and any manually-tagged UIView).
        if view.dash0IsMarkedSensitive { return true }

        // 2. OS-level secure entry flag on UIKit fields.
        if let tf = view as? UITextField, tf.isSecureTextEntry { return true }
        if let tv = view as? UITextView, tv.isSecureTextEntry { return true }

        // 3. Redact-all-text mode: mask every text-bearing view.
        if redactAllText {
            if view is UITextField || view is UITextView || view is UILabel { return true }
        }

        // 4. Last-resort conservative class-name fallback (off by default).
        if Dash0.conservativeClassNameFallbackEnabled {
            let className = String(describing: Swift.type(of: view)).lowercased()
            if conservativeSensitiveClassTokens.contains(where: { className.contains($0) }) {
                return true
            }
        }

        return false
    }
}

#endif

#if canImport(SwiftUI)

/// A SwiftUI `ViewModifier` that marks the modified view's on-screen region as
/// sensitive for Dash0 capture redaction.
///
/// It works by overlaying a zero-cost, fully transparent, non-interactive
/// `UIView` (via `UIViewRepresentable`) that exactly tracks the modified view's
/// bounds and carries the ``UIView/dash0MarkSensitive()`` flag. The capture
/// walk finds that backing view and masks its frame — a deterministic,
/// non-heuristic path that does not depend on SwiftUI's private view class
/// names.
///
/// ```swift
/// SecureField("Password", text: $password)
///     .dash0Redacted()
/// ```
@available(iOS 15.0, tvOS 15.0, *)
public struct Dash0RedactedModifier: ViewModifier {
    public init() {}

    public func body(content: Content) -> some View {
        #if canImport(UIKit) && (os(iOS) || os(tvOS))
        content.background(Dash0RedactionTagView().allowsHitTesting(false))
        #else
        // No UIKit backing view to tag on this platform; the capture modules
        // are UIKit-only, so redaction is a no-op passthrough here.
        content
        #endif
    }
}

@available(iOS 15.0, tvOS 15.0, *)
public extension View {
    /// Marks this SwiftUI view's region as sensitive so Dash0 screenshot and
    /// wireframe capture mask it. Robust replacement for relying on SwiftUI
    /// secure-field class-name detection. Apply directly to the sensitive view
    /// (e.g. a `SecureField`, or a `Text` showing a card number).
    func dash0Redacted() -> some View {
        modifier(Dash0RedactedModifier())
    }
}

#if canImport(UIKit) && (os(iOS) || os(tvOS))
/// The transparent backing view that carries the sensitive flag for SwiftUI's
/// `.dash0Redacted()`. Fills its SwiftUI region (it is used as a `.background`)
/// so its frame in the window matches the redacted region.
@available(iOS 15.0, tvOS 15.0, *)
struct Dash0RedactionTagView: UIViewRepresentable {
    func makeUIView(context: Context) -> Dash0RedactionBackingView {
        Dash0RedactionBackingView.makeTagged()
    }

    func updateUIView(_ uiView: Dash0RedactionBackingView, context: Context) {
        // Mark is set at creation and never cleared; nothing to update.
    }
}

/// A distinct subclass so the redaction backing view is unambiguous in the
/// view tree (useful for debugging and wireframe node typing). The sensitivity
/// signal is the associated-object flag, not this class name.
@available(iOS 15.0, tvOS 15.0, *)
final class Dash0RedactionBackingView: UIView {
    /// Builds the configured, sensitivity-tagged backing view. This is the
    /// single source of truth for how `.dash0Redacted()` tags its region, and
    /// is exposed so it can be unit-tested without synthesizing a SwiftUI
    /// `UIViewRepresentableContext` (which has no public initializer).
    static func makeTagged() -> Dash0RedactionBackingView {
        let view = Dash0RedactionBackingView()
        view.backgroundColor = .clear
        view.isUserInteractionEnabled = false
        view.dash0MarkSensitive()
        return view
    }
}
#endif

#endif
