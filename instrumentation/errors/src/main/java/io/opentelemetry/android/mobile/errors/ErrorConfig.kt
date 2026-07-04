/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.errors

import io.opentelemetry.android.mobile.instrumentation.Incubating
import java.util.concurrent.TimeUnit

/**
 * Configuration for error instrumentation.
 *
 * Defines behavior for error capture including:
 * - Uncaught exception handling
 * - Coroutine exception handling
 * - RxJava error hooks
 * - Deduplication (same error within time window)
 * - Rate limiting
 * - Stack trace scrubbing
 * - ProGuard mapping support
 *
 * Usage:
 * ```kotlin
 * val errorConfig = ErrorConfig(
 *     enabled = true,
 *     captureUncaughtExceptions = true,
 *     deduplicateWindowMs = 300000,
 *     rateLimit = 10
 * )
 * ```
 *
 * @property enabled Whether error instrumentation is enabled (default: true)
 * @property captureUncaughtExceptions Install uncaught exception handler (default: true)
 * @property captureCoroutineExceptions Hook into Kotlin coroutines (default: true)
 * @property captureRxJavaExceptions Hook into RxJava error handlers (default: false)
 * @property deduplicateWindowMs Time window for deduplication in ms (default: 5 minutes)
 * @property rateLimit Maximum errors to report per minute (default: 10)
 * @property maxStackTraceDepth Maximum stack trace depth to capture (default: 50)
 * @property scrubStackTraces Remove PII from stack traces (default: true)
 * @property attachBreadcrumbs Attach journey breadcrumbs to errors (default: true)
 * @property attachVitals Attach current vitals to errors (default: true)
 * @property proguardMappingFile Path to ProGuard mapping file for deobfuscation (default: null)
 * @property filterExceptions List of exception class names to ignore (default: empty)
 * @property captureExceptionMessages Include exception messages (default: true, privacy risk)
 * @property captureCauses Capture exception causes/suppressed (default: true)
 * @property flushOnError Trigger flush when error occurs (default: true)
 */
@Incubating
data class ErrorConfig(
    val enabled: Boolean = true,

    // Exception handling hooks
    val captureUncaughtExceptions: Boolean = true,
    val captureCoroutineExceptions: Boolean = true,
    val captureRxJavaExceptions: Boolean = false,

    // Deduplication and rate limiting
    val deduplicateWindowMs: Long = TimeUnit.MINUTES.toMillis(5),
    val rateLimit: Int = 10,  // per minute

    // Stack trace configuration
    val maxStackTraceDepth: Int = 50,
    val scrubStackTraces: Boolean = true,

    // Enrichment
    val attachBreadcrumbs: Boolean = true,
    val attachVitals: Boolean = true,

    // ProGuard support
    val proguardMappingFile: String? = null,

    // Filtering — network I/O exceptions are captured by OTelNetworkInterceptor as
    // http.error; letting them also fire as app.crash causes duplicate signals and
    // repeated policy flushes.
    val filterExceptions: List<String> = listOf(
        "java.net.SocketTimeoutException",
        "java.net.ConnectException",
        "java.net.UnknownHostException",
        "java.net.SocketException",
        "javax.net.ssl.SSLException",
        "java.io.InterruptedIOException",
    ),

    // Privacy
    val captureExceptionMessages: Boolean = true,
    val captureCauses: Boolean = true,

    // Flushing
    val flushOnError: Boolean = true
) {
    init {
        require(deduplicateWindowMs > 0) { "deduplicateWindowMs must be positive" }
        require(rateLimit > 0) { "rateLimit must be positive" }
        require(maxStackTraceDepth > 0) { "maxStackTraceDepth must be positive" }
    }

    /**
     * Check if an exception should be filtered (ignored).
     */
    fun shouldFilterException(throwable: Throwable): Boolean {
        if (!enabled) return true

        val className = throwable.javaClass.name
        return filterExceptions.any { filter ->
            className == filter || className.startsWith("$filter.")
        }
    }

    /**
     * Get exception fingerprint for deduplication.
     *
     * Fingerprint includes exception type and top stack frame.
     */
    fun getExceptionFingerprint(throwable: Throwable): String {
        val className = throwable.javaClass.name
        val message = throwable.message ?: ""
        val topFrame = throwable.stackTrace.firstOrNull()?.let {
            "${it.className}.${it.methodName}:${it.lineNumber}"
        } ?: ""

        return "$className|$topFrame|${message.take(100)}"
    }

    companion object {
        /**
         * Default configuration with recommended settings.
         */
        fun default(): ErrorConfig = ErrorConfig()

        /**
         * Minimal configuration - only critical errors.
         */
        fun minimal(): ErrorConfig = ErrorConfig(
            captureUncaughtExceptions = true,
            captureCoroutineExceptions = false,
            captureRxJavaExceptions = false,
            attachBreadcrumbs = false,
            attachVitals = false,
            flushOnError = true
        )

        /**
         * Debug configuration - capture all errors with full context.
         */
        fun debug(): ErrorConfig = ErrorConfig(
            captureUncaughtExceptions = true,
            captureCoroutineExceptions = true,
            captureRxJavaExceptions = true,
            deduplicateWindowMs = TimeUnit.MINUTES.toMillis(1),  // Shorter window
            rateLimit = 50,  // Higher limit
            maxStackTraceDepth = 100,
            scrubStackTraces = false,  // Don't scrub in debug
            attachBreadcrumbs = true,
            attachVitals = true,
            captureExceptionMessages = true,
            captureCauses = true,
            flushOnError = true
        )

        /**
         * Production configuration - balanced privacy and observability.
         */
        fun production(): ErrorConfig = ErrorConfig(
            captureUncaughtExceptions = true,
            captureCoroutineExceptions = true,
            captureRxJavaExceptions = false,
            deduplicateWindowMs = TimeUnit.MINUTES.toMillis(5),
            rateLimit = 10,
            maxStackTraceDepth = 50,
            scrubStackTraces = true,
            attachBreadcrumbs = true,
            attachVitals = true,
            captureExceptionMessages = true,  // Messages are valuable
            captureCauses = true,
            flushOnError = true,
            filterExceptions = listOf(
                // Common exceptions to ignore
                "java.util.concurrent.CancellationException",
                "kotlinx.coroutines.CancellationException"
            )
        )
    }
}
