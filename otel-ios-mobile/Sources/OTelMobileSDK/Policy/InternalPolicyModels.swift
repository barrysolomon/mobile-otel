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

/// Compiled policy configuration (the evaluator's internal model).
public struct PolicyConfig: Sendable, Equatable {
    public let policies: [Policy]
    public init(policies: [Policy]) { self.policies = policies }
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
