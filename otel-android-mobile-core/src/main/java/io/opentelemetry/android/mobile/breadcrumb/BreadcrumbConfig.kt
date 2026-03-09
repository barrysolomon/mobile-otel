/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.breadcrumb

/**
 * Configuration for journey breadcrumb collection.
 *
 * Breadcrumbs track user actions and navigation to provide context for debugging
 * crashes, errors, and other issues.
 *
 * @property enabled Enable breadcrumb collection
 * @property maxSize Maximum number of breadcrumbs to store in circular buffer
 * @property captureNavigation Capture navigation events (Activity/Fragment/Compose)
 * @property captureUserInput Capture user input events (clicks, taps, gestures)
 * @property captureNetwork Capture network request events
 * @property captureErrors Capture error and exception events
 * @property scrubElementIds Remove element IDs that may contain PII
 * @property scrubNetworkUrls Scrub URLs to remove sensitive query parameters
 * @property allowedScreens If non-empty, only capture breadcrumbs for these screens
 */
data class BreadcrumbConfig(
    val enabled: Boolean = true,
    val maxSize: Int = 50,

    // Capture filters
    val captureNavigation: Boolean = true,
    val captureUserInput: Boolean = true,
    val captureNetwork: Boolean = true,
    val captureErrors: Boolean = true,

    // Privacy controls
    val scrubElementIds: Boolean = true,
    val scrubNetworkUrls: Boolean = true,
    val allowedScreens: Set<String> = emptySet()
) {
    init {
        require(maxSize > 0) { "maxSize must be positive" }
    }

    /**
     * Check if breadcrumbs should be captured for a given screen.
     *
     * @param screen Screen name to check
     * @return True if breadcrumbs should be captured
     */
    fun shouldCaptureScreen(screen: String): Boolean {
        return allowedScreens.isEmpty() || screen in allowedScreens
    }

    companion object {
        /**
         * Default configuration with all breadcrumbs enabled.
         */
        fun default(): BreadcrumbConfig = BreadcrumbConfig()

        /**
         * Minimal configuration with only navigation and errors.
         */
        fun minimal(): BreadcrumbConfig = BreadcrumbConfig(
            captureUserInput = false,
            captureNetwork = false
        )

        /**
         * Privacy-focused configuration with aggressive scrubbing.
         */
        fun privacyFocused(): BreadcrumbConfig = BreadcrumbConfig(
            captureUserInput = false,
            scrubElementIds = true,
            scrubNetworkUrls = true
        )

        /**
         * Disabled configuration (no breadcrumbs).
         */
        fun disabled(): BreadcrumbConfig = BreadcrumbConfig(
            enabled = false
        )
    }
}
