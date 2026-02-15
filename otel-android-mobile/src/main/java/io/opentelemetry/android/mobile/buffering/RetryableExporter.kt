/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import android.util.Log
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

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
 * - On failure: Retries with exponential backoff
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
class RetryableExporter(
    private val delegate: LogRecordExporter,
    private val maxRetries: Int = 3,
    private val initialDelayMs: Long = 1000,
    private val maxDelayMs: Long = 60000
) : LogRecordExporter {

    private val TAG = "RetryableExporter"

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
                    // Success - we're done
                    Log.d(TAG, "Export succeeded on attempt ${attempt + 1}")
                    result.succeed()
                } else {
                    // Failed - retry if we have attempts left
                    if (attempt < maxRetries) {
                        val delayMs = calculateBackoff(attempt)
                        Log.w(TAG, "Export failed on attempt ${attempt + 1}, retrying in ${delayMs}ms...")

                        // Schedule retry
                        Thread {
                            Thread.sleep(delayMs)
                            val retryResult = exportWithRetry(logs, attempt + 1)
                            retryResult.whenComplete {
                                if (retryResult.isSuccess) {
                                    result.succeed()
                                } else {
                                    result.fail()
                                }
                            }
                        }.start()
                    } else {
                        // Out of retries
                        Log.e(TAG, "Export failed after ${maxRetries + 1} attempts")
                        result.fail()
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Exception during export attempt ${attempt + 1}", e)

            if (attempt < maxRetries) {
                val delayMs = calculateBackoff(attempt)
                Log.w(TAG, "Retrying in ${delayMs}ms...")

                Thread {
                    Thread.sleep(delayMs)
                    val retryResult = exportWithRetry(logs, attempt + 1)
                    retryResult.whenComplete {
                        if (retryResult.isSuccess) {
                            result.succeed()
                        } else {
                            result.fail()
                        }
                    }
                }.start()
            } else {
                result.fail()
            }
        }

        return result
    }

    /**
     * Calculates exponential backoff delay.
     *
     * Formula: min(initialDelay * 2^attempt, maxDelay)
     *
     * Examples (initialDelay=1s, maxDelay=60s):
     * - Attempt 0: 1s
     * - Attempt 1: 2s
     * - Attempt 2: 4s
     * - Attempt 3: 8s
     * - Attempt 4+: 60s (capped)
     */
    private fun calculateBackoff(attempt: Int): Long {
        val exponentialDelay = initialDelayMs * (2.0.pow(attempt.toDouble())).toLong()
        return min(exponentialDelay, maxDelayMs)
    }

    override fun flush(): CompletableResultCode {
        return delegate.flush()
    }

    override fun shutdown(): CompletableResultCode {
        return delegate.shutdown()
    }

    /**
     * Gets the underlying delegate exporter.
     */
    fun getDelegate(): LogRecordExporter = delegate
}
