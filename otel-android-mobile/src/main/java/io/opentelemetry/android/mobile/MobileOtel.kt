/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile

import android.content.Context
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.core.SessionManager
import io.opentelemetry.android.mobile.core.UserIdentity
import io.opentelemetry.android.mobile.breadcrumb.BreadcrumbManager
import io.opentelemetry.android.mobile.errors.ErrorInstrumentation
import io.opentelemetry.android.mobile.instrumentation.Incubating
import io.opentelemetry.android.mobile.vitals.VitalsCollector
import io.opentelemetry.android.mobile.predictive.DeviceHealthMonitor
import io.opentelemetry.android.mobile.predictive.HealthMetricsCollector
import io.opentelemetry.android.mobile.predictive.OnDevicePredictor
import io.opentelemetry.android.mobile.predictive.PredictiveExportPolicy
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.sdk.common.CompletableResultCode

/**
 * Main facade for the Mobile OpenTelemetry SDK.
 *
 * This object provides a simplified API for:
 * - Initialization (wires all auto-instrumentation modules)
 * - Session management
 * - User identity
 * - Global attributes
 * - Custom events
 * - Error reporting
 * - Flush control
 *
 * All instrumentation modules are automatically initialized and wired together:
 * - **ErrorInstrumentation**: Uncaught exceptions, coroutines, RxJava errors → auto flush
 * - **VitalsCollector**: App start, jank, memory, thermal → OTel metrics
 * - **PredictiveExportPolicy**: Crash/network-loss risk → pre-emptive flush
 * - **HealthMetricsCollector**: Device health → OTel metrics
 *
 * Example usage:
 * ```
 * // Initialize — all instrumentation starts automatically
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
 * // Force flush
 * MobileOtel.forceFlush()
 * ```
 */
@Incubating
object MobileOtel {

    private var provider: MobileLoggerProvider? = null
    private var errorInstrumentation: ErrorInstrumentation? = null
    private var vitalsCollector: VitalsCollector? = null
    private var predictivePolicy: PredictiveExportPolicy? = null
    private var healthMetricsCollector: HealthMetricsCollector? = null

    // ─────────────────────────────────────────────────────────────
    // Initialization
    // ─────────────────────────────────────────────────────────────

    /**
     * Initialize the Mobile OpenTelemetry SDK.
     *
     * This must be called before any other MobileOtel methods, typically in
     * Application.onCreate(). Automatically wires all instrumentation modules:
     * - Error capture (uncaught exceptions, coroutines, RxJava)
     * - Vitals monitoring (app start, jank, memory, thermal)
     * - Predictive export (crash risk, network loss → pre-emptive flush)
     * - Health metrics (device state → OTel metrics)
     *
     * @param context Application context
     * @param config Mobile configuration
     * @return The initialized MobileLoggerProvider
     */
    fun initialize(context: Context, config: MobileConfig): MobileLoggerProvider {
        val appContext = context.applicationContext

        // Initialize SessionManager FIRST (early init strategy)
        SessionManager.initialize(
            appContext,
            config.sessionConfig,
            logger = null // Will be set after MobileLoggerProvider initializes
        )

        // Initialize BreadcrumbManager
        BreadcrumbManager.initialize(
            appContext,
            config.breadcrumbConfig
        )

        // Initialize core MobileLoggerProvider (creates processor, exporters, OTel SDK)
        val loggerProvider = MobileLoggerProvider.getInstance(appContext, config)
        provider = loggerProvider

        val processor = loggerProvider.getMobileProcessor()
        val otelSdk = loggerProvider.getOpenTelemetrySdk()
        val meter = otelSdk.getMeter("io.opentelemetry.android.mobile")

        // Wire ErrorInstrumentation — captures uncaught exceptions, coroutine errors, RxJava errors
        // On error → flushes all buffered telemetry immediately
        if (config.errorConfig.enabled) {
            errorInstrumentation = ErrorInstrumentation.initialize(
                config = config.errorConfig,
                logger = loggerProvider.get("error-instrumentation"),
                onFlush = { processor.forceFlush() }
            )
        }

        // Wire VitalsCollector — app start, jank, memory, thermal as OTel metrics
        if (config.vitalsConfig.enabled) {
            vitalsCollector = VitalsCollector.initialize(
                context = appContext,
                config = config.vitalsConfig,
                meter = meter
            )
        }

        // Wire PredictiveExportPolicy — monitors device health, flushes pre-emptively
        // when crash risk or network loss risk exceeds threshold.
        // In HYBRID mode the processor's heartbeat tick drives runPredictionCycle() via
        // predictionCycleHook — so we must NOT start a second self-owned scheduler here,
        // otherwise prediction fires twice per tick and re-exports already-cleared events.
        val isHybrid = config.exportMode == io.opentelemetry.android.mobile.config.ExportMode.HYBRID
        predictivePolicy = PredictiveExportPolicy.builder(appContext)
            .setProcessor(processor)
            .setLogger(loggerProvider.get("predictive-export"))
            .setPredictionIntervalSeconds(config.predictionIntervalSeconds)
            .setStartOwnScheduler(!isHybrid)  // HYBRID: driven by heartbeat; others: self-scheduled
            .build()

        // HYBRID: co-locate prediction.cycle with device.heartbeat on a single shared timer.
        if (isHybrid) {
            processor.predictionCycleHook = { predictivePolicy?.runPredictionCycle() }
        }

        // Wire HealthMetricsCollector — exposes device health & predictions as OTel metrics
        healthMetricsCollector = HealthMetricsCollector.builder(appContext)
            .setOpenTelemetry(otelSdk)
            .setPredictor(OnDevicePredictor.getInstance(appContext))
            .build()

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
    // Custom Events
    // ─────────────────────────────────────────────────────────────

    /**
     * Send a custom event through the OTel pipeline.
     *
     * Events go into the ring buffer and are subject to the same policy-based
     * export as auto-captured events.
     *
     * @param name Event name (e.g., "checkout.completed", "search.performed")
     * @param attributes Optional attributes to attach to the event
     * @param severity Event severity (default: INFO)
     */
    fun sendEvent(
        name: String,
        attributes: Map<String, Any> = emptyMap(),
        severity: Severity = Severity.INFO
    ) {
        val logger = getProvider().get("custom-events")
        val builder = logger.logRecordBuilder()
            .setBody(name)
            .setSeverity(severity)

        if (attributes.isNotEmpty()) {
            val attrsBuilder = Attributes.builder()
            attributes.forEach { (key, value) ->
                when (value) {
                    is String -> attrsBuilder.put(AttributeKey.stringKey(key), value)
                    is Long -> attrsBuilder.put(AttributeKey.longKey(key), value)
                    is Int -> attrsBuilder.put(AttributeKey.longKey(key), value.toLong())
                    is Double -> attrsBuilder.put(AttributeKey.doubleKey(key), value)
                    is Float -> attrsBuilder.put(AttributeKey.doubleKey(key), value.toDouble())
                    is Boolean -> attrsBuilder.put(AttributeKey.booleanKey(key), value)
                    else -> attrsBuilder.put(AttributeKey.stringKey(key), value.toString())
                }
            }
            builder.setAllAttributes(attrsBuilder.build())
        }

        builder.emit()
    }

    // ─────────────────────────────────────────────────────────────
    // Error Reporting
    // ─────────────────────────────────────────────────────────────

    /**
     * Manually report an error/exception.
     *
     * The error is captured through ErrorInstrumentation (with deduplication,
     * rate limiting, stack trace scrubbing, and breadcrumb attachment) and
     * optionally triggers a buffer flush.
     *
     * @param throwable The exception to report
     * @param context Additional context as key-value pairs
     */
    fun reportError(throwable: Throwable, context: Map<String, String> = emptyMap()) {
        val errorInst = errorInstrumentation
        if (errorInst != null) {
            errorInst.recordException(throwable, context)
        } else {
            // Fallback: log directly if ErrorInstrumentation not initialized
            val logger = getProvider().get("error-reporting")
            logger.logRecordBuilder()
                .setBody("Exception: ${throwable.javaClass.simpleName}")
                .setSeverity(Severity.ERROR)
                .setAllAttributes(
                    Attributes.builder()
                        .put(AttributeKey.stringKey("exception.type"), throwable.javaClass.name)
                        .put(AttributeKey.stringKey("exception.message"), throwable.message ?: "")
                        .put(AttributeKey.stringKey("exception.origin"), "manual")
                        .build()
                )
                .emit()
        }
    }

    /**
     * Get the coroutine exception handler for use with Kotlin coroutines.
     *
     * Usage:
     * ```
     * val scope = CoroutineScope(Dispatchers.IO + MobileOtel.coroutineExceptionHandler)
     * ```
     *
     * @return CoroutineExceptionHandler that captures exceptions through ErrorInstrumentation,
     *         or null if ErrorInstrumentation is not initialized
     */
    fun getCoroutineExceptionHandler(): kotlinx.coroutines.CoroutineExceptionHandler? {
        return errorInstrumentation?.coroutineExceptionHandler
    }

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
        val loggerProvider = getProvider()
        return if (windowMinutes != null) {
            // Selective flush: only events in the time window
            loggerProvider.getMobileProcessor().flushWindow(windowMinutes)
        } else {
            // Full flush: all buffered events across all signals
            loggerProvider.forceFlush(timeoutSeconds)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Predictive Intelligence
    // ─────────────────────────────────────────────────────────────

    /**
     * Get the current device health prediction.
     *
     * Returns risk scores (0.0-1.0) for crash, network loss,
     * performance degradation, and battery drain.
     *
     * @return Current prediction, or null if predictive policy is not initialized
     */
    fun getCurrentPrediction() = predictivePolicy?.getCurrentPrediction()

    // ─────────────────────────────────────────────────────────────
    // Error Statistics
    // ─────────────────────────────────────────────────────────────

    /**
     * Get error capture statistics (unique errors, rate limit status).
     */
    fun getErrorStatistics() = errorInstrumentation?.getStatistics()

    /**
     * Get current buffer statistics (RAM/disk usage).
     */
    fun getBufferStats() = provider?.getMobileProcessor()?.getBufferStats()

    // ─────────────────────────────────────────────────────────────
    // Shutdown
    // ─────────────────────────────────────────────────────────────

    /**
     * Shutdown the Mobile OTel SDK and release all resources.
     *
     * Performs a final flush, shuts down all instrumentation modules,
     * and releases all resources.
     */
    fun shutdown() {
        predictivePolicy?.shutdown()
        predictivePolicy = null

        healthMetricsCollector?.shutdown()
        healthMetricsCollector = null

        vitalsCollector = null
        errorInstrumentation = null

        provider?.shutdown()
        SessionManager.getInstance().shutdown()
        provider = null
    }
}
