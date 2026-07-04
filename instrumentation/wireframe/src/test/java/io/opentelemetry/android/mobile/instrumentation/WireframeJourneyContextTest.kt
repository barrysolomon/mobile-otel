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
 * Phase 1 / UJ-003: Verifies that wireframe captures emitted while a journey
 * span is current carry the journey's `trace_id`.
 */
@RunWith(RobolectricTestRunner::class)
class WireframeJourneyContextTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    @Test
    fun `wireframe emits with journey trace_id when captured under a journey span`() {
        val app = mockk<Application>(relaxed = true)
        val hub = WindowEventHub()
        val ctx = InstrumentationContext(
            otelRule.openTelemetry,
            DefaultMobileSessionProvider(),
            hub,
            app
        )

        val inst = WireframeInstrumentation()
        inst.install(app, ctx)

        val tracer = otelRule.openTelemetry.getTracer("journey-test")
        val journey: Span = tracer.spanBuilder("checkout").startSpan()
        val journeyTraceId = journey.spanContext.traceId

        val journeyContext = Context.root().with(journey)
        inst.emitForTesting(
            trigger = "journey_start",
            screenName = "Cart",
            parentContext = journeyContext
        )

        journey.end()
        inst.uninstall()

        val logRecords = otelRule.logRecords
        val wireframeRecord = logRecords
            .firstOrNull { it.body.asString() == MobileSemconv.UI_WIREFRAME }
        requireNotNull(wireframeRecord) { "wireframe log record was not emitted" }

        assertEquals(
            journeyTraceId,
            wireframeRecord.spanContext.traceId,
            "wireframe must carry journey trace_id for replay stitching"
        )
    }
}
