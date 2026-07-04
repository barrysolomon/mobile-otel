/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import android.view.KeyEvent
import io.mockk.mockk
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavioral tests for [UiTelemetryMode] using [BackPressInstrumentation].
 *
 * BackPress has the simplest trigger path (single KeyEvent) making it ideal
 * for proving uiTelemetryMode works correctly across instrumentation modules.
 */
@RunWith(RobolectricTestRunner::class)
class UiTelemetryModeBackPressTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private fun makeCtx(
        app: Application = mockk(relaxed = true),
        mode: UiTelemetryMode = UiTelemetryMode.EVENTS
    ) = InstrumentationContext(
        otelRule.openTelemetry,
        DefaultMobileSessionProvider(),
        WindowEventHub(),
        app,
        mode
    )

    private fun fireBackPress(inst: BackPressInstrumentation) {
        val window = mockk<android.view.Window>(relaxed = true)
        val keyEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
        inst.onKeyEvent(keyEvent, window)
    }

    // ── EVENTS mode ────────────────────────────────────────────────────────

    @Test
    fun `EVENTS mode emits log record only for back press`() {
        val inst = BackPressInstrumentation()
        inst.install(mockk(relaxed = true), makeCtx(mode = UiTelemetryMode.EVENTS))

        fireBackPress(inst)

        assertTrue(otelRule.logRecords.isNotEmpty(), "EVENTS mode should emit log records")
        assertEquals("ui.back_press", otelRule.logRecords.first().bodyValue?.asString())
        assertTrue(otelRule.spans.isEmpty(), "EVENTS mode should NOT emit spans")
    }

    // ── SPANS mode ─────────────────────────────────────────────────────────

    @Test
    fun `SPANS mode emits span only for back press`() {
        val inst = BackPressInstrumentation()
        inst.install(mockk(relaxed = true), makeCtx(mode = UiTelemetryMode.SPANS))

        fireBackPress(inst)

        assertTrue(otelRule.logRecords.isEmpty(), "SPANS mode should NOT emit log records")
        assertTrue(otelRule.spans.isNotEmpty(), "SPANS mode should emit spans")
        assertEquals("ui.back_press", otelRule.spans.first().name)
    }

    // ── BOTH mode ──────────────────────────────────────────────────────────

    @Test
    fun `BOTH mode emits both log record and span for back press`() {
        val inst = BackPressInstrumentation()
        inst.install(mockk(relaxed = true), makeCtx(mode = UiTelemetryMode.BOTH))

        fireBackPress(inst)

        assertTrue(otelRule.logRecords.isNotEmpty(), "BOTH mode should emit log records")
        assertTrue(otelRule.spans.isNotEmpty(), "BOTH mode should emit spans")
        assertEquals("ui.back_press", otelRule.logRecords.first().bodyValue?.asString())
        assertEquals("ui.back_press", otelRule.spans.first().name)
    }

    // ── EVENTS mode includes session attributes ──────────────────────────

    @Test
    fun `EVENTS mode includes session attributes in log records`() {
        val inst = BackPressInstrumentation()
        inst.install(mockk(relaxed = true), makeCtx(mode = UiTelemetryMode.EVENTS))

        fireBackPress(inst)

        val log = otelRule.logRecords.first()
        val sessionId = log.attributes.get(MobileSemconv.SESSION_ID)
        assertTrue(sessionId != null && sessionId.isNotEmpty(),
            "EVENTS mode should include session ID in log records")
    }

    @Test
    fun `SPANS mode includes session attributes in spans`() {
        val inst = BackPressInstrumentation()
        inst.install(mockk(relaxed = true), makeCtx(mode = UiTelemetryMode.SPANS))

        fireBackPress(inst)

        val span = otelRule.spans.first()
        val sessionId = span.attributes.get(MobileSemconv.SESSION_ID)
        assertTrue(sessionId != null && sessionId.isNotEmpty(),
            "SPANS mode should include session ID in spans")
    }

    // ── Non-back-press keys are ignored regardless of mode ─────────────────

    @Test
    fun `non-back-press key emits nothing in any mode`() {
        for (mode in UiTelemetryMode.entries) {
            val ctx = makeCtx(mode = mode)
            val inst = BackPressInstrumentation()
            inst.install(mockk(relaxed = true), ctx)

            val window = mockk<android.view.Window>(relaxed = true)
            val enterKey = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
            inst.onKeyEvent(enterKey, window)

            assertEquals(0, otelRule.logRecords.size,
                "$mode mode: non-KEYCODE_BACK should emit no log records")
            assertEquals(0, otelRule.spans.size,
                "$mode mode: non-KEYCODE_BACK should emit no spans")
        }
    }

    @Test
    fun `ACTION_DOWN back press emits nothing`() {
        val inst = BackPressInstrumentation()
        inst.install(mockk(relaxed = true), makeCtx(mode = UiTelemetryMode.BOTH))

        val window = mockk<android.view.Window>(relaxed = true)
        val keyDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        inst.onKeyEvent(keyDown, window)

        assertEquals(0, otelRule.logRecords.size, "ACTION_DOWN should not emit")
        assertEquals(0, otelRule.spans.size, "ACTION_DOWN should not emit")
    }
}
