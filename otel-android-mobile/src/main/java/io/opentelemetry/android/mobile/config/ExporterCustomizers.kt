// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.config

import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.trace.export.SpanExporter

class ExporterCustomizers(
    val log: List<(LogRecordExporter) -> LogRecordExporter> = emptyList(),
    val span: List<(SpanExporter) -> SpanExporter> = emptyList(),
    val metric: List<(MetricExporter) -> MetricExporter> = emptyList()
) {
    companion object {
        val EMPTY = ExporterCustomizers()
    }

    class Builder {
        private val log = mutableListOf<(LogRecordExporter) -> LogRecordExporter>()
        private val span = mutableListOf<(SpanExporter) -> SpanExporter>()
        private val metric = mutableListOf<(MetricExporter) -> MetricExporter>()

        fun addLog(customizer: (LogRecordExporter) -> LogRecordExporter) = apply { log.add(customizer) }
        fun addSpan(customizer: (SpanExporter) -> SpanExporter) = apply { span.add(customizer) }
        fun addMetric(customizer: (MetricExporter) -> MetricExporter) = apply { metric.add(customizer) }

        fun build() = ExporterCustomizers(log.toList(), span.toList(), metric.toList())
    }
}
