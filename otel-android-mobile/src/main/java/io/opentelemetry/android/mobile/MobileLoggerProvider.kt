package io.opentelemetry.android.mobile

import android.content.Context
import io.opentelemetry.android.mobile.buffering.MobileLogRecordProcessor
import io.opentelemetry.android.mobile.config.MobileConfig
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
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
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
class MobileLoggerProvider private constructor(
    private val context: Context,
    private val config: MobileConfig
) : LoggerProvider {

    private val sdkLoggerProvider: SdkLoggerProvider
    private val openTelemetrySdk: OpenTelemetrySdk
    private val deviceId: String = getOrCreateDeviceId(context)
    private val sampler: io.opentelemetry.sdk.trace.samplers.Sampler

    init {
        // Create sampler based on configuration
        sampler = SamplerFactory.createSampler(config.samplingConfig)

        // Build resource with mobile-specific attributes
        val resource = Resource.builder()
            .put("service.name", config.serviceName)
            .put("service.version", config.serviceVersion)
            .put("device.id", deviceId)
            .put("device.platform", "android")
            .put("device.os.version", android.os.Build.VERSION.RELEASE)
            .put("device.model", android.os.Build.MODEL)
            .put("device.manufacturer", android.os.Build.MANUFACTURER)
            .build()

        // Create OTLP gRPC exporter with headers
        val otlpExporter = OtlpGrpcLogRecordExporter.builder()
            .setEndpoint(config.collectorEndpoint)
            .setTimeout(config.exportTimeoutSeconds, TimeUnit.SECONDS)
            .apply {
                config.headers?.forEach { (key, value) ->
                    android.util.Log.d("MobileLoggerProvider", "Adding header: $key = $value")
                    addHeader(key, value)
                }
            }
            .build()

        // Wrap with logging for debugging
        val loggingExporter = io.opentelemetry.android.mobile.export.LoggingHttpExporter(
            delegate = otlpExporter,
            endpoint = config.collectorEndpoint
        )

        // Wrap exporter with retry logic
        val retryableExporter = io.opentelemetry.android.mobile.buffering.RetryableExporter(
            delegate = loggingExporter,
            maxRetries = config.maxExportRetries
        )

        // Create mobile log processor with ring buffer
        val mobileProcessor = MobileLogRecordProcessor.builder(context)
            .setExporter(retryableExporter)
            .setConfig(config)
            .build()

        // Build SDK Logger Provider
        sdkLoggerProvider = SdkLoggerProvider.builder()
            .setResource(resource)
            .addLogRecordProcessor(mobileProcessor)
            .build()

        // Create OTLP trace exporter
        val traceExporter = OtlpGrpcSpanExporter.builder()
            .setEndpoint(config.collectorEndpoint)
            .setTimeout(config.exportTimeoutSeconds, TimeUnit.SECONDS)
            .apply {
                config.headers?.forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()

        // Build SDK Tracer Provider with mode-appropriate configuration and sampling
        val tracerProvider = SdkTracerProvider.builder()
            .setResource(resource)
            .setSampler(sampler)
            .addSpanProcessor(
                when (config.exportMode) {
                    io.opentelemetry.android.mobile.config.ExportMode.CONDITIONAL -> {
                        // Only export on forceFlush(), not on schedule
                        BatchSpanProcessor.builder(traceExporter)
                            .setScheduleDelay(3600, TimeUnit.SECONDS)  // 1 hour (effectively disabled)
                            .setMaxQueueSize(10000)  // Large queue for buffering
                            .build()
                    }
                    io.opentelemetry.android.mobile.config.ExportMode.CONTINUOUS -> {
                        // Regular scheduled exports
                        BatchSpanProcessor.builder(traceExporter)
                            .setScheduleDelay(config.traceExportIntervalSeconds, TimeUnit.SECONDS)
                            .build()
                    }
                    io.opentelemetry.android.mobile.config.ExportMode.HYBRID -> {
                        // Moderate export frequency
                        BatchSpanProcessor.builder(traceExporter)
                            .setScheduleDelay(config.traceExportIntervalSeconds * 2, TimeUnit.SECONDS)
                            .build()
                    }
                }
            )
            .build()

        // Create OTLP metric exporter
        val metricExporter = OtlpGrpcMetricExporter.builder()
            .setEndpoint(config.collectorEndpoint)
            .setTimeout(config.exportTimeoutSeconds, TimeUnit.SECONDS)
            .apply {
                config.headers?.forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()

        // Build SDK Meter Provider with mode-appropriate configuration
        val meterProvider = SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(
                when (config.exportMode) {
                    io.opentelemetry.android.mobile.config.ExportMode.CONDITIONAL -> {
                        // Only export on forceFlush(), not on schedule
                        PeriodicMetricReader.builder(metricExporter)
                            .setInterval(3600, TimeUnit.SECONDS)  // 1 hour (effectively disabled)
                            .build()
                    }
                    io.opentelemetry.android.mobile.config.ExportMode.CONTINUOUS -> {
                        // Regular scheduled exports
                        PeriodicMetricReader.builder(metricExporter)
                            .setInterval(config.metricExportIntervalSeconds, TimeUnit.SECONDS)
                            .build()
                    }
                    io.opentelemetry.android.mobile.config.ExportMode.HYBRID -> {
                        // Moderate export frequency (2x the configured interval)
                        PeriodicMetricReader.builder(metricExporter)
                            .setInterval(config.metricExportIntervalSeconds * 2, TimeUnit.SECONDS)
                            .build()
                    }
                }
            )
            .build()

        // Build OpenTelemetry SDK with logging, tracing, and metrics
        openTelemetrySdk = OpenTelemetrySdk.builder()
            .setLoggerProvider(sdkLoggerProvider)
            .setTracerProvider(tracerProvider)
            .setMeterProvider(meterProvider)
            .build()
    }

    /**
     * Gets a logger for the specified instrumentation scope.
     *
     * @param instrumentationScopeName Name of the instrumentation scope (e.g., component name)
     * @return Logger instance
     */
    override fun get(instrumentationScopeName: String): Logger {
        return sdkLoggerProvider.get(instrumentationScopeName)
    }

    /**
     * Gets a logger builder for advanced configuration.
     *
     * @param instrumentationScopeName Name of the instrumentation scope
     * @return LoggerBuilder for additional configuration
     */
    override fun loggerBuilder(instrumentationScopeName: String): io.opentelemetry.api.logs.LoggerBuilder {
        return sdkLoggerProvider.loggerBuilder(instrumentationScopeName)
    }

    /**
     * Gets the device ID for correlation across sessions.
     *
     * @return Stable device identifier
     */
    fun getDeviceId(): String = deviceId

    /**
     * Gets the OpenTelemetry SDK instance for advanced usage.
     *
     * @return OpenTelemetry SDK
     */
    fun getOpenTelemetrySdk(): OpenTelemetrySdk = openTelemetrySdk

    /**
     * Sets the trace sampling rate at runtime (for dynamic sampler only).
     *
     * This is used by workflow actions to temporarily increase sampling after critical events.
     * Only works if the configured sampler is a DynamicSampler.
     *
     * Example workflow action: "Set Sampling Rate to 100% for 10 minutes after HTTP 500 error"
     *
     * @param rate Sampling rate (0.0 to 1.0)
     * @param durationMinutes Optional duration before reverting to baseline (null = permanent)
     * @return true if sampling was adjusted, false if sampler is not dynamic
     */
    fun setSamplingRate(rate: Double, durationMinutes: Int? = null): Boolean {
        return if (sampler is DynamicSampler) {
            sampler.setSamplingRate(rate, durationMinutes)
            true
        } else {
            false
        }
    }

    /**
     * Resets trace sampling to baseline rate (for dynamic sampler only).
     *
     * @return true if sampling was reset, false if sampler is not dynamic
     */
    fun resetSamplingToBaseline(): Boolean {
        return if (sampler is DynamicSampler) {
            sampler.resetToBaseline()
            true
        } else {
            false
        }
    }

    /**
     * Gets the current trace sampling rate (for dynamic sampler only).
     *
     * @return Current sampling rate, or null if sampler is not dynamic
     */
    fun getCurrentSamplingRate(): Double? {
        return if (sampler is DynamicSampler) {
            sampler.getCurrentSamplingRate()
        } else {
            null
        }
    }

    /**
     * Triggers an immediate flush of buffered events across all signals (logs, traces, metrics).
     *
     * This forces all buffered data to be exported immediately, regardless of policies or schedules.
     * Essential for:
     * - Conditional export mode (where scheduled exports are disabled)
     * - Critical events that must be captured
     * - App shutdown
     * - Workflow trigger actions
     *
     * @param timeoutSeconds Maximum time to wait for flush to complete
     * @return CompletableResultCode indicating flush success/failure
     */
    fun forceFlush(timeoutSeconds: Long = 30): CompletableResultCode {
        val logResult = sdkLoggerProvider.forceFlush().join(timeoutSeconds, TimeUnit.SECONDS)
        val traceResult = openTelemetrySdk.sdkTracerProvider.forceFlush().join(timeoutSeconds, TimeUnit.SECONDS)
        val metricResult = openTelemetrySdk.sdkMeterProvider.forceFlush().join(timeoutSeconds, TimeUnit.SECONDS)

        return CompletableResultCode.ofAll(listOf(logResult, traceResult, metricResult))
    }

    /**
     * Shuts down the provider and releases resources.
     *
     * This should be called when the provider is no longer needed (e.g., app shutdown).
     * After shutdown, the provider cannot be used.
     *
     * @param timeoutSeconds Maximum time to wait for shutdown to complete
     * @return CompletableResultCode indicating shutdown success/failure
     */
    fun shutdown(timeoutSeconds: Long = 30): CompletableResultCode {
        // Shutdown the entire OpenTelemetry SDK (includes logger, tracer, and meter providers)
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

        /**
         * Gets or creates the singleton MobileLoggerProvider instance.
         *
         * This uses double-checked locking for thread-safe singleton initialization.
         *
         * @param context Application context
         * @param config Mobile configuration
         * @return Singleton MobileLoggerProvider instance
         */
        fun getInstance(context: Context, config: MobileConfig): MobileLoggerProvider {
            return instance ?: synchronized(this) {
                instance ?: MobileLoggerProvider(
                    context.applicationContext,
                    config
                ).also { instance = it }
            }
        }

        /**
         * Gets the current instance if initialized, or null.
         *
         * @return Current MobileLoggerProvider instance or null
         */
        fun getInstanceOrNull(): MobileLoggerProvider? = instance

        /**
         * Gets or creates a stable device ID for correlation.
         *
         * The device ID is persisted in SharedPreferences and remains stable across app restarts.
         * This is used for correlating events across sessions and for demo_run_id generation.
         *
         * @param context Application context
         * @return Stable UUID-based device identifier
         */
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
