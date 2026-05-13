/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile

import android.content.Context
import io.opentelemetry.android.mobile.config.ExporterCustomizers
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.core.SessionManager
import io.opentelemetry.android.mobile.core.UserIdentity
import io.opentelemetry.android.mobile.breadcrumb.BreadcrumbManager
import io.opentelemetry.android.mobile.errors.ErrorInstrumentation
import io.opentelemetry.android.mobile.instrumentation.DefaultMobileSessionProvider
import io.opentelemetry.android.mobile.instrumentation.Incubating
import io.opentelemetry.android.mobile.instrumentation.MobileSessionProvider
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
    private var releaseHealthSessionProvider: MobileSessionProvider? = null

    /** The active OpenTelemetryMobile instance, available after initialize(context) { } DSL. */
    var openTelemetryMobile: OpenTelemetryMobile? = null

    /** Current export mode, set during initialization. */
    private var currentExportMode: io.opentelemetry.android.mobile.config.ExportMode? = null

    fun getExportMode() = currentExportMode

    // ─────────────────────────────────────────────────────────────
    // Initialization
    // ─────────────────────────────────────────────────────────────

    /**
     * SDK-internal core initializer. Customers call [io.opentelemetry.android.mobile.OTelMobile.start]
     * instead — it's the single public entry point and routes here.
     *
     * The two-entry-point shape (`OTelMobile.start` + `MobileOtel.initialize`)
     * caused real production confusion (memory: `feedback_sdk_two_entry_points`).
     * Architecture-hardening epic Track 5 narrows the public initialization
     * surface to `OTelMobile.start`; other `MobileOtel` methods (identify,
     * forceFlush, sendEvent, etc.) remain public — they're orthogonal singletons.
     *
     * @param context Application context
     * @param config Mobile configuration
     * @return The initialized MobileLoggerProvider
     */
    internal fun initialize(
        context: Context,
        config: MobileConfig,
        customizers: ExporterCustomizers = ExporterCustomizers()
    ): MobileLoggerProvider {
        // Idempotency: first caller wins — prevents double instrumentation
        // setup when Android-side init races JS-side Dash0Mobile.start().
        provider?.let { return it }

        val appContext = context.applicationContext
        currentExportMode = config.exportMode

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
        val loggerProvider = MobileLoggerProvider.getInstance(appContext, config, customizers)
        provider = loggerProvider

        val processor = loggerProvider.getMobileProcessor()
        val otelSdk = loggerProvider.getOpenTelemetrySdk()
        val meter = otelSdk.getMeter("io.opentelemetry.android.mobile")

        // Create a session provider with meter for crash-free session tracking
        val sessionProv = DefaultMobileSessionProvider(meter = meter)
        releaseHealthSessionProvider = sessionProv

        // Wire ErrorInstrumentation — captures uncaught exceptions, coroutine errors, RxJava errors
        // On error → flushes all buffered telemetry immediately
        if (config.errorConfig.enabled) {
            errorInstrumentation = ErrorInstrumentation.initialize(
                config = config.errorConfig,
                logger = loggerProvider.get("error-instrumentation"),
                onFlush = { processor.forceFlush() },
                onCrashPersist = { processor.persistForCrash() },
                sessionProvider = sessionProv
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
     * Initialize the SDK with a Kotlin DSL block.
     *
     * This is the canonical DSL entry point used by all demo apps and
     * customers wanting an idiomatic Kotlin configuration shape. The
     * imperative form ([initialize] with positional `MobileConfig`) is
     * internal — customers needing imperative configuration call
     * [io.opentelemetry.android.mobile.OTelMobile.start] instead.
     *
     * Pre-architecture-hardening this object exposed BOTH initialization
     * entries publicly, which produced the "two entry points" footgun
     * documented in `feedback_sdk_two_entry_points`. Track 5 narrows the
     * surface to one per call style.
     */
    fun initialize(
        context: Context,
        block: io.opentelemetry.android.mobile.config.MobileOtelDsl.() -> Unit
    ): OpenTelemetryMobile {
        openTelemetryMobile?.let { return it }

        val dsl = io.opentelemetry.android.mobile.config.MobileOtelDsl().apply(block)
        val config = dsl.buildConfig()
        val customizers = dsl.buildCustomizers()

        // 1. Initialize core SDK (existing path)
        val loggerProvider = initialize(context, config, customizers)

        // 2. Build instrumentation registry (OTelMobileBuilder path)
        val app = context.applicationContext as android.app.Application
        val builder = io.opentelemetry.android.mobile.instrumentation.OTelMobileBuilder(
            app, loggerProvider.getOpenTelemetrySdk()
        )
        // Bridge UiTelemetryMode between config package and instrumentation package
        val instrMode = io.opentelemetry.android.mobile.instrumentation.UiTelemetryMode.valueOf(
            dsl.uiTelemetryMode.name
        )
        builder.setUiTelemetryMode(instrMode)
        dsl.applyInstrumentationsTo(builder)
        val handle = builder.build()

        // 3. Create and store the return type
        val mobile = OpenTelemetryMobile(
            openTelemetry = loggerProvider.getOpenTelemetrySdk(),
            handle = handle,
            sessionProvider = handle.sessionProvider
                ?: error("sessionProvider not set — OTelMobileBuilder.build() must call registry.install() first"),
            loggerProvider = loggerProvider
        )
        openTelemetryMobile = mobile
        return mobile
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
                        .put(AttributeKey.stringKey("mobile.exception.origin"), "manual")
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
        releaseHealthSessionProvider = null
        openTelemetryMobile = null

        provider?.shutdown()
        try { SessionManager.getInstance().shutdown() } catch (_: IllegalStateException) {}
        provider = null
    }
}
