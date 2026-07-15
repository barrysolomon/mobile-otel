/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation
import OpenTelemetryApi
#if canImport(SwiftUI)
import SwiftUI
#endif

/// SwiftUI-safe alternatives to the UIKit `UIViewController.viewDidAppear`
/// swizzle in `ScreenInstrumentation`. Apps opt in by attaching
/// `.trackScreen("Name")` / `.trackTaps(target: "Name")` to individual views.
///
/// These do not swizzle. They can't race with SwiftUI's `UIHostingController`
/// lifecycle because they go through SwiftUI's own `.onAppear` / `.onDisappear`
/// / `.simultaneousGesture` hooks.

#if canImport(SwiftUI)

/// Attach to a SwiftUI view to emit `screen.view` log + `page.<Name>` span
/// bracketing the view's on-screen time.
///
/// Usage:
/// ```swift
/// ProductListView(...)
///     .trackScreen("ProductList")
/// ```
@available(iOS 15.0, *)
public extension View {
    func trackScreen(_ name: String) -> some View {
        modifier(Dash0ScreenModifier(name: name))
    }

    /// Emit `ui.tap` log + span when this view is tapped. Composes with (and
    /// does not replace) the view's own gesture handlers.
    ///
    /// Usage:
    /// ```swift
    /// Button("Checkout") { ... }
    ///     .trackTaps(target: "checkout_button")
    /// ```
    func trackTaps(target: String) -> some View {
        modifier(Dash0TapModifier(target: target))
    }
}

@available(iOS 15.0, *)
struct Dash0ScreenModifier: ViewModifier {
    let name: String
    @State private var activeSpanId: String?

    func body(content: Content) -> some View {
        content
            .onAppear {
                Dash0ScreenInstrumentationBridge.shared.onAppear(screen: name)
            }
            .onDisappear {
                Dash0ScreenInstrumentationBridge.shared.onDisappear(screen: name)
            }
    }
}

@available(iOS 15.0, *)
struct Dash0TapModifier: ViewModifier {
    let target: String

    func body(content: Content) -> some View {
        content.simultaneousGesture(
            TapGesture().onEnded {
                Dash0ScreenInstrumentationBridge.shared.onTap(target: target)
            }
        )
    }
}

#endif

/// Bridges the SwiftUI modifiers to the already-installed
/// `ScreenInstrumentation.shared` (tracer + logger). Avoids duplicating
/// install state; SwiftUI modifiers are completely safe to attach even when
/// the instrumentation hasn't been installed yet — they no-op.
public final class Dash0ScreenInstrumentationBridge: @unchecked Sendable {
    public static let shared = Dash0ScreenInstrumentationBridge()

    private init() {}

    func onAppear(screen: String) {
        ScreenInstrumentation.shared.handleSwiftUIScreenAppear(name: screen)
    }

    func onDisappear(screen: String) {
        ScreenInstrumentation.shared.handleSwiftUIScreenDisappear(name: screen)
    }

    func onTap(target: String) {
        ScreenInstrumentation.shared.handleSwiftUITap(target: target)
    }
}
