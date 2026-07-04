/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.metrics

/**
 * Configuration for which device metrics to capture and export on flush/trigger events.
 *
 * By default, all metrics are enabled for crash/error scenarios to provide
 * maximum debugging context. Can be customized to reduce data volume.
 *
 * Usage:
 * ```kotlin
 * // Default: All metrics on crash
 * val config = DeviceMetricsConfig.default()
 *
 * // Minimal: Only essential metrics
 * val config = DeviceMetricsConfig.minimal()
 *
 * // Custom: Select specific metrics
 * val config = DeviceMetricsConfig(
 *     captureMemory = true,
 *     captureBattery = true,
 *     captureNetwork = false
 * )
 * ```
 *
 * @property captureMemory Memory usage (used, available, total, low memory state)
 * @property captureBattery Battery level, charging state, health, temperature
 * @property captureCpu CPU usage, core count, architecture
 * @property captureNetwork Network type, connection state, signal strength
 * @property captureStorage Disk usage (internal, external, cache size)
 * @property captureThermal Thermal state, throttling level
 * @property captureDisplay Screen state, brightness, orientation, resolution
 * @property captureSystem OS version, API level, kernel version, uptime
 * @property captureApp App version, install time, last update, foreground state
 * @property captureLocation Coarse location (country, timezone) - privacy-safe
 */
data class DeviceMetricsConfig(
    val captureMemory: Boolean = true,
    val captureBattery: Boolean = true,
    val captureCpu: Boolean = true,
    val captureNetwork: Boolean = true,
    val captureStorage: Boolean = true,
    val captureThermal: Boolean = true,
    val captureDisplay: Boolean = true,
    val captureSystem: Boolean = true,
    val captureApp: Boolean = true,
    val captureLocation: Boolean = false  // Privacy-sensitive, disabled by default
) {
    companion object {
        /**
         * Default configuration: All non-privacy-sensitive metrics enabled.
         * Recommended for crash/error scenarios to maximize debugging context.
         */
        fun default() = DeviceMetricsConfig()

        /**
         * Minimal configuration: Only essential metrics (memory, battery, system).
         * Use when data volume is a concern.
         */
        fun minimal() = DeviceMetricsConfig(
            captureMemory = true,
            captureBattery = true,
            captureCpu = false,
            captureNetwork = false,
            captureStorage = false,
            captureThermal = false,
            captureDisplay = false,
            captureSystem = true,
            captureApp = true,
            captureLocation = false
        )

        /**
         * Performance-focused: Metrics useful for performance debugging.
         */
        fun performance() = DeviceMetricsConfig(
            captureMemory = true,
            captureBattery = true,
            captureCpu = true,
            captureNetwork = false,
            captureStorage = true,
            captureThermal = true,
            captureDisplay = false,
            captureSystem = true,
            captureApp = true,
            captureLocation = false
        )

        /**
         * Network-focused: Metrics useful for network issue debugging.
         */
        fun network() = DeviceMetricsConfig(
            captureMemory = true,
            captureBattery = true,
            captureCpu = false,
            captureNetwork = true,
            captureStorage = false,
            captureThermal = false,
            captureDisplay = false,
            captureSystem = true,
            captureApp = true,
            captureLocation = false
        )

        /**
         * Privacy-focused: No location or potentially sensitive metrics.
         */
        fun privacyFocused() = DeviceMetricsConfig(
            captureMemory = true,
            captureBattery = true,
            captureCpu = true,
            captureNetwork = true,
            captureStorage = true,
            captureThermal = true,
            captureDisplay = false,  // Screen state can reveal user behavior
            captureSystem = true,
            captureApp = true,
            captureLocation = false  // Explicitly no location
        )

        /**
         * Disabled: No device metrics captured (for A/B testing or opt-out).
         */
        fun disabled() = DeviceMetricsConfig(
            captureMemory = false,
            captureBattery = false,
            captureCpu = false,
            captureNetwork = false,
            captureStorage = false,
            captureThermal = false,
            captureDisplay = false,
            captureSystem = false,
            captureApp = false,
            captureLocation = false
        )
    }
}

/**
 * Configuration for when to capture device metrics.
 *
 * Metrics can be captured on:
 * - App lifecycle events (start, force close)
 * - Crash/error events (recommended)
 * - Manual flush
 * - Scheduled intervals
 * - Workflow triggers
 *
 * @property onAppStart Capture metrics when app starts (default: true)
 * @property onForceClose Capture metrics when app is force closed by user (default: true)
 * @property onCrash Capture metrics when crash detected (default: true)
 * @property onError Capture metrics when error logged (HTTP 5xx, exceptions) (default: true)
 * @property onManualFlush Capture metrics on manual forceFlush() (default: true)
 * @property onScheduledFlush Capture metrics on scheduled exports (default: false, battery-saving)
 * @property onWorkflowTrigger Capture metrics when workflow triggers (default: true)
 * @property rateLimitSeconds Minimum seconds between metric captures (default: 60)
 */
data class DeviceMetricsCaptureConfig(
    val onAppStart: Boolean = true,
    val onForceClose: Boolean = true,
    val onCrash: Boolean = true,
    val onError: Boolean = true,
    val onManualFlush: Boolean = true,
    val onScheduledFlush: Boolean = false,  // Battery-saving default
    val onWorkflowTrigger: Boolean = true,
    val rateLimitSeconds: Int = 60  // Prevent excessive metric capture
) {
    companion object {
        /**
         * Default: Capture on all trigger events except scheduled flushes.
         */
        fun default() = DeviceMetricsCaptureConfig()

        /**
         * Aggressive: Capture metrics on every opportunity.
         */
        fun aggressive() = DeviceMetricsCaptureConfig(
            onAppStart = true,
            onForceClose = true,
            onCrash = true,
            onError = true,
            onManualFlush = true,
            onScheduledFlush = true,
            onWorkflowTrigger = true,
            rateLimitSeconds = 10
        )

        /**
         * Conservative: Only capture on crashes and app lifecycle (minimal battery impact).
         */
        fun conservative() = DeviceMetricsCaptureConfig(
            onAppStart = true,   // Track startup state
            onForceClose = true, // Track force close state
            onCrash = true,
            onError = false,
            onManualFlush = false,
            onScheduledFlush = false,
            onWorkflowTrigger = false,
            rateLimitSeconds = 300  // 5 minutes
        )
    }
}
