// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.buffering

import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.context.Context
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TDD tests for [PersistingSpanProcessor].
 *
 * Problem: long-running spans (page.*, journey.*) sit in the BatchSpanProcessor
 * RAM queue open when a crash occurs. Their children already exported; the
 * parent never did. Dash0 shows the parent as "Missing span".
 *
 * Fix: wrap BatchSpanProcessor with a checkpoint layer. On onStart, record the
 * span identity to a [SpanCheckpointStore]. On onEnd, remove it. On next launch,
 * call recoverAndExport() — it synthesizes truncated SpanData for every
 * un-ended entry (preserving the original traceId/spanId/parentSpanId so Dash0
 * can stitch the previously-orphaned children back under them).
 */
class PersistingSpanProcessorTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private val tracer: Tracer get() = otelRule.openTelemetry.getTracer("test")

    // ── checkpoint lifecycle ──────────────────────────────────────────────────

    @Test
    fun `checkpoint is written to store when a span starts`() {
        val store = InMemorySpanCheckpointStore()
        val processor = PersistingSpanProcessor(store, noopDelegate())

        val span = tracer.spanBuilder("page.CalendarFragment").startSpan()
        processor.onStart(Context.current(), span as ReadWriteSpan)

        assertEquals(1, store.readAll().size, "expected one checkpoint after span start")
        assertEquals("page.CalendarFragment", store.readAll().first().name)
        span.end()
    }

    @Test
    fun `checkpoint is removed when span ends normally`() {
        val store = InMemorySpanCheckpointStore()
        val processor = PersistingSpanProcessor(store, noopDelegate())

        val span = tracer.spanBuilder("page.CalendarFragment").startSpan()
        processor.onStart(Context.current(), span as ReadWriteSpan)
        span.end()
        processor.onEnd(span as ReadableSpan)

        assertEquals(0, store.readAll().size, "checkpoint must be removed after normal span end")
    }

    @Test
    fun `normal span lifecycle leaves no orphan in store`() {
        val store = InMemorySpanCheckpointStore()
        val processor = PersistingSpanProcessor(store, noopDelegate())

        repeat(5) { i ->
            val span = tracer.spanBuilder("page.Screen$i").startSpan()
            processor.onStart(Context.current(), span as ReadWriteSpan)
            span.end()
            processor.onEnd(span as ReadableSpan)
        }

        assertTrue(store.readAll().isEmpty(), "no orphans should remain after all spans ended")
    }

    // ── crash recovery ────────────────────────────────────────────────────────

    @Test
    fun `crashed span is synthesized and exported on recovery`() {
        val store = InMemorySpanCheckpointStore()
        val capturingExporter = CapturingSpanExporter()
        val processor = PersistingSpanProcessor(store, noopDelegate())

        // Simulate crash: span starts, is checkpointed, but never ends
        val span = tracer.spanBuilder("page.CalendarFragment").startSpan()
        processor.onStart(Context.current(), span as ReadWriteSpan)
        // [crash — onEnd never called]

        // Next launch: recover
        processor.recoverAndExport(capturingExporter)

        assertEquals(1, capturingExporter.exported.size, "expected one recovered span")
        assertEquals("page.CalendarFragment", capturingExporter.exported.first().name)
    }

    @Test
    fun `recovered span preserves original traceId and spanId`() {
        val store = InMemorySpanCheckpointStore()
        val capturingExporter = CapturingSpanExporter()
        val processor = PersistingSpanProcessor(store, noopDelegate())

        val span = tracer.spanBuilder("page.CalendarFragment").startSpan()
        val originalTraceId = span.spanContext.traceId
        val originalSpanId  = span.spanContext.spanId
        processor.onStart(Context.current(), span as ReadWriteSpan)

        processor.recoverAndExport(capturingExporter)

        val recovered = capturingExporter.exported.first()
        assertEquals(originalTraceId, recovered.spanContext.traceId,
            "recovered span must carry the original traceId so Dash0 can stitch orphaned children")
        assertEquals(originalSpanId, recovered.spanContext.spanId,
            "recovered span must carry the original spanId so children's parentSpanId resolves")
    }

    @Test
    fun `recovered span preserves parentSpanId — the exact field Dash0 uses to stitch orphaned children`() {
        val store = InMemorySpanCheckpointStore()
        val capturingExporter = CapturingSpanExporter()
        val processor = PersistingSpanProcessor(store, noopDelegate())

        // Simulate: journey span is the parent (as in the screenshot: otel-mobile-demo → page.CalendarFragment)
        val journeySpan = tracer.spanBuilder("journey.booking").startSpan()
        val journeyScope = journeySpan.makeCurrent()

        val pageSpan = tracer.spanBuilder("page.CalendarFragment").startSpan()
        val expectedParentSpanId = journeySpan.spanContext.spanId
        processor.onStart(Context.current(), pageSpan as ReadWriteSpan)

        journeyScope.close()
        journeySpan.end()
        // [crash — pageSpan.onEnd never called]

        processor.recoverAndExport(capturingExporter)

        val recovered = capturingExporter.exported.first()
        assertEquals(expectedParentSpanId, recovered.parentSpanContext.spanId,
            "parentSpanId must be preserved so Dash0 nests the recovered span under its original parent " +
            "instead of showing it as a new root — this is the exact fix for the 'Missing span' case in the screenshot")
    }

    @Test
    fun `children of a crashed page span resolve correctly after recovery — the Missing span scenario`() {
        // This is the exact scenario from the screenshot:
        //   otel-mobile-demo (journey, exported OK)
        //     └─ page.CalendarFragment (crashed open → Missing span in Dash0)
        //          └─ 10.0.2.2 network call (exported OK, points to missing parent)
        //
        // After recovery: the synthesized page span carries the same spanId, so Dash0
        // can place the network call under it instead of showing "Missing span".

        val store = InMemorySpanCheckpointStore()
        val capturingExporter = CapturingSpanExporter()
        val processor = PersistingSpanProcessor(store, noopDelegate())

        val journeySpan = tracer.spanBuilder("journey.booking").startSpan()
        val journeyScope = journeySpan.makeCurrent()

        val pageSpan = tracer.spanBuilder("page.CalendarFragment").startSpan()
        val pageSpanId = pageSpan.spanContext.spanId
        val pageTraceId = pageSpan.spanContext.traceId
        processor.onStart(Context.current(), pageSpan as ReadWriteSpan)

        // Child span (network call) starts and ends — exported before the crash
        val pageScope = pageSpan.makeCurrent()
        val networkSpan = tracer.spanBuilder("HTTP GET /api/calendar").startSpan()
        val networkParentId = (networkSpan as ReadableSpan).toSpanData().parentSpanContext.spanId
        networkSpan.end()
        pageScope.close()

        // Verify the network child correctly references the page span as its parent
        assertEquals(pageSpanId, networkParentId,
            "network child's parentSpanId must equal the page span's spanId before crash")

        journeyScope.close()
        journeySpan.end()
        // [crash — pageSpan never ended]

        // Next launch: recover
        processor.recoverAndExport(capturingExporter)

        val recovered = capturingExporter.exported.first()
        // The recovered span has the same spanId — so the network child's parentSpanId now resolves
        assertEquals(pageSpanId, recovered.spanContext.spanId,
            "recovered span's spanId must match the spanId the network child used as parentSpanId")
        assertEquals(pageTraceId, recovered.spanContext.traceId,
            "recovered span must share the same traceId as the network child")
    }

    @Test
    fun `recovered span carries the SDK resource so service name is correct in Dash0`() {
        val store = InMemorySpanCheckpointStore()
        val capturingExporter = CapturingSpanExporter()
        val sdkResource = io.opentelemetry.sdk.resources.Resource.builder()
            .put("service.name", "otel-mobile-demo")
            .put("service.version", "1.1.0")
            .build()
        val processor = PersistingSpanProcessor(store, noopDelegate(), sdkResource)

        val span = tracer.spanBuilder("page.HomeFragment").startSpan()
        processor.onStart(Context.current(), span as ReadWriteSpan)

        processor.recoverAndExport(capturingExporter)

        val recovered = capturingExporter.exported.first()
        assertEquals("otel-mobile-demo",
            recovered.resource.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("service.name")),
            "recovered span must carry the real SDK resource so Dash0 attributes it to the correct service")
    }

    @Test
    fun `recovered span has crash_recovered attribute set to true`() {
        val store = InMemorySpanCheckpointStore()
        val capturingExporter = CapturingSpanExporter()
        val processor = PersistingSpanProcessor(store, noopDelegate())

        val span = tracer.spanBuilder("page.HomeFragment").startSpan()
        processor.onStart(Context.current(), span as ReadWriteSpan)

        processor.recoverAndExport(capturingExporter)

        val recovered = capturingExporter.exported.first()
        val attr = recovered.attributes.get(
            io.opentelemetry.api.common.AttributeKey.booleanKey("crash.recovered")
        )
        assertTrue(attr == true, "recovered span must have crash.recovered=true")
    }

    @Test
    fun `store is cleared after recovery so spans are not re-exported on subsequent launches`() {
        val store = InMemorySpanCheckpointStore()
        val capturingExporter = CapturingSpanExporter()
        val processor = PersistingSpanProcessor(store, noopDelegate())

        val span = tracer.spanBuilder("page.CalendarFragment").startSpan()
        processor.onStart(Context.current(), span as ReadWriteSpan)

        processor.recoverAndExport(capturingExporter)
        // Second launch recovery: nothing should be re-exported
        capturingExporter.exported.clear()
        processor.recoverAndExport(capturingExporter)

        assertEquals(0, capturingExporter.exported.size,
            "recovery must not re-export already-recovered spans")
    }

    @Test
    fun `all crashed spans are recovered when multiple were open at crash time`() {
        val store = InMemorySpanCheckpointStore()
        val capturingExporter = CapturingSpanExporter()
        val processor = PersistingSpanProcessor(store, noopDelegate())

        val names = listOf("page.CalendarFragment", "journey.booking", "page.BookingFragment")
        names.forEach { name ->
            val span = tracer.spanBuilder(name).startSpan()
            processor.onStart(Context.current(), span as ReadWriteSpan)
        }

        processor.recoverAndExport(capturingExporter)

        assertEquals(names.size, capturingExporter.exported.size,
            "all open spans at crash time must be recovered")
        val recoveredNames = capturingExporter.exported.map { it.name }.toSet()
        assertEquals(names.toSet(), recoveredNames)
    }

    @Test
    fun `only crashed spans are recovered — ended spans are not re-exported`() {
        val store = InMemorySpanCheckpointStore()
        val capturingExporter = CapturingSpanExporter()
        val processor = PersistingSpanProcessor(store, noopDelegate())

        // This span ends normally
        val ended = tracer.spanBuilder("ui.tap").startSpan()
        processor.onStart(Context.current(), ended as ReadWriteSpan)
        ended.end()
        processor.onEnd(ended as ReadableSpan)

        // This span crashes
        val crashed = tracer.spanBuilder("page.CalendarFragment").startSpan()
        processor.onStart(Context.current(), crashed as ReadWriteSpan)

        processor.recoverAndExport(capturingExporter)

        assertEquals(1, capturingExporter.exported.size,
            "only the crashed span should be recovered, not the normally-ended one")
        assertEquals("page.CalendarFragment", capturingExporter.exported.first().name)
    }

    @Test
    fun `recoverAndExport is a no-op when no spans were open at crash time`() {
        val store = InMemorySpanCheckpointStore()
        val capturingExporter = CapturingSpanExporter()
        val processor = PersistingSpanProcessor(store, noopDelegate())

        processor.recoverAndExport(capturingExporter)

        assertEquals(0, capturingExporter.exported.size, "no recovery needed when store is empty")
    }

    // ── delegate forwarding ───────────────────────────────────────────────────

    @Test
    fun `onStart is forwarded to the delegate processor`() {
        val store = InMemorySpanCheckpointStore()
        val delegateCalled = mutableListOf<String>()
        val recordingDelegate = object : SpanProcessor {
            override fun onStart(parentContext: Context, span: ReadWriteSpan) {
                delegateCalled += "start:${span.name}"
            }
            override fun onEnd(span: ReadableSpan) {}
            override fun isStartRequired() = true
            override fun isEndRequired() = false
            override fun forceFlush() = CompletableResultCode.ofSuccess()
            override fun shutdown() = CompletableResultCode.ofSuccess()
        }
        val processor = PersistingSpanProcessor(store, recordingDelegate)

        val span = tracer.spanBuilder("page.HomeFragment").startSpan()
        processor.onStart(Context.current(), span as ReadWriteSpan)
        span.end()

        assertTrue(delegateCalled.contains("start:page.HomeFragment"),
            "onStart must be forwarded to the wrapped delegate")
    }

    @Test
    fun `onEnd is forwarded to the delegate processor`() {
        val store = InMemorySpanCheckpointStore()
        val delegateCalled = mutableListOf<String>()
        val recordingDelegate = object : SpanProcessor {
            override fun onStart(parentContext: Context, span: ReadWriteSpan) {}
            override fun onEnd(span: ReadableSpan) {
                delegateCalled += "end:${span.name}"
            }
            override fun isStartRequired() = true
            override fun isEndRequired() = true
            override fun forceFlush() = CompletableResultCode.ofSuccess()
            override fun shutdown() = CompletableResultCode.ofSuccess()
        }
        val processor = PersistingSpanProcessor(store, recordingDelegate)

        val span = tracer.spanBuilder("page.HomeFragment").startSpan()
        processor.onStart(Context.current(), span as ReadWriteSpan)
        span.end()
        processor.onEnd(span as ReadableSpan)

        assertTrue(delegateCalled.contains("end:page.HomeFragment"),
            "onEnd must be forwarded to the wrapped delegate")
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun noopDelegate() = object : SpanProcessor {
        override fun onStart(parentContext: Context, span: ReadWriteSpan) {}
        override fun onEnd(span: ReadableSpan) {}
        override fun isStartRequired() = true
        override fun isEndRequired() = true
        override fun forceFlush() = CompletableResultCode.ofSuccess()
        override fun shutdown() = CompletableResultCode.ofSuccess()
    }
}

/** In-memory [SpanCheckpointStore] for tests. */
class InMemorySpanCheckpointStore : SpanCheckpointStore {
    private val store = mutableMapOf<String, SpanCheckpoint>()
    override fun checkpoint(entry: SpanCheckpoint) { store[entry.spanId] = entry }
    override fun remove(spanId: String) { store.remove(spanId) }
    override fun readAll(): List<SpanCheckpoint> = store.values.toList()
    override fun clear() { store.clear() }
}

/** Captures exported spans for assertion. */
class CapturingSpanExporter : SpanExporter {
    val exported = mutableListOf<SpanData>()
    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        exported += spans
        return CompletableResultCode.ofSuccess()
    }
    override fun flush() = CompletableResultCode.ofSuccess()
    override fun shutdown() = CompletableResultCode.ofSuccess()
}
