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
import io.opentelemetry.sdk.metrics.export.AggregationTemporalitySelector
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter

/**
 * No-op exporters installed when the transport-security policy rejects the
 * configured endpoint (cleartext `http://` to a non-loopback host with
 * `allowInsecureTransport=false`). Export is disabled GRACEFULLY: every export
 * call returns success so the SDK pipelines (buffer drain, batch processors)
 * keep running without error spam, but no telemetry leaves the device in
 * cleartext. This is the Android equivalent of iOS converting a rejected
 * transport into a disabled export — the host is never crashed.
 *
 * Mirrors the iOS behaviour where `TransportError.insecureTransportRejected`
 * is caught and the export pipeline becomes a no-op.
 */
internal object NoopLogRecordExporter : LogRecordExporter {
    override fun export(logs: MutableCollection<LogRecordData>): CompletableResultCode =
        CompletableResultCode.ofSuccess()

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}

internal object NoopSpanExporter : SpanExporter {
    override fun export(spans: MutableCollection<SpanData>): CompletableResultCode =
        CompletableResultCode.ofSuccess()

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}

internal object NoopMetricExporter : MetricExporter, AggregationTemporalitySelector {
    override fun getAggregationTemporality(instrumentType: InstrumentType): AggregationTemporality =
        AggregationTemporality.CUMULATIVE

    override fun export(metrics: MutableCollection<MetricData>): CompletableResultCode =
        CompletableResultCode.ofSuccess()

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}
