import Foundation

// MARK: - Compiled Policy Model
//
// This is the *internal* compiled form that the policy evaluator operates on.
// Port of Android's internal data classes in
// `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt`
// (lines 800-878). The v2 DSL JSON is parsed into this model by `PolicyParser.parseConfigV2`.

/// Result of policy evaluation against a single event.
/// `contextSnapshot` is deferred — we'll add it when we port the evaluator.
public struct PolicyMatchResult: Sendable, Equatable {
    public let policyId: String
    public let flushWindowMinutes: Int

    public init(policyId: String, flushWindowMinutes: Int) {
        self.policyId = policyId
        self.flushWindowMinutes = flushWindowMinutes
    }
}

/// Remote SDK-level control delivered via the `sdk` block at the DSL v2 JSON
/// root (sibling of `workflows`/`version`). The kill switch + global sampling
/// override. See `docs/design/remote-kill-switch.md`.
///
/// - `enabled` — master kill switch. `false` drops all new telemetry at the
///   log/span choke points. Default **true**.
/// - `sampleRate` — global head-sampling fraction, clamped to `[0.0, 1.0]`.
///   Default **1.0**. Applied uniformly to logs and spans.
///
/// Absence of the `sdk` block ⇒ `.default` (no restriction). A malformed field
/// falls back to its individual default; the type never carries an out-of-range
/// rate because the initializer clamps.
public struct SDKRemoteConfig: Sendable, Equatable {
    public let enabled: Bool
    public let sampleRate: Double

    /// Fail-open default: SDK enabled, full sampling. Used when no `sdk` block
    /// is present and as the initial seed for a never-fed `RemoteGate`.
    public static let `default` = SDKRemoteConfig(enabled: true, sampleRate: 1.0)

    /// `sampleRate` is clamped to `[0.0, 1.0]` on construction so downstream
    /// reads never have to re-validate. A NaN rate collapses to the `1.0`
    /// default (treated as "no restriction") rather than poisoning comparisons.
    public init(enabled: Bool = true, sampleRate: Double = 1.0) {
        self.enabled = enabled
        if sampleRate.isNaN {
            self.sampleRate = 1.0
        } else {
            self.sampleRate = Swift.min(1.0, Swift.max(0.0, sampleRate))
        }
    }
}

/// Compiled policy configuration (the evaluator's internal model).
public struct PolicyConfig: Sendable, Equatable {
    public let policies: [Policy]

    /// Optional SDK-level remote control parsed from the root `sdk` block.
    /// `nil` when the block is absent — callers treat `nil` as
    /// `SDKRemoteConfig.default` (no restriction).
    public let sdkConfig: SDKRemoteConfig?

    public init(policies: [Policy], sdkConfig: SDKRemoteConfig? = nil) {
        self.policies = policies
        self.sdkConfig = sdkConfig
    }
}

public struct Policy: Sendable, Equatable {
    public let id: String
    public let enabled: Bool
    public let match: Match
    public let actions: Actions

    public init(id: String, enabled: Bool, match: Match, actions: Actions) {
        self.id = id
        self.enabled = enabled
        self.match = match
        self.actions = actions
    }
}

public struct Match: Sendable, Equatable {
    public let logicalOperator: String  // "and" | "or"
    public let attributes: [String: Condition]

    public init(logicalOperator: String, attributes: [String: Condition]) {
        self.logicalOperator = logicalOperator
        self.attributes = attributes
    }
}

/// Predicate applied to a single attribute. All fields optional — which field is non-nil
/// determines the operator. Android uses kotlin data class default-nil fields; this struct mirrors.
public struct Condition: Sendable, Equatable {
    public let equals: String?
    public let notEquals: String?
    public let gt: Double?
    public let lt: Double?
    public let gte: Double?
    public let lte: Double?
    public let contains: String?
    public let regex: String?

    public init(
        equals: String? = nil,
        notEquals: String? = nil,
        gt: Double? = nil,
        lt: Double? = nil,
        gte: Double? = nil,
        lte: Double? = nil,
        contains: String? = nil,
        regex: String? = nil
    ) {
        self.equals = equals
        self.notEquals = notEquals
        self.gt = gt
        self.lt = lt
        self.gte = gte
        self.lte = lte
        self.contains = contains
        self.regex = regex
    }
}

public struct Actions: Sendable, Equatable {
    public let flushWindowMinutes: Int
    public init(flushWindowMinutes: Int) { self.flushWindowMinutes = flushWindowMinutes }
}
