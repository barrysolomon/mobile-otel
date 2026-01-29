package io.opentelemetry.android.mobile.breadcrumb

/**
 * Type of breadcrumb event for user journey tracking.
 */
enum class BreadcrumbType {
    /**
     * Screen navigation event (Activity/Fragment/Compose).
     */
    NAVIGATION,

    /**
     * User input event (click, text input, gesture).
     */
    USER_INPUT,

    /**
     * Network request event.
     */
    NETWORK,

    /**
     * Error or exception event.
     */
    ERROR,

    /**
     * App lifecycle event (foreground/background).
     */
    LIFECYCLE,

    /**
     * Custom user-defined event.
     */
    CUSTOM
}
