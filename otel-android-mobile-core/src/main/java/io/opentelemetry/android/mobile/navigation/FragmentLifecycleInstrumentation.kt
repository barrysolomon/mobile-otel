/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.navigation

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import io.opentelemetry.android.mobile.breadcrumb.BreadcrumbConfig
import io.opentelemetry.android.mobile.breadcrumb.JourneyBreadcrumb
import io.opentelemetry.android.mobile.breadcrumb.JourneyBreadcrumbBuffer
import io.opentelemetry.android.mobile.instrumentation.Incubating

/**
 * Instrumentation for capturing Fragment lifecycle events as breadcrumbs.
 *
 * Mirrors [NavigationInstrumentation] but operates at the Fragment level:
 * registers `FragmentManager.FragmentLifecycleCallbacks` on every
 * `FragmentActivity` and emits a navigation breadcrumb on each fragment
 * `onFragmentCreated` / `onFragmentResumed` / `onFragmentPaused` /
 * `onFragmentDestroyed` transition.
 *
 * ## Why this exists separately
 *
 * Fragments are a sub-Activity navigation surface — a FragmentActivity
 * may show several fragments during its lifecycle, and breadcrumb
 * timelines are richer when each fragment shows up as its own screen.
 * Activity-only breadcrumbs miss the actual user-perceived screen
 * transitions in apps that use a single Activity + many Fragments
 * (the standard Jetpack Navigation Component pattern).
 *
 * ## Wiring
 *
 * [NavigationInstrumentation] hooks this in its `onActivityCreated`
 * callback when navigation is enabled and the activity is a
 * [FragmentActivity]. Hosting code that uses
 * [NavigationInstrumentation.initialize] gets fragment coverage for
 * free. Direct callers can also invoke [tryAttach] manually.
 *
 * ## `androidx.fragment` is `compileOnly`
 *
 * SDK consumers that ship Compose-only or pure-View-system apps may
 * not carry `androidx.fragment` on their classpath. The runtime path
 * gates with a `Class.forName` check (see [tryAttach]) so missing
 * classes are a clean no-op rather than a `NoClassDefFoundError`.
 * Direct references inside this file are fine — the class is only
 * loaded if the gate succeeds, and the JVM is lazy about class
 * resolution.
 */
@Incubating
object FragmentLifecycleInstrumentation {

    private const val FRAGMENT_ACTIVITY_FQN =
        "androidx.fragment.app.FragmentActivity"

    /**
     * Attempt to attach fragment lifecycle callbacks to `activity`.
     *
     * No-ops cleanly when:
     * - `androidx.fragment.app.FragmentActivity` is not on the host
     *   classpath (compileOnly dep, missing at runtime).
     * - `activity` is not a `FragmentActivity` (e.g. plain `Activity`,
     *   `ComponentActivity` without fragment support).
     * - `config.captureNavigation` is false.
     *
     * Idempotent per-Activity is the caller's responsibility — the
     * production caller is [NavigationInstrumentation.onActivityCreated],
     * which fires once per Activity instance via the platform lifecycle
     * callback contract.
     */
    fun tryAttach(
        activity: Activity,
        config: BreadcrumbConfig,
        breadcrumbBuffer: JourneyBreadcrumbBuffer
    ): Boolean {
        if (!config.captureNavigation) return false
        if (!isFragmentActivityAvailable()) return false
        if (activity !is FragmentActivity) return false
        return attach(activity, config, breadcrumbBuffer)
    }

    /**
     * Register callbacks on the given `FragmentActivity`'s
     * supportFragmentManager. Caller has already established that
     * `context` is a `FragmentActivity` and that the fragment classes
     * are loadable; prefer [tryAttach] from any code path that doesn't
     * already have those guarantees.
     */
    fun attach(
        context: Context,
        config: BreadcrumbConfig,
        breadcrumbBuffer: JourneyBreadcrumbBuffer
    ): Boolean {
        if (!config.captureNavigation) return false
        val fragmentActivity = context as? FragmentActivity ?: return false
        fragmentActivity.supportFragmentManager.registerFragmentLifecycleCallbacks(
            BreadcrumbCallbacks(config, breadcrumbBuffer),
            true // recursive — capture nested fragments too
        )
        return true
    }

    /**
     * Cheap class-availability gate. Wrapped in try/catch since some
     * classloader configurations can throw [LinkageError] subclasses
     * other than [ClassNotFoundException] when an artifact is partially
     * available.
     */
    private fun isFragmentActivityAvailable(): Boolean = try {
        Class.forName(FRAGMENT_ACTIVITY_FQN)
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * Test seam: build the callbacks instance directly so unit tests
     * can register it on a Robolectric-provided FragmentManager
     * without going through [attach]'s Activity dance.
     */
    internal fun buildCallbacks(
        config: BreadcrumbConfig,
        breadcrumbBuffer: JourneyBreadcrumbBuffer
    ): FragmentManager.FragmentLifecycleCallbacks =
        BreadcrumbCallbacks(config, breadcrumbBuffer)

    private class BreadcrumbCallbacks(
        private val config: BreadcrumbConfig,
        private val breadcrumbBuffer: JourneyBreadcrumbBuffer
    ) : FragmentManager.FragmentLifecycleCallbacks() {

        override fun onFragmentCreated(
            fm: FragmentManager,
            fragment: Fragment,
            savedInstanceState: Bundle?
        ) {
            emit(fragment, "fragment_created")
        }

        override fun onFragmentResumed(fm: FragmentManager, fragment: Fragment) {
            emit(fragment, "fragment_enter")
        }

        override fun onFragmentPaused(fm: FragmentManager, fragment: Fragment) {
            emit(fragment, "fragment_exit")
        }

        override fun onFragmentDestroyed(fm: FragmentManager, fragment: Fragment) {
            emit(fragment, "fragment_destroyed")
        }

        private fun emit(fragment: Fragment, action: String) {
            val screen = fragment.javaClass.simpleName
            if (!config.shouldCaptureScreen(screen)) return
            val crumb = JourneyBreadcrumb.navigation(
                screen = screen,
                action = action,
                attributes = mapOf(
                    "fragment.class" to fragment.javaClass.name
                )
            )
            breadcrumbBuffer.add(crumb)
        }
    }
}
