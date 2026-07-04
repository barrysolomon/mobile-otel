// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.scenarios

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.opentelemetry.android.demo.DemoScenarioBase
import io.opentelemetry.android.demo.R
import io.opentelemetry.android.demo.SchedulingActivity
import io.opentelemetry.android.mobile.MobileOtel
import io.opentelemetry.api.logs.Severity
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fault injection scenarios — isolated fault triggers that demonstrate
 * how the SDK detects and exports device/app health signals to Dash0.
 *
 * Run one:
 *   ./gradlew :android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=\
 *     io.opentelemetry.android.demo.scenarios.FaultScenarios#jankDetection
 */
@RunWith(AndroidJUnit4::class)
class FaultScenarios : DemoScenarioBase() {

    /**
     * Scenario 4: Jank detection
     * Signals in Dash0: ui.jank event with frame_duration_ms > 16,
     *   jank_detector metrics, possible jank.severe event at > 100ms
     *
     * Flow: launch → navigate 2 screens → trigger jank
     */
    @Test
    fun jankDetection() {
        val s = "jankDetection"

        pace.step(s, "app_launched")

        pace.step(s, "navigate_to_appointments")
        navigateTo(R.id.nav_appointments)

        pace.step(s, "navigate_to_book")
        navigateTo(R.id.nav_book)

        pace.step(s, "trigger_jank")
        clickDebugButton(R.id.btnTriggerJank)
        Thread.sleep(500)

        pace.step(s, "jank_triggered")

        pace.step(s, "navigate_to_calendar_recovery")
        navigateTo(R.id.nav_calendar)

        pace.step(s, "scenario_complete")
    }

    /**
     * Scenario 5: Memory pressure
     * Signals in Dash0: device.memory.low event with available_mb,
     *   predictive health metrics, possible pre-emptive flush
     *
     * Flow: launch → navigate 3 screens → trigger memory pressure
     */
    @Test
    fun memoryPressure() {
        val s = "memoryPressure"

        pace.step(s, "app_launched")

        pace.step(s, "navigate_to_calendar")
        navigateTo(R.id.nav_calendar)

        pace.step(s, "navigate_to_appointments")
        navigateTo(R.id.nav_appointments)

        pace.step(s, "navigate_to_directions")
        navigateTo(R.id.nav_directions)

        pace.step(s, "trigger_memory_pressure")
        clickDebugButton(R.id.btnTriggerMemory)
        Thread.sleep(2000)

        pace.step(s, "memory_pressure_triggered")

        pace.step(s, "scenario_complete")
    }

    /**
     * Scenario 6: ANR detection
     * Signals in Dash0: anr.risk event from SDK monitor, possible pre-emptive flush,
     *   breadcrumb "trigger_anr"
     *
     * Note: Uses 6s main-thread block — enough for SDK ANR monitor to fire,
     * short enough to avoid the OS ANR dialog (which would need UIAutomator).
     *
     * Flow: launch → Appointments → trigger ANR block → recover
     */
    @Test
    fun anrDetection() {
        val s = "anrDetection"

        pace.step(s, "app_launched")

        pace.step(s, "navigate_to_appointments")
        navigateTo(R.id.nav_appointments)

        pace.step(s, "trigger_anr_block")
        clickDebugButton(R.id.btnTriggerAnr)
        // Main thread blocks for 6s inside SchedulingActivity.onTriggerAnr()
        // SDK's ANR monitor detects the freeze and emits anr.risk event
        Thread.sleep(7000)

        pace.step(s, "anr_detected_recovered")

        pace.step(s, "scenario_complete")
    }

    /**
     * Scenario 7: Crash and recovery (simulated)
     * Signals in Dash0: app.crash event, app.crash_recovery event,
     *   breadcrumb trail from pre-crash session, 10-min flush window
     *
     * Note: We simulate the crash/recovery flow without actually killing the process
     * (a real process kill terminates the Espresso instrumentation runner).
     * The test builds a rich breadcrumb trail, emits app.crash + app.crash_recovery
     * events — identical telemetry shape to a real crash scenario.
     *
     * To demo a real process crash: tap "Crash" in the DebugToolbar manually,
     * then relaunch the app to see the recovery flush in Dash0.
     *
     * Flow: launch → build breadcrumbs → emit crash signal → emit recovery signal
     */
    @Test
    fun crashAndRecovery() {
        val s = "crashAndRecovery"

        pace.step(s, "app_launched")

        pace.step(s, "navigate_to_appointments")
        navigateTo(R.id.nav_appointments)

        pace.step(s, "navigate_to_book")
        navigateTo(R.id.nav_book)

        pace.step(s, "navigate_to_directions")
        navigateTo(R.id.nav_directions)

        pace.step(s, "navigate_to_calendar")
        navigateTo(R.id.nav_calendar)

        // Emit simulated crash — same shape as real crash event
        pace.step(s, "crash_simulated")
        MobileOtel.sendEvent(
            "app.crash",
            mapOf(
                "crash.type"    to "RuntimeException",
                "crash.message" to "Simulated booking service crash",
                "crash.thread"  to "main",
                "demo.run_id"   to pace.runId,
                "scenario.name" to s
            ),
            Severity.ERROR
        )
        Thread.sleep(1000)

        // Emit crash recovery — same shape as real app.crash_recovery flush event
        pace.step(s, "crash_recovery_detected")
        MobileOtel.sendEvent(
            "app.crash_recovery",
            mapOf(
                "recovery.type"          to "simulated",
                "recovery.flush_minutes" to 10,
                "demo.run_id"            to pace.runId,
                "scenario.name"          to s
            ),
            Severity.WARN
        )
        Thread.sleep(2000)

        pace.step(s, "crash_recovery_complete")
    }
}
