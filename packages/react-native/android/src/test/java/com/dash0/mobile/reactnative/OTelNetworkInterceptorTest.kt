/*
 * Unit tests for OTelNetworkInterceptor — the OkHttp interceptor that wraps the
 * host app's ENTIRE OkHttp pipeline (classic fetch, XMLHttpRequest, axios, AND
 * expo/fetch all bottom out on RN's shared OkHttp client).
 *
 * Because a throw from this interceptor would break ALL host networking, the
 * highest-value assertions here are the fault-isolation ones: a telemetry
 * failure must NEVER stop `chain.proceed` nor alter the response/exception the
 * host sees.
 *
 * Everything is exercised on a pure JVM: real OkHttp request/response objects, a
 * real OpenTelemetry SDK tracer wired to an InMemorySpanExporter so we can read
 * back the exact span the interceptor produced and the traceparent it injected,
 * and hand-rolled Interceptor.Chain test doubles. No React types, no Android
 * runtime, no emulator — the interceptor depends only on okhttp3 + otel-api.
 */
package com.dash0.mobile.reactnative

import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`data`.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.samplers.Sampler
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OTelNetworkInterceptorTest {

    private lateinit var spanExporter: InMemorySpanExporter
    private lateinit var sdk: OpenTelemetrySdk
    private lateinit var tracer: Tracer
    private lateinit var interceptor: OTelNetworkInterceptor

    @Before
    fun setUp() {
        spanExporter = InMemorySpanExporter.create()
        sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .setSampler(Sampler.alwaysOn())
                    .build(),
            )
            .build()
        tracer = sdk.getTracer("test")
        interceptor = OTelNetworkInterceptor()
    }

    @After
    fun tearDown() {
        sdk.close()
    }

    // ── unarmed → pure pass-through ──────────────────────────────────────────

    @Test
    fun unarmed_passesThrough_unmodifiedRequest_noSpan() {
        // No arm() call → interceptor must be a transparent no-op.
        val chain = FakeChain(get("https://api.example.com/widgets"))

        val response = interceptor.intercept(chain)

        // chain.proceed was called exactly once with the ORIGINAL request.
        assertEquals(1, chain.proceedCount)
        assertSame(chain.request, chain.proceededRequest)
        // No traceparent was injected.
        assertNull(chain.proceededRequest!!.header("traceparent"))
        // The exact response object flows back untouched.
        assertSame(chain.cannedResponse, response)
        // No telemetry produced.
        assertTrue(spanExporter.finishedSpanItems.isEmpty())
    }

    // ── armed → CLIENT span + W3C traceparent ────────────────────────────────

    @Test
    fun armed_recordsClientSpan_andInjectsWellFormedTraceparent() {
        interceptor.arm(tracer, collectorEndpoint = "https://ingress.dash0.com/v1/traces")
        val chain = FakeChain(get("https://api.example.com/orders?id=7"), responseCode = 200)

        val response = interceptor.intercept(chain)

        // Host request proceeded exactly once and the response is unchanged.
        assertEquals(1, chain.proceedCount)
        assertSame(chain.cannedResponse, response)

        // Exactly one CLIENT span, named by method, with the expected attributes.
        val spans = spanExporter.finishedSpanItems
        assertEquals(1, spans.size)
        val span: SpanData = spans[0]
        assertEquals("GET", span.name)
        assertEquals(io.opentelemetry.api.trace.SpanKind.CLIENT, span.kind)
        assertEquals("GET", span.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("http.request.method")))
        assertEquals("https://api.example.com/orders?id=7", span.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("url.full")))
        assertEquals("api.example.com", span.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("server.address")))
        assertEquals("https", span.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("url.scheme")))
        assertEquals(200L, span.attributes.get(io.opentelemetry.api.common.AttributeKey.longKey("http.response.status_code")))
        assertNotNull(span.attributes.get(io.opentelemetry.api.common.AttributeKey.longKey("http.client.request.duration_ms")))
        assertEquals(io.opentelemetry.api.trace.StatusCode.OK, span.status.statusCode)

        // The traceparent the host request carried must be a well-formed W3C
        // header derived from THIS span's context.
        val traceparent = chain.proceededRequest!!.header("traceparent")
        assertNotNull(traceparent)
        assertTrue(
            "traceparent not W3C-shaped: $traceparent",
            Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$").matches(traceparent!!),
        )
        // ...and it carries the span's REAL trace + span ids (distributed-trace stitch).
        assertEquals("00-${span.traceId}-${span.spanId}-01", traceparent)
    }

    // ── invalid/not-sampled context → traceparent OMITTED (not malformed) ─────

    @Test
    fun invalidSpanContext_omitsTraceparent_doesNotInjectMalformedHeader() {
        // A tracer whose spans always carry an INVALID span context (mirrors the
        // SDK handing back a no-op span when sampling is off). The interceptor
        // must inject NO traceparent rather than a malformed "00-000…-000…".
        interceptor.arm(InvalidContextTracer(), collectorEndpoint = null)
        val chain = FakeChain(get("https://api.example.com/x"))

        val response = interceptor.intercept(chain)

        assertEquals(1, chain.proceedCount)
        assertSame(chain.cannedResponse, response)
        assertNull("must omit traceparent for invalid context", chain.proceededRequest!!.header("traceparent"))
    }

    // ── collector host in ignore list → NOT instrumented ─────────────────────

    @Test
    fun collectorHost_isNotInstrumented_noSpanNoHeader() {
        // Arm with a collector endpoint; a request to that same host is our own
        // telemetry export and must NOT be instrumented (no recursion).
        interceptor.arm(tracer, collectorEndpoint = "https://ingress.dash0.com/v1/traces")
        val chain = FakeChain(get("https://ingress.dash0.com/v1/traces"))

        val response = interceptor.intercept(chain)

        assertEquals(1, chain.proceedCount)
        assertSame(chain.request, chain.proceededRequest) // untouched request
        assertNull(chain.proceededRequest!!.header("traceparent"))
        assertSame(chain.cannedResponse, response)
        assertTrue(spanExporter.finishedSpanItems.isEmpty())
    }

    @Test
    fun collectorHost_matchIsCaseInsensitive() {
        interceptor.arm(tracer, collectorEndpoint = "https://Ingress.Dash0.com/v1/traces")
        val chain = FakeChain(get("https://INGRESS.DASH0.COM/v1/traces"))

        interceptor.intercept(chain)

        assertTrue(spanExporter.finishedSpanItems.isEmpty())
        assertNull(chain.proceededRequest!!.header("traceparent"))
    }

    @Test
    fun nonCollectorHost_isInstrumented_evenWhenIgnoreListPresent() {
        interceptor.arm(tracer, collectorEndpoint = "https://ingress.dash0.com/v1/traces")
        val chain = FakeChain(get("https://api.example.com/y"))

        interceptor.intercept(chain)

        assertEquals(1, spanExporter.finishedSpanItems.size)
        assertNotNull(chain.proceededRequest!!.header("traceparent"))
    }

    // ── FAULT ISOLATION (the critical contract) ──────────────────────────────

    @Test
    fun throwingTracer_duringSetup_stillProceedsWithOriginalRequest_andReturnsResponse() {
        // The tracer throws as soon as the interceptor tries to start a span.
        // The host request must still proceed (with the UNMODIFIED request) and
        // the original response must come back verbatim. Telemetry failure must
        // never become a host-networking failure.
        interceptor.arm(ThrowingTracer(), collectorEndpoint = null)
        val chain = FakeChain(get("https://api.example.com/z"))

        val response = interceptor.intercept(chain)

        assertEquals(1, chain.proceedCount)
        assertSame("must proceed with the original, unmodified request", chain.request, chain.proceededRequest)
        assertNull(chain.proceededRequest!!.header("traceparent"))
        assertSame(chain.cannedResponse, response)
        assertTrue(spanExporter.finishedSpanItems.isEmpty())
    }

    @Test
    fun throwingTracer_doesNotPropagateTelemetryExceptionToHost() {
        // Belt-and-suspenders: the telemetry exception must be swallowed, not
        // surfaced to the host caller.
        interceptor.arm(ThrowingTracer(), collectorEndpoint = null)
        val chain = FakeChain(get("https://api.example.com/z"))
        try {
            interceptor.intercept(chain)
        } catch (t: Throwable) {
            fail("telemetry exception leaked to host: $t")
        }
    }

    // ── network failure → span ERROR, original throwable rethrown verbatim ────

    @Test
    fun networkException_endsSpanWithError_andRethrowsOriginalThrowableUnchanged() {
        interceptor.arm(tracer, collectorEndpoint = null)
        val boom = java.net.UnknownHostException("api.example.com: nodename nor servname provided")
        val chain = FakeChain(get("https://api.example.com/down"), throwOnProceed = boom)

        val thrown = try {
            interceptor.intercept(chain)
            null
        } catch (t: Throwable) {
            t
        }

        // The EXACT original throwable instance is rethrown (not wrapped).
        assertSame(boom, thrown)

        // The span was still recorded, ended, and marked ERROR.
        val spans = spanExporter.finishedSpanItems
        assertEquals(1, spans.size)
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, spans[0].status.statusCode)
        // No status_code attribute since the request never produced a response.
        assertNull(spans[0].attributes.get(io.opentelemetry.api.common.AttributeKey.longKey("http.response.status_code")))
    }

    // ── status-code mapping ──────────────────────────────────────────────────

    @Test
    fun http2xx_mapsToOkStatus() {
        interceptor.arm(tracer, collectorEndpoint = null)
        interceptor.intercept(FakeChain(get("https://api.example.com/ok"), responseCode = 204))
        assertEquals(io.opentelemetry.api.trace.StatusCode.OK, spanExporter.finishedSpanItems[0].status.statusCode)
    }

    @Test
    fun http4xx_mapsToErrorStatus() {
        interceptor.arm(tracer, collectorEndpoint = null)
        interceptor.intercept(FakeChain(get("https://api.example.com/missing"), responseCode = 404))
        val span = spanExporter.finishedSpanItems[0]
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, span.status.statusCode)
        assertEquals(404L, span.attributes.get(io.opentelemetry.api.common.AttributeKey.longKey("http.response.status_code")))
    }

    @Test
    fun http5xx_mapsToErrorStatus() {
        interceptor.arm(tracer, collectorEndpoint = null)
        interceptor.intercept(FakeChain(get("https://api.example.com/boom"), responseCode = 503))
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, spanExporter.finishedSpanItems[0].status.statusCode)
    }

    // ── method handling ──────────────────────────────────────────────────────

    @Test
    fun postRequest_spanNamedByUppercasedMethod_andRecordsMethodAttribute() {
        interceptor.arm(tracer, collectorEndpoint = null)
        val req = Request.Builder()
            .url("https://api.example.com/orders")
            .post("{}".toRequestBody())
            .build()
        interceptor.intercept(FakeChain(req, responseCode = 201))
        val span = spanExporter.finishedSpanItems[0]
        assertEquals("POST", span.name)
        assertEquals("POST", span.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("http.request.method")))
    }

    // ── disarm → back to pass-through ────────────────────────────────────────

    @Test
    fun disarm_returnsToPassThrough() {
        interceptor.arm(tracer, collectorEndpoint = null)
        interceptor.intercept(FakeChain(get("https://api.example.com/1")))
        assertEquals(1, spanExporter.finishedSpanItems.size)

        interceptor.disarm()
        val chain = FakeChain(get("https://api.example.com/2"))
        interceptor.intercept(chain)

        // Still just the one span from before the disarm.
        assertEquals(1, spanExporter.finishedSpanItems.size)
        assertNull(chain.proceededRequest!!.header("traceparent"))
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun get(url: String): Request = Request.Builder().url(url).build()
}

/**
 * Minimal okhttp3 Interceptor.Chain test double. Captures whatever request the
 * interceptor proceeds with, counts proceed calls, and either returns a canned
 * response or throws a supplied exception (network-failure simulation).
 */
private class FakeChain(
    val request: Request,
    responseCode: Int = 200,
    private val throwOnProceed: Throwable? = null,
) : Interceptor.Chain {

    var proceedCount = 0
    var proceededRequest: Request? = null

    val cannedResponse: Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(responseCode)
        .message("status $responseCode")
        .body("".toResponseBody())
        .build()

    override fun request(): Request = request

    override fun proceed(request: Request): Response {
        proceedCount++
        proceededRequest = request
        throwOnProceed?.let { throw it }
        // Return the SAME response instance so identity-based assertions can
        // prove the interceptor hands the host's response back verbatim.
        return cannedResponse
    }

    override fun connection() = null
    override fun call(): okhttp3.Call = throw UnsupportedOperationException()
    override fun connectTimeoutMillis(): Int = 0
    override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
    override fun readTimeoutMillis(): Int = 0
    override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
    override fun writeTimeoutMillis(): Int = 0
    override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
}

/** A Tracer whose span builder throws the moment startSpan() is called. */
private class ThrowingTracer : Tracer {
    override fun spanBuilder(spanName: String): io.opentelemetry.api.trace.SpanBuilder =
        throw RuntimeException("boom: tracer is broken")
}

/**
 * A Tracer that produces spans whose context is always INVALID — the SDK's
 * behavior when a span is dropped (e.g. sampling off) and it hands back a
 * no-op/invalid context. Used to prove the interceptor omits the traceparent
 * rather than emitting a malformed all-zeros header.
 */
private class InvalidContextTracer : Tracer {
    override fun spanBuilder(spanName: String): io.opentelemetry.api.trace.SpanBuilder =
        InvalidContextSpanBuilder()
}

private class InvalidContextSpanBuilder : io.opentelemetry.api.trace.SpanBuilder {
    override fun setParent(context: io.opentelemetry.context.Context) = this
    override fun setNoParent() = this
    override fun addLink(spanContext: io.opentelemetry.api.trace.SpanContext) = this
    override fun addLink(spanContext: io.opentelemetry.api.trace.SpanContext, attributes: io.opentelemetry.api.common.Attributes) = this
    // OTel API 1.63.0 added nullness annotations to SpanBuilder: the object-typed
    // setAttribute value params are now @Nullable (String? / T?). The primitive
    // overloads are unannotated (a primitive can't be null), so they stay non-null.
    override fun setAttribute(key: String, value: String?) = this
    override fun setAttribute(key: String, value: Long) = this
    override fun setAttribute(key: String, value: Double) = this
    override fun setAttribute(key: String, value: Boolean) = this
    override fun <T : Any> setAttribute(key: io.opentelemetry.api.common.AttributeKey<T>, value: T?) = this
    override fun setSpanKind(spanKind: io.opentelemetry.api.trace.SpanKind) = this
    override fun setStartTimestamp(startTimestamp: Long, unit: java.util.concurrent.TimeUnit) = this
    // Span.getInvalid() carries an invalid SpanContext and is a no-op.
    override fun startSpan(): io.opentelemetry.api.trace.Span = io.opentelemetry.api.trace.Span.getInvalid()
}
