/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.vitals

import io.opentelemetry.android.mobile.instrumentation.Incubating

/**
 * Configuration for mobile vitals monitoring.
 *
 * Defines thresholds and behavior for performance monitoring including:
 * - App start time (cold/warm)
 * - Time to Initial Display (TTID)
 * - Jank detection (dropped frames)
 * - Input latency
 * - ANR risk detection
 * - Memory pressure
 * - Thermal throttling
 *
 * Usage:
 * ```kotlin
 * val vitalsConfig = VitalsConfig(
 *     enabled = true,
 *     measureAppStart = true,
 *     detectJank = true,
 *     jankThresholdMs = 16.0
 * )
 * ```
 *
 * @property enabled Whether vitals monitoring is enabled (default: true)
 * @property measureAppStart Measure cold and warm start times (default: true)
 * @property measureTtid Measure time to initial display (default: true)
 * @property detectJank Detect frame drops and jank (default: true)
 * @property trackInputLatency Track touch/input response times (default: true)
 * @property monitorAnrRisk Monitor main thread blocking for ANR risk (default: true)
 * @property monitorMemoryPressure Monitor memory pressure levels (default: true)
 * @property monitorThermalState Monitor device thermal state (default: false)
 * @property jankThresholdMs Frame time threshold for jank detection in ms (default: 16.0 = 60fps)
 * @property severeJankThresholdMs Severe jank threshold in ms (default: 100.0)
 * @property inputLatencyThresholdMs Input latency threshold for warnings in ms (default: 50.0)
 * @property anrRiskThresholdMs Main thread block time threshold for ANR risk in ms (default: 3000.0)
 * @property coldStartThresholdMs Threshold for "slow" cold start in ms (default: 5000.0)
 * @property warmStartThresholdMs Threshold for "slow" warm start in ms (default: 2000.0)
 * @property ttidThresholdMs Threshold for "slow" TTID in ms (default: 3000.0)
 * @property memoryPressureCriticalMb Available memory threshold for critical state in MB (default: 50)
 * @property samplingRate Sampling rate for vitals (0.0-1.0, default: 1.0 = 100%)
 * @property reportingIntervalMs Interval for reporting vitals metrics in ms (default: 60000 = 1 min)
 */
@Incubating
data class VitalsConfig(
    val enabled: Boolean = true,

    // Feature flags
    val measureAppStart: Boolean = true,
    val measureTtid: Boolean = true,
    val detectJank: Boolean = true,
    val trackInputLatency: Boolean = true,
    val monitorAnrRisk: Boolean = true,
    val monitorMemoryPressure: Boolean = true,
    val monitorThermalState: Boolean = false,

    // Thresholds
    val jankThresholdMs: Double = 16.0,  // 60fps baseline
    val severeJankThresholdMs: Double = 100.0,
    val inputLatencyThresholdMs: Double = 50.0,
    val anrRiskThresholdMs: Long = 3000,
    val coldStartThresholdMs: Long = 5000,
    val warmStartThresholdMs: Long = 2000,
    val ttidThresholdMs: Long = 3000,
    val memoryPressureCriticalMb: Int = 50,

    // Sampling and reporting
    val samplingRate: Double = 1.0,
    val reportingIntervalMs: Long = 60000
) {
    init {
        require(jankThresholdMs > 0) { "jankThresholdMs must be positive" }
        require(severeJankThresholdMs > jankThresholdMs) { "severeJankThresholdMs must be greater than jankThresholdMs" }
        require(inputLatencyThresholdMs > 0) { "inputLatencyThresholdMs must be positive" }
        require(anrRiskThresholdMs > 0) { "anrRiskThresholdMs must be positive" }
        require(coldStartThresholdMs > 0) { "coldStartThresholdMs must be positive" }
        require(warmStartThresholdMs > 0) { "warmStartThresholdMs must be positive" }
        require(ttidThresholdMs > 0) { "ttidThresholdMs must be positive" }
        require(memoryPressureCriticalMb > 0) { "memoryPressureCriticalMb must be positive" }
        require(samplingRate in 0.0..1.0) { "samplingRate must be between 0.0 and 1.0" }
        require(reportingIntervalMs > 0) { "reportingIntervalMs must be positive" }
    }

    companion object {
        /**
         * Default configuration with recommended settings.
         */
        fun default(): VitalsConfig = VitalsConfig()

        /**
         * Minimal configuration - only critical vitals enabled.
         */
        fun minimal(): VitalsConfig = VitalsConfig(
            measureAppStart = true,
            measureTtid = false,
            detectJank = false,
            trackInputLatency = false,
            monitorAnrRisk = true,
            monitorMemoryPressure = true,
            monitorThermalState = false
        )

        /**
         * Aggressive configuration - all vitals with strict thresholds.
         */
        fun aggressive(): VitalsConfig = VitalsConfig(
            measureAppStart = true,
            measureTtid = true,
            detectJank = true,
            trackInputLatency = true,
            monitorAnrRisk = true,
            monitorMemoryPressure = true,
            monitorThermalState = true,
            jankThresholdMs = 16.0,
            severeJankThresholdMs = 50.0,
            inputLatencyThresholdMs = 30.0,
            anrRiskThresholdMs = 2000,
            coldStartThresholdMs = 3000,
            warmStartThresholdMs = 1000,
            ttidThresholdMs = 2000,
            samplingRate = 1.0
        )

        /**
         * Battery-friendly configuration - reduced monitoring.
         */
        fun batteryFriendly(): VitalsConfig = VitalsConfig(
            measureAppStart = true,
            measureTtid = true,
            detectJank = true,
            trackInputLatency = false,
            monitorAnrRisk = true,
            monitorMemoryPressure = false,
            monitorThermalState = false,
            samplingRate = 0.1,
            reportingIntervalMs = 300000  // 5 minutes
        )
    }
}
