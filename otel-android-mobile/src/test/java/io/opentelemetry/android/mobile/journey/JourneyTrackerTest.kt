// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.journey

import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import io.opentelemetry.sdk.trace.data.SpanData
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Failing-first tests for the journey auto-pause/resume safety net.
 *
 * Bug being fixed: a journey span started via `OTelMobile.startJourney` that
 * never received `.end()` (because the user backgrounded the app mid-flow)
 * left its already-ended children pointing at a parent the BatchSpanProcessor
 * never enqueued. Dash0 then surfaced the children as orphans with the parent
 * shown as "Missing span".
 *
 * Design: cross-trace links (Option 2). On background, any open journey
 * span ends with `journey.outcome=paused` so it actually reaches the
 * exporter. On foreground, a new sibling journey span starts with the same
 * `journey.id`, an incremented `journey.episode`, and an OTel Link to the
 * previous span's SpanContext.
 */
class JourneyTrackerTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private val tracer: Tracer get() = otelRule.openTelemetry.getTracer("test")

    @Test
    fun `startJourney returns a span with journey id and journey name attributes`() {
        val tracker = JourneyTracker(tracer)
        val span = tracker.startJourney("book_appointment")
        span.end()

        val data = onlySpan()
        assertEquals("book_appointment", data.name)
        assertEquals("book_appointment", attr(data, "journey.name"))
        assertNotNull(attr(data, "journey.id"), "journey.id must be stamped on episode 1")
        assertEquals(1L, attrLong(data, "journey.episode"))
    }

    @Test
    fun `onBackground ends an open journey with outcome paused`() {
        val tracker = JourneyTracker(tracer)
        tracker.startJourney("book_appointment")

        tracker.onBackground()

        val data = onlySpan()
        assertEquals("paused", attr(data, "journey.outcome"))
    }

    @Test
    fun `onBackground does nothing when no journey is open`() {
        val tracker = JourneyTracker(tracer)
        tracker.onBackground()  // must not crash, must not emit anything
        assertEquals(0, otelRule.spans.size)
    }

    @Test
    fun `onForeground after pause starts a new episode linked to the previous`() {
        val tracker = JourneyTracker(tracer)
        val first = tracker.startJourney("book_appointment")
        val firstId = first.spanContext.spanId
        val firstTraceId = first.spanContext.traceId

        tracker.onBackground()  // episode 1 ends now -- safe to read its SpanData
        tracker.onForeground()
        tracker.endJourney("book_appointment", outcome = "success")  // close episode 2 so it exports

        val spans = otelRule.spans
        assertEquals(2, spans.size, "expected exactly 2 spans, got ${spans.size}")

        val episode1 = spans.first { attrLong(it, "journey.episode") == 1L }
        val episode2 = spans.first { attrLong(it, "journey.episode") == 2L }
        val journeyId = attr(episode1, "journey.id")

        assertEquals(journeyId, attr(episode2, "journey.id"),
            "episode 2 must carry the same journey.id as episode 1")

        // Cross-trace link: episode 2 should be its OWN trace, with a Link to episode 1
        assertTrue(firstTraceId != episode2.traceId,
            "episode 2 must be in a new trace (cross-trace link semantics)")
        assertEquals(1, episode2.links.size, "episode 2 must have exactly one Link to episode 1")
        assertEquals(firstId, episode2.links.first().spanContext.spanId,
            "Link must point to episode 1's span_id")
        assertEquals(firstTraceId, episode2.links.first().spanContext.traceId,
            "Link must point to episode 1's trace_id")

        // Episode 1's outcome is the paused marker
        assertEquals("paused", attr(episode1, "journey.outcome"))
        // Episode 2 was explicitly ended with success
        assertEquals("success", attr(episode2, "journey.outcome"))
    }

    @Test
    fun `onForeground when no paused journey does nothing`() {
        val tracker = JourneyTracker(tracer)
        tracker.onForeground()
        assertEquals(0, otelRule.spans.size, "foreground without a paused journey must not invent a span")
    }

    @Test
    fun `multi-episode bg fg bg fg increments episode count and chains links`() {
        val tracker = JourneyTracker(tracer)
        tracker.startJourney("book_appointment")

        tracker.onBackground()  // episode 1 ends
        tracker.onForeground()  // episode 2 starts
        tracker.onBackground()  // episode 2 ends
        tracker.onForeground()  // episode 3 starts
        tracker.endJourney("book_appointment", outcome = "success")  // episode 3 ends

        val spans = otelRule.spans.sortedBy { attrLong(it, "journey.episode") }
        assertEquals(3, spans.size)
        assertEquals(1L, attrLong(spans[0], "journey.episode"))
        assertEquals(2L, attrLong(spans[1], "journey.episode"))
        assertEquals(3L, attrLong(spans[2], "journey.episode"))

        // All three carry the same journey.id
        val journeyIds = spans.map { attr(it, "journey.id") }.toSet()
        assertEquals(1, journeyIds.size, "all episodes must share one journey.id")

        // Link chain: ep2 links to ep1, ep3 links to ep2
        assertEquals(spans[0].spanId, spans[1].links.first().spanContext.spanId)
        assertEquals(spans[1].spanId, spans[2].links.first().spanContext.spanId)

        // Final outcome on the last episode
        assertEquals("success", attr(spans[2], "journey.outcome"))
        // Earlier episodes carry the paused outcome
        assertEquals("paused", attr(spans[0], "journey.outcome"))
        assertEquals("paused", attr(spans[1], "journey.outcome"))
    }

    @Test
    fun `endJourneyBySpan finds the open journey by span identity and ends it`() {
        val tracker = JourneyTracker(tracer)
        val span = tracker.startJourney("book_appointment")

        tracker.endJourneyBySpan(span, outcome = "success")

        val data = onlySpan()
        assertEquals("success", attr(data, "journey.outcome"))

        // State cleared — backgrounding now must NOT emit a second span
        tracker.onBackground()
        tracker.onForeground()
        assertEquals(1, otelRule.spans.size)
    }

    @Test
    fun `endJourneyBySpan returns false for an unknown span`() {
        val tracker = JourneyTracker(tracer)
        val unknownSpan = tracer.spanBuilder("not_a_journey").startSpan()
        try {
            val ended = tracker.endJourneyBySpan(unknownSpan, outcome = "success")
            // The tracker should NOT have ended the span and should report not-found.
            // The caller (OTelMobile) is responsible for calling span.end() in that case.
            kotlin.test.assertFalse(ended, "endJourneyBySpan must return false for unknown spans")
        } finally {
            unknownSpan.end()
        }
    }

    @Test
    fun `endJourney before background ends the journey with the given outcome and clears state`() {
        val tracker = JourneyTracker(tracer)
        tracker.startJourney("book_appointment")
        tracker.endJourney("book_appointment", outcome = "success")

        // Backgrounding now must NOT emit a second span — the journey is already closed
        tracker.onBackground()
        tracker.onForeground()

        assertEquals(1, otelRule.spans.size)
        assertEquals("success", attr(onlySpan(), "journey.outcome"))
    }

    @Test
    fun `multiple concurrent journeys are tracked independently by name`() {
        val tracker = JourneyTracker(tracer)
        tracker.startJourney("onboarding")
        tracker.startJourney("book_appointment")

        tracker.onBackground()  // both episode 1s end
        tracker.onForeground()  // both episode 2s start
        tracker.endJourney("onboarding", outcome = "success")
        tracker.endJourney("book_appointment", outcome = "success")

        // 2 paused (episode 1 each) + 2 resumed-and-ended (episode 2 each) = 4 spans
        assertEquals(4, otelRule.spans.size)
        val names = otelRule.spans.map { it.name }.toSet()
        assertEquals(setOf("onboarding", "book_appointment"), names)
    }

    @Test
    fun `onPolicyFlush ends open journeys with outcome flushed and does not start new episodes`() {
        val tracker = JourneyTracker(tracer)
        tracker.startJourney("book_appointment")

        tracker.onPolicyFlush()

        // The journey span is exported with outcome=flushed so its already-emitted
        // children no longer orphan. No new episode is started — user is still on
        // the same screen; the demo code decides whether to start a fresh journey.
        val data = onlySpan()
        assertEquals("flushed", attr(data, "journey.outcome"))

        // A subsequent foreground must NOT resurrect this journey — onPolicyFlush
        // clears pause state. Background/foreground after a policy flush is a no-op.
        tracker.onForeground()
        assertEquals(1, otelRule.spans.size,
            "onForeground after onPolicyFlush must not invent a new episode")
    }

    @Test
    fun `onPolicyFlush does nothing when no journey is open`() {
        val tracker = JourneyTracker(tracer)
        tracker.onPolicyFlush()
        assertEquals(0, otelRule.spans.size)
    }

    @Test
    fun `onPolicyFlush during a paused journey ends the paused state without emitting again`() {
        // Edge case: a journey was paused (e.g. transient background) and then a
        // policy flush fires before foreground returns. The pause already ended
        // the span and exported it; onPolicyFlush should not re-export or
        // double-emit, and it should clear the paused state.
        val tracker = JourneyTracker(tracer)
        tracker.startJourney("book_appointment")
        tracker.onBackground()  // paused -- 1 span exported

        tracker.onPolicyFlush()  // no-op for spans, but clears pause state

        tracker.onForeground()  // must NOT resurrect

        assertEquals(1, otelRule.spans.size,
            "onPolicyFlush should clear pause state so foreground does not resurrect")
    }

    // ---- helpers ---------------------------------------------------------

    private fun onlySpan(): SpanData {
        val spans = otelRule.spans
        assertEquals(1, spans.size, "expected exactly one span, got ${spans.size}")
        return spans.first()
    }

    private fun attr(span: SpanData, key: String): String? =
        span.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey(key))

    private fun attrLong(span: SpanData, key: String): Long? =
        span.attributes.get(io.opentelemetry.api.common.AttributeKey.longKey(key))
}
