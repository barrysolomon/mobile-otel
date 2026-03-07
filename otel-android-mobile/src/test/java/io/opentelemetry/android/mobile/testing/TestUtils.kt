/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.testing

import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.logs.ReadWriteLogRecord
import io.opentelemetry.sdk.logs.data.Body
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.resources.Resource

/**
 * Utility functions for creating test data.
 */
object TestUtils {

    /**
     * Creates a test LogRecordData with specified properties.
     *
     * @param body Log record body text
     * @param attributes Map of attributes to add
     * @param timestamp Timestamp in epoch milliseconds
     * @param severity Log severity level
     * @return TestLogRecordData implementing LogRecordData for testing
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
            bodyText = body,
            attrs = attrs.build(),
            timestampNs = timestamp * 1_000_000,
            sev = severity
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

    /**
     * Wraps a LogRecordData in a mock ReadWriteLogRecord for use with onEmit().
     *
     * MobileLogRecordProcessor.onEmit() expects ReadWriteLogRecord (the mutable form),
     * not LogRecordData (the immutable data form). This helper bridges the gap.
     */
    fun asReadWriteLogRecord(data: LogRecordData): ReadWriteLogRecord {
        val mock = mockk<ReadWriteLogRecord>(relaxed = true)
        every { mock.toLogRecordData() } returns data
        return mock
    }

    // ── Journey-specific event factories ─────────────────────────────────────

    /** Simulates a screen navigation breadcrumb (body = "screen.navigation"). */
    fun createNavigationEvent(
        screen: String,
        previousScreen: String = "unknown",
        timestamp: Long = System.currentTimeMillis()
    ) = createTestLogRecord(
        body = "screen.navigation",
        attributes = mapOf(
            "screen.name"          to screen,
            "screen.previous"      to previousScreen,
            "navigation.type"      to "bottom_nav"
        ),
        timestamp = timestamp,
        severity = Severity.INFO
    )

    /** Simulates a user tap/interaction breadcrumb (body = "user.action"). */
    fun createBreadcrumb(
        action: String,
        screen: String,
        timestamp: Long = System.currentTimeMillis()
    ) = createTestLogRecord(
        body = "user.action",
        attributes = mapOf(
            "action.name"  to action,
            "screen.name"  to screen
        ),
        timestamp = timestamp,
        severity = Severity.INFO
    )

    /** Simulates a business transaction event (body = "user.transaction"). */
    fun createTransactionEvent(
        index: Int,
        type: String = "appointment_view",
        timestamp: Long = System.currentTimeMillis()
    ) = createTestLogRecord(
        body = "user.transaction",
        attributes = mapOf(
            "transaction.index"  to index,
            "transaction.type"   to type,
            "transaction.result" to "success"
        ),
        timestamp = timestamp,
        severity = Severity.INFO
    )

    /** Simulates an outbound API request event (body = "api.request" or "http.error"). */
    fun createApiRequestEvent(
        index: Int,
        endpoint: String = "/appointments",
        statusCode: Int = 200,
        durationMs: Int = 85,
        timestamp: Long = System.currentTimeMillis()
    ): TestLogRecordData {
        val isError = statusCode >= 400
        return createTestLogRecord(
            body = if (isError) "http.error" else "api.request",
            attributes = mapOf(
                "request.index"       to index,
                "request.endpoint"    to endpoint,
                "request.status_code" to statusCode,
                "request.duration_ms" to durationMs
            ),
            timestamp = timestamp,
            severity = if (isError) Severity.ERROR else Severity.INFO
        )
    }

    /**
     * Emits a sequence of events on [processor] via onEmit().
     * Returns the list of ReadWriteLogRecord wrappers emitted.
     */
    fun emitAll(
        processor: io.opentelemetry.sdk.logs.LogRecordProcessor,
        records: List<LogRecordData>
    ) {
        records.forEach { record ->
            processor.onEmit(
                io.opentelemetry.context.Context.root(),
                asReadWriteLogRecord(record)
            )
        }
    }
}

/**
 * Simplified LogRecordData implementation for testing.
 *
 * Implements the LogRecordData interface so instances can be passed directly
 * to APIs expecting LogRecordData (e.g., DiskLogBuffer.persistEvents).
 *
 * Note: bodyText is used instead of body to avoid JVM naming conflict with
 * the interface method getBody() which returns Body (not String).
 */
class TestLogRecordData(
    val bodyText: String,
    val attrs: Attributes,
    val timestampNs: Long,
    val sev: Severity
) : LogRecordData {

    override fun getResource(): Resource = Resource.empty()

    override fun getInstrumentationScopeInfo(): InstrumentationScopeInfo =
        InstrumentationScopeInfo.empty()

    override fun getTimestampEpochNanos(): Long = timestampNs

    override fun getObservedTimestampEpochNanos(): Long = timestampNs

    override fun getSpanContext(): SpanContext = SpanContext.getInvalid()

    override fun getSeverity(): Severity = sev

    override fun getSeverityText(): String = sev.name

    override fun getBody(): Body = object : Body {
        override fun asString(): String = bodyText
        override fun getType(): Body.Type = Body.Type.STRING
    }

    override fun getAttributes(): Attributes = attrs

    override fun getTotalAttributeCount(): Int = attrs.size()
}
