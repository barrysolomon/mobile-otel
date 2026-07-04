// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import android.graphics.Bitmap
import io.mockk.mockk
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ScreenshotInstrumentationTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    @Test fun `instrumentationName is correct`() {
        assertEquals(
            "io.opentelemetry.android.mobile.screenshot",
            ScreenshotInstrumentation().instrumentationName
        )
    }

    @Test fun `install registers activity callbacks`() {
        val app = mockk<Application>(relaxed = true)
        val hub = WindowEventHub()
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        val inst = ScreenshotInstrumentation()
        assertFalse(inst.isInstalled)

        inst.install(app, ctx)
        assertTrue(inst.isInstalled)

        inst.uninstall()
    }

    @Test fun `install with disabled config is a no-op`() {
        val app = mockk<Application>(relaxed = true)
        val hub = WindowEventHub()
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        val inst = ScreenshotInstrumentation(ScreenshotConfig(enabled = false))
        inst.install(app, ctx)

        assertFalse(inst.isInstalled)
    }

    @Test fun `uninstall cleans up state`() {
        val app = mockk<Application>(relaxed = true)
        val hub = WindowEventHub()
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        val inst = ScreenshotInstrumentation()
        inst.install(app, ctx)
        assertTrue(inst.isInstalled)

        inst.uninstall()
        assertFalse(inst.isInstalled)
        assertNull(inst.trackedActivity)
    }

    @Test fun `captureScreenshot with no activity is a no-op`() {
        val app = mockk<Application>(relaxed = true)
        val hub = WindowEventHub()
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        val inst = ScreenshotInstrumentation()
        inst.install(app, ctx)

        // Should not crash — just silently returns.
        inst.captureScreenshot("test")

        inst.uninstall()
    }

    @Test fun `captureScreenshot when disabled is a no-op`() {
        val inst = ScreenshotInstrumentation(ScreenshotConfig(enabled = false))
        // Should not crash even without install.
        inst.captureScreenshot("test")
    }

    @Test fun `scaleBitmap returns same bitmap when within bounds`() {
        val inst = ScreenshotInstrumentation(ScreenshotConfig(maxWidthPx = 100, maxHeightPx = 100))
        val bitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)

        val result = inst.scaleBitmap(bitmap)
        assertTrue(result === bitmap, "Should return the same bitmap instance when within bounds")

        bitmap.recycle()
    }

    @Test fun `scaleBitmap downscales when too wide`() {
        val inst = ScreenshotInstrumentation(ScreenshotConfig(maxWidthPx = 100, maxHeightPx = 200))
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)

        val result = inst.scaleBitmap(bitmap)
        assertTrue(result.width <= 100)
        assertTrue(result.height <= 200)

        bitmap.recycle()
        result.recycle()
    }

    @Test fun `scaleBitmap downscales when too tall`() {
        val inst = ScreenshotInstrumentation(ScreenshotConfig(maxWidthPx = 200, maxHeightPx = 100))
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)

        val result = inst.scaleBitmap(bitmap)
        assertTrue(result.width <= 200)
        assertTrue(result.height <= 100)

        bitmap.recycle()
        result.recycle()
    }

    @Test fun `scaleBitmap preserves aspect ratio`() {
        val inst = ScreenshotInstrumentation(ScreenshotConfig(maxWidthPx = 100, maxHeightPx = 100))
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)

        val result = inst.scaleBitmap(bitmap)
        // Original is 2:1 — scaled should maintain that.
        assertEquals(100, result.width)
        assertEquals(50, result.height)

        bitmap.recycle()
        result.recycle()
    }

    @Test fun `default version is 1_0_0`() {
        assertEquals("1.0.0", ScreenshotInstrumentation().instrumentationVersion)
    }
}
