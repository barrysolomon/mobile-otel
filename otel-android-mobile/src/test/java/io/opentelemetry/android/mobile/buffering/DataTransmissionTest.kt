/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.logs.data.Body
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.resources.Resource
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Validates that telemetry data is transmitted as standard OTLP — not encrypted at the
 * application layer. This is critical because backends like Dash0 receive standard OTLP
 * and cannot decrypt SDK-level encryption.
 *
 * The SDK's security model:
 * - **At rest** (on device): EncryptedSharedPreferences for session/user data;
 *   Room DB for buffered logs (plaintext, non-PII telemetry events)
 * - **In transit**: TLS (HTTPS) at the transport layer; OTLP payload is standard protobuf/JSON
 * - **No application-layer encryption** of the OTLP payload itself
 *
 * These tests verify that:
 * 1. LogRecordData passed to the exporter is standard OTel format (readable attributes, body)
 * 2. RetryableExporter passes through data unmodified to the delegate exporter
 * 3. DiskLogBuffer round-trips data without corruption or encryption
 * 4. Attribute values are readable strings/numbers (not ciphertext)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DataTransmissionTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        DiskLogBuffer.resetForTesting()
    }

    @Test
    fun `RetryableExporter passes LogRecordData to delegate unmodified`() {
        val capturedLogs = mutableListOf<LogRecordData>()

        val captureExporter = object : LogRecordExporter {
            override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
                capturedLogs.addAll(logs)
                return CompletableResultCode.ofSuccess()
            }
            override fun flush() = CompletableResultCode.ofSuccess()
            override fun shutdown() = CompletableResultCode.ofSuccess()
        }

        val retryableExporter = RetryableExporter(captureExporter)
        val testLog = createTestLogRecord(
            eventName = "ui.tap",
            attributes = Attributes.of(
                AttributeKey.stringKey("mobile.session.id"), "abc-123",
                AttributeKey.stringKey("mobile.screen.name"), "HomeScreen",
                AttributeKey.longKey("ui.time_on_screen_ms"), 5000L
            )
        )

        retryableExporter.export(listOf(testLog))

        // Wait for async completion
        Thread.sleep(100)

        assertEquals(1, capturedLogs.size, "Exporter should receive exactly 1 log record")

        val received = capturedLogs[0]

        // Verify body is readable plain text (not encrypted)
        assertEquals("ui.tap", received.body.asString(),
            "Body should be plain text event name, not ciphertext")

        // Verify attributes are readable (not encrypted)
        val sessionId = received.attributes.get(AttributeKey.stringKey("mobile.session.id"))
        assertEquals("abc-123", sessionId,
            "Attribute values should be plain text, not encrypted")

        val screenName = received.attributes.get(AttributeKey.stringKey("mobile.screen.name"))
        assertEquals("HomeScreen", screenName,
            "String attributes should pass through as plain text")

        val timeOnScreen = received.attributes.get(AttributeKey.longKey("ui.time_on_screen_ms"))
        assertEquals(5000L, timeOnScreen,
            "Numeric attributes should pass through as plain numbers")

        // Verify severity is standard OTel
        assertEquals(Severity.INFO, received.severity,
            "Severity should be standard OTel Severity enum")

        retryableExporter.shutdown()
    }

    @Test
    fun `DiskLogBuffer round-trips data without encryption`() {
        val buffer = DiskLogBuffer.getInstance(context, maxSizeMb = 10, ttlHours = 24)

        val originalLog = createTestLogRecord(
            eventName = "app.crash",
            attributes = Attributes.of(
                AttributeKey.stringKey("exception.type"), "NullPointerException",
                AttributeKey.stringKey("exception.message"), "Attempt to invoke on null reference",
                AttributeKey.stringKey("mobile.session.id"), "session-xyz-789",
                AttributeKey.longKey("http.response.status_code"), 500L,
                AttributeKey.booleanKey("mobile.start.slow"), true
            )
        )

        // Persist to disk
        buffer.persistEvents(listOf(originalLog))

        // Wait for async persistence
        Thread.sleep(500)

        // Read back from disk
        val events = kotlinx.coroutines.runBlocking {
            buffer.getAllEvents()
        }

        assertEquals(1, events.size, "Should retrieve 1 event from disk")

        val retrieved = events[0]

        // Verify body is plain text after round-trip
        assertEquals("app.crash", retrieved.body.asString(),
            "Body should survive disk round-trip as plain text")

        // Verify attributes are readable after deserialization
        val exceptionType = retrieved.attributes.get(AttributeKey.stringKey("exception.type"))
        assertEquals("NullPointerException", exceptionType,
            "String attributes should be plain text after disk round-trip")

        val exceptionMessage = retrieved.attributes.get(AttributeKey.stringKey("exception.message"))
        assertEquals("Attempt to invoke on null reference", exceptionMessage,
            "Exception messages should be readable, not encrypted")

        val sessionId = retrieved.attributes.get(AttributeKey.stringKey("mobile.session.id"))
        assertEquals("session-xyz-789", sessionId,
            "Session ID should be plain text in exported data")

        val statusCode = retrieved.attributes.get(AttributeKey.longKey("http.response.status_code"))
        assertEquals(500L, statusCode,
            "Numeric attributes should survive round-trip")

        val startSlow = retrieved.attributes.get(AttributeKey.booleanKey("mobile.start.slow"))
        assertEquals(true, startSlow,
            "Boolean attributes should survive round-trip")

        // Verify severity survived
        assertEquals(Severity.ERROR, retrieved.severity,
            "Severity should survive disk round-trip")
    }

    @Test
    fun `DiskLogBuffer preserves span context through round-trip`() {
        val buffer = DiskLogBuffer.getInstance(context, maxSizeMb = 10, ttlHours = 24)

        val traceId = "0123456789abcdef0123456789abcdef"
        val spanId = "fedcba9876543210"

        val originalLog = createTestLogRecord(
            eventName = "http.error",
            traceId = traceId,
            spanId = spanId,
            attributes = Attributes.of(
                AttributeKey.stringKey("url.full"), "https://api.example.com/users"
            )
        )

        buffer.persistEvents(listOf(originalLog))
        Thread.sleep(500)

        val events = kotlinx.coroutines.runBlocking {
            buffer.getAllEvents()
        }

        assertEquals(1, events.size)
        val retrieved = events[0]

        // Verify span context is preserved (important for distributed tracing)
        assertEquals(traceId, retrieved.spanContext.traceId,
            "TraceId should be preserved through disk round-trip")
        assertEquals(spanId, retrieved.spanContext.spanId,
            "SpanId should be preserved through disk round-trip")

        // Verify URL is plain text
        val url = retrieved.attributes.get(AttributeKey.stringKey("url.full"))
        assertEquals("https://api.example.com/users", url,
            "URL should be plain text in exported data (not encrypted)")
    }

    @Test
    fun `exported data uses OTel standard attribute types`() {
        val capturedLogs = mutableListOf<LogRecordData>()
        val captureExporter = object : LogRecordExporter {
            override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
                capturedLogs.addAll(logs)
                return CompletableResultCode.ofSuccess()
            }
            override fun flush() = CompletableResultCode.ofSuccess()
            override fun shutdown() = CompletableResultCode.ofSuccess()
        }

        val exporter = RetryableExporter(captureExporter)
        val log = createTestLogRecord(
            eventName = "device.metrics",
            attributes = Attributes.builder()
                .put(AttributeKey.stringKey("service.name"), "test-app")
                .put(AttributeKey.stringKey("service.version"), "1.0.0")
                .put(AttributeKey.longKey("process.runtime.jvm.memory.heap"), 50_000_000L)
                .put(AttributeKey.doubleKey("system.cpu.utilization"), 0.45)
                .put(AttributeKey.booleanKey("mobile.battery.charging"), false)
                .build()
        )

        exporter.export(listOf(log))
        Thread.sleep(100)

        assertEquals(1, capturedLogs.size)
        val exported = capturedLogs[0]

        // These are standard OTel attribute types that any OTLP-compatible backend
        // (Dash0, Jaeger, Grafana Tempo, etc.) can decode without special handling
        assertNotNull(exported.attributes.get(AttributeKey.stringKey("service.name")))
        assertNotNull(exported.attributes.get(AttributeKey.longKey("process.runtime.jvm.memory.heap")))
        assertNotNull(exported.attributes.get(AttributeKey.doubleKey("system.cpu.utilization")))
        assertNotNull(exported.attributes.get(AttributeKey.booleanKey("mobile.battery.charging")))

        exporter.shutdown()
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    private fun createTestLogRecord(
        eventName: String,
        attributes: Attributes = Attributes.empty(),
        traceId: String? = null,
        spanId: String? = null
    ): LogRecordData {
        val spanCtx = if (traceId != null && spanId != null) {
            SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault())
        } else {
            SpanContext.getInvalid()
        }

        return object : LogRecordData {
            override fun getResource() = Resource.builder()
                .put("service.name", "test-app")
                .put("service.version", "1.0.0")
                .build()
            override fun getInstrumentationScopeInfo() = InstrumentationScopeInfo.builder("test").build()
            override fun getTimestampEpochNanos() = System.currentTimeMillis() * 1_000_000
            override fun getObservedTimestampEpochNanos() = System.currentTimeMillis() * 1_000_000
            override fun getSpanContext() = spanCtx
            override fun getSeverity() = if (eventName.contains("crash") || eventName.contains("error"))
                Severity.ERROR else Severity.INFO
            override fun getSeverityText() = severity?.name
            override fun getBody() = Body.string(eventName)
            override fun getAttributes() = attributes
            override fun getTotalAttributeCount() = attributes.size()
        }
    }
}
