/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import android.util.Log
import io.opentelemetry.android.mobile.export.ExportStatus
import io.opentelemetry.android.mobile.export.ExportStatusManager
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import io.opentelemetry.android.mobile.instrumentation.Incubating

/**
 * Wrapper around LogRecordExporter that adds retry logic with exponential backoff.
 *
 * This ensures events aren't lost when the collector is temporarily unavailable.
 *
 * **Retry Strategy:**
 * - Initial delay: 1 second
 * - Max delay: 60 seconds
 * - Max retries: 3 (configurable)
 * - Exponential backoff: delay = min(initialDelay * 2^attempt, maxDelay)
 *
 * **Behavior:**
 * - On failure: Retries with exponential backoff using a shared scheduler (no raw threads)
 * - On non-retryable error: Fails immediately without retry
 * - After max retries: Returns failure (caller keeps events in buffer)
 * - On success: Returns immediately
 *
 * Usage:
 * ```kotlin
 * val otlpExporter = OtlpGrpcLogRecordExporter.builder()
 *     .setEndpoint(endpoint)
 *     .build()
 *
 * val retryableExporter = RetryableExporter(
 *     delegate = otlpExporter,
 *     maxRetries = 3
 * )
 * ```
 */
internal class RetryableExporter(
    private val delegate: LogRecordExporter,
    private val maxRetries: Int = 3,
    private val initialDelayMs: Long = 1000,
    private val maxDelayMs: Long = 60000,
    private val random: Random = Random.Default,
) : LogRecordExporter {

    private val TAG = "RetryableExporter"

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "OTel-RetryExporter").apply { isDaemon = true }
    }

    /**
     * Exports logs with retry logic.
     *
     * Attempts export up to (maxRetries + 1) times with exponential backoff.
     * If all retries fail, returns failure so caller can keep events in buffer.
     */
    override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
        return exportWithRetry(logs, attempt = 0)
    }

    private fun exportWithRetry(
        logs: Collection<LogRecordData>,
        attempt: Int
    ): CompletableResultCode {
        val result = CompletableResultCode()

        try {
            // Attempt export
            val exportResult = delegate.export(logs)

            exportResult.whenComplete {
                if (exportResult.isSuccess) {
                    Log.d(TAG, "Export succeeded on attempt ${attempt + 1}")
                    ExportStatusManager.notify(ExportStatus.Success(eventCount = logs.size))
                    result.succeed()
                } else {
                    if (attempt < maxRetries) {
                        val delayMs = calculateBackoff(attempt)
                        Log.w(TAG, "Export failed on attempt ${attempt + 1}, retrying in ${delayMs}ms...")
                        ExportStatusManager.notify(ExportStatus.Retrying(
                            attempt = attempt + 1, maxAttempts = maxRetries + 1, delayMs = delayMs
                        ))

                        try {
                            scheduler.schedule({
                                val retryResult = exportWithRetry(logs, attempt + 1)
                                retryResult.whenComplete {
                                    if (retryResult.isSuccess) {
                                        result.succeed()
                                    } else {
                                        result.fail()
                                    }
                                }
                            }, delayMs, TimeUnit.MILLISECONDS)
                        } catch (_: RejectedExecutionException) {
                            Log.d(TAG, "Scheduler shut down, abandoning retry")
                            result.fail()
                        }
                    } else {
                        val errorMsg = "Export failed after ${maxRetries + 1} attempts"
                        Log.e(TAG, errorMsg)
                        ExportStatusManager.notify(classifyFailure(errorMsg, logs.size, attempt + 1))
                        result.fail()
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Exception during export attempt ${attempt + 1}", e)

            if (isNonRetryableException(e)) {
                Log.e(TAG, "Non-retryable error, giving up immediately")
                ExportStatusManager.notify(classifyFailure(e.message ?: e.javaClass.simpleName, logs.size, attempt + 1))
                result.fail()
                return result
            }

            if (attempt < maxRetries) {
                val delayMs = calculateBackoff(attempt)
                Log.w(TAG, "Retrying in ${delayMs}ms...")
                ExportStatusManager.notify(ExportStatus.Retrying(
                    attempt = attempt + 1, maxAttempts = maxRetries + 1, delayMs = delayMs
                ))

                try {
                    scheduler.schedule({
                        val retryResult = exportWithRetry(logs, attempt + 1)
                        retryResult.whenComplete {
                            if (retryResult.isSuccess) {
                                result.succeed()
                            } else {
                                result.fail()
                            }
                        }
                    }, delayMs, TimeUnit.MILLISECONDS)
                } catch (_: RejectedExecutionException) {
                    Log.d(TAG, "Scheduler shut down, abandoning retry")
                    result.fail()
                }
            } else {
                ExportStatusManager.notify(classifyFailure(e.message ?: "unknown", logs.size, attempt + 1))
                result.fail()
            }
        }

        return result
    }

    /**
     * Determines if an exception indicates a non-retryable error.
     * Client-side errors won't be resolved by retrying.
     */
    private fun isNonRetryableException(e: Exception): Boolean {
        return e is IllegalArgumentException ||
            e is SecurityException ||
            e is UnsupportedOperationException
    }

    /**
     * Backoff with full jitter — SR-009. Without jitter every device in a fleet
     * retries in lockstep after a shared outage and re-DDoS's the collector;
     * see https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/.
     *
     * Envelope (initialDelayMs=1s, maxDelayMs=60s) — picked uniformly at random
     * in [0, ceiling]:
     * - Attempt 0: [0, 1s]
     * - Attempt 3: [0, 8s]
     * - Attempt 6+: [0, 60s] (capped)
     */
    private fun calculateBackoff(attempt: Int): Long {
        val exponentialDelay = initialDelayMs * (2.0.pow(attempt.toDouble())).toLong()
        val ceiling = min(exponentialDelay, maxDelayMs)
        return random.nextLong(0, ceiling + 1)
    }

    @androidx.annotation.VisibleForTesting
    internal fun calculateBackoffForTest(attempt: Int): Long = calculateBackoff(attempt)

    override fun flush(): CompletableResultCode {
        return delegate.flush()
    }

    override fun shutdown(): CompletableResultCode {
        scheduler.shutdown()
        return delegate.shutdown()
    }

    /**
     * Classifies a failure as auth error vs generic failure based on the error message.
     * gRPC status 16 (UNAUTHENTICATED) and common auth keywords trigger AuthError.
     */
    private fun classifyFailure(reason: String, eventCount: Int, attempt: Int): ExportStatus {
        val lower = reason.lowercase()
        val isAuth = lower.contains("authentication") ||
            lower.contains("unauthenticated") ||
            lower.contains("token") ||
            lower.contains("unauthorized") ||
            lower.contains("status code 16") ||
            lower.contains("permission denied")
        return if (isAuth) {
            ExportStatus.AuthError(reason = reason, eventCount = eventCount)
        } else {
            ExportStatus.Failed(reason = reason, eventCount = eventCount, attempt = attempt)
        }
    }

    /**
     * Gets the underlying delegate exporter.
     */
    fun getDelegate(): LogRecordExporter = delegate
}
