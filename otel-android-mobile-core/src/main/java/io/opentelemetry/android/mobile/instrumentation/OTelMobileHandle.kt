// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer

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
    private val openTelemetry: OpenTelemetry,
    private val registry: InstrumentationRegistry
) {
    /** Returns a [Tracer] scoped to [scope]. */
    fun getTracer(scope: String): Tracer = openTelemetry.getTracer(scope)

    /** Returns a [Logger] scoped to [scope]. */
    fun getLogger(scope: String): Logger = openTelemetry.logsBridge.get(scope)

    /** Returns a [Meter] scoped to [scope]. */
    fun getMeter(scope: String): Meter = openTelemetry.getMeter(scope)

    /**
     * Stops all instrumentation and releases resources.
     * After calling [stop], this handle should not be used.
     *
     * @param timeoutSeconds Maximum time to wait for in-flight telemetry to flush.
     * TODO: wire to OpenTelemetry SDK forceFlush when aggregator module is wired (Task 15).
     */
    fun stop(timeoutSeconds: Long = 30) {
        registry.uninstall()
        // TODO: call openTelemetry SDK shutdown/forceFlush with timeoutSeconds (Task 15)
    }
}
