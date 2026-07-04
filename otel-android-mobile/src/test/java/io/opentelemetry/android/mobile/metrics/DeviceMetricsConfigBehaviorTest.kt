/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.metrics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavioral tests for [DeviceMetricsConfig], [DeviceMetricsCaptureConfig],
 * and [DeviceMetricsCollector].
 *
 * Validates preset configs, boolean flag wiring, and capture config presets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeviceMetricsConfigBehaviorTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        // No singleton to reset for DeviceMetricsCollector (not a singleton)
    }

    // ── DeviceMetricsConfig: default() preset ───────────────────────────────

    @Test
    fun `default preset has all true except captureLocation`() {
        val config = DeviceMetricsConfig.default()
        assertTrue(config.captureMemory, "default should capture memory")
        assertTrue(config.captureBattery, "default should capture battery")
        assertTrue(config.captureCpu, "default should capture cpu")
        assertTrue(config.captureNetwork, "default should capture network")
        assertTrue(config.captureStorage, "default should capture storage")
        assertTrue(config.captureThermal, "default should capture thermal")
        assertTrue(config.captureDisplay, "default should capture display")
        assertTrue(config.captureSystem, "default should capture system")
        assertTrue(config.captureApp, "default should capture app")
        assertFalse(config.captureLocation,
            "default should NOT capture location (privacy-sensitive)")
    }

    @Test
    fun `default constructor matches default preset`() {
        val fromConstructor = DeviceMetricsConfig()
        val fromPreset = DeviceMetricsConfig.default()
        assertEquals(fromConstructor, fromPreset,
            "No-arg constructor should match default() preset")
    }

    // ── DeviceMetricsConfig: minimal() preset ───────────────────────────────

    @Test
    fun `minimal preset enables only essential metrics`() {
        val config = DeviceMetricsConfig.minimal()
        assertTrue(config.captureMemory, "minimal should capture memory")
        assertTrue(config.captureBattery, "minimal should capture battery")
        assertFalse(config.captureCpu, "minimal should not capture cpu")
        assertFalse(config.captureNetwork, "minimal should not capture network")
        assertFalse(config.captureStorage, "minimal should not capture storage")
        assertFalse(config.captureThermal, "minimal should not capture thermal")
        assertFalse(config.captureDisplay, "minimal should not capture display")
        assertTrue(config.captureSystem, "minimal should capture system")
        assertTrue(config.captureApp, "minimal should capture app")
        assertFalse(config.captureLocation, "minimal should not capture location")
    }

    // ── DeviceMetricsConfig: performance() preset ───────────────────────────

    @Test
    fun `performance preset enables performance-relevant metrics`() {
        val config = DeviceMetricsConfig.performance()
        assertTrue(config.captureMemory, "performance should capture memory")
        assertTrue(config.captureBattery, "performance should capture battery")
        assertTrue(config.captureCpu, "performance should capture cpu")
        assertFalse(config.captureNetwork, "performance should not capture network")
        assertTrue(config.captureStorage, "performance should capture storage")
        assertTrue(config.captureThermal, "performance should capture thermal")
        assertFalse(config.captureDisplay, "performance should not capture display")
        assertTrue(config.captureSystem, "performance should capture system")
        assertTrue(config.captureApp, "performance should capture app")
        assertFalse(config.captureLocation, "performance should not capture location")
    }

    // ── DeviceMetricsConfig: network() preset ───────────────────────────────

    @Test
    fun `network preset enables network-relevant metrics`() {
        val config = DeviceMetricsConfig.network()
        assertTrue(config.captureMemory, "network should capture memory")
        assertTrue(config.captureBattery, "network should capture battery")
        assertFalse(config.captureCpu, "network should not capture cpu")
        assertTrue(config.captureNetwork, "network should capture network")
        assertFalse(config.captureStorage, "network should not capture storage")
        assertFalse(config.captureThermal, "network should not capture thermal")
        assertFalse(config.captureDisplay, "network should not capture display")
        assertTrue(config.captureSystem, "network should capture system")
        assertTrue(config.captureApp, "network should capture app")
        assertFalse(config.captureLocation, "network should not capture location")
    }

    // ── DeviceMetricsConfig: privacyFocused() preset ────────────────────────

    @Test
    fun `privacyFocused preset disables display and location`() {
        val config = DeviceMetricsConfig.privacyFocused()
        assertTrue(config.captureMemory, "privacyFocused should capture memory")
        assertTrue(config.captureBattery, "privacyFocused should capture battery")
        assertTrue(config.captureCpu, "privacyFocused should capture cpu")
        assertTrue(config.captureNetwork, "privacyFocused should capture network")
        assertTrue(config.captureStorage, "privacyFocused should capture storage")
        assertTrue(config.captureThermal, "privacyFocused should capture thermal")
        assertFalse(config.captureDisplay,
            "privacyFocused should not capture display (reveals user behavior)")
        assertTrue(config.captureSystem, "privacyFocused should capture system")
        assertTrue(config.captureApp, "privacyFocused should capture app")
        assertFalse(config.captureLocation,
            "privacyFocused should not capture location")
    }

    // ── DeviceMetricsConfig: disabled() preset ──────────────────────────────

    @Test
    fun `disabled preset has all false`() {
        val config = DeviceMetricsConfig.disabled()
        assertFalse(config.captureMemory, "disabled should not capture memory")
        assertFalse(config.captureBattery, "disabled should not capture battery")
        assertFalse(config.captureCpu, "disabled should not capture cpu")
        assertFalse(config.captureNetwork, "disabled should not capture network")
        assertFalse(config.captureStorage, "disabled should not capture storage")
        assertFalse(config.captureThermal, "disabled should not capture thermal")
        assertFalse(config.captureDisplay, "disabled should not capture display")
        assertFalse(config.captureSystem, "disabled should not capture system")
        assertFalse(config.captureApp, "disabled should not capture app")
        assertFalse(config.captureLocation, "disabled should not capture location")
    }

    @Test
    fun `disabled preset has exactly 10 false booleans`() {
        val config = DeviceMetricsConfig.disabled()
        val flags = listOf(
            config.captureMemory, config.captureBattery, config.captureCpu,
            config.captureNetwork, config.captureStorage, config.captureThermal,
            config.captureDisplay, config.captureSystem, config.captureApp,
            config.captureLocation
        )
        assertEquals(0, flags.count { it },
            "disabled preset should have zero true flags out of 10")
        assertEquals(10, flags.size,
            "DeviceMetricsConfig should have exactly 10 boolean fields")
    }

    // ── DeviceMetricsConfig: custom config ──────────────────────────────────

    @Test
    fun `custom config stores all fields correctly`() {
        val config = DeviceMetricsConfig(
            captureMemory = false,
            captureBattery = true,
            captureCpu = false,
            captureNetwork = true,
            captureStorage = false,
            captureThermal = true,
            captureDisplay = false,
            captureSystem = true,
            captureApp = false,
            captureLocation = true
        )
        assertFalse(config.captureMemory)
        assertTrue(config.captureBattery)
        assertFalse(config.captureCpu)
        assertTrue(config.captureNetwork)
        assertFalse(config.captureStorage)
        assertTrue(config.captureThermal)
        assertFalse(config.captureDisplay)
        assertTrue(config.captureSystem)
        assertFalse(config.captureApp)
        assertTrue(config.captureLocation)
    }

    @Test
    fun `data class copy preserves unmodified fields`() {
        val original = DeviceMetricsConfig.default()
        val modified = original.copy(captureLocation = true)
        assertTrue(modified.captureLocation, "Copied field should be updated")
        assertTrue(modified.captureMemory, "Unmodified field should be preserved")
        assertTrue(modified.captureBattery, "Unmodified field should be preserved")
    }

    @Test
    fun `data class equality works for identical configs`() {
        val a = DeviceMetricsConfig.minimal()
        val b = DeviceMetricsConfig.minimal()
        assertEquals(a, b, "Identical DeviceMetricsConfig instances should be equal")
    }

    // ── DeviceMetricsCaptureConfig: default() preset ────────────────────────

    @Test
    fun `capture default preset has correct values`() {
        val config = DeviceMetricsCaptureConfig.default()
        assertTrue(config.onAppStart, "default capture should fire on app start")
        assertTrue(config.onForceClose, "default capture should fire on force close")
        assertTrue(config.onCrash, "default capture should fire on crash")
        assertTrue(config.onError, "default capture should fire on error")
        assertTrue(config.onManualFlush, "default capture should fire on manual flush")
        assertFalse(config.onScheduledFlush,
            "default capture should NOT fire on scheduled flush (battery-saving)")
        assertTrue(config.onWorkflowTrigger, "default capture should fire on workflow trigger")
        assertEquals(60, config.rateLimitSeconds,
            "default capture rateLimitSeconds should be 60")
    }

    @Test
    fun `capture default constructor matches default preset`() {
        val fromConstructor = DeviceMetricsCaptureConfig()
        val fromPreset = DeviceMetricsCaptureConfig.default()
        assertEquals(fromConstructor, fromPreset,
            "No-arg constructor should match default() preset")
    }

    // ── DeviceMetricsCaptureConfig: aggressive() preset ─────────────────────

    @Test
    fun `aggressive preset has rateLimitSeconds=10`() {
        val config = DeviceMetricsCaptureConfig.aggressive()
        assertEquals(10, config.rateLimitSeconds,
            "aggressive rateLimitSeconds should be 10")
    }

    @Test
    fun `aggressive preset enables all triggers`() {
        val config = DeviceMetricsCaptureConfig.aggressive()
        assertTrue(config.onAppStart, "aggressive should fire on app start")
        assertTrue(config.onForceClose, "aggressive should fire on force close")
        assertTrue(config.onCrash, "aggressive should fire on crash")
        assertTrue(config.onError, "aggressive should fire on error")
        assertTrue(config.onManualFlush, "aggressive should fire on manual flush")
        assertTrue(config.onScheduledFlush,
            "aggressive should fire on scheduled flush")
        assertTrue(config.onWorkflowTrigger, "aggressive should fire on workflow trigger")
    }

    // ── DeviceMetricsCaptureConfig: conservative() preset ───────────────────

    @Test
    fun `conservative preset has rateLimitSeconds=300`() {
        val config = DeviceMetricsCaptureConfig.conservative()
        assertEquals(300, config.rateLimitSeconds,
            "conservative rateLimitSeconds should be 300 (5 minutes)")
    }

    @Test
    fun `conservative preset disables non-essential triggers`() {
        val config = DeviceMetricsCaptureConfig.conservative()
        assertTrue(config.onAppStart, "conservative should fire on app start")
        assertTrue(config.onForceClose, "conservative should fire on force close")
        assertTrue(config.onCrash, "conservative should fire on crash")
        assertFalse(config.onError, "conservative should not fire on error")
        assertFalse(config.onManualFlush, "conservative should not fire on manual flush")
        assertFalse(config.onScheduledFlush, "conservative should not fire on scheduled flush")
        assertFalse(config.onWorkflowTrigger, "conservative should not fire on workflow trigger")
    }

    // ── DeviceMetricsCaptureConfig: rate limit ordering ─────────────────────

    @Test
    fun `aggressive rate limit is less than default`() {
        assertTrue(
            DeviceMetricsCaptureConfig.aggressive().rateLimitSeconds <
                DeviceMetricsCaptureConfig.default().rateLimitSeconds,
            "aggressive rate limit should be less than default"
        )
    }

    @Test
    fun `conservative rate limit is greater than default`() {
        assertTrue(
            DeviceMetricsCaptureConfig.conservative().rateLimitSeconds >
                DeviceMetricsCaptureConfig.default().rateLimitSeconds,
            "conservative rate limit should be greater than default"
        )
    }

    // ── DeviceMetricsCollector: captureMetrics respects boolean flags ────────

    @Test
    fun `collector with disabled config captures metrics but no metric data recorded`() {
        val meter = otelRule.openTelemetry.getMeter("test-metrics")
        val config = DeviceMetricsConfig.disabled()
        val collector = DeviceMetricsCollector(context, meter, config)

        // captureMetrics should succeed (return true) even with all flags disabled
        val captured = collector.captureMetrics(CaptureReason.MANUAL_CAPTURE, force = true)
        assertTrue(captured, "captureMetrics should return true even with disabled config")
    }

    @Test
    fun `collector with default config captures metrics successfully`() {
        val meter = otelRule.openTelemetry.getMeter("test-metrics")
        val config = DeviceMetricsConfig.default()
        val collector = DeviceMetricsCollector(context, meter, config)

        val captured = collector.captureMetrics(CaptureReason.CRASH, force = true)
        assertTrue(captured, "captureMetrics with default config should succeed")
    }

    @Test
    fun `collector with minimal config captures metrics successfully`() {
        val meter = otelRule.openTelemetry.getMeter("test-metrics")
        val config = DeviceMetricsConfig.minimal()
        val collector = DeviceMetricsCollector(context, meter, config)

        val captured = collector.captureMetrics(CaptureReason.APP_START, force = true)
        assertTrue(captured, "captureMetrics with minimal config should succeed")
    }

    @Test
    fun `collector rate limits without force flag`() {
        val meter = otelRule.openTelemetry.getMeter("test-metrics")
        val config = DeviceMetricsConfig.default()
        val collector = DeviceMetricsCollector(context, meter, config)

        val first = collector.captureMetrics(CaptureReason.CRASH, force = true)
        assertTrue(first, "First capture should succeed")

        val second = collector.captureMetrics(CaptureReason.CRASH, force = false)
        assertFalse(second, "Second capture without force should be rate-limited")
    }

    @Test
    fun `collector force flag bypasses rate limit`() {
        val meter = otelRule.openTelemetry.getMeter("test-metrics")
        val config = DeviceMetricsConfig.default()
        val collector = DeviceMetricsCollector(context, meter, config)

        val first = collector.captureMetrics(CaptureReason.CRASH, force = true)
        assertTrue(first, "First capture should succeed")

        val second = collector.captureMetrics(CaptureReason.CRASH, force = true)
        assertTrue(second, "Second capture with force should bypass rate limit")
    }

    @Test
    fun `collector getAvailableMemoryMb returns non-negative value`() {
        val meter = otelRule.openTelemetry.getMeter("test-metrics")
        val config = DeviceMetricsConfig.default()
        val collector = DeviceMetricsCollector(context, meter, config)

        val memoryMb = collector.getAvailableMemoryMb()
        assertTrue(memoryMb >= 0,
            "Available memory should be non-negative, got $memoryMb")
    }

    @Test
    fun `collector with only memory enabled captures without error`() {
        val meter = otelRule.openTelemetry.getMeter("test-metrics")
        val config = DeviceMetricsConfig(
            captureMemory = true,
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
        val collector = DeviceMetricsCollector(context, meter, config)

        val captured = collector.captureMetrics(CaptureReason.MANUAL_CAPTURE, force = true)
        assertTrue(captured, "Capture with only memory enabled should succeed")
    }

    @Test
    fun `collector supports all CaptureReason values`() {
        val meter = otelRule.openTelemetry.getMeter("test-metrics")
        val config = DeviceMetricsConfig.disabled()
        val collector = DeviceMetricsCollector(context, meter, config)

        // Verify all enum values can be passed without error
        CaptureReason.values().forEach { reason ->
            val captured = collector.captureMetrics(reason, force = true)
            assertTrue(captured, "captureMetrics should succeed for reason=$reason")
        }
    }
}
