// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.config

import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExporterCustomizersTest {

    @Test fun `empty customizers have empty lists`() {
        val c = ExporterCustomizers()
        assertTrue(c.log.isEmpty())
        assertTrue(c.span.isEmpty())
        assertTrue(c.metric.isEmpty())
    }

    @Test fun `EMPTY companion is empty`() {
        assertTrue(ExporterCustomizers.EMPTY.log.isEmpty())
    }

    @Test fun `builder adds log customizers in order`() {
        val c1: (LogRecordExporter) -> LogRecordExporter = { it }
        val c2: (LogRecordExporter) -> LogRecordExporter = { it }
        val result = ExporterCustomizers.Builder().addLog(c1).addLog(c2).build()
        assertEquals(2, result.log.size)
        assertSame(c1, result.log[0])
        assertSame(c2, result.log[1])
    }

    @Test fun `builder adds span customizers`() {
        val c1: (SpanExporter) -> SpanExporter = { it }
        assertEquals(1, ExporterCustomizers.Builder().addSpan(c1).build().span.size)
    }

    @Test fun `builder adds metric customizers`() {
        val c1: (MetricExporter) -> MetricExporter = { it }
        assertEquals(1, ExporterCustomizers.Builder().addMetric(c1).build().metric.size)
    }

    @Test fun `log customizers chain in registration order`() {
        val calls = mutableListOf<String>()
        val base = mockk<LogRecordExporter>(relaxed = true)
        val c1: (LogRecordExporter) -> LogRecordExporter = { calls.add("c1"); it }
        val c2: (LogRecordExporter) -> LogRecordExporter = { calls.add("c2"); it }
        val customizers = ExporterCustomizers(log = listOf(c1, c2))
        var exporter: LogRecordExporter = base
        for (c in customizers.log) { exporter = c(exporter) }
        assertEquals(listOf("c1", "c2"), calls)
    }
}
