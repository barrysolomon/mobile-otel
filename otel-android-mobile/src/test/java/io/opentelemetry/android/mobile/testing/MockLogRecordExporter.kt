/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.testing

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import java.util.Collections

/**
 * Mock LogRecordExporter for testing.
 *
 * Captures exported logs in-memory instead of sending via OTLP.
 * Useful for verifying export behavior without network dependencies.
 */
class MockLogRecordExporter : LogRecordExporter {

    /**
     * Thread-safe list of all exported logs.
     */
    val exportedLogs: MutableList<LogRecordData> = Collections.synchronizedList(mutableListOf())

    /**
     * Controls whether export should succeed or fail.
     */
    var shouldFail = false

    /**
     * Number of times export was called.
     */
    var exportCallCount = 0
        private set

    /**
     * Simulated network delay in milliseconds.
     */
    var simulatedDelayMs = 0L

    override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
        exportCallCount++

        return if (shouldFail) {
            CompletableResultCode.ofFailure()
        } else {
            if (simulatedDelayMs > 0) {
                Thread.sleep(simulatedDelayMs)
            }
            exportedLogs.addAll(logs)
            CompletableResultCode.ofSuccess()
        }
    }

    override fun flush(): CompletableResultCode {
        return CompletableResultCode.ofSuccess()
    }

    override fun shutdown(): CompletableResultCode {
        // Do not clear exportedLogs — tests must be able to verify exports after shutdown
        return CompletableResultCode.ofSuccess()
    }

    /**
     * Clears all captured logs.
     */
    fun clear() {
        exportedLogs.clear()
        exportCallCount = 0
    }

    /**
     * Gets the total number of exported logs.
     */
    fun getExportedCount(): Int = exportedLogs.size

    /**
     * Finds logs matching a predicate.
     */
    fun findLogs(predicate: (LogRecordData) -> Boolean): List<LogRecordData> {
        return exportedLogs.filter(predicate)
    }

    /**
     * Finds logs with specific body text.
     */
    fun findLogsByBody(body: String): List<LogRecordData> {
        return exportedLogs.filter { it.body.asString() == body }
    }

    /**
     * Waits for a specific number of logs to be exported.
     *
     * @param count Expected number of logs
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return true if count reached, false if timeout
     */
    fun waitForLogs(count: Int, timeoutMs: Long = 5000): Boolean {
        val startTime = System.currentTimeMillis()
        while (exportedLogs.size < count) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                return false
            }
            Thread.sleep(100)
        }
        return true
    }
}
