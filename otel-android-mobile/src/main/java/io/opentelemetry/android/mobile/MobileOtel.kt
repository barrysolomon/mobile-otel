package io.opentelemetry.android.mobile

import android.content.Context
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.core.SessionManager
import io.opentelemetry.android.mobile.core.UserIdentity
import io.opentelemetry.android.mobile.breadcrumb.BreadcrumbManager
import io.opentelemetry.sdk.common.CompletableResultCode

/**
 * Main facade for the Mobile OpenTelemetry SDK.
 *
 * This object provides a simplified API for:
 * - Initialization
 * - Session management
 * - User identity
 * - Global attributes
 * - Custom events (future)
 * - Error reporting (future)
 * - Module control (future)
 * - Flush control
 *
 * Example usage:
 * ```
 * // Initialize
 * val config = MobileConfig(
 *     serviceName = "my-app",
 *     serviceVersion = "1.0.0",
 *     collectorEndpoint = "https://collector.example.com:4317"
 * )
 * MobileOtel.initialize(context, config)
 *
 * // Identify user
 * MobileOtel.identify(UserIdentity(userId = "user123"))
 *
 * // Add global attribute
 * MobileOtel.addGlobalAttribute("feature_flag", "new_checkout")
 *
 * // Force flush
 * MobileOtel.forceFlush()
 * ```
 */
object MobileOtel {

    private var provider: MobileLoggerProvider? = null

    // ─────────────────────────────────────────────────────────────
    // Initialization
    // ─────────────────────────────────────────────────────────────

    /**
     * Initialize the Mobile OpenTelemetry SDK.
     *
     * This must be called before any other MobileOtel methods, typically in
     * Application.onCreate().
     *
     * @param context Application context
     * @param config Mobile configuration
     * @return The initialized MobileLoggerProvider
     */
    fun initialize(context: Context, config: MobileConfig): MobileLoggerProvider {
        // Initialize SessionManager FIRST (early init strategy)
        SessionManager.initialize(
            context.applicationContext,
            config.sessionConfig,
            logger = null // Will be set after MobileLoggerProvider initializes
        )

        // Initialize BreadcrumbManager
        BreadcrumbManager.initialize(
            context.applicationContext,
            config.breadcrumbConfig
        )

        // TODO: Initialize instrumentation modules when implemented
        // config.vitalsConfig?.let { VitalsCollector.initialize(context, it) }
        // config.networkConfig?.let { NetworkInstrumentation.initialize(context, it) }
        // config.errorConfig?.let { ErrorInstrumentation.initialize(context, it) }

        // Initialize core MobileLoggerProvider
        val loggerProvider = MobileLoggerProvider.getInstance(context, config)
        provider = loggerProvider

        return loggerProvider
    }

    /**
     * Get the MobileLoggerProvider instance.
     *
     * @throws IllegalStateException if initialize() has not been called
     */
    fun getProvider(): MobileLoggerProvider {
        return provider ?: throw IllegalStateException(
            "MobileOtel not initialized. Call MobileOtel.initialize() first."
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Session Management
    // ─────────────────────────────────────────────────────────────

    /**
     * Identify the current user. User ID is attached to all telemetry.
     *
     * @param user User identity (ID + optional metadata)
     */
    fun identify(user: UserIdentity) {
        SessionManager.getInstance().identify(user)
    }

    /**
     * Clear user identity. Future telemetry will be anonymous.
     */
    fun clearIdentity() {
        SessionManager.getInstance().clearIdentity()
    }

    /**
     * Terminate the current session. A new session will start on next app use.
     *
     * @param reason Reason for termination (e.g., "logout", "account_switch")
     */
    fun terminateSession(reason: String = "manual") {
        SessionManager.getInstance().terminateSession(reason)
    }

    /**
     * Enable/disable session tracking.
     *
     * @param enabled True to enable, false to disable
     */
    fun setSessionEnabled(enabled: Boolean) {
        SessionManager.getInstance().setEnabled(enabled)
    }

    // ─────────────────────────────────────────────────────────────
    // Global Attributes
    // ─────────────────────────────────────────────────────────────

    /**
     * Add a global attribute that will be attached to all telemetry.
     *
     * @param key Attribute key (must be alphanumeric + underscores/dots)
     * @param value Attribute value (String, Number, Boolean)
     */
    fun addGlobalAttribute(key: String, value: Any) {
        SessionManager.getInstance().addGlobalAttribute(key, value)
    }

    /**
     * Remove a global attribute.
     *
     * @param key Attribute key to remove
     */
    fun removeGlobalAttribute(key: String) {
        SessionManager.getInstance().removeGlobalAttribute(key)
    }

    /**
     * Clear all global attributes.
     */
    fun clearGlobalAttributes() {
        SessionManager.getInstance().clearGlobalAttributes()
    }

    // ─────────────────────────────────────────────────────────────
    // Custom Events (Future - Phase 6)
    // ─────────────────────────────────────────────────────────────

    // TODO: Implement in Phase 6
    // fun sendEvent(name: String, options: EventOptions = EventOptions())

    // ─────────────────────────────────────────────────────────────
    // Error Reporting (Future - Phase 5)
    // ─────────────────────────────────────────────────────────────

    // TODO: Implement in Phase 5
    // fun reportError(throwable: Throwable, context: Map<String, Any> = emptyMap())

    // ─────────────────────────────────────────────────────────────
    // Module Control (Future - Phases 3-5)
    // ─────────────────────────────────────────────────────────────

    // TODO: Implement in Phases 3-5
    // fun setModuleEnabled(module: InstrumentationModule, enabled: Boolean)

    // ─────────────────────────────────────────────────────────────
    // Flush Control
    // ─────────────────────────────────────────────────────────────

    /**
     * Force flush all buffered telemetry to collector.
     *
     * @param windowMinutes Optional: flush only last N minutes (null = flush all)
     * @param timeoutSeconds Timeout for flush operation (default: 30 seconds)
     * @return CompletableResultCode indicating success/failure
     */
    fun forceFlush(
        windowMinutes: Int? = null,
        timeoutSeconds: Long = 30
    ): CompletableResultCode {
        return getProvider().forceFlush(timeoutSeconds)
        // TODO: Implement windowMinutes support in MobileLogRecordProcessor
    }

    /**
     * Shutdown the Mobile OTel SDK and release all resources.
     */
    fun shutdown() {
        provider?.shutdown()
        SessionManager.getInstance().shutdown()
        provider = null
    }
}
