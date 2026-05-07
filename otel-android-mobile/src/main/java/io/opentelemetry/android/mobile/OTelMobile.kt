/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile

import android.app.Application
import io.opentelemetry.android.mobile.autocapture.AutoCaptureOptions
import io.opentelemetry.android.mobile.config.UiTelemetryMode as ConfigUiTelemetryMode
import io.opentelemetry.android.mobile.instrumentation.UiTelemetryMode as CoreUiTelemetryMode
import io.opentelemetry.android.mobile.autocapture.RecoveryTracker
import io.opentelemetry.android.mobile.autocapture.SessionTracker
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.instrumentation.BackPressInstrumentation
import io.opentelemetry.android.mobile.instrumentation.ErrorsInstrumentation
import io.opentelemetry.android.mobile.instrumentation.FreezeInstrumentation
import io.opentelemetry.android.mobile.instrumentation.Incubating
import io.opentelemetry.android.mobile.instrumentation.LifecycleInstrumentation
import io.opentelemetry.android.mobile.instrumentation.OTelMobileBuilder
import io.opentelemetry.android.mobile.instrumentation.OTelMobileHandle
import io.opentelemetry.android.mobile.instrumentation.ScreenViewInstrumentation
import io.opentelemetry.android.mobile.instrumentation.ScrollInstrumentation
import io.opentelemetry.android.mobile.instrumentation.TapInstrumentation
import io.opentelemetry.android.mobile.instrumentation.TextInputInstrumentation
import io.opentelemetry.android.mobile.instrumentation.VitalsInstrumentation
import io.opentelemetry.android.mobile.instrumentation.ScreenshotInstrumentation
import io.opentelemetry.android.mobile.instrumentation.WireframeInstrumentation
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer

/**
 * Public entry point for automatic mobile instrumentation.
 *
 * Calling [start] initializes the full SDK with all auto-instrumentation:
 * - **Auto-capture**: taps, scrolls, back presses, freezes/ANR, lifecycle, screen views
 * - **Error capture**: uncaught exceptions, coroutine errors, RxJava errors → auto flush
 * - **Vitals**: app start, jank, memory pressure, thermal state → OTel metrics
 * - **Predictive export**: crash/network-loss risk → pre-emptive buffer flush
 * - **Ring buffer**: RAM (5000 events) → disk (50MB, 24h TTL) → selective export
 * - **Policy evaluation**: DSL-based trigger conditions → window flush
 * - **Session & breadcrumbs**: user journey tracking, session lifecycle
 *
 * Usage:
 * ```kotlin
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         OTelMobile.start(this, MobileConfig(
 *             serviceName = "my-app",
 *             serviceVersion = "1.0.0",
 *             collectorEndpoint = "https://collector.example.com:4317"
 *         ))
 *     }
 * }
 * ```
 */
private fun ConfigUiTelemetryMode.toCore(): CoreUiTelemetryMode = when (this) {
    ConfigUiTelemetryMode.EVENTS -> CoreUiTelemetryMode.EVENTS
    ConfigUiTelemetryMode.SPANS  -> CoreUiTelemetryMode.SPANS
    ConfigUiTelemetryMode.BOTH   -> CoreUiTelemetryMode.BOTH
}

@Incubating
object OTelMobile {
    @Volatile
    private var provider: MobileLoggerProvider? = null

    @Volatile
    private var handle: OTelMobileHandle? = null

    @Volatile
    private var recoveryTracker: RecoveryTracker? = null

    /**
     * Initializes the SDK and starts all auto-instrumentation.
     *
     * Idempotent — subsequent calls are no-ops while the SDK is running.
     * Typically called from [android.app.Application.onCreate].
     *
     * @param application The application instance used to register lifecycle callbacks.
     * @param config SDK configuration including service name, collector endpoint, and feature flags.
     */
    fun start(application: Application, config: MobileConfig) {
        synchronized(this) {
            if (provider == null) {
                val instance = MobileOtel.initialize(application, config)
                provider = instance

                val rt = RecoveryTracker(
                    application,
                    instance.get("io.opentelemetry.android.mobile.recovery"),
                    instance,
                    SessionTracker(AutoCaptureOptions())
                )
                recoveryTracker = rt
                rt.start()

                val builder = OTelMobileBuilder(application, instance.getOpenTelemetrySdk())
                    .setUiTelemetryMode(config.uiTelemetryMode.toCore())
                    .addInstrumentation(LifecycleInstrumentation())
                    .addInstrumentation(ScreenViewInstrumentation())
                    .addInstrumentation(TapInstrumentation())
                    .addInstrumentation(ScrollInstrumentation())
                    .addInstrumentation(TextInputInstrumentation(config.textInputConfig))
                    .addInstrumentation(BackPressInstrumentation())
                    .addInstrumentation(FreezeInstrumentation())
                    .addInstrumentation(ErrorsInstrumentation())
                    .addInstrumentation(VitalsInstrumentation())

                // Incubating: non-OTel-native modules, opt-in via config flags.
                if (config.screenshotConfig.enabled) {
                    builder.addInstrumentation(ScreenshotInstrumentation(config.screenshotConfig))
                }
                if (config.wireframeConfig.enabled) {
                    builder.addInstrumentation(WireframeInstrumentation(config.wireframeConfig))
                }

                handle = builder.build()
            }
        }
    }

    /**
     * Stops all instrumentation and performs a final flush.
     *
     * @param timeoutSeconds Maximum time in seconds to wait for in-flight exports to complete.
     */
    fun stop(timeoutSeconds: Long = 30) {
        handle?.stop(timeoutSeconds)
        handle = null
        recoveryTracker?.let { /* no stop() needed — it lives for app lifetime */ }
        recoveryTracker = null
        MobileOtel.shutdown()
        provider = null
    }

    /**
     * Returns the initialized [MobileLoggerProvider].
     *
     * @throws IllegalStateException if [start] has not been called.
     */
    fun getLoggerProvider(): MobileLoggerProvider {
        return provider ?: error("OTelMobile.start must be called before accessing the provider")
    }

    /**
     * Returns an OTel [Logger] for the given instrumentation scope.
     *
     * @param scope Instrumentation scope name, typically a reverse-DNS string.
     */
    fun getLogger(scope: String): Logger = getLoggerProvider().get(scope)

    /**
     * Returns an OTel [Tracer] for the given instrumentation scope.
     *
     * @param scope Instrumentation scope name.
     * @param version Optional scope version string.
     */
    fun getTracer(scope: String, version: String? = null): Tracer {
        val sdk = getLoggerProvider().getOpenTelemetrySdk()
        return if (version == null) sdk.getTracer(scope) else sdk.getTracer(scope, version)
    }

    /**
     * Returns an OTel [Meter] for the given instrumentation scope.
     *
     * @param scope Instrumentation scope name.
     */
    fun getMeter(scope: String): Meter = getLoggerProvider().getOpenTelemetrySdk().getMeter(scope)

    /**
     * Returns the recovery type detected at the previous app start (e.g., "crash", "anr", "low_memory"),
     * or null if the app started normally.
     */
    fun getLastRecoveryType(): String? = recoveryTracker?.getLastRecoveryType()

    /**
     * Persists a flag so the next app start knows the previous session ended in a crash.
     * Used by the [RecoveryTracker] to emit a crash-recovery event on launch.
     */
    fun markCrashForNextStart() {
        recoveryTracker?.markCrashForNextStart()
    }

    /**
     * Persists a flag so the next app start knows the previous session was terminated due to low memory.
     */
    fun markLowMemoryForNextStart() {
        recoveryTracker?.markLowMemoryForNextStart()
    }

    /**
     * Persists a flag so the next app start knows the previous session ended with an ANR.
     */
    fun markAnrForNextStart() {
        recoveryTracker?.markAnrForNextStart()
    }

    /**
     * Ends the current page span and starts a fresh one for the same screen.
     * Page spans are now managed by ScreenViewInstrumentation; this is a no-op kept for API
     * compatibility and will be removed in the cleanup pass (Task 7).
     */
    fun restartPageSpan(screenName: String) {
        // Page spans are managed by ScreenViewInstrumentation — no-op here
    }

    /**
     * Starts a journey span that becomes the parent for all subsequent page and interaction spans.
     *
     * Make the returned span current on the main thread with [Span.makeCurrent] so that
     * [ScreenViewInstrumentation] can nest page spans under it automatically.
     *
     * For visual replay, pair with [endJourney] (or [captureScreenshot] /
     * [captureWireframe] explicitly) so the journey timeline carries
     * screenshot + wireframe attachments. See
     * [USER_JOURNEY_CAPTURES_EPIC](../../docs/epics/USER_JOURNEY_CAPTURES_EPIC.md).
     *
     * @param name Journey name (e.g., "checkout", "onboarding").
     * @return The started journey [Span]. Caller is responsible for calling
     *   [Span.end] (or [endJourney] for the auto-capture variant).
     */
    @Incubating
    fun startJourney(name: String): Span {
        return getTracer("io.opentelemetry.android.mobile.journey").spanBuilder(name)
            .setSpanKind(io.opentelemetry.api.trace.SpanKind.INTERNAL)
            .startSpan()
    }

    /**
     * Ends a [journey] span and triggers a final screenshot + wireframe
     * capture so the control plane has the visual end-state. Captures emit
     * BEFORE [Span.end] so they inherit the journey's `trace_id` (UJ-003).
     *
     * Silent no-op for capture if the screenshot/wireframe instrumentation
     * modules aren't registered. Always ends the span.
     */
    @Incubating
    fun endJourney(journey: Span) {
        handle?.endJourney(journey) ?: journey.end()
    }

    /**
     * Triggers a screenshot capture. Inherits the current OTel `Context` so
     * captures emitted inside a journey span carry the journey's `trace_id`.
     * Silent no-op if the screenshot instrumentation module isn't registered.
     *
     * @param trigger Common values: `"manual"`, `"journey_start"`,
     *   `"journey_end"`, `"error"`. Recorded as
     *   `mobile.screenshot.trigger` on the emitted log record.
     */
    @Incubating
    fun captureScreenshot(trigger: String = "manual") {
        handle?.captureScreenshot(trigger)
    }

    /**
     * Triggers a wireframe capture. See [captureScreenshot] for the
     * context-propagation contract.
     */
    @Incubating
    fun captureWireframe(trigger: String = "manual") {
        handle?.captureWireframe(trigger)
    }

    /**
     * Returns a new [OTelMobileBuilder] for fine-grained SDK configuration.
     *
     * Use this instead of [start] when you want to control exactly which
     * instrumentations are active.
     *
     * ```kotlin
     * val handle = OTelMobile.builder(app, openTelemetry)
     *     .addInstrumentation(TapInstrumentation())
     *     .addInstrumentation(LifecycleInstrumentation())
     *     .build()
     * ```
     */
    fun builder(application: Application, openTelemetry: OpenTelemetry): OTelMobileBuilder {
        return OTelMobileBuilder(application, openTelemetry)
    }
}
