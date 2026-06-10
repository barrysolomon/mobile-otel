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
 * OTLP wire protocol used to export telemetry to the collector / ingest endpoint.
 *
 * - [HTTP_PROTOBUF] — OTLP over HTTP/1.1 with a protobuf body (`Content-Type:
 *   application/x-protobuf`). Telemetry is POSTed to `<endpoint>/v1/{logs,traces,metrics}`.
 *   This is the **default** and matches the iOS SDK, so a single `collectorEndpoint`
 *   works for both platforms. It also traverses HTTPS-terminating reverse proxies and
 *   PaaS load balancers (which speak HTTP/1.1, not HTTP/2 gRPC) without special config —
 *   the common deployment behind managed ingress.
 * - [GRPC] — OTLP over gRPC (HTTP/2) to a single endpoint, typically `:4317`. Lower
 *   per-batch overhead on high-volume pipelines and enterprise collectors that expose a
 *   gRPC receiver. Requires an endpoint that terminates gRPC end-to-end; most HTTPS
 *   proxies cannot forward it.
 *
 * @see MobileConfig.protocol
 */
@Incubating
enum class OtlpProtocol {
    /**
     * OTLP/HTTP with protobuf payload. Exports to `<endpoint>/v1/{logs,traces,metrics}`.
     * Default — matches iOS and works through HTTPS proxies / PaaS ingress.
     */
    HTTP_PROTOBUF,

    /** OTLP/gRPC to a single endpoint (typically `:4317`). */
    GRPC
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
    /**
     * OTLP wire protocol used to export telemetry. Defaults to
     * [OtlpProtocol.HTTP_PROTOBUF] to match the iOS SDK (so the same
     * `collectorEndpoint` works for both platforms) and to traverse
     * HTTPS-terminating proxies / PaaS ingress that cannot forward gRPC.
     *
     * With [OtlpProtocol.HTTP_PROTOBUF] the SDK POSTs to
     * `<collectorEndpoint>/v1/logs`, `/v1/traces`, and `/v1/metrics`
     * (the per-signal suffix is appended automatically; if the endpoint
     * already ends in the right `/v1/<signal>` path it is left untouched).
     *
     * With [OtlpProtocol.GRPC] the SDK exports OTLP/gRPC to the single
     * `collectorEndpoint` (typically a `:4317` gRPC port) — the previous
     * behaviour. Use this only when your endpoint terminates gRPC
     * end-to-end.
     */
    val protocol: OtlpProtocol = OtlpProtocol.HTTP_PROTOBUF,
    val exportMode: ExportMode = ExportMode.HYBRID,
    val uiTelemetryMode: UiTelemetryMode = UiTelemetryMode.EVENTS,
    val textInputConfig: io.opentelemetry.android.mobile.instrumentation.TextInputConfig = io.opentelemetry.android.mobile.instrumentation.TextInputConfig(),
    val traceExportIntervalSeconds: Long = 30,
    val metricExportIntervalSeconds: Long = 60,
    val predictionIntervalSeconds: Long = 30,
    val ramBufferSize: Int = 5000,
    /**
     * Total-byte budget for the RAM ring buffer (SDK_SAFETY non-negotiable #3,
     * iOS parity with `RAMEventBuffer.maxTotalBytes`). When adding an event
     * would push the cumulative estimated size over this budget, oldest events
     * are overflowed to disk (FIFO) until the buffer is back under budget. This
     * caps RAM independently of [ramBufferSize] (the count cap), so a handful of
     * large screenshot/wireframe events can't balloon memory. Default 10 MB. */
    val ramBufferMaxTotalBytes: Long = 10L * 1024 * 1024,
    /**
     * Per-event byte cap for the RAM ring buffer (iOS parity with
     * `RAMEventBuffer.maxEventBytes`). A single event whose estimated size
     * exceeds this is dropped and counted (see [MobileLogRecordProcessor]
     * `droppedOversizeCount` / the `buffer.ram.dropped_oversize` gauge) rather
     * than buffered. Default 256 KB. */
    val ramBufferMaxEventBytes: Int = 256 * 1024,
    val diskBufferMb: Int = 50,
    val diskBufferTtlHours: Int = 24,
    /**
     * When true (the default), the on-disk telemetry buffer
     * (`otel_log_buffer.db`) is encrypted at rest using SQLCipher with a
     * passphrase wrapped by an Android Keystore key. This brings Android to
     * parity with iOS NSFileProtection: persisted spans/logs/bodies — which can
     * contain PII — are unreadable on rooted or backed-up devices.
     *
     * **Default ON, with crash-safe migration.** Enabling encryption on an
     * existing cleartext buffer (or any open failure: invalidated Keystore key,
     * corrupt file, missing native libs) NEVER crashes the host. The buffer is
     * transparently recreated, and if SQLCipher/Keystore are fundamentally
     * unavailable on the device the buffer degrades to cleartext rather than
     * failing. The one-time cost of enabling encryption is dropping any
     * already-buffered (TTL-bounded, best-effort) telemetry on first launch.
     *
     * Set to `false` to keep the buffer cleartext (e.g. to avoid the SQLCipher
     * native-library size cost where at-rest PII risk is not a concern).
     */
    @Incubating val encryptDiskBufferAtRest: Boolean = true,
    val exportTimeoutSeconds: Long = 30,
    /**
     * Enables polling the collector/control-plane `/config` endpoint for
     * mobile policy DSL updates. Set this to false when `collectorEndpoint`
     * points directly at a plain OTLP ingest endpoint, since OTLP receivers do
     * not serve policy config and will usually return 4xx responses.
     */
    @Incubating val remoteConfigEnabled: Boolean = true,
    val configPollIntervalSeconds: Long = 300,
    val maxExportRetries: Int = 3,
    val headers: Map<String, String>? = null,
    /**
     * Extra resource attributes merged into the SDK's built-in resource.
     * Use this to add vendor- or framework-specific identifiers such as
     * `telemetry.distro.name` / `telemetry.distro.version` when wrapping
     * this SDK in a higher-level distribution (e.g. the React Native
     * bridge). Null entries and blank keys are ignored.
     */
    val extraResourceAttributes: Map<String, String>? = null,
    val attachContextAttributes: Boolean = false,
    val buildChannel: String? = null,
    /**
     * Name of the [android.content.SharedPreferences] file the SDK may read
     * to populate the optional user-demographic fields on
     * [io.opentelemetry.android.mobile.context.ContextSnapshot]
     * (`deviceType`, `userRegion`, `ageGroup`, `tier`). When `null` (the
     * default), the SDK reads no preferences and all four fields remain
     * `null` — preferred for any app that does not need demographic
     * segmentation.
     *
     * **Privacy note (SR-024):** age group and region in combination with
     * other captured context can constitute quasi-PII under some
     * regulatory regimes (GDPR, CPRA). Opt in only when you have a real
     * use case and have considered consent / data-minimization.
     *
     * **Migration (SR-011):** earlier SDK versions hard-coded the demo
     * app's preferences name (`demo_app_prefs`). Apps that relied on that
     * implicit behavior must now set this explicitly.
     *
     * Expected key names inside the prefs file:
     *   `user_device_type`, `user_region`, `user_age_group`, `user_tier`.
     */
    val userContextPrefsName: String? = null,
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
    @Incubating val wireframeConfig: WireframeConfig = WireframeConfig(enabled = false),
    /** Incubating — offline disk budget management. Controls max disk usage when offline. */
    @Incubating val offlineBudgetConfig: OfflineBudgetConfig = OfflineBudgetConfig.default(),
    /** Incubating — controls what gets buffered when offline. */
    @Incubating val offlinePolicy: OfflinePolicy = OfflinePolicy.BUFFER_ALL,
    /**
     * Incubating — when true, the app is the authority for screen identity: it
     * calls [io.opentelemetry.android.mobile.OTelMobile.screenView] per logical
     * (e.g. Compose) screen, and the SDK suppresses host-Activity screen-views
     * and filters out `screen.render` spans that would carry only the Activity
     * name (e.g. the cold-launch first frame). Default `false` keeps that render
     * as legitimate startup telemetry. Intended for single-Activity Compose apps.
     */
    @Incubating val appManagedScreens: Boolean = false
) {
    init {
        require(serviceName.isNotBlank()) { "serviceName must not be blank" }
        require(serviceVersion.isNotBlank()) { "serviceVersion must not be blank" }
        require(collectorEndpoint.isNotBlank()) { "collectorEndpoint must not be blank" }
        require(traceExportIntervalSeconds > 0) { "traceExportIntervalSeconds must be positive" }
        require(metricExportIntervalSeconds > 0) { "metricExportIntervalSeconds must be positive" }
        require(predictionIntervalSeconds > 0) { "predictionIntervalSeconds must be positive" }
        require(ramBufferSize in 1..100_000) { "ramBufferSize must be between 1 and 100,000" }
        require(ramBufferMaxEventBytes > 0) { "ramBufferMaxEventBytes must be positive" }
        require(ramBufferMaxTotalBytes >= ramBufferMaxEventBytes) {
            "ramBufferMaxTotalBytes ($ramBufferMaxTotalBytes) must be >= ramBufferMaxEventBytes ($ramBufferMaxEventBytes)"
        }
        require(diskBufferMb in 1..500) { "diskBufferMb must be between 1 and 500" }
        require(diskBufferTtlHours in 1..168) { "diskBufferTtlHours must be between 1 and 168 (7 days)" }
        require(exportTimeoutSeconds > 0) { "exportTimeoutSeconds must be positive" }
        require(configPollIntervalSeconds > 0) { "configPollIntervalSeconds must be positive" }
        require(maxExportRetries in 0..10) { "maxExportRetries must be between 0 and 10" }

        // Enforce HTTPS for the collector endpoint. localhost / emulator-loopback
        // (10.0.2.2) stay exempt for local development. For any other cleartext
        // http:// endpoint, log a PROMINENT ERROR — telemetry (including the auth
        // token in headers) would otherwise travel in plaintext over the network.
        //
        // Prime directive: we do NOT hard-crash the host on misconfiguration; we
        // log loudly and continue. (Cert pinning is intentionally out of scope —
        // that's a feature, not a config-validation fix.)
        val endpoint = collectorEndpoint.lowercase()
        if (!endpoint.startsWith("https://") && !isLocalhostEndpoint(endpoint)) {
            Log.e("MobileConfig", "SECURITY: collectorEndpoint '$collectorEndpoint' uses cleartext " +
                "(non-HTTPS) transport to a non-localhost host. Telemetry AND the ingest auth " +
                "token will be sent in PLAINTEXT and can be intercepted. Use https:// in production. " +
                "Continuing with insecure transport — fix this before shipping.")
        }
    }

    companion object {
        private val IPV6_LOOPBACK_HOSTS = setOf(
            "::1",
            "0:0:0:0:0:0:0:1",
        )

        private fun isLocalhostEndpoint(endpoint: String): Boolean {
            val authority = endpoint.removePrefix("http://").substringBefore("/")
            val host = if (authority.startsWith("[")) {
                authority.substringAfter("[").substringBefore("]")
            } else {
                authority.substringBefore(":")
            }
            return host == "localhost" ||
                host == "127.0.0.1" ||
                host == "10.0.2.2" ||
                host in IPV6_LOOPBACK_HOSTS
        }

        @androidx.annotation.VisibleForTesting
        internal fun isLocalhostEndpointForTest(endpoint: String): Boolean =
            isLocalhostEndpoint(endpoint.lowercase())

        /**
         * Build the per-signal OTLP/HTTP ingest URL from a base
         * `collectorEndpoint`, mirroring the iOS `OTLPExporterFactory`
         * URL-building so Android and iOS resolve the same endpoint:
         *
         * - Trailing slashes on the base are collapsed before appending, so
         *   `https://host/` + `/v1/logs` yields `https://host/v1/logs` (not a
         *   doubled slash).
         * - If the endpoint already ends in the target `/v1/<signal>` path
         *   (with or without a trailing slash), it is returned unchanged — we
         *   never double-append.
         * - Any query string on the base endpoint is preserved.
         *
         * @param base the user-supplied `collectorEndpoint`.
         * @param signalPath the signal suffix, e.g. `/v1/logs`.
         */
        @JvmStatic
        fun buildOtlpHttpUrl(base: String, signalPath: String): String {
            val trimmed = base.trim()
            // Split off any query string so the suffix lands on the path, not after `?`.
            val queryIdx = trimmed.indexOf('?')
            val pathPart = if (queryIdx >= 0) trimmed.substring(0, queryIdx) else trimmed
            val query = if (queryIdx >= 0) trimmed.substring(queryIdx) else ""

            // Already suffixed (with or without a trailing slash) → leave untouched.
            val withoutTrailingSlash = pathPart.trimEnd('/')
            if (withoutTrailingSlash.endsWith(signalPath)) {
                return withoutTrailingSlash + query
            }

            return withoutTrailingSlash + signalPath + query
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
        private var protocol: OtlpProtocol = OtlpProtocol.HTTP_PROTOBUF
        private var exportMode: ExportMode = ExportMode.HYBRID
        private var uiTelemetryMode: UiTelemetryMode = UiTelemetryMode.EVENTS
        private var textInputConfig: io.opentelemetry.android.mobile.instrumentation.TextInputConfig = io.opentelemetry.android.mobile.instrumentation.TextInputConfig()
        private var traceExportIntervalSeconds: Long = 30
        private var metricExportIntervalSeconds: Long = 60
        private var predictionIntervalSeconds: Long = 30
        private var ramBufferSize: Int = 5000
        private var ramBufferMaxTotalBytes: Long = 10L * 1024 * 1024
        private var ramBufferMaxEventBytes: Int = 256 * 1024
        private var diskBufferMb: Int = 50
        private var diskBufferTtlHours: Int = 24
        private var encryptDiskBufferAtRest: Boolean = true
        private var exportTimeoutSeconds: Long = 30
        private var remoteConfigEnabled: Boolean = true
        private var configPollIntervalSeconds: Long = 300
        private var maxExportRetries: Int = 3
        private var headers: Map<String, String>? = null
        private var attachContextAttributes: Boolean = false
        private var buildChannel: String? = null
        private var userContextPrefsName: String? = null
        private var samplingConfig: SamplingConfig = SamplingConfig.dynamic(normalRate = 0.1, highPriorityRate = 1.0)
        private var deviceMetricsConfig: DeviceMetricsConfig = DeviceMetricsConfig.default()
        private var sessionConfig: SessionConfig = SessionConfig()
        private var breadcrumbConfig: BreadcrumbConfig = BreadcrumbConfig.default()
        private var vitalsConfig: VitalsConfig = VitalsConfig.default()
        private var networkConfig: NetworkConfig = NetworkConfig.default()
        private var errorConfig: ErrorConfig = ErrorConfig.default()
        private var screenshotConfig: ScreenshotConfig = ScreenshotConfig(enabled = false)
        private var wireframeConfig: WireframeConfig = WireframeConfig(enabled = false)
        private var appManagedScreens: Boolean = false

        fun setServiceName(serviceName: String) = apply { this.serviceName = serviceName }
        fun setServiceVersion(serviceVersion: String) = apply { this.serviceVersion = serviceVersion }
        fun setCollectorEndpoint(collectorEndpoint: String) = apply { this.collectorEndpoint = collectorEndpoint }
        /** See [MobileConfig.protocol]. Defaults to [OtlpProtocol.HTTP_PROTOBUF] (matches iOS). */
        fun setProtocol(protocol: OtlpProtocol) = apply { this.protocol = protocol }
        fun setExportMode(exportMode: ExportMode) = apply { this.exportMode = exportMode }
        fun setUiTelemetryMode(mode: UiTelemetryMode) = apply { this.uiTelemetryMode = mode }
        fun setTextInputConfig(config: io.opentelemetry.android.mobile.instrumentation.TextInputConfig) = apply { this.textInputConfig = config }
        fun setTraceExportIntervalSeconds(interval: Long) = apply { this.traceExportIntervalSeconds = interval }
        fun setMetricExportIntervalSeconds(interval: Long) = apply { this.metricExportIntervalSeconds = interval }
        fun setPredictionIntervalSeconds(seconds: Long) = apply { this.predictionIntervalSeconds = seconds }
        fun setRamBufferSize(ramBufferSize: Int) = apply { this.ramBufferSize = ramBufferSize }
        /** See [MobileConfig.ramBufferMaxTotalBytes]. Default 10 MB. */
        fun setRamBufferMaxTotalBytes(bytes: Long) = apply { this.ramBufferMaxTotalBytes = bytes }
        /** See [MobileConfig.ramBufferMaxEventBytes]. Default 256 KB. */
        fun setRamBufferMaxEventBytes(bytes: Int) = apply { this.ramBufferMaxEventBytes = bytes }
        fun setDiskBufferMb(diskBufferMb: Int) = apply { this.diskBufferMb = diskBufferMb }
        fun setDiskBufferTtlHours(diskBufferTtlHours: Int) = apply { this.diskBufferTtlHours = diskBufferTtlHours }
        /** See [MobileConfig.encryptDiskBufferAtRest]. Default `true`. */
        fun setEncryptDiskBufferAtRest(enabled: Boolean) = apply { this.encryptDiskBufferAtRest = enabled }
        fun setExportTimeoutSeconds(exportTimeoutSeconds: Long) = apply { this.exportTimeoutSeconds = exportTimeoutSeconds }
        fun setRemoteConfigEnabled(enabled: Boolean) = apply { this.remoteConfigEnabled = enabled }
        fun setConfigPollIntervalSeconds(configPollIntervalSeconds: Long) = apply { this.configPollIntervalSeconds = configPollIntervalSeconds }
        fun setMaxExportRetries(maxExportRetries: Int) = apply { this.maxExportRetries = maxExportRetries }
        fun setHeaders(headers: Map<String, String>) = apply { this.headers = headers }
        fun setAttachContextAttributes(enabled: Boolean) = apply { this.attachContextAttributes = enabled }
        fun setBuildChannel(channel: String) = apply { this.buildChannel = channel }
        /** See [MobileConfig.userContextPrefsName]. */
        fun setUserContextPrefsName(name: String?) = apply { this.userContextPrefsName = name }
        fun setSamplingConfig(config: SamplingConfig) = apply { this.samplingConfig = config }
        fun setDeviceMetricsConfig(config: DeviceMetricsConfig) = apply { this.deviceMetricsConfig = config }
        fun setSessionConfig(config: SessionConfig) = apply { this.sessionConfig = config }
        fun setBreadcrumbConfig(config: BreadcrumbConfig) = apply { this.breadcrumbConfig = config }
        fun setVitalsConfig(config: VitalsConfig) = apply { this.vitalsConfig = config }
        fun setNetworkConfig(config: NetworkConfig) = apply { this.networkConfig = config }
        fun setErrorConfig(config: ErrorConfig) = apply { this.errorConfig = config }
        fun setScreenshotConfig(config: ScreenshotConfig) = apply { this.screenshotConfig = config }
        fun setWireframeConfig(config: WireframeConfig) = apply { this.wireframeConfig = config }
        fun setAppManagedScreens(enabled: Boolean) = apply { this.appManagedScreens = enabled }

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
                protocol = protocol,
                exportMode = exportMode,
                uiTelemetryMode = uiTelemetryMode,
                textInputConfig = textInputConfig,
                traceExportIntervalSeconds = traceExportIntervalSeconds,
                metricExportIntervalSeconds = metricExportIntervalSeconds,
                predictionIntervalSeconds = predictionIntervalSeconds,
                ramBufferSize = ramBufferSize,
                ramBufferMaxTotalBytes = ramBufferMaxTotalBytes,
                ramBufferMaxEventBytes = ramBufferMaxEventBytes,
                diskBufferMb = diskBufferMb,
                diskBufferTtlHours = diskBufferTtlHours,
                encryptDiskBufferAtRest = encryptDiskBufferAtRest,
                exportTimeoutSeconds = exportTimeoutSeconds,
                remoteConfigEnabled = remoteConfigEnabled,
                configPollIntervalSeconds = configPollIntervalSeconds,
                maxExportRetries = maxExportRetries,
                headers = headers,
                attachContextAttributes = attachContextAttributes,
                buildChannel = buildChannel,
                userContextPrefsName = userContextPrefsName,
                samplingConfig = samplingConfig,
                deviceMetricsConfig = deviceMetricsConfig,
                sessionConfig = sessionConfig,
                breadcrumbConfig = breadcrumbConfig,
                vitalsConfig = vitalsConfig,
                networkConfig = networkConfig,
                errorConfig = errorConfig,
                screenshotConfig = screenshotConfig,
                wireframeConfig = wireframeConfig,
                appManagedScreens = appManagedScreens
            )
        }

        fun buildWithCustomizers(): Pair<MobileConfig, ExporterCustomizers> {
            return Pair(build(), exporterCustomizers.build())
        }
    }

}
