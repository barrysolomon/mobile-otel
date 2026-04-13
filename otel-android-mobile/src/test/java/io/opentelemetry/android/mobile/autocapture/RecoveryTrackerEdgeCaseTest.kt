/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.autocapture

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.opentelemetry.android.mobile.MobileLoggerProvider
import io.opentelemetry.android.mobile.testing.MockLogRecordExporter
import io.opentelemetry.api.common.AttributeKey
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Edge-case tests for [RecoveryTracker] — extends [RecoveryTrackerTest] with
 * downtime calculation, trim-memory thresholds, multi-cycle recovery, and
 * concurrent marker scenarios.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RecoveryTrackerEdgeCaseTest {

    private lateinit var app: Application
    private lateinit var mockExporter: MockLogRecordExporter
    private lateinit var logger: Logger
    private lateinit var sdkLoggerProvider: SdkLoggerProvider
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

        sdkLoggerProvider = SdkLoggerProvider.builder()
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(mockExporter))
            .build()
        logger = sdkLoggerProvider.get("test-recovery-edge")

        sessionTracker = SessionTracker(AutoCaptureOptions())
        mockProvider = mockk(relaxed = true)

        clearAllPrefs()
    }

    // ========== 1. Downtime calculation ==========

    @Test
    fun `downtime_ms is calculated from last session end to now`() {
        val pastTime = System.currentTimeMillis() - 5000L // 5 seconds ago
        setPrefsLong(KEY_LAST_SESSION_END_MS, pastTime)
        setPrefsBoolean(KEY_CRASH_MARKER, true)

        startTracker()

        val recoveryEvents = findByBody("app.recovery")
        assertEquals(1, recoveryEvents.size)

        val downtimeMs = recoveryEvents[0].attributes
            .get(AttributeKey.longKey("mobile.downtime_ms"))
        // Downtime should be approximately 5000ms (allow some test execution time)
        assertTrue(
            downtimeMs != null && downtimeMs >= 4500 && downtimeMs <= 10000,
            "Downtime should be ~5000ms, got $downtimeMs"
        )
    }

    // ========== 2. Downtime with no previous session ==========

    @Test
    fun `downtime is 0 when no previous session end recorded`() {
        // lastSessionEnd defaults to 0 when not set
        setPrefsBoolean(KEY_CRASH_MARKER, true)

        startTracker()

        val recoveryEvents = findByBody("app.recovery")
        assertEquals(1, recoveryEvents.size)

        val downtimeMs = recoveryEvents[0].attributes
            .get(AttributeKey.longKey("mobile.downtime_ms"))
        assertEquals(0L, downtimeMs, "Downtime should be 0 when no previous session end")
    }

    // ========== 3. All markers set — crash takes priority, all cleared ==========

    @Test
    fun `all markers set crash takes priority and all markers cleared`() {
        setPrefsBoolean(KEY_CRASH_MARKER, true)
        setPrefsBoolean(KEY_ANR_MARKER, true)
        setPrefsBoolean(KEY_LOW_MEMORY_MARKER, true)
        setPrefsBoolean(KEY_SESSION_ACTIVE, true)

        val tracker = startTracker()

        assertEquals("crash", tracker.getLastRecoveryType())

        // All markers should be cleared after handling
        val prefs = getPrefs()
        assertFalse(prefs.getBoolean(KEY_CRASH_MARKER, false), "Crash marker should be cleared")
        assertFalse(prefs.getBoolean(KEY_ANR_MARKER, false), "ANR marker should be cleared")
        assertFalse(prefs.getBoolean(KEY_LOW_MEMORY_MARKER, false), "Low memory marker should be cleared")
    }

    // ========== 4. onTrimMemory TRIM_MEMORY_COMPLETE ==========

    @Test
    fun `onTrimMemory TRIM_MEMORY_COMPLETE sets low memory marker`() {
        val tracker = startTracker()
        clearAllPrefs() // clear session_active set by start()

        tracker.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)

        assertTrue(
            getPrefs().getBoolean(KEY_LOW_MEMORY_MARKER, false),
            "TRIM_MEMORY_COMPLETE should set low_memory_marker"
        )
    }

    // ========== 5. onTrimMemory TRIM_MEMORY_RUNNING_CRITICAL ==========

    @Test
    fun `onTrimMemory TRIM_MEMORY_RUNNING_CRITICAL sets low memory marker`() {
        val tracker = startTracker()
        clearAllPrefs()

        tracker.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)

        assertTrue(
            getPrefs().getBoolean(KEY_LOW_MEMORY_MARKER, false),
            "TRIM_MEMORY_RUNNING_CRITICAL should set low_memory_marker"
        )
    }

    // ========== 6. onTrimMemory below threshold does NOT set marker ==========

    @Test
    fun `onTrimMemory UI_HIDDEN does not set low memory marker`() {
        val tracker = startTracker()
        clearAllPrefs()

        tracker.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)

        assertFalse(
            getPrefs().getBoolean(KEY_LOW_MEMORY_MARKER, false),
            "TRIM_MEMORY_UI_HIDDEN should NOT set low_memory_marker"
        )
    }

    @Test
    fun `onTrimMemory RUNNING_MODERATE does not set low memory marker`() {
        val tracker = startTracker()
        clearAllPrefs()

        tracker.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)

        assertFalse(
            getPrefs().getBoolean(KEY_LOW_MEMORY_MARKER, false),
            "TRIM_MEMORY_RUNNING_MODERATE should NOT set low_memory_marker"
        )
    }

    @Test
    fun `onTrimMemory RUNNING_LOW does not set low memory marker`() {
        val tracker = startTracker()
        clearAllPrefs()

        tracker.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

        assertFalse(
            getPrefs().getBoolean(KEY_LOW_MEMORY_MARKER, false),
            "TRIM_MEMORY_RUNNING_LOW should NOT set low_memory_marker"
        )
    }

    // ========== 7. onLowMemory ==========

    @Test
    fun `onLowMemory sets low memory marker`() {
        val tracker = startTracker()
        clearAllPrefs()

        tracker.onLowMemory()

        assertTrue(
            getPrefs().getBoolean(KEY_LOW_MEMORY_MARKER, false),
            "onLowMemory() should set low_memory_marker"
        )
    }

    // ========== 8. markCleanShutdown ==========

    @Test
    fun `markCleanShutdown sets session_active false and records end time`() {
        val tracker = startTracker()
        val beforeShutdown = System.currentTimeMillis()

        tracker.markCleanShutdown()

        val prefs = getPrefs()
        assertFalse(
            prefs.getBoolean(KEY_SESSION_ACTIVE, true),
            "session_active should be false after clean shutdown"
        )

        val endMs = prefs.getLong(KEY_LAST_SESSION_END_MS, 0)
        assertTrue(
            endMs >= beforeShutdown,
            "last_session_end_ms ($endMs) should be >= time before shutdown ($beforeShutdown)"
        )
    }

    // ========== 9. Multiple recovery cycles ==========

    @Test
    fun `multiple recovery cycles work correctly`() {
        // Cycle 1: crash recovery
        setPrefsBoolean(KEY_CRASH_MARKER, true)
        val tracker1 = startTracker()
        assertEquals("crash", tracker1.getLastRecoveryType())
        tracker1.stop()

        // Verify crash marker was cleared
        assertFalse(getPrefs().getBoolean(KEY_CRASH_MARKER, false))

        // Simulate clean shutdown
        tracker1.markCleanShutdown()
        mockExporter.clear()

        // Cycle 2: clean start (no markers set, session_active is false after clean shutdown)
        // Need a fresh logger/tracker for the new "app launch"
        val tracker2 = createTracker()
        tracker2.start()

        assertEquals("clean_start", tracker2.getLastRecoveryType())
        assertEquals(0, findByBody("app.recovery").size, "No recovery event on clean start")
        tracker2.stop()
    }

    // ========== 10. ANR recovery emits both app.anr and app.recovery with correct session.id ==========

    @Test
    fun `ANR recovery emits both app_anr and app_recovery with matching session id`() {
        setPrefsBoolean(KEY_ANR_MARKER, true)

        startTracker()

        val anrEvents = findByBody("app.anr")
        val recoveryEvents = findByBody("app.recovery")

        assertEquals(1, anrEvents.size, "Expected exactly one app.anr event")
        assertEquals(1, recoveryEvents.size, "Expected exactly one app.recovery event")

        // Both events should have the same session.id
        val anrSessionId = anrEvents[0].attributes
            .get(AttributeKey.stringKey("mobile.session.id"))
        val recoverySessionId = recoveryEvents[0].attributes
            .get(AttributeKey.stringKey("mobile.session.id"))

        assertTrue(anrSessionId != null, "app.anr should have session.id")
        assertTrue(recoverySessionId != null, "app.recovery should have session.id")
        assertEquals(anrSessionId, recoverySessionId, "Both events should share the same session.id")

        // Verify recovery_type on the recovery event
        val recoveryType = recoveryEvents[0].attributes
            .get(AttributeKey.stringKey("mobile.recovery_type"))
        assertEquals("anr_force_kill", recoveryType)

        // Verify anr-specific attribute
        val userAction = anrEvents[0].attributes
            .get(AttributeKey.stringKey("mobile.anr.user_action"))
        assertEquals("force_close", userAction)
    }

    // ========== Helpers ==========

    private fun startTracker(): RecoveryTracker {
        return createTracker().also { it.start() }
    }

    private fun createTracker(): RecoveryTracker {
        return RecoveryTracker(app, logger, mockProvider, sessionTracker)
    }

    private fun setPrefsBoolean(key: String, value: Boolean) {
        getPrefs().edit().putBoolean(key, value).commit()
    }

    private fun setPrefsLong(key: String, value: Long) {
        getPrefs().edit().putLong(key, value).commit()
    }

    private fun clearAllPrefs() {
        getPrefs().edit().clear().commit()
    }

    private fun getPrefs() = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun findByBody(body: String) = mockExporter.findLogs { it.body.asString() == body }
}
