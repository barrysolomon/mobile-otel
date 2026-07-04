/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.export

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.metrics.InstrumentType
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter

/**
 * Lazily-constructed exporter delegates.
 *
 * Building the OTLP exporters (HTTP client, TLS context, protobuf
 * marshallers) costs ~50 ms of class-loading and allocation. SDK init runs
 * on the app's main thread (`Application.onCreate`), but the FIRST actual
 * export always happens on a background thread (batch processors, the
 * mobile buffer's flush executor, periodic metric readers) — so deferring
 * construction to first use moves that cost off the critical path entirely.
 * HS-001 budgets SDK init at < 50 ms on the main thread.
 *
 * `flush()`/`shutdown()` on a never-used exporter succeed WITHOUT
 * constructing the delegate — building an exporter just to tear it down
 * would reintroduce the cost at shutdown.
 *
 * Thread-safety: double-checked construction; the factory runs at most once.
 */
internal class LazyLogRecordExporter(
    private val factory: () -> LogRecordExporter,
) : LogRecordExporter {
    @Volatile private var built: LogRecordExporter? = null

    private fun delegate(): LogRecordExporter =
        built ?: synchronized(this) { built ?: factory().also { built = it } }

    override fun export(logs: Collection<LogRecordData>): CompletableResultCode =
        delegate().export(logs)

    /** Force-build and return the underlying exporter (tests/wiring assertions). */
    internal fun materialize(): LogRecordExporter = delegate()

    override fun flush(): CompletableResultCode =
        built?.flush() ?: CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode =
        built?.shutdown() ?: CompletableResultCode.ofSuccess()
}

internal class LazySpanExporter(
    private val factory: () -> SpanExporter,
) : SpanExporter {
    @Volatile private var built: SpanExporter? = null

    private fun delegate(): SpanExporter =
        built ?: synchronized(this) { built ?: factory().also { built = it } }

    override fun export(spans: Collection<SpanData>): CompletableResultCode =
        delegate().export(spans)

    /** Force-build and return the underlying exporter (tests/wiring assertions). */
    internal fun materialize(): SpanExporter = delegate()

    override fun flush(): CompletableResultCode =
        built?.flush() ?: CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode =
        built?.shutdown() ?: CompletableResultCode.ofSuccess()
}

internal class LazyMetricExporter(
    private val factory: () -> MetricExporter,
) : MetricExporter {
    @Volatile private var built: MetricExporter? = null

    private fun delegate(): MetricExporter =
        built ?: synchronized(this) { built ?: factory().also { built = it } }

    override fun export(metrics: Collection<MetricData>): CompletableResultCode =
        delegate().export(metrics)

    /** Force-build and return the underlying exporter (tests/wiring assertions). */
    internal fun materialize(): MetricExporter = delegate()

    // Answered statically so the periodic reader's registration-time query
    // can't force construction: every exporter this SDK wraps (OTLP HTTP and
    // OTLP gRPC) defaults to CUMULATIVE temporality.
    override fun getAggregationTemporality(instrumentType: InstrumentType): AggregationTemporality =
        AggregationTemporality.CUMULATIVE

    override fun flush(): CompletableResultCode =
        built?.flush() ?: CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode =
        built?.shutdown() ?: CompletableResultCode.ofSuccess()
}
