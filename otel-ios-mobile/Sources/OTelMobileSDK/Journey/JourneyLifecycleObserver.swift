// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

#if canImport(UIKit) && (os(iOS) || os(tvOS))
import Foundation
import UIKit

/// Bridges `UIApplication`/`UIScene` lifecycle notifications to
/// [JourneyTracker] so open journeys close cleanly on background and
/// resume via cross-trace links on foreground.
///
/// Mirrors Android's `JourneyLifecycleObserver` (which observes
/// `ProcessLifecycleOwner` for the same purpose). Wired by
/// `OTelMobile.start` after the tracker is constructed; observers are
/// unregistered in `OTelMobile.stop`.
///
/// We listen to both `UIApplication.didEnterBackground` and
/// `UIScene.didEnterBackground` so SwiftUI scene-based apps get coverage
/// alongside legacy UIApplication-based apps. Same pattern used by
/// `LifecycleInstrumentation` and the auto-flush hook elsewhere in
/// `OTelMobileSDK`.
public final class JourneyLifecycleObserver: @unchecked Sendable {

    private let tracker: JourneyTracker
    private var observers: [NSObjectProtocol] = []

    public init(tracker: JourneyTracker) {
        self.tracker = tracker
    }

    /// Registers `UIApplication`/`UIScene` notification observers. Must be
    /// called on the main thread (NotificationCenter delivery serializes
    /// on the queue passed here, which is `nil` = original posting queue —
    /// typically main for `UIApplication` lifecycle events).
    public func register() {
        let nc = NotificationCenter.default
        observers.append(nc.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil, queue: nil
        ) { [weak self] _ in self?.tracker.onBackground() })
        observers.append(nc.addObserver(
            forName: UIScene.didEnterBackgroundNotification,
            object: nil, queue: nil
        ) { [weak self] _ in self?.tracker.onBackground() })

        observers.append(nc.addObserver(
            forName: UIApplication.willEnterForegroundNotification,
            object: nil, queue: nil
        ) { [weak self] _ in self?.tracker.onForeground() })
        observers.append(nc.addObserver(
            forName: UIScene.willEnterForegroundNotification,
            object: nil, queue: nil
        ) { [weak self] _ in self?.tracker.onForeground() })
    }

    /// Removes all observers. Safe to call multiple times.
    public func unregister() {
        let nc = NotificationCenter.default
        for o in observers { nc.removeObserver(o) }
        observers.removeAll()
    }

    deinit {
        unregister()
    }
}
#endif
