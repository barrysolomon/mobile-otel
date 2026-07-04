/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.sampling

import io.opentelemetry.android.mobile.policy.RemoteGate
import io.opentelemetry.sdk.trace.samplers.Sampler

/**
 * Factory for creating OpenTelemetry samplers based on configuration.
 *
 * Follows OTEL sampling specifications and provides mobile-optimized samplers.
 */
object SamplerFactory {

    /**
     * Creates a sampler based on the provided configuration.
     *
     * @param config Sampling configuration
     * @param remoteGate the shared remote kill-switch / global-sampling gate. Wired into
     *   the [DynamicSampler] (the default mobile strategy) so the span choke point honours
     *   the same kill switch as the log choke point. When `null`, the sampler uses a fresh
     *   open gate — preserving the prior behaviour for callers that don't pass one.
     * @return Configured sampler instance
     */
    fun createSampler(config: SamplingConfig, remoteGate: RemoteGate? = null): Sampler {
        return when (config.strategy) {
            SamplingStrategy.ALWAYS_ON -> {
                Sampler.alwaysOn()
            }

            SamplingStrategy.ALWAYS_OFF -> {
                Sampler.alwaysOff()
            }

            SamplingStrategy.TRACE_ID_RATIO -> {
                Sampler.traceIdRatioBased(config.samplingRate)
            }

            SamplingStrategy.PARENT_BASED -> {
                val rootSampler = when (config.parentBasedRoot) {
                    SamplingStrategy.ALWAYS_ON -> Sampler.alwaysOn()
                    SamplingStrategy.ALWAYS_OFF -> Sampler.alwaysOff()
                    SamplingStrategy.TRACE_ID_RATIO -> Sampler.traceIdRatioBased(config.parentBasedRootSamplingRate)
                    else -> Sampler.traceIdRatioBased(config.parentBasedRootSamplingRate)
                }

                Sampler.parentBased(rootSampler)
            }

            SamplingStrategy.DYNAMIC -> {
                if (remoteGate != null) {
                    DynamicSampler(
                        baselineSamplingRate = config.samplingRate,
                        highPrioritySamplingRate = config.highPrioritySamplingRate,
                        remoteGate = remoteGate
                    )
                } else {
                    DynamicSampler(
                        baselineSamplingRate = config.samplingRate,
                        highPrioritySamplingRate = config.highPrioritySamplingRate
                    )
                }
            }
        }
    }

    /**
     * Creates a dynamic sampler that can be adjusted at runtime.
     *
     * This is the recommended sampler for mobile applications as it allows
     * adaptive sampling based on workflow triggers and device conditions.
     *
     * @param baselineRate Baseline sampling rate (default: 0.1 = 10%)
     * @param highPriorityRate High-priority sampling rate (default: 1.0 = 100%)
     * @return DynamicSampler instance
     */
    fun createDynamicSampler(
        baselineRate: Double = 0.1,
        highPriorityRate: Double = 1.0
    ): DynamicSampler {
        return DynamicSampler(
            baselineSamplingRate = baselineRate,
            highPrioritySamplingRate = highPriorityRate
        )
    }
}
