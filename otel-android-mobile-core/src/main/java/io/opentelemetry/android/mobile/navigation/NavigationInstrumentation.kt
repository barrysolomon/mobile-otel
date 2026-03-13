/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.navigation

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import io.opentelemetry.android.mobile.breadcrumb.BreadcrumbConfig
import io.opentelemetry.android.mobile.breadcrumb.JourneyBreadcrumb
import io.opentelemetry.android.mobile.breadcrumb.JourneyBreadcrumbBuffer
import io.opentelemetry.android.mobile.instrumentation.Incubating
import io.opentelemetry.android.mobile.core.PiiScrubber

/**
 * Instrumentation for capturing navigation events and breadcrumbs.
 *
 * This class automatically tracks:
 * - Activity lifecycle (created, resumed, paused, destroyed)
 * - Deep link navigation
 * - Screen transitions
 *
 * All navigation events are added as breadcrumbs to the global buffer.
 */
@Incubating
class NavigationInstrumentation private constructor(
    private val context: Context,
    private val config: BreadcrumbConfig,
    private val breadcrumbBuffer: JourneyBreadcrumbBuffer
) {
    @Volatile
    private var enabled = config.enabled

    @Volatile
    private var currentScreen: String? = null

    companion object {
        @Volatile
        private var instance: NavigationInstrumentation? = null

        /**
         * Initialize navigation instrumentation.
         *
         * @param context Application context
         * @param config Breadcrumb configuration
         * @param breadcrumbBuffer Buffer to store breadcrumbs
         */
        fun initialize(
            context: Context,
            config: BreadcrumbConfig,
            breadcrumbBuffer: JourneyBreadcrumbBuffer
        ) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = NavigationInstrumentation(
                            context.applicationContext,
                            config,
                            breadcrumbBuffer
                        ).apply {
                            registerLifecycleCallbacks()
                        }
                    }
                }
            }
        }

        /**
         * Get the singleton instance.
         */
        fun getInstance(): NavigationInstrumentation {
            return instance ?: throw IllegalStateException(
                "NavigationInstrumentation not initialized"
            )
        }

        /**
         * Get the current screen name (Activity simple name).
         */
        fun getCurrentScreen(): String? {
            return instance?.currentScreen
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Lifecycle Callbacks
    // ─────────────────────────────────────────────────────────────

    private fun registerLifecycleCallbacks() {
        if (!config.captureNavigation) return

        (context as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    if (!enabled) return

                    val screenName = activity.javaClass.simpleName
                    if (!config.shouldCaptureScreen(screenName)) return

                    // Check for deep link
                    activity.intent?.data?.let { deepLinkUri ->
                        captureDeepLink(screenName, deepLinkUri)
                    }

                    // Screen created breadcrumb
                    val breadcrumb = JourneyBreadcrumb.navigation(
                        screen = screenName,
                        action = "screen_created",
                        attributes = mapOf(
                            "screen.class" to activity.javaClass.name
                        )
                    )
                    breadcrumbBuffer.add(breadcrumb)
                }

                override fun onActivityStarted(activity: Activity) {}

                override fun onActivityResumed(activity: Activity) {
                    if (!enabled) return

                    val screenName = activity.javaClass.simpleName
                    if (!config.shouldCaptureScreen(screenName)) return

                    currentScreen = screenName

                    // Screen enter breadcrumb
                    val breadcrumb = JourneyBreadcrumb.navigation(
                        screen = screenName,
                        action = "screen_enter",
                        attributes = mapOf(
                            "screen.class" to activity.javaClass.name
                        )
                    )
                    breadcrumbBuffer.add(breadcrumb)
                }

                override fun onActivityPaused(activity: Activity) {
                    if (!enabled) return

                    val screenName = activity.javaClass.simpleName
                    if (!config.shouldCaptureScreen(screenName)) return

                    // Screen exit breadcrumb
                    val breadcrumb = JourneyBreadcrumb.navigation(
                        screen = screenName,
                        action = "screen_exit",
                        attributes = mapOf(
                            "screen.class" to activity.javaClass.name
                        )
                    )
                    breadcrumbBuffer.add(breadcrumb)
                }

                override fun onActivityStopped(activity: Activity) {}

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

                override fun onActivityDestroyed(activity: Activity) {
                    if (!enabled) return

                    val screenName = activity.javaClass.simpleName
                    if (!config.shouldCaptureScreen(screenName)) return

                    // Screen destroyed breadcrumb
                    val breadcrumb = JourneyBreadcrumb.navigation(
                        screen = screenName,
                        action = "screen_destroyed",
                        attributes = mapOf(
                            "screen.class" to activity.javaClass.name
                        )
                    )
                    breadcrumbBuffer.add(breadcrumb)

                    if (currentScreen == screenName) {
                        currentScreen = null
                    }
                }
            }
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Deep Link Handling
    // ─────────────────────────────────────────────────────────────

    private fun captureDeepLink(screen: String, uri: Uri) {
        val scrubbedUri = if (config.scrubNetworkUrls) {
            PiiScrubber.scrubDeepLink(uri, allowQueryParams = false)
        } else {
            uri.toString()
        }

        val breadcrumb = JourneyBreadcrumb.navigation(
            screen = screen,
            action = "deep_link",
            attributes = mapOf(
                "deep_link.uri" to scrubbedUri,
                "deep_link.scheme" to (uri.scheme ?: "unknown"),
                "deep_link.host" to (uri.host ?: "unknown")
            )
        )
        breadcrumbBuffer.add(breadcrumb)
    }

    // ─────────────────────────────────────────────────────────────
    // Manual Navigation Tracking
    // ─────────────────────────────────────────────────────────────

    /**
     * Manually track a navigation event.
     *
     * Use this for custom navigation that isn't automatically captured
     * (e.g., ViewPager, custom navigation).
     *
     * @param screen Screen name
     * @param action Action type (e.g., "navigate", "back")
     * @param route Optional route identifier
     * @param attributes Additional attributes
     */
    fun trackNavigation(
        screen: String,
        action: String,
        route: String? = null,
        attributes: Map<String, String> = emptyMap()
    ) {
        if (!enabled || !config.captureNavigation) return
        if (!config.shouldCaptureScreen(screen)) return

        currentScreen = screen

        val breadcrumb = JourneyBreadcrumb.navigation(
            screen = screen,
            action = action,
            route = route,
            attributes = attributes
        )
        breadcrumbBuffer.add(breadcrumb)
    }

    /**
     * Track a back navigation event.
     *
     * @param fromScreen Screen being navigated away from
     * @param toScreen Screen being navigated to (if known)
     */
    fun trackBackNavigation(fromScreen: String, toScreen: String? = null) {
        if (!enabled || !config.captureNavigation) return

        val attrs = if (toScreen != null) {
            mapOf("to_screen" to toScreen)
        } else {
            emptyMap()
        }

        val breadcrumb = JourneyBreadcrumb.navigation(
            screen = fromScreen,
            action = "back_pressed",
            attributes = attrs
        )
        breadcrumbBuffer.add(breadcrumb)

        currentScreen = toScreen
    }

    // ─────────────────────────────────────────────────────────────
    // Control
    // ─────────────────────────────────────────────────────────────

    /**
     * Enable or disable navigation instrumentation.
     *
     * @param enabled True to enable, false to disable
     */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    /**
     * Check if navigation instrumentation is enabled.
     *
     * @return True if enabled
     */
    fun isEnabled(): Boolean = enabled
}
