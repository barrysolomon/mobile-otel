/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile

import android.content.Context
import io.opentelemetry.android.mobile.buffering.MobileLogRecordProcessor
import io.opentelemetry.android.mobile.config.ExporterCustomizers
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.instrumentation.Incubating
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.LoggerProvider
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.android.mobile.sampling.SamplerFactory
import io.opentelemetry.android.mobile.sampling.DynamicSampler
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Mobile-optimized OpenTelemetry Provider with full observability support (logs, traces, metrics).
 *
 * This provider extends the standard OpenTelemetry SDK with mobile-specific features:
 * - Two-tier ring buffer (RAM + disk) for offline support
 * - Policy-based conditional export to reduce bandwidth
 * - Device ID correlation for session tracking
 * - Crash recovery with persisted events
 * - Full support for logs, traces, and metrics
 *
 * Usage:
 * ```kotlin
 * val config = MobileConfig(
 *     serviceName = "my-app",
 *     serviceVersion = "1.0.0",
 *     collectorEndpoint = "https://collector.example.com:4317"
 * )
 * val provider = MobileLoggerProvider.getInstance(context, config)
 *
 * // Get logger for logs
 * val logger = provider.getLogger("my-component")
 *
 * // Get tracer for traces
 * val tracer = provider.getOpenTelemetrySdk().getTracer("my-component", "1.0.0")
 *
 * // Get meter for metrics
 * val meter = provider.getOpenTelemetrySdk().getMeter("my-component")
 * ```
 *
 * @see MobileConfig for configuration options
 * @see MobileLogRecordProcessor for buffering and export logic
 */
@Incubating
class MobileLoggerProvider private constructor(
    private val context: Context,
    private val config: MobileConfig,
    private val customizers: ExporterCustomizers = ExporterCustomizers()
) : LoggerProvider {

    private val sdkLoggerProvider: SdkLoggerProvider
    private val openTelemetrySdk: OpenTelemetrySdk
    private val deviceId: String = getOrCreateDeviceId(context)
    private val sampler: io.opentelemetry.sdk.trace.samplers.Sampler
    private val mobileProcessor: MobileLogRecordProcessor

    init {
        // Create sampler based on configuration
        sampler = SamplerFactory.createSampler(config.samplingConfig)

        val resource = Resource.getDefault().merge(
            Resource.builder()
                .put("service.name", config.serviceName)
                .put("service.version", config.serviceVersion)
                .put("device.id", deviceId)
                .put("device.platform", "android")
                .put("os.name", "android")
                .put("os.version", android.os.Build.VERSION.RELEASE)
                .put("os.description", "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                .put("device.model.name", android.os.Build.MODEL)
                .put("device.manufacturer", android.os.Build.MANUFACTURER)
                .build()
        )

        // Create base log exporter (gRPC — note: this is wrapped by LoggingHttpExporter which does
        // the actual HTTP export. The gRPC exporter here is the fallback delegate; in practice
        // LoggingHttpExporter handles the export via OkHttp.)
        var baseLogExporter: LogRecordExporter = OtlpGrpcLogRecordExporter.builder()
            .setEndpoint(config.collectorEndpoint)
            .setTimeout(config.exportTimeoutSeconds, TimeUnit.SECONDS)
            .apply {
                config.headers?.forEach { (key, value) ->
                    android.util.Log.d("MobileLoggerProvider", "Adding header: $key = $value")
                    addHeader(key, value)
                }
            }
            .build()
        for (c in customizers.log) { baseLogExporter = c(baseLogExporter) }

        // Wrap with logging for debugging
        val loggingExporter = io.opentelemetry.android.mobile.export.LoggingHttpExporter(
            delegate = baseLogExporter,
            endpoint = config.collectorEndpoint
        )

        // Wrap exporter with retry logic
        val retryableExporter = io.opentelemetry.android.mobile.buffering.RetryableExporter(
            delegate = loggingExporter,
            maxRetries = config.maxExportRetries
        )

        // Create OTLP HTTP metric exporter
        val metricEndpoint = config.collectorEndpoint.removeSuffix("/") + "/v1/metrics"
        var baseMetricExporter: io.opentelemetry.sdk.metrics.export.MetricExporter = OtlpHttpMetricExporter.builder()
            .setEndpoint(metricEndpoint)
            .setTimeout(config.exportTimeoutSeconds, TimeUnit.SECONDS)
            .apply {
                config.headers?.forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()
        for (c in customizers.metric) { baseMetricExporter = c(baseMetricExporter) }

        // Build SDK Meter Provider with mode-appropriate configuration
        val meterProvider = SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(
                when (config.exportMode) {
                    io.opentelemetry.android.mobile.config.ExportMode.CONDITIONAL -> {
                        PeriodicMetricReader.builder(baseMetricExporter)
                            .setInterval(3600, TimeUnit.SECONDS)
                            .build()
                    }
                    io.opentelemetry.android.mobile.config.ExportMode.CONTINUOUS -> {
                        PeriodicMetricReader.builder(baseMetricExporter)
                            .setInterval(config.metricExportIntervalSeconds, TimeUnit.SECONDS)
                            .build()
                    }
                    io.opentelemetry.android.mobile.config.ExportMode.HYBRID -> {
                        PeriodicMetricReader.builder(baseMetricExporter)
                            .setInterval(config.metricExportIntervalSeconds, TimeUnit.SECONDS)
                            .build()
                    }
                }
            )
            .build()

        // Create OTLP trace exporter (HTTP, not gRPC — Android lacks gRPC transport by default)
        val traceEndpoint = config.collectorEndpoint.removeSuffix("/") + "/v1/traces"
        var baseSpanExporter: io.opentelemetry.sdk.trace.export.SpanExporter = OtlpHttpSpanExporter.builder()
            .setEndpoint(traceEndpoint)
            .setTimeout(config.exportTimeoutSeconds, TimeUnit.SECONDS)
            .apply {
                config.headers?.forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()
        for (c in customizers.span) { baseSpanExporter = c(baseSpanExporter) }

        val tracerProvider = SdkTracerProvider.builder()
            .setResource(resource)
            .setSampler(sampler)
            .addSpanProcessor(
                when (config.exportMode) {
                    io.opentelemetry.android.mobile.config.ExportMode.CONDITIONAL -> {
                        BatchSpanProcessor.builder(baseSpanExporter)
                            .setScheduleDelay(3600, TimeUnit.SECONDS)
                            .setMaxQueueSize(10000)
                            .build()
                    }
                    io.opentelemetry.android.mobile.config.ExportMode.CONTINUOUS -> {
                        BatchSpanProcessor.builder(baseSpanExporter)
                            .setScheduleDelay(config.traceExportIntervalSeconds, TimeUnit.SECONDS)
                            .build()
                    }
                    io.opentelemetry.android.mobile.config.ExportMode.HYBRID -> {
                        BatchSpanProcessor.builder(baseSpanExporter)
                            .setScheduleDelay(3600, TimeUnit.SECONDS)
                            .setMaxQueueSize(10000)
                            .build()
                    }
                }
            )
            .build()

        // Create mobile log processor with ring buffer
        mobileProcessor = MobileLogRecordProcessor.builder(context)
            .setExporter(retryableExporter)
            .setConfig(config)
            .setMeter(meterProvider.get("io.opentelemetry.android.mobile.device-metrics"))
            .build()

        // Build SDK Logger Provider
        sdkLoggerProvider = SdkLoggerProvider.builder()
            .setResource(resource)
            .addLogRecordProcessor(mobileProcessor)
            .build()

        // Build OpenTelemetry SDK with logging, tracing, metrics, and W3C context propagation
        openTelemetrySdk = OpenTelemetrySdk.builder()
            .setLoggerProvider(sdkLoggerProvider)
            .setTracerProvider(tracerProvider)
            .setMeterProvider(meterProvider)
            .setPropagators(io.opentelemetry.context.propagation.ContextPropagators.create(
                io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance()
            ))
            .build()

        if (config.exportMode == io.opentelemetry.android.mobile.config.ExportMode.HYBRID) {
            mobileProcessor.heartbeatLogger = sdkLoggerProvider.get("io.opentelemetry.android.mobile.heartbeat")
            mobileProcessor.spanFlushHook = {
                openTelemetrySdk.sdkTracerProvider.forceFlush()
            }
        }
    }

    override fun get(instrumentationScopeName: String): Logger {
        return sdkLoggerProvider.get(instrumentationScopeName)
    }

    override fun loggerBuilder(instrumentationScopeName: String): io.opentelemetry.api.logs.LoggerBuilder {
        return sdkLoggerProvider.loggerBuilder(instrumentationScopeName)
    }

    fun getDeviceId(): String = deviceId

    fun getMobileProcessor(): MobileLogRecordProcessor = mobileProcessor

    fun getOpenTelemetrySdk(): OpenTelemetrySdk = openTelemetrySdk

    fun setSamplingRate(rate: Double, durationMinutes: Int? = null): Boolean {
        return if (sampler is DynamicSampler) {
            sampler.setSamplingRate(rate, durationMinutes)
            true
        } else {
            false
        }
    }

    fun resetSamplingToBaseline(): Boolean {
        return if (sampler is DynamicSampler) {
            sampler.resetToBaseline()
            true
        } else {
            false
        }
    }

    fun getCurrentSamplingRate(): Double? {
        return if (sampler is DynamicSampler) {
            sampler.getCurrentSamplingRate()
        } else {
            null
        }
    }

    fun forceFlush(timeoutSeconds: Long = 30): CompletableResultCode {
        val logResult    = sdkLoggerProvider.forceFlush().join(timeoutSeconds, TimeUnit.SECONDS)
        val traceResult  = openTelemetrySdk.sdkTracerProvider.forceFlush().join(timeoutSeconds, TimeUnit.SECONDS)
        val metricResult = openTelemetrySdk.sdkMeterProvider.forceFlush().join(timeoutSeconds, TimeUnit.SECONDS)
        return CompletableResultCode.ofAll(listOf(logResult, traceResult, metricResult))
            .join(timeoutSeconds, TimeUnit.SECONDS)
    }

    fun shutdown(timeoutSeconds: Long = 30): CompletableResultCode {
        return openTelemetrySdk.sdkLoggerProvider.shutdown()
            .join(timeoutSeconds, TimeUnit.SECONDS)
            .also {
                openTelemetrySdk.sdkTracerProvider.shutdown().join(timeoutSeconds, TimeUnit.SECONDS)
                openTelemetrySdk.sdkMeterProvider.shutdown().join(timeoutSeconds, TimeUnit.SECONDS)
            }
    }

    companion object {
        private const val PREFS_NAME = "otel_mobile_prefs"
        private const val KEY_DEVICE_ID = "device_id"

        @Volatile
        private var instance: MobileLoggerProvider? = null

        fun getInstance(
            context: Context,
            config: MobileConfig,
            customizers: ExporterCustomizers = ExporterCustomizers()
        ): MobileLoggerProvider {
            return instance ?: synchronized(this) {
                instance ?: MobileLoggerProvider(context.applicationContext, config, customizers)
                    .also { instance = it }
            }
        }

        fun getInstanceOrNull(): MobileLoggerProvider? = instance

        /** Reset singleton for test isolation. Only call from androidTest. */
        @androidx.annotation.VisibleForTesting
        fun resetForTesting() {
            instance?.shutdown()
            instance = null
        }

        private fun getOrCreateDeviceId(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_DEVICE_ID, null) ?: run {
                val newId = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
                newId
            }
        }
    }
}
