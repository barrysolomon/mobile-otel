import Foundation

/// Parses DSL v2 JSON into the compiled internal `PolicyConfig` model.
///
/// Port of Android's `PolicyEvaluator.parseConfigV2` (and helpers) from
/// `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt`
/// (lines 492-796). Uses `JSONSerialization` rather than Codable to mirror Android's
/// forgiving `org.json` semantics (default values for missing keys, graceful degradation).
public enum PolicyParser {
    // Constants — match Android's values.
    static let maxPolicies = 50              // MAX_POLICIES
    static let maxConditionsPerPolicy = 20   // MAX_CONDITIONS_PER_POLICY
    static let minFlushWindowMinutes = 1     // MIN_FLUSH_WINDOW_MINUTES
    static let maxFlushWindowMinutes = 60    // MAX_FLUSH_WINDOW_MINUTES
    static let defaultFlushMinutes = 2

    /// Parse v2 DSL JSON → compiled `PolicyConfig`.
    /// Returns `nil` if `version != 2` or parsing fails.
    public static func parseConfigV2(jsonString: String) -> PolicyConfig? {
        guard let data = jsonString.data(using: .utf8) else { return nil }
        do {
            let parsed = try JSONSerialization.jsonObject(with: data, options: [])
            guard let root = parsed as? [String: Any] else { return nil }

            let version = optInt(root, "version", default: 1)
            if version != 2 { return nil }

            // Root `sdk` block — remote kill switch + global sampling.
            // Absent ⇒ nil (caller treats as "no restriction"); malformed
            // fields fall back to per-field defaults; never throws.
            let sdkConfig = parseSDKConfig(root)

            guard let workflowsArray = root["workflows"] as? [Any] else {
                return PolicyConfig(policies: [], sdkConfig: sdkConfig)
            }

            var policies: [Policy] = []
            let workflowCount = min(workflowsArray.count, maxPolicies)

            for i in 0..<workflowCount {
                guard let workflow = workflowsArray[i] as? [String: Any] else { continue }

                let workflowId = optString(workflow, "id", default: "workflow-\(i)")
                let enabled = optBool(workflow, "enabled", default: true)
                guard let states = workflow["states"] as? [Any] else { continue }

                for s in 0..<states.count {
                    guard let state = states[s] as? [String: Any] else { continue }
                    let stateId = optString(state, "id", default: "state-\(s)")
                    guard let matchers = state["matchers"] as? [Any] else { continue }
                    guard let onMatch = state["on_match"] as? [String: Any] else { continue }
                    guard let actions = onMatch["actions"] as? [Any] else { continue }

                    let flushMinutes = extractFlushMinutesV2(actions)

                    for m in 0..<matchers.count {
                        guard let matcher = matchers[m] as? [String: Any] else { continue }
                        if let match = matcherToMatch(matcher) {
                            let policyId: String
                            if matchers.count == 1 && states.count == 1 {
                                policyId = workflowId
                            } else {
                                policyId = "\(workflowId)/\(stateId)/\(m)"
                            }
                            policies.append(Policy(
                                id: policyId,
                                enabled: enabled,
                                match: match,
                                actions: Actions(flushWindowMinutes: flushMinutes)
                            ))
                        }
                    }
                }
            }

            return PolicyConfig(policies: policies, sdkConfig: sdkConfig)
        } catch {
            print("⚠️ PolicyParser: Failed to parse v2 config: \(error)")
            return nil
        }
    }

    // MARK: - Private helpers

    /// Parse the root `sdk` block into `SDKRemoteConfig`.
    ///
    /// - Absent block ⇒ `nil` (caller treats as "no restriction").
    /// - Present but malformed (`sdk` not an object) ⇒ `nil` (defaults apply).
    /// - Present with malformed individual fields ⇒ that field falls back to
    ///   its default (`enabled = true`, `sample_rate = 1.0`).
    /// - `sample_rate` out of `[0,1]` ⇒ clamped by `SDKRemoteConfig.init`.
    ///
    /// Never throws; the `optBool`/`optDouble` accessors are forgiving by
    /// design (org.json-style coercion).
    private static func parseSDKConfig(_ root: [String: Any]) -> SDKRemoteConfig? {
        guard let sdk = root["sdk"] as? [String: Any] else { return nil }
        let enabled = optBool(sdk, "enabled", default: true)
        let sampleRate = optDouble(sdk, "sample_rate", default: 1.0)
        return SDKRemoteConfig(enabled: enabled, sampleRate: sampleRate)
    }

    /// Map a v2 matcher type to the internal `Match` model.
    /// Returns `nil` for `timeout` and `field_absence` (state-machine constructs
    /// that can't be expressed as a positive flush trigger) and when no
    /// attributes end up populated.
    private static func matcherToMatch(_ matcher: [String: Any]) -> Match? {
        let type = optString(matcher, "type", default: "")
        let config = (matcher["config"] as? [String: Any]) ?? [:]
        let whereClause = matcher["where"] as? [Any]

        var attributes: [String: Condition] = [:]

        switch type {
        case "crash":
            attributes["event.name"] = Condition(equals: "app.crash")
        case "ui_freeze":
            attributes["event.name"] = Condition(equals: "ui.freeze")
            let durationMs = optDouble(config, "duration_ms", default: 0.0)
            if durationMs > 0 { attributes["duration_ms"] = Condition(gt: durationMs) }
        case "event_match":
            let eventName = optString(config, "event_name", default: "")
            if !eventName.isEmpty {
                attributes["event.name"] = Condition(equals: eventName)
            }
        case "log_severity":
            let minSeverity = optString(config, "min_severity", default: "")
            if !minSeverity.isEmpty {
                let severityLevel = severityToLevel(minSeverity)
                if severityLevel > 0 {
                    attributes["severity_number"] = Condition(gte: Double(severityLevel))
                } else {
                    attributes["severity"] = Condition(equals: minSeverity)
                }
            }
            let bodyContains = optString(config, "body_contains", default: "")
            if !bodyContains.isEmpty {
                attributes["body"] = Condition(contains: bodyContains)
            }
        case "http_match":
            attributes["event.name"] = Condition(equals: "http.error")
            let statusMin = optInt(config, "status_min", default: 0)
            if statusMin > 0 {
                attributes["http.status_code"] = Condition(gte: Double(statusMin))
            }
        case "exception_pattern":
            attributes["event.name"] = Condition(equals: "app.crash")
            let exType = optString(config, "exception_type", default: "")
            if !exType.isEmpty {
                attributes["exception.type"] = Condition(contains: exType)
            }
            let msgPattern = optString(config, "message_pattern", default: "")
            if !msgPattern.isEmpty {
                attributes["exception.message"] = Condition(regex: msgPattern)
            }
        case "metric_threshold":
            let metricName = optString(config, "metric_name", default: "")
            if !metricName.isEmpty {
                attributes["event.name"] = Condition(equals: metricName)
            }
            let op = optString(config, "operator", default: "gt")
            if let threshold = optDoubleOrNil(config, "threshold") {
                switch op {
                case "gt":  attributes["value"] = Condition(gt: threshold)
                case "lt":  attributes["value"] = Condition(lt: threshold)
                case "gte": attributes["value"] = Condition(gte: threshold)
                case "lte": attributes["value"] = Condition(lte: threshold)
                default:    attributes["value"] = Condition(gt: threshold)
                }
            }
        case "slow_operation":
            let opName = optString(config, "operation_name", default: "")
            if !opName.isEmpty {
                attributes["event.name"] = Condition(equals: opName)
            }
            let thresholdMs = optDouble(config, "threshold_ms", default: 0.0)
            if thresholdMs > 0 {
                attributes["duration_ms"] = Condition(gt: thresholdMs)
            }
        case "frame_drop":
            attributes["event.name"] = Condition(equals: "ui.jank")
            let dropped = optDouble(config, "dropped_frames", default: 0.0)
            if dropped > 0 {
                attributes["dropped_frames"] = Condition(gt: dropped)
            }
        case "network_loss":
            attributes["event.name"] = Condition(equals: "network.loss")
        case "network_restored":
            attributes["event.name"] = Condition(equals: "network.restored")
        case "slow_request":
            attributes["event.name"] = Condition(equals: "http.request")
            let thresholdMs = optDouble(config, "threshold_ms", default: 0.0)
            if thresholdMs > 0 {
                attributes["duration_ms"] = Condition(gt: thresholdMs)
            }
        case "low_memory":
            attributes["event.name"] = Condition(equals: "device.low_memory")
            let availMb = optDouble(config, "available_mb", default: 0.0)
            if availMb > 0 {
                attributes["available_mb"] = Condition(lt: availMb)
            }
        case "battery_drain":
            attributes["event.name"] = Condition(equals: "device.battery_drain")
            let rate = optDouble(config, "drain_rate_perc_per_min", default: 0.0)
            if rate > 0 {
                attributes["drain_rate"] = Condition(gt: rate)
            }
        case "thermal_throttle":
            attributes["event.name"] = Condition(equals: "device.thermal_throttle")
        case "storage_low":
            attributes["event.name"] = Condition(equals: "device.storage_low")
            let availMb = optDouble(config, "available_mb", default: 0.0)
            if availMb > 0 {
                attributes["available_mb"] = Condition(lt: availMb)
            }
        case "predictive_risk":
            attributes["event.name"] = Condition(equals: "prediction.high_risk_alert")
            let minScore = optDouble(config, "min_score", default: 0.0)
            if minScore > 0 {
                attributes["risk_score"] = Condition(gte: minScore)
            }
        case "anr":
            attributes["event.name"] = Condition(equals: "app.anr")
        case "app_lifecycle":
            let event = optString(config, "event", default: "")
            attributes["event.name"] = Condition(equals: event.isEmpty ? "app.lifecycle" : event)
        case "resource_snapshot":
            let metricName = optString(config, "metric_name", default: "")
            if !metricName.isEmpty {
                attributes["event.name"] = Condition(equals: metricName)
            } else {
                attributes["event.name"] = Condition(equals: "resource.snapshot")
            }
        case "field_presence":
            let field = optString(config, "field", default: "")
            if !field.isEmpty {
                attributes[field] = Condition(regex: ".+")
            }
        case "field_absence":
            // Field absence can't be expressed as a positive match — skip.
            return nil
        case "timeout":
            // State-machine transition, not a flush trigger.
            return nil
        default:
            print("⚠️ PolicyParser: Unknown v2 matcher type: \(type), using as event name")
            attributes["event.name"] = Condition(equals: type)
        }

        // Apply where-clause predicates.
        if let whereArray = whereClause {
            let count = min(whereArray.count, maxConditionsPerPolicy)
            for w in 0..<count {
                guard let predicate = whereArray[w] as? [String: Any] else { continue }
                let attr = optString(predicate, "attr", default: "")
                let op = optString(predicate, "op", default: "==")
                let value = predicate["value"]
                if !attr.isEmpty, let value = value, !(value is NSNull) {
                    attributes[attr] = predicateToCondition(op: op, value: value)
                }
            }
        }

        if attributes.isEmpty { return nil }

        return Match(logicalOperator: "and", attributes: attributes)
    }

    /// Map OTel severity name to numeric level for gte comparison.
    private static func severityToLevel(_ name: String) -> Int {
        switch name.uppercased() {
        case "TRACE": return 1
        case "DEBUG": return 5
        case "INFO":  return 9
        case "WARN":  return 13
        case "ERROR": return 17
        case "FATAL": return 21
        default:      return 0
        }
    }

    /// Convert a where-clause predicate `{op, value}` into a `Condition`.
    private static func predicateToCondition(op: String, value: Any) -> Condition {
        let numValue: Double? = {
            if let n = value as? NSNumber { return n.doubleValue }
            if let d = value as? Double   { return d }
            if let i = value as? Int      { return Double(i) }
            return nil
        }()
        let strValue = stringifyValue(value)

        switch op {
        case "==", "equals":          return Condition(equals: strValue)
        case "!=", "not_equals":      return Condition(notEquals: strValue)
        case ">",  "gt":              return Condition(gt: numValue)
        case "<",  "lt":              return Condition(lt: numValue)
        case ">=", "gte":             return Condition(gte: numValue)
        case "<=", "lte":             return Condition(lte: numValue)
        case "contains":              return Condition(contains: strValue)
        case "regex":                 return Condition(regex: strValue)
        default:                      return Condition(equals: strValue)
        }
    }

    /// Extract `flush_buffer` minutes from a v2 actions array. Default 2 min,
    /// clamped to [MIN_FLUSH_WINDOW_MINUTES, MAX_FLUSH_WINDOW_MINUTES].
    private static func extractFlushMinutesV2(_ actions: [Any]) -> Int {
        for a in 0..<actions.count {
            guard let action = actions[a] as? [String: Any] else { continue }
            let type = optString(action, "type", default: "")
            if type == "flush_buffer" {
                guard let actionConfig = action["config"] as? [String: Any] else { continue }
                let minutes = optInt(actionConfig, "minutes", default: defaultFlushMinutes)
                return clamp(minutes, minFlushWindowMinutes, maxFlushWindowMinutes)
            }
        }
        return defaultFlushMinutes
    }

    // MARK: - Tiny JSON accessors (org.json-style optX helpers)

    private static func optString(_ obj: [String: Any], _ key: String, default defaultValue: String) -> String {
        if let v = obj[key] {
            if let s = v as? String { return s }
            if v is NSNull { return defaultValue }
            // Mirror org.json: coerce non-string scalars via description.
            if let n = v as? NSNumber { return n.stringValue }
            if let b = v as? Bool { return b ? "true" : "false" }
            return String(describing: v)
        }
        return defaultValue
    }

    private static func optInt(_ obj: [String: Any], _ key: String, default defaultValue: Int) -> Int {
        if let n = obj[key] as? NSNumber { return n.intValue }
        if let i = obj[key] as? Int { return i }
        if let d = obj[key] as? Double { return Int(d) }
        if let s = obj[key] as? String, let i = Int(s) { return i }
        return defaultValue
    }

    private static func optBool(_ obj: [String: Any], _ key: String, default defaultValue: Bool) -> Bool {
        // Mirror org.json `JSONObject.optBoolean`: only an actual boolean or a
        // "true"/"false" string is coerced — a numeric value (e.g. `0`/`1`) is
        // NOT a boolean and falls through to the default. JSONSerialization
        // bridges every JSON number AND every JSON boolean to `NSNumber`, and a
        // boolean NSNumber satisfies `as? Bool`. We therefore must distinguish a
        // genuinely-boolean NSNumber (CFBoolean) from a numeric one, otherwise
        // `{"enabled": 0}` would wrongly bridge to `false` and disable the SDK
        // while Android keeps the default.
        if let n = obj[key] as? NSNumber {
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return n.boolValue }
            // Numeric NSNumber — not a boolean per org.json. Fall through.
        } else if let b = obj[key] as? Bool {
            // Non-NSNumber path (e.g. a raw Swift Bool): treat as boolean.
            return b
        }
        if let s = obj[key] as? String {
            let lower = s.lowercased()
            if lower == "true" { return true }
            if lower == "false" { return false }
        }
        return defaultValue
    }

    private static func optDouble(_ obj: [String: Any], _ key: String, default defaultValue: Double) -> Double {
        if let n = obj[key] as? NSNumber { return n.doubleValue }
        if let d = obj[key] as? Double { return d }
        if let i = obj[key] as? Int { return Double(i) }
        if let s = obj[key] as? String, let d = Double(s) { return d }
        return defaultValue
    }

    /// Android uses `optDouble(key, NaN)` then tests `!isNaN`. We model that as nil-return.
    private static func optDoubleOrNil(_ obj: [String: Any], _ key: String) -> Double? {
        guard let v = obj[key] else { return nil }
        if v is NSNull { return nil }
        if let n = v as? NSNumber { return n.doubleValue }
        if let d = v as? Double { return d }
        if let i = v as? Int { return Double(i) }
        if let s = v as? String, let d = Double(s) { return d }
        return nil
    }

    private static func stringifyValue(_ value: Any) -> String {
        if let s = value as? String { return s }
        if let n = value as? NSNumber {
            // Preserve int/double formatting the way org.json toString does.
            if CFNumberIsFloatType(n) {
                return n.stringValue
            }
            return n.stringValue
        }
        if let b = value as? Bool { return b ? "true" : "false" }
        return String(describing: value)
    }

    private static func clamp(_ value: Int, _ low: Int, _ high: Int) -> Int {
        return min(max(value, low), high)
    }
}
