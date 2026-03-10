// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.platform.app.InstrumentationRegistry
import io.opentelemetry.android.mobile.MobileOtel
import io.opentelemetry.api.logs.Severity
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
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // Dismiss any stray system dialogs (ANR, crash, permission) left by previous tests
        uiAutomation.executeShellCommand("am force-stop io.opentelemetry.android.demo").close()
        Thread.sleep(500)
        // Pre-grant location permissions to avoid system permission dialog in getDirections
        uiAutomation.executeShellCommand(
            "pm grant io.opentelemetry.android.demo android.permission.ACCESS_COARSE_LOCATION"
        ).close()
        uiAutomation.executeShellCommand(
            "pm grant io.opentelemetry.android.demo android.permission.ACCESS_FINE_LOCATION"
        ).close()
        scenario = ActivityScenario.launch(SchedulingActivity::class.java)
        // Allow activity to fully render before tests begin
        Thread.sleep(1500)
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
    }

    /** Tap a bottom nav item. navId is e.g. R.id.nav_appointments */
    protected fun navigateTo(navId: Int) {
        try {
            onView(withId(navId)).perform(click())
        } catch (e: RuntimeException) {
            if ("RootViewWithoutFocusException" in (e.javaClass.name + e.message.orEmpty())) {
                // A system overlay (ANR, notification, permission dialog) stole focus.
                // Dismiss it with Back and retry once.
                val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
                uiAutomation.executeShellCommand("input keyevent 4").close() // KEYCODE_BACK
                Thread.sleep(500)
                onView(withId(navId)).perform(click())
            } else {
                throw e
            }
        }
        Thread.sleep(500) // allow fragment to load
    }

    /** Expand the DebugToolbar so its buttons become visible */
    protected fun expandDebugToolbar() {
        // Directly call setExpanded(true) on the DebugToolbar to bypass animations
        // and avoid timing issues with GestureDetector + Choreographer in tests.
        scenario.onActivity { activity ->
            val toolbar = activity.findViewById<io.opentelemetry.android.demo.ui.debug.DebugToolbar>(R.id.debugToolbar)
            toolbar?.setExpanded(true)
            val content = activity.findViewById<View>(R.id.debugToolbarContent)
            content?.visibility = View.VISIBLE
            content?.alpha = 1f
        }
        Thread.sleep(100)
    }

    /** Click a button inside the DebugToolbar. Expands toolbar first if needed. */
    protected fun clickDebugButton(buttonId: Int) {
        expandDebugToolbar()
        onView(withId(buttonId)).perform(click())
    }

    /**
     * Emits a buffer.snapshot event showing current RAM/disk ring buffer occupancy.
     * Visible in Dash0 as a log event with buffer stats as attributes.
     *
     * @param label Descriptive label for this snapshot (e.g. "pre_flush", "post_flush")
     */
    protected fun emitBufferStats(label: String) {
        val stats = MobileOtel.getBufferStats()
        MobileOtel.sendEvent(
            "buffer.snapshot",
            mapOf(
                "buffer.label"         to label,
                "buffer.ram.events"    to (stats?.ramBufferSize ?: -1),
                "buffer.ram.capacity"  to (stats?.ramBufferCapacity ?: -1),
                "buffer.disk.events"   to (stats?.diskBufferSize ?: -1),
                "demo.run_id"          to pace.runId
            ),
            Severity.INFO
        )
    }
}
