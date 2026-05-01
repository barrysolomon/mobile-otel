/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.demo

import android.util.Log
import androidx.activity.ComponentActivity
import io.opentelemetry.android.mobile.network.NetworkConfig
import io.opentelemetry.android.mobile.network.OTelNetworkInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Gate 2 probe — Dash0 flavor.
 *
 * Compare to `src/upstream/.../Gate2Probe.kt`. Same observable outcome:
 * an OTLP CLIENT span with `http.request.method=GET`, `url.full=...`,
 * and `server.address=httpbin.org`, plus W3C tracecontext injected into
 * the outgoing request. Counting the lines of substantive code below
 * vs. the upstream version is the point.
 */
object Gate2Probe {
    fun fire(activity: ComponentActivity) {
        val otel = OtelDemoApplication.openTelemetry
        if (otel == null) {
            Log.w(TAG, "Gate2: openTelemetry is null, skipping HTTP probe")
            return
        }
        Log.i(TAG, "Gate2: firing httpbin.org/get (dash0 flavor — interceptor-based)")
        val tracer = otel.getTracer("io.opentelemetry.android.demo.gate2", "0.1.0")
        val propagator = otel.propagators.textMapPropagator

        // ── 4 lines: the entire span lifecycle, semconv, and W3C
        //    propagation are handled by OTelNetworkInterceptor. ──
        val client = OkHttpClient.Builder()
            .addInterceptor(OTelNetworkInterceptor.create(activity.applicationContext, NetworkConfig(), tracer, propagator))
            .build()

        val request = Request.Builder().url("https://httpbin.org/get").get().build()
        // Fire 30 in parallel to overcome the SDK's default 10% trace sampler
        // (SamplingConfig.dynamic(normalRate=0.1)). 30x ≈ 95.8% chance one
        // CLIENT span survives sampling and reaches Dash0. Single-shot was
        // flaky for matchy-matchy validation.
        Thread {
            for (i in 1..30) {
                try {
                    val r = client.newCall(request).execute()
                    if (i == 1) Log.i(TAG, "Gate2: httpbin response code=${r.code}")
                    r.close()
                } catch (e: IOException) {
                    Log.w(TAG, "Gate2: httpbin call failed: ${e.message}")
                }
            }
            Log.i(TAG, "Gate2: 30 probes complete")
        }.start()
    }
}
