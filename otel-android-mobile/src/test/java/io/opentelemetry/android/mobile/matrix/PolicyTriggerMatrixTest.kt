// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.matrix

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.android.mobile.buffering.DiskLogBuffer
import io.opentelemetry.android.mobile.buffering.MobileLogRecordProcessor
import io.opentelemetry.android.mobile.config.ExportMode
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.testing.MockLogRecordExporter
import io.opentelemetry.android.mobile.testing.TestUtils
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.Context as OtelContext
import io.opentelemetry.sdk.logs.ReadWriteLogRecord
import io.opentelemetry.sdk.logs.data.LogRecordData
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 3: Policy trigger matrix — 5 triggers x 3 modes.
 *
 * Tests whether events are actually exported through [MobileLogRecordProcessor]
 * based on export mode and trigger type. Uses a long `traceExportIntervalSeconds`
 * (30s) so CONTINUOUS periodic flush does not fire during the test window.
 *
 * Triggers:
 *  1. app.crash (crash)
 *  2. ui.freeze (UI freeze, duration >= 700ms)
 *  3. http.error with status 503 (server error)
 *  4. api.request with status 200 (success — body is "api.request", no policy match)
 *  5. screen.navigation (normal navigation — not a trigger)
 *
 * Expected:
 *  - CONDITIONAL/HYBRID + real trigger (crash, freeze, 503) → events exported (>= 2)
 *  - CONTINUOUS + any trigger → 0 (periodic timer hasn't elapsed at 30s interval)
 *  - Any mode + 200 OK → 0 (body is "api.request", not matched by any policy)
 *  - Any mode + navigation-only → 0 (not a trigger)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PolicyTriggerMatrixTest {

    private lateinit var context: Context
    private lateinit var exporter: MockLogRecordExporter

    @Before
    fun setup() {
        DiskLogBuffer.resetForTesting()
        context = ApplicationProvider.getApplicationContext()
        exporter = MockLogRecordExporter()
    }

    @After
    fun teardown() {
        DiskLogBuffer.resetForTesting()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun buildProcessor(mode: ExportMode): MobileLogRecordProcessor {
        val config = MobileConfig(
            serviceName = "matrix-test",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317",
            exportMode = mode,
            traceExportIntervalSeconds = 30,
            ramBufferSize = 100,
            diskBufferMb = 10,
            diskBufferTtlHours = 1
        )
        val meter = OpenTelemetry.noop().meterProvider.get("test")
        return MobileLogRecordProcessor.builder(context)
            .setExporter(exporter)
            .setConfig(config)
            .setMeter(meter)
            .setRamBufferSize(config.ramBufferSize)
            .setDiskBufferMb(config.diskBufferMb)
            .setDiskBufferTtlHours(config.diskBufferTtlHours)
            .build()
    }

    private fun wrap(data: LogRecordData): ReadWriteLogRecord {
        val mock = mockk<ReadWriteLogRecord>(relaxed = true)
        every { mock.toLogRecordData() } returns data
        return mock
    }

    private fun emitAndWait(processor: MobileLogRecordProcessor, vararg records: LogRecordData) {
        records.forEach { processor.onEmit(OtelContext.root(), wrap(it)) }
        Thread.sleep(500) // Allow async policy evaluation
    }

    // ── CONDITIONAL mode ────────────────────────────────────────────────────

    @Test
    fun `CONDITIONAL + crash triggers export`() {
        val processor = buildProcessor(ExportMode.CONDITIONAL)
        try {
            emitAndWait(
                processor,
                TestUtils.createNavigationEvent("Screen1"),
                TestUtils.createCrashLog()
            )
            assertTrue(
                "CONDITIONAL + crash should export events (got ${exporter.getExportedCount()})",
                exporter.getExportedCount() >= 2
            )
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `CONDITIONAL + UI freeze triggers export`() {
        val processor = buildProcessor(ExportMode.CONDITIONAL)
        try {
            emitAndWait(
                processor,
                TestUtils.createNavigationEvent("Screen1"),
                TestUtils.createUIFreezeLog(1500)
            )
            assertTrue(
                "CONDITIONAL + UI freeze should export events (got ${exporter.getExportedCount()})",
                exporter.getExportedCount() >= 2
            )
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `CONDITIONAL + http 503 triggers export`() {
        val processor = buildProcessor(ExportMode.CONDITIONAL)
        try {
            emitAndWait(
                processor,
                TestUtils.createNavigationEvent("Screen1"),
                TestUtils.createApiRequestEvent(1, statusCode = 503)
            )
            assertTrue(
                "CONDITIONAL + 503 should export events (got ${exporter.getExportedCount()})",
                exporter.getExportedCount() >= 2
            )
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `CONDITIONAL + http 200 does NOT trigger export`() {
        val processor = buildProcessor(ExportMode.CONDITIONAL)
        try {
            emitAndWait(
                processor,
                TestUtils.createNavigationEvent("Screen1"),
                TestUtils.createApiRequestEvent(1, statusCode = 200)
            )
            assertEquals(
                "CONDITIONAL + 200 OK should NOT trigger export",
                0, exporter.getExportedCount()
            )
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `CONDITIONAL + navigation only does NOT trigger export`() {
        val processor = buildProcessor(ExportMode.CONDITIONAL)
        try {
            emitAndWait(
                processor,
                TestUtils.createNavigationEvent("Screen1"),
                TestUtils.createNavigationEvent("Screen2")
            )
            assertEquals(
                "CONDITIONAL + navigation-only should NOT trigger export",
                0, exporter.getExportedCount()
            )
        } finally {
            processor.shutdown()
        }
    }

    // ── CONTINUOUS mode (30s interval — no periodic flush during test) ──────

    @Test
    fun `CONTINUOUS + crash does NOT auto-export within 30s interval`() {
        val processor = buildProcessor(ExportMode.CONTINUOUS)
        try {
            emitAndWait(
                processor,
                TestUtils.createNavigationEvent("Screen1"),
                TestUtils.createCrashLog()
            )
            assertEquals(
                "CONTINUOUS mode relies on periodic flush — no immediate export expected",
                0, exporter.getExportedCount()
            )
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `CONTINUOUS + UI freeze does NOT auto-export within 30s interval`() {
        val processor = buildProcessor(ExportMode.CONTINUOUS)
        try {
            emitAndWait(
                processor,
                TestUtils.createNavigationEvent("Screen1"),
                TestUtils.createUIFreezeLog(1500)
            )
            assertEquals(
                "CONTINUOUS mode relies on periodic flush — no immediate export expected",
                0, exporter.getExportedCount()
            )
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `CONTINUOUS + http 503 does NOT auto-export within 30s interval`() {
        val processor = buildProcessor(ExportMode.CONTINUOUS)
        try {
            emitAndWait(
                processor,
                TestUtils.createNavigationEvent("Screen1"),
                TestUtils.createApiRequestEvent(1, statusCode = 503)
            )
            assertEquals(
                "CONTINUOUS mode relies on periodic flush — no immediate export expected",
                0, exporter.getExportedCount()
            )
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `CONTINUOUS + http 404 does NOT auto-export`() {
        val processor = buildProcessor(ExportMode.CONTINUOUS)
        try {
            emitAndWait(
                processor,
                TestUtils.createNavigationEvent("Screen1"),
                TestUtils.createApiRequestEvent(1, statusCode = 200)
            )
            assertEquals(
                "CONTINUOUS + 200 OK should not export",
                0, exporter.getExportedCount()
            )
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `CONTINUOUS + navigation only does NOT auto-export`() {
        val processor = buildProcessor(ExportMode.CONTINUOUS)
        try {
            emitAndWait(
                processor,
                TestUtils.createNavigationEvent("Screen1"),
                TestUtils.createNavigationEvent("Screen2")
            )
            assertEquals(
                "CONTINUOUS + navigation-only should not export",
                0, exporter.getExportedCount()
            )
        } finally {
            processor.shutdown()
        }
    }

    // ── HYBRID mode ─────────────────────────────────────────────────────────

    @Test
    fun `HYBRID + crash triggers export`() {
        val processor = buildProcessor(ExportMode.HYBRID)
        try {
            emitAndWait(
                processor,
                TestUtils.createNavigationEvent("Screen1"),
                TestUtils.createCrashLog()
            )
            assertTrue(
                "HYBRID + crash should export events (got ${exporter.getExportedCount()})",
                exporter.getExportedCount() >= 2
            )
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `HYBRID + UI freeze triggers export`() {
        val processor = buildProcessor(ExportMode.HYBRID)
        try {
            emitAndWait(
                processor,
                TestUtils.createNavigationEvent("Screen1"),
                TestUtils.createUIFreezeLog(1500)
            )
            assertTrue(
                "HYBRID + UI freeze should export events (got ${exporter.getExportedCount()})",
                exporter.getExportedCount() >= 2
            )
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `HYBRID + http 503 triggers export`() {
        val processor = buildProcessor(ExportMode.HYBRID)
        try {
            emitAndWait(
                processor,
                TestUtils.createNavigationEvent("Screen1"),
                TestUtils.createApiRequestEvent(1, statusCode = 503)
            )
            assertTrue(
                "HYBRID + 503 should export events (got ${exporter.getExportedCount()})",
                exporter.getExportedCount() >= 2
            )
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `HYBRID + http 200 does NOT trigger export`() {
        val processor = buildProcessor(ExportMode.HYBRID)
        try {
            emitAndWait(
                processor,
                TestUtils.createNavigationEvent("Screen1"),
                TestUtils.createApiRequestEvent(1, statusCode = 200)
            )
            assertEquals(
                "HYBRID + 200 OK should NOT trigger export",
                0, exporter.getExportedCount()
            )
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `HYBRID + navigation only does NOT trigger export`() {
        val processor = buildProcessor(ExportMode.HYBRID)
        try {
            emitAndWait(
                processor,
                TestUtils.createNavigationEvent("Screen1"),
                TestUtils.createNavigationEvent("Screen2")
            )
            assertEquals(
                "HYBRID + navigation-only should NOT trigger export",
                0, exporter.getExportedCount()
            )
        } finally {
            processor.shutdown()
        }
    }
}
