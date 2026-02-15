/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.sampling

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.data.LinkData
import io.opentelemetry.sdk.trace.samplers.Sampler
import io.opentelemetry.sdk.trace.samplers.SamplingDecision
import io.opentelemetry.sdk.trace.samplers.SamplingResult
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Dynamic sampler that can adjust sampling rate at runtime.
 *
 * This sampler follows OpenTelemetry sampling specifications and adds
 * mobile-specific features:
 * - Runtime sampling rate adjustment (for workflow actions)
 * - Temporary high-priority sampling (e.g., after errors)
 * - Attribute-based sampling decisions
 * - Sampling rate scheduling (revert to baseline after duration)
 *
 * Thread-safe implementation using read-write locks.
 *
 * Usage:
 * ```kotlin
 * val sampler = DynamicSampler(
 *     baselineSamplingRate = 0.1,  // 10% baseline
 *     highPrioritySamplingRate = 1.0  // 100% for high-priority
 * )
 *
 * // Temporarily increase sampling for 10 minutes after error
 * sampler.setSamplingRate(1.0, durationMinutes = 10)
 *
 * // Mark span as high priority to force sampling
 * span.setAttribute("sampling.priority", "high")
 * ```
 */
class DynamicSampler(
    private val baselineSamplingRate: Double = 0.1,
    private val highPrioritySamplingRate: Double = 1.0
) : Sampler {

    private val lock = ReentrantReadWriteLock()
    private var currentSamplingRate = AtomicReference(baselineSamplingRate)
    private var scheduledRevertTime: Long? = null

    init {
        require(baselineSamplingRate in 0.0..1.0) { "baselineSamplingRate must be between 0.0 and 1.0" }
        require(highPrioritySamplingRate in 0.0..1.0) { "highPrioritySamplingRate must be between 0.0 and 1.0" }
    }

    override fun shouldSample(
        parentContext: Context,
        traceId: String,
        name: String,
        spanKind: SpanKind,
        attributes: Attributes,
        parentLinks: List<LinkData>
    ): SamplingResult {
        // Check if scheduled revert time has passed
        checkScheduledRevert()

        // Check for high-priority attribute
        val priority = attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("sampling.priority"))
        val isHighPriority = priority == "high" || priority == "critical"

        // Determine sampling rate
        val rate = if (isHighPriority) {
            highPrioritySamplingRate
        } else {
            currentSamplingRate.get()
        }

        // Make sampling decision based on trace ID
        val decision = if (shouldSampleTraceId(traceId, rate)) {
            SamplingDecision.RECORD_AND_SAMPLE
        } else {
            SamplingDecision.DROP
        }

        // Add sampling attributes
        val samplingAttributes = Attributes.builder()
            .put("sampling.rate", rate)
            .put("sampling.strategy", "dynamic")
            .apply {
                if (isHighPriority) {
                    put("sampling.high_priority", true)
                }
            }
            .build()

        return SamplingResult.create(decision, samplingAttributes)
    }

    override fun getDescription(): String {
        return "DynamicSampler{baseline=$baselineSamplingRate, current=${currentSamplingRate.get()}, highPriority=$highPrioritySamplingRate}"
    }

    /**
     * Sets the current sampling rate.
     *
     * @param rate Sampling rate (0.0 to 1.0)
     * @param durationMinutes Optional duration in minutes before reverting to baseline (null = permanent)
     */
    fun setSamplingRate(rate: Double, durationMinutes: Int? = null) {
        require(rate in 0.0..1.0) { "rate must be between 0.0 and 1.0" }

        lock.write {
            currentSamplingRate.set(rate)

            if (durationMinutes != null) {
                scheduledRevertTime = System.currentTimeMillis() + (durationMinutes * 60 * 1000)
            } else {
                scheduledRevertTime = null
            }
        }
    }

    /**
     * Resets sampling rate to baseline.
     */
    fun resetToBaseline() {
        lock.write {
            currentSamplingRate.set(baselineSamplingRate)
            scheduledRevertTime = null
        }
    }

    /**
     * Gets the current sampling rate.
     */
    fun getCurrentSamplingRate(): Double {
        return currentSamplingRate.get()
    }

    /**
     * Gets the baseline sampling rate.
     */
    fun getBaselineSamplingRate(): Double {
        return baselineSamplingRate
    }

    /**
     * Checks if scheduled revert time has passed and reverts if needed.
     */
    private fun checkScheduledRevert() {
        lock.read {
            val revertTime = scheduledRevertTime
            if (revertTime != null && System.currentTimeMillis() >= revertTime) {
                lock.write {
                    if (scheduledRevertTime == revertTime) {  // Double-check to avoid race
                        currentSamplingRate.set(baselineSamplingRate)
                        scheduledRevertTime = null
                    }
                }
            }
        }
    }

    /**
     * Determines if a trace ID should be sampled based on rate.
     *
     * Uses OpenTelemetry's trace ID ratio-based sampling algorithm:
     * - Takes the first 8 bytes of trace ID as a long
     * - Converts to a value between 0.0 and 1.0
     * - Samples if value < sampling rate
     *
     * This ensures consistent sampling decisions across distributed systems.
     */
    private fun shouldSampleTraceId(traceId: String, rate: Double): Boolean {
        if (rate >= 1.0) return true
        if (rate <= 0.0) return false

        // Convert first 16 hex chars (8 bytes) of trace ID to long
        // This matches OpenTelemetry's TraceIdRatioBased sampler algorithm
        val traceIdPrefix = if (traceId.length >= 16) {
            traceId.substring(0, 16)
        } else {
            traceId.padEnd(16, '0')
        }

        val traceIdLong = try {
            java.lang.Long.parseUnsignedLong(traceIdPrefix, 16)
        } catch (e: NumberFormatException) {
            // Invalid trace ID, default to sampling
            return true
        }

        // Convert to 0.0-1.0 range
        val traceIdRatio = traceIdLong.toDouble() / Long.MAX_VALUE.toDouble()

        return traceIdRatio < rate
    }

    companion object {
        /**
         * Creates a sampler with 100% sampling (development).
         */
        fun alwaysOn(): DynamicSampler {
            return DynamicSampler(baselineSamplingRate = 1.0)
        }

        /**
         * Creates a sampler with 0% sampling (disabled).
         */
        fun alwaysOff(): DynamicSampler {
            return DynamicSampler(baselineSamplingRate = 0.0)
        }

        /**
         * Creates a sampler for production use.
         */
        fun production(rate: Double = 0.1): DynamicSampler {
            return DynamicSampler(baselineSamplingRate = rate)
        }
    }
}
