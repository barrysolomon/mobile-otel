/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.vitals

import io.mockk.*
import io.opentelemetry.api.logs.Logger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Tests that [VitalsConfig] flags and thresholds actually control [JankDetector] behaviour.
 *
 * Covers:
 * - [VitalsConfig.detectJank] = false prevents monitoring from starting
 * - [VitalsConfig.jankThresholdMs] controls which frames are classified as jank
 * - [VitalsConfig.severeJankThresholdMs] controls which frames are classified as severe
 * - Frame timing: `doFrame()` called directly with synthetic timestamps
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class JankDetectorConfigTest {

    private lateinit var mockLogger: Logger
    private lateinit var mockVitalsCollector: VitalsCollector

    @Before
    fun setup() {
        JankDetector.resetForTesting()
        mockLogger = mockk(relaxed = true)
        mockVitalsCollector = mockk(relaxed = true)
    }

    @After
    fun teardown() {
        JankDetector.resetForTesting()
    }

    // ── detectJank flag ───────────────────────────────────────────────────────

    @Test
    fun `detectJank false - no jank accumulated even after many frames`() {
        val config = VitalsConfig(detectJank = false)
        val detector = JankDetector.initialize(config, mockVitalsCollector, mockLogger)

        // With detectJank=false, doFrame() returns immediately without counting jank
        val now = System.nanoTime()
        detector.doFrame(now)
        detector.doFrame(now + TimeUnit.MILLISECONDS.toNanos(200)) // would be severe if enabled

        assertEquals("No jank should be counted when detectJank=false",
            0L, detector.getStatistics().consecutiveJanks)
    }

    @Test
    fun `detectJank false - doFrame callback does not report frame time`() {
        val config = VitalsConfig(detectJank = false)
        val detector = JankDetector.initialize(config, mockVitalsCollector, mockLogger)

        // Simulate frame callbacks directly (bypasses Choreographer)
        val now = System.nanoTime()
        detector.doFrame(now)
        detector.doFrame(now + TimeUnit.MILLISECONDS.toNanos(100))

        // Since detectJank=false, isMonitoring is false, doFrame returns early without recording
        verify(exactly = 0) { mockVitalsCollector.recordFrameTime(any()) }
    }

    @Test
    fun `detectJank true - doFrame reports frame times to vitals collector`() {
        val config = VitalsConfig(
            detectJank = true,
            jankThresholdMs = 16.0,
            severeJankThresholdMs = 100.0
        )
        // Since singleton initializes with startMonitoring(), isMonitoring=true
        val detector = JankDetector.initialize(config, mockVitalsCollector, mockLogger)

        val now = System.nanoTime()
        detector.doFrame(now)
        val nextFrame = now + TimeUnit.MILLISECONDS.toNanos(20) // 20ms — jank frame
        detector.doFrame(nextFrame)

        // Frame time should be recorded
        verify(atLeast = 1) { mockVitalsCollector.recordFrameTime(any()) }
    }

    // ── jankThresholdMs controls what is classified as jank ──────────────────

    @Test
    fun `jankThresholdMs 16 - 20ms frame triggers jank log`() {
        val config = VitalsConfig(
            detectJank = true,
            jankThresholdMs = 16.0,
            severeJankThresholdMs = 100.0
        )
        val detector = JankDetector.initialize(config, mockVitalsCollector, mockLogger)

        val now = System.nanoTime()
        detector.doFrame(now)
        detector.doFrame(now + TimeUnit.MILLISECONDS.toNanos(20)) // 20ms > 16ms threshold

        // jank should increment consecutiveJanks
        assertTrue("20ms frame must be classified as jank with 16ms threshold",
            detector.getStatistics().consecutiveJanks > 0)
    }

    @Test
    fun `jankThresholdMs 32 - 20ms frame does NOT trigger jank`() {
        JankDetector.resetForTesting()
        val config = VitalsConfig(
            detectJank = true,
            jankThresholdMs = 32.0,
            severeJankThresholdMs = 200.0
        )
        val detector = JankDetector.initialize(config, mockVitalsCollector, mockLogger)
        detector.reset()

        val now = System.nanoTime()
        detector.doFrame(now)
        detector.doFrame(now + TimeUnit.MILLISECONDS.toNanos(20)) // 20ms < 32ms threshold

        assertEquals("20ms frame must NOT be jank when threshold is 32ms",
            0L, detector.getStatistics().consecutiveJanks)
    }

    @Test
    fun `jankThresholdMs 32 - 40ms frame DOES trigger jank`() {
        JankDetector.resetForTesting()
        val config = VitalsConfig(
            detectJank = true,
            jankThresholdMs = 32.0,
            severeJankThresholdMs = 200.0
        )
        val detector = JankDetector.initialize(config, mockVitalsCollector, mockLogger)
        detector.reset()

        val now = System.nanoTime()
        detector.doFrame(now)
        detector.doFrame(now + TimeUnit.MILLISECONDS.toNanos(40)) // 40ms > 32ms threshold

        assertTrue("40ms frame must be classified as jank with 32ms threshold",
            detector.getStatistics().consecutiveJanks > 0)
    }

    // ── severeJankThresholdMs ─────────────────────────────────────────────────

    @Test
    fun `severeJankThresholdMs 200 - 100ms frame is logged as NOT severe`() {
        val logRecords = mutableListOf<String>()
        val capturingLogger = mockk<Logger>(relaxed = true)
        var capturedSeverity: io.opentelemetry.api.logs.Severity? = null

        val mockBuilder = mockk<io.opentelemetry.api.logs.LogRecordBuilder>(relaxed = true)
        every { capturingLogger.logRecordBuilder() } returns mockBuilder
        every { mockBuilder.setBody(any<String>()) } returns mockBuilder
        every { mockBuilder.setSeverity(any()) } answers {
            capturedSeverity = firstArg()
            mockBuilder
        }
        every { mockBuilder.setAllAttributes(any()) } returns mockBuilder

        JankDetector.resetForTesting()
        val config = VitalsConfig(
            detectJank = true,
            jankThresholdMs = 16.0,
            severeJankThresholdMs = 200.0
        )
        val detector = JankDetector.initialize(config, null, capturingLogger)
        detector.reset()

        val now = System.nanoTime()
        detector.doFrame(now)
        detector.doFrame(now + TimeUnit.MILLISECONDS.toNanos(100)) // 100ms < 200ms severe threshold

        // Should log as INFO (non-severe), not WARN (severe)
        assertEquals(
            "100ms frame must be INFO (non-severe) with severeThreshold=200ms",
            io.opentelemetry.api.logs.Severity.INFO, capturedSeverity
        )
    }

    @Test
    fun `severeJankThresholdMs 50 - 100ms frame IS logged as severe`() {
        var capturedSeverity: io.opentelemetry.api.logs.Severity? = null
        val capturingLogger = mockk<Logger>(relaxed = true)
        val mockBuilder = mockk<io.opentelemetry.api.logs.LogRecordBuilder>(relaxed = true)
        every { capturingLogger.logRecordBuilder() } returns mockBuilder
        every { mockBuilder.setBody(any<String>()) } returns mockBuilder
        every { mockBuilder.setSeverity(any()) } answers {
            capturedSeverity = firstArg()
            mockBuilder
        }
        every { mockBuilder.setAllAttributes(any()) } returns mockBuilder

        JankDetector.resetForTesting()
        val config = VitalsConfig(
            detectJank = true,
            jankThresholdMs = 16.0,
            severeJankThresholdMs = 50.0
        )
        val detector = JankDetector.initialize(config, null, capturingLogger)
        detector.reset()

        val now = System.nanoTime()
        detector.doFrame(now)
        detector.doFrame(now + TimeUnit.MILLISECONDS.toNanos(100)) // 100ms >= 50ms severe threshold

        assertEquals(
            "100ms frame must be WARN (severe) with severeThreshold=50ms",
            io.opentelemetry.api.logs.Severity.WARN, capturedSeverity
        )
    }
}
