/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the HYBRID-mode http_match flush bug reported 2026-05-12:
 *
 * > "hybrid failed to send on http error"
 *
 * Root cause: [OTelNetworkInterceptor] emits a log record with
 * `body = "http.error"` but the `event.name` attribute is unset. The DSL
 * v2 `http_match` matcher in [PolicyEvaluator] keys on the `event.name`
 * attribute (not the body), so the matcher never fires and CONDITIONAL +
 * HYBRID modes silently drop the event window.
 *
 * Same shape as the documented lifecycle event.name gap
 * (feedback_lifecycle_event_name_missing).
 *
 * This test locks the contract: every HTTP error response must produce a
 * log record carrying `event.name = "http.error"` as a top-level
 * attribute, regardless of body.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HttpErrorEventNameTest {

    private lateinit var context: Context
    private lateinit var spanExporter: InMemorySpanExporter
    private lateinit var tracerProvider: SdkTracerProvider
    private lateinit var logExporter: InMemoryLogRecordExporter
    private lateinit var loggerProvider: SdkLoggerProvider

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        spanExporter = InMemorySpanExporter.create()
        tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
            .build()
        logExporter = InMemoryLogRecordExporter.create()
        loggerProvider = SdkLoggerProvider.builder()
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(logExporter))
            .build()
    }

    @After
    fun teardown() {
        tracerProvider.close()
        loggerProvider.close()
        spanExporter.reset()
        logExporter.reset()
    }

    private fun buildInterceptor(): OTelNetworkInterceptor {
        val tracer = tracerProvider.get("test")
        val logger = loggerProvider.get("test")
        val propagator = W3CTraceContextPropagator.getInstance()
        return OTelNetworkInterceptor.create(context, NetworkConfig(), tracer, propagator, logger)
    }

    private fun mockChain(statusCode: Int): Interceptor.Chain {
        val request = Request.Builder().url("https://api.example.com/users").get().build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(statusCode)
            .message(if (statusCode in 200..299) "OK" else "Error")
            .body("{}".toResponseBody("application/json".toMediaType()))
            .build()
        return mockk<Interceptor.Chain>(relaxed = true).also {
            every { it.request() } returns request
            every { it.proceed(any()) } returns response
        }
    }

    @Test
    fun `4xx response emits http error log with event_name attribute`() {
        val interceptor = buildInterceptor()
        interceptor.intercept(mockChain(404))

        val logs = logExporter.finishedLogRecordItems
        assertEquals("Exactly one http.error log record per 4xx response", 1, logs.size)
        val eventName = logs[0].attributes.get(AttributeKey.stringKey("event.name"))
        assertEquals(
            "event.name attribute must be 'http.error' so the DSL http_match matcher can trigger flushWindow",
            "http.error",
            eventName
        )
    }

    @Test
    fun `5xx response emits http error log with event_name attribute`() {
        val interceptor = buildInterceptor()
        interceptor.intercept(mockChain(503))

        val logs = logExporter.finishedLogRecordItems
        assertEquals(1, logs.size)
        assertEquals("http.error", logs[0].attributes.get(AttributeKey.stringKey("event.name")))
    }

    @Test
    fun `2xx response does NOT emit an http error log`() {
        val interceptor = buildInterceptor()
        interceptor.intercept(mockChain(200))

        assertEquals(
            "Successful responses must not produce an http.error log",
            0, logExporter.finishedLogRecordItems.size
        )
    }
}
