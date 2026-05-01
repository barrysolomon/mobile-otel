/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.demo

import android.util.Log
import androidx.activity.ComponentActivity
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapSetter
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI

/**
 * Gate 2 probe — upstream flavor (`io.opentelemetry.android:android-agent`).
 *
 * Compare to `src/dash0/.../Gate2Probe.kt`. Same observable outcome — an
 * OTLP CLIENT span with HTTP semantic conventions and W3C tracecontext
 * on the outgoing request — but expressed against the bare OTel API
 * because the upstream Android agent ships no first-party HTTP
 * interceptor. Caller is responsible for:
 *
 *   1. Starting/ending a span of `SpanKind.CLIENT`.
 *   2. Stamping HTTP semantic-convention attributes (`http.request.method`,
 *      `url.full`, `server.address`, `server.port`,
 *      `http.response.status_code`).
 *   3. Injecting `traceparent`/`tracestate` into the outgoing headers via
 *      the configured `TextMapPropagator`.
 *   4. Mapping IOException → `SpanStatus.ERROR` and `error.type`.
 *   5. Closing the span on every exit path.
 *
 * The line-count delta vs. the dash0 flavor is the point.
 */
object Gate2Probe {

    // OTel HTTP semantic conventions (stable v1.32.0). Listed by hand
    // because the upstream Android agent does not expose them on a
    // ready-made interceptor.
    private val HTTP_REQUEST_METHOD = AttributeKey.stringKey("http.request.method")
    private val URL_FULL = AttributeKey.stringKey("url.full")
    private val SERVER_ADDRESS = AttributeKey.stringKey("server.address")
    private val SERVER_PORT = AttributeKey.longKey("server.port")
    private val HTTP_RESPONSE_STATUS_CODE = AttributeKey.longKey("http.response.status_code")
    private val ERROR_TYPE = AttributeKey.stringKey("error.type")

    private val HEADER_SETTER = TextMapSetter<Request.Builder> { carrier, key, value ->
        carrier?.header(key, value)
    }

    fun fire(activity: ComponentActivity) {
        val otel = OtelDemoApplication.openTelemetry
        if (otel == null) {
            Log.w(TAG, "Gate2: openTelemetry is null, skipping HTTP probe")
            return
        }
        Log.i(TAG, "Gate2: firing httpbin.org/get (upstream flavor — hand-rolled spans)")
        val tracer = otel.getTracer("io.opentelemetry.android.demo.gate2", "0.1.0")
        val propagator = otel.propagators.textMapPropagator
        val client = OkHttpClient.Builder().build()
        val urlString = "https://httpbin.org/get"
        val uri = URI(urlString)

        Thread {
            for (i in 1..30) {
                // ── Manual span lifecycle ─────────────────────────
                val span = tracer.spanBuilder("GET")
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute(HTTP_REQUEST_METHOD, "GET")
                    .setAttribute(URL_FULL, urlString)
                    .setAttribute(SERVER_ADDRESS, uri.host)
                    .setAttribute(SERVER_PORT, (uri.port.takeIf { it >= 0 } ?: 443).toLong())
                    .startSpan()
                val scope = span.makeCurrent()
                try {
                    // ── Manual W3C tracecontext injection ─────────
                    val builder = Request.Builder().url(urlString).get()
                    propagator.inject(Context.current(), builder, HEADER_SETTER)
                    val request = builder.build()

                    val response = client.newCall(request).execute()
                    span.setAttribute(HTTP_RESPONSE_STATUS_CODE, response.code.toLong())
                    if (response.code >= 400) {
                        span.setStatus(StatusCode.ERROR)
                        span.setAttribute(ERROR_TYPE, response.code.toString())
                    }
                    if (i == 1) Log.i(TAG, "Gate2: httpbin response code=${response.code}")
                    response.close()
                } catch (e: IOException) {
                    // ── Manual exception mapping ──────────────────
                    span.setStatus(StatusCode.ERROR, e.message ?: "")
                    span.setAttribute(ERROR_TYPE, e.javaClass.name)
                    span.recordException(e)
                    Log.w(TAG, "Gate2: httpbin call failed: ${e.message}")
                } finally {
                    scope.close()
                    span.end()
                }
            }
            Log.i(TAG, "Gate2: 30 probes complete")
        }.start()
    }
}
