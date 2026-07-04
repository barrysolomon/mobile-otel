// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo

import io.opentelemetry.android.mobile.breadcrumb.BreadcrumbManager
import io.opentelemetry.android.mobile.breadcrumb.JourneyBreadcrumb

/**
 * Extension functions for easy breadcrumb tracking in the demo app.
 */

/**
 * Add a user input breadcrumb for button clicks.
 */
fun trackButtonClick(buttonId: String, screen: String = "MainActivity") {
    if (BreadcrumbManager.isInitialized()) {
        BreadcrumbManager.add(
            JourneyBreadcrumb.userInput(
                screen = screen,
                action = "click",
                elementId = buttonId
            )
        )
    }
}

/**
 * Add a custom breadcrumb for scenario actions.
 */
fun trackScenarioAction(scenarioName: String, action: String, screen: String = "MainActivity") {
    if (BreadcrumbManager.isInitialized()) {
        BreadcrumbManager.add(
            JourneyBreadcrumb.custom(
                screen = screen,
                action = action,
                attributes = mapOf(
                    "scenario" to scenarioName
                )
            )
        )
    }
}

/**
 * Get breadcrumb summary for logging.
 */
fun getBreadcrumbSummary(): String {
    return if (BreadcrumbManager.isInitialized()) {
        BreadcrumbManager.getBuffer().summary()
    } else {
        "Breadcrumbs not initialized"
    }
}

/**
 * Get breadcrumbs as attributes for a log event.
 */
fun getBreadcrumbAttributes(): Map<String, String> {
    return if (BreadcrumbManager.isInitialized()) {
        val buffer = BreadcrumbManager.getBuffer()
        mapOf(
            "mobile.user.journey" to buffer.toJson(),
            "mobile.user.journey.length" to buffer.size().toString(),
            "mobile.user.journey.duration_sec" to (buffer.duration() / 1000).toString()
        )
    } else {
        emptyMap()
    }
}
