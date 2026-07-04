// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.config

import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.trace.export.SpanExporter

@MobileOtelDslMarker
class ExporterCustomizersDsl {
    private val logCustomizers = mutableListOf<(LogRecordExporter) -> LogRecordExporter>()
    private val spanCustomizers = mutableListOf<(SpanExporter) -> SpanExporter>()
    private val metricCustomizers = mutableListOf<(MetricExporter) -> MetricExporter>()

    fun log(customizer: (LogRecordExporter) -> LogRecordExporter) { logCustomizers.add(customizer) }
    fun span(customizer: (SpanExporter) -> SpanExporter) { spanCustomizers.add(customizer) }
    fun metric(customizer: (MetricExporter) -> MetricExporter) { metricCustomizers.add(customizer) }

    internal fun build() = ExporterCustomizers(
        log = logCustomizers.toList(),
        span = spanCustomizers.toList(),
        metric = metricCustomizers.toList()
    )
}
