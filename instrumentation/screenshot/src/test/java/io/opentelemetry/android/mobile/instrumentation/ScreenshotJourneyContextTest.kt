// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.mockk.mockk
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * Phase 1 / UJ-003: Verifies that screenshot captures emitted while a journey
 * span is current carry the journey's `trace_id` so the control plane can
 * stitch the journey timeline with its visual captures.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenshotJourneyContextTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    @Test
    fun `screenshot emits with journey trace_id when captured under a journey span`() {
        val app = mockk<Application>(relaxed = true)
        val hub = WindowEventHub()
        val ctx = InstrumentationContext(
            otelRule.openTelemetry,
            DefaultMobileSessionProvider(),
            hub,
            app
        )

        val inst = ScreenshotInstrumentation()
        inst.install(app, ctx)

        val tracer = otelRule.openTelemetry.getTracer("journey-test")
        val journey: Span = tracer.spanBuilder("book_appointment").startSpan()
        val journeyTraceId = journey.spanContext.traceId

        // Use the public emit hook the instrumentation exposes for tests
        // and lifecycle integration. The pinned context must carry the
        // journey's trace_id through to the emitted log record.
        val journeyContext = Context.root().with(journey)
        inst.emitForTesting(
            trigger = "journey_start",
            screenName = "Calendar",
            parentContext = journeyContext
        )

        journey.end()
        inst.uninstall()

        val logRecords = otelRule.logRecords
        val screenshotRecord = logRecords
            .firstOrNull { it.body.asString() == MobileSemconv.UI_SCREENSHOT }
        requireNotNull(screenshotRecord) { "screenshot log record was not emitted" }

        assertEquals(
            journeyTraceId,
            screenshotRecord.spanContext.traceId,
            "screenshot must carry journey trace_id for replay stitching"
        )
    }
}
