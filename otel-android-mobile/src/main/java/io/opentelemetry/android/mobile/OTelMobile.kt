package io.opentelemetry.android.mobile

import android.app.Application
import io.opentelemetry.android.mobile.autocapture.AutoCaptureManager
import io.opentelemetry.android.mobile.autocapture.AutoCaptureOptions
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer

/**
 * Public entry point for automatic mobile instrumentation.
 */
object OTelMobile {
    @Volatile
    private var provider: MobileLoggerProvider? = null

    @Volatile
    private var autoCaptureManager: AutoCaptureManager? = null

    fun start(application: Application, config: MobileConfig, options: AutoCaptureOptions = AutoCaptureOptions()) {
        synchronized(this) {
            val instance = provider ?: MobileLoggerProvider.getInstance(application, config).also {
                provider = it
            }
            if (autoCaptureManager == null) {
                autoCaptureManager = AutoCaptureManager(application, instance, options).also {
                    it.start()
                }
            }
        }
    }

    fun stop(timeoutSeconds: Long = 30) {
        autoCaptureManager?.stop()
        autoCaptureManager = null
        provider?.shutdown(timeoutSeconds)
        provider = null
    }

    fun getLoggerProvider(): MobileLoggerProvider {
        return provider ?: error("OTelMobile.start must be called before accessing the provider")
    }

    fun getLogger(scope: String): Logger = getLoggerProvider().get(scope)

    fun getTracer(scope: String, version: String? = null): Tracer {
        val sdk = getLoggerProvider().getOpenTelemetrySdk()
        return if (version == null) sdk.getTracer(scope) else sdk.getTracer(scope, version)
    }

    fun getMeter(scope: String): Meter = getLoggerProvider().getOpenTelemetrySdk().getMeter(scope)

    fun getLastRecoveryType(): String? = autoCaptureManager?.getLastRecoveryType()

    fun markCrashForNextStart() {
        autoCaptureManager?.markCrashForNextStart()
    }

    fun markLowMemoryForNextStart() {
        autoCaptureManager?.markLowMemoryForNextStart()
    }

    fun markAnrForNextStart() {
        autoCaptureManager?.markAnrForNextStart()
    }

    fun startJourney(name: String): Span {
        val manager = autoCaptureManager
        return manager?.startJourney(name)
            ?: getTracer("journey").spanBuilder(name).startSpan()
    }
}
