/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.sampling

/**
 * Sampling strategy for traces and spans.
 *
 * Follows OpenTelemetry sampling specifications:
 * https://opentelemetry.io/docs/specs/otel/trace/sdk/#sampling
 */
enum class SamplingStrategy {
    /**
     * Always sample (100% sampling rate).
     * Records and exports all traces.
     * Use for: Development, debugging, critical flows.
     */
    ALWAYS_ON,

    /**
     * Never sample (0% sampling rate).
     * Records no traces.
     * Use for: Disabling tracing temporarily.
     */
    ALWAYS_OFF,

    /**
     * Probability-based sampling.
     * Samples a percentage of traces based on trace ID.
     * Use for: Production with controlled data volume.
     */
    TRACE_ID_RATIO,

    /**
     * Parent-based sampling.
     * Inherits sampling decision from parent span.
     * Use for: Distributed tracing consistency.
     */
    PARENT_BASED,

    /**
     * Dynamic sampling with runtime adjustments.
     * Allows changing sampling rate based on conditions.
     * Use for: Adaptive sampling based on device state or triggers.
     */
    DYNAMIC
}

/**
 * Configuration for trace and span sampling.
 *
 * Controls which traces are recorded and exported to reduce data volume
 * and battery consumption while maintaining observability.
 *
 * Usage:
 * ```kotlin
 * // Production: 10% sampling
 * val config = SamplingConfig(
 *     strategy = SamplingStrategy.TRACE_ID_RATIO,
 *     samplingRate = 0.1
 * )
 *
 * // Development: 100% sampling
 * val config = SamplingConfig(
 *     strategy = SamplingStrategy.ALWAYS_ON
 * )
 *
 * // Dynamic: adjust sampling based on conditions
 * val config = SamplingConfig(
 *     strategy = SamplingStrategy.DYNAMIC,
 *     samplingRate = 0.05,
 *     highPrioritySamplingRate = 1.0
 * )
 * ```
 *
 * @property strategy Sampling strategy to use (default: TRACE_ID_RATIO)
 * @property samplingRate Base sampling rate (0.0 to 1.0) for TRACE_ID_RATIO and DYNAMIC strategies (default: 0.1 = 10%)
 * @property highPrioritySamplingRate Sampling rate for high-priority traces in DYNAMIC strategy (default: 1.0 = 100%)
 * @property parentBasedRoot Root span sampling strategy when using PARENT_BASED (default: TRACE_ID_RATIO)
 * @property parentBasedRootSamplingRate Sampling rate for root spans in PARENT_BASED strategy (default: 0.1)
 */
data class SamplingConfig(
    val strategy: SamplingStrategy = SamplingStrategy.TRACE_ID_RATIO,
    val samplingRate: Double = 0.1,
    val highPrioritySamplingRate: Double = 1.0,
    val parentBasedRoot: SamplingStrategy = SamplingStrategy.TRACE_ID_RATIO,
    val parentBasedRootSamplingRate: Double = 0.1
) {
    init {
        require(samplingRate in 0.0..1.0) { "samplingRate must be between 0.0 and 1.0" }
        require(highPrioritySamplingRate in 0.0..1.0) { "highPrioritySamplingRate must be between 0.0 and 1.0" }
        require(parentBasedRootSamplingRate in 0.0..1.0) { "parentBasedRootSamplingRate must be between 0.0 and 1.0" }
    }

    companion object {
        /**
         * Always sample everything (development).
         */
        fun alwaysOn() = SamplingConfig(
            strategy = SamplingStrategy.ALWAYS_ON,
            samplingRate = 1.0
        )

        /**
         * Never sample anything (disabled).
         */
        fun alwaysOff() = SamplingConfig(
            strategy = SamplingStrategy.ALWAYS_OFF,
            samplingRate = 0.0
        )

        /**
         * Production sampling (10% by default).
         */
        fun production(rate: Double = 0.1) = SamplingConfig(
            strategy = SamplingStrategy.TRACE_ID_RATIO,
            samplingRate = rate
        )

        /**
         * Dynamic sampling with different rates for normal and high-priority traces.
         */
        fun dynamic(normalRate: Double = 0.05, highPriorityRate: Double = 1.0) = SamplingConfig(
            strategy = SamplingStrategy.DYNAMIC,
            samplingRate = normalRate,
            highPrioritySamplingRate = highPriorityRate
        )

        /**
         * Parent-based sampling for distributed tracing.
         */
        fun parentBased(rootRate: Double = 0.1) = SamplingConfig(
            strategy = SamplingStrategy.PARENT_BASED,
            parentBasedRoot = SamplingStrategy.TRACE_ID_RATIO,
            parentBasedRootSamplingRate = rootRate
        )
    }
}
