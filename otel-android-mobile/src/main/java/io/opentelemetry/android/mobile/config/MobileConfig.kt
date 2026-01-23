package io.opentelemetry.android.mobile.config

import io.opentelemetry.android.mobile.sampling.SamplingConfig
import io.opentelemetry.android.mobile.metrics.DeviceMetricsConfig

/**
 * Export mode for telemetry data.
 */
enum class ExportMode {
    /**
     * Only export when triggered by conditions (errors, low memory, battery drain, etc.).
     * Most battery-efficient, minimal bandwidth usage.
     */
    CONDITIONAL,

    /**
     * Export on fixed schedule regardless of conditions.
     * Higher battery usage, consistent data flow.
     */
    CONTINUOUS,

    /**
     * Hybrid: regular lightweight exports + conditional full dumps.
     * Balanced approach for production apps.
     */
    HYBRID
}

/**
 * Configuration for MobileLoggerProvider.
 *
 * Defines all configuration options for the mobile observability system including:
 * - Service identification
 * - OTLP endpoint and credentials
 * - Buffer sizes and retention
 * - Export behavior and modes
 *
 * Usage:
 * ```kotlin
 * val config = MobileConfig(
 *     serviceName = "my-mobile-app",
 *     serviceVersion = "1.0.0",
 *     collectorEndpoint = "https://otel-collector.example.com:4317",
 *     exportMode = ExportMode.CONDITIONAL,
 *     ramBufferSize = 5000,
 *     diskBufferMb = 50
 * )
 * ```
 *
 * @property serviceName Logical name of the service (e.g., "my-mobile-app")
 * @property serviceVersion Version of the service (e.g., "1.0.0")
 * @property collectorEndpoint OTLP/gRPC endpoint for the collector (e.g., "https://host:4317")
 * @property exportMode Export behavior mode (default: CONDITIONAL for battery efficiency)
 * @property traceExportIntervalSeconds Export interval for traces in CONTINUOUS mode (default: 30)
 * @property metricExportIntervalSeconds Export interval for metrics in CONTINUOUS mode (default: 60)
 * @property ramBufferSize Maximum number of events to buffer in RAM (default: 5000)
 * @property diskBufferMb Maximum disk space for persisted events in MB (default: 50)
 * @property diskBufferTtlHours Time-to-live for disk-persisted events in hours (default: 24)
 * @property exportTimeoutSeconds Timeout for OTLP export operations in seconds (default: 30)
 * @property configPollIntervalSeconds Interval for polling configuration updates (default: 300)
 * @property maxExportRetries Maximum number of retry attempts when export fails (default: 3)
 * @property headers Optional headers to include in OTLP requests (e.g., authentication)
 * @property attachContextAttributes Whether to attach geo/device context attributes to exported logs (default: false)
 * @property buildChannel Build channel for the app: prod/beta/internal/unknown (default: "unknown")
 * @property samplingConfig Sampling configuration for traces (default: 10% dynamic sampling with 100% for high-priority)
 * @property deviceMetricsConfig Configuration for which device metrics to capture on triggers (default: all enabled except location)
 */
data class MobileConfig(
    val serviceName: String,
    val serviceVersion: String,
    val collectorEndpoint: String,
    val exportMode: ExportMode = ExportMode.CONDITIONAL,
    val traceExportIntervalSeconds: Long = 30,
    val metricExportIntervalSeconds: Long = 60,
    val ramBufferSize: Int = 5000,
    val diskBufferMb: Int = 50,
    val diskBufferTtlHours: Int = 24,
    val exportTimeoutSeconds: Long = 30,
    val configPollIntervalSeconds: Long = 300,
    val maxExportRetries: Int = 3,
    val headers: Map<String, String>? = null,
    val attachContextAttributes: Boolean = false,
    val buildChannel: String? = null,
    val samplingConfig: SamplingConfig = SamplingConfig.dynamic(normalRate = 0.1, highPriorityRate = 1.0),
    val deviceMetricsConfig: DeviceMetricsConfig = DeviceMetricsConfig.default()
) {
    init {
        require(serviceName.isNotBlank()) { "serviceName must not be blank" }
        require(serviceVersion.isNotBlank()) { "serviceVersion must not be blank" }
        require(collectorEndpoint.isNotBlank()) { "collectorEndpoint must not be blank" }
        require(traceExportIntervalSeconds > 0) { "traceExportIntervalSeconds must be positive" }
        require(metricExportIntervalSeconds > 0) { "metricExportIntervalSeconds must be positive" }
        require(ramBufferSize > 0) { "ramBufferSize must be positive" }
        require(diskBufferMb > 0) { "diskBufferMb must be positive" }
        require(diskBufferTtlHours > 0) { "diskBufferTtlHours must be positive" }
        require(exportTimeoutSeconds > 0) { "exportTimeoutSeconds must be positive" }
        require(configPollIntervalSeconds > 0) { "configPollIntervalSeconds must be positive" }
        require(maxExportRetries >= 0) { "maxExportRetries must be non-negative" }
    }

    /**
     * Builder for MobileConfig with fluent API.
     *
     * Usage:
     * ```kotlin
     * val config = MobileConfig.builder()
     *     .setServiceName("my-app")
     *     .setServiceVersion("1.0.0")
     *     .setCollectorEndpoint("https://collector.example.com:4317")
     *     .setRamBufferSize(10000)
     *     .build()
     * ```
     */
    class Builder {
        private var serviceName: String? = null
        private var serviceVersion: String? = null
        private var collectorEndpoint: String? = null
        private var exportMode: ExportMode = ExportMode.CONDITIONAL
        private var traceExportIntervalSeconds: Long = 30
        private var metricExportIntervalSeconds: Long = 60
        private var ramBufferSize: Int = 5000
        private var diskBufferMb: Int = 50
        private var diskBufferTtlHours: Int = 24
        private var exportTimeoutSeconds: Long = 30
        private var configPollIntervalSeconds: Long = 300
        private var maxExportRetries: Int = 3
        private var headers: Map<String, String>? = null
        private var attachContextAttributes: Boolean = false
        private var buildChannel: String? = null
        private var samplingConfig: SamplingConfig = SamplingConfig.dynamic(normalRate = 0.1, highPriorityRate = 1.0)
        private var deviceMetricsConfig: DeviceMetricsConfig = DeviceMetricsConfig.default()

        fun setServiceName(serviceName: String) = apply { this.serviceName = serviceName }
        fun setServiceVersion(serviceVersion: String) = apply { this.serviceVersion = serviceVersion }
        fun setCollectorEndpoint(collectorEndpoint: String) = apply { this.collectorEndpoint = collectorEndpoint }
        fun setExportMode(exportMode: ExportMode) = apply { this.exportMode = exportMode }
        fun setTraceExportIntervalSeconds(interval: Long) = apply { this.traceExportIntervalSeconds = interval }
        fun setMetricExportIntervalSeconds(interval: Long) = apply { this.metricExportIntervalSeconds = interval }
        fun setRamBufferSize(ramBufferSize: Int) = apply { this.ramBufferSize = ramBufferSize }
        fun setDiskBufferMb(diskBufferMb: Int) = apply { this.diskBufferMb = diskBufferMb }
        fun setDiskBufferTtlHours(diskBufferTtlHours: Int) = apply { this.diskBufferTtlHours = diskBufferTtlHours }
        fun setExportTimeoutSeconds(exportTimeoutSeconds: Long) = apply { this.exportTimeoutSeconds = exportTimeoutSeconds }
        fun setConfigPollIntervalSeconds(configPollIntervalSeconds: Long) = apply { this.configPollIntervalSeconds = configPollIntervalSeconds }
        fun setMaxExportRetries(maxExportRetries: Int) = apply { this.maxExportRetries = maxExportRetries }
        fun setHeaders(headers: Map<String, String>) = apply { this.headers = headers }
        fun setAttachContextAttributes(enabled: Boolean) = apply { this.attachContextAttributes = enabled }
        fun setBuildChannel(channel: String) = apply { this.buildChannel = channel }
        fun setSamplingConfig(config: SamplingConfig) = apply { this.samplingConfig = config }
        fun setDeviceMetricsConfig(config: DeviceMetricsConfig) = apply { this.deviceMetricsConfig = config }

        fun build(): MobileConfig {
            return MobileConfig(
                serviceName = requireNotNull(serviceName) { "serviceName is required" },
                serviceVersion = requireNotNull(serviceVersion) { "serviceVersion is required" },
                collectorEndpoint = requireNotNull(collectorEndpoint) { "collectorEndpoint is required" },
                exportMode = exportMode,
                traceExportIntervalSeconds = traceExportIntervalSeconds,
                metricExportIntervalSeconds = metricExportIntervalSeconds,
                ramBufferSize = ramBufferSize,
                diskBufferMb = diskBufferMb,
                diskBufferTtlHours = diskBufferTtlHours,
                exportTimeoutSeconds = exportTimeoutSeconds,
                configPollIntervalSeconds = configPollIntervalSeconds,
                maxExportRetries = maxExportRetries,
                headers = headers,
                attachContextAttributes = attachContextAttributes,
                buildChannel = buildChannel,
                samplingConfig = samplingConfig,
                deviceMetricsConfig = deviceMetricsConfig
            )
        }
    }

    companion object {
        /**
         * Creates a new builder for MobileConfig.
         */
        fun builder(): Builder = Builder()
    }
}
