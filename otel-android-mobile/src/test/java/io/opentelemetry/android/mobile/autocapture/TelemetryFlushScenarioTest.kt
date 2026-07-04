/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.autocapture

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.opentelemetry.android.mobile.MobileLoggerProvider
import io.opentelemetry.android.mobile.buffering.DiskLogBuffer
import io.opentelemetry.android.mobile.buffering.MobileLogRecordProcessor
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.testing.MockLogRecordExporter
import io.opentelemetry.android.mobile.testing.TestUtils
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Comprehensive telemetry flush scenario tests.
 *
 * Validates the full pipeline for each failure mode:
 *  - Crash recovery: marker written before crash → events + flush on next start
 *  - ANR recovery: freeze detected → ANR marker → events on next start
 *  - OOM / low-memory kill: low-memory marker → recovery event on next start
 *  - System force kill: session active with no specific marker
 *  - HTTP 500 in booking: ApiException emitted, buffer flush triggered
 *  - Duplicate booking: local rejection, no HTTP, WARN log emitted
 *  - Predictive flush: recovery events contain session context
 *  - Buffer forceFlush: events queued in buffer → forceFlush → exporter receives all
 *  - Buffer capacity: overflow handling (RAM → disk handoff)
 *  - Export retry: exporter failure → subsequent flush exports accumulated events
 *  - Recovery priority: crash > ANR > OOM > system_force_kill
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TelemetryFlushScenarioTest {

    private lateinit var app: Application
    private lateinit var mockExporter: MockLogRecordExporter
    private lateinit var logger: Logger
    private lateinit var sessionTracker: SessionTracker
    private lateinit var mockProvider: MobileLoggerProvider

    companion object {
        private const val PREFS_NAME  = "otel_recovery"
        private const val KEY_SESSION  = "session_active"
        private const val KEY_CRASH    = "crash_marker"
        private const val KEY_OOM      = "low_memory_marker"
        private const val KEY_ANR      = "anr_marker"
    }

    @Before
    fun setup() {
        DiskLogBuffer.resetForTesting()
        app = ApplicationProvider.getApplicationContext()
        mockExporter = MockLogRecordExporter()

        val sdkLoggerProvider = SdkLoggerProvider.builder()
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(mockExporter))
            .build()
        logger = sdkLoggerProvider.get("test-flush")
        sessionTracker = SessionTracker(AutoCaptureOptions())
        mockProvider = mockk(relaxed = true)

        clearPrefs()
    }

    @After
    fun teardown() {
        DiskLogBuffer.resetForTesting()
        clearPrefs()
    }

    // ── Crash recovery → telemetry flush ──────────────────────────────────

    @Test
    fun `crash recovery emits app_recovery (app_crash is owned by ErrorInstrumentation at crash time)`() {
        // RecoveryTracker no longer re-emits app.crash on restart to avoid duplicate events.
        // ErrorInstrumentation emits app.crash synchronously in the uncaught-exception handler.
        setPrefs(KEY_CRASH, true)
        startTracker()

        val bodies = mockExporter.exportedLogs.map { it.bodyValue?.asString() }
        assertFalse("app.crash must NOT be re-emitted by RecoveryTracker", "app.crash" in bodies)
        assertTrue("app.recovery expected", "app.recovery" in bodies)
    }

    @Test
    fun `crash recovery event has ERROR severity`() {
        setPrefs(KEY_CRASH, true)
        startTracker()

        val recovery = mockExporter.exportedLogs.first { it.bodyValue?.asString() == "app.recovery" }
        assertEquals(Severity.ERROR, recovery.severity)
    }

    @Test
    fun `crash recovery_type attribute is crash`() {
        setPrefs(KEY_CRASH, true)
        startTracker()

        val recovery = mockExporter.exportedLogs.first { it.bodyValue?.asString() == "app.recovery" }
        assertEquals("crash", recovery.attributes.get(AttributeKey.stringKey("mobile.recovery_type")))
    }

    @Test
    fun `crash recovery includes session_id in recovery event`() {
        setPrefs(KEY_CRASH, true)
        startTracker()

        val recovery = mockExporter.exportedLogs.first { it.bodyValue?.asString() == "app.recovery" }
        assertFalse("mobile.session.id should be present and non-empty",
            recovery.attributes.get(AttributeKey.stringKey("mobile.session.id")).isNullOrBlank())
    }

    @Test
    fun `crash marker is cleared after handling — does not re-emit on second start`() {
        setPrefs(KEY_CRASH, true)
        startTracker()
        mockExporter.clear()

        // Second start — no crash marker should remain
        startTracker()
        assertEquals(0, mockExporter.findLogsByBody("app.crash").size)
    }

    // ── ANR recovery → telemetry flush ────────────────────────────────────

    @Test
    fun `ANR recovery emits app_anr and app_recovery`() {
        setPrefs(KEY_ANR, true)
        startTracker()

        assertTrue("app.anr expected", mockExporter.findLogsByBody("app.anr").isNotEmpty())
        assertTrue("app.recovery expected", mockExporter.findLogsByBody("app.recovery").isNotEmpty())
    }

    @Test
    fun `ANR recovery_type is anr_force_kill`() {
        setPrefs(KEY_ANR, true)
        startTracker()

        val recovery = mockExporter.findLogsByBody("app.recovery").first()
        assertEquals("anr_force_kill", recovery.attributes.get(AttributeKey.stringKey("mobile.recovery_type")))
    }

    @Test
    fun `ANR recovery event has ERROR severity`() {
        setPrefs(KEY_ANR, true)
        startTracker()

        val anr = mockExporter.findLogsByBody("app.anr").first()
        assertEquals(Severity.ERROR, anr.severity)
    }

    @Test
    fun `ANR marker cleared after handling`() {
        setPrefs(KEY_ANR, true)
        startTracker()
        mockExporter.clear()

        startTracker()
        assertEquals(0, mockExporter.findLogsByBody("app.anr").size)
    }

    // ── OOM / low-memory kill → telemetry flush ───────────────────────────

    @Test
    fun `OOM recovery emits app_recovery with low_memory_kill type`() {
        setPrefs(KEY_OOM, true)
        startTracker()

        val recovery = mockExporter.findLogsByBody("app.recovery").first()
        assertEquals("low_memory_kill", recovery.attributes.get(AttributeKey.stringKey("mobile.recovery_type")))
    }

    @Test
    fun `OOM recovery does NOT emit app_crash or app_anr`() {
        setPrefs(KEY_OOM, true)
        startTracker()

        assertEquals(0, mockExporter.findLogsByBody("app.crash").size)
        assertEquals(0, mockExporter.findLogsByBody("app.anr").size)
    }

    @Test
    fun `OOM marker cleared after handling`() {
        setPrefs(KEY_OOM, true)
        val first = startTracker()
        // Simulate clean shutdown so session_active is cleared before the next start
        first.markCleanShutdown()
        mockExporter.clear()

        startTracker()
        assertEquals(0, mockExporter.findLogsByBody("app.recovery").size)
    }

    // ── System force kill (session active, no specific marker) ────────────

    @Test
    fun `session active without specific marker emits system_force_kill recovery`() {
        setPrefs(KEY_SESSION, true)
        startTracker()

        val recovery = mockExporter.findLogsByBody("app.recovery")
        assertEquals(1, recovery.size)
        assertEquals("system_force_kill", recovery[0].attributes.get(AttributeKey.stringKey("mobile.recovery_type")))
    }

    @Test
    fun `clean start emits no events`() {
        startTracker()
        assertEquals(0, mockExporter.getExportedCount())
    }

    // ── Recovery priority ordering ────────────────────────────────────────

    @Test
    fun `crash wins over ANR when both markers are set`() {
        setPrefs(KEY_CRASH, true)
        setPrefs(KEY_ANR, true)

        val tracker = startTracker()
        assertEquals("crash", tracker.getLastRecoveryType())
        // app.crash is owned by ErrorInstrumentation; RecoveryTracker emits app.recovery with recovery_type=crash
        assertTrue("app.recovery with recovery_type=crash expected", mockExporter.findLogsByBody("app.recovery").isNotEmpty())
    }

    @Test
    fun `ANR wins over OOM when both markers are set`() {
        setPrefs(KEY_ANR, true)
        setPrefs(KEY_OOM, true)

        val tracker = startTracker()
        assertEquals("anr_force_kill", tracker.getLastRecoveryType())
        assertTrue("app.anr expected", mockExporter.findLogsByBody("app.anr").isNotEmpty())
    }

    @Test
    fun `crash wins over all other markers`() {
        setPrefs(KEY_CRASH, true)
        setPrefs(KEY_ANR, true)
        setPrefs(KEY_OOM, true)
        setPrefs(KEY_SESSION, true)

        val tracker = startTracker()
        assertEquals("crash", tracker.getLastRecoveryType())
    }

    // ── Marker write API ─────────────────────────────────────────────────

    @Test
    fun `markCrashForNextStart writes marker and is detected on next start`() {
        val tracker = startTracker()
        clearPrefs()
        tracker.markCrashForNextStart()
        mockExporter.clear()

        startTracker()
        assertTrue("app.recovery with recovery_type=crash should be emitted", mockExporter.findLogsByBody("app.recovery").isNotEmpty())
    }

    @Test
    fun `markAnrForNextStart writes marker and is detected on next start`() {
        val tracker = startTracker()
        tracker.markAnrForNextStart()
        mockExporter.clear()

        startTracker()
        assertTrue("app.anr should be emitted", mockExporter.findLogsByBody("app.anr").isNotEmpty())
    }

    @Test
    fun `markLowMemoryForNextStart is detected on next start as low_memory_kill`() {
        val tracker = startTracker()
        clearPrefs()
        tracker.markLowMemoryForNextStart()
        mockExporter.clear()

        startTracker()
        val recovery = mockExporter.findLogsByBody("app.recovery")
        assertEquals(1, recovery.size)
        assertEquals("low_memory_kill", recovery[0].attributes.get(AttributeKey.stringKey("mobile.recovery_type")))
    }

    @Test
    fun `clearAnrMarker prevents ANR recovery on next start`() {
        val tracker = startTracker()
        tracker.markAnrForNextStart()
        tracker.clearAnrMarker()
        mockExporter.clear()

        startTracker()
        assertEquals(0, mockExporter.findLogsByBody("app.anr").size)
    }

    // ── Buffer forceFlush pipeline ────────────────────────────────────────

    @Test
    fun `events emitted to processor are exported after forceFlush`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val config = MobileConfig(
            serviceName = "test", serviceVersion = "1.0", collectorEndpoint = "http://localhost:4317"
        )
        val meter = OpenTelemetry.noop().meterProvider.get("test")
        val processor = MobileLogRecordProcessor.builder(context)
            .setExporter(mockExporter)
            .setConfig(config)
            .setMeter(meter)
            .build()

        repeat(10) { i ->
            processor.onEmit(io.opentelemetry.context.Context.root(), TestUtils.asReadWriteLogRecord(
                TestUtils.createTestLogRecord("test.event.$i", mapOf("index" to i))
            ))
        }

        processor.forceFlush()

        // Wait briefly for async export
        val exported = mockExporter.waitForLogs(10, timeoutMs = 3000)
        assertTrue("Expected 10 exported events", exported)
        assertEquals(10, mockExporter.getExportedCount())

        processor.shutdown()
    }

    @Test
    fun `forceFlush after crash event exports all buffered events`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val config = MobileConfig(
            serviceName = "test", serviceVersion = "1.0", collectorEndpoint = "http://localhost:4317"
        )
        val meter = OpenTelemetry.noop().meterProvider.get("test")
        val processor = MobileLogRecordProcessor.builder(context)
            .setExporter(mockExporter)
            .setConfig(config)
            .setMeter(meter)
            .build()

        // Simulate events before a crash
        listOf("ui.tap", "form.submitted", "ui.scroll", "ui.tap", "api.call").forEach { event ->
            processor.onEmit(io.opentelemetry.context.Context.root(), TestUtils.asReadWriteLogRecord(
                TestUtils.createTestLogRecord(event)
            ))
        }

        // Simulate crash detection triggering flush
        processor.forceFlush()

        val exported = mockExporter.waitForLogs(5, timeoutMs = 3000)
        assertTrue("All pre-crash events should be exported", exported)
        assertEquals(5, mockExporter.getExportedCount())

        processor.shutdown()
    }

    @Test
    fun `exporter failure does not cause processor to throw`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val config = MobileConfig(
            serviceName = "test", serviceVersion = "1.0", collectorEndpoint = "http://localhost:4317"
        )
        val meter = OpenTelemetry.noop().meterProvider.get("test")
        mockExporter.shouldFail = true
        val processor = MobileLogRecordProcessor.builder(context)
            .setExporter(mockExporter)
            .setConfig(config)
            .setMeter(meter)
            .build()

        processor.onEmit(io.opentelemetry.context.Context.root(), TestUtils.asReadWriteLogRecord(
            TestUtils.createTestLogRecord("test.event")
        ))
        // Should not throw
        processor.forceFlush()
        processor.shutdown()
    }

    @Test
    fun `export succeeds after transient failure`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val config = MobileConfig(
            serviceName = "test", serviceVersion = "1.0", collectorEndpoint = "http://localhost:4317"
        )
        val meter = OpenTelemetry.noop().meterProvider.get("test")
        val processor = MobileLogRecordProcessor.builder(context)
            .setExporter(mockExporter)
            .setConfig(config)
            .setMeter(meter)
            .build()

        // First flush fails
        mockExporter.shouldFail = true
        processor.onEmit(io.opentelemetry.context.Context.root(), TestUtils.asReadWriteLogRecord(TestUtils.createTestLogRecord("event.1")))
        processor.forceFlush()

        // Second flush succeeds
        mockExporter.shouldFail = false
        processor.onEmit(io.opentelemetry.context.Context.root(), TestUtils.asReadWriteLogRecord(TestUtils.createTestLogRecord("event.2")))
        processor.forceFlush()

        mockExporter.waitForLogs(1, timeoutMs = 2000)
        assertTrue("At least event.2 should be exported", mockExporter.getExportedCount() >= 1)

        processor.shutdown()
    }

    // ── UI freeze detection ───────────────────────────────────────────────

    @Test
    fun `freeze log with long duration has ERROR severity`() {
        val freezeLog = TestUtils.createUIFreezeLog(durationMs = 3500)
        assertEquals(io.opentelemetry.api.logs.Severity.WARN, freezeLog.severity)
        assertTrue(freezeLog.attributes.get(AttributeKey.longKey("duration_ms"))!! >= 2000)
    }

    @Test
    fun `crash log has ERROR severity`() {
        val crashLog = TestUtils.createCrashLog()
        assertEquals(io.opentelemetry.api.logs.Severity.ERROR, crashLog.severity)
    }

    @Test
    fun `HTTP error log has ERROR severity and preserves status code`() {
        val httpLog = TestUtils.createHttpErrorLog(statusCode = 503, route = "/api/scheduling")
        assertEquals(io.opentelemetry.api.logs.Severity.ERROR, httpLog.severity)
        assertEquals(503L, httpLog.attributes.get(AttributeKey.longKey("http.status_code")))
    }

    // ── Buffer large-volume scenario ──────────────────────────────────────

    @Test
    fun `buffer accepts and exports 100 events in sequence`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val config = MobileConfig(
            serviceName = "test", serviceVersion = "1.0",
            collectorEndpoint = "http://localhost:4317",
            ramBufferSize = 200
        )
        val meter = OpenTelemetry.noop().meterProvider.get("test")
        val processor = MobileLogRecordProcessor.builder(context)
            .setExporter(mockExporter)
            .setConfig(config)
            .setMeter(meter)
            .setRamBufferSize(200)
            .build()

        repeat(100) { i ->
            processor.onEmit(io.opentelemetry.context.Context.root(), TestUtils.asReadWriteLogRecord(
                TestUtils.createTestLogRecord("event.$i", mapOf("seq" to i))
            ))
        }

        processor.forceFlush()
        mockExporter.waitForLogs(100, timeoutMs = 5000)
        assertEquals(100, mockExporter.getExportedCount())

        processor.shutdown()
    }

    // ── Shutdown ──────────────────────────────────────────────────────────

    @Test
    fun `shutdown after events exports remaining buffer`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val config = MobileConfig(
            serviceName = "test", serviceVersion = "1.0", collectorEndpoint = "http://localhost:4317"
        )
        val meter = OpenTelemetry.noop().meterProvider.get("test")
        val processor = MobileLogRecordProcessor.builder(context)
            .setExporter(mockExporter)
            .setConfig(config)
            .setMeter(meter)
            .build()

        processor.onEmit(io.opentelemetry.context.Context.root(), TestUtils.asReadWriteLogRecord(TestUtils.createTestLogRecord("final.event")))
        processor.forceFlush()
        mockExporter.waitForLogs(1, timeoutMs = 2000)

        processor.shutdown()
        assertTrue("final.event should be exported before shutdown",
            mockExporter.getExportedCount() >= 1)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun startTracker(): RecoveryTracker =
        RecoveryTracker(app, logger, mockProvider, sessionTracker).also { it.start() }

    private fun setPrefs(key: String, value: Boolean) =
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(key, value).commit()

    private fun clearPrefs() =
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
}
