/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.navigation

import android.app.Activity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import io.opentelemetry.android.mobile.breadcrumb.BreadcrumbConfig
import io.opentelemetry.android.mobile.breadcrumb.BreadcrumbType
import io.opentelemetry.android.mobile.breadcrumb.JourneyBreadcrumbBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [FragmentLifecycleInstrumentation].
 *
 * Two layers of coverage:
 *
 * 1. **Direct callbacks tests** drive the [FragmentLifecycleInstrumentation.buildCallbacks]
 *    seam against synthetic Fragment instances. This exercises every
 *    callback transition without needing a real Activity host — the
 *    fast, deterministic layer.
 *
 * 2. **Integration tests** drive the [FragmentLifecycleInstrumentation.tryAttach]
 *    path against a Robolectric-built FragmentActivity, covering the
 *    "is the activity actually a FragmentActivity" branch and the
 *    "no-op when navigation is disabled" branch.
 *
 * The Robolectric `@Config(sdk = [28])` matches NavigationInstrumentationTest's
 * config so both files run under the same Android API level.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FragmentLifecycleInstrumentationTest {

    private lateinit var buffer: JourneyBreadcrumbBuffer

    @Before
    fun setup() {
        buffer = JourneyBreadcrumbBuffer()
    }

    // ─── tryAttach: gating ────────────────────────────────────────────

    @Test
    fun tryAttach_returnsFalse_whenCaptureNavigationDisabled() {
        val config = BreadcrumbConfig(captureNavigation = false)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java)
            .create().get()
        assertFalse(
            FragmentLifecycleInstrumentation.tryAttach(activity, config, buffer)
        )
    }

    @Test
    fun tryAttach_returnsFalse_whenActivityIsNotFragmentActivity() {
        val config = BreadcrumbConfig()
        val activity = Robolectric.buildActivity(Activity::class.java)
            .create().get()
        assertFalse(
            FragmentLifecycleInstrumentation.tryAttach(activity, config, buffer)
        )
    }

    @Test
    fun tryAttach_returnsTrue_onFragmentActivity() {
        val config = BreadcrumbConfig()
        val activity = Robolectric.buildActivity(FragmentActivity::class.java)
            .create().get()
        assertTrue(
            FragmentLifecycleInstrumentation.tryAttach(activity, config, buffer)
        )
    }

    // ─── attach: registration on real FragmentManager ──────────────────

    @Test
    fun attach_emitsCreated_whenFragmentIsAdded() {
        val config = BreadcrumbConfig()
        val activity = Robolectric.buildActivity(FragmentActivity::class.java)
            .create().get()
        FragmentLifecycleInstrumentation.attach(activity, config, buffer)

        val fragment = TestFragment()
        activity.supportFragmentManager.beginTransaction()
            .add(fragment, "test")
            .commitNow()

        val crumbs = buffer.toList()
            .filter { it.type == BreadcrumbType.NAVIGATION }
            .filter { it.attributes["fragment.class"] == TestFragment::class.java.name }
        assertTrue(
            "expected at least one fragment_created breadcrumb; got: $crumbs",
            crumbs.any { it.action == "fragment_created" }
        )
    }

    @Test
    fun attach_skipsRegistration_whenCaptureNavigationDisabled() {
        val config = BreadcrumbConfig(captureNavigation = false)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java)
            .create().get()
        assertFalse(
            FragmentLifecycleInstrumentation.attach(activity, config, buffer)
        )
        // A fragment add after a no-op attach must NOT produce
        // breadcrumbs — proves the callbacks weren't registered.
        activity.supportFragmentManager.beginTransaction()
            .add(TestFragment(), "test")
            .commitNow()
        val fragmentCrumbs = buffer.toList()
            .filter { it.attributes["fragment.class"] == TestFragment::class.java.name }
        assertTrue(
            "expected zero fragment breadcrumbs when disabled; got: $fragmentCrumbs",
            fragmentCrumbs.isEmpty()
        )
    }

    // ─── buildCallbacks: direct callback exercise ──────────────────────
    //
    // These bypass `attach` so we can drive each callback method
    // synthetically and assert breadcrumb shape per transition without
    // depending on Robolectric's Fragment lifecycle implementation
    // ordering (which has shifted between Robolectric versions in the
    // past).

    @Test
    fun callbacks_onFragmentCreated_emitsCreatedBreadcrumb() {
        val callbacks = buildCallbacks()
        val fm = mockFragmentManager()
        callbacks.onFragmentCreated(fm, TestFragment(), null)
        val crumb = lastFragmentCrumb()
        assertNotNull(crumb)
        assertEquals("fragment_created", crumb!!.action)
        assertEquals(TestFragment::class.java.simpleName, crumb.screen)
        assertEquals(TestFragment::class.java.name, crumb.attributes["fragment.class"])
    }

    @Test
    fun callbacks_onFragmentResumed_emitsEnterBreadcrumb() {
        val callbacks = buildCallbacks()
        val fm = mockFragmentManager()
        callbacks.onFragmentResumed(fm, TestFragment())
        assertEquals("fragment_enter", lastFragmentCrumb()!!.action)
    }

    @Test
    fun callbacks_onFragmentPaused_emitsExitBreadcrumb() {
        val callbacks = buildCallbacks()
        val fm = mockFragmentManager()
        callbacks.onFragmentPaused(fm, TestFragment())
        assertEquals("fragment_exit", lastFragmentCrumb()!!.action)
    }

    @Test
    fun callbacks_onFragmentDestroyed_emitsDestroyedBreadcrumb() {
        val callbacks = buildCallbacks()
        val fm = mockFragmentManager()
        callbacks.onFragmentDestroyed(fm, TestFragment())
        assertEquals("fragment_destroyed", lastFragmentCrumb()!!.action)
    }

    // ─── buildCallbacks: filtering + screen-name ───────────────────────

    @Test
    fun callbacks_skipsFragment_whenAllowedScreensExcludesIt() {
        val config = BreadcrumbConfig(allowedScreens = setOf("OtherFragment"))
        val callbacks = FragmentLifecycleInstrumentation.buildCallbacks(config, buffer)
        callbacks.onFragmentResumed(mockFragmentManager(), TestFragment())
        assertNull(
            "TestFragment is not in allowedScreens; should be filtered",
            lastFragmentCrumb()
        )
    }

    @Test
    fun callbacks_emitsFragment_whenAllowedScreensIncludesIt() {
        val config = BreadcrumbConfig(allowedScreens = setOf("TestFragment"))
        val callbacks = FragmentLifecycleInstrumentation.buildCallbacks(config, buffer)
        callbacks.onFragmentResumed(mockFragmentManager(), TestFragment())
        assertNotNull(lastFragmentCrumb())
    }

    @Test
    fun callbacks_useSimpleNameForScreen_eachFragmentClassDistinct() {
        val callbacks = buildCallbacks()
        val fm = mockFragmentManager()
        callbacks.onFragmentResumed(fm, TestFragment())
        callbacks.onFragmentResumed(fm, AnotherFragment())
        val screens = buffer.toList().map { it.screen }
        assertTrue("TestFragment" in screens)
        assertTrue("AnotherFragment" in screens)
    }

    // ─── NavigationInstrumentation hook integration ────────────────────

    @Test
    fun navigationInstrumentation_hooksFragmentLifecycle_onActivityCreated() {
        // The Activity-level navigation hook should ALSO register
        // fragment callbacks for FragmentActivity hosts. End-to-end
        // proof: initialize NavigationInstrumentation, create a
        // FragmentActivity, add a Fragment, observe a fragment-level
        // breadcrumb in the buffer.
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        // Reset NavigationInstrumentation singleton so each test gets a
        // fresh state (the production class is a process-wide singleton).
        resetNavigationInstrumentationSingleton()

        NavigationInstrumentation.initialize(app, BreadcrumbConfig(), buffer)

        val activity = Robolectric.buildActivity(FragmentActivity::class.java)
            .create().get()
        activity.supportFragmentManager.beginTransaction()
            .add(TestFragment(), "test")
            .commitNow()

        val fragmentCrumbs = buffer.toList()
            .filter { it.attributes["fragment.class"] == TestFragment::class.java.name }
        assertTrue(
            "expected fragment_* breadcrumb after Navigation hook fired; got: ${buffer.toList().map { it.attributes }}",
            fragmentCrumbs.isNotEmpty()
        )
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private fun buildCallbacks(): FragmentManager.FragmentLifecycleCallbacks =
        FragmentLifecycleInstrumentation.buildCallbacks(BreadcrumbConfig(), buffer)

    private fun mockFragmentManager(): FragmentManager {
        // Build a real Activity so we get a real FragmentManager — its
        // identity doesn't matter to the callbacks under test, but
        // FragmentManager has no public constructor and is not easily
        // mocked. The Robolectric builder is the cheap path.
        return Robolectric.buildActivity(FragmentActivity::class.java)
            .create().get().supportFragmentManager
    }

    private fun lastFragmentCrumb() = buffer.toList()
        .lastOrNull { it.attributes["fragment.class"] != null }

    /**
     * NavigationInstrumentation is a singleton with private state.
     * Reset via reflection so the integration test starts clean even
     * if another test in this run already initialized it.
     */
    private fun resetNavigationInstrumentationSingleton() {
        try {
            val instanceField = NavigationInstrumentation::class.java
                .getDeclaredField("instance")
            instanceField.isAccessible = true
            // Companion holds the singleton field via a backing field
            // on the companion object class.
            val companionField = NavigationInstrumentation::class.java
                .getDeclaredField("Companion")
            companionField.isAccessible = true
            val companion = companionField.get(null)
            val companionInstanceField = companion.javaClass
                .getDeclaredField("instance")
            companionInstanceField.isAccessible = true
            companionInstanceField.set(companion, null)
        } catch (_: NoSuchFieldException) {
            // Field name might differ across Kotlin versions; if reset
            // fails, the test still works — it just inherits whatever
            // singleton the previous test left.
        }
    }

    // Synthetic Fragment classes for testing — distinct simpleNames so
    // breadcrumb screen-name attribution can be asserted.
    class TestFragment : Fragment()
    class AnotherFragment : Fragment()
}
