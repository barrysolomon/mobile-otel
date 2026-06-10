/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.sampling

import io.opentelemetry.android.mobile.instrumentation.Incubating
import io.opentelemetry.android.mobile.policy.RemoteGate
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
 * ## Interaction with auto-capture trace hierarchy
 *
 * `page.*` spans (created by page-span instrumentation) are always sampled based on
 * their name using the standard OTel `shouldSample()` `name` parameter — no span attribute
 * needed. This ensures the trace waterfall is always intact: if a page span were dropped, all
 * taps, scrolls, and manually-created child spans on that screen would lose their parent context
 * and appear as disconnected flat logs.
 *
 * Spans always captured regardless of baseline rate:
 *   - `page.*` — detected by name prefix (OTel-native, no attribute needed)
 *   - `app.startup` — detected by name
 *
 * Usage:
 * ```kotlin
 * val sampler = DynamicSampler(
 *     baselineSamplingRate = 0.1,  // 10% for high-volume spans (taps, API calls)
 *     highPrioritySamplingRate = 1.0  // 100% for page spans, errors, crashes
 * )
 *
 * // Temporarily increase sampling for 10 minutes after error
 * sampler.setSamplingRate(1.0, durationMinutes = 10)
 * ```
 */
@Incubating
class DynamicSampler(
    private val baselineSamplingRate: Double = 0.1,
    private val highPrioritySamplingRate: Double = 1.0,
    // Shared remote kill-switch / global-sampling gate. The same instance is wired into
    // the log processor so spans and logs gate coherently. Defaults to an open gate
    // (enabled, full rate) so existing call sites behave exactly as before.
    private val remoteGate: RemoteGate = RemoteGate()
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

        // Remote kill switch — a single volatile read. When the SDK is remotely disabled
        // every span is a hard DROP, including page/startup spans: the spec requires that a
        // disabled SDK produce NO new telemetry, and a dropped page span only orphans child
        // spans that are themselves being dropped. The gate snapshot is read once so the
        // `enabled`/`sampleRate` pair used below is internally consistent.
        val gate = remoteGate.snapshot()
        if (!gate.enabled) {
            return SamplingResult.create(SamplingDecision.DROP, GATE_DISABLED_ATTRIBUTES)
        }

        // OTel-native: always sample page and startup spans by name.
        // page.* spans are the root of the trace waterfall for every screen; dropping them
        // breaks all child spans (taps, scrolls, API calls) for that screen session.
        val isPageSpan = name.startsWith("page.") || name == "app.startup"
        val isHighPriority = isPageSpan

        // Determine the local sampling rate, then fold in the remote global rate as a CAP.
        // We take min(local, remote): the global head sample_rate can only ever REDUCE
        // volume, never raise it above what the app configured. A remote rate of 1.0 (the
        // default / "no restriction") leaves the local decision untouched, so page/startup
        // spans keep their force-sample behaviour unless an operator explicitly throttles
        // the whole fleet below 1.0.
        val localRate = if (isHighPriority) {
            highPrioritySamplingRate
        } else {
            currentSamplingRate.get()
        }
        val rate = minOf(localRate, gate.sampleRate)

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
                if (isPageSpan) put("sampling.page_span", true)
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
     * Returns the shared remote kill-switch / global-sampling gate this sampler reads.
     */
    fun getRemoteGate(): RemoteGate = remoteGate

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
     * - Takes the LOWER 8 bytes (trailing 64 bits) of the trace ID as a long
     * - Converts to a value between 0.0 and 1.0
     * - Samples if value < sampling rate
     *
     * The OTel-spec `TraceIdRatioBasedSampler` keys on the trailing 8 bytes
     * (hex chars 16-31), and the iOS SDK keys on `TraceId.idLo` (the low 64
     * bits = the same trailing bytes). Keying on the lower 64 bits here keeps
     * Android, iOS, and the OTel spec in lockstep so identical (traceId, rate)
     * inputs yield identical keep/drop decisions across platforms.
     */
    private fun shouldSampleTraceId(traceId: String, rate: Double): Boolean {
        if (rate >= 1.0) return true
        if (rate <= 0.0) return false

        // Convert the LOWER 16 hex chars (low 8 bytes) of the trace ID to a
        // long. This matches OpenTelemetry's TraceIdRatioBased sampler and the
        // iOS SDK's `TraceId.idLo`, keeping cross-platform decisions identical.
        val traceIdSuffix = if (traceId.length >= 32) {
            traceId.substring(16, 32)
        } else if (traceId.length >= 16) {
            // Shorter-than-128-bit trace id: use its trailing 16 hex chars.
            traceId.substring(traceId.length - 16)
        } else {
            traceId.padStart(16, '0')
        }

        val traceIdULong = try {
            java.lang.Long.parseUnsignedLong(traceIdSuffix, 16).toULong()
        } catch (e: NumberFormatException) {
            // Invalid trace ID, default to sampling
            return true
        }

        // SR-023: divide as unsigned. Previously this used signed Long math,
        // so any trace ID whose top bit was set parsed to a negative Long,
        // yielded a negative ratio, and `ratio < rate` evaluated true — so
        // ~50% of all trace IDs sampled regardless of rate. Using ULong here
        // gives a true [0.0, 1.0] ratio aligned with the OTel spec.
        val traceIdRatio = traceIdULong.toDouble() / ULong.MAX_VALUE.toDouble()

        return traceIdRatio < rate
    }

    companion object {
        /**
         * Sampling attributes attached to spans dropped by the remote kill switch.
         * Pre-built and shared since they never vary, keeping the disabled path
         * allocation-free aside from the [SamplingResult] itself.
         */
        private val GATE_DISABLED_ATTRIBUTES: Attributes = Attributes.builder()
            .put("sampling.strategy", "dynamic")
            .put("sampling.sdk_disabled", true)
            .build()

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
