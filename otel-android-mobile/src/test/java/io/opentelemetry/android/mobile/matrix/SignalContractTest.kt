// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.matrix

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task 5: Signal contract tests -- uses GoldenJourneyEmitter to emit the full
 * golden journey, then asserts signal-level properties on each event type.
 */
class SignalContractTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private fun emitJourney(): List<io.opentelemetry.sdk.logs.data.LogRecordData> {
        val logger = otelRule.openTelemetry.logsBridge.get("signal-contract")
        val tracer = otelRule.openTelemetry.getTracer("signal-contract")
        GoldenJourneyEmitter(logger, tracer).emit()
        return otelRule.logRecords.toList()
    }

    private fun findByBody(events: List<io.opentelemetry.sdk.logs.data.LogRecordData>, body: String) =
        events.filter { it.body.asString() == body }

    // ── 1. app.start has INFO severity + session.id ──────────────────────────

    @Test
    fun `app start has INFO severity and session id`() {
        val events = emitJourney()
        val appStart = findByBody(events, "app.start")
        assertEquals(1, appStart.size, "Expected exactly 1 app.start event")
        assertEquals(Severity.INFO, appStart[0].severity)
        val sessionId = appStart[0].attributes[AttributeKey.stringKey("mobile.session.id")]
        assertNotNull(sessionId, "app.start should have mobile.session.id")
    }

    // ── 2. app.foreground has INFO severity ──────────────────────────────────

    @Test
    fun `app foreground has INFO severity`() {
        val events = emitJourney()
        val fg = findByBody(events, "app.foreground")
        assertEquals(1, fg.size, "Expected exactly 1 app.foreground event")
        assertEquals(Severity.INFO, fg[0].severity)
    }

    // ── 3. ui.screen_view x 3, all INFO, all have mobile.screen.name ────────

    @Test
    fun `screen view events are INFO with screen name`() {
        val events = emitJourney()
        val screenViews = findByBody(events, "ui.screen_view")
        assertEquals(3, screenViews.size, "Expected exactly 3 ui.screen_view events")
        screenViews.forEach { sv ->
            assertEquals(Severity.INFO, sv.severity,
                "ui.screen_view should have INFO severity")
            val screenName = sv.attributes[AttributeKey.stringKey("mobile.screen.name")]
            assertNotNull(screenName,
                "ui.screen_view should have mobile.screen.name attribute")
        }
    }

    // ── 4. ui.tap has INFO severity ──────────────────────────────────────────

    @Test
    fun `ui tap has INFO severity`() {
        val events = emitJourney()
        val taps = findByBody(events, "ui.tap")
        assertEquals(1, taps.size, "Expected exactly 1 ui.tap event")
        assertEquals(Severity.INFO, taps[0].severity)
    }

    // ── 5. ui.text_input has INFO severity ───────────────────────────────────

    @Test
    fun `ui text input has INFO severity`() {
        val events = emitJourney()
        val inputs = findByBody(events, "ui.text_input")
        assertEquals(1, inputs.size, "Expected exactly 1 ui.text_input event")
        assertEquals(Severity.INFO, inputs[0].severity)
    }

    // ── 6. http.error has ERROR severity + http.status_code=503 ──────────────

    @Test
    fun `http error has ERROR severity and status code 503`() {
        val events = emitJourney()
        val httpErrors = findByBody(events, "http.error")
        assertEquals(1, httpErrors.size, "Expected exactly 1 http.error event")
        assertEquals(Severity.ERROR, httpErrors[0].severity)
        val statusCode = httpErrors[0].attributes[AttributeKey.stringKey("http.status_code")]
        assertEquals("503", statusCode, "http.error should have status_code=503")
    }

    // ── 7. ui.back_press has INFO severity ───────────────────────────────────

    @Test
    fun `ui back press has INFO severity`() {
        val events = emitJourney()
        val backPresses = findByBody(events, "ui.back_press")
        assertEquals(1, backPresses.size, "Expected exactly 1 ui.back_press event")
        assertEquals(Severity.INFO, backPresses[0].severity)
    }

    // ── 8. All events have session.id ────────────────────────────────────────

    @Test
    fun `all events have universal attributes`() {
        val events = emitJourney()
        TelemetryContract.assertUniversalAttributes(events)
    }

    // ── 9. Ordering: app.start timestamp <= ui.screen_view timestamp ─────────

    @Test
    fun `app start precedes screen view in timestamp order`() {
        val events = emitJourney()
        TelemetryContract.assertOrdering(events, "app.start", "ui.screen_view")
    }

    // ── Negative signal assertions ─────────────────────────────────────────

    private fun emitGoldenJourney(): List<io.opentelemetry.sdk.logs.data.LogRecordData> {
        val logger = otelRule.openTelemetry.logsBridge.get("negative-signal")
        val tracer = otelRule.openTelemetry.getTracer("negative-signal")
        GoldenJourneyEmitter(logger, tracer).emit()
        return otelRule.logRecords.toList()
    }

    @Test
    fun `golden journey does NOT contain app_crash event`() {
        val events = emitGoldenJourney()
        val crashes = events.filter { it.bodyValue?.asString() == "app.crash" }
        assertEquals(0, crashes.size, "Golden journey should not produce app.crash")
    }

    @Test
    fun `golden journey does NOT contain device_heartbeat`() {
        val events = emitGoldenJourney()
        val heartbeats = events.filter { it.bodyValue?.asString() == "device.heartbeat" }
        assertEquals(0, heartbeats.size, "Golden journey emitter should not produce heartbeats")
    }

    @Test
    fun `golden journey does NOT contain prediction_cycle`() {
        val events = emitGoldenJourney()
        val predictions = events.filter { it.bodyValue?.asString() == "prediction.cycle" }
        assertEquals(0, predictions.size, "Golden journey emitter should not produce predictions")
    }
}
