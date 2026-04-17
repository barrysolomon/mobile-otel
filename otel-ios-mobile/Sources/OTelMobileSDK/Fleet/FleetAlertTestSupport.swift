import Foundation

/// Test factories for the fleet-alert pipeline. Test files can't import
/// Foundation directly (Command Line Tools `_Testing_Foundation` overlay
/// is incomplete), so every Foundation-derived helper lives here.
extension FleetAlert {
    /// Build a `FleetAlert` with sensible defaults. `expiresSecondsAhead`
    /// is relative to `now()` so tests can pass positive (future) or
    /// negative (already expired) values to exercise expiry checks.
    public static func makeForTesting(
        alertId: String = "test-alert",
        cascadeChainId: String = "chain-1",
        hop: Int = 0,
        priority: Int = 0,
        actions: [FleetAction] = [],
        expiresSecondsAhead: Double = 3600
    ) -> FleetAlert {
        let fmt = ISO8601DateFormatter()
        fmt.formatOptions = [.withInternetDateTime]
        let expires = Date().addingTimeInterval(expiresSecondsAhead)
        let issued = Date().addingTimeInterval(-60)
        return FleetAlert(
            alertId: alertId,
            cascadeChainId: cascadeChainId,
            hop: hop,
            priority: priority,
            actions: actions,
            expiresAt: fmt.string(from: expires),
            issuedAt: fmt.string(from: issued)
        )
    }

    /// Invalid-expiry alert for negative-path tests.
    public static func makeWithInvalidExpiryForTesting(alertId: String = "bad-date") -> FleetAlert {
        FleetAlert(
            alertId: alertId,
            cascadeChainId: "chain",
            actions: [],
            expiresAt: "not-an-iso-date",
            issuedAt: ISO8601DateFormatter().string(from: Date())
        )
    }
}

extension FleetAlertDeduplicator {
    /// Fresh `UserDefaults`-backed dedup store that tests can throw away
    /// without polluting the app's standard defaults.
    public static func makeEphemeralForTesting() -> FleetAlertDeduplicator {
        let suite = "io.dash0.mobile.test.fleet." + UUID().uuidString
        let defaults = UserDefaults(suiteName: suite) ?? .standard
        return FleetAlertDeduplicator(
            namespace: "io.dash0.mobile.test.fleet_alert_dedup." + UUID().uuidString,
            expirySeconds: 3600,
            defaults: defaults
        )
    }
}
