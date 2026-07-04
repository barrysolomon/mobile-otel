/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.breadcrumb

import io.opentelemetry.android.mobile.core.PiiScrubber
import kotlinx.serialization.Serializable

/**
 * A breadcrumb representing a single event in the user's journey.
 *
 * Breadcrumbs are collected in a circular buffer and attached to critical events
 * (crashes, errors, UI freezes) to provide context about what the user was doing
 * leading up to the event.
 *
 * @property timestamp Event timestamp in milliseconds since epoch
 * @property type Type of breadcrumb event
 * @property screen Screen name (Activity/Fragment/Compose route)
 * @property action Action description (e.g., "click", "swipe", "navigate")
 * @property elementId UI element identifier (privacy-safe, can be null)
 * @property attributes Additional context attributes (key-value pairs)
 */
@Serializable
data class JourneyBreadcrumb(
    val timestamp: Long,
    val type: BreadcrumbType,
    val screen: String,
    val action: String,
    val elementId: String? = null,
    val attributes: Map<String, String> = emptyMap()
) {
    /**
     * Duration from this breadcrumb to another (in milliseconds).
     */
    fun durationTo(other: JourneyBreadcrumb): Long {
        return other.timestamp - this.timestamp
    }

    companion object {
        /**
         * Create a navigation breadcrumb.
         */
        fun navigation(
            screen: String,
            action: String,
            route: String? = null,
            attributes: Map<String, String> = emptyMap()
        ): JourneyBreadcrumb {
            val attrs = if (route != null) {
                attributes + ("route" to route)
            } else {
                attributes
            }
            return JourneyBreadcrumb(
                timestamp = System.currentTimeMillis(),
                type = BreadcrumbType.NAVIGATION,
                screen = screen,
                action = action,
                elementId = null,
                attributes = attrs
            )
        }

        /**
         * Create a user input breadcrumb.
         */
        fun userInput(
            screen: String,
            action: String,
            elementId: String? = null,
            attributes: Map<String, String> = emptyMap()
        ): JourneyBreadcrumb {
            return JourneyBreadcrumb(
                timestamp = System.currentTimeMillis(),
                type = BreadcrumbType.USER_INPUT,
                screen = screen,
                action = action,
                elementId = elementId,
                attributes = attributes
            )
        }

        /**
         * Create a network breadcrumb.
         */
        fun network(
            screen: String,
            method: String,
            url: String,
            statusCode: Int? = null,
            attributes: Map<String, String> = emptyMap()
        ): JourneyBreadcrumb {
            val attrs = mutableMapOf<String, String>()
            attrs["http.method"] = method
            attrs["http.url"] = url
            statusCode?.let { attrs["http.status_code"] = it.toString() }
            attrs.putAll(attributes)

            return JourneyBreadcrumb(
                timestamp = System.currentTimeMillis(),
                type = BreadcrumbType.NETWORK,
                screen = screen,
                action = "http_request",
                elementId = null,
                attributes = attrs
            )
        }

        /**
         * Create an error breadcrumb.
         */
        fun error(
            screen: String,
            errorType: String,
            message: String? = null,
            attributes: Map<String, String> = emptyMap()
        ): JourneyBreadcrumb {
            val attrs = mutableMapOf<String, String>()
            attrs["exception.type"] = errorType
            // Scrub PII out of the raw throwable message before it is stored in
            // a breadcrumb (mirrors ErrorInstrumentation.scrubExceptionMessage).
            message?.let { attrs["exception.message"] = PiiScrubber.scrubExceptionMessage(it) }
            attrs.putAll(attributes)

            return JourneyBreadcrumb(
                timestamp = System.currentTimeMillis(),
                type = BreadcrumbType.ERROR,
                screen = screen,
                action = "error",
                elementId = null,
                attributes = attrs
            )
        }

        /**
         * Create a lifecycle breadcrumb.
         */
        fun lifecycle(
            screen: String,
            action: String,
            attributes: Map<String, String> = emptyMap()
        ): JourneyBreadcrumb {
            return JourneyBreadcrumb(
                timestamp = System.currentTimeMillis(),
                type = BreadcrumbType.LIFECYCLE,
                screen = screen,
                action = action,
                elementId = null,
                attributes = attributes
            )
        }

        /**
         * Create a custom breadcrumb.
         */
        fun custom(
            screen: String,
            action: String,
            elementId: String? = null,
            attributes: Map<String, String> = emptyMap()
        ): JourneyBreadcrumb {
            return JourneyBreadcrumb(
                timestamp = System.currentTimeMillis(),
                type = BreadcrumbType.CUSTOM,
                screen = screen,
                action = action,
                elementId = elementId,
                attributes = attributes
            )
        }
    }
}
