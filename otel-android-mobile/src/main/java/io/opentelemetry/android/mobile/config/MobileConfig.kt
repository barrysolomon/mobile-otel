/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.config

import android.util.Log
import io.opentelemetry.android.mobile.instrumentation.Incubating
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.android.mobile.sampling.SamplingConfig
import io.opentelemetry.android.mobile.metrics.DeviceMetricsConfig
import io.opentelemetry.android.mobile.core.SessionConfig
import io.opentelemetry.android.mobile.breadcrumb.BreadcrumbConfig
import io.opentelemetry.android.mobile.vitals.VitalsConfig
import io.opentelemetry.android.mobile.network.NetworkConfig
import io.opentelemetry.android.mobile.errors.ErrorConfig
import io.opentelemetry.android.mobile.instrumentation.ScreenshotConfig
import io.opentelemetry.android.mobile.instrumentation.WireframeConfig

/**
 * Controls whether UI interactions (taps, scrolls, back presses, screen views, text input)
 * are emitted as OTel log events, as nested child spans under the current page span, or both.
 *
 * - [EVENTS] — OTel log records only. Each interaction appears in the Logs section of your
 *   backend. This is the default and matches the classic mobile event model.
 * - [SPANS] — Zero-duration child spans only. Each interaction is nested under the active
 *   `page.<ScreenName>` span, making the full user journey visible in the Traces section.
 * - [BOTH] — Emit both a log record and a child span for every interaction.
 *
 * Usage:
 * ```kotlin
 * val config = MobileConfig(
 *     serviceName = "my-app",
 *     serviceVersion = "1.0.0",
 *     collectorEndpoint = "https://collector.example.com:4317",
 *     uiTelemetryMode = UiTelemetryMode.SPANS
 * )
 * ```
 */
@Incubating
enum class UiTelemetryMode {
    /** Emit UI interactions as OTel log records (default). */
    EVENTS,
    /** Emit UI interactions as zero-duration child spans nested under the page span. */
    SPANS,
    /** Emit UI interactions as both log records and child spans. */
    BOTH
}

/**
 * Export mode for telemetry data.
 */
@Incubating
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
 */
@Incubating
data class MobileConfig(
    val serviceName: String,
    val serviceVersion: String,
    val collectorEndpoint: String,
    val exportMode: ExportMode = ExportMode.CONDITIONAL,
    val uiTelemetryMode: UiTelemetryMode = UiTelemetryMode.EVENTS,
    val textInputConfig: io.opentelemetry.android.mobile.instrumentation.TextInputConfig = io.opentelemetry.android.mobile.instrumentation.TextInputConfig(),
    val traceExportIntervalSeconds: Long = 30,
    val metricExportIntervalSeconds: Long = 60,
    val predictionIntervalSeconds: Long = 30,
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
    val deviceMetricsConfig: DeviceMetricsConfig = DeviceMetricsConfig.default(),
    val sessionConfig: SessionConfig = SessionConfig(),
    val breadcrumbConfig: BreadcrumbConfig = BreadcrumbConfig.default(),
    val vitalsConfig: VitalsConfig = VitalsConfig.default(),
    val networkConfig: NetworkConfig = NetworkConfig.default(),
    val errorConfig: ErrorConfig = ErrorConfig.default(),
    /** Incubating — screenshot capture is not part of the OTel spec. Disabled by default. */
    @Incubating val screenshotConfig: ScreenshotConfig = ScreenshotConfig(enabled = false),
    /** Incubating — wireframe capture is not part of the OTel spec. Disabled by default. */
    @Incubating val wireframeConfig: WireframeConfig = WireframeConfig(enabled = false)
) {
    init {
        require(serviceName.isNotBlank()) { "serviceName must not be blank" }
        require(serviceVersion.isNotBlank()) { "serviceVersion must not be blank" }
        require(collectorEndpoint.isNotBlank()) { "collectorEndpoint must not be blank" }
        require(traceExportIntervalSeconds > 0) { "traceExportIntervalSeconds must be positive" }
        require(metricExportIntervalSeconds > 0) { "metricExportIntervalSeconds must be positive" }
        require(predictionIntervalSeconds > 0) { "predictionIntervalSeconds must be positive" }
        require(ramBufferSize in 1..100_000) { "ramBufferSize must be between 1 and 100,000" }
        require(diskBufferMb in 1..500) { "diskBufferMb must be between 1 and 500" }
        require(diskBufferTtlHours in 1..168) { "diskBufferTtlHours must be between 1 and 168 (7 days)" }
        require(exportTimeoutSeconds > 0) { "exportTimeoutSeconds must be positive" }
        require(configPollIntervalSeconds > 0) { "configPollIntervalSeconds must be positive" }
        require(maxExportRetries in 0..10) { "maxExportRetries must be between 0 and 10" }

        // Warn if collector endpoint is not using TLS (allow localhost for development)
        val endpoint = collectorEndpoint.lowercase()
        if (!endpoint.startsWith("https://") && !isLocalhostEndpoint(endpoint)) {
            Log.w("MobileConfig", "collectorEndpoint is not using HTTPS. " +
                "Telemetry data will be transmitted in plaintext. " +
                "Use https:// in production to protect data in transit.")
        }
    }

    companion object {
        private fun isLocalhostEndpoint(endpoint: String): Boolean {
            val host = endpoint.removePrefix("http://").substringBefore(":")
                .substringBefore("/")
            return host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2"
        }

        fun builder(): Builder = Builder()
    }

    /**
     * Builder for MobileConfig with fluent API.
     */
    class Builder {
        private val exporterCustomizers = ExporterCustomizers.Builder()
        private var serviceName: String? = null
        private var serviceVersion: String? = null
        private var collectorEndpoint: String? = null
        private var exportMode: ExportMode = ExportMode.CONDITIONAL
        private var uiTelemetryMode: UiTelemetryMode = UiTelemetryMode.EVENTS
        private var textInputConfig: io.opentelemetry.android.mobile.instrumentation.TextInputConfig = io.opentelemetry.android.mobile.instrumentation.TextInputConfig()
        private var traceExportIntervalSeconds: Long = 30
        private var metricExportIntervalSeconds: Long = 60
        private var predictionIntervalSeconds: Long = 30
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
        private var sessionConfig: SessionConfig = SessionConfig()
        private var breadcrumbConfig: BreadcrumbConfig = BreadcrumbConfig.default()
        private var vitalsConfig: VitalsConfig = VitalsConfig.default()
        private var networkConfig: NetworkConfig = NetworkConfig.default()
        private var errorConfig: ErrorConfig = ErrorConfig.default()
        private var screenshotConfig: ScreenshotConfig = ScreenshotConfig(enabled = false)
        private var wireframeConfig: WireframeConfig = WireframeConfig(enabled = false)

        fun setServiceName(serviceName: String) = apply { this.serviceName = serviceName }
        fun setServiceVersion(serviceVersion: String) = apply { this.serviceVersion = serviceVersion }
        fun setCollectorEndpoint(collectorEndpoint: String) = apply { this.collectorEndpoint = collectorEndpoint }
        fun setExportMode(exportMode: ExportMode) = apply { this.exportMode = exportMode }
        fun setUiTelemetryMode(mode: UiTelemetryMode) = apply { this.uiTelemetryMode = mode }
        fun setTextInputConfig(config: io.opentelemetry.android.mobile.instrumentation.TextInputConfig) = apply { this.textInputConfig = config }
        fun setTraceExportIntervalSeconds(interval: Long) = apply { this.traceExportIntervalSeconds = interval }
        fun setMetricExportIntervalSeconds(interval: Long) = apply { this.metricExportIntervalSeconds = interval }
        fun setPredictionIntervalSeconds(seconds: Long) = apply { this.predictionIntervalSeconds = seconds }
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
        fun setSessionConfig(config: SessionConfig) = apply { this.sessionConfig = config }
        fun setBreadcrumbConfig(config: BreadcrumbConfig) = apply { this.breadcrumbConfig = config }
        fun setVitalsConfig(config: VitalsConfig) = apply { this.vitalsConfig = config }
        fun setNetworkConfig(config: NetworkConfig) = apply { this.networkConfig = config }
        fun setErrorConfig(config: ErrorConfig) = apply { this.errorConfig = config }
        fun setScreenshotConfig(config: ScreenshotConfig) = apply { this.screenshotConfig = config }
        fun setWireframeConfig(config: WireframeConfig) = apply { this.wireframeConfig = config }

        fun addLogExporterCustomizer(customizer: (LogRecordExporter) -> LogRecordExporter) = apply {
            exporterCustomizers.addLog(customizer)
        }
        fun addSpanExporterCustomizer(customizer: (SpanExporter) -> SpanExporter) = apply {
            exporterCustomizers.addSpan(customizer)
        }
        fun addMetricExporterCustomizer(customizer: (MetricExporter) -> MetricExporter) = apply {
            exporterCustomizers.addMetric(customizer)
        }

        fun build(): MobileConfig {
            return MobileConfig(
                serviceName = requireNotNull(serviceName) { "serviceName is required" },
                serviceVersion = requireNotNull(serviceVersion) { "serviceVersion is required" },
                collectorEndpoint = requireNotNull(collectorEndpoint) { "collectorEndpoint is required" },
                exportMode = exportMode,
                uiTelemetryMode = uiTelemetryMode,
                textInputConfig = textInputConfig,
                traceExportIntervalSeconds = traceExportIntervalSeconds,
                metricExportIntervalSeconds = metricExportIntervalSeconds,
                predictionIntervalSeconds = predictionIntervalSeconds,
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
                deviceMetricsConfig = deviceMetricsConfig,
                sessionConfig = sessionConfig,
                breadcrumbConfig = breadcrumbConfig,
                vitalsConfig = vitalsConfig,
                networkConfig = networkConfig,
                errorConfig = errorConfig,
                screenshotConfig = screenshotConfig,
                wireframeConfig = wireframeConfig
            )
        }

        fun buildWithCustomizers(): Pair<MobileConfig, ExporterCustomizers> {
            return Pair(build(), exporterCustomizers.build())
        }
    }

}
