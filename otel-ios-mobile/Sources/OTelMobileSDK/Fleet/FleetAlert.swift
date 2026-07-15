/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation

/// Fleet-scoped alert emitted by the control plane and consumed by the SDK
/// to trigger local actions (flush, sampling adjustment, screenshot, etc.).
///
/// Android parity: mirrors `io.opentelemetry.android.mobile.fleet.FleetAlert`
/// and its kotlinx.serialization `@Serializable` contract. JSON shape is
/// identical so a single control-plane payload is valid for both platforms.
public struct FleetAlert: Codable, Sendable, Equatable {
    public enum `Type`: String, Codable, Sendable {
        case fleetAlert = "fleet_alert"
    }

    public let type: `Type`
    /// Dedup key — one alertId is processed at most once per SDK install
    /// within the `FleetAlertDeduplicator.expirySeconds` window.
    public let alertId: String
    /// Cascade-chain identity; used for propagation loops across the fleet.
    public let cascadeChainId: String
    /// Propagation depth — for loop detection.
    public let hop: Int
    /// Priority — higher wins when multiple alerts would set conflicting
    /// active overrides (e.g. sampling).
    public let priority: Int
    public let sourceTrigger: String
    public let sourceCohort: String
    public let sourceDeviceCount: Int
    public let actions: [FleetAction]
    /// ISO 8601 timestamp. Alerts past their expiry are dropped without
    /// executing any actions.
    public let expiresAt: String
    /// HMAC signature placeholder. Present but not verified in this
    /// port (matches the current Android behaviour).
    public let signature: String
    public let issuedAt: String
    public let truncated: Bool?
    public let totalActions: Int?

    public init(
        type: `Type` = .fleetAlert,
        alertId: String,
        cascadeChainId: String,
        hop: Int = 0,
        priority: Int = 0,
        sourceTrigger: String = "",
        sourceCohort: String = "",
        sourceDeviceCount: Int = 0,
        actions: [FleetAction] = [],
        expiresAt: String,
        signature: String = "",
        issuedAt: String,
        truncated: Bool? = nil,
        totalActions: Int? = nil
    ) {
        self.type = type
        self.alertId = alertId
        self.cascadeChainId = cascadeChainId
        self.hop = hop
        self.priority = priority
        self.sourceTrigger = sourceTrigger
        self.sourceCohort = sourceCohort
        self.sourceDeviceCount = sourceDeviceCount
        self.actions = actions
        self.expiresAt = expiresAt
        self.signature = signature
        self.issuedAt = issuedAt
        self.truncated = truncated
        self.totalActions = totalActions
    }
}

/// Individual action carried by a `FleetAlert`. The `config` dictionary
/// carries action-specific parameters as a loose map — intentional so the
/// schema can evolve without breaking deserialization of historical
/// payloads. Values are decoded as strings from the over-the-wire JSON.
public struct FleetAction: Codable, Sendable, Equatable {
    public let type: String
    public let config: [String: String]

    public init(type: String, config: [String: String] = [:]) {
        self.type = type
        self.config = config
    }
}

/// Result of `FleetAlertHandler.handle(...)`. Reflects what was and wasn't
/// executed plus a single `reason` when the whole alert was skipped.
public struct FleetAlertResult: Sendable, Equatable {
    public let alertId: String
    public let accepted: Bool
    public let reason: String?
    public let actionsExecuted: [String]
    public let actionsSkipped: [String]

    public init(
        alertId: String,
        accepted: Bool,
        reason: String? = nil,
        actionsExecuted: [String] = [],
        actionsSkipped: [String] = []
    ) {
        self.alertId = alertId
        self.accepted = accepted
        self.reason = reason
        self.actionsExecuted = actionsExecuted
        self.actionsSkipped = actionsSkipped
    }
}
