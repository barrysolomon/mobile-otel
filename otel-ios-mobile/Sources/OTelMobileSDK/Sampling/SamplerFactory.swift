/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation
import OpenTelemetrySdk

/// Builds an OTel-Swift `Sampler` from a `SamplingConfig`. Direct port
/// of Android's `SamplerFactory`. Centralising the strategy → sampler
/// mapping keeps the Tracer-provider wiring free of switch statements
/// and gives policy-evaluator code a single seam for swapping samplers
/// at runtime.
public enum SamplerFactory {
    /// Returns the sampler matching `config.strategy`. For `.parentBased`,
    /// the configured root sampler is built recursively (limited to one
    /// level — Android does the same; nesting parent-based inside
    /// parent-based is not supported and would be flagged at config
    /// time anyway).
    public static func createSampler(_ config: SamplingConfig) -> Sampler {
        switch config.strategy {
        case .alwaysOn:
            return Samplers.alwaysOn
        case .alwaysOff:
            return Samplers.alwaysOff
        case .traceIdRatio:
            return Samplers.traceIdRatio(ratio: config.samplingRate)
        case .parentBased:
            let root: Sampler
            switch config.parentBasedRoot {
            case .alwaysOn:
                root = Samplers.alwaysOn
            case .alwaysOff:
                root = Samplers.alwaysOff
            case .traceIdRatio, .parentBased, .dynamic:
                // Android collapses everything except the three explicit
                // cases to traceIdRatio. Match that — silently swapping
                // a `.dynamic` root for a static rate keeps behaviour
                // predictable instead of accepting a self-referential
                // parent-of-parent config.
                root = Samplers.traceIdRatio(ratio: config.parentBasedRootSamplingRate)
            }
            return Samplers.parentBased(root: root)
        case .dynamic:
            return DynamicSampler(
                baselineSamplingRate: config.samplingRate,
                highPrioritySamplingRate: config.highPrioritySamplingRate
            )
        }
    }

    /// Convenience: build a `DynamicSampler` directly without
    /// constructing a config first. Recommended for mobile use cases
    /// where the policy evaluator wants to bump the rate at runtime.
    public static func createDynamicSampler(
        baselineRate: Double = 0.1,
        highPriorityRate: Double = 1.0
    ) -> DynamicSampler {
        DynamicSampler(
            baselineSamplingRate: baselineRate,
            highPrioritySamplingRate: highPriorityRate
        )
    }
}
