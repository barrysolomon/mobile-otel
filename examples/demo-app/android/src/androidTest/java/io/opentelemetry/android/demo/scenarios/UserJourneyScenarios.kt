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
     * Flow: launch → Calendar → Book → Appointments → Profile
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
        Thread.sleep(1500)

        pace.step(s, "swipe_refresh_2")
        onView(withId(R.id.swipeRefresh)).perform(swipeDown())
        Thread.sleep(1500)

        pace.step(s, "browse_complete")
    }

    /**
     * Scenario 3: Network error recovery
     * Signals in Dash0: HTTP 500 error log, policy flush trigger (5-min window export)
     *
     * Flow: launch → Appointments (baseline) → trigger HTTP 500 → refresh → Calendar
     */
    @Test
    fun networkErrorRecovery() {
        val s = "networkErrorRecovery"

        pace.step(s, "app_launched")

        pace.step(s, "navigate_to_appointments_baseline")
        navigateTo(R.id.nav_appointments)
        Thread.sleep(1000)

        pace.step(s, "trigger_http_500")
        clickDebugButton(R.id.btnTriggerHttp500)

        pace.step(s, "refresh_to_trigger_error")
        onView(withId(R.id.swipeRefresh)).perform(swipeDown())
        Thread.sleep(2000)

        pace.step(s, "navigate_to_calendar_recovery")
        navigateTo(R.id.nav_calendar)

        pace.step(s, "error_recovery_complete")
    }

    /**
     * Scenario 8: Get directions
     * Signals in Dash0: navigation span with location attrs, 2 child HTTP spans
     *   (Nominatim + OSRM routing), directions.fetched event
     *
     * Flow: launch → Directions tab → tap Get Directions → Calendar
     */
    @Test
    fun getDirections() {
        val s = "getDirections"

        pace.step(s, "app_launched")

        pace.step(s, "navigate_to_directions")
        navigateTo(R.id.nav_directions)

        pace.step(s, "tap_get_directions")
        onView(withId(R.id.btnGetDirections)).perform(click())
        Thread.sleep(4000)

        pace.step(s, "directions_fetched")

        pace.step(s, "navigate_back_to_calendar")
        navigateTo(R.id.nav_calendar)

        pace.step(s, "journey_complete")
    }
}
