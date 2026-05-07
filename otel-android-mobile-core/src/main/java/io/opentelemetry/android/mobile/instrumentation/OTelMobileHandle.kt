// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.util.Log
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Span
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
     * Triggers a screenshot capture if [ScreenshotInstrumentation] is registered.
     *
     * Reflective lookup keeps `otel-android-mobile-core` decoupled from the
     * separate `instrumentation/screenshot/` module; if the screenshot
     * module isn't on the classpath this is a silent no-op.
     *
     * The captured screenshot inherits the current OTel `Context` so it
     * carries the active journey span's `trace_id` (see UJ-003).
     *
     * @param trigger Describes what triggered the capture. Recorded as
     *   `mobile.screenshot.trigger`. Common values: `"manual"`,
     *   `"journey_start"`, `"journey_end"`, `"error"`.
     */
    fun captureScreenshot(trigger: String = "manual") {
        invokeCapture("io.opentelemetry.android.mobile.screenshot", "captureScreenshot", trigger)
    }

    /**
     * Triggers a wireframe capture if [WireframeInstrumentation] is registered.
     *
     * See [captureScreenshot] for the lookup + context-propagation contract.
     */
    fun captureWireframe(trigger: String = "manual") {
        invokeCapture("io.opentelemetry.android.mobile.wireframe", "captureWireframe", trigger)
    }

    /**
     * Convenience: ends a journey [Span] and triggers a final screenshot +
     * wireframe capture so the control plane has the visual state at the
     * journey boundary. Captures emit BEFORE [Span.end] so they inherit the
     * journey's context.
     *
     * Usage:
     * ```kotlin
     * val journey = otelMobile.startJourney("book_appointment")
     * val scope = journey.makeCurrent()
     * try {
     *     // ... user flow
     * } finally {
     *     scope.close()
     *     otelMobile.endJourney(journey)
     * }
     * ```
     */
    fun endJourney(journey: Span) {
        try {
            captureScreenshot("journey_end")
            captureWireframe("journey_end")
        } finally {
            journey.end()
        }
    }

    private fun invokeCapture(instrumentationName: String, methodName: String, trigger: String) {
        val inst = registry.findByName(instrumentationName) ?: return
        try {
            val method = inst.javaClass.getMethod(methodName, String::class.java)
            method.invoke(inst, trigger)
        } catch (e: NoSuchMethodException) {
            Log.d("OTelMobileHandle", "$instrumentationName has no $methodName(String) method", e)
        } catch (e: Exception) {
            Log.w("OTelMobileHandle", "$instrumentationName.$methodName failed", e)
        }
    }

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
