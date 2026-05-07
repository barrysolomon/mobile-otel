// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.util.Log
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import java.util.concurrent.TimeUnit

/**
 * Live handle to the running Mobile OTel SDK instance.
 *
 * Returned by [OTelMobileBuilder.build]. Owns the [InstrumentationRegistry]
 * and the [OpenTelemetry] SDK instance. All SDK state lives here — the
 * stateless factory entry-point in the aggregator module holds no state.
 *
 * Call [stop] to flush, deactivate all instrumentation, and release resources.
 */
@Incubating
class OTelMobileHandle internal constructor(
    internal val openTelemetry: OpenTelemetry,
    private val registry: InstrumentationRegistry,
    private val hubInstaller: WindowEventHubInstaller? = null
) {
    val sessionProvider: MobileSessionProvider? get() = registry.sessionProvider

    /** Returns a [Tracer] scoped to [scope]. */
    fun getTracer(scope: String): Tracer = openTelemetry.getTracer(scope)

    /** Returns a [Logger] scoped to [scope]. */
    fun getLogger(scope: String): Logger = openTelemetry.logsBridge.get(scope)

    /** Returns a [Meter] scoped to [scope]. */
    fun getMeter(scope: String): Meter = openTelemetry.getMeter(scope)

    /**
     * Stops all instrumentation, flushes pending telemetry, and releases resources.
     * After calling [stop], this handle should not be used.
     *
     * @param timeoutSeconds Maximum time to wait for in-flight telemetry to flush.
     */
    fun stop(timeoutSeconds: Long = 30) {
        hubInstaller?.uninstall()
        registry.uninstall()

        val sdk = openTelemetry as? OpenTelemetrySdk ?: return
        try {
            // Flush all pending telemetry (logs, traces, metrics) before shutdown.
            // Without this, disk-overflow events and buffered spans are silently lost.
            sdk.sdkLoggerProvider.forceFlush().join(timeoutSeconds, TimeUnit.SECONDS)
            sdk.sdkTracerProvider.forceFlush().join(timeoutSeconds, TimeUnit.SECONDS)
            sdk.sdkMeterProvider.forceFlush().join(timeoutSeconds, TimeUnit.SECONDS)

            sdk.shutdown().join(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w("OTelMobileHandle", "Error during OTel SDK shutdown", e)
        }
    }
}
