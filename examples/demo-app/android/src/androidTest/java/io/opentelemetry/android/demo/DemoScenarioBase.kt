package io.opentelemetry.android.demo

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
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
        // Pre-grant location permissions to avoid system permission dialog in getDirections
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        uiAutomation.executeShellCommand(
            "pm grant io.opentelemetry.android.demo android.permission.ACCESS_COARSE_LOCATION"
        ).close()
        uiAutomation.executeShellCommand(
            "pm grant io.opentelemetry.android.demo android.permission.ACCESS_FINE_LOCATION"
        ).close()
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
        // Cancel alpha animation and force full visibility for reliable Espresso clicks.
        // toggleExpanded() starts content at alpha=0 then animates to 1 via ViewPropertyAnimator.
        // Even with animator_duration_scale=0, the animation is scheduled on the next Choreographer
        // frame. We bypass this by cancelling and directly setting the final state.
        scenario.onActivity { activity ->
            val content = activity.findViewById<View>(R.id.debugToolbarContent)
            content.animate().cancel()
            content.visibility = View.VISIBLE
            content.alpha = 1f
        }
    }

    /** Click a button inside the DebugToolbar. Expands toolbar first if needed. */
    protected fun clickDebugButton(buttonId: Int) {
        expandDebugToolbar()
        onView(withId(buttonId)).perform(click())
    }
}
