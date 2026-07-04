// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.config

import io.opentelemetry.android.mobile.breadcrumb.BreadcrumbConfig
import io.opentelemetry.android.mobile.core.SessionConfig
import io.opentelemetry.android.mobile.errors.ErrorConfig
import io.opentelemetry.android.mobile.instrumentation.OTelMobileBuilder
import io.opentelemetry.android.mobile.instrumentation.ScreenshotConfig
import io.opentelemetry.android.mobile.instrumentation.WireframeConfig
import io.opentelemetry.android.mobile.network.NetworkConfig
import io.opentelemetry.android.mobile.vitals.VitalsConfig

@DslMarker
annotation class MobileOtelDslMarker

@MobileOtelDslMarker
class MobileOtelDsl {
    private val serviceConfig = ServiceDsl()
    private val exportConfig = ExportDsl()
    private val bufferingConfig = BufferingDsl()
    private val sessionConfig = SessionDsl()
    private val customizerConfig = ExporterCustomizersDsl()
    private val instrumentationsConfig = InstrumentationsDsl()

    // Top-level settings exposed directly
    var uiTelemetryMode: UiTelemetryMode = UiTelemetryMode.EVENTS

    /**
     * Extra resource attributes attached to every emitted record.
     * Useful for test cell IDs, deployment tags, and similar metadata
     * that the OTel `service.*` and `device.*` defaults don't cover.
     * Null entries and blank keys are ignored downstream.
     */
    var extraResourceAttributes: Map<String, String>? = null
    private var screenshotConfig = ScreenshotConfig(enabled = false)
    private var wireframeConfig = WireframeConfig(enabled = false)
    private var networkConfig = NetworkConfig.default()
    private var errorConfig = ErrorConfig.default()
    private var vitalsConfig = VitalsConfig.default()
    private var breadcrumbConfig = BreadcrumbConfig.default()

    fun service(block: ServiceDsl.() -> Unit) { serviceConfig.apply(block) }
    fun export(block: ExportDsl.() -> Unit) { exportConfig.apply(block) }
    fun buffering(block: BufferingDsl.() -> Unit) { bufferingConfig.apply(block) }
    fun session(block: SessionDsl.() -> Unit) { sessionConfig.apply(block) }
    fun exportCustomizers(block: ExporterCustomizersDsl.() -> Unit) { customizerConfig.apply(block) }
    fun instrumentations(block: InstrumentationsDsl.() -> Unit) { instrumentationsConfig.apply(block) }

    internal fun buildConfig(): MobileConfig = MobileConfig(
        serviceName = serviceConfig.name ?: error("service { name = ... } is required"),
        serviceVersion = serviceConfig.version ?: error("service { version = ... } is required"),
        collectorEndpoint = exportConfig.endpoint ?: error("export { endpoint = ... } is required"),
        protocol = exportConfig.protocol,
        exportMode = exportConfig.mode,
        uiTelemetryMode = uiTelemetryMode,
        traceExportIntervalSeconds = exportConfig.traceIntervalSeconds,
        metricExportIntervalSeconds = exportConfig.metricIntervalSeconds,
        ramBufferSize = bufferingConfig.ramSize,
        diskBufferMb = bufferingConfig.diskMb,
        diskBufferTtlHours = bufferingConfig.ttlHours,
        exportTimeoutSeconds = exportConfig.timeoutSeconds,
        maxExportRetries = exportConfig.maxRetries,
        headers = exportConfig.headers,
        sessionConfig = SessionConfig(inactivityTimeoutMs = sessionConfig.renewalMinutes * 60_000L),
        screenshotConfig = screenshotConfig,
        wireframeConfig = wireframeConfig,
        networkConfig = networkConfig,
        errorConfig = errorConfig,
        vitalsConfig = vitalsConfig,
        breadcrumbConfig = breadcrumbConfig,
        extraResourceAttributes = extraResourceAttributes
    )

    internal fun buildCustomizers(): ExporterCustomizers = customizerConfig.build()

    internal fun applyInstrumentationsTo(builder: OTelMobileBuilder) {
        instrumentationsConfig.applyTo(builder)
    }
}
