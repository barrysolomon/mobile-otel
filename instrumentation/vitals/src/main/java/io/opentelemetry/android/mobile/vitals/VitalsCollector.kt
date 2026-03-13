/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.vitals

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement
import io.opentelemetry.api.metrics.ObservableLongMeasurement
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Collects mobile vitals metrics for performance monitoring.
 *
 * This class coordinates the collection of various performance metrics including:
 * - App start times (cold/warm)
 * - Jank detection (frame drops)
 * - Input latency
 * - ANR risk signals
 * - Memory pressure
 * - Thermal state
 *
 * Thread-safe singleton that integrates with OpenTelemetry metrics.
 */
class VitalsCollector private constructor(
    private val context: Context,
    private val config: VitalsConfig,
    private val meter: Meter
) {
    private val activityManager: ActivityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val powerManager: PowerManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    } else {
        null
    }

    // Metrics state
    private val coldStartTime = AtomicLong(0)
    private val warmStartTime = AtomicLong(0)
    private val ttidTime = AtomicLong(0)
    private val lastJankTimestamp = AtomicLong(0)
    private val jankCount = AtomicLong(0)
    private val severeJankCount = AtomicLong(0)
    private val totalFrameCount = AtomicLong(0)
    private val inputLatencies = mutableListOf<Long>()
    private val mainThreadBlockTime = AtomicLong(0)
    private val thermalState = AtomicReference("normal")

    private val handler = Handler(Looper.getMainLooper())

    init {
        if (config.enabled) {
            registerMetrics()
            startMonitoring()
        }
    }

    /**
     * Register OpenTelemetry metrics.
     */
    private fun registerMetrics() {
        // App start metrics
        if (config.measureAppStart) {
            // @Incubating: mobile semconv not yet standardized; aligns with OTel mobile SIG proposal
            meter.gaugeBuilder("mobile.app.start.cold")
                .setDescription("Cold start time in milliseconds")
                .setUnit("ms")
                .ofLongs()
                .buildWithCallback { measurement: ObservableLongMeasurement ->
                    val value = coldStartTime.get()
                    if (value > 0) {
                        measurement.record(
                            value,
                            Attributes.of(
                                AttributeKey.stringKey("start.type"), "cold",
                                AttributeKey.booleanKey("start.slow"), value > config.coldStartThresholdMs
                            )
                        )
                    }
                }

            // @Incubating: mobile semconv not yet standardized; aligns with OTel mobile SIG proposal
            meter.gaugeBuilder("mobile.app.start.warm")
                .setDescription("Warm start time in milliseconds")
                .setUnit("ms")
                .ofLongs()
                .buildWithCallback { measurement: ObservableLongMeasurement ->
                    val value = warmStartTime.get()
                    if (value > 0) {
                        measurement.record(
                            value,
                            Attributes.of(
                                AttributeKey.stringKey("start.type"), "warm",
                                AttributeKey.booleanKey("start.slow"), value > config.warmStartThresholdMs
                            )
                        )
                    }
                }
        }

        // TTID metric
        if (config.measureTtid) {
            // @Incubating: mobile semconv not yet standardized; aligns with OTel mobile SIG proposal
            meter.gaugeBuilder("mobile.app.ttid")
                .setDescription("Time to initial display in milliseconds")
                .setUnit("ms")
                .ofLongs()
                .buildWithCallback { measurement: ObservableLongMeasurement ->
                    val value = ttidTime.get()
                    if (value > 0) {
                        measurement.record(
                            value,
                            Attributes.of(
                                AttributeKey.booleanKey("ttid.slow"), value > config.ttidThresholdMs
                            )
                        )
                    }
                }
        }

        // Jank metrics
        if (config.detectJank) {
            // @Incubating: mobile semconv not yet standardized; aligns with OTel mobile SIG proposal
            meter.gaugeBuilder("mobile.ui.jank.count")
                .setDescription("Total number of jank events detected")
                .setUnit("{events}")
                .ofLongs()
                .buildWithCallback { measurement: ObservableLongMeasurement ->
                    measurement.record(jankCount.get())
                }

            // @Incubating: mobile semconv not yet standardized; aligns with OTel mobile SIG proposal
            meter.gaugeBuilder("mobile.ui.jank.severe.count")
                .setDescription("Total number of severe jank events detected")
                .setUnit("{events}")
                .ofLongs()
                .buildWithCallback { measurement: ObservableLongMeasurement ->
                    measurement.record(severeJankCount.get())
                }

            // @Incubating: mobile semconv not yet standardized; aligns with OTel mobile SIG proposal
            meter.gaugeBuilder("mobile.ui.jank.rate")
                .setDescription("Percentage of frames that experienced jank")
                .setUnit("%")
                .buildWithCallback { measurement: ObservableDoubleMeasurement ->
                    val frames = totalFrameCount.get()
                    if (frames > 0) {
                        val rate = (jankCount.get().toDouble() / frames) * 100
                        measurement.record(rate)
                    }
                }
        }

        // Input latency
        if (config.trackInputLatency) {
            // @Incubating: mobile semconv not yet standardized; aligns with OTel mobile SIG proposal
            meter.gaugeBuilder("mobile.input.latency.avg")
                .setDescription("Average input latency in milliseconds")
                .setUnit("ms")
                .buildWithCallback { measurement: ObservableDoubleMeasurement ->
                    synchronized(inputLatencies) {
                        if (inputLatencies.isNotEmpty()) {
                            val avg = inputLatencies.average()
                            measurement.record(
                                avg,
                                Attributes.of(
                                    AttributeKey.booleanKey("latency.high"), avg > config.inputLatencyThresholdMs
                                )
                            )
                            inputLatencies.clear()
                        }
                    }
                }
        }

        // ANR risk
        if (config.monitorAnrRisk) {
            // @Incubating: mobile semconv not yet standardized; aligns with OTel mobile SIG proposal
            meter.gaugeBuilder("mobile.anr.risk")
                .setDescription("Main thread block time indicating ANR risk in milliseconds")
                .setUnit("ms")
                .ofLongs()
                .buildWithCallback { measurement: ObservableLongMeasurement ->
                    val blockTime = mainThreadBlockTime.get()
                    if (blockTime > 0) {
                        measurement.record(
                            blockTime,
                            Attributes.of(
                                AttributeKey.booleanKey("anr.risk.high"), blockTime > config.anrRiskThresholdMs
                            )
                        )
                    }
                }
        }

        // Memory pressure
        if (config.monitorMemoryPressure) {
            // @Incubating: mobile semconv not yet standardized; aligns with OTel mobile SIG proposal
            meter.gaugeBuilder("mobile.memory.available")
                .setDescription("Available memory in megabytes")
                .setUnit("MB")
                .ofLongs()
                .buildWithCallback { measurement: ObservableLongMeasurement ->
                    val memInfo = ActivityManager.MemoryInfo()
                    activityManager.getMemoryInfo(memInfo)
                    val availableMb = memInfo.availMem / (1024 * 1024)
                    measurement.record(
                        availableMb,
                        Attributes.of(
                            AttributeKey.booleanKey("memory.low"), memInfo.lowMemory,
                            AttributeKey.booleanKey("memory.critical"), availableMb < config.memoryPressureCriticalMb
                        )
                    )
                }

            // @Incubating: mobile semconv not yet standardized; aligns with OTel mobile SIG proposal
            meter.gaugeBuilder("mobile.memory.threshold")
                .setDescription("Low memory threshold in megabytes")
                .setUnit("MB")
                .ofLongs()
                .buildWithCallback { measurement: ObservableLongMeasurement ->
                    val memInfo = ActivityManager.MemoryInfo()
                    activityManager.getMemoryInfo(memInfo)
                    val thresholdMb = memInfo.threshold / (1024 * 1024)
                    measurement.record(thresholdMb)
                }
        }

        // Thermal state
        if (config.monitorThermalState && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // @Incubating: mobile semconv not yet standardized; aligns with OTel mobile SIG proposal
            meter.gaugeBuilder("mobile.thermal.state")
                .setDescription("Device thermal state (0=none, 1=light, 2=moderate, 3=severe, 4=critical)")
                .setUnit("{state}")
                .ofLongs()
                .buildWithCallback { measurement: ObservableLongMeasurement ->
                    powerManager?.let { pm ->
                        val state = pm.currentThermalStatus
                        measurement.record(
                            state.toLong(),
                            Attributes.of(
                                AttributeKey.stringKey("thermal.state"), getThermalStateName(state)
                            )
                        )
                        thermalState.set(getThermalStateName(state))
                    }
                }
        }
    }

    /**
     * Start background monitoring tasks.
     */
    private fun startMonitoring() {
        // Periodic vitals check
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (config.enabled && shouldSample()) {
                    collectVitalsSnapshot()
                }
                handler.postDelayed(this, config.reportingIntervalMs)
            }
        }, config.reportingIntervalMs)
    }

    /**
     * Record cold start time.
     */
    fun recordColdStart(durationMs: Long) {
        if (config.measureAppStart && shouldSample()) {
            coldStartTime.set(durationMs)
        }
    }

    /**
     * Record warm start time.
     */
    fun recordWarmStart(durationMs: Long) {
        if (config.measureAppStart && shouldSample()) {
            warmStartTime.set(durationMs)
        }
    }

    /**
     * Record time to initial display.
     */
    fun recordTtid(durationMs: Long) {
        if (config.measureTtid && shouldSample()) {
            ttidTime.set(durationMs)
        }
    }

    /**
     * Record a frame time for jank detection.
     */
    fun recordFrameTime(frameTimeMs: Double) {
        if (config.detectJank && shouldSample()) {
            totalFrameCount.incrementAndGet()

            if (frameTimeMs > config.jankThresholdMs) {
                jankCount.incrementAndGet()
                lastJankTimestamp.set(System.currentTimeMillis())

                if (frameTimeMs > config.severeJankThresholdMs) {
                    severeJankCount.incrementAndGet()
                }
            }
        }
    }

    /**
     * Record input latency.
     */
    fun recordInputLatency(latencyMs: Long) {
        if (config.trackInputLatency && shouldSample()) {
            synchronized(inputLatencies) {
                inputLatencies.add(latencyMs)
            }
        }
    }

    /**
     * Record main thread block time.
     */
    fun recordMainThreadBlock(blockTimeMs: Long) {
        if (config.monitorAnrRisk && shouldSample()) {
            mainThreadBlockTime.set(blockTimeMs)
        }
    }

    /**
     * Check if we should sample this event based on sampling rate.
     */
    private fun shouldSample(): Boolean {
        return Math.random() < config.samplingRate
    }

    /**
     * Collect a snapshot of all vitals metrics.
     */
    private fun collectVitalsSnapshot() {
        // Metrics are automatically collected via callbacks
        // This method can be used for additional custom logic
    }

    /**
     * Get thermal state name from integer value.
     */
    private fun getThermalStateName(state: Int): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (state) {
                PowerManager.THERMAL_STATUS_NONE -> "none"
                PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
                else -> "unknown"
            }
        } else {
            "not_supported"
        }
    }

    /**
     * Reset all vitals metrics.
     */
    fun reset() {
        coldStartTime.set(0)
        warmStartTime.set(0)
        ttidTime.set(0)
        jankCount.set(0)
        severeJankCount.set(0)
        totalFrameCount.set(0)
        synchronized(inputLatencies) {
            inputLatencies.clear()
        }
        mainThreadBlockTime.set(0)
    }

    /**
     * Get current vitals as attributes for enrichment.
     */
    fun getVitalsAttributes(): Attributes {
        val builder = Attributes.builder()

        if (config.detectJank) {
            builder.put(AttributeKey.longKey("mobile.jank.count"), jankCount.get())
            builder.put(AttributeKey.longKey("mobile.jank.severe.count"), severeJankCount.get())
        }

        if (config.monitorAnrRisk) {
            val blockTime = mainThreadBlockTime.get()
            if (blockTime > 0) {
                builder.put(AttributeKey.longKey("mobile.anr.risk.ms"), blockTime)
            }
        }

        if (config.monitorMemoryPressure) {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            builder.put(AttributeKey.booleanKey("mobile.memory.low"), memInfo.lowMemory)
        }

        if (config.monitorThermalState) {
            builder.put(AttributeKey.stringKey("mobile.thermal.state"), thermalState.get())
        }

        return builder.build()
    }

    companion object {
        @Volatile
        private var instance: VitalsCollector? = null

        /**
         * Initialize the vitals collector.
         */
        fun initialize(context: Context, config: VitalsConfig, meter: Meter): VitalsCollector {
            return instance ?: synchronized(this) {
                instance ?: VitalsCollector(context.applicationContext, config, meter).also {
                    instance = it
                }
            }
        }

        /**
         * Get the vitals collector instance.
         */
        fun getInstance(): VitalsCollector? = instance

        /**
         * Check if vitals collector is initialized.
         */
        fun isInitialized(): Boolean = instance != null
    }
}
