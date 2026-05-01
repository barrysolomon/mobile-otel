/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.demo

import android.app.Application
import android.util.Log
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter

object SdkInitializer {
    @Suppress("UNUSED_PARAMETER")
    fun initialize(app: Application, cellId: String? = null) {
        try {
            val endpoint = ExportConfig.endpoint
            val headers = ExportConfig.headers

            val builder = OpenTelemetryRum.builder(app)

            // Configure OTLP HTTP exporters with endpoint and auth headers
            builder.addSpanExporterCustomizer { _ ->
                OtlpHttpSpanExporter.builder()
                    .setEndpoint("$endpoint/v1/traces")
                    .also { b -> headers.forEach { (k, v) -> b.addHeader(k, v) } }
                    .build()
            }
            builder.addLogRecordExporterCustomizer { _ ->
                OtlpHttpLogRecordExporter.builder()
                    .setEndpoint("$endpoint/v1/logs")
                    .also { b -> headers.forEach { (k, v) -> b.addHeader(k, v) } }
                    .build()
            }
            builder.addMetricExporterCustomizer { _ ->
                OtlpHttpMetricExporter.builder()
                    .setEndpoint("$endpoint/v1/metrics")
                    .also { b -> headers.forEach { (k, v) -> b.addHeader(k, v) } }
                    .build()
            }

            val rum = builder.build()
            OtelDemoApplication.openTelemetry = rum.openTelemetry
            OtelDemoApplication.sessionId = rum.getRumSessionId()
        } catch (e: Exception) {
            Log.e("SdkInit", "Failed to initialize upstream SDK", e)
        }
    }
}
