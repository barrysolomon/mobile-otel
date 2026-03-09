package io.opentelemetry.android.demo.scenarios

import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.swipeDown
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.opentelemetry.android.demo.DemoScenarioBase
import io.opentelemetry.android.demo.R
import io.opentelemetry.android.mobile.OTelMobile
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Scope
import org.hamcrest.Matchers.anything
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
     * Scenario 4: End-to-end booking transaction
     * Signals in Dash0: journey.endToEndBooking root span containing all page spans as children.
     *
     * Trace hierarchy:
     *   journey.endToEndBooking          ← root, wraps entire test
     *   ├── page.BookFragment (21s)
     *   │   ├── booking.submit
     *   │   │   └── POST /posts          ← OkHttp auto-instrumented
     *   │   └── (6 span events: form.submitted, booking.device_context, …)
     *   └── page.AppointmentsFragment
     *
     * The journey span is opened on the app main thread so that ScreenViewInstrumentation's
     * startPageSpan() — which calls spanBuilder("page.X").startSpan() and reads
     * Context.current() from the main thread — automatically picks it up as parent.
     *
     * Flow: launch → Book → select provider → select time slot → enter notes →
     *       tap Book Appointment → wait for confirmation → Appointments
     */
    @Test
    fun endToEndBooking() {
        val s = "endToEndBooking"

        // Open journey span on the main thread so all subsequent page spans inherit it.
        var journeySpan: Span? = null
        var journeyScope: Scope? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            journeySpan = OTelMobile.startJourney(s)
            journeyScope = journeySpan!!.makeCurrent()
        }

        try {
            pace.step(s, "app_launched")

            pace.step(s, "navigate_to_book")
            navigateTo(R.id.nav_book)

            // Select a provider (index 1 = "Dr. Marcus Webb") so spans carry a non-default value.
            pace.step(s, "select_provider")
            onView(withId(R.id.spinnerProvider)).perform(click())
            onData(anything()).atPosition(1).perform(click())

            // Select a time slot (index 2 = "10:00 AM").
            pace.step(s, "select_time_slot")
            onView(withId(R.id.spinnerTimeSlot)).perform(click())
            onData(anything()).atPosition(2).perform(click())

            // Type a note so booking.notes_provided=true appears in the span.
            pace.step(s, "enter_notes")
            onView(withId(R.id.etNotes)).perform(replaceText("Espresso e2e test run"))

            emitBufferStats("pre_booking_submit")

            // Tap Book — triggers booking.submit span + POST /posts HTTP call.
            pace.step(s, "tap_book_appointment")
            onView(withId(R.id.btnBook)).perform(click())

            // Wait for the async HTTP round-trip to complete (jsonplaceholder is fast but give 6s).
            Thread.sleep(6000)

            // Verify the booking flow completed — tvResult is visible for both success and duplicate.
            // AppointmentRepository is an in-process singleton; re-runs on a live app may get
            // DuplicateAppointmentException for the same slot, which still emits full telemetry.
            pace.step(s, "verify_confirmation")
            onView(withId(R.id.tvResult)).check(matches(isDisplayed()))

            emitBufferStats("post_booking_confirm")

            // Navigate to Appointments to see the newly created entry.
            pace.step(s, "navigate_to_appointments")
            navigateTo(R.id.nav_appointments)
            Thread.sleep(1500)

            pace.step(s, "booking_journey_complete")
        } finally {
            // Close scope and end journey span on the main thread (mirrors where it was opened).
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                journeyScope?.close()
                journeySpan?.end()
            }
        }
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
