/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation
import OpenTelemetryApi
import OTelMobileCore
#if canImport(UIKit)
import UIKit
import ObjectiveC.runtime
#endif

/// Auto-captures screen transitions by swizzling `UIViewController.viewDidAppear`
/// and `viewDidDisappear`. Works for both UIKit apps and SwiftUI apps (the
/// `UIHostingController` wrapping each SwiftUI screen still fires these methods).
///
/// For each viewDidAppear, emits:
/// - Log record with body `screen.view` + attribute `screen.name` = the VC class
///   (or unwrapped hosting controller type name for SwiftUI)
/// - Starts a span `page.<ScreenName>` whose lifetime brackets the on-screen
///   time. Ends on viewDidDisappear.
public final class ScreenInstrumentation: @unchecked Sendable {
    public static let shared = ScreenInstrumentation()

    private let lock = NSLock()
    private var installed = false
    private var tracer: Tracer?
    private var logger: Logger?

    private init() {}

    /// Install screen instrumentation. By default, only the SAFE SwiftUI
    /// bridge is enabled — apps attach `.trackScreen("Name")` to SwiftUI
    /// views and we emit through the shared singleton. The UIKit
    /// `UIViewController.viewDidAppear` swizzle path remains opt-in via
    /// `enableUIKitSwizzle: true` because it can race with SwiftUI's
    /// `UIHostingController` lifecycle and has left apps on a blank launch
    /// screen in testing.
    public func install(tracer: Tracer, logger: Logger, enableUIKitSwizzle: Bool = false) {
        lock.lock(); defer { lock.unlock() }
        self.tracer = tracer
        self.logger = logger
        guard !installed else { return }
        installed = true
        #if canImport(UIKit) && (os(iOS) || os(tvOS))
        if enableUIKitSwizzle {
            ScreenSwizzle.installOnce()
        }
        #endif
    }

    public func uninstall() {
        lock.lock(); defer { lock.unlock() }
        installed = false
        // Swizzles cannot be reliably reversed — short-circuit via `installed`.
    }

    // Called from swizzled viewDidAppear
    fileprivate func handleViewDidAppear(screenName: String) {
        lock.lock()
        let enabled = installed
        let tracer = self.tracer
        let logger = self.logger
        lock.unlock()
        guard enabled else { return }

        logger?.logRecordBuilder()
            .setBody(.string("ui.screen_view"))
            .setSeverity(.info)
            .setAttributes([
                "event.name": .string("ui.screen_view"),
                "screen.name": .string(screenName),
            ])
            .emit()

        if let tracer = tracer {
            let span = tracer.spanBuilder(spanName: "page.\(screenName)")
                .setSpanKind(spanKind: .internal)
                .startSpan()
            ScreenSpanRegistry.shared.set(screen: screenName, span: span)
        }
    }

    fileprivate func handleViewDidDisappear(screenName: String) {
        lock.lock()
        let enabled = installed
        lock.unlock()
        guard enabled else { return }
        ScreenSpanRegistry.shared.endSpan(for: screenName)
    }

    // MARK: - SwiftUI bridge (no swizzling — safe to call even before install)

    /// Called from the `.trackScreen("Name")` ViewModifier. Always emits —
    /// the SwiftUI opt-in path works whether or not UIKit swizzling is active.
    internal func handleSwiftUIScreenAppear(name: String) {
        handleViewDidAppear(screenName: name)
    }

    internal func handleSwiftUIScreenDisappear(name: String) {
        handleViewDidDisappear(screenName: name)
    }

    /// Called from the `.trackTaps(target:)` ViewModifier. Emits a `ui.tap`
    /// log + span. Target is the caller-supplied identifier, not a gesture
    /// recognizer introspection — this keeps us 100% SwiftUI-safe and
    /// privacy-preserving (no view-tree walking, no text extraction).
    internal func handleSwiftUITap(target: String) {
        emitUIEvent(eventName: "ui.tap", target: target, spanName: "ui.tap")
    }

    /// Called from `.trackScrolls(target:)`. Caller throttles; we just emit.
    internal func handleSwiftUIScroll(target: String) {
        emitUIEvent(eventName: "ui.scroll", target: target, spanName: "ui.scroll")
    }

    /// Called from `.trackTextInput(target:)` on focus gain. Privacy-safe:
    /// only the target identifier is recorded, never the typed text.
    internal func handleSwiftUITextInput(target: String) {
        emitUIEvent(eventName: "ui.text_input", target: target, spanName: "ui.text_input")
    }

    private func emitUIEvent(eventName: String, target: String, spanName: String) {
        lock.lock()
        let enabled = installed
        let logger = self.logger
        let tracer = self.tracer
        lock.unlock()
        guard enabled else { return }

        logger?.logRecordBuilder()
            .setBody(.string(eventName))
            .setSeverity(.info)
            .setAttributes([
                "event.name": .string(eventName),
                "ui.target": .string(target),
            ])
            .emit()

        if let tracer = tracer {
            let span = tracer.spanBuilder(spanName: spanName)
                .setSpanKind(spanKind: .internal)
                .startSpan()
            span.setAttribute(key: "ui.target", value: target)
            span.end()
        }
    }
}

/// Tracks the currently-active page span per screen name so we can end it when
/// viewDidDisappear fires. Keyed by `screenName` — if multiple VCs of the same
/// class are on screen, the most recent one wins (acceptable for demo-grade).
final class ScreenSpanRegistry: @unchecked Sendable {
    static let shared = ScreenSpanRegistry()
    private let lock = NSLock()
    private var spans: [String: Span] = [:]

    private init() {}

    func set(screen: String, span: Span) {
        lock.lock(); defer { lock.unlock() }
        // End any previously-open span for the same screen to avoid leaks.
        spans[screen]?.end()
        spans[screen] = span
    }

    func endSpan(for screen: String) {
        lock.lock(); defer { lock.unlock() }
        spans[screen]?.end()
        spans.removeValue(forKey: screen)
    }
}

#if canImport(UIKit) && (os(iOS) || os(tvOS))
/// Swizzles UIViewController.viewDidAppear / viewDidDisappear exactly once.
enum ScreenSwizzle {
    private static var installed = false

    static func installOnce() {
        guard !installed else { return }
        installed = true
        swizzle(
            original: #selector(UIViewController.viewDidAppear(_:)),
            replacement: #selector(UIViewController.dash0_swizzled_viewDidAppear(_:))
        )
        swizzle(
            original: #selector(UIViewController.viewDidDisappear(_:)),
            replacement: #selector(UIViewController.dash0_swizzled_viewDidDisappear(_:))
        )
    }

    private static func swizzle(original: Selector, replacement: Selector) {
        let cls: AnyClass = UIViewController.self
        guard let orig = class_getInstanceMethod(cls, original),
              let repl = class_getInstanceMethod(cls, replacement) else {
            return
        }
        method_exchangeImplementations(orig, repl)
    }
}

extension UIViewController {
    @objc func dash0_swizzled_viewDidAppear(_ animated: Bool) {
        // This calls the ORIGINAL viewDidAppear thanks to the swap.
        self.dash0_swizzled_viewDidAppear(animated)
        let name = Dash0ScreenName.for(self)
        ScreenInstrumentation.shared.handleViewDidAppear(screenName: name)
    }

    @objc func dash0_swizzled_viewDidDisappear(_ animated: Bool) {
        self.dash0_swizzled_viewDidDisappear(animated)
        let name = Dash0ScreenName.for(self)
        ScreenInstrumentation.shared.handleViewDidDisappear(screenName: name)
    }
}

/// Unwraps the screen name from potentially-generic hosting controllers.
/// `UIHostingController<ProductListView>` → `ProductListView`.
enum Dash0ScreenName {
    static func `for`(_ vc: UIViewController) -> String {
        let raw = String(describing: type(of: vc))
        // Strip generic brackets for hosting controllers.
        if let open = raw.firstIndex(of: "<"), let close = raw.lastIndex(of: ">") {
            let inner = raw[raw.index(after: open)..<close]
            let parts = String(inner).split(separator: ".")
            return String(parts.last ?? Substring(inner))
        }
        return raw
    }
}
#endif
