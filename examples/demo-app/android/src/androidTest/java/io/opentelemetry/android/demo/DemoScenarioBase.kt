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
