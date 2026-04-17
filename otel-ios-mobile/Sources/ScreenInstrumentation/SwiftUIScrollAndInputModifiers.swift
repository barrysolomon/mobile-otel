import Foundation
import OpenTelemetryApi
#if canImport(SwiftUI)
import SwiftUI
#endif

/// Additional SwiftUI ViewModifiers for scroll + text-input tracking, matching
/// the trackScreen/trackTaps pattern. All emit via the already-installed
/// `ScreenInstrumentation.shared` — no extra setup needed.

#if canImport(SwiftUI)

@available(iOS 15.0, *)
public extension View {
    /// Emit `ui.scroll` logs as the view scrolls. Throttled to ~5 Hz to avoid
    /// log storms on continuous scroll. Attach to a `ScrollView` or any view
    /// whose scroll offset you're willing to report.
    ///
    /// Usage:
    /// ```swift
    /// ScrollView { ... }
    ///     .trackScrolls(target: "product_list")
    /// ```
    func trackScrolls(target: String) -> some View {
        modifier(Dash0ScrollModifier(target: target))
    }

    /// Emit `ui.text_input` on focus change / submit of a `TextField`. Does
    /// NOT record the typed text — only the field's target identifier. This
    /// is privacy-preserving by default: we record that a user interacted
    /// with a field, not what they typed.
    ///
    /// Usage:
    /// ```swift
    /// TextField("Email", text: $email)
    ///     .trackTextInput(target: "signup_email")
    /// ```
    func trackTextInput(target: String) -> some View {
        modifier(Dash0TextInputModifier(target: target))
    }
}

@available(iOS 15.0, *)
struct Dash0ScrollModifier: ViewModifier {
    let target: String
    @State private var lastEmit: Date = Date.distantPast

    func body(content: Content) -> some View {
        // Use a GeometryReader to watch frame changes. This is lighter than
        // scrollPosition tracking and compatible with iOS 15. We throttle to
        // ~200ms between emits.
        content.overlay(
            GeometryReader { geo in
                Color.clear
                    .onChange(of: geo.frame(in: .global).origin.y) { _ in
                        let now = Date()
                        if now.timeIntervalSince(lastEmit) >= 0.2 {
                            lastEmit = now
                            ScreenInstrumentation.shared.handleSwiftUIScroll(target: target)
                        }
                    }
            }
            .allowsHitTesting(false)
        )
    }
}

@available(iOS 15.0, *)
struct Dash0TextInputModifier: ViewModifier {
    let target: String
    @FocusState private var focused: Bool

    func body(content: Content) -> some View {
        content
            .focused($focused)
            .onChange(of: focused) { isFocused in
                // Emit on focus GAIN only — this mirrors Android's TextInput
                // tracking which fires on onFocusChangeListener with
                // hasFocus=true. Loss-of-focus events aren't emitted to avoid
                // double-counting (focus loss is implicit from the NEXT
                // field's focus gain OR screen change).
                if isFocused {
                    ScreenInstrumentation.shared.handleSwiftUITextInput(target: target)
                }
            }
    }
}

#endif
