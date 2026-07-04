// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.journey

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Survives app background → foreground transitions without orphaning a
 * journey's child spans.
 *
 * Background story: a journey span started via `OTelMobile.startJourney` and
 * `makeCurrent`'d on the main thread becomes the parent of every page / tap /
 * network span that fires while it's open. If the user backgrounds the app
 * mid-flow without the demo code calling `endJourney`, the children flush via
 * the periodic batch processor while the parent stays open forever — and
 * never reaches the exporter. Dash0 then renders the children as orphans
 * with "Missing span" where the parent should be.
 *
 * Fix: close any open journey on background with `journey.outcome=paused`,
 * and on foreground start a new sibling span carrying the same `journey.id`,
 * an incremented `journey.episode`, and an OTel `Link` to the previous
 * span's `SpanContext`. Each foreground episode is therefore a clean,
 * bounded trace; the link chain stitches them into a single logical journey
 * at query time (`dash0 traces get <id> --follow-span-links`).
 *
 * Thread-safety: `startJourney` / `endJourney` / `onBackground` / `onForeground`
 * must be called from the main thread to interact safely with
 * `Context.current()` propagation. Internal state uses concurrent maps
 * defensively.
 */
class JourneyTracker(private val tracer: Tracer) {

    private data class OpenJourney(
        val span: Span,
        val journeyId: String,
        val episode: Long
    )

    private data class PausedJourney(
        val spanContext: SpanContext,
        val journeyId: String,
        val episode: Long
    )

    private val openByName = ConcurrentHashMap<String, OpenJourney>()
    private val pausedByName = ConcurrentHashMap<String, PausedJourney>()

    /**
     * Starts a journey span tagged with a fresh `journey.id`. Returns the
     * underlying [Span] so callers can `.makeCurrent()` or set additional
     * attributes — the wrapper does not change span semantics.
     *
     * If a journey with this name is already open, the existing one is
     * returned without creating a new span. This protects against duplicate
     * starts from re-entrant lifecycle callbacks.
     */
    fun startJourney(name: String): Span {
        openByName[name]?.let { return it.span }

        val journeyId = UUID.randomUUID().toString()
        return startEpisode(name, journeyId, episode = 1L, linkTo = null)
    }

    /**
     * Ends the named journey with [outcome] stamped as `journey.outcome`.
     * Clears any paused state for this name so a later `onForeground` will
     * not resurrect it.
     */
    fun endJourney(name: String, outcome: String) {
        openByName.remove(name)?.let { open ->
            open.span.setAttribute(JOURNEY_OUTCOME, outcome)
            open.span.end()
        }
        pausedByName.remove(name)
    }

    /**
     * Closes every open journey with `journey.outcome=paused` and stashes
     * each one's [SpanContext] so a subsequent [onForeground] can start a
     * linked next episode.
     */
    fun onBackground() {
        val snapshot = openByName.toMap()
        openByName.clear()
        for ((name, open) in snapshot) {
            open.span.setAttribute(JOURNEY_OUTCOME, OUTCOME_PAUSED)
            open.span.end()
            pausedByName[name] = PausedJourney(
                spanContext = open.span.spanContext,
                journeyId = open.journeyId,
                episode = open.episode
            )
        }
    }

    /**
     * Called when a policy-triggered flush fires (e.g. `http.error` policy
     * exporting a 2-minute window). The user is still on the same screen, so
     * we do NOT start a new linked episode — that would emit a "ghost" span
     * that never accumulates children. Instead we just close any open
     * journey with `journey.outcome=flushed` so the export captures a real
     * parent for the children that are about to flush, and clear any paused
     * state so a later foreground does not resurrect the journey.
     *
     * Subsequent user spans emitted on the main thread will still carry the
     * closed journey's `span_id` as their parent — that is OTel-compliant
     * (children may be created after the parent ends; duration overlap is
     * permitted). The product code is free to call `startJourney(name)`
     * again to begin a fresh journey episode if the user crosses a logical
     * boundary.
     */
    fun onPolicyFlush() {
        val snapshot = openByName.toMap()
        openByName.clear()
        pausedByName.clear()
        for ((_, open) in snapshot) {
            open.span.setAttribute(JOURNEY_OUTCOME, OUTCOME_FLUSHED)
            open.span.end()
        }
    }

    /**
     * For each previously paused journey, starts a new episode span with the
     * same `journey.id`, incremented `journey.episode`, and a Link back to
     * the paused span's context. The new span is NOT automatically made
     * current — callers wanting auto-parenting of subsequent UI spans must
     * call `.makeCurrent()` themselves.
     */
    fun onForeground() {
        val snapshot = pausedByName.toMap()
        pausedByName.clear()
        for ((name, paused) in snapshot) {
            startEpisode(
                name = name,
                journeyId = paused.journeyId,
                episode = paused.episode + 1,
                linkTo = paused.spanContext
            )
        }
    }

    private fun startEpisode(
        name: String,
        journeyId: String,
        episode: Long,
        linkTo: SpanContext?
    ): Span {
        val builder = tracer.spanBuilder(name)
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute(JOURNEY_NAME, name)
            .setAttribute(JOURNEY_ID, journeyId)
            .setAttribute(JOURNEY_EPISODE, episode)
        if (linkTo != null) {
            builder.addLink(linkTo)
        }
        val span = builder.startSpan()
        openByName[name] = OpenJourney(span, journeyId, episode)
        return span
    }

    companion object {
        private val JOURNEY_NAME = AttributeKey.stringKey("journey.name")
        private val JOURNEY_ID = AttributeKey.stringKey("journey.id")
        private val JOURNEY_EPISODE = AttributeKey.longKey("journey.episode")
        private val JOURNEY_OUTCOME = AttributeKey.stringKey("journey.outcome")
        const val OUTCOME_PAUSED = "paused"
        const val OUTCOME_FLUSHED = "flushed"
    }
}
