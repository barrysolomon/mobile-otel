// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.buffering

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.api.common.AttributeKey as AttrKey
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.data.EventData
import io.opentelemetry.sdk.trace.data.LinkData
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.data.StatusData
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.sdk.common.InstrumentationLibraryInfo

/**
 * Wraps a delegate [SpanProcessor] (typically [io.opentelemetry.sdk.trace.export.BatchSpanProcessor])
 * and adds crash-safe span recovery.
 *
 * **Problem being solved:** long-running spans (`page.*`, `journey.*`) sit open in
 * the BatchSpanProcessor's RAM queue when a crash occurs. Their children already
 * exported; the parent never did. Dash0 renders the children as orphans under
 * a "Missing span" placeholder.
 *
 * **How it works:**
 * - [onStart]: write a [SpanCheckpoint] to the [SpanCheckpointStore] (O(1)).
 * - [onEnd]: remove the checkpoint — span ended normally, no recovery needed.
 * - [recoverAndExport]: called once at startup. Reads any un-ended checkpoints
 *   (crash victims), synthesizes truncated [SpanData] preserving the original
 *   `traceId`/`spanId`/`parentSpanId`, marks them with `crash.recovered=true`,
 *   exports them, then clears the store. Dash0 can then stitch the previously-
 *   orphaned children back under the recovered parent span.
 */
class PersistingSpanProcessor(
    private val store: SpanCheckpointStore,
    private val delegate: SpanProcessor,
    private val resource: Resource = Resource.getDefault(),
) : SpanProcessor {

    override fun onStart(parentContext: Context, span: ReadWriteSpan) {
        val sc = span.spanContext
        val parentId = span.toSpanData().parentSpanContext
            .takeIf { it.isValid }?.spanId ?: ""
        store.checkpoint(
            SpanCheckpoint(
                traceId = sc.traceId,
                spanId = sc.spanId,
                parentSpanId = parentId,
                name = span.name,
                startEpochNanos = span.toSpanData().startEpochNanos,
            )
        )
        delegate.onStart(parentContext, span)
    }

    override fun onEnd(span: ReadableSpan) {
        store.remove(span.spanContext.spanId)
        delegate.onEnd(span)
    }

    override fun isStartRequired() = true
    override fun isEndRequired() = true

    override fun forceFlush(): CompletableResultCode = delegate.forceFlush()
    override fun shutdown(): CompletableResultCode = delegate.shutdown()

    /**
     * Synthesizes a truncated [SpanData] for each un-ended checkpoint and exports
     * them via [exporter]. Must be called once during SDK startup, before the app
     * starts new spans, so the synthesized spans reach Dash0 before any children
     * that reference them as parents.
     */
    fun recoverAndExport(exporter: SpanExporter) {
        val orphans = store.readAll()
        if (orphans.isEmpty()) return
        val endNanos = System.currentTimeMillis() * 1_000_000L
        val synthesized = orphans.map { it.toSynthesizedSpanData(endNanos) }
        exporter.export(synthesized)
        store.clear()
    }

    private fun SpanCheckpoint.toSynthesizedSpanData(endEpochNanos: Long): SpanData {
        val spanCtx = SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault())
        val parentCtx = if (parentSpanId.isNotEmpty())
            SpanContext.create(traceId, parentSpanId, TraceFlags.getSampled(), TraceState.getDefault())
        else
            SpanContext.getInvalid()

        val attrs = Attributes.of(
            AttributeKey.booleanKey("crash.recovered"), true,
        )
        return SynthesizedSpanData(
            spanContext = spanCtx,
            parentSpanContext = parentCtx,
            name = name,
            startEpochNanos = startEpochNanos,
            endEpochNanos = endEpochNanos,
            attributes = attrs,
            resource = resource,
        )
    }
}

/** Minimal [SpanData] implementation for crash-recovered spans. */
private class SynthesizedSpanData(
    private val spanContext: SpanContext,
    private val parentSpanContext: SpanContext,
    private val name: String,
    private val startEpochNanos: Long,
    private val endEpochNanos: Long,
    private val attributes: Attributes,
    private val resource: Resource = Resource.getDefault(),
) : SpanData {
    override fun getName() = name
    override fun getKind() = SpanKind.INTERNAL
    override fun getSpanContext() = spanContext
    override fun getParentSpanContext() = parentSpanContext
    override fun getStatus() = StatusData.unset()
    override fun getStartEpochNanos() = startEpochNanos
    override fun getEndEpochNanos() = endEpochNanos
    override fun getAttributes() = attributes
    override fun getEvents(): List<EventData> = emptyList()
    override fun getLinks(): List<LinkData> = emptyList()
    override fun getTotalAttributeCount() = attributes.size()
    override fun getTotalRecordedEvents() = 0
    override fun getTotalRecordedLinks() = 0
    override fun getResource() = resource
    override fun getInstrumentationScopeInfo() =
        InstrumentationScopeInfo.create("io.opentelemetry.android.mobile.crash-recovery")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getInstrumentationLibraryInfo(): InstrumentationLibraryInfo =
        InstrumentationLibraryInfo.create("io.opentelemetry.android.mobile.crash-recovery", null)
    override fun hasEnded() = true
}
