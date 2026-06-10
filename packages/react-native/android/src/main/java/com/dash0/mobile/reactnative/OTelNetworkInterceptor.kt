/*
 * OTelNetworkInterceptor — the Android network-instrumentation story for
 * @dash0/mobile-react-native.
 *
 * WHY NATIVE (and not the JS XHR/fetch shim):
 *   Expo SDK 52+ replaces the global `fetch` with `expo/fetch`, which is
 *   implemented natively on OkHttp rather than on `XMLHttpRequest`. The JS
 *   `fetch`/`XHR` shims therefore see ZERO traffic in Expo apps. Installing an
 *   OkHttp `Interceptor` on React Native's shared OkHttp client captures every
 *   outbound request — classic `fetch`, `XMLHttpRequest`, axios, AND `expo/fetch`
 *   — because they all bottom out on that one client.
 *
 * WHAT IT DOES, per request:
 *   1. Records a native CLIENT span (name, http.request.method, url.full,
 *      server.address, http.response.status_code, timing).
 *   2. Injects a W3C `traceparent` header built from the REAL native span
 *      context (trace id + span id + sampled flag), so mobile→backend
 *      distributed traces stitch on Android the same way iOS's
 *      OTelURLProtocol stitches them.
 *
 * HOST SAFETY (non-negotiable):
 *   This interceptor wraps the host app's ENTIRE OkHttp pipeline. If it ever
 *   throws, it breaks ALL networking. So every telemetry operation is wrapped
 *   such that `chain.proceed(request)` ALWAYS runs and the original
 *   response/exception is ALWAYS returned. Telemetry failure = drop telemetry,
 *   never affect the host request.
 *
 * LIFECYCLE:
 *   The interceptor is registered on RN's OkHttpClientProvider BEFORE any JS
 *   runs (in Dash0MobilePackage.createNativeModules) so expo/fetch's OkHttp
 *   client picks it up. At that point no tracer exists yet, so it is armed via
 *   [arm] when the sink's `start` runs (which is what stands up OTelMobile and
 *   hence the tracer). Before [arm], and after [disarm], it is a pure
 *   pass-through no-op.
 */
package com.dash0.mobile.reactnative

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Interceptor
import okhttp3.Response

// Public (not `internal`) because it is exposed through the public
// `NetworkInstrumentation.interceptor` property; an `internal` type behind a
// public property is a Kotlin "exposes internal type" compile error.
class OTelNetworkInterceptor : Interceptor {

    /**
     * Everything the interceptor needs to produce telemetry. Held behind a
     * single immutable reference swapped atomically by [arm] / [disarm] so the
     * intercept path reads a consistent snapshot without locking. `null` = not
     * yet armed (or shut down) → pure pass-through.
     */
    private class Armament(
        val tracer: Tracer,
        /** Lower-cased collector hosts that must NOT be instrumented (our own exports). */
        val ignoredHosts: Set<String>,
    )

    private val armament = AtomicReference<Armament?>(null)

    /**
     * Arm the interceptor with a tracer once the sink has started OTelMobile.
     * Called from [OTelMobileCallSink.start]. The `collectorEndpoint` is the
     * configured OTLP endpoint; its host is added to the ignore list so we do
     * not instrument (and recurse on) our own telemetry exports.
     */
    fun arm(tracer: Tracer, collectorEndpoint: String?) {
        val ignored = buildSet {
            hostFromUrl(collectorEndpoint)?.let { add(it) }
        }
        armament.set(Armament(tracer, ignored))
    }

    /** Return to pass-through. Called on sink shutdown. */
    fun disarm() {
        armament.set(null)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val active = armament.get()
            ?: return chain.proceed(chain.request())

        // From here on, EVERY telemetry step is fault-isolated. The original
        // request must always be proceeded, and the original response (or
        // exception) must always be returned to the host.

        val originalRequest = chain.request()

        // Decide whether to instrument at all. A throw while computing this
        // must not stop the request — fall back to "don't instrument".
        val host: String? = try {
            originalRequest.url.host.lowercase()
        } catch (_: Throwable) {
            null
        }
        val shouldInstrument = try {
            host == null || !active.ignoredHosts.contains(host)
        } catch (_: Throwable) {
            false
        }

        if (!shouldInstrument) {
            return chain.proceed(originalRequest)
        }

        // Start the span + build the traceparent-augmented request. If ANY of
        // this throws, we proceed with the ORIGINAL request and skip telemetry.
        var span: Span? = null
        val outgoing = try {
            val method = originalRequest.method.uppercase()
            val url = originalRequest.url
            val startedSpan = active.tracer
                .spanBuilder(method)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan()
            span = startedSpan
            startedSpan.setAttribute("http.request.method", method)
            startedSpan.setAttribute("url.full", url.toString())
            startedSpan.setAttribute("server.address", url.host)
            startedSpan.setAttribute("url.scheme", url.scheme)

            val traceparent = traceparentOf(startedSpan)
            if (traceparent != null) {
                originalRequest.newBuilder()
                    .header("traceparent", traceparent)
                    .build()
            } else {
                originalRequest
            }
        } catch (_: Throwable) {
            // Telemetry setup failed. Make sure any half-started span is closed
            // (best-effort) and proceed cleanly with the untouched request.
            try {
                span?.end()
            } catch (_: Throwable) {
            }
            return chain.proceed(originalRequest)
        }

        // THE host request. This is the one call that must propagate its result
        // (response OR exception) verbatim to the host. We time it and enrich
        // the span around it, but never let enrichment alter the outcome.
        val startNanos = System.nanoTime()
        val response: Response
        try {
            response = chain.proceed(outgoing)
        } catch (t: Throwable) {
            // Network failure: record it on the span (best-effort) then rethrow
            // the ORIGINAL throwable so the host sees the real error.
            try {
                span?.setStatus(StatusCode.ERROR, t.javaClass.simpleName)
                span?.end()
            } catch (_: Throwable) {
            }
            throw t
        }

        // Success path: enrich + end the span. A throw here must NOT corrupt the
        // response we hand back to the host.
        try {
            val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
            val code = response.code
            span?.setAttribute("http.response.status_code", code.toLong())
            span?.setAttribute("http.client.request.duration_ms", durationMs)
            if (code >= 400) {
                span?.setStatus(StatusCode.ERROR, "HTTP $code")
            } else {
                span?.setStatus(StatusCode.OK)
            }
            span?.end()
        } catch (_: Throwable) {
            // Telemetry finalization failed — drop it, keep the response.
        }

        return response
    }

    private companion object {
        private val HOST_PATTERN =
            Regex("^[a-z][a-z0-9+.-]*://([^/:?#]+)", RegexOption.IGNORE_CASE)

        /**
         * W3C traceparent: `00-<32hex traceId>-<16hex spanId>-<2hex flags>`.
         * Built from the REAL native span context so the backend stitches the
         * mobile CLIENT span to its SERVER span. Returns null for an invalid
         * context (e.g. a no-op span when sampling is off and the SDK hands back
         * an invalid context) so we never inject a malformed header.
         */
        fun traceparentOf(span: Span): String? {
            val ctx = span.spanContext
            if (!ctx.isValid) return null
            val flags = if (ctx.isSampled) "01" else "00"
            return "00-${ctx.traceId}-${ctx.spanId}-$flags"
        }

        fun hostFromUrl(url: String?): String? {
            if (url == null) return null
            return HOST_PATTERN.find(url)?.groupValues?.getOrNull(1)?.lowercase()
        }
    }
}
