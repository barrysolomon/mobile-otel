/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import CryptoKit
import Foundation
import OpenTelemetryApi
import OTelMobileCore
#if canImport(UIKit)
import UIKit
#endif

public final class WireframeInstrumentation: @unchecked Sendable, TouchEventListener {
    public static let shared = WireframeInstrumentation()

    public let config: WireframeConfig

    private let lock = NSLock()
    private var installed = false
    private var logger: Logger?
    private var tracer: Tracer?
    private var sessionProvider: SessionProvider?
    private var eventHub: TouchEventHub?
    private var rateLimiter: RateLimiter
    private var sequenceNumber: Int64 = 0

    // Content-hash dedup state. `lastEmittedHash` is the SHA-256 of the most
    // recently EMITTED full wireframe JSON (not just the most recently
    // captured). `lastEmittedId` is the public `mobile.wireframe.id` we
    // attached to it, so dedup emits a `ui.wireframe.ref` pointing at that
    // id instead of the full payload. Both guarded by `lock`. Mirrors
    // Android's WireframeInstrumentation dedup state.
    private var lastEmittedHash: String?
    private var lastEmittedId: String?

    /// Current wireframe id, or `nil` if no wireframe has been emitted yet
    /// in this session. Companion to Android's
    /// `WireframeInstrumentation.currentWireframeId()`.
    public func currentWireframeId() -> String? {
        lock.lock(); defer { lock.unlock() }
        return lastEmittedId
    }

    #if canImport(UIKit) && (os(iOS) || os(tvOS))
    private var observers: [NSObjectProtocol] = []
    #endif

    public init(config: WireframeConfig = WireframeConfig()) {
        self.config = config
        self.rateLimiter = RateLimiter(maxPerWindow: config.maxCapturesPerMinute)
    }

    public func install(context: InstrumentationContext) {
        lock.lock()
        if installed || !config.enabled {
            lock.unlock()
            return
        }
        installed = true
        self.logger = context.logger
        self.tracer = context.tracer
        self.sessionProvider = context.sessionProvider
        self.eventHub = context.eventHub
        lock.unlock()

        if config.captureOnTap {
            context.eventHub.addListener(id: "wireframe", listener: self)
        }

        #if canImport(UIKit) && (os(iOS) || os(tvOS))
        if config.captureOnScreenView {
            let nc = NotificationCenter.default
            lock.lock()
            observers.append(nc.addObserver(
                forName: UIScene.didActivateNotification,
                object: nil, queue: .main
            ) { [weak self] _ in
                self?.capture(trigger: "screen_view")
            })
            lock.unlock()
        }
        #endif
    }

    public func uninstall() {
        lock.lock(); defer { lock.unlock() }
        installed = false
        #if canImport(UIKit) && (os(iOS) || os(tvOS))
        let nc = NotificationCenter.default
        for o in observers { nc.removeObserver(o) }
        observers.removeAll()
        #endif
        if config.captureOnTap {
            eventHub?.removeListener(id: "wireframe")
        }
        logger = nil
        tracer = nil
        sessionProvider = nil
        eventHub = nil
        rateLimiter.reset()
    }

    // MARK: - TouchEventListener

    public func onTouchEvent(_ event: TouchEventHub.Event) {
        guard config.captureOnTap, event.type == .touchUp else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(100)) { [weak self] in
            self?.capture(trigger: "tap")
        }
    }

    // MARK: - Capture

    public func capture(trigger: String = "manual") {
        guard config.enabled else { return }
        // Trigger-specific gates: policy-match captures default-on but
        // configurable via WireframeConfig.captureOnPolicyMatch.
        if trigger.hasPrefix("policy_") && !config.captureOnPolicyMatch {
            return
        }
        guard rateLimiter.tryAcquire() else { return }

        #if canImport(UIKit) && (os(iOS) || os(tvOS))
        // captureFromKeyWindow walks UIApplication/UIWindow/UIView, which are
        // main-thread-only. The tap / screen_view triggers already arrive on
        // main, but the error / policy-match / public-API paths can call
        // capture() from a background Task.detached (see
        // MobileLogRecordProcessor). Hop to main for those to avoid off-main
        // UIKit access (undefined behavior / host crash).
        if Thread.isMainThread {
            captureFromKeyWindow(trigger: trigger)
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.captureFromKeyWindow(trigger: trigger)
            }
        }
        #endif
    }

    /// Evaluate the consent gate synchronously on the main thread (the capture
    /// path is already on the main thread). Returns `true` when no gate is
    /// configured or the gate authorizes the capture.
    internal func consentAllows(trigger: String, screenName: String?) -> Bool {
        guard let gate = config.shouldCapture else { return true }
        let context = CaptureContext(
            trigger: CaptureTrigger(rawTrigger: trigger),
            kind: .wireframe,
            screenName: screenName
        )
        return gate(context)
    }

    #if canImport(UIKit) && (os(iOS) || os(tvOS))
    private func captureFromKeyWindow(trigger: String) {
        guard let window = Self.findKeyWindow() else { return }

        // Consent gate: consulted synchronously on the main thread immediately
        // before the view-tree walk. If it denies, skip the capture entirely.
        let screenName = Self.topViewControllerName(in: window)
        guard consentAllows(trigger: trigger, screenName: screenName) else { return }

        let node = buildTree(view: window, depth: 0)
        let json = node.toJson()
        emitWireframe(json: json, nodeCount: node.nodeCount(), screenName: screenName, trigger: trigger, sizeBytes: json.count)
    }

    internal func buildTree(view: UIView, depth: Int) -> WireframeNode {
        let frame = view.frame
        let bounds = [Int(frame.origin.x), Int(frame.origin.y),
                      Int(frame.origin.x + frame.width), Int(frame.origin.y + frame.height)]

        let type = Self.viewTypeName(view)

        // Deterministic redaction: a node is redacted when the shared policy
        // says so (secure UIKit field or explicitly-tagged view, incl. SwiftUI
        // `.dash0Redacted()`). Redacted nodes drop ALL text-bearing fields (the
        // WireframeNode initializer enforces this) and we do NOT descend into
        // their subtree, so no sensitive child text can leak either.
        let isRedacted = Dash0RedactionPolicy.shouldRedact(view, redactAllText: false)

        let interactive: Bool? = config.includeInteractionState ? view.isUserInteractionEnabled : nil
        let enabled: Bool? = config.includeInteractionState ? (view.alpha > 0 && !view.isHidden) : nil

        if isRedacted {
            return WireframeNode(
                type: type, bounds: bounds,
                isInteractive: interactive, isEnabled: enabled,
                redacted: true
            )
        }

        let aid: String? = config.includeAccessibilityIdentifiers ? view.accessibilityIdentifier : nil
        let hint: String? = config.includeTextHints ? extractHint(from: view) : nil
        let label: String? = config.includeContentDescription ? view.accessibilityLabel : nil

        if depth >= config.maxDepth {
            return WireframeNode(
                type: type, bounds: bounds,
                accessibilityIdentifier: aid, hint: hint, accessibilityLabel: label,
                isInteractive: interactive, isEnabled: enabled,
                truncated: !view.subviews.isEmpty
            )
        }

        let children = view.subviews
            .filter { !$0.isHidden }
            .map { buildTree(view: $0, depth: depth + 1) }

        return WireframeNode(
            type: type, bounds: bounds,
            accessibilityIdentifier: aid, hint: hint, accessibilityLabel: label,
            isInteractive: interactive, isEnabled: enabled,
            children: children
        )
    }

    private static func viewTypeName(_ view: UIView) -> String {
        String(describing: Swift.type(of: view))
            .components(separatedBy: ".")
            .last ?? "UIView"
    }

    private func extractHint(from view: UIView) -> String? {
        if let tf = view as? UITextField { return tf.placeholder }
        if let tv = view as? UITextView { return tv.text.isEmpty ? nil : nil }
        return nil
    }

    private static func topViewControllerName(in window: UIWindow) -> String {
        var vc = window.rootViewController
        while let presented = vc?.presentedViewController {
            vc = presented
        }
        if let nav = vc as? UINavigationController {
            vc = nav.topViewController
        }
        if let tab = vc as? UITabBarController {
            vc = tab.selectedViewController
        }
        return vc.map { String(describing: Swift.type(of: $0)) } ?? "Unknown"
    }

    internal static func findKeyWindow() -> UIWindow? {
        if #available(iOS 15.0, *) {
            return UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap { $0.windows }
                .first { $0.isKeyWindow }
        }
        return nil
    }
    #endif

    private func emitWireframe(json: String, nodeCount: Int, screenName: String, trigger: String, sizeBytes: Int) {
        let hash = Self.sha256Hex(json)

        // Snapshot mutable state under the lock. If dedup is on and the hash
        // matches the previously emitted wireframe, emit a `ui.wireframe.ref`
        // log carrying the prior id only — no JSON payload.
        lock.lock()
        let logger = self.logger
        let session = self.sessionProvider
        let seq = sequenceNumber
        sequenceNumber += 1
        let dedupHit = config.dedupeByContentHash
            && hash == lastEmittedHash
            && lastEmittedId != nil
        let priorId = lastEmittedId
        if !dedupHit {
            lastEmittedHash = hash
            lastEmittedId = hash
        }
        lock.unlock()

        guard let logger = logger else { return }

        if dedupHit, let priorId = priorId {
            var attrs: [String: AttributeValue] = [
                "mobile.wireframe.trigger": .string(trigger),
                "mobile.wireframe.sequence": .int(Int(seq)),
                "mobile.wireframe.id": .string(priorId),
                "screen.name": .string(screenName),
            ]
            if let sid = session?.sessionId {
                attrs["mobile.session.id"] = .string(sid)
            }
            logger.logRecordBuilder()
                .setBody(AttributeValue.string("ui.wireframe.ref"))
                .setSeverity(.info)
                .setAttributes(attrs)
                .emit()
            return
        }

        // First emit or content changed — send the full payload. The id is
        // the hash itself so consumers can correlate ref logs to the
        // originating wireframe deterministically.
        var attrs: [String: AttributeValue] = [
            "mobile.wireframe.trigger": .string(trigger),
            "mobile.wireframe.sequence": .int(Int(seq)),
            "mobile.wireframe.id": .string(hash),
            "mobile.wireframe.size_bytes": .int(sizeBytes),
            "mobile.wireframe.node_count": .int(nodeCount),
            "mobile.wireframe.data": .string(json),
            "screen.name": .string(screenName),
        ]
        if let sid = session?.sessionId {
            attrs["mobile.session.id"] = .string(sid)
        }

        logger.logRecordBuilder()
            .setBody(AttributeValue.string("ui.wireframe"))
            .setSeverity(.info)
            .setAttributes(attrs)
            .emit()
    }

    private static func sha256Hex(_ input: String) -> String {
        let data = Data(input.utf8)
        let digest = SHA256.hash(data: data)
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    // MARK: - Test seam

    internal func emitForTesting(trigger: String, screenName: String) {
        lock.lock()
        let logger = self.logger
        let session = self.sessionProvider
        let seq = sequenceNumber
        sequenceNumber += 1
        lock.unlock()

        guard let logger = logger else { return }

        var attrs: [String: AttributeValue] = [
            "mobile.wireframe.trigger": .string(trigger),
            "screen.name": .string(screenName),
            "mobile.wireframe.sequence": .int(Int(seq)),
        ]
        if let sid = session?.sessionId {
            attrs["mobile.session.id"] = .string(sid)
        }

        logger.logRecordBuilder()
            .setBody(AttributeValue.string("ui.wireframe"))
            .setSeverity(.info)
            .setAttributes(attrs)
            .emit()
    }

    internal var isInstalled: Bool {
        lock.lock(); defer { lock.unlock() }
        return installed
    }
}
