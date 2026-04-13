/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.errors

import io.opentelemetry.android.mobile.instrumentation.Incubating
import io.opentelemetry.android.mobile.instrumentation.Supersedes
import io.opentelemetry.android.mobile.instrumentation.MobileSessionProvider
import io.opentelemetry.android.mobile.instrumentation.RateLimiter
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.android.mobile.breadcrumb.BreadcrumbManager
import io.opentelemetry.android.mobile.breadcrumb.JourneyBreadcrumb
import io.opentelemetry.android.mobile.core.PiiScrubber
import io.opentelemetry.android.mobile.vitals.VitalsCollector
import io.opentelemetry.sdk.common.CompletableResultCode
import kotlinx.coroutines.CoroutineExceptionHandler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * Instrumentation for error capture and reporting.
 *
 * Features:
 * - Uncaught exception handler
 * - Coroutine exception handler
 * - Deduplication (same error within time window)
 * - Rate limiting
 * - Stack trace scrubbing
 * - Breadcrumb and vitals attachment
 * - Automatic flush on error
 *
 * Thread-safe singleton that integrates with OpenTelemetry logging.
 */
@Incubating
@Supersedes("crash")
class ErrorInstrumentation private constructor(
    private val config: ErrorConfig,
    private val logger: Logger,
    private val onFlush: (() -> CompletableResultCode)?,
    private val sessionProvider: MobileSessionProvider? = null
) {
    private val defaultExceptionHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    // Deduplication tracking
    private val errorFingerprints = ConcurrentHashMap<String, Long>()

    // Rate limiting
    private val rateLimiter = RateLimiter(config.rateLimit)

    /**
     * Coroutine exception handler for Kotlin coroutines.
     */
    val coroutineExceptionHandler = CoroutineExceptionHandler { context, throwable ->
        if (config.captureCoroutineExceptions) {
            captureException(throwable, "coroutine", context.toString())
        }
    }

    init {
        if (config.captureUncaughtExceptions) {
            installUncaughtExceptionHandler()
        }

        if (config.captureRxJavaExceptions) {
            installRxJavaHooks()
        }
    }

    /**
     * Install uncaught exception handler.
     */
    private fun installUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            captureException(throwable, "uncaught", thread.name)

            // Flush immediately on uncaught exception.
            // MUST block until export completes — the process will die as soon as
            // the original handler runs. Without join(), the HTTP request fires
            // asynchronously and gets killed mid-flight, losing all buffered events.
            // 5s timeout: if export can't finish in time, events are on disk and
            // RecoveryTracker will re-export on next launch.
            if (config.flushOnError) {
                try {
                    onFlush?.invoke()?.join(5, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    // Don't let flush failure prevent the original handler from running
                }
            }

            // Call original handler
            defaultExceptionHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Install RxJava error hooks (if RxJava is available).
     */
    private fun installRxJavaHooks() {
        try {
            // Check if RxJava is available
            val rxJavaPluginsClass = Class.forName("io.reactivex.rxjava3.plugins.RxJavaPlugins")
            val setErrorHandlerMethod = rxJavaPluginsClass.getMethod(
                "setErrorHandler",
                Class.forName("io.reactivex.rxjava3.functions.Consumer")
            )

            // Create consumer that captures errors
            val consumerClass = Class.forName("io.reactivex.rxjava3.functions.Consumer")
            val consumer = java.lang.reflect.Proxy.newProxyInstance(
                consumerClass.classLoader,
                arrayOf(consumerClass)
            ) { _, _, args ->
                val throwable = args[0] as? Throwable
                throwable?.let { captureException(it, "rxjava", null) }
                null
            }

            setErrorHandlerMethod.invoke(null, consumer)
        } catch (e: Exception) {
            // RxJava not available or version mismatch - ignore
        }
    }

    /**
     * Capture an exception.
     *
     * @param throwable The exception to capture
     * @param source Source of the exception (uncaught, coroutine, rxjava, manual)
     * @param context Additional context (thread name, coroutine context, etc.)
     */
    fun captureException(
        throwable: Throwable,
        source: String = "manual",
        context: String? = null
    ) {
        if (!config.enabled) return

        // Check if exception should be filtered
        if (config.shouldFilterException(throwable)) {
            return
        }

        // Check rate limit
        if (!rateLimiter.tryAcquire()) {
            return
        }

        // Check deduplication
        val fingerprint = config.getExceptionFingerprint(throwable)
        if (!shouldCaptureError(fingerprint)) {
            return
        }

        // Mark current session as having experienced an error (for crash-free session tracking)
        sessionProvider?.markSessionError()

        // Build attributes using OTel semantic conventions for exceptions
        val attributesBuilder = Attributes.builder()
            .put(AttributeKey.stringKey("mobile.exception.origin"), source) // custom: OTel semconv has no standard for exception origin category
            .put(AttributeKey.stringKey("exception.type"), throwable.javaClass.name)
            .put(AttributeKey.stringKey("mobile.error.fingerprint"), fingerprint)

        context?.let {
            attributesBuilder.put(AttributeKey.stringKey("mobile.error.context"), it)
        }

        // Add exception message (semconv: exception.message)
        if (config.captureExceptionMessages) {
            val message = if (config.scrubStackTraces) {
                PiiScrubber.scrubExceptionMessage(throwable.message ?: "")
            } else {
                throwable.message ?: ""
            }
            attributesBuilder.put(AttributeKey.stringKey("exception.message"), message)
        }

        // Add stack trace (semconv: exception.stacktrace)
        val stackTrace = buildStackTrace(throwable)
        attributesBuilder.put(AttributeKey.stringKey("exception.stacktrace"), stackTrace)

        // Add causes if enabled
        if (config.captureCauses) {
            var cause = throwable.cause
            var causeDepth = 0
            while (cause != null && causeDepth < 5) {
                attributesBuilder.put(
                    AttributeKey.stringKey("exception.cause.$causeDepth.type"),
                    cause.javaClass.name
                )
                cause.message?.let { msg ->
                    val scrubbedMsg = if (config.scrubStackTraces) {
                        PiiScrubber.scrubExceptionMessage(msg)
                    } else {
                        msg
                    }
                    attributesBuilder.put(
                        AttributeKey.stringKey("exception.cause.$causeDepth.message"),
                        scrubbedMsg
                    )
                }
                cause = cause.cause
                causeDepth++
            }
        }

        // Attach breadcrumbs
        if (config.attachBreadcrumbs && BreadcrumbManager.isInitialized()) {
            val breadcrumbsJson = BreadcrumbManager.toJson()
            attributesBuilder.put(AttributeKey.stringKey("mobile.user.journey"), breadcrumbsJson)
        }

        // Attach vitals
        if (config.attachVitals) {
            VitalsCollector.getInstance()?.getVitalsAttributes()?.let { vitalsAttrs ->
                vitalsAttrs.forEach { key, value ->
                    attributesBuilder.put(key as AttributeKey<Any>, value)
                }
            }
        }

        // Stamp the fingerprint BEFORE emitting so concurrent handlers (e.g. multiple
        // uncaught-exception callbacks firing ms apart for the same crash) all see it and
        // skip — preventing duplicate app.crash events at the same timestamp.
        errorFingerprints[fingerprint] = System.currentTimeMillis()

        // Body is "app.crash" so the default crash-recovery policy matches.
        // Exception details are in attributes (exception.type, exception.message, exception.stacktrace).
        attributesBuilder.put(AttributeKey.stringKey("mobile.exception.summary"), "Exception captured: ${throwable.javaClass.simpleName}")
        logger.logRecordBuilder()
            .setBody("app.crash")
            .setSeverity(Severity.ERROR)
            .setAllAttributes(attributesBuilder.build())
            .emit()

        // Add breadcrumb for the error itself
        if (BreadcrumbManager.isInitialized()) {
            BreadcrumbManager.add(
                JourneyBreadcrumb.error(
                    screen = "unknown",
                    errorType = throwable.javaClass.name,
                    message = throwable.message,
                    attributes = mapOf("source" to source)
                )
            )
        }

        // Record exception on current span if active
        val currentSpan = Span.current()
        if (currentSpan.spanContext.isValid) {
            currentSpan.recordException(throwable)
            currentSpan.setStatus(StatusCode.ERROR, throwable.message ?: throwable.javaClass.simpleName)
        }

        // Trigger flush if configured
        if (config.flushOnError && source != "uncaught") {  // Uncaught already flushes
            onFlush?.invoke()
        }
    }

    /**
     * Build stack trace string from throwable.
     */
    private fun buildStackTrace(throwable: Throwable): String {
        return if (config.scrubStackTraces) {
            PiiScrubber.scrubStackTrace(throwable.stackTrace, config.maxStackTraceDepth)
        } else {
            val frames = throwable.stackTrace.take(config.maxStackTraceDepth)
            frames.joinToString("\n") { frame ->
                "  at ${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})"
            }
        }
    }

    /**
     * Check if we should capture this error based on deduplication.
     */
    private fun shouldCaptureError(fingerprint: String): Boolean {
        val now = System.currentTimeMillis()
        val lastSeen = errorFingerprints[fingerprint]

        // First time seeing this error
        if (lastSeen == null) {
            return true
        }

        // Check if outside deduplication window
        return (now - lastSeen) > config.deduplicateWindowMs
    }

    /**
     * Manually capture an exception.
     */
    fun recordException(throwable: Throwable, context: Map<String, String> = emptyMap()) {
        captureException(throwable, "manual", context.toString())
    }

    /**
     * Clear deduplication cache.
     */
    fun clearDeduplicationCache() {
        errorFingerprints.clear()
    }

    /**
     * Get error statistics.
     */
    fun getStatistics(): ErrorStatistics {
        val count = rateLimiter.currentCount
        return ErrorStatistics(
            uniqueErrors = errorFingerprints.size,
            errorsThisMinute = count,
            rateLimitActive = count >= config.rateLimit
        )
    }

    /**
     * Error statistics data class.
     */
    data class ErrorStatistics(
        val uniqueErrors: Int,
        val errorsThisMinute: Int,
        val rateLimitActive: Boolean
    )

    companion object {
        @Volatile
        private var instance: ErrorInstrumentation? = null

        /**
         * Initialize error instrumentation.
         *
         * @param config Error configuration
         * @param logger OpenTelemetry logger
         * @param onFlush Optional callback to trigger flush. Returns CompletableResultCode
         *                so the crash handler can block until export completes.
         * @param sessionProvider Optional session provider for crash-free session tracking
         */
        fun initialize(
            config: ErrorConfig,
            logger: Logger,
            onFlush: (() -> CompletableResultCode)? = null,
            sessionProvider: MobileSessionProvider? = null
        ): ErrorInstrumentation {
            return instance ?: synchronized(this) {
                instance ?: ErrorInstrumentation(config, logger, onFlush, sessionProvider).also {
                    instance = it
                }
            }
        }

        /**
         * Get error instrumentation instance.
         */
        fun getInstance(): ErrorInstrumentation? = instance

        /**
         * Check if error instrumentation is initialized.
         */
        fun isInitialized(): Boolean = instance != null

        /**
         * Reset the singleton. Visible for testing only.
         */
        internal fun reset() {
            instance?.restoreDefaultExceptionHandler()
            instance = null
        }
    }

    /** Restore the exception handler we replaced during init. */
    private fun restoreDefaultExceptionHandler() {
        defaultExceptionHandler?.let { Thread.setDefaultUncaughtExceptionHandler(it) }
    }
}
