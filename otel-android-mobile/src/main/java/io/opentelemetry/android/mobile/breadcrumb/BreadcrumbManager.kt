package io.opentelemetry.android.mobile.breadcrumb

import android.content.Context
import io.opentelemetry.android.mobile.navigation.NavigationInstrumentation

/**
 * Global manager for journey breadcrumbs.
 *
 * This singleton provides access to the breadcrumb buffer and coordinates
 * breadcrumb collection across different instrumentation modules.
 */
object BreadcrumbManager {

    @Volatile
    private var buffer: JourneyBreadcrumbBuffer? = null

    @Volatile
    private var config: BreadcrumbConfig? = null

    /**
     * Initialize the breadcrumb manager.
     *
     * @param context Application context
     * @param breadcrumbConfig Configuration for breadcrumb collection
     */
    fun initialize(context: Context, breadcrumbConfig: BreadcrumbConfig) {
        if (buffer == null) {
            synchronized(this) {
                if (buffer == null) {
                    config = breadcrumbConfig
                    buffer = JourneyBreadcrumbBuffer(breadcrumbConfig.maxSize)

                    // Initialize navigation instrumentation if enabled
                    if (breadcrumbConfig.captureNavigation) {
                        NavigationInstrumentation.initialize(
                            context,
                            breadcrumbConfig,
                            buffer!!
                        )
                    }
                }
            }
        }
    }

    /**
     * Get the breadcrumb buffer.
     *
     * @return The global breadcrumb buffer
     * @throws IllegalStateException if not initialized
     */
    fun getBuffer(): JourneyBreadcrumbBuffer {
        return buffer ?: throw IllegalStateException(
            "BreadcrumbManager not initialized. Call initialize() first."
        )
    }

    /**
     * Get the breadcrumb configuration.
     *
     * @return The breadcrumb configuration
     * @throws IllegalStateException if not initialized
     */
    fun getConfig(): BreadcrumbConfig {
        return config ?: throw IllegalStateException(
            "BreadcrumbManager not initialized. Call initialize() first."
        )
    }

    /**
     * Add a breadcrumb to the buffer.
     *
     * @param breadcrumb The breadcrumb to add
     */
    fun add(breadcrumb: JourneyBreadcrumb) {
        buffer?.add(breadcrumb)
    }

    /**
     * Get breadcrumbs as a list.
     *
     * @return List of all breadcrumbs
     */
    fun getBreadcrumbs(): List<JourneyBreadcrumb> {
        return buffer?.toList() ?: emptyList()
    }

    /**
     * Get breadcrumbs from a time window.
     *
     * @param windowMs Time window in milliseconds
     * @return List of breadcrumbs within the window
     */
    fun getWindow(windowMs: Long): List<JourneyBreadcrumb> {
        return buffer?.getWindow(windowMs) ?: emptyList()
    }

    /**
     * Get breadcrumbs as JSON string.
     *
     * @return JSON representation of breadcrumbs
     */
    fun toJson(): String {
        return buffer?.toJson() ?: "[]"
    }

    /**
     * Clear all breadcrumbs.
     */
    fun clear() {
        buffer?.clear()
    }

    /**
     * Check if breadcrumb manager is initialized.
     *
     * @return True if initialized
     */
    fun isInitialized(): Boolean {
        return buffer != null
    }
}
