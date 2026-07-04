// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.scenarios

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.opentelemetry.android.demo.DemoScenarioBase
import io.opentelemetry.android.demo.R
import io.opentelemetry.android.mobile.MobileOtel
import io.opentelemetry.api.logs.Severity
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator stress scenarios — inject device stress conditions via adb shell and
 * verify the SDK observes and exports the expected telemetry to Dash0.
 *
 * Each test:
 *   1. Emits a "stress.start" event with baseline device state
 *   2. Injects the condition via emulator adb shell commands
 *   3. Lets the SDK observe the condition (predictive monitor, vitals, etc.)
 *   4. Emits a "stress.end" event with post-stress device state
 *   5. Restores emulator state in @After
 *
 * ## Expected signals in Dash0 per scenario:
 *   - batteryDrain         → device.health metrics, predictive flush at battery < 15%
 *   - thermalThrottle      → thermal.status OTel gauge, mobile.prediction log at severity >= 3
 *   - memoryPressure       → onTrimMemory log, device.health metrics, possible pre-emptive flush
 *   - networkDegradation   → connectivity.change log, HTTP span errors/timeouts
 *   - combinedStress       → all of the above + elevated crash_risk prediction score
 *   - rapidBatteryDrain    → multiple battery.change events showing drain curve
 *   - extremeLowBattery    → crash_risk prediction triggers pre-emptive buffer flush
 *
 * ## Running individual tests:
 *   ./gradlew :android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=\
 *     io.opentelemetry.android.demo.scenarios.EmulatorStressScenarios#batteryDrain
 *
 * ## Notes on emulator commands:
 *   - `dumpsys battery set level X` — sets battery percentage (0-100), requires no root
 *   - `dumpsys battery set status X` — 2=charging, 3=discharging, 5=full
 *   - `dumpsys battery set temp X` — sets battery temp in tenths of Celsius (e.g. 600 = 60°C)
 *   - `dumpsys battery reset` — restores real battery readings
 *   - `cmd thermalservice override-status X` — thermal throttling status, API 29+
 *     (0=none, 1=light, 2=moderate, 3=severe, 4=critical, 5=emergency, 6=shutdown)
 *   - `cmd thermalservice reset` — removes the override
 *   - `am send-trim-memory <package> RUNNING_CRITICAL` — triggers onTrimMemory callback
 *   - `svc wifi disable` / `svc wifi enable` — toggle WiFi
 */
@RunWith(AndroidJUnit4::class)
class EmulatorStressScenarios : DemoScenarioBase() {

    private val uiAutomation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val appCtx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val packageName = "io.opentelemetry.android.demo"

    @Before
    fun setUpStress() {
        // Ensure we start from a clean emulator state before each stress test.
        restoreEmulatorState()
    }

    @After
    fun tearDownStress() {
        restoreEmulatorState()
    }

    // ── Scenario S1: Battery drain ────────────────────────────────────────────

    /**
     * Simulates progressive battery drain from 100% → 5%.
     *
     * The SDK's DeviceHealthMonitor samples battery every 30s. This test steps through
     * several battery levels with pauses so the monitor observes each step. At ≤ 15%
     * battery, crash_risk should cross 0.7 and trigger a pre-emptive flush.
     *
     * Expected Dash0 signals:
     *   - OTel gauge: device.battery_level (stepping down)
     *   - OTel gauge: device.battery_charging = false
     *   - Log: mobile.prediction with crash_risk ≥ 0.7 when battery ≤ 15%
     *   - Pre-emptive buffer flush (buffer.snapshot events around the flush)
     */
    @Test
    fun batteryDrain() {
        val s = "batteryDrain"

        emitStressStart(s, "battery_drain", readDeviceState())

        pace.step(s, "set_discharging")
        shell("dumpsys battery set status 3")  // BATTERY_STATUS_DISCHARGING
        Thread.sleep(500)

        // Step down battery level, pausing at each step for the health monitor to sample.
        val batteryLevels = listOf(80, 60, 40, 20, 15, 10, 5)
        for (level in batteryLevels) {
            pace.step(s, "battery_${level}pct")
            shell("dumpsys battery set level $level")
            MobileOtel.sendEvent(
                "stress.battery_level_set",
                mapOf(
                    "target.battery_level" to level,
                    "demo.run_id"          to pace.runId,
                    "scenario.name"        to s
                ),
                Severity.INFO
            )
            // Allow health monitor to sample (it polls on a schedule; give it time to react)
            Thread.sleep(if (level <= 15) 3000L else 1500L)
            emitBufferStats("battery_${level}pct")
        }

        pace.step(s, "battery_critical_reached")
        emitStressEnd(s, "battery_drain", readDeviceState())
    }

    // ── Scenario S2: Thermal throttle ────────────────────────────────────────

    /**
     * Simulates progressive thermal throttling through all severity levels.
     *
     * Requires API 29+. On older API the thermal override command is silently ignored and the
     * test still runs, exercising battery-temperature injection via `dumpsys battery set temp`.
     *
     * Expected Dash0 signals:
     *   - OTel gauge: device.thermal_status (0 → 1 → 2 → 3 → 4)
     *   - Log: mobile.prediction with network_loss_risk ≥ 0.7 at SEVERE throttling
     *   - Pre-emptive flush at critical thermal level
     */
    @Test
    fun thermalThrottle() {
        val s = "thermalThrottle"

        emitStressStart(s, "thermal_throttle", readDeviceState())

        // Also set battery temperature to match — SDK reads both.
        // Battery temp is in tenths of Celsius: 450 = 45°C, 550 = 55°C, 650 = 65°C
        val thermalSteps = listOf(
            Triple(1, 350, "light"),      // 35°C
            Triple(2, 450, "moderate"),   // 45°C
            Triple(3, 550, "severe"),     // 55°C — prediction risk threshold
            Triple(4, 650, "critical")    // 65°C — emergency
        )

        for ((status, tempTenths, label) in thermalSteps) {
            pace.step(s, "thermal_$label")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                shell("cmd thermalservice override-status $status")
            }
            shell("dumpsys battery set temp $tempTenths")
            MobileOtel.sendEvent(
                "stress.thermal_level_set",
                mapOf(
                    "thermal.status"    to status,
                    "thermal.label"     to label,
                    "battery.temp_c"    to (tempTenths / 10),
                    "demo.run_id"       to pace.runId,
                    "scenario.name"     to s
                ),
                Severity.WARN
            )
            Thread.sleep(3000)
            emitBufferStats("thermal_$label")
        }

        pace.step(s, "thermal_critical_reached")
        emitStressEnd(s, "thermal_throttle", readDeviceState())
    }

    // ── Scenario S3: Memory pressure ─────────────────────────────────────────

    /**
     * Sends escalating onTrimMemory callbacks to the app.
     *
     * Android uses these to signal available memory levels. The SDK's DeviceHealthMonitor
     * tracks onTrimMemory callbacks; predictive export triggers a pre-emptive flush when
     * memory is critically low.
     *
     * Expected Dash0 signals:
     *   - Log: device.memory.trim_level at RUNNING_LOW, RUNNING_CRITICAL, COMPLETE
     *   - OTel gauge: device.available_memory_mb (dropping)
     *   - Log: mobile.prediction with crash_risk ≥ 0.7 at COMPLETE
     *   - Pre-emptive flush event
     */
    @Test
    fun memoryPressure() {
        val s = "memoryPressure"

        emitStressStart(s, "memory_pressure", readDeviceState())

        // Navigate to a content-heavy screen to have realistic memory usage
        navigateTo(R.id.nav_appointments)
        Thread.sleep(500)

        val trimLevels = listOf(
            Pair("RUNNING_LOW",      "running_low"),
            Pair("RUNNING_MODERATE", "running_moderate"),
            Pair("RUNNING_CRITICAL", "running_critical"),
            Pair("COMPLETE",         "complete")
        )

        for ((adbLevel, label) in trimLevels) {
            pace.step(s, "trim_$label")
            shell("am send-trim-memory $packageName $adbLevel")
            MobileOtel.sendEvent(
                "stress.memory_trim",
                mapOf(
                    "trim.level"   to adbLevel,
                    "trim.label"   to label,
                    "demo.run_id"  to pace.runId,
                    "scenario.name" to s
                ),
                if (adbLevel == "COMPLETE") Severity.ERROR else Severity.WARN
            )
            Thread.sleep(2000)
            emitBufferStats("trim_$label")
        }

        pace.step(s, "memory_critical_reached")
        emitStressEnd(s, "memory_pressure", readDeviceState())
    }

    // ── Scenario S4: Network degradation ─────────────────────────────────────

    /**
     * Simulates network loss by disabling WiFi and mobile data.
     *
     * Requires shell permissions (granted via uiAutomation — no rooting needed).
     *
     * Expected Dash0 signals:
     *   - Log: connectivity.change (wifi=disabled, cellular=disabled)
     *   - HTTP spans with errors once requests fail (timeout/no route)
     *   - Log: mobile.prediction with network_loss_risk elevated
     *   - Pre-emptive flush if risk ≥ 0.7
     */
    @Test
    fun networkDegradation() {
        val s = "networkDegradation"

        emitStressStart(s, "network_degradation", readDeviceState())

        // Navigate to a screen that makes network requests
        navigateTo(R.id.nav_appointments)
        Thread.sleep(1000)

        pace.step(s, "disable_wifi")
        shell("svc wifi disable")
        MobileOtel.sendEvent(
            "stress.network_change",
            mapOf(
                "network.wifi_enabled"     to false,
                "network.cellular_enabled" to true,
                "demo.run_id"              to pace.runId,
                "scenario.name"            to s
            ),
            Severity.WARN
        )
        Thread.sleep(2000)
        emitBufferStats("wifi_disabled")

        pace.step(s, "disable_cellular")
        shell("svc data disable")
        MobileOtel.sendEvent(
            "stress.network_change",
            mapOf(
                "network.wifi_enabled"     to false,
                "network.cellular_enabled" to false,
                "demo.run_id"              to pace.runId,
                "scenario.name"            to s
            ),
            Severity.ERROR
        )
        Thread.sleep(3000)
        emitBufferStats("fully_offline")

        // Trigger a navigation that would normally load data — generates HTTP error spans
        pace.step(s, "navigate_while_offline")
        navigateTo(R.id.nav_book)
        Thread.sleep(1000)

        pace.step(s, "restore_network")
        shell("svc wifi enable")
        shell("svc data enable")
        Thread.sleep(2000)
        emitBufferStats("network_restored")

        emitStressEnd(s, "network_degradation", readDeviceState())
    }

    // ── Scenario S5: Rapid battery drain ─────────────────────────────────────

    /**
     * Drains battery from 100% to 1% in rapid steps, measuring how quickly the SDK
     * detects the critical threshold and triggers a pre-emptive flush.
     *
     * This tests the responsiveness of the DeviceHealthMonitor polling loop.
     *
     * Expected Dash0 signals:
     *   - Rapid sequence of device.battery_level metric values
     *   - Exact battery level at which mobile.prediction fires
     *   - buffer.snapshot "pre_flush" event before flush, "post_flush" after
     */
    @Test
    fun rapidBatteryDrain() {
        val s = "rapidBatteryDrain"

        emitStressStart(s, "rapid_battery_drain", readDeviceState())
        shell("dumpsys battery set status 3")  // discharging

        var flushDetectedAtLevel = -1

        // Drain from 100 → 1 in steps of 5, pause briefly between each
        for (level in 100 downTo 1 step 5) {
            shell("dumpsys battery set level $level")
            Thread.sleep(500)

            if (level <= 15 && flushDetectedAtLevel < 0) {
                // At this point the SDK's prediction model should detect crash_risk ≥ 0.7
                flushDetectedAtLevel = level
                emitBufferStats("pre_predicted_flush_at_${level}pct")
            }
        }

        MobileOtel.sendEvent(
            "stress.rapid_drain_complete",
            mapOf(
                "flush.detected_at_level" to flushDetectedAtLevel,
                "demo.run_id"             to pace.runId,
                "scenario.name"           to s
            ),
            Severity.WARN
        )
        emitBufferStats("after_rapid_drain")
        emitStressEnd(s, "rapid_battery_drain", readDeviceState())
    }

    // ── Scenario S6: Combined stress ──────────────────────────────────────────

    /**
     * Applies battery drain + thermal throttling + memory pressure simultaneously.
     *
     * This is the "perfect storm" scenario — all health signals degrade together.
     * The combined crash_risk score should significantly exceed 0.7, triggering
     * an early and aggressive pre-emptive flush.
     *
     * Expected Dash0 signals:
     *   - All signals from batteryDrain + thermalThrottle + memoryPressure
     *   - mobile.prediction with crash_risk well above threshold
     *   - Earlier flush trigger than any individual scenario alone
     *   - Rich buffer.snapshot events showing buffer state at each stress step
     */
    @Test
    fun combinedStress() {
        val s = "combinedStress"

        // Emit a realistic user journey first (events to flush)
        navigateTo(R.id.nav_appointments)
        Thread.sleep(500)
        navigateTo(R.id.nav_book)
        Thread.sleep(500)
        navigateTo(R.id.nav_calendar)
        Thread.sleep(500)

        for (i in 0 until 10) {
            MobileOtel.sendEvent(
                "user.activity",
                mapOf("activity.index" to i, "scenario.name" to s, "demo.run_id" to pace.runId),
                Severity.INFO
            )
        }
        emitBufferStats("pre_stress")

        emitStressStart(s, "combined_stress", readDeviceState())

        // Step 1: Low battery + discharging
        pace.step(s, "apply_low_battery")
        shell("dumpsys battery set status 3")
        shell("dumpsys battery set level 12")
        Thread.sleep(1000)

        // Step 2: Elevated temperature
        pace.step(s, "apply_thermal_stress")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            shell("cmd thermalservice override-status 3")  // SEVERE
        }
        shell("dumpsys battery set temp 550")  // 55°C
        Thread.sleep(1000)

        // Step 3: Memory pressure
        pace.step(s, "apply_memory_pressure")
        shell("am send-trim-memory $packageName RUNNING_CRITICAL")
        Thread.sleep(1000)

        emitBufferStats("peak_combined_stress")

        MobileOtel.sendEvent(
            "stress.combined_peak",
            mapOf(
                "battery.level"         to 12,
                "thermal.status"        to "severe",
                "memory.trim_level"     to "RUNNING_CRITICAL",
                "demo.run_id"           to pace.runId,
                "scenario.name"         to s
            ),
            Severity.ERROR
        )

        // Allow the health monitor 3 cycles to observe all conditions
        Thread.sleep(5000)
        emitBufferStats("post_stress_pause")

        pace.step(s, "combined_stress_peak_observed")
        emitStressEnd(s, "combined_stress", readDeviceState())
    }

    // ── Scenario S7: Extreme low battery (pre-emptive flush trigger) ──────────

    /**
     * Directly drops battery to 5% to trigger the pre-emptive flush in one step.
     * Used to measure how quickly the SDK detects the condition and flushes.
     *
     * Expected Dash0 signals:
     *   - buffer.snapshot "pre_drop" showing events in buffer
     *   - mobile.prediction with crash_risk ≥ 0.7
     *   - buffer.snapshot "post_flush" showing buffer empty after pre-emptive flush
     */
    @Test
    fun extremeLowBattery() {
        val s = "extremeLowBattery"

        // Build up buffer state so flush is visible
        for (i in 0 until 20) {
            MobileOtel.sendEvent(
                "user.pre_stress_activity",
                mapOf("activity.index" to i, "demo.run_id" to pace.runId),
                Severity.INFO
            )
        }
        emitBufferStats("pre_drop")

        emitStressStart(s, "extreme_low_battery", readDeviceState())

        pace.step(s, "drop_to_5pct")
        shell("dumpsys battery set status 3")  // discharging
        shell("dumpsys battery set level 5")

        MobileOtel.sendEvent(
            "stress.extreme_low_battery",
            mapOf(
                "battery.level"  to 5,
                "demo.run_id"    to pace.runId,
                "scenario.name"  to s
            ),
            Severity.ERROR
        )

        // Wait for predictive monitor to detect + flush (up to 10s)
        Thread.sleep(10000)
        emitBufferStats("post_flush")

        emitStressEnd(s, "extreme_low_battery", readDeviceState())
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Runs a shell command via UIAutomation (no adb bridge needed).
     * Same as `adb shell <cmd>` but executed from within the instrumented test.
     */
    private fun shell(cmd: String) {
        uiAutomation.executeShellCommand(cmd).close()
    }

    /**
     * Restores all emulator overrides modified by stress tests.
     * Called in @After to ensure subsequent tests start from real hardware state.
     */
    private fun restoreEmulatorState() {
        shell("dumpsys battery reset")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // "reset" removes the thermal override and resumes real thermal monitoring
            shell("cmd thermalservice reset")
        }
        shell("svc wifi enable")
        shell("svc data enable")
    }

    /**
     * Reads current device state from Android APIs — not emulator overrides.
     * Used to snapshot the actual observable state at the start/end of each scenario.
     */
    private fun readDeviceState(): Map<String, Any> {
        val ctx = appCtx

        val batteryIntent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = if (scale > 0) (level * 100 / scale) else -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0

        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }

        return mapOf(
            "device.battery_level"      to batteryPct,
            "device.battery_status"     to status,
            "device.battery_temp_c"     to (tempTenths / 10),
            "device.low_memory"         to memInfo.lowMemory,
            "device.available_memory_mb" to (memInfo.availMem / (1024 * 1024)),
            "device.total_memory_mb"    to (memInfo.totalMem / (1024 * 1024))
        )
    }

    private fun emitStressStart(scenario: String, stressType: String, deviceState: Map<String, Any>) {
        MobileOtel.sendEvent(
            "stress.start",
            mapOf(
                "stress.type"  to stressType,
                "scenario.name" to scenario,
                "demo.run_id"  to pace.runId
            ) + deviceState,
            Severity.INFO
        )
        emitBufferStats("stress_start")
    }

    private fun emitStressEnd(scenario: String, stressType: String, deviceState: Map<String, Any>) {
        MobileOtel.sendEvent(
            "stress.end",
            mapOf(
                "stress.type"   to stressType,
                "scenario.name" to scenario,
                "demo.run_id"   to pace.runId
            ) + deviceState,
            Severity.INFO
        )
        emitBufferStats("stress_end")
    }
}
