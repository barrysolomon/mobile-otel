/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation

/// Applies incoming `FleetAlert` payloads to local SDK actions:
/// `flush_buffer` (triggers `flushWindow`), `set_sampling` (installs a
/// time-limited active override), `take_screenshot` (gated — no-op today).
///
/// Android parity: mirrors `FleetAlertHandler.kt`. Validates in this order:
/// 1. **Expiry** — `ISO8601DateFormatter` parse of `expiresAt`; past-expiry
///    alerts rejected.
/// 2. **Dedup** — `FleetAlertDeduplicator.isProcessed(alertId)` short-circuits.
/// 3. **Rate limit** — rolling 1-hour window, max 5 accepted alerts.
/// 4. **Privacy gates** — per-action `PrivacyConfig` bool checks.
public final class FleetAlertHandler: @unchecked Sendable {
    public struct Config: Sendable {
        public var maxAlertsPerWindow: Int
        public var rateLimitWindowSeconds: TimeInterval
        /// When true, `set_sampling` alerts may preempt existing overrides
        /// if incoming priority is strictly greater.
        public var honorSetSampling: Bool
        /// When true, `take_screenshot` is allowed (stubbed today — still
        /// gated so privacy posture doesn't drift if/when it ships).
        public var allowScreenshot: Bool
        /// When true, `flush_buffer` alerts are allowed. Off-by-default
        /// deployments can set false to refuse all fleet flushes.
        public var allowFleetFlush: Bool

        public static let `default` = Config(
            maxAlertsPerWindow: 5,
            rateLimitWindowSeconds: 3600,
            honorSetSampling: true,
            allowScreenshot: false,
            allowFleetFlush: true
        )

        public init(
            maxAlertsPerWindow: Int = 5,
            rateLimitWindowSeconds: TimeInterval = 3600,
            honorSetSampling: Bool = true,
            allowScreenshot: Bool = false,
            allowFleetFlush: Bool = true
        ) {
            self.maxAlertsPerWindow = maxAlertsPerWindow
            self.rateLimitWindowSeconds = rateLimitWindowSeconds
            self.honorSetSampling = honorSetSampling
            self.allowScreenshot = allowScreenshot
            self.allowFleetFlush = allowFleetFlush
        }
    }

    public struct SamplingOverride: Sendable, Equatable {
        public let rate: Double
        public let priority: Int
        public let until: Date
    }

    public typealias FlushWindowClosure = @Sendable (UInt64) -> Void

    private let config: Config
    private let dedup: FleetAlertDeduplicator
    private let flushWindow: FlushWindowClosure
    private let lock = NSLock()
    private var alertTimestamps: [Date] = []
    private var currentOverride: SamplingOverride?

    public init(
        config: Config = .default,
        dedup: FleetAlertDeduplicator = FleetAlertDeduplicator(),
        flushWindow: @escaping FlushWindowClosure
    ) {
        self.config = config
        self.dedup = dedup
        self.flushWindow = flushWindow
    }

    public func handle(_ alert: FleetAlert) -> FleetAlertResult {
        if let reason = validateExpiry(alert) {
            return FleetAlertResult(alertId: alert.alertId, accepted: false, reason: reason)
        }
        if dedup.isProcessed(alert.alertId) {
            return FleetAlertResult(alertId: alert.alertId, accepted: false, reason: "duplicate")
        }
        if let reason = rateLimitCheck() {
            return FleetAlertResult(alertId: alert.alertId, accepted: false, reason: reason)
        }

        var executed: [String] = []
        var skipped: [String] = []

        for action in alert.actions {
            switch action.type {
            case "flush_buffer":
                if !config.allowFleetFlush {
                    skipped.append("flush_buffer:privacy_gate")
                    continue
                }
                let minutes = UInt64(action.config["minutes"].flatMap { Int($0) } ?? 5)
                flushWindow(minutes)
                executed.append("flush_buffer")
            case "set_sampling":
                if !config.honorSetSampling {
                    skipped.append("set_sampling:privacy_gate")
                    continue
                }
                guard let rateStr = action.config["rate"], let rate = Double(rateStr) else {
                    skipped.append("set_sampling:missing_rate")
                    continue
                }
                let durationSeconds = Double(action.config["duration_seconds"] ?? "300") ?? 300
                let candidate = SamplingOverride(
                    rate: rate,
                    priority: alert.priority,
                    until: Date().addingTimeInterval(durationSeconds)
                )
                if let existing = currentOverride, Date() < existing.until,
                   candidate.priority <= existing.priority {
                    skipped.append("set_sampling:lower_priority")
                    continue
                }
                lock.lock()
                currentOverride = candidate
                lock.unlock()
                executed.append("set_sampling")
            case "take_screenshot":
                if !config.allowScreenshot {
                    skipped.append("take_screenshot:privacy_gate")
                    continue
                }
                // Stubbed — iOS screenshot module is gated behind the
                // privacy design. When it ships, wire here.
                skipped.append("take_screenshot:not_implemented")
            default:
                skipped.append("\(action.type):unknown")
            }
        }

        lock.lock()
        alertTimestamps.append(Date())
        lock.unlock()
        dedup.markProcessed(alert.alertId)

        return FleetAlertResult(
            alertId: alert.alertId,
            accepted: true,
            reason: nil,
            actionsExecuted: executed,
            actionsSkipped: skipped
        )
    }

    /// Current sampling override, if any and still valid. Returns nil when
    /// the override has expired (without mutating state — expired
    /// overrides remain until the next `handle(_:)` arrives with a new
    /// priority-wins check).
    public func activeSamplingOverride() -> SamplingOverride? {
        lock.lock(); defer { lock.unlock() }
        guard let ov = currentOverride, Date() < ov.until else { return nil }
        return ov
    }

    // MARK: - Validation

    private func validateExpiry(_ alert: FleetAlert) -> String? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let parsed = formatter.date(from: alert.expiresAt)
            ?? ISO8601DateFormatter().date(from: alert.expiresAt)
        guard let expiresAt = parsed else { return "invalid_expires_at" }
        if expiresAt <= Date() { return "expired" }
        return nil
    }

    private func rateLimitCheck() -> String? {
        lock.lock(); defer { lock.unlock() }
        let cutoff = Date().addingTimeInterval(-config.rateLimitWindowSeconds)
        alertTimestamps.removeAll { $0 < cutoff }
        if alertTimestamps.count >= config.maxAlertsPerWindow {
            return "rate_limited"
        }
        return nil
    }
}
