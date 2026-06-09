/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.export

import android.util.Log
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter

/**
 * Wrapper that logs detailed export information for debugging.
 */
class LoggingHttpExporter(
    private val delegate: LogRecordExporter,
    private val endpoint: String,
    // Gate the chatty per-export endpoint/count logging. Off by default so release
    // builds aren't noisy; enable for local debugging. Export FAILURES are always
    // logged regardless of this flag.
    private val debugLogging: Boolean = false
) : LogRecordExporter {

    companion object {
        private const val TAG = "LoggingHttpExporter"

        // Callback for export results
        var onExportResult: ((success: Boolean, message: String) -> Unit)? = null
    }

    override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
        if (debugLogging) {
            Log.d(TAG, "=== EXPORT ATTEMPT ===")
            Log.d(TAG, "Endpoint: $endpoint")
            Log.d(TAG, "Log count: ${logs.size}")
            Log.d(TAG, "Expected URL: $endpoint/v1/logs")
        }

        val result = delegate.export(logs)

        // Log result asynchronously
        Thread {
            val finalResult = result.join(30, java.util.concurrent.TimeUnit.SECONDS)
            if (finalResult.isSuccess) {
                val msg = "✅ Export successful to $endpoint/v1/logs (${logs.size} logs)"
                if (debugLogging) Log.i(TAG, msg)
                onExportResult?.invoke(true, msg)
            } else {
                val msg = "❌ Export failed to $endpoint/v1/logs (${logs.size} logs)"
                Log.e(TAG, msg)
                onExportResult?.invoke(false, msg)
            }
        }.start()

        return result
    }

    override fun flush(): CompletableResultCode {
        return delegate.flush()
    }

    override fun shutdown(): CompletableResultCode {
        return delegate.shutdown()
    }
}
