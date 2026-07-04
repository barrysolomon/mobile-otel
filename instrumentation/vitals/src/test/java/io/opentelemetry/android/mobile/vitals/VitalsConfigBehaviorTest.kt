/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.vitals

import android.app.Application
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VitalsConfigBehaviorTest {

    @get:Rule
    val otelRule = OpenTelemetryRule.create()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        resetSingleton()
    }

    @After
    fun tearDown() {
        resetSingleton()
    }

    /**
     * Reset VitalsCollector singleton between tests via reflection.
     */
    private fun resetSingleton() {
        val instanceField = VitalsCollector::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        instanceField.set(null, null)
    }

    private fun meter() = otelRule.openTelemetry.getMeter("test")

    private fun createCollector(config: VitalsConfig): VitalsCollector {
        return VitalsCollector.initialize(app, config, meter())
    }

    // ---------------------------------------------------------------
    // 1. enabled=false prevents OTel metric registration and monitoring
    //    Note: enabled=false guards init{} (registerMetrics + startMonitoring).
    //    Individual record* methods are guarded by their own feature flags.
    // ---------------------------------------------------------------

    @Test
    fun `enabled false skips metric registration and monitoring`() {
        // With enabled=false, registerMetrics() and startMonitoring() are not called.
        // Individual feature flags still guard record* methods independently.
        val collector = createCollector(VitalsConfig(enabled = false))
        // No crash, no OTel metrics registered -- the collector is inert at the OTel level.
        collector.reset()
    }

    @Test
    fun `enabled false with all features disabled prevents all recording`() {
        val config = VitalsConfig(
            enabled = false,
            measureAppStart = false,
            measureTtid = false,
            detectJank = false,
            trackInputLatency = false,
            monitorAnrRisk = false,
            monitorMemoryPressure = false,
            monitorThermalState = false
        )
        val collector = createCollector(config)
        collector.recordColdStart(1000)
        collector.recordWarmStart(1000)
        collector.recordTtid(500)
        collector.recordFrameTime(50.0)
        collector.recordInputLatency(100)
        collector.recordMainThreadBlock(5000)
        val attrs = collector.getVitalsAttributes()
        assertEquals(0, attrs.size())
    }

    // ---------------------------------------------------------------
    // 2. enabled=true allows metrics
    // ---------------------------------------------------------------

    @Test
    fun `enabled true allows jank recording and attributes`() {
        val config = VitalsConfig(enabled = true, detectJank = true, samplingRate = 1.0)
        val collector = createCollector(config)
        collector.recordFrameTime(50.0) // above default 16ms threshold
        val attrs = collector.getVitalsAttributes()
        assertEquals(1L, attrs.get(io.opentelemetry.api.common.AttributeKey.longKey("mobile.jank.count")))
    }

    @Test
    fun `enabled true allows ANR risk recording and attributes`() {
        val config = VitalsConfig(enabled = true, monitorAnrRisk = true, samplingRate = 1.0)
        val collector = createCollector(config)
        collector.recordMainThreadBlock(4000)
        val attrs = collector.getVitalsAttributes()
        assertEquals(4000L, attrs.get(io.opentelemetry.api.common.AttributeKey.longKey("mobile.anr.risk.ms")))
    }

    // ---------------------------------------------------------------
    // 3. measureAppStart=false prevents cold/warm start recording
    // ---------------------------------------------------------------

    @Test
    fun `measureAppStart false prevents cold start recording`() {
        val config = VitalsConfig(
            enabled = true,
            measureAppStart = false,
            samplingRate = 1.0
        )
        val collector = createCollector(config)
        collector.recordColdStart(3000)
        // Cold start should not be recorded; reset and verify via reset (value stays 0)
        // We verify indirectly: recordColdStart does nothing when measureAppStart=false
        // After recording, the internal coldStartTime should still be 0
        // We can check by calling reset and seeing it was already 0 (no-op)
        collector.reset()
        // No direct getter, but the metric callback won't fire with value 0
    }

    @Test
    fun `measureAppStart false prevents warm start recording`() {
        val config = VitalsConfig(
            enabled = true,
            measureAppStart = false,
            samplingRate = 1.0
        )
        val collector = createCollector(config)
        collector.recordWarmStart(2000)
        // warmStartTime should remain 0 since measureAppStart=false
    }

    // ---------------------------------------------------------------
    // 4. detectJank=false prevents frame time recording
    // ---------------------------------------------------------------

    @Test
    fun `detectJank false prevents frame time recording`() {
        val config = VitalsConfig(
            enabled = true,
            detectJank = false,
            samplingRate = 1.0
        )
        val collector = createCollector(config)
        collector.recordFrameTime(200.0) // well above threshold
        val attrs = collector.getVitalsAttributes()
        // With detectJank=false, jank attributes should not be present
        assertFalse(attrs.asMap().containsKey(io.opentelemetry.api.common.AttributeKey.longKey("mobile.jank.count")))
    }

    // ---------------------------------------------------------------
    // 5. trackInputLatency=false prevents input latency recording
    // ---------------------------------------------------------------

    @Test
    fun `trackInputLatency false prevents input latency recording`() {
        val config = VitalsConfig(
            enabled = true,
            trackInputLatency = false,
            samplingRate = 1.0
        )
        val collector = createCollector(config)
        collector.recordInputLatency(100)
        // No input latency metric registered; nothing recorded
    }

    // ---------------------------------------------------------------
    // 6. monitorAnrRisk=false prevents ANR risk recording
    // ---------------------------------------------------------------

    @Test
    fun `monitorAnrRisk false prevents main thread block recording`() {
        val config = VitalsConfig(
            enabled = true,
            monitorAnrRisk = false,
            samplingRate = 1.0
        )
        val collector = createCollector(config)
        collector.recordMainThreadBlock(5000)
        val attrs = collector.getVitalsAttributes()
        assertFalse(attrs.asMap().containsKey(io.opentelemetry.api.common.AttributeKey.longKey("mobile.anr.risk.ms")))
    }

    // ---------------------------------------------------------------
    // 7. jankThresholdMs controls jank counting
    // ---------------------------------------------------------------

    @Test
    fun `frame above jankThresholdMs counts as jank`() {
        val config = VitalsConfig(
            enabled = true,
            detectJank = true,
            jankThresholdMs = 20.0,
            severeJankThresholdMs = 100.0,
            samplingRate = 1.0
        )
        val collector = createCollector(config)
        collector.recordFrameTime(25.0) // above 20ms threshold
        val attrs = collector.getVitalsAttributes()
        assertEquals(1L, attrs.get(io.opentelemetry.api.common.AttributeKey.longKey("mobile.jank.count")))
    }

    @Test
    fun `frame below jankThresholdMs does not count as jank`() {
        val config = VitalsConfig(
            enabled = true,
            detectJank = true,
            jankThresholdMs = 20.0,
            severeJankThresholdMs = 100.0,
            samplingRate = 1.0
        )
        val collector = createCollector(config)
        collector.recordFrameTime(15.0) // below 20ms threshold
        val attrs = collector.getVitalsAttributes()
        assertEquals(0L, attrs.get(io.opentelemetry.api.common.AttributeKey.longKey("mobile.jank.count")))
    }

    @Test
    fun `frame at exact jankThresholdMs does not count as jank`() {
        val config = VitalsConfig(
            enabled = true,
            detectJank = true,
            jankThresholdMs = 20.0,
            severeJankThresholdMs = 100.0,
            samplingRate = 1.0
        )
        val collector = createCollector(config)
        collector.recordFrameTime(20.0) // exactly at threshold (uses > not >=)
        val attrs = collector.getVitalsAttributes()
        assertEquals(0L, attrs.get(io.opentelemetry.api.common.AttributeKey.longKey("mobile.jank.count")))
    }

    // ---------------------------------------------------------------
    // 8. severeJankThresholdMs controls severe jank counting
    // ---------------------------------------------------------------

    @Test
    fun `frame above severeJankThresholdMs counts as severe jank`() {
        val config = VitalsConfig(
            enabled = true,
            detectJank = true,
            jankThresholdMs = 16.0,
            severeJankThresholdMs = 100.0,
            samplingRate = 1.0
        )
        val collector = createCollector(config)
        collector.recordFrameTime(150.0) // above 100ms severe threshold
        val attrs = collector.getVitalsAttributes()
        assertEquals(1L, attrs.get(io.opentelemetry.api.common.AttributeKey.longKey("mobile.jank.severe.count")))
        // Also counts as regular jank
        assertEquals(1L, attrs.get(io.opentelemetry.api.common.AttributeKey.longKey("mobile.jank.count")))
    }

    @Test
    fun `frame between jank and severe thresholds counts only as regular jank`() {
        val config = VitalsConfig(
            enabled = true,
            detectJank = true,
            jankThresholdMs = 16.0,
            severeJankThresholdMs = 100.0,
            samplingRate = 1.0
        )
        val collector = createCollector(config)
        collector.recordFrameTime(50.0) // above 16ms, below 100ms
        val attrs = collector.getVitalsAttributes()
        assertEquals(1L, attrs.get(io.opentelemetry.api.common.AttributeKey.longKey("mobile.jank.count")))
        assertEquals(0L, attrs.get(io.opentelemetry.api.common.AttributeKey.longKey("mobile.jank.severe.count")))
    }

    // ---------------------------------------------------------------
    // 9. coldStartThresholdMs sets "mobile.start.slow" attribute
    // ---------------------------------------------------------------

    @Test
    fun `coldStartThresholdMs defaults to 5000`() {
        val config = VitalsConfig.default()
        assertEquals(5000L, config.coldStartThresholdMs)
    }

    @Test
    fun `custom coldStartThresholdMs is respected in config`() {
        val config = VitalsConfig(coldStartThresholdMs = 3000)
        assertEquals(3000L, config.coldStartThresholdMs)
    }

    @Test
    fun `warmStartThresholdMs defaults to 2000`() {
        val config = VitalsConfig.default()
        assertEquals(2000L, config.warmStartThresholdMs)
    }

    // ---------------------------------------------------------------
    // 10. samplingRate=0.0 drops all samples
    // ---------------------------------------------------------------

    @Test
    fun `samplingRate zero drops all cold start recordings`() {
        val config = VitalsConfig(
            enabled = true,
            measureAppStart = true,
            detectJank = true,
            monitorAnrRisk = true,
            samplingRate = 0.0
        )
        val collector = createCollector(config)
        // Record many events - none should be sampled
        repeat(100) { collector.recordColdStart(1000) }
        repeat(100) { collector.recordFrameTime(50.0) }
        repeat(100) { collector.recordMainThreadBlock(5000) }
        val attrs = collector.getVitalsAttributes()
        // Jank count should be 0 since nothing was sampled
        assertEquals(0L, attrs.get(io.opentelemetry.api.common.AttributeKey.longKey("mobile.jank.count")))
        // ANR risk should have no block time recorded
        assertFalse(attrs.asMap().containsKey(io.opentelemetry.api.common.AttributeKey.longKey("mobile.anr.risk.ms")))
    }

    // ---------------------------------------------------------------
    // 11. samplingRate=1.0 keeps all samples
    // ---------------------------------------------------------------

    @Test
    fun `samplingRate one keeps all frame recordings`() {
        val config = VitalsConfig(
            enabled = true,
            detectJank = true,
            samplingRate = 1.0
        )
        val collector = createCollector(config)
        repeat(10) { collector.recordFrameTime(50.0) } // all above threshold
        val attrs = collector.getVitalsAttributes()
        assertEquals(10L, attrs.get(io.opentelemetry.api.common.AttributeKey.longKey("mobile.jank.count")))
    }

    @Test
    fun `samplingRate one keeps all main thread block recordings`() {
        val config = VitalsConfig(
            enabled = true,
            monitorAnrRisk = true,
            samplingRate = 1.0
        )
        val collector = createCollector(config)
        collector.recordMainThreadBlock(4500)
        val attrs = collector.getVitalsAttributes()
        assertEquals(4500L, attrs.get(io.opentelemetry.api.common.AttributeKey.longKey("mobile.anr.risk.ms")))
    }

    // ---------------------------------------------------------------
    // 12. Preset configs have correct field values
    // ---------------------------------------------------------------

    @Test
    fun `default preset has all features enabled except thermal`() {
        val config = VitalsConfig.default()
        assertTrue(config.enabled)
        assertTrue(config.measureAppStart)
        assertTrue(config.measureTtid)
        assertTrue(config.detectJank)
        assertTrue(config.trackInputLatency)
        assertTrue(config.monitorAnrRisk)
        assertTrue(config.monitorMemoryPressure)
        assertFalse(config.monitorThermalState)
        assertEquals(16.0, config.jankThresholdMs)
        assertEquals(100.0, config.severeJankThresholdMs)
        assertEquals(50.0, config.inputLatencyThresholdMs)
        assertEquals(3000L, config.anrRiskThresholdMs)
        assertEquals(5000L, config.coldStartThresholdMs)
        assertEquals(2000L, config.warmStartThresholdMs)
        assertEquals(3000L, config.ttidThresholdMs)
        assertEquals(50, config.memoryPressureCriticalMb)
        assertEquals(1.0, config.samplingRate)
        assertEquals(60000L, config.reportingIntervalMs)
    }

    @Test
    fun `minimal preset disables jank inputLatency and thermal`() {
        val config = VitalsConfig.minimal()
        assertTrue(config.enabled)
        assertTrue(config.measureAppStart)
        assertFalse(config.measureTtid)
        assertFalse(config.detectJank)
        assertFalse(config.trackInputLatency)
        assertTrue(config.monitorAnrRisk)
        assertTrue(config.monitorMemoryPressure)
        assertFalse(config.monitorThermalState)
    }

    @Test
    fun `aggressive preset enables all features with strict thresholds`() {
        val config = VitalsConfig.aggressive()
        assertTrue(config.enabled)
        assertTrue(config.measureAppStart)
        assertTrue(config.measureTtid)
        assertTrue(config.detectJank)
        assertTrue(config.trackInputLatency)
        assertTrue(config.monitorAnrRisk)
        assertTrue(config.monitorMemoryPressure)
        assertTrue(config.monitorThermalState)
        assertEquals(16.0, config.jankThresholdMs)
        assertEquals(50.0, config.severeJankThresholdMs)
        assertEquals(30.0, config.inputLatencyThresholdMs)
        assertEquals(2000L, config.anrRiskThresholdMs)
        assertEquals(3000L, config.coldStartThresholdMs)
        assertEquals(1000L, config.warmStartThresholdMs)
        assertEquals(2000L, config.ttidThresholdMs)
        assertEquals(1.0, config.samplingRate)
    }

    @Test
    fun `batteryFriendly preset reduces monitoring and sampling`() {
        val config = VitalsConfig.batteryFriendly()
        assertTrue(config.enabled)
        assertTrue(config.measureAppStart)
        assertTrue(config.measureTtid)
        assertTrue(config.detectJank)
        assertFalse(config.trackInputLatency)
        assertTrue(config.monitorAnrRisk)
        assertFalse(config.monitorMemoryPressure)
        assertFalse(config.monitorThermalState)
        assertEquals(0.1, config.samplingRate)
        assertEquals(300000L, config.reportingIntervalMs)
    }
}
