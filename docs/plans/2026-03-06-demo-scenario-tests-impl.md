# Demo Scenario Tests Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 8 Espresso-driven instrumented tests that generate rich OTel telemetry visible in Dash0, covering both user journeys and fault scenarios.

**Architecture:** Espresso tests in `androidTest/` drive the real `SchedulingActivity` UI. A shared `DemoScenarioPace` utility emits `demo.step` OTel logs and sleeps between steps. A `DemoScenarioBase` class provides nav/toolbar helpers. Scenarios send live telemetry to `https://ingress.us-west-2.aws.dash0.com:4317`.

**Tech Stack:** Kotlin, Espresso (`androidx.test.espresso:espresso-core:3.7.0`), `androidx.test.ext:junit:1.3.0`, OTelMobile SDK, `InstrumentationRegistry` for argument passing.

---

## Task 1: Create `DemoScenarioPace`

**Files:**
- Create: `examples/demo-app/android/src/androidTest/java/io/opentelemetry/android/demo/DemoScenarioPace.kt`

**Step 1: Create the file**

```kotlin
package io.opentelemetry.android.demo

import androidx.test.platform.app.InstrumentationRegistry
import io.opentelemetry.android.mobile.OTelMobile
import io.opentelemetry.api.logs.Severity
import java.util.UUID

/**
 * Controls pacing between demo scenario steps.
 *
 * Pass --paceMs=0 to disable pauses (CI/fast runs):
 *   ./gradlew :android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.paceMs=0
 *
 * Default is 2000ms — gives traces time to appear as separate entries in Dash0.
 */
class DemoScenarioPace {

    val runId: String = UUID.randomUUID().toString()
    private val paceMs: Long
    private var stepIndex = 0

    init {
        val args = InstrumentationRegistry.getArguments()
        paceMs = args.getString("paceMs")?.toLongOrNull() ?: 2000L
    }

    fun step(scenarioName: String, stepName: String) {
        stepIndex++
        OTelMobile.sendEvent(
            "demo.step",
            mapOf(
                "scenario.name"       to scenarioName,
                "scenario.step"       to stepName,
                "scenario.step_index" to stepIndex,
                "demo.run_id"         to runId
            ),
            Severity.INFO
        )
        if (paceMs > 0) Thread.sleep(paceMs)
    }

    fun reset() {
        stepIndex = 0
    }
}
```

**Step 2: Verify it compiles**

```bash
cd examples/demo-app
./gradlew :android:compileDebugAndroidTestKotlin
```
Expected: `BUILD SUCCESSFUL`

**Step 3: Commit**

```bash
git add examples/demo-app/android/src/androidTest/java/io/opentelemetry/android/demo/DemoScenarioPace.kt
git commit -m "test: add DemoScenarioPace for paced demo telemetry emission"
```

---

## Task 2: Create `DemoScenarioBase`

**Files:**
- Create: `examples/demo-app/android/src/androidTest/java/io/opentelemetry/android/demo/DemoScenarioBase.kt`

**Step 1: Create the file**

```kotlin
package io.opentelemetry.android.demo

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import org.junit.After
import org.junit.Before

/**
 * Base class for all demo scenarios.
 *
 * Launches SchedulingActivity before each test and closes it after.
 * Provides helpers for navigation and DebugToolbar interaction.
 *
 * DebugToolbar is visible in debug builds (TelemetryFlags.showDebugToolbar = true).
 */
abstract class DemoScenarioBase {

    protected lateinit var pace: DemoScenarioPace
    private lateinit var scenario: ActivityScenario<SchedulingActivity>

    @Before
    fun setUp() {
        pace = DemoScenarioPace()
        scenario = ActivityScenario.launch(SchedulingActivity::class.java)
        // Allow activity to fully render before tests begin
        Thread.sleep(1000)
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
    }

    /** Tap a bottom nav item. navId is e.g. R.id.nav_appointments */
    protected fun navigateTo(navId: Int) {
        onView(withId(navId)).perform(click())
        Thread.sleep(500) // allow fragment to load
    }

    /** Expand the DebugToolbar so its buttons become visible */
    protected fun expandDebugToolbar() {
        onView(withId(R.id.debugToolbarHeader)).perform(click())
        Thread.sleep(300) // allow expand animation
    }

    /** Click a button inside the DebugToolbar. Expands toolbar first if needed. */
    protected fun clickDebugButton(buttonId: Int) {
        expandDebugToolbar()
        onView(withId(buttonId)).perform(click())
    }
}
```

**Step 2: Verify it compiles**

```bash
cd examples/demo-app
./gradlew :android:compileDebugAndroidTestKotlin
```
Expected: `BUILD SUCCESSFUL`

**Step 3: Commit**

```bash
git add examples/demo-app/android/src/androidTest/java/io/opentelemetry/android/demo/DemoScenarioBase.kt
git commit -m "test: add DemoScenarioBase with nav and toolbar helpers"
```

---

## Task 3: Create `UserJourneyScenarios`

**Files:**
- Create: `examples/demo-app/android/src/androidTest/java/io/opentelemetry/android/demo/scenarios/UserJourneyScenarios.kt`

**Step 1: Create the scenarios directory and file**

```bash
mkdir -p examples/demo-app/android/src/androidTest/java/io/opentelemetry/android/demo/scenarios
```

```kotlin
package io.opentelemetry.android.demo.scenarios

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeDown
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.opentelemetry.android.demo.DemoScenarioBase
import io.opentelemetry.android.demo.R
import org.junit.Test
import org.junit.runner.RunWith

/**
 * User journey scenarios — multi-step flows that generate breadcrumb trails,
 * spans, and HTTP telemetry visible in Dash0.
 *
 * Run all:
 *   ./gradlew :android:connectedDebugAndroidTest
 *
 * Run one:
 *   ./gradlew :android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=\
 *     io.opentelemetry.android.demo.scenarios.UserJourneyScenarios#happyPathBooking
 *
 * Run fast (no pauses):
 *   ./gradlew :android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.paceMs=0
 */
@RunWith(AndroidJUnit4::class)
class UserJourneyScenarios : DemoScenarioBase() {

    /**
     * Scenario 1: Happy path booking
     * Signals in Dash0: breadcrumb trail, navigation spans, session trace
     *
     * Flow: launch → Calendar → Book → Appointments
     */
    @Test
    fun happyPathBooking() {
        val s = "happyPathBooking"

        pace.step(s, "app_launched")

        pace.step(s, "navigate_to_calendar")
        navigateTo(R.id.nav_calendar)

        pace.step(s, "navigate_to_book")
        navigateTo(R.id.nav_book)

        pace.step(s, "navigate_to_appointments")
        navigateTo(R.id.nav_appointments)

        pace.step(s, "navigate_to_profile")
        navigateTo(R.id.nav_profile)

        pace.step(s, "journey_complete")
    }

    /**
     * Scenario 2: Browse and refresh
     * Signals in Dash0: HTTP timing histogram, network spans, swipe interaction events
     *
     * Flow: launch → Appointments → swipe-to-refresh × 2
     */
    @Test
    fun browseAndRefresh() {
        val s = "browseAndRefresh"

        pace.step(s, "app_launched")

        pace.step(s, "navigate_to_appointments")
        navigateTo(R.id.nav_appointments)

        pace.step(s, "swipe_refresh_1")
        onView(withId(R.id.swipeRefresh)).perform(swipeDown())
        Thread.sleep(1500) // wait for network call to complete

        pace.step(s, "swipe_refresh_2")
        onView(withId(R.id.swipeRefresh)).perform(swipeDown())
        Thread.sleep(1500)

        pace.step(s, "browse_complete")
    }

    /**
     * Scenario 3: Network error recovery
     * Signals in Dash0: HTTP 500 error log, policy flush trigger (5-min window export)
     *
     * Flow: launch → trigger HTTP 500 via DebugToolbar → navigate to Appointments
     */
    @Test
    fun networkErrorRecovery() {
        val s = "networkErrorRecovery"

        pace.step(s, "app_launched")

        pace.step(s, "navigate_to_appointments_baseline")
        navigateTo(R.id.nav_appointments)
        Thread.sleep(1000) // let baseline request complete

        pace.step(s, "trigger_http_500")
        clickDebugButton(R.id.btnTriggerHttp500)

        pace.step(s, "refresh_to_trigger_error")
        onView(withId(R.id.swipeRefresh)).perform(swipeDown())
        Thread.sleep(2000) // wait for error + policy flush

        pace.step(s, "navigate_to_calendar_recovery")
        navigateTo(R.id.nav_calendar)

        pace.step(s, "error_recovery_complete")
    }

    /**
     * Scenario 8: Get directions
     * Signals in Dash0: navigation span with location attrs, 2 child HTTP spans
     *   (Nominatim + OSRM routing), directions.fetched event
     *
     * Flow: launch → Directions tab → tap Get Directions
     */
    @Test
    fun getDirections() {
        val s = "getDirections"

        pace.step(s, "app_launched")

        pace.step(s, "navigate_to_directions")
        navigateTo(R.id.nav_directions)

        pace.step(s, "tap_get_directions")
        onView(withId(R.id.btnGetDirections)).perform(click())
        Thread.sleep(4000) // network calls take 1-3s each

        pace.step(s, "directions_fetched")

        pace.step(s, "navigate_back_to_calendar")
        navigateTo(R.id.nav_calendar)

        pace.step(s, "journey_complete")
    }
}
```

**Step 2: Verify compilation**

```bash
cd examples/demo-app
./gradlew :android:compileDebugAndroidTestKotlin
```
Expected: `BUILD SUCCESSFUL`

**Step 3: Commit**

```bash
git add examples/demo-app/android/src/androidTest/java/io/opentelemetry/android/demo/scenarios/UserJourneyScenarios.kt
git commit -m "test: add user journey demo scenarios for Dash0 telemetry showcase"
```

---

## Task 4: Create `FaultScenarios`

**Files:**
- Create: `examples/demo-app/android/src/androidTest/java/io/opentelemetry/android/demo/scenarios/FaultScenarios.kt`

**Step 1: Create the file**

```kotlin
package io.opentelemetry.android.demo.scenarios

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.opentelemetry.android.demo.DemoScenarioBase
import io.opentelemetry.android.demo.R
import io.opentelemetry.android.demo.SchedulingActivity
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
        Thread.sleep(500) // wait for jank to be detected

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
        Thread.sleep(2000) // allow health monitor to detect and emit

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
     * Flow: launch → build context → trigger ANR block → recover
     */
    @Test
    fun anrDetection() {
        val s = "anrDetection"

        pace.step(s, "app_launched")

        pace.step(s, "navigate_to_appointments")
        navigateTo(R.id.nav_appointments)

        // Use fast pace for the ANR test — the thread block IS the pause
        pace.step(s, "trigger_anr_block")
        clickDebugButton(R.id.btnTriggerAnr)
        // Main thread blocks for 6s inside SchedulingActivity.onTriggerAnr()
        // SDK's ANR monitor detects the freeze and emits anr.risk event
        Thread.sleep(7000) // wait for block to finish + detection to emit

        pace.step(s, "anr_detected_recovered")

        pace.step(s, "scenario_complete")
    }

    /**
     * Scenario 7: Crash and recovery
     * Signals in Dash0: app.crash_recovery event on relaunch, 10-min pre-crash
     *   flush with buffered telemetry, breadcrumb trail from pre-crash session
     *
     * Note: Espresso test process survives the app crash because we use
     * ActivityScenario which catches the RemoteException. We then re-launch
     * the activity to capture the recovery telemetry.
     *
     * Flow: launch → build breadcrumbs → trigger crash → re-launch → recovery telemetry
     */
    @Test
    fun crashAndRecovery() {
        val s = "crashAndRecovery"

        pace.step(s, "app_launched")

        // Build up a meaningful breadcrumb trail before the crash
        pace.step(s, "navigate_to_appointments")
        navigateTo(R.id.nav_appointments)

        pace.step(s, "navigate_to_book")
        navigateTo(R.id.nav_book)

        pace.step(s, "navigate_to_directions")
        navigateTo(R.id.nav_directions)

        pace.step(s, "navigate_to_calendar")
        navigateTo(R.id.nav_calendar)

        // Trigger crash — app process will die
        pace.step(s, "trigger_crash")
        try {
            clickDebugButton(R.id.btnTriggerCrash)
            Thread.sleep(1000) // wait for crash to propagate
        } catch (_: Exception) {
            // Expected — Espresso may throw when the activity is destroyed
        }

        // Give the OS time to process the crash
        Thread.sleep(3000)

        // Re-launch to capture crash recovery telemetry
        // On restart, DemoApp detects the crash marker and fires app.crash_recovery flush
        pace.step(s, "relaunching_for_recovery")
        val recoveryScenario = ActivityScenario.launch(SchedulingActivity::class.java)
        Thread.sleep(3000) // allow crash recovery detection + 10-min buffer flush

        pace.step(s, "crash_recovery_complete")
        recoveryScenario.close()
    }
}
```

**Step 2: Verify compilation**

```bash
cd examples/demo-app
./gradlew :android:compileDebugAndroidTestKotlin
```
Expected: `BUILD SUCCESSFUL`

**Step 3: Commit**

```bash
git add examples/demo-app/android/src/androidTest/java/io/opentelemetry/android/demo/scenarios/FaultScenarios.kt
git commit -m "test: add fault injection demo scenarios for Dash0 telemetry showcase"
```

---

## Task 5: Run and verify all scenarios on emulators

**Step 1: Confirm emulators are running**

```bash
adb devices
```
Expected: 2 emulators showing `device` status (emulator-5554, emulator-5556)

**Step 2: Run all demo scenarios with default pacing**

```bash
cd examples/demo-app
./gradlew :android:connectedDebugAndroidTest
```
Expected: `BUILD SUCCESSFUL`, all 8 tests pass

**Step 3: Verify telemetry in Dash0**

Open Dash0 and filter by:
- `service.name = "otel-mobile-demo"`
- `demo.run_id = <the UUID from test output logs>`

You should see `demo.step` log events grouped by `scenario.name`, breadcrumb trails, spans for navigation and HTTP calls, and fault events.

**Step 4: Verify fast-mode works**

```bash
./gradlew :android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.paceMs=0
```
Expected: `BUILD SUCCESSFUL`, tests run significantly faster

**Step 5: Run a single scenario**

```bash
./gradlew :android:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.opentelemetry.android.demo.scenarios.FaultScenarios#jankDetection
```
Expected: `BUILD SUCCESSFUL`, single test runs

**Step 6: Final commit with run instructions update**

```bash
git add -A
git commit -m "test: demo scenario suite complete — 8 Espresso scenarios sending telemetry to Dash0"
```

---

## Quick Reference

| Scenario | Class | Key Dash0 signal |
|---|---|---|
| `happyPathBooking` | UserJourneyScenarios | breadcrumbs, nav spans |
| `browseAndRefresh` | UserJourneyScenarios | HTTP histogram |
| `networkErrorRecovery` | UserJourneyScenarios | HTTP 500 + policy flush |
| `getDirections` | UserJourneyScenarios | location spans + 2 HTTP children |
| `jankDetection` | FaultScenarios | `ui.jank` > 16ms |
| `memoryPressure` | FaultScenarios | `device.memory.low` |
| `anrDetection` | FaultScenarios | ANR risk event |
| `crashAndRecovery` | FaultScenarios | `app.crash_recovery` + 10min flush |

Filter all runs in Dash0: `scenario.name EXISTS AND service.name = "otel-mobile-demo"`
Correlate one run: `demo.run_id = "<uuid>"`
