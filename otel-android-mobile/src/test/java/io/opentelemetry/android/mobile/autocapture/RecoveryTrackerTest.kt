/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.autocapture

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.opentelemetry.android.mobile.MobileLoggerProvider
import io.opentelemetry.android.mobile.testing.MockLogRecordExporter
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [RecoveryTracker] - validates that crash, ANR, and OOM events are detected
 * on app restart and the correct telemetry is emitted for each failure mode.
 *
 * Covers user journeys:
 * - App crash recovery (uncaught exception)
 * - ANR recovery (force kill after ANR)
 * - Low-memory kill recovery (OOM)
 * - Session force-kill (system kills active session)
 * - Clean start (no recovery needed)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecoveryTrackerTest {

    private lateinit var app: Application
    private lateinit var mockExporter: MockLogRecordExporter
    private lateinit var logger: Logger
    private lateinit var sessionTracker: SessionTracker
    private lateinit var mockProvider: MobileLoggerProvider

    companion object {
        private const val PREFS_NAME = "otel_recovery"
        private const val KEY_SESSION_ACTIVE = "session_active"
        private const val KEY_CRASH_MARKER = "crash_marker"
        private const val KEY_LOW_MEMORY_MARKER = "low_memory_marker"
        private const val KEY_ANR_MARKER = "anr_marker"
        private const val KEY_LAST_SESSION_END_MS = "last_session_end_ms"
    }

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()

        mockExporter = MockLogRecordExporter()

        // Use SimpleLogRecordProcessor so emit() calls export synchronously (no batching delay)
        val sdkLoggerProvider = SdkLoggerProvider.builder()
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(mockExporter))
            .build()
        logger = sdkLoggerProvider.get("test-recovery")

        sessionTracker = SessionTracker(AutoCaptureOptions())

        // Relax the mock so forceFlush() returns a default value without explicit stubbing
        mockProvider = mockk(relaxed = true)

        // Start each test with a clean prefs slate
        clearAllPrefs()
    }

    // ========== Clean Start ==========

    @Test
    fun `clean start emits no events`() {
        startTracker()

        assertEquals(0, mockExporter.getExportedCount(), "No events expected on clean start")
    }

    @Test
    fun `clean start sets lastRecoveryType to clean_start`() {
        val tracker = startTracker()

        assertEquals("clean_start", tracker.getLastRecoveryType())
    }

    // ========== Crash Recovery ==========

    @Test
    fun `crash marker triggers app_crash event`() {
        setPrefsBoolean(KEY_CRASH_MARKER, true)

        startTracker()

        val crashEvents = findByBody("app.crash")
        assertEquals(1, crashEvents.size, "Expected exactly one app.crash event on crash recovery")
    }

    @Test
    fun `crash marker triggers app_recovery event with recovery_type crash`() {
        setPrefsBoolean(KEY_CRASH_MARKER, true)

        startTracker()

        val recoveryEvents = findByBody("app.recovery")
        assertEquals(1, recoveryEvents.size, "Expected exactly one app.recovery event")

        val recoveryType = recoveryEvents[0].attributes
            .get(io.opentelemetry.api.common.AttributeKey.stringKey("recovery_type"))
        assertEquals("crash", recoveryType)
    }

    @Test
    fun `crash recovery sets lastRecoveryType to crash`() {
        setPrefsBoolean(KEY_CRASH_MARKER, true)

        val tracker = startTracker()

        assertEquals("crash", tracker.getLastRecoveryType())
    }

    @Test
    fun `crash marker is cleared from prefs after handling`() {
        setPrefsBoolean(KEY_CRASH_MARKER, true)

        startTracker()

        val prefs = getPrefs()
        assertFalse(prefs.getBoolean(KEY_CRASH_MARKER, false), "Crash marker should be cleared after recovery")
    }

    // ========== ANR Recovery ==========

    @Test
    fun `ANR marker triggers app_anr event`() {
        setPrefsBoolean(KEY_ANR_MARKER, true)

        startTracker()

        val anrEvents = findByBody("app.anr")
        assertEquals(1, anrEvents.size, "Expected exactly one app.anr event on ANR recovery")
    }

    @Test
    fun `ANR marker triggers app_recovery event with recovery_type anr_force_kill`() {
        setPrefsBoolean(KEY_ANR_MARKER, true)

        startTracker()

        val recoveryEvents = findByBody("app.recovery")
        assertEquals(1, recoveryEvents.size)

        val recoveryType = recoveryEvents[0].attributes
            .get(io.opentelemetry.api.common.AttributeKey.stringKey("recovery_type"))
        assertEquals("anr_force_kill", recoveryType)
    }

    @Test
    fun `ANR recovery sets lastRecoveryType to anr_force_kill`() {
        setPrefsBoolean(KEY_ANR_MARKER, true)

        val tracker = startTracker()

        assertEquals("anr_force_kill", tracker.getLastRecoveryType())
    }

    @Test
    fun `ANR marker is cleared from prefs after handling`() {
        setPrefsBoolean(KEY_ANR_MARKER, true)

        startTracker()

        val prefs = getPrefs()
        assertFalse(prefs.getBoolean(KEY_ANR_MARKER, false), "ANR marker should be cleared after recovery")
    }

    // ========== Low-Memory Kill Recovery ==========

    @Test
    fun `low memory marker triggers app_recovery with recovery_type low_memory_kill`() {
        setPrefsBoolean(KEY_LOW_MEMORY_MARKER, true)

        startTracker()

        val recoveryEvents = findByBody("app.recovery")
        assertEquals(1, recoveryEvents.size)

        val recoveryType = recoveryEvents[0].attributes
            .get(io.opentelemetry.api.common.AttributeKey.stringKey("recovery_type"))
        assertEquals("low_memory_kill", recoveryType)
    }

    @Test
    fun `low memory recovery does not emit app_crash or app_anr events`() {
        setPrefsBoolean(KEY_LOW_MEMORY_MARKER, true)

        startTracker()

        assertEquals(0, findByBody("app.crash").size, "No app.crash event expected for OOM kill")
        assertEquals(0, findByBody("app.anr").size, "No app.anr event expected for OOM kill")
    }

    @Test
    fun `low memory recovery sets lastRecoveryType to low_memory_kill`() {
        setPrefsBoolean(KEY_LOW_MEMORY_MARKER, true)

        val tracker = startTracker()

        assertEquals("low_memory_kill", tracker.getLastRecoveryType())
    }

    @Test
    fun `low memory marker is cleared from prefs after handling`() {
        setPrefsBoolean(KEY_LOW_MEMORY_MARKER, true)

        startTracker()

        val prefs = getPrefs()
        assertFalse(prefs.getBoolean(KEY_LOW_MEMORY_MARKER, false), "Low memory marker should be cleared")
    }

    // ========== System Force Kill (session active, no specific marker) ==========

    @Test
    fun `active session without specific marker means system_force_kill`() {
        setPrefsBoolean(KEY_SESSION_ACTIVE, true)

        val tracker = startTracker()

        assertEquals("system_force_kill", tracker.getLastRecoveryType())
    }

    @Test
    fun `system force kill emits app_recovery event`() {
        setPrefsBoolean(KEY_SESSION_ACTIVE, true)

        startTracker()

        val recoveryEvents = findByBody("app.recovery")
        assertEquals(1, recoveryEvents.size)
    }

    // ========== Marker API ==========

    @Test
    fun `markCrashForNextStart writes crash marker to prefs`() {
        val tracker = startTracker()
        clearAllPrefs() // remove session_active set by start()

        tracker.markCrashForNextStart()

        assertTrue(getPrefs().getBoolean(KEY_CRASH_MARKER, false))
    }

    @Test
    fun `markAnrForNextStart writes ANR marker to prefs`() {
        val tracker = startTracker()

        tracker.markAnrForNextStart()

        assertTrue(getPrefs().getBoolean(KEY_ANR_MARKER, false))
    }

    @Test
    fun `markLowMemoryForNextStart writes low memory marker to prefs`() {
        val tracker = startTracker()

        tracker.markLowMemoryForNextStart()

        assertTrue(getPrefs().getBoolean(KEY_LOW_MEMORY_MARKER, false))
    }

    @Test
    fun `clearAnrMarker removes ANR marker from prefs`() {
        val tracker = startTracker()
        tracker.markAnrForNextStart()
        assertTrue(getPrefs().getBoolean(KEY_ANR_MARKER, false))

        tracker.clearAnrMarker()

        assertFalse(getPrefs().getBoolean(KEY_ANR_MARKER, false))
    }

    // ========== Priority ordering (crash wins over ANR, ANR wins over OOM) ==========

    @Test
    fun `crash marker takes priority over ANR marker`() {
        setPrefsBoolean(KEY_CRASH_MARKER, true)
        setPrefsBoolean(KEY_ANR_MARKER, true)

        val tracker = startTracker()

        assertEquals("crash", tracker.getLastRecoveryType())
    }

    @Test
    fun `ANR marker takes priority over low memory marker`() {
        setPrefsBoolean(KEY_ANR_MARKER, true)
        setPrefsBoolean(KEY_LOW_MEMORY_MARKER, true)

        val tracker = startTracker()

        assertEquals("anr_force_kill", tracker.getLastRecoveryType())
    }

    // ========== Helpers ==========

    private fun startTracker(): RecoveryTracker {
        return RecoveryTracker(app, logger, mockProvider, sessionTracker).also { it.start() }
    }

    private fun setPrefsBoolean(key: String, value: Boolean) {
        getPrefs().edit().putBoolean(key, value).commit()
    }

    private fun clearAllPrefs() {
        getPrefs().edit().clear().commit()
    }

    private fun getPrefs() = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun findByBody(body: String) = mockExporter.findLogs { it.body.toString() == body }
}
