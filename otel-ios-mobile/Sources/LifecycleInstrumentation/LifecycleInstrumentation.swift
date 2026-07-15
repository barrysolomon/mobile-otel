/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
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
/// - `app.foreground` — UIApplication.didBecomeActive (or synthesized if installed
///   while app is already active; tagged via `app.foreground.type` attribute)
/// - `app.background` — UIApplication.didEnterBackground
/// - `app.will_terminate` — UIApplication.willTerminate (best-effort)
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

#if canImport(UIKit) && (os(iOS) || os(tvOS))
    typealias ApplicationStateProvider = @Sendable () -> UIApplication.State
    private let applicationStateProvider: ApplicationStateProvider
#endif

    // Injected so tests can use a private NotificationCenter per instance,
    // preventing cross-test notification pollution when tests run concurrently.
    private let notificationCenter: NotificationCenter

    private let lock = NSLock()
    private var installed = false
    private var tracer: Tracer?
    private var logger: Logger?
    private var observers: [NSObjectProtocol] = []
    private var foregroundSpan: Span?
    // Tracks whether we've already handled a foreground/background edge.
    // Needed because we observe both UIApplication.* and UIScene.* notifications,
    // AND the late-init synthesis path; this flag dedups across all of them.
    private var foregroundActive = false

    private init() {
#if canImport(UIKit) && (os(iOS) || os(tvOS))
        // Production callers always use this default. The closure dispatches
        // to main inside install() before invoking it, so reading
        // UIApplication.shared.applicationState here is main-thread-safe.
        self.applicationStateProvider = { @Sendable in
            UIApplication.shared.applicationState
        }
#endif
        self.notificationCenter = .default
    }

#if canImport(UIKit) && (os(iOS) || os(tvOS))
    /// Test-only init. Internal so it's accessible from `@testable import`
    /// in test targets but not from external consumers.
    ///
    /// `notificationCenter` defaults to `.default` so existing call-sites
    /// without the argument are unaffected. Pass a fresh `NotificationCenter()`
    /// in tests to prevent cross-test notification pollution when Swift Testing
    /// runs cases concurrently.
    internal init(
        applicationStateProvider: @escaping ApplicationStateProvider,
        notificationCenter: NotificationCenter = .default
    ) {
        self.applicationStateProvider = applicationStateProvider
        self.notificationCenter = notificationCenter
    }
#endif

    public func install(tracer: Tracer?, logger: Logger) {
        lock.lock()
        if installed {
            lock.unlock()
            return
        }
        installed = true
        self.tracer = tracer
        self.logger = logger
        lock.unlock()

        // IMPORTANT: emit OUTSIDE the lock. emit() re-acquires the lock to
        // read self.logger; NSLock is non-reentrant on Darwin.
        emit(event: "app.launch")

        #if canImport(UIKit) && (os(iOS) || os(tvOS))
        let nc = self.notificationCenter
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

        // Late-init synthesis: NotificationCenter has no at-attach replay,
        // so if the app is already foregrounded when install() runs (RN
        // useEffect case, or any deferred init), we must synthesize the
        // initial foreground event. Dispatched to main because
        // UIApplication state must be read on the main thread.
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            if self.applicationStateProvider() == .active {
                self.handleForeground(lateInstall: true)
            }
        }
        #endif
    }

    public func uninstall() {
        lock.lock(); defer { lock.unlock() }
        installed = false
        let nc = self.notificationCenter
        for o in observers { nc.removeObserver(o) }
        observers.removeAll()
        foregroundSpan?.end()
        foregroundSpan = nil
        foregroundActive = false
    }

    // MARK: - Handlers

    private func handleForeground(lateInstall: Bool = false) {
        lock.lock()
        if foregroundActive {
            lock.unlock()
            return
        }
        foregroundActive = true
        let t = tracer
        lock.unlock()
        emit(
            event: "app.foreground",
            attributes: ["app.foreground.type": .string(lateInstall ? "instrumentation_late" : "natural")]
        )
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

    private func emit(event: String, severity: Severity = .info, attributes: [String: AttributeValue] = [:]) {
        lock.lock()
        let logger = self.logger
        lock.unlock()
        guard let logger = logger else { return }
        var attrs: [String: AttributeValue] = ["event.name": .string(event)]
        for (k, v) in attributes { attrs[k] = v }
        logger.logRecordBuilder()
            .setBody(AttributeValue.string(event))
            .setSeverity(severity)
            .setAttributes(attrs)
            .emit()
    }
}
