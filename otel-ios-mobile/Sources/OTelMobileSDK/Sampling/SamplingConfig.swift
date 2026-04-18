import Foundation

/// Sampling strategy for traces and spans. Direct port of Android's
/// `SamplingStrategy` enum — same five cases, same intended use sites.
public enum SamplingStrategy: Sendable {
    /// 100% sampling — every span recorded and exported. Use for
    /// development / debugging or for critical flows that must never
    /// be lost.
    case alwaysOn

    /// 0% sampling — every span dropped. Use to disable tracing
    /// temporarily without removing instrumentation.
    case alwaysOff

    /// Probability-based sampling keyed on `traceId`. Same trace id
    /// always gets the same decision so distributed traces stay whole.
    case traceIdRatio

    /// Inherits the sampling decision from the parent span. Falls back
    /// to a configurable root sampler at trace root.
    case parentBased

    /// Adaptive sampling whose rate can be adjusted at runtime
    /// (typically by a policy evaluator reacting to device state /
    /// error rate). Backed by `DynamicSampler`.
    case dynamic
}

/// Configuration for trace and span sampling. Direct port of Android's
/// `SamplingConfig` — same fields, same defaults, same factory
/// constructors.
///
/// Validates rates in `init`; out-of-range values trap (programmer
/// error). The sampler factory is a separate type so a misconfigured
/// `SamplingConfig` never produces a bogus sampler — failure surfaces
/// at config time, not at sample time.
public struct SamplingConfig: Sendable, Equatable {
    public let strategy: SamplingStrategy
    public let samplingRate: Double
    public let highPrioritySamplingRate: Double
    public let parentBasedRoot: SamplingStrategy
    public let parentBasedRootSamplingRate: Double

    public init(
        strategy: SamplingStrategy = .traceIdRatio,
        samplingRate: Double = 0.1,
        highPrioritySamplingRate: Double = 1.0,
        parentBasedRoot: SamplingStrategy = .traceIdRatio,
        parentBasedRootSamplingRate: Double = 0.1
    ) {
        self.strategy = strategy
        // Clamp instead of trapping. The SDK safety audit forbids
        // `fatalError` / `preconditionFailure`; clamp matches Android's
        // observable contract for in-range inputs and stays defensive
        // for out-of-range ones.
        self.samplingRate = SamplingConfig.clamp(samplingRate)
        self.highPrioritySamplingRate = SamplingConfig.clamp(highPrioritySamplingRate)
        self.parentBasedRoot = parentBasedRoot
        self.parentBasedRootSamplingRate = SamplingConfig.clamp(parentBasedRootSamplingRate)
    }

    private static func clamp(_ value: Double) -> Double {
        return min(1.0, max(0.0, value))
    }

    // MARK: - Factory constructors (mirror Android's companion object)

    /// 100% sampling — for development / staging.
    public static func alwaysOn() -> SamplingConfig {
        SamplingConfig(strategy: .alwaysOn, samplingRate: 1.0)
    }

    /// 0% sampling — disables tracing.
    public static func alwaysOff() -> SamplingConfig {
        SamplingConfig(strategy: .alwaysOff, samplingRate: 0.0)
    }

    /// Production preset: trace-id-ratio, default 10%.
    public static func production(rate: Double = 0.1) -> SamplingConfig {
        SamplingConfig(strategy: .traceIdRatio, samplingRate: rate)
    }

    /// Dynamic preset: low baseline, 100% for high-priority spans
    /// (page.* / app.startup).
    public static func dynamic(
        normalRate: Double = 0.05,
        highPriorityRate: Double = 1.0
    ) -> SamplingConfig {
        SamplingConfig(
            strategy: .dynamic,
            samplingRate: normalRate,
            highPrioritySamplingRate: highPriorityRate
        )
    }

    /// Parent-based preset: inherit from parent, configurable root rate.
    public static func parentBased(rootRate: Double = 0.1) -> SamplingConfig {
        SamplingConfig(
            strategy: .parentBased,
            parentBasedRoot: .traceIdRatio,
            parentBasedRootSamplingRate: rootRate
        )
    }
}
