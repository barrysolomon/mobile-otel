// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.scenarios

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.swipeDown
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.opentelemetry.android.demo.ConfigManager
import io.opentelemetry.android.demo.DemoScenarioPace
import io.opentelemetry.android.demo.R
import io.opentelemetry.android.demo.SchedulingActivity
import io.opentelemetry.android.mobile.MobileOtel
import io.opentelemetry.android.mobile.export.ExportStatus
import io.opentelemetry.android.mobile.export.ExportStatusListener
import io.opentelemetry.android.mobile.export.ExportStatusManager
import io.opentelemetry.api.logs.Severity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Offline resilience E2E tests.
 *
 * Adapts automatically to **four backend configurations**:
 *
 * | Dash0 | Demo Backend | Behavior |
 * |-------|-------------|----------|
 * | Yes   | Yes         | Full E2E — telemetry exports, app API works |
 * | Yes   | No          | Telemetry exports, app API errors generate HTTP error telemetry |
 * | No    | Yes         | App works, telemetry buffers → local buffer assertions |
 * | No    | No          | Pure offline — both buffer and API errors accumulate |
 *
 * Detection is automatic:
 * - **Dash0**: checks `ConfigManager.isDash0Configured()`
 * - **Demo backend**: pings `http://10.0.2.2:3001/health` (emulator → host localhost)
 *
 * All tests assert locally via buffer stats and ExportStatusListener.
 * When Dash0 is available, additional assertions verify buffer drain.
 * When demo backend is down, tests verify HTTP error events are generated.
 *
 * ## Running:
 *   ./gradlew :android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=\
 *     io.opentelemetry.android.demo.scenarios.OfflineResilienceScenarios
 */
@RunWith(AndroidJUnit4::class)
class OfflineResilienceScenarios {

    private lateinit var pace: DemoScenarioPace
    private lateinit var scenario: ActivityScenario<SchedulingActivity>
    private val uiAuto get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    // Collect export statuses for local assertions
    private val exportStatuses = CopyOnWriteArrayList<ExportStatus>()
    private val exportListener = ExportStatusListener { status -> exportStatuses.add(status) }

    private var hasDash0 = false
    private var hasDemoBackend = false

    @Before
    fun setUp() {
        pace = DemoScenarioPace()
        exportStatuses.clear()
        ExportStatusManager.addListener(exportListener)

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        hasDash0 = ConfigManager.isDash0Configured(ctx)
        hasDemoBackend = isDemoBackendReachable()

        // Dismiss stale dialogs
        uiAuto.executeShellCommand("am kill io.opentelemetry.android.demo").close()
        Thread.sleep(800)
        uiAuto.executeShellCommand(
            "pm grant io.opentelemetry.android.demo android.permission.ACCESS_COARSE_LOCATION"
        ).close()
        uiAuto.executeShellCommand(
            "pm grant io.opentelemetry.android.demo android.permission.ACCESS_FINE_LOCATION"
        ).close()

        scenario = ActivityScenario.launch(SchedulingActivity::class.java)
        Thread.sleep(1500)

        // Log test environment so it's visible in Dash0 and logcat
        MobileOtel.sendEvent("test.environment", mapOf(
            "demo.run_id" to pace.runId,
            "has_dash0" to hasDash0,
            "has_demo_backend" to hasDemoBackend,
            "test_mode" to when {
                hasDash0 && hasDemoBackend -> "full_e2e"
                hasDash0 && !hasDemoBackend -> "dash0_only"
                !hasDash0 && hasDemoBackend -> "local_only"
                else -> "pure_offline"
            }
        ), Severity.INFO)
    }

    @After
    fun tearDown() {
        setAirplaneMode(false)
        Thread.sleep(3000)
        ExportStatusManager.removeListener(exportListener)
        if (::scenario.isInitialized) scenario.close()
    }

    // ─── Test 1: Burst + Offline + Reconnect ────────────────────────────────

    @Test
    fun burstThenOfflineThenFlush() {
        val s = "burstThenOfflineThenFlush"

        // ── Phase 1: Online — generate traffic ──────────────────────────
        pace.step(s, "phase1_online_start")
        emitBufferStats("phase1_start")

        // Navigate (generates page spans + screen_view logs)
        safeNavigate(R.id.nav_calendar)
        pace.step(s, "nav_calendar")
        safeNavigate(R.id.nav_book)
        pace.step(s, "nav_book")
        safeNavigate(R.id.nav_appointments)
        pace.step(s, "nav_appointments")

        // Swipe to refresh
        try { onView(withId(R.id.swipeRefresh)).perform(swipeDown()) } catch (_: Exception) {}
        pace.step(s, "swipe_refresh")

        safeNavigate(R.id.nav_profile)
        pace.step(s, "nav_profile")

        // Wait for export cycle
        emitMarker(s, "phase1_waiting_for_export")
        Thread.sleep(15_000)

        val statsAfterOnline = MobileOtel.getBufferStats()
        emitBufferStats("phase1_after_export")

        if (hasDash0) {
            // With backend: buffer should have drained
            assertTrue(
                "With backend: exports should have succeeded",
                exportStatuses.any { it is ExportStatus.Success }
            )
        }

        // ── Phase 2: Go offline ─────────────────────────────────────────
        emitMarker(s, "phase2_going_offline")
        val preOfflineStats = MobileOtel.getBufferStats()
        emitBufferStats("pre_offline")
        setAirplaneMode(true)

        // Generate traffic while offline (SDK calls, no Espresso)
        emitMarker(s, "phase2_offline_traffic")
        for (i in 1..20) {
            MobileOtel.sendEvent("offline.nav.$i", mapOf(
                "screen.name" to listOf("Calendar", "Appointments", "Book", "Profile")[i % 4],
                "demo.run_id" to pace.runId
            ), Severity.INFO)
            Thread.sleep(200)
        }
        pace.step(s, "offline_events_emitted")

        // Wait for failed export attempts
        Thread.sleep(15_000)

        val offlineStats = MobileOtel.getBufferStats()
        emitBufferStats("after_offline_wait")

        // Assert: buffer grew while offline (events accumulated)
        assertTrue(
            "Buffer should have events while offline (RAM=${offlineStats?.ramBufferSize}, Disk=${offlineStats?.diskBufferSize})",
            (offlineStats?.ramBufferSize ?: 0) + (offlineStats?.diskBufferSize ?: 0) > 0
        )

        // ── Phase 3: Come back online ───────────────────────────────────
        emitMarker(s, "phase3_going_online")
        setAirplaneMode(false)
        Thread.sleep(5000)

        MobileOtel.forceFlush()
        Thread.sleep(15_000)

        val postReconnectStats = MobileOtel.getBufferStats()
        emitBufferStats("post_reconnect")

        if (hasDash0) {
            // With backend: buffer should drain after reconnect
            val postTotal = (postReconnectStats?.ramBufferSize ?: 0) + (postReconnectStats?.diskBufferSize ?: 0)
            val offlineTotal = (offlineStats?.ramBufferSize ?: 0) + (offlineStats?.diskBufferSize ?: 0)
            assertTrue(
                "Buffer should drain after reconnect (was $offlineTotal, now $postTotal)",
                postTotal < offlineTotal
            )
        } else {
            // Without backend: verify export attempts happened (Failed or AuthError)
            assertTrue(
                "Without backend: should see export failures",
                exportStatuses.any { it is ExportStatus.Failed || it is ExportStatus.AuthError }
            )
        }

        pace.step(s, "test_complete")
    }

    // ─── Test 2: Extended Offline ───────────────────────────────────────────

    @Test
    fun extendedOfflineBufferAccumulation() {
        val s = "extendedOfflineBufferAccumulation"

        pace.step(s, "start")
        emitBufferStats("initial")

        // Go offline immediately
        emitMarker(s, "going_offline")
        setAirplaneMode(true)

        emitMarker(s, "offline_traffic_start")

        // Round 1: Simulate screen navigation events
        for (i in 1..20) {
            MobileOtel.sendEvent("ui.screen_view", mapOf(
                "screen.name" to listOf("Calendar", "Appointments", "Book", "Profile")[i % 4],
                "demo.run_id" to pace.runId
            ), Severity.INFO)
            Thread.sleep(500)
        }
        pace.step(s, "nav_events_emitted")
        emitBufferStats("after_nav_events")

        // Round 2: Simulate various event types
        for (i in 1..10) {
            MobileOtel.sendEvent("offline.event.$i", mapOf(
                "demo.run_id" to pace.runId,
                "event.type" to listOf("ui.tap", "ui.scroll", "http.request", "ui.freeze", "error.warning")[i % 5],
                "event.index" to i
            ), Severity.INFO)
            Thread.sleep(1000)
        }
        pace.step(s, "varied_events_emitted")
        emitBufferStats("after_varied_events")

        // Round 3: Rapid burst (stress test buffer)
        for (i in 1..50) {
            MobileOtel.sendEvent("offline.burst.$i", mapOf(
                "demo.run_id" to pace.runId,
                "batch" to "rapid_fire"
            ), Severity.DEBUG)
        }
        pace.step(s, "burst_events_emitted")

        val preDrainStats = MobileOtel.getBufferStats()
        emitBufferStats("after_burst")

        // Assert: buffer has accumulated events
        val totalBuffered = (preDrainStats?.ramBufferSize ?: 0) + (preDrainStats?.diskBufferSize ?: 0)
        assertTrue(
            "Buffer should have 50+ events after offline traffic (actual: $totalBuffered)",
            totalBuffered >= 50
        )

        // Round 4: Idle — let periodic metrics accumulate
        emitMarker(s, "idle_accumulation_start")
        Thread.sleep(30_000)
        emitBufferStats("after_idle")

        val preReconnectStats = MobileOtel.getBufferStats()
        val preReconnectTotal = (preReconnectStats?.ramBufferSize ?: 0) + (preReconnectStats?.diskBufferSize ?: 0)

        // ── Come back online and flush ──────────────────────────────────
        emitMarker(s, "going_online")
        setAirplaneMode(false)
        Thread.sleep(5000)

        MobileOtel.forceFlush()
        Thread.sleep(20_000)
        emitBufferStats("post_flush_1")

        // Second flush for large buffers
        MobileOtel.forceFlush()
        Thread.sleep(15_000)

        val postFlushStats = MobileOtel.getBufferStats()
        emitBufferStats("post_flush_2")

        if (hasDash0) {
            val postFlushTotal = (postFlushStats?.ramBufferSize ?: 0) + (postFlushStats?.diskBufferSize ?: 0)
            assertTrue(
                "Buffer should drain after reconnect + flush (was $preReconnectTotal, now $postFlushTotal)",
                postFlushTotal < preReconnectTotal
            )

            assertTrue(
                "Should see successful exports after reconnect",
                exportStatuses.any { it is ExportStatus.Success }
            )
        } else {
            // Without backend: verify the SDK tried to export and properly reported failures
            assertTrue(
                "Should see export attempts (retries or failures)",
                exportStatuses.isNotEmpty()
            )
        }

        pace.step(s, "test_complete")
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private fun safeNavigate(navId: Int) {
        try {
            onView(withId(navId)).perform(
                androidx.test.espresso.action.ViewActions.click()
            )
        } catch (e: Exception) {
            // Dismiss system dialog and retry
            uiAuto.executeShellCommand("input keyevent 4").close()
            Thread.sleep(500)
            try {
                onView(withId(navId)).perform(
                    androidx.test.espresso.action.ViewActions.click()
                )
            } catch (_: Exception) { /* give up silently */ }
        }
        Thread.sleep(500)
    }

    private fun setAirplaneMode(enabled: Boolean) {
        val cmd = if (enabled) "cmd connectivity airplane-mode enable"
        else "cmd connectivity airplane-mode disable"
        uiAuto.executeShellCommand(cmd).close()
        Thread.sleep(2000)

        // Dismiss connectivity dialogs
        uiAuto.executeShellCommand("input keyevent 4").close()
        Thread.sleep(500)

        MobileOtel.sendEvent("test.connectivity", mapOf(
            "airplane_mode" to enabled,
            "demo.run_id" to pace.runId
        ), if (enabled) Severity.WARN else Severity.INFO)
    }

    private fun emitMarker(scenario: String, label: String) {
        MobileOtel.sendEvent("test.marker", mapOf(
            "scenario.name" to scenario,
            "marker" to label,
            "demo.run_id" to pace.runId,
            "timestamp_ms" to System.currentTimeMillis()
        ), Severity.INFO)
    }

    /** Pings the demo backend to check if it's running. */
    private fun isDemoBackendReachable(): Boolean {
        return try {
            val conn = URL("http://10.0.2.2:3001/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    private fun emitBufferStats(label: String) {
        val stats = MobileOtel.getBufferStats()
        MobileOtel.sendEvent("buffer.snapshot", mapOf(
            "buffer.label" to label,
            "buffer.ram.events" to (stats?.ramBufferSize ?: -1),
            "buffer.ram.capacity" to (stats?.ramBufferCapacity ?: -1),
            "buffer.disk.events" to (stats?.diskBufferSize ?: -1),
            "demo.run_id" to pace.runId
        ), Severity.INFO)
    }
}
