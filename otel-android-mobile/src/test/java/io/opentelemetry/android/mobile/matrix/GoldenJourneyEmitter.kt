// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.matrix

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context

class GoldenJourneyEmitter(
    private val logger: Logger,
    private val tracer: Tracer,
    private val sessionId: String = "test-session-001"
) {
    fun emit(): GoldenJourneyExpectation {
        // Step 1: App launch
        emitLog("app.start", Severity.INFO, mapOf("app.start.type" to "cold"))
        emitLog("app.foreground", Severity.INFO)

        // Step 2: Calendar screen
        emitLog("ui.screen_view", Severity.INFO, mapOf("mobile.screen.name" to "CalendarFragment"))
        val calendarSpan = tracer.spanBuilder("page.CalendarFragment").startSpan()
        val calendarScope = calendarSpan.makeCurrent()

        // Step 3: Tap
        emitLog("ui.tap", Severity.INFO, mapOf("mobile.screen.name" to "CalendarFragment"))

        // Step 4: Navigate to booking
        calendarSpan.end()
        calendarScope.close()
        emitLog("ui.screen_view", Severity.INFO, mapOf("mobile.screen.name" to "BookFragment"))
        val bookSpan = tracer.spanBuilder("page.BookFragment").startSpan()
        val bookScope = bookSpan.makeCurrent()

        // Step 5: Fill form
        emitLog("ui.text_input", Severity.INFO, mapOf("mobile.screen.name" to "BookFragment"))

        // Step 6: Submit booking (HTTP span as child of page)
        val httpSpan = tracer.spanBuilder("HTTP POST /api/appointments")
            .setParent(Context.current().with(bookSpan))
            .startSpan()
        httpSpan.end()

        // Step 7: Trigger 503
        emitLog("http.error", Severity.ERROR, mapOf(
            "http.status_code" to "503",
            "http.url" to "/api/appointments"
        ))

        // Step 8: Navigate back
        bookSpan.end()
        bookScope.close()
        emitLog("ui.back_press", Severity.INFO)
        emitLog("ui.screen_view", Severity.INFO, mapOf("mobile.screen.name" to "CalendarFragment"))
        val backSpan = tracer.spanBuilder("page.CalendarFragment").startSpan()
        backSpan.end()

        return EXPECTED
    }

    private fun emitLog(body: String, severity: Severity, attrs: Map<String, String> = emptyMap()) {
        val attrBuilder = Attributes.builder()
            .put(AttributeKey.stringKey("mobile.session.id"), sessionId)
        attrs.forEach { (k, v) -> attrBuilder.put(AttributeKey.stringKey(k), v) }
        logger.logRecordBuilder()
            .setBody(body)
            .setSeverity(severity)
            .setAllAttributes(attrBuilder.build())
            .emit()
    }

    companion object {
        val EXPECTED = GoldenJourneyExpectation(
            expectedLogBodies = listOf(
                "app.start", "app.foreground",
                "ui.screen_view", "ui.tap", "ui.screen_view",
                "ui.text_input", "http.error",
                "ui.back_press", "ui.screen_view"
            ),
            expectedSpanNames = listOf(
                "page.CalendarFragment", "page.BookFragment",
                "HTTP POST /api/appointments", "page.CalendarFragment"
            ),
            expectedLogCount = 9,
            expectedSpanCount = 4
        )

        val HYBRID_ONLY_BODIES = setOf("device.heartbeat", "prediction.cycle", "prediction.high_risk_alert")
    }
}

data class GoldenJourneyExpectation(
    val expectedLogBodies: List<String>,
    val expectedSpanNames: List<String>,
    val expectedLogCount: Int,
    val expectedSpanCount: Int
)
