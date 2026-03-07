/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.vitals

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributeKey
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Detects UI jank (frame drops) by monitoring frame callback timing.
 *
 * Jank occurs when a frame takes longer than the target frame time (16ms for 60fps).
 * This detector uses Choreographer to measure actual frame times and detect:
 * - Minor jank: Frame time > 16ms (missed 1 frame)
 * - Moderate jank: Frame time > 32ms (missed 2 frames)
 * - Severe jank: Frame time > 100ms (UI freeze)
 *
 * Thread-safe singleton that reports to VitalsCollector and logs jank events.
 */
class JankDetector private constructor(
    private val config: VitalsConfig,
    private val vitalsCollector: VitalsCollector?,
    private val logger: Logger?
) : Choreographer.FrameCallback {

    private val choreographer = Choreographer.getInstance()
    private var lastFrameTimeNanos = AtomicLong(0)
    private var isMonitoring = false
    private val handler = Handler(Looper.getMainLooper())

    // Frame time thresholds in nanoseconds
    private val jankThresholdNanos = TimeUnit.MILLISECONDS.toNanos(config.jankThresholdMs.toLong())
    private val severeJankThresholdNanos = TimeUnit.MILLISECONDS.toNanos(config.severeJankThresholdMs.toLong())

    // Statistics
    private val consecutiveJanks = AtomicLong(0)
    private val lastJankReport = AtomicLong(0)
    private val jankReportCooldownMs = 1000L  // Don't spam jank logs

    /**
     * Start monitoring for jank.
     */
    fun startMonitoring() {
        if (!isMonitoring && config.detectJank) {
            isMonitoring = true
            lastFrameTimeNanos.set(System.nanoTime())
            choreographer.postFrameCallback(this)
        }
    }

    /**
     * Stop monitoring for jank.
     */
    fun stopMonitoring() {
        isMonitoring = false
        choreographer.removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isMonitoring) {
            return
        }

        val lastFrame = lastFrameTimeNanos.get()
        if (lastFrame > 0) {
            val frameTimeElapsed = frameTimeNanos - lastFrame
            val frameTimeMs = TimeUnit.NANOSECONDS.toMillis(frameTimeElapsed).toDouble()

            // Record frame time to vitals collector
            vitalsCollector?.recordFrameTime(frameTimeMs)

            // Detect jank
            if (frameTimeElapsed > jankThresholdNanos) {
                handleJank(frameTimeMs, frameTimeElapsed >= severeJankThresholdNanos)
            } else {
                // Reset consecutive jank counter on good frame
                consecutiveJanks.set(0)
            }
        }

        lastFrameTimeNanos.set(frameTimeNanos)

        // Schedule next frame callback
        if (isMonitoring) {
            choreographer.postFrameCallback(this)
        }
    }

    /**
     * Handle detected jank event.
     */
    private fun handleJank(frameTimeMs: Double, isSevere: Boolean) {
        val janks = consecutiveJanks.incrementAndGet()
        val now = System.currentTimeMillis()
        val lastReport = lastJankReport.get()

        // Only log if we haven't reported recently (avoid spam)
        if (now - lastReport > jankReportCooldownMs) {
            val severity = when {
                isSevere -> "severe"
                frameTimeMs > 32.0 -> "moderate"
                else -> "minor"
            }

            val droppedFrames = (frameTimeMs / config.jankThresholdMs).toInt()

            logger?.logRecordBuilder()
                ?.setBody("UI jank detected")
                ?.setSeverity(if (isSevere) io.opentelemetry.api.logs.Severity.WARN else io.opentelemetry.api.logs.Severity.INFO)
                ?.setAllAttributes(
                    Attributes.of(
                        AttributeKey.stringKey("event.name"), "mobile.ui.jank",
                        AttributeKey.doubleKey("jank.frame_time_ms"), frameTimeMs,
                        AttributeKey.longKey("jank.dropped_frames"), droppedFrames.toLong(),
                        AttributeKey.stringKey("jank.severity"), severity,
                        AttributeKey.longKey("jank.consecutive_count"), janks,
                        AttributeKey.booleanKey("jank.severe"), isSevere
                    )
                )
                ?.emit()

            lastJankReport.set(now)
        }
    }

    /**
     * Get current jank statistics.
     */
    fun getStatistics(): JankStatistics {
        return JankStatistics(
            consecutiveJanks = consecutiveJanks.get(),
            isCurrentlyJanky = consecutiveJanks.get() > 3
        )
    }

    /**
     * Reset jank statistics.
     */
    fun reset() {
        consecutiveJanks.set(0)
        lastFrameTimeNanos.set(0)
        lastJankReport.set(0)
    }

    /**
     * Jank statistics data class.
     */
    data class JankStatistics(
        val consecutiveJanks: Long,
        val isCurrentlyJanky: Boolean
    )

    companion object {
        @Volatile
        private var instance: JankDetector? = null

        /**
         * Initialize jank detector.
         *
         * @param config Vitals configuration
         * @param vitalsCollector Vitals collector for metrics
         * @param logger Logger for jank events
         */
        fun initialize(
            config: VitalsConfig,
            vitalsCollector: VitalsCollector?,
            logger: Logger?
        ): JankDetector {
            return instance ?: synchronized(this) {
                instance ?: JankDetector(config, vitalsCollector, logger).also {
                    instance = it
                    // Start monitoring automatically
                    it.startMonitoring()
                }
            }
        }

        /**
         * Get jank detector instance.
         */
        fun getInstance(): JankDetector? = instance

        /**
         * Check if jank detector is initialized.
         */
        fun isInitialized(): Boolean = instance != null

        /**
         * Resets the singleton for testing.
         *
         * Stops monitoring and clears the instance so the next [initialize] call
         * receives a fresh detector with the desired config. Only call from tests.
         */
        fun resetForTesting() {
            synchronized(this) {
                instance?.stopMonitoring()
                instance = null
            }
        }
    }
}
