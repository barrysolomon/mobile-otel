package io.opentelemetry.android.mobile.config

/**
 * Configuration for MobileLoggerProvider.
 *
 * Defines all configuration options for the mobile observability system including:
 * - Service identification
 * - OTLP endpoint and credentials
 * - Buffer sizes and retention
 * - Export behavior
 *
 * Usage:
 * ```kotlin
 * val config = MobileConfig(
 *     serviceName = "my-mobile-app",
 *     serviceVersion = "1.0.0",
 *     collectorEndpoint = "https://otel-collector.example.com:4317",
 *     ramBufferSize = 5000,
 *     diskBufferMb = 50
 * )
 * ```
 *
 * @property serviceName Logical name of the service (e.g., "my-mobile-app")
 * @property serviceVersion Version of the service (e.g., "1.0.0")
 * @property collectorEndpoint OTLP/gRPC endpoint for the collector (e.g., "https://host:4317")
 * @property ramBufferSize Maximum number of events to buffer in RAM (default: 5000)
 * @property diskBufferMb Maximum disk space for persisted events in MB (default: 50)
 * @property diskBufferTtlHours Time-to-live for disk-persisted events in hours (default: 24)
 * @property exportTimeoutSeconds Timeout for OTLP export operations in seconds (default: 30)
 * @property configPollIntervalSeconds Interval for polling configuration updates (default: 300)
 * @property maxExportRetries Maximum number of retry attempts when export fails (default: 3)
 * @property headers Optional headers to include in OTLP requests (e.g., authentication)
 */
data class MobileConfig(
    val serviceName: String,
    val serviceVersion: String,
    val collectorEndpoint: String,
    val ramBufferSize: Int = 5000,
    val diskBufferMb: Int = 50,
    val diskBufferTtlHours: Int = 24,
    val exportTimeoutSeconds: Long = 30,
    val configPollIntervalSeconds: Long = 300,
    val maxExportRetries: Int = 3,
    val headers: Map<String, String>? = null
) {
    init {
        require(serviceName.isNotBlank()) { "serviceName must not be blank" }
        require(serviceVersion.isNotBlank()) { "serviceVersion must not be blank" }
        require(collectorEndpoint.isNotBlank()) { "collectorEndpoint must not be blank" }
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
        private var ramBufferSize: Int = 5000
        private var diskBufferMb: Int = 50
        private var diskBufferTtlHours: Int = 24
        private var exportTimeoutSeconds: Long = 30
        private var configPollIntervalSeconds: Long = 300
        private var maxExportRetries: Int = 3
        private var headers: Map<String, String>? = null

        fun setServiceName(serviceName: String) = apply { this.serviceName = serviceName }
        fun setServiceVersion(serviceVersion: String) = apply { this.serviceVersion = serviceVersion }
        fun setCollectorEndpoint(collectorEndpoint: String) = apply { this.collectorEndpoint = collectorEndpoint }
        fun setRamBufferSize(ramBufferSize: Int) = apply { this.ramBufferSize = ramBufferSize }
        fun setDiskBufferMb(diskBufferMb: Int) = apply { this.diskBufferMb = diskBufferMb }
        fun setDiskBufferTtlHours(diskBufferTtlHours: Int) = apply { this.diskBufferTtlHours = diskBufferTtlHours }
        fun setExportTimeoutSeconds(exportTimeoutSeconds: Long) = apply { this.exportTimeoutSeconds = exportTimeoutSeconds }
        fun setConfigPollIntervalSeconds(configPollIntervalSeconds: Long) = apply { this.configPollIntervalSeconds = configPollIntervalSeconds }
        fun setMaxExportRetries(maxExportRetries: Int) = apply { this.maxExportRetries = maxExportRetries }
        fun setHeaders(headers: Map<String, String>) = apply { this.headers = headers }

        fun build(): MobileConfig {
            return MobileConfig(
                serviceName = requireNotNull(serviceName) { "serviceName is required" },
                serviceVersion = requireNotNull(serviceVersion) { "serviceVersion is required" },
                collectorEndpoint = requireNotNull(collectorEndpoint) { "collectorEndpoint is required" },
                ramBufferSize = ramBufferSize,
                diskBufferMb = diskBufferMb,
                diskBufferTtlHours = diskBufferTtlHours,
                exportTimeoutSeconds = exportTimeoutSeconds,
                configPollIntervalSeconds = configPollIntervalSeconds,
                maxExportRetries = maxExportRetries,
                headers = headers
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
