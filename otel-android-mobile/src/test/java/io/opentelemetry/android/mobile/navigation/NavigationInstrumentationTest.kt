/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.navigation

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.android.mobile.breadcrumb.BreadcrumbConfig
import io.opentelemetry.android.mobile.breadcrumb.BreadcrumbType
import io.opentelemetry.android.mobile.breadcrumb.JourneyBreadcrumbBuffer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [NavigationInstrumentation] — activity lifecycle breadcrumbs, manual navigation,
 * deep link capture, and enable/disable control.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NavigationInstrumentationTest {

    private lateinit var app: Application
    private lateinit var buffer: JourneyBreadcrumbBuffer

    @Before
    fun setup() {
        resetSingleton()
        app = ApplicationProvider.getApplicationContext()
        buffer = JourneyBreadcrumbBuffer()
    }

    // ─────────────────────────────────────────────────────────────
    // Initialization
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `getInstance throws when not initialized`() {
        assertThrows(IllegalStateException::class.java) {
            NavigationInstrumentation.getInstance()
        }
    }

    @Test
    fun `initialize creates singleton accessible via getInstance`() {
        NavigationInstrumentation.initialize(app, BreadcrumbConfig(), buffer)
        assertNotNull(NavigationInstrumentation.getInstance())
    }

    @Test
    fun `initialize is idempotent - second call is a no-op`() {
        NavigationInstrumentation.initialize(app, BreadcrumbConfig(), buffer)
        val first = NavigationInstrumentation.getInstance()

        val otherBuffer = JourneyBreadcrumbBuffer()
        NavigationInstrumentation.initialize(app, BreadcrumbConfig(), otherBuffer)
        assertSame("Second initialize should not replace singleton", first, NavigationInstrumentation.getInstance())
    }

    // ─────────────────────────────────────────────────────────────
    // getCurrentScreen
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `getCurrentScreen returns null before any navigation`() {
        NavigationInstrumentation.initialize(app, BreadcrumbConfig(), buffer)
        assertNull(NavigationInstrumentation.getCurrentScreen())
    }

    @Test
    fun `getCurrentScreen returns screen after trackNavigation`() {
        NavigationInstrumentation.initialize(app, BreadcrumbConfig(), buffer)
        NavigationInstrumentation.getInstance().trackNavigation("HomeScreen", "navigate")
        assertEquals("HomeScreen", NavigationInstrumentation.getCurrentScreen())
    }

    // ─────────────────────────────────────────────────────────────
    // Manual navigation tracking
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `trackNavigation adds navigation breadcrumb to buffer`() {
        NavigationInstrumentation.initialize(app, BreadcrumbConfig(), buffer)
        NavigationInstrumentation.getInstance().trackNavigation("BookScreen", "navigate")

        assertEquals(1, buffer.size())
        val crumb = buffer.last()!!
        assertEquals(BreadcrumbType.NAVIGATION, crumb.type)
        assertEquals("BookScreen", crumb.screen)
        assertEquals("navigate", crumb.action)
    }

    @Test
    fun `trackNavigation includes route in breadcrumb attributes`() {
        NavigationInstrumentation.initialize(app, BreadcrumbConfig(), buffer)
        NavigationInstrumentation.getInstance()
            .trackNavigation("DetailScreen", "navigate", route = "/detail/42")

        val crumb = buffer.last()!!
        assertEquals("/detail/42", crumb.attributes["route"])
    }

    @Test
    fun `trackNavigation with captureNavigation=false does nothing`() {
        val config = BreadcrumbConfig(captureNavigation = false)
        NavigationInstrumentation.initialize(app, config, buffer)
        NavigationInstrumentation.getInstance().trackNavigation("Screen", "navigate")

        assertEquals(0, buffer.size())
    }

    @Test
    fun `trackNavigation respects allowedScreens filter`() {
        val config = BreadcrumbConfig(allowedScreens = setOf("AllowedScreen"))
        NavigationInstrumentation.initialize(app, config, buffer)
        val nav = NavigationInstrumentation.getInstance()

        nav.trackNavigation("AllowedScreen", "navigate")
        nav.trackNavigation("BlockedScreen", "navigate")

        assertEquals(1, buffer.size())
        assertEquals("AllowedScreen", buffer.last()!!.screen)
    }

    @Test
    fun `trackNavigation updates currentScreen`() {
        NavigationInstrumentation.initialize(app, BreadcrumbConfig(), buffer)
        val nav = NavigationInstrumentation.getInstance()

        nav.trackNavigation("ScreenA", "navigate")
        assertEquals("ScreenA", NavigationInstrumentation.getCurrentScreen())

        nav.trackNavigation("ScreenB", "navigate")
        assertEquals("ScreenB", NavigationInstrumentation.getCurrentScreen())
    }

    // ─────────────────────────────────────────────────────────────
    // Back navigation
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `trackBackNavigation adds back_pressed breadcrumb`() {
        NavigationInstrumentation.initialize(app, BreadcrumbConfig(), buffer)
        NavigationInstrumentation.getInstance().trackBackNavigation("DetailScreen")

        assertEquals(1, buffer.size())
        val crumb = buffer.last()!!
        assertEquals(BreadcrumbType.NAVIGATION, crumb.type)
        assertEquals("back_pressed", crumb.action)
        assertEquals("DetailScreen", crumb.screen)
    }

    @Test
    fun `trackBackNavigation includes destination in attributes`() {
        NavigationInstrumentation.initialize(app, BreadcrumbConfig(), buffer)
        NavigationInstrumentation.getInstance().trackBackNavigation("DetailScreen", toScreen = "HomeScreen")

        val crumb = buffer.last()!!
        assertEquals("HomeScreen", crumb.attributes["to_screen"])
    }

    @Test
    fun `trackBackNavigation updates currentScreen to destination`() {
        NavigationInstrumentation.initialize(app, BreadcrumbConfig(), buffer)
        NavigationInstrumentation.getInstance()
            .trackBackNavigation("DetailScreen", toScreen = "HomeScreen")

        assertEquals("HomeScreen", NavigationInstrumentation.getCurrentScreen())
    }

    @Test
    fun `trackBackNavigation with null destination sets currentScreen to null`() {
        NavigationInstrumentation.initialize(app, BreadcrumbConfig(), buffer)
        val nav = NavigationInstrumentation.getInstance()

        nav.trackNavigation("SomeScreen", "navigate")
        assertNotNull(NavigationInstrumentation.getCurrentScreen())

        nav.trackBackNavigation("SomeScreen") // no toScreen
        assertNull(NavigationInstrumentation.getCurrentScreen())
    }

    // ─────────────────────────────────────────────────────────────
    // Enable / disable
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `isEnabled returns true after initialization with enabled config`() {
        NavigationInstrumentation.initialize(app, BreadcrumbConfig(enabled = true), buffer)
        assertTrue(NavigationInstrumentation.getInstance().isEnabled())
    }

    @Test
    fun `setEnabled false stops breadcrumb collection`() {
        NavigationInstrumentation.initialize(app, BreadcrumbConfig(), buffer)
        val nav = NavigationInstrumentation.getInstance()

        nav.setEnabled(false)
        nav.trackNavigation("Screen", "navigate")

        assertEquals(0, buffer.size())
    }

    @Test
    fun `setEnabled true re-enables breadcrumb collection`() {
        NavigationInstrumentation.initialize(app, BreadcrumbConfig(), buffer)
        val nav = NavigationInstrumentation.getInstance()

        nav.setEnabled(false)
        nav.trackNavigation("ScreenA", "navigate")
        assertEquals(0, buffer.size())

        nav.setEnabled(true)
        nav.trackNavigation("ScreenB", "navigate")
        assertEquals(1, buffer.size())
        assertEquals("ScreenB", buffer.last()!!.screen)
    }

    // ─────────────────────────────────────────────────────────────
    // Activity lifecycle via Robolectric
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `activity resume adds screen_enter navigation breadcrumb`() {
        NavigationInstrumentation.initialize(app, BreadcrumbConfig(), buffer)

        val controller = Robolectric.buildActivity(TestActivity::class.java).create().start()
        controller.resume()

        val navCrumbs = buffer.filterByType(BreadcrumbType.NAVIGATION)
        assertTrue("Should have at least a screen_enter breadcrumb",
            navCrumbs.any { it.action == "screen_enter" })
    }

    @Test
    fun `activity pause adds screen_exit navigation breadcrumb`() {
        NavigationInstrumentation.initialize(app, BreadcrumbConfig(), buffer)

        val controller = Robolectric.buildActivity(TestActivity::class.java).create().start().resume()
        controller.pause()

        val navCrumbs = buffer.filterByType(BreadcrumbType.NAVIGATION)
        assertTrue("Should have a screen_exit breadcrumb",
            navCrumbs.any { it.action == "screen_exit" })
    }

    @Test
    fun `activity onCreate adds screen_created breadcrumb`() {
        NavigationInstrumentation.initialize(app, BreadcrumbConfig(), buffer)
        Robolectric.buildActivity(TestActivity::class.java).create()

        val navCrumbs = buffer.filterByType(BreadcrumbType.NAVIGATION)
        assertTrue("Should have a screen_created breadcrumb",
            navCrumbs.any { it.action == "screen_created" })
    }

    @Test
    fun `activity onDestroy adds screen_destroyed breadcrumb`() {
        NavigationInstrumentation.initialize(app, BreadcrumbConfig(), buffer)
        val controller = Robolectric.buildActivity(TestActivity::class.java)
            .create().start().resume().pause().stop()
        controller.destroy()

        val navCrumbs = buffer.filterByType(BreadcrumbType.NAVIGATION)
        assertTrue("Should have a screen_destroyed breadcrumb",
            navCrumbs.any { it.action == "screen_destroyed" })
    }

    @Test
    fun `deep link activity adds deep_link navigation breadcrumb`() {
        NavigationInstrumentation.initialize(app, BreadcrumbConfig(scrubNetworkUrls = false), buffer)

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("schedulr://appointments/42"))
        Robolectric.buildActivity(TestActivity::class.java, intent).create()

        val deepLinkCrumbs = buffer.filterByType(BreadcrumbType.NAVIGATION)
            .filter { it.action == "deep_link" }
        assertEquals("Should have exactly one deep_link breadcrumb", 1, deepLinkCrumbs.size)
        assertTrue(deepLinkCrumbs[0].attributes["deep_link.scheme"] == "schedulr")
    }

    @Test
    fun `allowedScreens filter blocks unlisted activity screens`() {
        val config = BreadcrumbConfig(allowedScreens = setOf("OtherScreen"))
        NavigationInstrumentation.initialize(app, config, buffer)

        Robolectric.buildActivity(TestActivity::class.java).create().start().resume()

        // TestActivity is not in allowedScreens — buffer should stay empty
        assertEquals(0, buffer.size())
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Reset the NavigationInstrumentation singleton so each test starts fresh.
     */
    private fun resetSingleton() {
        try {
            // Kotlin companion object fields are instance fields on the Companion class
            val companionField = NavigationInstrumentation::class.java
                .getDeclaredField("Companion")
                .also { it.isAccessible = true }
            val companion = companionField.get(null)
            val instanceField = companion.javaClass
                .getDeclaredField("instance")
                .also { it.isAccessible = true }
            instanceField.set(companion, null)
        } catch (e: NoSuchFieldException) {
            // Fallback: field might be a static on the outer class (depends on Kotlin version)
            try {
                NavigationInstrumentation::class.java
                    .getDeclaredField("instance")
                    .also { it.isAccessible = true }
                    .set(null, null)
            } catch (_: Exception) { }
        }
    }
}

/** Minimal concrete Activity for lifecycle tests. */
class TestActivity : Activity()
