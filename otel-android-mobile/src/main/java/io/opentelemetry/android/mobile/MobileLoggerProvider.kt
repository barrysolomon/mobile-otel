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
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter
import io.opentelemetry.sdk.resources.Resource
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Mobile-optimized OpenTelemetry Logger Provider with ring buffer and conditional export.
 *
 * This provider extends the standard OpenTelemetry SDK with mobile-specific features:
 * - Two-tier ring buffer (RAM + disk) for offline support
 * - Policy-based conditional export to reduce bandwidth
 * - Device ID correlation for session tracking
 * - Crash recovery with persisted events
 *
 * Usage:
 * ```kotlin
 * val config = MobileConfig(
 *     serviceName = "my-app",
 *     serviceVersion = "1.0.0",
 *     collectorEndpoint = "https://collector.example.com:4317"
 * )
 * val provider = MobileLoggerProvider.getInstance(context, config)
 * val logger = provider.getLogger("my-component")
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

    init {
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

        // Create OTLP exporter
        val otlpExporter = OtlpGrpcLogRecordExporter.builder()
            .setEndpoint(config.collectorEndpoint)
            .setTimeout(config.exportTimeoutSeconds, TimeUnit.SECONDS)
            .apply {
                config.headers?.forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()

        // Wrap exporter with retry logic
        val retryableExporter = io.opentelemetry.android.mobile.buffering.RetryableExporter(
            delegate = otlpExporter,
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

        // Build OpenTelemetry SDK
        openTelemetrySdk = OpenTelemetrySdk.builder()
            .setLoggerProvider(sdkLoggerProvider)
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
     * Triggers an immediate flush of buffered events.
     *
     * This forces all buffered events to be exported immediately, regardless of policies.
     * Useful for critical events or app shutdown.
     *
     * @param timeoutSeconds Maximum time to wait for flush to complete
     * @return CompletableResultCode indicating flush success/failure
     */
    fun forceFlush(timeoutSeconds: Long = 30): CompletableResultCode {
        return sdkLoggerProvider.forceFlush().join(timeoutSeconds, TimeUnit.SECONDS)
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
        return sdkLoggerProvider.shutdown().join(timeoutSeconds, TimeUnit.SECONDS)
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
