package io.opentelemetry.android.mobile.errors

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.android.mobile.breadcrumb.BreadcrumbManager
import io.opentelemetry.android.mobile.core.PiiScrubber
import io.opentelemetry.android.mobile.vitals.VitalsCollector
import kotlinx.coroutines.CoroutineExceptionHandler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
class ErrorInstrumentation private constructor(
    private val config: ErrorConfig,
    private val logger: Logger,
    private val onFlush: (() -> Unit)?
) {
    private val defaultExceptionHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    // Deduplication tracking
    private val errorFingerprints = ConcurrentHashMap<String, Long>()

    // Rate limiting
    private val errorsThisMinute = AtomicInteger(0)
    private var lastMinuteReset = System.currentTimeMillis()

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

            // Flush immediately on uncaught exception
            if (config.flushOnError) {
                onFlush?.invoke()
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
        if (!checkRateLimit()) {
            return
        }

        // Check deduplication
        val fingerprint = config.getExceptionFingerprint(throwable)
        if (!shouldCaptureError(fingerprint)) {
            return
        }

        // Build attributes
        val attributesBuilder = Attributes.builder()
            .put(AttributeKey.stringKey("error.source"), source)
            .put(AttributeKey.stringKey("error.type"), throwable.javaClass.name)
            .put(AttributeKey.stringKey("error.fingerprint"), fingerprint)

        context?.let {
            attributesBuilder.put(AttributeKey.stringKey("error.context"), it)
        }

        // Add exception message
        if (config.captureExceptionMessages) {
            val message = if (config.scrubStackTraces) {
                PiiScrubber.scrubExceptionMessage(throwable.message ?: "")
            } else {
                throwable.message ?: ""
            }
            attributesBuilder.put(AttributeKey.stringKey("error.message"), message)
        }

        // Add stack trace
        val stackTrace = buildStackTrace(throwable)
        attributesBuilder.put(AttributeKey.stringKey("error.stack_trace"), stackTrace)

        // Add causes if enabled
        if (config.captureCauses) {
            var cause = throwable.cause
            var causeDepth = 0
            while (cause != null && causeDepth < 5) {
                attributesBuilder.put(
                    AttributeKey.stringKey("error.cause.$causeDepth.type"),
                    cause.javaClass.name
                )
                cause.message?.let { msg ->
                    val scrubbedMsg = if (config.scrubStackTraces) {
                        PiiScrubber.scrubExceptionMessage(msg)
                    } else {
                        msg
                    }
                    attributesBuilder.put(
                        AttributeKey.stringKey("error.cause.$causeDepth.message"),
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
            attributesBuilder.put(AttributeKey.stringKey("user.journey"), breadcrumbsJson)
        }

        // Attach vitals
        if (config.attachVitals) {
            VitalsCollector.getInstance()?.getVitalsAttributes()?.let { vitalsAttrs ->
                vitalsAttrs.forEach { key, value ->
                    attributesBuilder.put(key as AttributeKey<Any>, value)
                }
            }
        }

        // Log the error
        logger.logRecordBuilder()
            .setBody("Exception captured: ${throwable.javaClass.simpleName}")
            .setSeverity(Severity.ERROR)
            .setAllAttributes(attributesBuilder.build())
            .emit()

        // Mark error as captured
        errorFingerprints[fingerprint] = System.currentTimeMillis()

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
     * Check rate limit.
     */
    private fun checkRateLimit(): Boolean {
        val now = System.currentTimeMillis()

        // Reset counter every minute
        synchronized(this) {
            if (now - lastMinuteReset > 60_000) {
                errorsThisMinute.set(0)
                lastMinuteReset = now
            }
        }

        // Check if under limit
        val count = errorsThisMinute.incrementAndGet()
        return count <= config.rateLimit
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
        return ErrorStatistics(
            uniqueErrors = errorFingerprints.size,
            errorsThisMinute = errorsThisMinute.get(),
            rateLimitActive = errorsThisMinute.get() >= config.rateLimit
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
         * @param onFlush Optional callback to trigger flush
         */
        fun initialize(
            config: ErrorConfig,
            logger: Logger,
            onFlush: (() -> Unit)? = null
        ): ErrorInstrumentation {
            return instance ?: synchronized(this) {
                instance ?: ErrorInstrumentation(config, logger, onFlush).also {
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
    }
}
