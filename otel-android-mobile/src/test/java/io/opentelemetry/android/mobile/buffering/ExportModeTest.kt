/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
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
 * Tests for the three export modes: CONDITIONAL, CONTINUOUS, and HYBRID.
 *
 * ## Mode summary
 *
 * | Mode        | Periodic flush | Policy-triggered flush |
 * |-------------|---------------|------------------------|
 * | CONDITIONAL | No            | Yes (flushWindow)       |
 * | CONTINUOUS  | Yes           | Yes (also fires)        |
 * | HYBRID      | Yes           | Yes (flushWindow)       |
 *
 * CONDITIONAL is the most battery-efficient mode: events sit in the buffer until
 * a matching export policy is triggered (e.g. UI freeze, crash). CONTINUOUS adds
 * a fixed-schedule periodic export so traces always flow. HYBRID combines both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExportModeTest {

    private lateinit var context: Context
    private lateinit var mockExporter: MockLogRecordExporter

    @Before
    fun setup() {
        DiskLogBuffer.resetForTesting()
        context = ApplicationProvider.getApplicationContext()
        mockExporter = MockLogRecordExporter()
    }

    @After
    fun teardown() {
        DiskLogBuffer.resetForTesting()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun buildProcessor(
        exportMode: ExportMode,
        traceExportIntervalSeconds: Long = 30,
        metricExportIntervalSeconds: Long = 60
    ): MobileLogRecordProcessor {
        val config = MobileConfig(
            serviceName = "test-service",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317",
            exportMode = exportMode,
            traceExportIntervalSeconds = traceExportIntervalSeconds,
            metricExportIntervalSeconds = metricExportIntervalSeconds,
            ramBufferSize = 100,
            diskBufferMb = 10,
            diskBufferTtlHours = 1
        )
        val meter = OpenTelemetry.noop().meterProvider.get("test")
        return MobileLogRecordProcessor.builder(context)
            .setExporter(mockExporter)
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

    // ── CONDITIONAL mode ─────────────────────────────────────────────────────

    @Test
    fun `CONDITIONAL - normal events are buffered and NOT auto-exported`() {
        val processor = buildProcessor(ExportMode.CONDITIONAL)
        try {
            repeat(10) { i ->
                processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("normal.$i")))
            }

            // Give time for any async export that should NOT happen
            Thread.sleep(500)

            // No policy triggered → nothing should be exported
            assertEquals(
                "CONDITIONAL mode must not export without a policy trigger",
                0, mockExporter.exportedLogs.size
            )

            // But events must still be buffered
            val stats = processor.getBufferStats()
            assertEquals(10, stats.ramBufferSize)
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `CONDITIONAL - UI freeze policy triggers selective flushWindow`() {
        val processor = buildProcessor(ExportMode.CONDITIONAL)
        try {
            // Emit several normal events first
            repeat(5) { i ->
                processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("normal.$i")))
            }

            // UI freeze event — the default policy matches duration_ms > 2000
            val freezeLog = TestUtils.createUIFreezeLog(durationMs = 3000)
            processor.onEmit(OtelContext.root(), wrap(freezeLog))

            // Wait for async policy evaluation + export
            val exported = mockExporter.waitForLogs(6, timeoutMs = 3000)
            assertTrue(
                "UI freeze policy should trigger flushWindow (exported ${mockExporter.exportedLogs.size})",
                exported
            )
            assertTrue(mockExporter.exportedLogs.size >= 6)
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `CONDITIONAL - crash policy triggers selective flushWindow`() {
        val processor = buildProcessor(ExportMode.CONDITIONAL)
        try {
            repeat(3) { i ->
                processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("pre-crash.$i")))
            }

            val crashLog = TestUtils.createCrashLog()
            processor.onEmit(OtelContext.root(), wrap(crashLog))

            val exported = mockExporter.waitForLogs(4, timeoutMs = 3000)
            assertTrue(
                "Crash policy should trigger flushWindow (exported ${mockExporter.exportedLogs.size})",
                exported
            )
            assertTrue(mockExporter.exportedLogs.size >= 4)
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `CONDITIONAL - events with non-policy body do NOT trigger export`() {
        val processor = buildProcessor(ExportMode.CONDITIONAL)
        try {
            // Emit events with bodies that don't match any default policy trigger
            // (default policies watch for "ui.freeze" and "app.crash" body text)
            repeat(5) { i ->
                processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("http.request.$i")))
            }
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createHttpErrorLog(404, "/api/items")))
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("user.action")))

            Thread.sleep(500)

            assertEquals(
                "Events that match no policy must not trigger export in CONDITIONAL mode",
                0, mockExporter.exportedLogs.size
            )

            // Verify all events are still buffered
            val stats = processor.getBufferStats()
            assertEquals(7, stats.ramBufferSize)
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `CONDITIONAL - forceFlush still works on-demand`() {
        val processor = buildProcessor(ExportMode.CONDITIONAL)
        try {
            repeat(8) { i ->
                processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("on-demand.$i")))
            }

            val result = processor.forceFlush()
            assertTrue(result.isSuccess)
            assertEquals(8, mockExporter.exportedLogs.size)
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `CONDITIONAL - flushWindow exports only events within the time window`() {
        val processor = buildProcessor(ExportMode.CONDITIONAL)
        try {
            val now = System.currentTimeMillis()

            // Old event (10 minutes ago) — outside a 2-minute window
            val oldLog = TestUtils.createTestLogRecordWithTimestamp("old-event", now - (10 * 60 * 1000))
            processor.onEmit(OtelContext.root(), wrap(oldLog))

            // Recent event (30 seconds ago) — inside a 2-minute window
            val recentLog = TestUtils.createTestLogRecordWithTimestamp("recent-event", now - (30 * 1000))
            processor.onEmit(OtelContext.root(), wrap(recentLog))

            processor.flushWindow(2)
            Thread.sleep(300)

            assertEquals("Only events within the 2-minute window should be exported", 1, mockExporter.exportedLogs.size)
            assertEquals("recent-event", mockExporter.exportedLogs[0].body.asString())
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `CONDITIONAL - buffer is unchanged after failed export`() {
        val processor = buildProcessor(ExportMode.CONDITIONAL)
        try {
            mockExporter.shouldFail = true

            repeat(5) { i ->
                processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("retry.$i")))
            }

            processor.flushWindow(60)
            Thread.sleep(300)

            // Export failed — events must still be in buffer for retry
            val stats = processor.getBufferStats()
            assertTrue(
                "Events must be retained in buffer after a failed export",
                stats.ramBufferSize > 0
            )
        } finally {
            processor.shutdown()
        }
    }

    // ── CONTINUOUS mode ───────────────────────────────────────────────────────

    @Test
    fun `CONTINUOUS - periodic flush exports buffered events automatically`() {
        // Use a 1-second flush interval so the test completes quickly
        val processor = buildProcessor(ExportMode.CONTINUOUS, traceExportIntervalSeconds = 1)
        try {
            repeat(5) { i ->
                processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("continuous.$i")))
            }

            // Wait up to 3s for the periodic scheduler to fire (interval = 1s)
            val exported = mockExporter.waitForLogs(5, timeoutMs = 4000)
            assertTrue(
                "CONTINUOUS mode periodic flush must export buffered events (exported ${mockExporter.exportedLogs.size})",
                exported
            )
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `CONTINUOUS - events added after flush are exported in next cycle`() {
        val processor = buildProcessor(ExportMode.CONTINUOUS, traceExportIntervalSeconds = 1)
        try {
            // First batch
            repeat(3) { i ->
                processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("batch1.$i")))
            }
            mockExporter.waitForLogs(3, timeoutMs = 4000)
            val firstBatchCount = mockExporter.exportedLogs.size
            assertTrue("First batch should be exported", firstBatchCount >= 3)

            // Second batch added after first flush
            repeat(3) { i ->
                processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("batch2.$i")))
            }
            mockExporter.waitForLogs(firstBatchCount + 3, timeoutMs = 4000)

            assertTrue(
                "Second batch should also be exported in subsequent cycle",
                mockExporter.exportedLogs.size >= firstBatchCount + 3
            )
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `CONTINUOUS - crash event is NOT immediately exported by policy`() {
        // CONTINUOUS has no policy evaluation — the periodic scheduler is the only export path.
        // Even a high-severity crash event must wait for the next scheduled cycle.
        val processor = buildProcessor(ExportMode.CONTINUOUS, traceExportIntervalSeconds = 30)
        try {
            repeat(4) { i ->
                processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("ctx.$i")))
            }

            // Crash event — must NOT trigger an immediate policy-driven flush in CONTINUOUS mode
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createCrashLog()))

            // Wait a bit — longer than async policy evaluation would take if it were running
            Thread.sleep(800)

            assertEquals(
                "CONTINUOUS mode must not export on policy match — only via periodic scheduler",
                0, mockExporter.exportedLogs.size
            )
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `CONTINUOUS - crash event IS exported when periodic flush fires`() {
        // Use short interval to verify the crash eventually reaches the exporter via schedule
        val processor = buildProcessor(ExportMode.CONTINUOUS, traceExportIntervalSeconds = 1)
        try {
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createCrashLog()))

            // Should appear in next periodic flush (interval = 1s), not via policy
            val exported = mockExporter.waitForLogs(1, timeoutMs = 4000)
            assertTrue(
                "CONTINUOUS mode must export crash via periodic flush (exported ${mockExporter.exportedLogs.size})",
                exported
            )
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `CONTINUOUS - forceFlush works at any time`() {
        val processor = buildProcessor(ExportMode.CONTINUOUS, traceExportIntervalSeconds = 30)
        try {
            repeat(7) { i ->
                processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("manual.$i")))
            }

            val result = processor.forceFlush()
            assertTrue(result.isSuccess)
            assertTrue(mockExporter.exportedLogs.size >= 7)
        } finally {
            processor.shutdown()
        }
    }

    // ── HYBRID mode ───────────────────────────────────────────────────────────

    @Test
    fun `HYBRID - periodic flush fires automatically`() {
        val processor = buildProcessor(ExportMode.HYBRID, traceExportIntervalSeconds = 1)
        try {
            repeat(5) { i ->
                processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("hybrid.$i")))
            }

            val exported = mockExporter.waitForLogs(5, timeoutMs = 4000)
            assertTrue(
                "HYBRID mode periodic flush must fire (exported ${mockExporter.exportedLogs.size})",
                exported
            )
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `HYBRID - crash event IS immediately exported by policy unlike CONTINUOUS`() {
        // This is the key semantic difference between HYBRID and CONTINUOUS:
        // HYBRID evaluates policies and exports immediately on a match.
        val processor = buildProcessor(ExportMode.HYBRID, traceExportIntervalSeconds = 30)
        try {
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createCrashLog()))

            val exported = mockExporter.waitForLogs(1, timeoutMs = 3000)
            assertTrue(
                "HYBRID mode must export crash immediately via policy (exported ${mockExporter.exportedLogs.size})",
                exported
            )
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `HYBRID - policy-triggered flushWindow fires on matching event`() {
        // Verify that even with a long periodic interval, a policy match triggers an
        // immediate selective flush (the defining characteristic of HYBRID vs CONTINUOUS).
        val processor = buildProcessor(ExportMode.HYBRID, traceExportIntervalSeconds = 30)
        try {
            repeat(4) { i ->
                processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("pre.$i")))
            }

            val freezeLog = TestUtils.createUIFreezeLog(durationMs = 2500)
            processor.onEmit(OtelContext.root(), wrap(freezeLog))

            val exported = mockExporter.waitForLogs(5, timeoutMs = 3000)
            assertTrue(
                "HYBRID mode must export on policy trigger even outside periodic cycle (exported ${mockExporter.exportedLogs.size})",
                exported
            )
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `HYBRID - both periodic and policy paths contribute exports`() {
        // Use a short periodic interval AND trigger a policy to verify both paths fire.
        val processor = buildProcessor(ExportMode.HYBRID, traceExportIntervalSeconds = 1)
        try {
            // First, let the periodic flush drain the buffer
            repeat(3) { i ->
                processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("periodic.$i")))
            }
            mockExporter.waitForLogs(3, timeoutMs = 4000)
            val afterPeriodicCount = mockExporter.exportedLogs.size
            assertTrue("Periodic flush should have fired", afterPeriodicCount >= 3)

            // Now add more events and trigger a policy
            repeat(3) { i ->
                processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("policy.$i")))
            }
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createCrashLog()))

            mockExporter.waitForLogs(afterPeriodicCount + 4, timeoutMs = 4000)
            assertTrue(
                "HYBRID mode: policy-triggered export must also fire (exported ${mockExporter.exportedLogs.size})",
                mockExporter.exportedLogs.size >= afterPeriodicCount + 4
            )
        } finally {
            processor.shutdown()
        }
    }

    @Test
    fun `HYBRID - shutdown flushes remaining events regardless of mode`() {
        val processor = buildProcessor(ExportMode.HYBRID, traceExportIntervalSeconds = 30)
        try {
            repeat(6) { i ->
                processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("shutdown.$i")))
            }

            processor.shutdown()

            assertEquals("All RAM-buffered events must be exported on shutdown", 6, mockExporter.exportedLogs.size)
        } finally {
            // already shut down — second call is a no-op
        }
    }

    // ── Mode comparison ───────────────────────────────────────────────────────

    @Test
    fun `CONDITIONAL buffers without exporting while CONTINUOUS exports automatically`() {
        val conditionalExporter = MockLogRecordExporter()
        val continuousExporter = MockLogRecordExporter()

        val conditionalConfig = MobileConfig(
            serviceName = "test", serviceVersion = "1.0", collectorEndpoint = "http://localhost:4317",
            exportMode = ExportMode.CONDITIONAL, traceExportIntervalSeconds = 1,
            ramBufferSize = 100, diskBufferMb = 10, diskBufferTtlHours = 1
        )
        val continuousConfig = conditionalConfig.copy(exportMode = ExportMode.CONTINUOUS)

        val meter = OpenTelemetry.noop().meterProvider.get("test")

        val conditionalProcessor = MobileLogRecordProcessor.builder(context)
            .setExporter(conditionalExporter).setConfig(conditionalConfig).setMeter(meter)
            .setRamBufferSize(100).setDiskBufferMb(10).setDiskBufferTtlHours(1).build()

        val continuousProcessor = MobileLogRecordProcessor.builder(context)
            .setExporter(continuousExporter).setConfig(continuousConfig).setMeter(meter)
            .setRamBufferSize(100).setDiskBufferMb(10).setDiskBufferTtlHours(1).build()

        try {
            repeat(5) { i ->
                val log = TestUtils.createTestLogRecord("compare.$i")
                val wrapped = wrap(log)
                conditionalProcessor.onEmit(OtelContext.root(), wrapped)
                continuousProcessor.onEmit(OtelContext.root(), wrap(log))
            }

            // CONTINUOUS should export within 3s (interval = 1s)
            val continuousExported = continuousExporter.waitForLogs(5, timeoutMs = 4000)
            assertTrue("CONTINUOUS must auto-export", continuousExported)

            // CONDITIONAL should NOT have exported (no policy trigger)
            assertEquals("CONDITIONAL must NOT auto-export", 0, conditionalExporter.exportedLogs.size)
        } finally {
            conditionalProcessor.shutdown()
            continuousProcessor.shutdown()
        }
    }
}
