/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation

// MARK: - Top-Level Config

public struct DSLConfigV2: Codable, Sendable {
    public let version: Int
    public let bufferConfig: DSLBufferConfig
    public let targeting: DSLTargeting?
    public let workflows: [DSLWorkflow]

    enum CodingKeys: String, CodingKey {
        case version
        case bufferConfig = "buffer_config"
        case targeting
        case workflows
    }
}

public struct DSLBufferConfig: Codable, Sendable {
    public let ramEvents: Int?
    public let diskMb: Int?
    public let retentionHours: Int?
    public let strategy: String?

    enum CodingKeys: String, CodingKey {
        case ramEvents = "ram_events"
        case diskMb = "disk_mb"
        case retentionHours = "retention_hours"
        case strategy
    }
}

public struct DSLTargeting: Codable, Sendable {
    public let platform: String?
    public let appVersionRange: String?
    public let osVersionRange: String?
    public let deviceModels: [String]?
    public let deviceGroup: String?
    public let customAttributes: [String: String]?

    enum CodingKeys: String, CodingKey {
        case platform
        case appVersionRange = "app_version_range"
        case osVersionRange = "os_version_range"
        case deviceModels = "device_models"
        case deviceGroup = "device_group"
        case customAttributes = "custom_attributes"
    }
}

// MARK: - Workflow

public struct DSLWorkflow: Codable, Sendable {
    public let id: String
    public let name: String?
    public let enabled: Bool
    public let priority: Int
    public let initialState: String
    public let states: [DSLWorkflowState]

    enum CodingKeys: String, CodingKey {
        case id, name, enabled, priority
        case initialState = "initial_state"
        case states
    }
}

public struct DSLWorkflowState: Codable, Sendable {
    public let id: String
    public let matchers: [DSLMatcher]
    public let onMatch: DSLMatchAction?
    public let onTimeout: DSLTimeoutAction?

    enum CodingKeys: String, CodingKey {
        case id, matchers
        case onMatch = "on_match"
        case onTimeout = "on_timeout"
    }
}

public struct DSLMatchAction: Codable, Sendable {
    public let actions: [DSLAction]
    public let transitionTo: String?

    enum CodingKeys: String, CodingKey {
        case actions
        case transitionTo = "transition_to"
    }
}

public struct DSLTimeoutAction: Codable, Sendable {
    public let durationMs: Int
    public let actions: [DSLAction]
    public let transitionTo: String?

    enum CodingKeys: String, CodingKey {
        case durationMs = "duration_ms"
        case actions
        case transitionTo = "transition_to"
    }
}

// MARK: - Combine Mode / Flush Scope

public enum DSLCombineMode: String, Codable, Sendable {
    case any
    case all
}

public enum DSLFlushScope: String, Codable, Sendable {
    case session
    case device
}

// MARK: - Matcher (31 types + compound)

public enum DSLMatcher: Codable, Sendable {
    // 21 core matchers (evaluated on-device)
    case eventMatch(eventName: String)
    case logSeverity(minSeverity: String, bodyContains: String?)
    case metricThreshold(metricName: String, op: String, threshold: Double)
    case httpMatch(statusMin: Int?, routeContains: String?, method: String?)
    case crash
    case exceptionPattern(exceptionType: String, messagePattern: String?)
    case uiFreeze(durationMs: Int)
    case slowOperation(operationName: String, thresholdMs: Int)
    case frameDrop(droppedFrames: Int, windowMs: Int)
    case networkLoss(consecutiveFailures: Int?)
    case networkRestored
    case lowMemory(availableMb: Int)
    case batteryDrain(drainRatePercPerMin: Double)
    case thermalThrottle(minLevel: Int)
    case storageLow(availableMb: Int)
    case predictiveRisk(riskType: String, minScore: Double)
    case anr
    case appLifecycle(event: String)
    case resourceSnapshot(metric: String, op: String, threshold: Double)
    case fieldPresence(fieldName: String)
    case fieldAbsence(fieldName: String)
    case timeout(durationMs: Int)
    // Compound
    case compound(combine: DSLCombineMode, children: [DSLMatcher])
    // 7 fleet matchers (server-side only, parsed but not evaluated locally)
    case fleetThreshold
    case fleetRate
    case fleetAbsence
    case fleetCorrelation
    case fleetAnomaly
    case fleetPrediction
    case fleetRootCause
    // 3 backend matchers (server-side only)
    case backendHealth
    case backendDeploy
    case backendCapacity

    enum CodingKeys: String, CodingKey {
        case type, combine, children, config
        case eventName = "event_name"
        case minSeverity = "min_severity"
        case bodyContains = "body_contains"
        case metricName = "metric_name"
        case op = "operator"
        case threshold
        case statusMin = "status_min"
        case routeContains = "route_contains"
        case method
        case exceptionType = "exception_type"
        case messagePattern = "message_pattern"
        case durationMs = "duration_ms"
        case operationName = "operation_name"
        case thresholdMs = "threshold_ms"
        case droppedFrames = "dropped_frames"
        case windowMs = "window_ms"
        case consecutiveFailures = "consecutive_failures"
        case availableMb = "available_mb"
        case drainRatePercPerMin = "drain_rate_perc_per_min"
        case minLevel = "min_level"
        case riskType = "risk_type"
        case minScore = "min_score"
        case event
        case metric
        case fieldName = "field_name"
    }

    /// A field-lookup helper that checks the top-level container first, then
    /// falls back to a nested `"config"` container if present. The Dash0 DSL
    /// schema uses both shapes in practice: control-plane-emitted workflows
    /// (Android's V2 test fixtures) wrap per-matcher fields in `"config"`,
    /// while the plan/spec fixtures keep them at the top level.
    private struct FieldReader {
        let direct: KeyedDecodingContainer<CodingKeys>
        let nested: KeyedDecodingContainer<CodingKeys>?

        func decode<T: Decodable>(_ t: T.Type, forKey key: CodingKeys) throws -> T {
            if direct.contains(key) {
                return try direct.decode(t, forKey: key)
            }
            if let n = nested, n.contains(key) {
                return try n.decode(t, forKey: key)
            }
            // Fall through to direct.decode so we raise a proper DecodingError
            // with the expected codingPath — mirrors stock Codable behaviour.
            return try direct.decode(t, forKey: key)
        }

        func decodeIfPresent<T: Decodable>(_ t: T.Type, forKey key: CodingKeys) throws -> T? {
            if direct.contains(key) {
                return try direct.decodeIfPresent(t, forKey: key)
            }
            if let n = nested, n.contains(key) {
                return try n.decodeIfPresent(t, forKey: key)
            }
            return nil
        }
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(String.self, forKey: .type)
        let nested: KeyedDecodingContainer<CodingKeys>? = try? container.nestedContainer(
            keyedBy: CodingKeys.self, forKey: .config
        )
        let fields = FieldReader(direct: container, nested: nested)

        switch type {
        case "event_match":
            self = .eventMatch(eventName: try fields.decode(String.self, forKey: .eventName))
        case "log_severity":
            self = .logSeverity(
                minSeverity: try fields.decode(String.self, forKey: .minSeverity),
                bodyContains: try fields.decodeIfPresent(String.self, forKey: .bodyContains)
            )
        case "metric_threshold":
            self = .metricThreshold(
                metricName: try fields.decode(String.self, forKey: .metricName),
                op: try fields.decode(String.self, forKey: .op),
                threshold: try fields.decode(Double.self, forKey: .threshold)
            )
        case "http_match":
            self = .httpMatch(
                statusMin: try fields.decodeIfPresent(Int.self, forKey: .statusMin),
                routeContains: try fields.decodeIfPresent(String.self, forKey: .routeContains),
                method: try fields.decodeIfPresent(String.self, forKey: .method)
            )
        case "crash":
            self = .crash
        case "exception_pattern":
            self = .exceptionPattern(
                exceptionType: try fields.decode(String.self, forKey: .exceptionType),
                messagePattern: try fields.decodeIfPresent(String.self, forKey: .messagePattern)
            )
        case "ui_freeze":
            self = .uiFreeze(durationMs: try fields.decode(Int.self, forKey: .durationMs))
        case "slow_operation":
            self = .slowOperation(
                operationName: try fields.decode(String.self, forKey: .operationName),
                thresholdMs: try fields.decode(Int.self, forKey: .thresholdMs)
            )
        case "frame_drop":
            self = .frameDrop(
                droppedFrames: try fields.decode(Int.self, forKey: .droppedFrames),
                windowMs: try fields.decode(Int.self, forKey: .windowMs)
            )
        case "network_loss":
            self = .networkLoss(
                consecutiveFailures: try fields.decodeIfPresent(Int.self, forKey: .consecutiveFailures)
            )
        case "network_restored":
            self = .networkRestored
        case "low_memory":
            self = .lowMemory(availableMb: try fields.decode(Int.self, forKey: .availableMb))
        case "battery_drain":
            self = .batteryDrain(
                drainRatePercPerMin: try fields.decode(Double.self, forKey: .drainRatePercPerMin)
            )
        case "thermal_throttle":
            self = .thermalThrottle(minLevel: try fields.decode(Int.self, forKey: .minLevel))
        case "storage_low":
            self = .storageLow(availableMb: try fields.decode(Int.self, forKey: .availableMb))
        case "predictive_risk":
            self = .predictiveRisk(
                riskType: try fields.decode(String.self, forKey: .riskType),
                minScore: try fields.decode(Double.self, forKey: .minScore)
            )
        case "anr":
            self = .anr
        case "app_lifecycle":
            self = .appLifecycle(event: try fields.decode(String.self, forKey: .event))
        case "resource_snapshot":
            self = .resourceSnapshot(
                metric: try fields.decode(String.self, forKey: .metric),
                op: try fields.decode(String.self, forKey: .op),
                threshold: try fields.decode(Double.self, forKey: .threshold)
            )
        case "field_presence":
            self = .fieldPresence(fieldName: try fields.decode(String.self, forKey: .fieldName))
        case "field_absence":
            self = .fieldAbsence(fieldName: try fields.decode(String.self, forKey: .fieldName))
        case "timeout":
            self = .timeout(durationMs: try fields.decode(Int.self, forKey: .durationMs))
        case "compound":
            self = .compound(
                combine: try fields.decode(DSLCombineMode.self, forKey: .combine),
                children: try fields.decode([DSLMatcher].self, forKey: .children)
            )
        case "fleet_threshold": self = .fleetThreshold
        case "fleet_rate": self = .fleetRate
        case "fleet_absence": self = .fleetAbsence
        case "fleet_correlation": self = .fleetCorrelation
        case "fleet_anomaly": self = .fleetAnomaly
        case "fleet_prediction": self = .fleetPrediction
        case "fleet_root_cause": self = .fleetRootCause
        case "backend_health": self = .backendHealth
        case "backend_deploy": self = .backendDeploy
        case "backend_capacity": self = .backendCapacity
        default:
            throw DecodingError.dataCorrupted(
                .init(codingPath: [CodingKeys.type], debugDescription: "Unknown matcher type: \(type)")
            )
        }
    }

    /// Abbreviated encoder. iOS is a DSL consumer, not an emitter; only the
    /// cases we actually round-trip in tests are implemented. Fleshing the
    /// rest out is straightforward if/when we need it.
    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .crash: try container.encode("crash", forKey: .type)
        case .anr: try container.encode("anr", forKey: .type)
        default: break
        }
    }
}

// MARK: - Action (15 types)

public enum DSLAction: Codable, Sendable {
    // 10 core actions
    case flushBuffer(minutes: Int, scope: DSLFlushScope)
    case recordSession(maxDurationMinutes: Int)
    case emitMetric(metricName: String, metricType: String)
    case createFunnel(funnelName: String, steps: [String])
    case createSankey(sankeyName: String)
    case takeScreenshot(quality: String?, redactText: Bool?)
    case annotate(triggerId: String, reason: String)
    case setSampling(rate: Double, durationMinutes: Int?)
    case adjustBuffer(parameter: String, value: Int, durationMinutes: Int?)
    case sendAlert(severity: String, message: String)
    // 5 fleet actions
    case fleetFlush
    case fleetSetSampling
    case fleetAdjustConfig
    case fleetScreenshot
    case fleetClientCircuitBreak

    enum CodingKeys: String, CodingKey {
        case type, minutes, scope, config
        case maxDurationMinutes = "max_duration_minutes"
        case metricName = "metric_name"
        case metricType = "metric_type"
        case funnelName = "funnel_name"
        case steps
        case sankeyName = "sankey_name"
        case quality
        case redactText = "redact_text"
        case triggerId = "trigger_id"
        case reason, rate
        case durationMinutes = "duration_minutes"
        case parameter, value, severity, message
    }

    private struct FieldReader {
        let direct: KeyedDecodingContainer<CodingKeys>
        let nested: KeyedDecodingContainer<CodingKeys>?

        func decode<T: Decodable>(_ t: T.Type, forKey key: CodingKeys) throws -> T {
            if direct.contains(key) {
                return try direct.decode(t, forKey: key)
            }
            if let n = nested, n.contains(key) {
                return try n.decode(t, forKey: key)
            }
            return try direct.decode(t, forKey: key)
        }

        func decodeIfPresent<T: Decodable>(_ t: T.Type, forKey key: CodingKeys) throws -> T? {
            if direct.contains(key) {
                return try direct.decodeIfPresent(t, forKey: key)
            }
            if let n = nested, n.contains(key) {
                return try n.decodeIfPresent(t, forKey: key)
            }
            return nil
        }
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(String.self, forKey: .type)
        let nested: KeyedDecodingContainer<CodingKeys>? = try? container.nestedContainer(
            keyedBy: CodingKeys.self, forKey: .config
        )
        let fields = FieldReader(direct: container, nested: nested)

        switch type {
        case "flush_buffer":
            // `scope` is optional in Android's V2 examples (defaults to session).
            let scope = try fields.decodeIfPresent(DSLFlushScope.self, forKey: .scope) ?? .session
            self = .flushBuffer(
                minutes: try fields.decode(Int.self, forKey: .minutes),
                scope: scope
            )
        case "record_session":
            self = .recordSession(
                maxDurationMinutes: try fields.decode(Int.self, forKey: .maxDurationMinutes)
            )
        case "emit_metric":
            self = .emitMetric(
                metricName: try fields.decode(String.self, forKey: .metricName),
                metricType: try fields.decode(String.self, forKey: .metricType)
            )
        case "create_funnel":
            self = .createFunnel(
                funnelName: try fields.decode(String.self, forKey: .funnelName),
                steps: try fields.decode([String].self, forKey: .steps)
            )
        case "create_sankey":
            self = .createSankey(sankeyName: try fields.decode(String.self, forKey: .sankeyName))
        case "take_screenshot":
            self = .takeScreenshot(
                quality: try fields.decodeIfPresent(String.self, forKey: .quality),
                redactText: try fields.decodeIfPresent(Bool.self, forKey: .redactText)
            )
        case "annotate":
            self = .annotate(
                triggerId: try fields.decode(String.self, forKey: .triggerId),
                reason: try fields.decode(String.self, forKey: .reason)
            )
        case "set_sampling":
            self = .setSampling(
                rate: try fields.decode(Double.self, forKey: .rate),
                durationMinutes: try fields.decodeIfPresent(Int.self, forKey: .durationMinutes)
            )
        case "adjust_buffer":
            self = .adjustBuffer(
                parameter: try fields.decode(String.self, forKey: .parameter),
                value: try fields.decode(Int.self, forKey: .value),
                durationMinutes: try fields.decodeIfPresent(Int.self, forKey: .durationMinutes)
            )
        case "send_alert":
            self = .sendAlert(
                severity: try fields.decode(String.self, forKey: .severity),
                message: try fields.decode(String.self, forKey: .message)
            )
        case "fleet_flush": self = .fleetFlush
        case "fleet_set_sampling": self = .fleetSetSampling
        case "fleet_adjust_config": self = .fleetAdjustConfig
        case "fleet_screenshot": self = .fleetScreenshot
        case "fleet_client_circuit_break": self = .fleetClientCircuitBreak
        default:
            throw DecodingError.dataCorrupted(
                .init(codingPath: [CodingKeys.type], debugDescription: "Unknown action type: \(type)")
            )
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .flushBuffer(let minutes, let scope):
            try container.encode("flush_buffer", forKey: .type)
            try container.encode(minutes, forKey: .minutes)
            try container.encode(scope, forKey: .scope)
        default: break
        }
    }
}
