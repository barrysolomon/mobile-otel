/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.autocapture

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.android.mobile.MobileLoggerProvider
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.opentelemetry.android.mobile.testing.MockLogRecordExporter

/**
 * Tests for the page-level span model in [AutoCaptureManager].
 *
 * Covers:
 * - startPageSpan() creates named span and makes it current
 * - startPageSpan() ends any previously active page span before starting a new one
 * - endPageSpan() ends the current span and clears scope
 * - Span.current() returns page span while active
 * - Span.current() returns NOOP span after endPageSpan()
 * - Page span carries session.id, view.id, screen.name attributes
 * - Consecutive calls produce independent spans with correct names
 * - Span events added via Span.current() appear on the correct span
 * - Span attributes added via Span.current() appear on the correct span
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PageSpanModelTest {

    private lateinit var app: Application
    private lateinit var spanExporter: InMemorySpanExporter
    private lateinit var logExporter: MockLogRecordExporter
    private lateinit var manager: AutoCaptureManager

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        spanExporter = InMemorySpanExporter.create()
        logExporter  = MockLogRecordExporter()

        val tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
            .build()

        val loggerProvider = SdkLoggerProvider.builder()
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(logExporter))
            .build()

        val otel = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .build()

        val mockProvider = mockk<MobileLoggerProvider>(relaxed = true)
        every { mockProvider.getOpenTelemetrySdk() } returns otel
        every { mockProvider.get(any()) } returns loggerProvider.get("test")

        manager = AutoCaptureManager(app, mockProvider, AutoCaptureOptions())
    }

    // ── startPageSpan ─────────────────────────────────────────────────────

    @Test
    fun `startPageSpan creates a span named page dot ScreenName`() {
        manager.startPageSpan("BookFragment")
        manager.endPageSpan()

        val spans = spanExporter.finishedSpanItems
        assertTrue("Expected at least one finished span", spans.isNotEmpty())
        assertTrue("Expected a page.BookFragment span",
            spans.any { it.name == "page.BookFragment" })
    }

    @Test
    fun `startPageSpan makes span current so Span_current returns it`() {
        manager.startPageSpan("AppointmentsFragment")
        val currentSpan = Span.current()
        assertTrue("Current span should be valid (recording)", currentSpan.spanContext.isValid)
        manager.endPageSpan()
    }

    @Test
    fun `startPageSpan attaches screen_name attribute`() {
        manager.startPageSpan("CalendarFragment")
        manager.endPageSpan()

        val span = spanExporter.finishedSpanItems.first { it.name == "page.CalendarFragment" }
        assertEquals("CalendarFragment",
            span.attributes.get(AttributeKey.stringKey("screen.name")))
    }

    @Test
    fun `startPageSpan attaches session_id attribute`() {
        manager.startPageSpan("BookFragment")
        manager.endPageSpan()

        val span = spanExporter.finishedSpanItems.first { it.name == "page.BookFragment" }
        assertNotNull("session.id should be present",
            span.attributes.get(AttributeKey.stringKey("session.id")))
    }

    @Test
    fun `startPageSpan attaches view_id attribute`() {
        manager.startPageSpan("DirectionsFragment")
        manager.endPageSpan()

        val span = spanExporter.finishedSpanItems.first { it.name == "page.DirectionsFragment" }
        assertNotNull("view.id should be present",
            span.attributes.get(AttributeKey.stringKey("view.id")))
    }

    // ── endPageSpan ───────────────────────────────────────────────────────

    @Test
    fun `endPageSpan finishes the span`() {
        manager.startPageSpan("BookFragment")
        val wasRecording = Span.current().spanContext.isValid
        manager.endPageSpan()

        assertTrue("Span should have been recording before endPageSpan()", wasRecording)
        val spans = spanExporter.finishedSpanItems
        assertTrue("Span should be finished after endPageSpan()", spans.any { it.name == "page.BookFragment" })
    }

    @Test
    fun `endPageSpan clears current span so Span_current becomes invalid`() {
        manager.startPageSpan("BookFragment")
        manager.endPageSpan()

        val current = Span.current()
        assertFalse("Span.current() should be NOOP/invalid after endPageSpan()",
            current.spanContext.isValid)
    }

    @Test
    fun `endPageSpan when no span active does not throw`() {
        // Should be a no-op
        manager.endPageSpan()
    }

    @Test
    fun `endPageSpan called twice does not throw`() {
        manager.startPageSpan("ProfileFragment")
        manager.endPageSpan()
        manager.endPageSpan() // second call should be safe
    }

    // ── startPageSpan replaces previous span ──────────────────────────────

    @Test
    fun `startPageSpan called twice ends first span before starting second`() {
        manager.startPageSpan("ScreenA")
        val firstSpanId = Span.current().spanContext.spanId

        manager.startPageSpan("ScreenB")
        val secondSpanId = Span.current().spanContext.spanId

        manager.endPageSpan()

        assertNotEquals("Second startPageSpan should produce a new span ID",
            firstSpanId, secondSpanId)

        val names = spanExporter.finishedSpanItems.map { it.name }
        assertTrue("page.ScreenA should be finished", "page.ScreenA" in names)
    }

    @Test
    fun `navigating across three screens produces three named spans`() {
        manager.startPageSpan("AppointmentsFragment")
        manager.startPageSpan("BookFragment")
        manager.startPageSpan("CalendarFragment")
        manager.endPageSpan()

        val names = spanExporter.finishedSpanItems.map { it.name }
        assertTrue("page.AppointmentsFragment expected", "page.AppointmentsFragment" in names)
        assertTrue("page.BookFragment expected", "page.BookFragment" in names)
        assertTrue("page.CalendarFragment expected", "page.CalendarFragment" in names)
    }

    // ── Span.current() annotations under active page span ────────────────

    @Test
    fun `events added via Span_current appear on the page span`() {
        manager.startPageSpan("BookFragment")

        Span.current().addEvent("form.submitted")

        manager.endPageSpan()

        val pageSpan = spanExporter.finishedSpanItems.first { it.name == "page.BookFragment" }
        assertTrue("form.submitted event should be on the page span",
            pageSpan.events.any { it.name == "form.submitted" })
    }

    @Test
    fun `attributes set via Span_current appear on the page span`() {
        manager.startPageSpan("BookFragment")

        Span.current().setAttribute(AttributeKey.stringKey("result.appointment_id"), "appt-999")

        manager.endPageSpan()

        val pageSpan = spanExporter.finishedSpanItems.first { it.name == "page.BookFragment" }
        assertEquals("appt-999",
            pageSpan.attributes.get(AttributeKey.stringKey("result.appointment_id")))
    }

    @Test
    fun `status set via Span_current appears on the page span`() {
        manager.startPageSpan("BookFragment")
        Span.current().setStatus(StatusCode.OK)
        manager.endPageSpan()

        val pageSpan = spanExporter.finishedSpanItems.first { it.name == "page.BookFragment" }
        assertEquals(StatusCode.OK, pageSpan.status.statusCode)
    }

    @Test
    fun `error status set via Span_current appears on the page span`() {
        manager.startPageSpan("BookFragment")
        Span.current().setStatus(StatusCode.ERROR, "duplicate appointment")
        manager.endPageSpan()

        val pageSpan = spanExporter.finishedSpanItems.first { it.name == "page.BookFragment" }
        assertEquals(StatusCode.ERROR, pageSpan.status.statusCode)
    }

    // ── Multiple events in sequence ───────────────────────────────────────

    @Test
    fun `multiple events on same page span are all recorded`() {
        manager.startPageSpan("BookFragment")
        Span.current().addEvent("form.provider_selected")
        Span.current().addEvent("form.date_selected")
        Span.current().addEvent("form.type_selected")
        Span.current().addEvent("form.submitted")
        manager.endPageSpan()

        val pageSpan = spanExporter.finishedSpanItems.first { it.name == "page.BookFragment" }
        val eventNames = pageSpan.events.map { it.name }
        assertTrue("form.provider_selected" in eventNames)
        assertTrue("form.date_selected" in eventNames)
        assertTrue("form.type_selected" in eventNames)
        assertTrue("form.submitted" in eventNames)
    }

    // ── stop() calls endPageSpan ──────────────────────────────────────────

    @Test
    fun `stop() ends active page span`() {
        manager.start()
        manager.startPageSpan("TestFragment")
        manager.stop()

        val spans = spanExporter.finishedSpanItems
        assertTrue("page.TestFragment should be finished after stop()",
            spans.any { it.name == "page.TestFragment" })
    }
}
