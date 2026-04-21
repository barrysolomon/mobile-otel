import Foundation
import OpenTelemetryApi
import OTelMobileCore
#if canImport(UIKit)
import UIKit
#endif

/// Auto-instruments app lifecycle transitions.
///
/// Emits OTel log records (plus spans for foreground/background windows) for:
/// - `app.launch` — first install call
/// - `app.foreground` — UIApplication.didBecomeActive
/// - `app.background` — UIApplication.didEnterBackground
/// - `app.will_terminate` — UIApplication.willTerminate (best-effort, iOS may not always deliver)
/// - `app.memory_warning` — UIApplication.didReceiveMemoryWarning
///
/// Usage:
/// ```swift
/// let mobile = try OTelMobile.start(config: config)
/// if let tracer = mobile.tracer, let logger = mobile.logger {
///     LifecycleInstrumentation.shared.install(tracer: tracer, logger: logger)
/// }
/// ```
public final class LifecycleInstrumentation: @unchecked Sendable {
    public static let shared = LifecycleInstrumentation()

    private let lock = NSLock()
    private var installed = false
    private var tracer: Tracer?
    private var logger: Logger?
    private var observers: [NSObjectProtocol] = []
    private var foregroundSpan: Span?
    // Tracks whether we've already handled a foreground/background edge.
    // Needed because we now observe both UIApplication.* and UIScene.*
    // notifications; on non-scene apps both fire, on scene apps only
    // UIScene.* fires, and on multi-scene apps UIScene.didActivate fires
    // once per scene activation. Collapse all of that into a single edge.
    private var foregroundActive = false

    private init() {}

    public func install(tracer: Tracer, logger: Logger) {
        lock.lock()
        if installed {
            lock.unlock()
            return
        }
        installed = true
        self.tracer = tracer
        self.logger = logger
        lock.unlock()

        // IMPORTANT: emit OUTSIDE the lock. `emit()` re-acquires `lock`
        // to read `self.logger`, and NSLock is non-reentrant on Darwin —
        // so calling emit while holding the install lock would deadlock,
        // which silently swallowed `app.launch` and aborted observer
        // registration below. See the comment on `foregroundActive`.
        emit(event: "app.launch")

        #if canImport(UIKit) && (os(iOS) || os(tvOS))
        let nc = NotificationCenter.default
        // Scene-based apps (Info.plist has UIApplicationSceneManifest — which
        // is every modern SwiftUI app and most UIKit apps from iOS 13+) do
        // NOT post UIApplication.didBecomeActiveNotification. Observe both
        // UIApplication and UIScene notifications so lifecycle telemetry
        // flows regardless of app architecture. handleForeground/Background
        // dedupe via `foregroundActive`.
        observers.append(nc.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil, queue: nil
        ) { [weak self] _ in self?.handleForeground() })

        observers.append(nc.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil, queue: nil
        ) { [weak self] _ in self?.handleBackground() })

        observers.append(nc.addObserver(
            forName: UIScene.didActivateNotification,
            object: nil, queue: nil
        ) { [weak self] _ in self?.handleForeground() })

        observers.append(nc.addObserver(
            forName: UIScene.didEnterBackgroundNotification,
            object: nil, queue: nil
        ) { [weak self] _ in self?.handleBackground() })

        observers.append(nc.addObserver(
            forName: UIApplication.willTerminateNotification,
            object: nil, queue: nil
        ) { [weak self] _ in self?.emit(event: "app.will_terminate") })

        observers.append(nc.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification,
            object: nil, queue: nil
        ) { [weak self] _ in self?.emit(event: "app.memory_warning", severity: .warn) })
        #endif
    }

    public func uninstall() {
        lock.lock(); defer { lock.unlock() }
        installed = false
        let nc = NotificationCenter.default
        for o in observers { nc.removeObserver(o) }
        observers.removeAll()
        foregroundSpan?.end()
        foregroundSpan = nil
        foregroundActive = false
    }

    // MARK: - Handlers

    private func handleForeground() {
        lock.lock()
        if foregroundActive {
            lock.unlock()
            return
        }
        foregroundActive = true
        let t = tracer
        lock.unlock()
        emit(event: "app.foreground")
        if let tracer = t {
            // Open a span whose lifetime brackets the foreground session.
            let span = tracer.spanBuilder(spanName: "app.foreground_session")
                .setSpanKind(spanKind: .internal)
                .startSpan()
            lock.lock()
            foregroundSpan?.end()  // end stale span from a missed background
            foregroundSpan = span
            lock.unlock()
        }
    }

    private func handleBackground() {
        lock.lock()
        if !foregroundActive {
            lock.unlock()
            return
        }
        foregroundActive = false
        let span = foregroundSpan
        foregroundSpan = nil
        lock.unlock()
        emit(event: "app.background")
        span?.end()
    }

    private func emit(event: String, severity: Severity = .info) {
        lock.lock()
        let logger = self.logger
        lock.unlock()
        guard let logger = logger else { return }
        logger.logRecordBuilder()
            .setBody(AttributeValue.string(event))
            .setSeverity(severity)
            .setAttributes(["event.name": .string(event)])
            .emit()
    }
}

