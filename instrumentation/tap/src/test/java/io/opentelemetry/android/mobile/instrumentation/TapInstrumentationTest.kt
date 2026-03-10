// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class TapInstrumentationTest {
    @get:Rule val otelRule = OpenTelemetryRule.create()

    private fun realApp(): Application = ApplicationProvider.getApplicationContext()

    private fun makeCtx(app: Application = realApp()) =
        InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), WindowEventHub(), app)

    /** Synthesize a simple tap: DOWN at (x,y) then UP at (x,y) within tap timeout. */
    private fun tapEvent(x: Float = 100f, y: Float = 100f): Pair<MotionEvent, MotionEvent> {
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, x, y, 0)
        val up   = MotionEvent.obtain(0L, 50L, MotionEvent.ACTION_UP,  x, y, 0)
        return down to up
    }

    /** Synthesize a swipe: DOWN at (x1,y) then UP at (x2,y). */
    private fun swipeEvent(x1: Float = 100f, x2: Float = 300f, y: Float = 100f): Pair<MotionEvent, MotionEvent> {
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, x1, y, 0)
        val up   = MotionEvent.obtain(0L, 50L, MotionEvent.ACTION_UP,  x2, y, 0)
        return down to up
    }

    @Test fun `instrumentationName is correct`() {
        assertEquals("io.opentelemetry.android.mobile.tap", TapInstrumentation().instrumentationName)
    }

    @Test fun `registers as WindowEventListener on install`() {
        val hub = spyk(WindowEventHub())
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, realApp())

        TapInstrumentation().install(realApp(), ctx)

        verify { hub.addListener(any()) }
    }

    @Test fun `uninstall removes WindowEventListener`() {
        val hub = spyk(WindowEventHub())
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, realApp())

        val inst = TapInstrumentation()
        inst.install(realApp(), ctx)
        inst.uninstall()

        verify { hub.removeListener(any()) }
    }

    @Test fun `tap emits ui_tap log record`() {
        val inst = TapInstrumentation()
        val ctx = makeCtx()
        inst.install(realApp(), ctx)

        val window = mockk<android.view.Window>(relaxed = true)
        val (down, up) = tapEvent()
        inst.onTouchEvent(down, window)
        inst.onTouchEvent(up, window)

        assertTrue(otelRule.logRecords.any { it.body.asString() == "ui.tap" })
    }

    @Test fun `swipe beyond threshold emits ui_swipe with direction`() {
        val inst = TapInstrumentation(TapConfig(swipeMinDistancePx = 50f))
        val ctx = makeCtx()
        inst.install(realApp(), ctx)

        val window = mockk<android.view.Window>(relaxed = true)
        val (down, up) = swipeEvent(x1 = 100f, x2 = 300f) // 200px right
        inst.onTouchEvent(down, window)
        inst.onTouchEvent(up, window)

        val swipe = otelRule.logRecords.find { it.body.asString() == "ui.swipe" }
        assertTrue(swipe != null, "Expected ui.swipe log record")
        assertEquals("right", swipe.attributes[MobileSemconv.SWIPE_DIRECTION])
    }

    @Test fun `swipe below threshold emits ui_tap not ui_swipe`() {
        val inst = TapInstrumentation(TapConfig(swipeMinDistancePx = 50f))
        val ctx = makeCtx()
        inst.install(realApp(), ctx)

        val window = mockk<android.view.Window>(relaxed = true)
        val (down, up) = swipeEvent(x1 = 100f, x2 = 120f) // only 20px
        inst.onTouchEvent(down, window)
        inst.onTouchEvent(up, window)

        assertTrue(otelRule.logRecords.none { it.body.asString() == "ui.swipe" })
        assertTrue(otelRule.logRecords.any { it.body.asString() == "ui.tap" })
    }

    @Test fun `ACTION_CANCEL does not emit any event`() {
        val inst = TapInstrumentation()
        val ctx = makeCtx()
        inst.install(realApp(), ctx)

        val window = mockk<android.view.Window>(relaxed = true)
        val down   = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        val cancel = MotionEvent.obtain(0L, 50L, MotionEvent.ACTION_CANCEL, 100f, 100f, 0)
        inst.onTouchEvent(down, window)
        inst.onTouchEvent(cancel, window)

        assertTrue(otelRule.logRecords.isEmpty())
    }
}
