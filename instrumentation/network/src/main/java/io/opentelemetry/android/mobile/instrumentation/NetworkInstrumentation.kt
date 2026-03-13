// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import io.opentelemetry.android.mobile.instrumentation.Incubating
import android.app.Application
import android.util.Log
import io.opentelemetry.android.mobile.network.NetworkConfig
import io.opentelemetry.android.mobile.network.OTelNetworkInterceptor
import okhttp3.Interceptor

/**
 * [MobileInstrumentation] wrapper for [OTelNetworkInterceptor].
 *
 * Because [OTelNetworkInterceptor] is an OkHttp interceptor that must be
 * manually added to an [okhttp3.OkHttpClient] by the application, this class
 * cannot auto-inject network instrumentation.  Instead it creates the
 * interceptor during [install] and exposes it via [getInterceptor] so that
 * callers can retrieve it and attach it to their HTTP client.
 *
 * Example:
 * ```kotlin
 * val netInst = NetworkInstrumentation()
 * // (registered automatically via InstrumentationRegistry or manually)
 * netInst.install(app, ctx)
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(netInst.getInterceptor()!!)
 *     .build()
 * ```
 */
@Incubating
class NetworkInstrumentation : MobileInstrumentation {

    override val instrumentationName = "io.opentelemetry.android.mobile.network"

    @Volatile private var interceptor: Interceptor? = null

    override fun install(application: Application, context: InstrumentationContext) {
        try {
            interceptor = OTelNetworkInterceptor.create(
                context = application.applicationContext,
                config = NetworkConfig.default(),
                tracer = context.tracer(instrumentationName),
                propagator = context.openTelemetry.propagators.textMapPropagator,
                logger = context.logger(instrumentationName)
            )
        } catch (e: Exception) {
            Log.w("NetworkInstrumentation", "Failed to create OTelNetworkInterceptor: ${e.message}")
            // Provide a no-op interceptor so getInterceptor() is still non-null after install.
            interceptor = Interceptor { chain -> chain.proceed(chain.request()) }
        }
    }

    override fun uninstall() {
        interceptor = null
    }

    /**
     * Returns the [OTelNetworkInterceptor] created during [install], or `null`
     * if [install] has not been called yet.
     *
     * Add this interceptor to your [okhttp3.OkHttpClient] to enable automatic
     * span creation and W3C trace-context propagation for all HTTP calls.
     */
    fun getInterceptor(): Interceptor? = interceptor
}
