/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
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
 * Integration-level tests that validate every [NetworkConfig] flag and threshold
 * actually affects what spans [OTelNetworkInterceptor] creates and what attributes
 * those spans carry.
 *
 * These complement [NetworkConfigTest] (which only tests the config logic methods)
 * by verifying the interceptor USES the config correctly when intercepting real
 * OkHttp requests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OTelNetworkInterceptorConfigTest {

    private lateinit var context: Context
    private lateinit var spanExporter: InMemorySpanExporter
    private lateinit var tracerProvider: SdkTracerProvider

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        spanExporter = InMemorySpanExporter.create()
        tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
            .build()
    }

    @After
    fun teardown() {
        tracerProvider.close()
        spanExporter.reset()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildInterceptor(config: NetworkConfig): OTelNetworkInterceptor {
        val tracer = tracerProvider.get("test")
        val propagator = W3CTraceContextPropagator.getInstance()
        return OTelNetworkInterceptor.create(context, config, tracer, propagator)
    }

    /**
     * Creates a mock [Interceptor.Chain] for a GET request to the given URL that
     * returns the given status code.
     */
    private fun mockChain(
        url: String,
        statusCode: Int = 200,
        responseHeaders: Map<String, String> = emptyMap(),
        requestHeaders: Map<String, String> = emptyMap()
    ): Interceptor.Chain {
        val requestBuilder = Request.Builder().url(url).get()
        requestHeaders.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
        val request = requestBuilder.build()

        val responseBuilder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(statusCode)
            .message(if (statusCode in 200..299) "OK" else "Error")
            .body("{}".toResponseBody("application/json".toMediaType()))
        responseHeaders.forEach { (k, v) -> responseBuilder.addHeader(k, v) }
        val response = responseBuilder.build()

        return mockk<Interceptor.Chain>(relaxed = true).also {
            every { it.request() } returns request
            every { it.proceed(any()) } returns response
        }
    }

    // ── enabled = false → no spans produced ──────────────────────────────────

    @Test
    fun `enabled false - interceptor passes request through without creating a span`() {
        val interceptor = buildInterceptor(NetworkConfig(enabled = false))
        interceptor.intercept(mockChain("https://api.example.com/users"))

        assertEquals("No span should be created when networking is disabled",
            0, spanExporter.finishedSpanItems.size)
    }

    @Test
    fun `enabled true - interceptor creates a span for each request`() {
        val interceptor = buildInterceptor(NetworkConfig(enabled = true))
        interceptor.intercept(mockChain("https://api.example.com/users"))

        assertEquals("A span should be created for every instrumented request",
            1, spanExporter.finishedSpanItems.size)
    }

    // ── propagateTraceContext ─────────────────────────────────────────────────

    @Test
    fun `propagateTraceContext true - traceparent header injected into outgoing request`() {
        var capturedRequest: Request? = null
        val interceptor = buildInterceptor(NetworkConfig(propagateTraceContext = true))

        val chain = mockChain("https://api.example.com/data")
        every { chain.proceed(any()) } answers {
            capturedRequest = firstArg<Request>()
            Response.Builder()
                .request(firstArg())
                .protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        interceptor.intercept(chain)

        assertNotNull("traceparent header must be injected",
            capturedRequest?.header("traceparent"))
    }

    @Test
    fun `propagateTraceContext false - traceparent header NOT injected`() {
        var capturedRequest: Request? = null
        val interceptor = buildInterceptor(NetworkConfig(propagateTraceContext = false))

        val chain = mockChain("https://api.example.com/data")
        every { chain.proceed(any()) } answers {
            capturedRequest = firstArg<Request>()
            Response.Builder()
                .request(firstArg())
                .protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        interceptor.intercept(chain)

        assertNull("traceparent must not be injected when propagation is disabled",
            capturedRequest?.header("traceparent"))
    }

    // ── errorStatusThreshold ──────────────────────────────────────────────────

    @Test
    fun `errorStatusThreshold 400 - 400 response marks span as ERROR`() {
        val interceptor = buildInterceptor(NetworkConfig(errorStatusThreshold = 400))
        interceptor.intercept(mockChain("https://api.example.com/item", statusCode = 400))

        val span = spanExporter.finishedSpanItems.single()
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, span.status.statusCode)
    }

    @Test
    fun `errorStatusThreshold 500 - 400 response marks span as OK`() {
        val interceptor = buildInterceptor(NetworkConfig(errorStatusThreshold = 500))
        interceptor.intercept(mockChain("https://api.example.com/item", statusCode = 404))

        val span = spanExporter.finishedSpanItems.single()
        assertEquals("404 should be OK when threshold is 500",
            io.opentelemetry.api.trace.StatusCode.OK, span.status.statusCode)
    }

    @Test
    fun `errorStatusThreshold 500 - 500 response marks span as ERROR`() {
        val interceptor = buildInterceptor(NetworkConfig(errorStatusThreshold = 500))
        interceptor.intercept(mockChain("https://api.example.com/item", statusCode = 500))

        val span = spanExporter.finishedSpanItems.single()
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, span.status.statusCode)
    }

    @Test
    fun `errorStatusThreshold 400 - 200 response marks span as OK`() {
        val interceptor = buildInterceptor(NetworkConfig(errorStatusThreshold = 400))
        interceptor.intercept(mockChain("https://api.example.com/item", statusCode = 200))

        val span = spanExporter.finishedSpanItems.single()
        assertEquals(io.opentelemetry.api.trace.StatusCode.OK, span.status.statusCode)
    }

    // ── captureRequestHeaders ─────────────────────────────────────────────────

    @Test
    fun `captureRequestHeaders - listed header value appears in span attributes`() {
        val interceptor = buildInterceptor(NetworkConfig(
            captureRequestHeaders = listOf("Content-Type", "X-App-Version"),
            scrubHeaders = false
        ))
        interceptor.intercept(mockChain(
            "https://api.example.com/data",
            requestHeaders = mapOf("Content-Type" to "application/json", "X-App-Version" to "1.2.3")
        ))

        val span = spanExporter.finishedSpanItems.single()
        assertEquals("application/json",
            span.attributes.get(AttributeKey.stringKey("http.request.header.Content-Type")))
        assertEquals("1.2.3",
            span.attributes.get(AttributeKey.stringKey("http.request.header.X-App-Version")))
    }

    @Test
    fun `captureRequestHeaders - Authorization header NOT captured even when listed`() {
        // scrubHeaders=true means sensitive headers are always blocked
        val interceptor = buildInterceptor(NetworkConfig(
            captureRequestHeaders = listOf("Authorization"),
            scrubHeaders = true
        ))
        interceptor.intercept(mockChain(
            "https://api.example.com/data",
            requestHeaders = mapOf("Authorization" to "Bearer secret123")
        ))

        val span = spanExporter.finishedSpanItems.single()
        assertNull("Authorization must not be captured when scrubHeaders=true",
            span.attributes.get(AttributeKey.stringKey("http.request.header.Authorization")))
    }

    @Test
    fun `captureRequestHeaders empty - no request headers appear in span`() {
        val interceptor = buildInterceptor(NetworkConfig(
            captureRequestHeaders = emptyList()
        ))
        interceptor.intercept(mockChain(
            "https://api.example.com/data",
            requestHeaders = mapOf("Content-Type" to "application/json")
        ))

        val span = spanExporter.finishedSpanItems.single()
        assertNull(span.attributes.get(AttributeKey.stringKey("http.request.header.Content-Type")))
    }

    // ── captureResponseHeaders ────────────────────────────────────────────────

    @Test
    fun `captureResponseHeaders - listed header value appears in span attributes`() {
        val interceptor = buildInterceptor(NetworkConfig(
            captureResponseHeaders = listOf("Content-Type"),
            scrubHeaders = false
        ))
        interceptor.intercept(mockChain(
            "https://api.example.com/data",
            responseHeaders = mapOf("Content-Type" to "application/json")
        ))

        val span = spanExporter.finishedSpanItems.single()
        assertEquals("application/json",
            span.attributes.get(AttributeKey.stringKey("http.response.header.Content-Type")))
    }

    @Test
    fun `captureResponseHeaders empty - no response headers appear in span`() {
        val interceptor = buildInterceptor(NetworkConfig(
            captureResponseHeaders = emptyList()
        ))
        interceptor.intercept(mockChain(
            "https://api.example.com/data",
            responseHeaders = mapOf("Content-Type" to "application/json")
        ))

        val span = spanExporter.finishedSpanItems.single()
        assertNull(span.attributes.get(AttributeKey.stringKey("http.response.header.Content-Type")))
    }

    // ── bucketSizes ───────────────────────────────────────────────────────────

    @Test
    fun `bucketSizes true - response size bucket attribute is set`() {
        // Create interceptor with known large response so body contentLength is > 0
        val config = NetworkConfig(bucketSizes = true)
        val interceptor = buildInterceptor(config)

        // Use a chain where the response body has a known size
        val request = Request.Builder().url("https://api.example.com/data").get().build()
        val responseBody = "x".repeat(5 * 1024).toResponseBody("text/plain".toMediaType())
        val response = Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(responseBody).build()
        val chain = mockk<Interceptor.Chain>(relaxed = true)
        every { chain.request() } returns request
        every { chain.proceed(any()) } returns response

        interceptor.intercept(chain)

        val span = spanExporter.finishedSpanItems.single()
        // Response body size is 5 * 1024 = 5120 bytes → "1-10KB" bucket
        assertEquals("1-10KB",
            span.attributes.get(AttributeKey.stringKey("http.response.size_bucket")))
    }

    @Test
    fun `bucketSizes false - no size bucket attributes in span`() {
        val interceptor = buildInterceptor(NetworkConfig(bucketSizes = false))
        interceptor.intercept(mockChain("https://api.example.com/data"))

        val span = spanExporter.finishedSpanItems.single()
        assertNull(span.attributes.get(AttributeKey.stringKey("http.response.size_bucket")))
        assertNull(span.attributes.get(AttributeKey.stringKey("http.request.size_bucket")))
    }

    // ── detectNetworkType ─────────────────────────────────────────────────────

    @Test
    fun `detectNetworkType false - no network type attribute in span`() {
        val interceptor = buildInterceptor(NetworkConfig(detectNetworkType = false))
        interceptor.intercept(mockChain("https://api.example.com/data"))

        val span = spanExporter.finishedSpanItems.single()
        assertNull(span.attributes.get(AttributeKey.stringKey("network.type")))
    }

    // ── minDurationMs ─────────────────────────────────────────────────────────

    @Test
    fun `minDurationMs 1000 - fast mock response span ends without status code attribute`() {
        // Mock responses return in 0ms so duration < 1000ms.
        // The interceptor should end the span early without adding response attributes.
        val interceptor = buildInterceptor(NetworkConfig(minDurationMs = 1000))
        interceptor.intercept(mockChain("https://api.example.com/data", statusCode = 200))

        val span = spanExporter.finishedSpanItems.single()
        // Status code is not set because the span ended before response enrichment
        assertNull("Status code must be absent for sub-threshold requests",
            span.attributes.get(io.opentelemetry.semconv.HttpAttributes.HTTP_RESPONSE_STATUS_CODE))
    }

    @Test
    fun `minDurationMs 0 - all requests get full response attributes`() {
        // Default minDurationMs=0 means all requests are enriched
        val interceptor = buildInterceptor(NetworkConfig(minDurationMs = 0))
        interceptor.intercept(mockChain("https://api.example.com/data", statusCode = 201))

        val span = spanExporter.finishedSpanItems.single()
        assertEquals(201L,
            span.attributes.get(io.opentelemetry.semconv.HttpAttributes.HTTP_RESPONSE_STATUS_CODE))
    }

    // ── allowedHosts / blockedHosts ───────────────────────────────────────────

    @Test
    fun `allowedHosts restricts instrumentation to listed hosts only`() {
        val interceptor = buildInterceptor(NetworkConfig(
            allowedHosts = listOf("api.myapp.com")
        ))

        interceptor.intercept(mockChain("https://api.myapp.com/users"))
        interceptor.intercept(mockChain("https://analytics.thirdparty.com/track"))

        assertEquals("Only api.myapp.com should produce a span", 1, spanExporter.finishedSpanItems.size)
    }

    @Test
    fun `blockedHosts prevents instrumentation for listed hosts`() {
        val interceptor = buildInterceptor(NetworkConfig(
            blockedHosts = listOf("analytics.thirdparty.com")
        ))

        interceptor.intercept(mockChain("https://api.myapp.com/users"))
        interceptor.intercept(mockChain("https://analytics.thirdparty.com/track"))

        assertEquals("analytics host must be blocked", 1, spanExporter.finishedSpanItems.size)
        assertEquals("api.myapp.com",
            spanExporter.finishedSpanItems[0].attributes
                .get(io.opentelemetry.semconv.ServerAttributes.SERVER_ADDRESS))
    }

    @Test
    fun `blockedHosts also blocks subdomains`() {
        val interceptor = buildInterceptor(NetworkConfig(
            blockedHosts = listOf("thirdparty.com")
        ))

        interceptor.intercept(mockChain("https://sub.thirdparty.com/data"))

        assertEquals("Subdomain of blocked host must also be blocked",
            0, spanExporter.finishedSpanItems.size)
    }

    // ── scrubUrls ─────────────────────────────────────────────────────────────

    @Test
    fun `scrubUrls false - full URL including query parameters is recorded`() {
        val interceptor = buildInterceptor(NetworkConfig(scrubUrls = false))
        interceptor.intercept(mockChain("https://api.example.com/search?q=hello&token=secret"))

        val span = spanExporter.finishedSpanItems.single()
        val url = span.attributes.get(io.opentelemetry.semconv.UrlAttributes.URL_FULL) ?: ""
        assertTrue("Full URL with query params should be present when scrubbing is disabled",
            url.contains("q=hello"))
    }

    @Test
    fun `scrubUrls true - query parameters are removed from recorded URL`() {
        val interceptor = buildInterceptor(NetworkConfig(scrubUrls = true))
        interceptor.intercept(mockChain("https://api.example.com/search?q=hello&token=secret"))

        val span = spanExporter.finishedSpanItems.single()
        val url = span.attributes.get(io.opentelemetry.semconv.UrlAttributes.URL_FULL) ?: ""
        assertFalse("Query params must be scrubbed from URL when scrubUrls=true",
            url.contains("token=secret"))
    }

    // ── Span always has required OTel semantic attributes ─────────────────────

    @Test
    fun `every request span has HTTP method and server address attributes`() {
        val interceptor = buildInterceptor(NetworkConfig())
        interceptor.intercept(mockChain("https://api.example.com/users"))

        val span = spanExporter.finishedSpanItems.single()
        assertEquals("GET",
            span.attributes.get(io.opentelemetry.semconv.HttpAttributes.HTTP_REQUEST_METHOD))
        assertEquals("api.example.com",
            span.attributes.get(io.opentelemetry.semconv.ServerAttributes.SERVER_ADDRESS))
    }

    @Test
    fun `span name follows HTTP method + path convention`() {
        val interceptor = buildInterceptor(NetworkConfig())
        interceptor.intercept(mockChain("https://api.example.com/users/42"))

        val span = spanExporter.finishedSpanItems.single()
        assertEquals("GET /users/42", span.name)
    }
}
