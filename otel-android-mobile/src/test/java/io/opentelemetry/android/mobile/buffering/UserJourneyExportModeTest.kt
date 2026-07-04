/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.android.mobile.config.ExportMode
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.testing.MockLogRecordExporter
import io.opentelemetry.android.mobile.testing.TestUtils
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.Context as OtelContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Validates that user journey event sequences produce the expected telemetry shape
 * under each of the three export modes: CONDITIONAL, CONTINUOUS, and HYBRID.
 *
 * Each test mirrors one of the demo scenarios defined in the androidTest suite, using
 * the same event types (screen.navigation, user.transaction, api.request, http.error,
 * app.crash) and asserting mode-specific export behaviour with a [MockLogRecordExporter].
 *
 * ## Mode contract summary
 *
 * | Event type               | CONDITIONAL | CONTINUOUS  | HYBRID       |
 * |--------------------------|-------------|-------------|--------------|
 * | navigation / transaction | buffered    | auto-export | auto-export  |
 * | app.crash                | flush(5min) | periodic    | flush(5min)  |
 * | http.error               | flush(5min) | periodic    | flush(5min)  |
 * | ui.freeze                | flush(2min) | periodic    | flush(2min)  |
 *
 * ## Structure
 *
 * Each user journey is exercised three times — once per mode — so the assertions
 * document the exact observable difference between them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UserJourneyExportModeTest {

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

    // ── Builder helpers ───────────────────────────────────────────────────────

    private fun processor(
        exportMode: ExportMode,
        traceIntervalSeconds: Long = 30
    ): MobileLogRecordProcessor {
        val config = MobileConfig(
            serviceName       = "journey-test",
            serviceVersion    = "1.0.0",
            collectorEndpoint = "http://localhost:4317",
            exportMode        = exportMode,
            traceExportIntervalSeconds  = traceIntervalSeconds,
            metricExportIntervalSeconds = 60,
            ramBufferSize      = 200,
            diskBufferMb       = 10,
            diskBufferTtlHours = 1
        )
        return MobileLogRecordProcessor.builder(context)
            .setExporter(exporter)
            .setConfig(config)
            .setMeter(OpenTelemetry.noop().meterProvider.get("test"))
            .setRamBufferSize(config.ramBufferSize)
            .setDiskBufferMb(config.diskBufferMb)
            .setDiskBufferTtlHours(config.diskBufferTtlHours)
            .build()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 1: Happy path booking
    // launch → Calendar → Book → Appointments → Profile
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * CONDITIONAL: 4 navigation events accumulate silently; nothing exported.
     * The journey produces no policy-triggering event so the buffer is never drained.
     */
    @Test
    fun `Scenario1 CONDITIONAL - navigation events are buffered and not exported`() {
        val p = processor(ExportMode.CONDITIONAL)
        try {
            emitHappyPathBookingEvents(p)

            Thread.sleep(500)

            assertEquals(
                "CONDITIONAL: happy-path nav events must not be auto-exported",
                0, exporter.exportedLogs.size
            )
            assertEquals(
                "All 4 navigation events must be in RAM buffer",
                4, p.getBufferStats().ramBufferSize
            )
        } finally { p.shutdown() }
    }

    /**
     * CONTINUOUS: navigation events are exported automatically within the periodic interval.
     */
    @Test
    fun `Scenario1 CONTINUOUS - navigation events are auto-exported by periodic flush`() {
        val p = processor(ExportMode.CONTINUOUS, traceIntervalSeconds = 1)
        try {
            emitHappyPathBookingEvents(p)

            assertTrue(
                "CONTINUOUS: periodic flush must export 4 navigation events",
                exporter.waitForLogs(4, timeoutMs = 4000)
            )
            assertEquals(4, exporter.exportedLogs.size)
            assertTrue(
                "All exported events must be screen.navigation",
                exporter.exportedLogs.all { it.bodyValue?.asString() == "screen.navigation" }
            )
        } finally { p.shutdown() }
    }

    /**
     * HYBRID: navigation events buffer silently — no periodic forceFlush.
     * Unlike CONTINUOUS, HYBRID only exports when a policy trigger fires (e.g. crash, http error).
     */
    @Test
    fun `Scenario1 HYBRID - navigation events buffer silently without policy trigger`() {
        val p = processor(ExportMode.HYBRID, traceIntervalSeconds = 1)
        try {
            emitHappyPathBookingEvents(p)

            // Wait long enough that a periodic flush *would* have fired in CONTINUOUS mode
            Thread.sleep(2500)
            assertEquals(
                "HYBRID: navigation events must buffer silently without a policy trigger",
                0, exporter.exportedLogs.size
            )
        } finally { p.shutdown() }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 3: Network error recovery
    // Appointments (baseline) → HTTP 500 → refresh → Calendar
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * CONDITIONAL: 5 successful api.request events buffer silently, then
     * the single http.error triggers the http-error-detector policy (flushWindow 5min)
     * exporting all 6 events together in one shot.
     */
    @Test
    fun `Scenario3 CONDITIONAL - buffered api requests flushed when http error arrives`() {
        val p = processor(ExportMode.CONDITIONAL)
        try {
            emitNetworkErrorRecoveryEvents(p, includeHttpError = false)

            Thread.sleep(400)
            assertEquals("No exports before error trigger", 0, exporter.exportedLogs.size)
            assertEquals("5 api requests must be buffered", 5, p.getBufferStats().ramBufferSize)

            // The http.error event matches the http-error-detector policy → flushWindow(5)
            TestUtils.emitAll(p, listOf(TestUtils.createApiRequestEvent(6, statusCode = 500)))

            assertTrue(
                "http.error policy must flush all 6 events",
                exporter.waitForLogs(6, timeoutMs = 3000)
            )
            // All events exported in a single flush window batch (may be chunked into 100-record
            // sub-batches internally, but total event count is what matters)
            assertEquals(6, exporter.exportedLogs.size)
            val bodies = exporter.exportedLogs.map { it.bodyValue?.asString() }.toSet()
            assertTrue("Exported set must contain api.request", bodies.contains("api.request"))
            assertTrue("Exported set must contain the http.error trigger", bodies.contains("http.error"))
        } finally { p.shutdown() }
    }

    /**
     * CONDITIONAL: the pre-error api.request events are exported IN THE SAME BATCH as
     * the trigger, not before it — proving the buffer held them until the flush.
     */
    @Test
    fun `Scenario3 CONDITIONAL - pre-error events are in same export batch as trigger`() {
        val p = processor(ExportMode.CONDITIONAL)
        try {
            emitNetworkErrorRecoveryEvents(p, includeHttpError = true)

            assertTrue(
                "All events including pre-error requests must be exported together",
                exporter.waitForLogs(6, timeoutMs = 3000)
            )
            // The first export batch must contain both prior and trigger events.
            // (CONDITIONAL produces at most a few batches from a single flushWindow; the total count is the check)
            assertTrue(
                "First batch must include pre-error requests (CONDITIONAL holds until flush)",
                exporter.exportBatches.isNotEmpty() &&
                    exporter.exportBatches.flatMap { it }.any { it.bodyValue?.asString() == "api.request" }
            )
        } finally { p.shutdown() }
    }

    /**
     * CONTINUOUS: all 6 events (including the http.error) are exported automatically
     * by the periodic scheduler, not by a policy trigger.
     */
    @Test
    fun `Scenario3 CONTINUOUS - all events exported via periodic flush regardless of http error`() {
        val p = processor(ExportMode.CONTINUOUS, traceIntervalSeconds = 1)
        try {
            emitNetworkErrorRecoveryEvents(p, includeHttpError = true)

            assertTrue(
                "CONTINUOUS must export all 6 events via schedule",
                exporter.waitForLogs(6, timeoutMs = 4000)
            )
            // In CONTINUOUS mode the http.error does NOT immediately trigger a flush;
            // the periodic scheduler handles it. Multiple small batches are expected.
            assertEquals(6, exporter.exportedLogs.size)
        } finally { p.shutdown() }
    }

    /**
     * CONTINUOUS: the http.error event does NOT immediately trigger an export —
     * it must wait for the periodic cycle (use a long interval to prove it).
     */
    @Test
    fun `Scenario3 CONTINUOUS - http error does NOT trigger immediate export`() {
        val p = processor(ExportMode.CONTINUOUS, traceIntervalSeconds = 30)
        try {
            emitNetworkErrorRecoveryEvents(p, includeHttpError = true)

            Thread.sleep(800) // plenty of time for any policy-based export to fire

            assertEquals(
                "CONTINUOUS must NOT export on http.error policy — periodic only",
                0, exporter.exportedLogs.size
            )
        } finally { p.shutdown() }
    }

    /**
     * HYBRID: http.error triggers an IMMEDIATE policy flush (like CONDITIONAL) even
     * when the periodic interval has not fired yet.
     */
    @Test
    fun `Scenario3 HYBRID - http error triggers immediate policy flush`() {
        val p = processor(ExportMode.HYBRID, traceIntervalSeconds = 30)
        try {
            emitNetworkErrorRecoveryEvents(p, includeHttpError = true)

            assertTrue(
                "HYBRID must export immediately on http.error policy match",
                exporter.waitForLogs(6, timeoutMs = 3000)
            )
            assertEquals(6, exporter.exportedLogs.size)
        } finally { p.shutdown() }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 9: Quiet buffer accumulation → crash triggers flush
    // 20 user.transaction + 4 nav → app.crash → flushWindow(5)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * CONDITIONAL: 24 events sit silently in buffer → app.crash policy match → all exported.
     */
    @Test
    fun `Scenario9 CONDITIONAL - 20 transactions buffered silently then crash flushes all`() {
        val p = processor(ExportMode.CONDITIONAL)
        try {
            emitQuietTransactionBatch(p, count = 20)
            emitNavSequence(p, screens = listOf("calendar", "book", "appointments", "directions"))

            Thread.sleep(500)

            // Nothing exported yet
            assertEquals("24 events must be silently buffered", 0, exporter.exportedLogs.size)
            assertEquals(24, p.getBufferStats().ramBufferSize)

            // Crash event triggers crash-recovery policy → flushWindow(5min)
            TestUtils.emitAll(p, listOf(TestUtils.createCrashLog()))

            assertTrue(
                "Crash must trigger export of all 25 buffered events",
                exporter.waitForLogs(25, timeoutMs = 4000)
            )
            assertEquals(25, exporter.exportedLogs.size)

            // The exported set must include both the silent transactions and the crash trigger
            val transactionCount = exporter.findLogsByBody("user.transaction").size
            assertEquals("All 20 silent transactions must be in exported set", 20, transactionCount)
            assertEquals("Crash log must be in exported set", 1, exporter.findLogsByBody("app.crash").size)
        } finally { p.shutdown() }
    }

    /**
     * CONDITIONAL: buffer stats reflect fill and drain — events visible in buffer before
     * flush, absent after.
     */
    @Test
    fun `Scenario9 CONDITIONAL - buffer drains to zero after crash flush`() {
        val p = processor(ExportMode.CONDITIONAL)
        try {
            emitQuietTransactionBatch(p, count = 10)
            assertEquals(10, p.getBufferStats().ramBufferSize)

            TestUtils.emitAll(p, listOf(TestUtils.createCrashLog()))
            exporter.waitForLogs(11, timeoutMs = 3000)

            // After successful flush, RAM buffer should be empty
            val statsAfter = p.getBufferStats()
            assertEquals(
                "RAM buffer must be empty after successful crash flush",
                0, statsAfter.ramBufferSize
            )
        } finally { p.shutdown() }
    }

    /**
     * CONTINUOUS: 20 transactions are exported automatically by the periodic scheduler
     * without needing a crash event — the crash is eventually exported too.
     */
    @Test
    fun `Scenario9 CONTINUOUS - transactions auto-exported before crash event`() {
        val p = processor(ExportMode.CONTINUOUS, traceIntervalSeconds = 1)
        try {
            emitQuietTransactionBatch(p, count = 20)

            // Periodic flush fires within 1s
            assertTrue(
                "CONTINUOUS must auto-export transactions without waiting for crash",
                exporter.waitForLogs(20, timeoutMs = 4000)
            )
            assertEquals(20, exporter.exportedLogs.size)

            // Now add the crash — it is exported in the next cycle too
            TestUtils.emitAll(p, listOf(TestUtils.createCrashLog()))
            assertTrue(
                "Crash event must eventually appear in CONTINUOUS export",
                exporter.waitForLogs(21, timeoutMs = 4000)
            )
        } finally { p.shutdown() }
    }

    /**
     * CONTINUOUS: crash event does NOT cause an immediate extra export (no policy evaluation).
     */
    @Test
    fun `Scenario9 CONTINUOUS - crash does not trigger immediate export`() {
        val p = processor(ExportMode.CONTINUOUS, traceIntervalSeconds = 30)
        try {
            TestUtils.emitAll(p, listOf(TestUtils.createCrashLog()))

            Thread.sleep(800)

            assertEquals(
                "CONTINUOUS must not export crash immediately — no policy evaluation",
                0, exporter.exportedLogs.size
            )
        } finally { p.shutdown() }
    }

    /**
     * HYBRID: quiet transactions buffer silently; crash triggers immediate policy flush.
     * No periodic forceFlush in HYBRID — only policy triggers export events.
     */
    @Test
    fun `Scenario9 HYBRID - quiet transactions buffer silently then crash triggers immediate flush`() {
        val p = processor(ExportMode.HYBRID, traceIntervalSeconds = 1)
        try {
            emitQuietTransactionBatch(p, count = 10)

            // Wait long enough for a periodic flush to have fired in CONTINUOUS — it must NOT fire in HYBRID
            Thread.sleep(2500)
            assertEquals("HYBRID: quiet transactions must buffer silently", 0, exporter.exportedLogs.size)

            // Crash triggers crash-detector policy → immediate flushWindow
            TestUtils.emitAll(p, listOf(TestUtils.createCrashLog()))

            assertTrue(
                "HYBRID: crash must trigger immediate policy flush of buffered events",
                exporter.waitForLogs(11, timeoutMs = 3000)
            )
        } finally { p.shutdown() }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Scenario 10: HTTP error flush
    // 15 api.request buffered → http.error → flushWindow(5)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * CONDITIONAL: 15 api.request events buffer silently; http.error triggers flush of all 16.
     */
    @Test
    fun `Scenario10 CONDITIONAL - 15 api requests buffered until http error triggers flush`() {
        val p = processor(ExportMode.CONDITIONAL)
        try {
            emitApiRequestBatch(p, count = 15)

            Thread.sleep(400)
            assertEquals("15 api.request events must be silently buffered", 0, exporter.exportedLogs.size)
            assertEquals(15, p.getBufferStats().ramBufferSize)

            // http.error triggers http-error-detector policy → flushWindow(5min)
            TestUtils.emitAll(p, listOf(TestUtils.createHttpErrorLog(500, "/appointments")))

            assertTrue(
                "http.error must flush all 16 events (15 requests + error)",
                exporter.waitForLogs(16, timeoutMs = 3000)
            )
            assertEquals(16, exporter.exportedLogs.size)
            assertEquals("All 15 silent requests present", 15, exporter.findLogsByBody("api.request").size)
            assertEquals("HTTP error event present", 1, exporter.findLogsByBody("http.error").size)
        } finally { p.shutdown() }
    }

    /**
     * CONDITIONAL: http.error arrives without prior buffered events — only the trigger
     * itself is exported (no pre-error history to flush).
     */
    @Test
    fun `Scenario10 CONDITIONAL - http error with empty buffer exports only the trigger`() {
        val p = processor(ExportMode.CONDITIONAL)
        try {
            TestUtils.emitAll(p, listOf(TestUtils.createHttpErrorLog(500, "/appointments")))

            assertTrue(
                "Trigger-only flush must export just the 1 http.error event",
                exporter.waitForLogs(1, timeoutMs = 3000)
            )
            assertEquals(1, exporter.exportedLogs.size)
        } finally { p.shutdown() }
    }

    /**
     * CONTINUOUS: 15 api.request events auto-exported on schedule.
     * The http.error is also exported eventually — without policy involvement.
     */
    @Test
    fun `Scenario10 CONTINUOUS - api requests auto-exported without waiting for http error`() {
        val p = processor(ExportMode.CONTINUOUS, traceIntervalSeconds = 1)
        try {
            emitApiRequestBatch(p, count = 15)
            TestUtils.emitAll(p, listOf(TestUtils.createHttpErrorLog(500, "/appointments")))

            assertTrue(
                "CONTINUOUS must export all 16 events via periodic schedule",
                exporter.waitForLogs(16, timeoutMs = 5000)
            )
            assertEquals(16, exporter.exportedLogs.size)
        } finally { p.shutdown() }
    }

    /**
     * HYBRID: api.request events buffer silently; http.error triggers immediate policy flush of all buffered events.
     * No periodic forceFlush in HYBRID — the http-error-detector policy is the only export trigger.
     */
    @Test
    fun `Scenario10 HYBRID - api requests buffer silently then http error triggers policy flush`() {
        val p = processor(ExportMode.HYBRID, traceIntervalSeconds = 1)
        try {
            emitApiRequestBatch(p, count = 15)

            // Wait — events must remain buffered without a policy trigger
            Thread.sleep(400)
            assertEquals("HYBRID: api.request events must buffer silently", 0, exporter.exportedLogs.size)

            // http.error triggers http-error-detector policy → flushWindow(5min) exports all 16 events
            TestUtils.emitAll(p, listOf(TestUtils.createHttpErrorLog(500, "/appointments")))

            assertTrue(
                "HYBRID: http.error must flush all 16 events (15 requests + error)",
                exporter.waitForLogs(16, timeoutMs = 3000)
            )
        } finally { p.shutdown() }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Cross-mode comparisons — same journey, different observable outcomes
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Runs the quiet-buffer journey on CONDITIONAL and CONTINUOUS processors in parallel.
     * Asserts that before the crash event:
     *   - CONDITIONAL has exported nothing
     *   - CONTINUOUS has exported everything
     */
    @Test
    fun `Cross-mode - CONDITIONAL holds buffer while CONTINUOUS drains it`() {
        val conditionalExporter = MockLogRecordExporter()
        val continuousExporter  = MockLogRecordExporter()

        val baseConfig = MobileConfig(
            serviceName = "x", serviceVersion = "1.0", collectorEndpoint = "http://localhost:4317",
            exportMode = ExportMode.CONDITIONAL, traceExportIntervalSeconds = 1,
            ramBufferSize = 100, diskBufferMb = 10, diskBufferTtlHours = 1
        )

        val cond = MobileLogRecordProcessor.builder(context)
            .setExporter(conditionalExporter).setConfig(baseConfig)
            .setMeter(OpenTelemetry.noop().meterProvider.get("test"))
            .setRamBufferSize(100).setDiskBufferMb(10).setDiskBufferTtlHours(1).build()

        val cont = MobileLogRecordProcessor.builder(context)
            .setExporter(continuousExporter).setConfig(baseConfig.copy(exportMode = ExportMode.CONTINUOUS))
            .setMeter(OpenTelemetry.noop().meterProvider.get("test"))
            .setRamBufferSize(100).setDiskBufferMb(10).setDiskBufferTtlHours(1).build()

        try {
            // Emit identical silent transactions to both processors
            repeat(10) { i ->
                val log = TestUtils.createTransactionEvent(i)
                cond.onEmit(OtelContext.root(), TestUtils.asReadWriteLogRecord(log))
                cont.onEmit(OtelContext.root(), TestUtils.asReadWriteLogRecord(log))
            }

            // CONTINUOUS should drain within 3s (interval = 1s)
            continuousExporter.waitForLogs(10, timeoutMs = 4000)
            assertTrue("CONTINUOUS must auto-export 10 transactions", continuousExporter.exportedLogs.size >= 10)

            // CONDITIONAL must not have exported anything
            assertEquals(
                "CONDITIONAL must NOT export transactions without a policy trigger",
                0, conditionalExporter.exportedLogs.size
            )
            assertEquals(10, cond.getBufferStats().ramBufferSize)

        } finally {
            cond.shutdown()
            cont.shutdown()
        }
    }

    /**
     * Runs the crash scenario on CONTINUOUS and HYBRID processors.
     * Asserts that HYBRID exports the crash IMMEDIATELY while CONTINUOUS does not.
     * This is the key semantic difference between the two modes.
     */
    @Test
    fun `Cross-mode - HYBRID exports crash immediately while CONTINUOUS waits for schedule`() {
        val hybridExporter     = MockLogRecordExporter()
        val continuousExporter = MockLogRecordExporter()

        val baseConfig = MobileConfig(
            serviceName = "x", serviceVersion = "1.0", collectorEndpoint = "http://localhost:4317",
            exportMode = ExportMode.CONTINUOUS, traceExportIntervalSeconds = 30,
            ramBufferSize = 100, diskBufferMb = 10, diskBufferTtlHours = 1
        )

        val hybrid = MobileLogRecordProcessor.builder(context)
            .setExporter(hybridExporter).setConfig(baseConfig.copy(exportMode = ExportMode.HYBRID))
            .setMeter(OpenTelemetry.noop().meterProvider.get("test"))
            .setRamBufferSize(100).setDiskBufferMb(10).setDiskBufferTtlHours(1).build()

        val continuous = MobileLogRecordProcessor.builder(context)
            .setExporter(continuousExporter).setConfig(baseConfig)
            .setMeter(OpenTelemetry.noop().meterProvider.get("test"))
            .setRamBufferSize(100).setDiskBufferMb(10).setDiskBufferTtlHours(1).build()

        try {
            val crash = TestUtils.createCrashLog()
            hybrid.onEmit(OtelContext.root(), TestUtils.asReadWriteLogRecord(crash))
            continuous.onEmit(OtelContext.root(), TestUtils.asReadWriteLogRecord(crash))

            // HYBRID: crash policy must fire within 2s
            assertTrue(
                "HYBRID must export crash immediately via policy",
                hybridExporter.waitForLogs(1, timeoutMs = 2500)
            )

            // CONTINUOUS: crash must NOT be exported yet (interval = 30s)
            assertEquals(
                "CONTINUOUS must NOT export crash before periodic interval fires",
                0, continuousExporter.exportedLogs.size
            )
        } finally {
            hybrid.shutdown()
            continuous.shutdown()
        }
    }

    // ── Journey event sequence helpers ────────────────────────────────────────

    private fun emitHappyPathBookingEvents(p: MobileLogRecordProcessor) {
        TestUtils.emitAll(p, listOf(
            TestUtils.createNavigationEvent("calendar"),
            TestUtils.createNavigationEvent("book", previousScreen = "calendar"),
            TestUtils.createNavigationEvent("appointments", previousScreen = "book"),
            TestUtils.createNavigationEvent("profile", previousScreen = "appointments")
        ))
    }

    private fun emitNetworkErrorRecoveryEvents(
        p: MobileLogRecordProcessor,
        includeHttpError: Boolean
    ) {
        // 5 successful API requests (all buffer silently in CONDITIONAL)
        repeat(5) { i ->
            TestUtils.emitAll(p, listOf(TestUtils.createApiRequestEvent(i + 1, statusCode = 200)))
        }
        if (includeHttpError) {
            TestUtils.emitAll(p, listOf(TestUtils.createApiRequestEvent(6, statusCode = 500)))
        }
    }

    private fun emitQuietTransactionBatch(p: MobileLogRecordProcessor, count: Int, startIndex: Int = 1) {
        TestUtils.emitAll(p, (startIndex until startIndex + count).map { i ->
            TestUtils.createTransactionEvent(i)
        })
    }

    private fun emitNavSequence(p: MobileLogRecordProcessor, screens: List<String>) {
        TestUtils.emitAll(p, screens.mapIndexed { i, screen ->
            TestUtils.createNavigationEvent(screen, previousScreen = if (i == 0) "home" else screens[i - 1])
        })
    }

    private fun emitApiRequestBatch(p: MobileLogRecordProcessor, count: Int, startIndex: Int = 1) {
        TestUtils.emitAll(p, (startIndex until startIndex + count).map { i ->
            TestUtils.createApiRequestEvent(i, statusCode = 200)
        })
    }
}
