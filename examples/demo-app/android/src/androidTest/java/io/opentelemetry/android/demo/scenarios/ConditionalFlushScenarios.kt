// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.scenarios

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.swipeDown
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.opentelemetry.android.demo.DemoScenarioBase
import io.opentelemetry.android.demo.R
import io.opentelemetry.android.mobile.MobileOtel
import io.opentelemetry.api.logs.Severity
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Conditional flush scenarios — show the SDK's policy-driven export model.
 *
 * In CONDITIONAL mode the ring buffer silently accumulates events; nothing is
 * exported until a policy match triggers a selective flushWindow().  These tests
 * make that behaviour visible in Dash0:
 *
 *   1. Many "quiet" transactions fill the buffer → no export yet.
 *   2. A single error event matches a default policy → flushWindow fires.
 *   3. All buffered events arrive in Dash0 together, timestamped from the start
 *      of the session — the full breadcrumb trail, not just the error.
 *
 * Run all:
 *   ./gradlew :android:connectedDebugAndroidTest
 *
 * Run one:
 *   ./gradlew :android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=\
 *     io.opentelemetry.android.demo.scenarios.ConditionalFlushScenarios#quietBufferThenCrashFlush
 */
@RunWith(AndroidJUnit4::class)
class ConditionalFlushScenarios : DemoScenarioBase() {

    /**
     * Scenario 9: Quiet buffer accumulation → crash triggers flush
     *
     * Signals in Dash0:
     *   - 20 x user.transaction events (appear all at once on flush, not trickled)
     *   - 4 navigation breadcrumbs accumulated silently
     *   - buffer.snapshot events showing RAM fill before and after flush
     *   - app.crash triggers crash-recovery policy → flushWindow(5min)
     *   - app.crash_recovery marks session recovery
     *
     * Story: the buffer absorbed 24+ events without exporting anything;
     * the moment the crash signal arrived everything was flushed together.
     *
     * Flow: launch → 20 silent transactions → navigate 4 screens → crash flush
     */
    @Test
    fun quietBufferThenCrashFlush() {
        val s = "quietBufferThenCrashFlush"

        pace.step(s, "app_launched")
        emitBufferStats("session_start")

        // Emit 20 "quiet" transactions — none match any default policy so they
        // accumulate in the RAM ring buffer without being exported.
        pace.step(s, "loading_silent_transactions")
        repeat(20) { i ->
            MobileOtel.sendEvent(
                "user.transaction",
                mapOf(
                    "transaction.index"  to i + 1,
                    "transaction.type"   to "appointment_view",
                    "transaction.result" to "success",
                    "demo.run_id"        to pace.runId,
                    "scenario.name"      to s
                ),
                Severity.INFO
            )
        }
        pace.step(s, "silent_transactions_buffered")
        emitBufferStats("after_20_transactions")

        // Navigate through screens — each nav generates a breadcrumb span that
        // also sits in the buffer, building the pre-crash trail.
        pace.step(s, "navigate_to_calendar")
        navigateTo(R.id.nav_calendar)

        pace.step(s, "navigate_to_book")
        navigateTo(R.id.nav_book)

        pace.step(s, "navigate_to_appointments")
        navigateTo(R.id.nav_appointments)

        pace.step(s, "navigate_to_directions")
        navigateTo(R.id.nav_directions)

        pace.step(s, "about_to_flush")
        emitBufferStats("pre_crash_flush")

        // Emit app.crash — matches the crash-recovery default policy.
        // PolicyEvaluator sees event.name == "app.crash" → flushWindow(5).
        // All 24+ buffered events are exported to Dash0 in a single batch.
        pace.step(s, "crash_triggered")
        MobileOtel.sendEvent(
            "app.crash",
            mapOf(
                "crash.type"    to "RuntimeException",
                "crash.message" to "Simulated crash after quiet session",
                "crash.thread"  to "main",
                "demo.run_id"   to pace.runId,
                "scenario.name" to s
            ),
            Severity.ERROR
        )

        // Allow the async policy evaluation and flushWindow to complete
        Thread.sleep(3000)

        pace.step(s, "flush_complete")
        emitBufferStats("post_crash_flush")

        // Recovery event — same shape as a real post-crash relaunch
        MobileOtel.sendEvent(
            "app.crash_recovery",
            mapOf(
                "recovery.type"          to "simulated",
                "recovery.flush_minutes" to 5,
                "demo.run_id"            to pace.runId,
                "scenario.name"          to s
            ),
            Severity.WARN
        )
        Thread.sleep(1000)

        pace.step(s, "scenario_complete")
    }

    /**
     * Scenario 10: HTTP error triggers flush of buffered API requests
     *
     * Signals in Dash0:
     *   - 15 x api.request events silently buffered
     *   - Navigation spans and tap breadcrumbs in buffer
     *   - buffer.snapshot events at key milestones
     *   - http.error with status 500 triggers http-error-detector policy → flushWindow(5min)
     *   - All buffered events arrive in Dash0 together after the error
     *
     * Story: the SDK watched 15 successful API calls without exporting; the first
     * server error immediately surfaced the entire session history to Dash0.
     *
     * Flow: launch → 15 silent api.request → appointments → trigger HTTP 500 →
     *        emit http.error (policy match) → flush → calendar
     */
    @Test
    fun httpErrorFlush() {
        val s = "httpErrorFlush"

        pace.step(s, "app_launched")
        emitBufferStats("session_start")

        // Simulate 15 successful API calls — no policy match, all buffered silently.
        pace.step(s, "loading_api_requests")
        repeat(15) { i ->
            MobileOtel.sendEvent(
                "api.request",
                mapOf(
                    "request.index"       to i + 1,
                    "request.endpoint"    to "/appointments",
                    "request.status_code" to 200,
                    "request.duration_ms" to (80 + i * 3),
                    "demo.run_id"         to pace.runId,
                    "scenario.name"       to s
                ),
                Severity.INFO
            )
        }
        pace.step(s, "api_requests_buffered")
        emitBufferStats("after_15_requests")

        // Navigate to appointments to set context for the HTTP error
        pace.step(s, "navigate_to_appointments")
        navigateTo(R.id.nav_appointments)

        pace.step(s, "pre_error")
        emitBufferStats("pre_http_error")

        // Arm the HTTP 500 flag via DebugToolbar, then trigger the actual request
        pace.step(s, "trigger_http_500")
        clickDebugButton(R.id.btnTriggerHttp500)

        // Pull-to-refresh fires the HTTP request that returns 503 (forceNextFetchError=true).
        // The OTelNetworkInterceptor logs the error span; we also emit http.error explicitly
        // to guarantee the policy trigger fires regardless of network timing.
        onView(withId(R.id.swipeRefresh)).perform(swipeDown())
        Thread.sleep(2000)

        // Emit http.error — matches http-error-detector default policy → flushWindow(5).
        // All 15 buffered api.request events + navigation breadcrumbs exported together.
        pace.step(s, "http_error_emitted")
        MobileOtel.sendEvent(
            "http.error",
            mapOf(
                "http.status_code" to 500,
                "http.url"         to "/appointments",
                "error.type"       to "http.server_error",
                "error.message"    to "HTTP 500 Internal Server Error",
                "demo.run_id"      to pace.runId,
                "scenario.name"    to s
            ),
            Severity.ERROR
        )

        // Allow policy evaluation + flushWindow to complete
        Thread.sleep(3000)

        pace.step(s, "flush_complete")
        emitBufferStats("post_http_error_flush")

        // Navigate away to show recovery
        pace.step(s, "navigate_to_calendar_recovery")
        navigateTo(R.id.nav_calendar)

        pace.step(s, "scenario_complete")
    }
}
