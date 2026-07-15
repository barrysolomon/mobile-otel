/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation

/// Tracks which `FleetAlert.alertId` values have already been processed by
/// this SDK install, within a sliding expiry window. Backed by
/// `UserDefaults` so dedup survives process death.
///
/// Android parity: mirrors `SharedPreferences`-backed
/// `FleetAlertDeduplicator`. The iOS equivalent (`UserDefaults`) is
/// always private and doesn't need the `MODE_PRIVATE` flag.
public final class FleetAlertDeduplicator: @unchecked Sendable {
    private let defaults: UserDefaults
    private let namespace: String
    private let expirySeconds: TimeInterval
    private let lock = NSLock()

    /// - Parameters:
    ///   - namespace: UserDefaults key prefix. Dedup entries are written as
    ///     `"<namespace>.<alertId>"`.
    ///   - expirySeconds: how long an alertId stays in the dedup store
    ///     before `cleanup()` evicts it. Default 1 hour, matching Android.
    ///   - defaults: inject for testing; defaults to `.standard`.
    public init(
        namespace: String = "io.dash0.mobile.fleet_alert_dedup",
        expirySeconds: TimeInterval = 3600,
        defaults: UserDefaults = .standard
    ) {
        self.namespace = namespace
        self.expirySeconds = expirySeconds
        self.defaults = defaults
    }

    public func isProcessed(_ alertId: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return defaults.object(forKey: key(for: alertId)) != nil
    }

    public func markProcessed(_ alertId: String) {
        lock.lock()
        defaults.set(Date().timeIntervalSince1970, forKey: key(for: alertId))
        lock.unlock()
        cleanup()
    }

    /// Evict entries older than `expirySeconds`. Cheap — called after every
    /// `markProcessed` so the store never grows unbounded in typical use.
    public func cleanup() {
        lock.lock(); defer { lock.unlock() }
        let cutoff = Date().timeIntervalSince1970 - expirySeconds
        let prefix = namespace + "."
        for (key, value) in defaults.dictionaryRepresentation() {
            guard key.hasPrefix(prefix), let timestamp = value as? TimeInterval else { continue }
            if timestamp < cutoff {
                defaults.removeObject(forKey: key)
            }
        }
    }

    /// Wipe all dedup entries for this namespace. Primarily for tests.
    public func reset() {
        lock.lock(); defer { lock.unlock() }
        let prefix = namespace + "."
        for key in defaults.dictionaryRepresentation().keys where key.hasPrefix(prefix) {
            defaults.removeObject(forKey: key)
        }
    }

    private func key(for alertId: String) -> String {
        "\(namespace).\(alertId)"
    }
}
