/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.testing

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.sdk.logs.data.LogRecordData

/**
 * Utility functions for creating test data.
 */
object TestUtils {

    /**
     * Creates a test LogRecordData with specified properties.
     *
     * Note: This is a simplified version. In production tests, you would
     * use the actual SDK to create LogRecordData instances.
     *
     * @param body Log record body text
     * @param attributes Map of attributes to add
     * @param timestamp Timestamp in epoch milliseconds
     * @param severity Log severity level
     * @return LogRecordData for testing
     */
    fun createTestLogRecord(
        body: String,
        attributes: Map<String, Any> = emptyMap(),
        timestamp: Long = System.currentTimeMillis(),
        severity: Severity = Severity.INFO
    ): TestLogRecordData {
        val attrs = Attributes.builder()

        attributes.forEach { (key, value) ->
            when (value) {
                is String -> attrs.put(AttributeKey.stringKey(key), value)
                is Long -> attrs.put(AttributeKey.longKey(key), value)
                is Int -> attrs.put(AttributeKey.longKey(key), value.toLong())
                is Double -> attrs.put(AttributeKey.doubleKey(key), value)
                is Float -> attrs.put(AttributeKey.doubleKey(key), value.toDouble())
                is Boolean -> attrs.put(AttributeKey.booleanKey(key), value)
            }
        }

        return TestLogRecordData(
            body = body,
            attributes = attrs.build(),
            timestampEpochNanos = timestamp * 1_000_000,
            severity = severity
        )
    }

    /**
     * Creates multiple test log records with sequential bodies.
     *
     * @param prefix Prefix for log body (e.g., "event" creates "event.0", "event.1", etc.)
     * @param count Number of logs to create
     * @param baseTimestamp Starting timestamp
     * @return List of test log records
     */
    fun createTestLogRecords(
        prefix: String,
        count: Int,
        baseTimestamp: Long = System.currentTimeMillis()
    ): List<TestLogRecordData> {
        return (0 until count).map { i ->
            createTestLogRecord(
                body = "$prefix.$i",
                timestamp = baseTimestamp + (i * 1000), // 1 second apart
                attributes = mapOf("index" to i)
            )
        }
    }

    /**
     * Creates a test log record for a UI freeze scenario.
     */
    fun createUIFreezeLog(durationMs: Long): TestLogRecordData {
        return createTestLogRecord(
            body = "ui.freeze",
            attributes = mapOf(
                "duration_ms" to durationMs,
                "screen" to "MainActivity",
                "thread" to "main"
            ),
            severity = Severity.WARN
        )
    }

    /**
     * Creates a test log record for a crash scenario.
     */
    fun createCrashLog(crashType: String = "uncaught_exception"): TestLogRecordData {
        return createTestLogRecord(
            body = "app.crash",
            attributes = mapOf(
                "crash_type" to crashType,
                "stack_trace" to "java.lang.NullPointerException at MainActivity.kt:42"
            ),
            severity = Severity.ERROR
        )
    }

    /**
     * Creates a test log record for an HTTP error scenario.
     */
    fun createHttpErrorLog(statusCode: Int, route: String): TestLogRecordData {
        return createTestLogRecord(
            body = "http.error",
            attributes = mapOf(
                "http.status_code" to statusCode,
                "http.route" to route,
                "http.method" to "POST",
                "error.message" to "Internal Server Error"
            ),
            severity = Severity.ERROR
        )
    }

    /**
     * Creates a test log record with a specific timestamp.
     */
    fun createTestLogRecordWithTimestamp(body: String, timestampMs: Long): TestLogRecordData {
        return createTestLogRecord(
            body = body,
            timestamp = timestampMs,
            attributes = mapOf("timestamp_ms" to timestampMs)
        )
    }
}

/**
 * Simplified LogRecordData implementation for testing.
 *
 * In production, you would capture actual LogRecordData instances
 * from the SDK during tests.
 */
data class TestLogRecordData(
    val body: String,
    val attributes: Attributes,
    val timestampEpochNanos: Long,
    val severity: Severity
) {
    fun getBody(): String = body
    fun getAttributes(): Attributes = attributes
    fun getTimestampEpochNanos(): Long = timestampEpochNanos
    fun getSeverity(): Severity = severity
}
