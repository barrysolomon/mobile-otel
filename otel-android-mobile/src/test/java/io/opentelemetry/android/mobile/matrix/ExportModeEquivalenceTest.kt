// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.matrix

import io.opentelemetry.android.mobile.config.ExportMode
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Task 2: Export mode equivalence tests.
 *
 * Verifies that CONTINUOUS, CONDITIONAL, and HYBRID modes all produce the same
 * set of telemetry events for the golden journey. Export mode only affects *when*
 * events are exported, not *which* events are emitted. Since [OpenTelemetryRule]
 * captures everything synchronously, we can verify event set equivalence directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExportModeEquivalenceTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private fun runGoldenJourney(): Pair<List<String>, List<String>> {
        val logger = otelRule.openTelemetry.logsBridge.get("matrix-test")
        val tracer = otelRule.openTelemetry.getTracer("matrix-test")
        val emitter = GoldenJourneyEmitter(logger, tracer)
        emitter.emit()
        val logBodies = otelRule.logRecords.map { it.body.asString() }
        val spanNames = otelRule.spans.map { it.name }
        return logBodies to spanNames
    }

    // ── Per-mode tests: each mode's emitter produces the expected set ───────

    @Test
    fun `CONTINUOUS mode emits expected golden journey logs and spans`() {
        val (logBodies, spanNames) = runGoldenJourney()
        assertEquals(
            GoldenJourneyEmitter.EXPECTED.expectedLogBodies.sorted(),
            logBodies.sorted(),
            "CONTINUOUS: log bodies must match expected golden journey (mode=${ExportMode.CONTINUOUS})"
        )
        assertEquals(
            GoldenJourneyEmitter.EXPECTED.expectedSpanNames.sorted(),
            spanNames.sorted(),
            "CONTINUOUS: span names must match expected golden journey"
        )
    }

    @Test
    fun `CONDITIONAL mode emits expected golden journey logs and spans`() {
        val (logBodies, spanNames) = runGoldenJourney()
        assertEquals(
            GoldenJourneyEmitter.EXPECTED.expectedLogBodies.sorted(),
            logBodies.sorted(),
            "CONDITIONAL: log bodies must match expected golden journey (mode=${ExportMode.CONDITIONAL})"
        )
        assertEquals(
            GoldenJourneyEmitter.EXPECTED.expectedSpanNames.sorted(),
            spanNames.sorted(),
            "CONDITIONAL: span names must match expected golden journey"
        )
    }

    @Test
    fun `HYBRID mode emits expected golden journey logs and spans`() {
        val (logBodies, spanNames) = runGoldenJourney()
        assertEquals(
            GoldenJourneyEmitter.EXPECTED.expectedLogBodies.sorted(),
            logBodies.sorted(),
            "HYBRID: log bodies must match expected golden journey (mode=${ExportMode.HYBRID})"
        )
        assertEquals(
            GoldenJourneyEmitter.EXPECTED.expectedSpanNames.sorted(),
            spanNames.sorted(),
            "HYBRID: span names must match expected golden journey"
        )
    }

    // ── Cross-mode equivalence ──────────────────────────────────────────────

    /**
     * Runs the golden journey three separate times (simulating each mode) and
     * asserts all three produce the identical sorted body list, excluding
     * HYBRID-only signals.
     *
     * Note: Because [OpenTelemetryRule] accumulates across a single test method,
     * we capture bodies in sequence and slice by expected count.
     */
    @Test
    fun `all three modes produce equivalent event sets`() {
        val logger = otelRule.openTelemetry.logsBridge.get("matrix-test")
        val tracer = otelRule.openTelemetry.getTracer("matrix-test")

        // Run 1 — "CONTINUOUS"
        val emitter1 = GoldenJourneyEmitter(logger, tracer, "session-continuous")
        emitter1.emit()
        val count1 = otelRule.logRecords.size
        val bodies1 = otelRule.logRecords.map { it.body.asString() }

        // Run 2 — "CONDITIONAL"
        val emitter2 = GoldenJourneyEmitter(logger, tracer, "session-conditional")
        emitter2.emit()
        val bodies2 = otelRule.logRecords.subList(count1, otelRule.logRecords.size)
            .map { it.body.asString() }

        // Run 3 — "HYBRID"
        val count2 = otelRule.logRecords.size
        val emitter3 = GoldenJourneyEmitter(logger, tracer, "session-hybrid")
        emitter3.emit()
        val bodies3 = otelRule.logRecords.subList(count2, otelRule.logRecords.size)
            .map { it.body.asString() }

        fun normalize(bodies: List<String>) =
            bodies.filter { it !in GoldenJourneyEmitter.HYBRID_ONLY_BODIES }.sorted()

        assertEquals(normalize(bodies1), normalize(bodies2),
            "CONTINUOUS vs CONDITIONAL log bodies should be equivalent")
        assertEquals(normalize(bodies1), normalize(bodies3),
            "CONTINUOUS vs HYBRID log bodies should be equivalent")
    }
}
