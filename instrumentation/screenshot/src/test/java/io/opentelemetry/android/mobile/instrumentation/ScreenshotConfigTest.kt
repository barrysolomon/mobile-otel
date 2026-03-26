// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenshotConfigTest {

    @Test fun `default config has sensible values`() {
        val config = ScreenshotConfig()
        assertTrue(config.enabled)
        assertEquals(480, config.maxWidthPx)
        assertEquals(960, config.maxHeightPx)
        assertEquals(50, config.quality)
        assertEquals(ScreenshotFormat.JPEG, config.format)
        assertEquals(200, config.maxPayloadKb)
        assertTrue(config.redactTextViews)
        assertTrue(config.captureOnError)
        assertEquals(5, config.maxCapturesPerMinute)
    }

    @Test fun `can disable`() {
        val config = ScreenshotConfig(enabled = false)
        assertFalse(config.enabled)
    }

    @Test fun `can set PNG format`() {
        val config = ScreenshotConfig(format = ScreenshotFormat.PNG)
        assertEquals(ScreenshotFormat.PNG, config.format)
    }

    @Test fun `can disable text redaction`() {
        val config = ScreenshotConfig(redactTextViews = false)
        assertFalse(config.redactTextViews)
    }

    @Test fun `can disable captureOnError`() {
        val config = ScreenshotConfig(captureOnError = false)
        assertFalse(config.captureOnError)
    }

    @Test fun `maxWidthPx must be positive`() {
        assertFailsWith<IllegalArgumentException> { ScreenshotConfig(maxWidthPx = 0) }
    }

    @Test fun `maxWidthPx must not exceed 4096`() {
        assertFailsWith<IllegalArgumentException> { ScreenshotConfig(maxWidthPx = 4097) }
    }

    @Test fun `maxHeightPx must be positive`() {
        assertFailsWith<IllegalArgumentException> { ScreenshotConfig(maxHeightPx = 0) }
    }

    @Test fun `maxHeightPx must not exceed 4096`() {
        assertFailsWith<IllegalArgumentException> { ScreenshotConfig(maxHeightPx = 4097) }
    }

    @Test fun `quality must be in 0 to 100`() {
        assertFailsWith<IllegalArgumentException> { ScreenshotConfig(quality = -1) }
        assertFailsWith<IllegalArgumentException> { ScreenshotConfig(quality = 101) }
    }

    @Test fun `quality boundaries are valid`() {
        assertEquals(0, ScreenshotConfig(quality = 0).quality)
        assertEquals(100, ScreenshotConfig(quality = 100).quality)
    }

    @Test fun `maxCapturesPerMinute must be in 1 to 60`() {
        assertFailsWith<IllegalArgumentException> { ScreenshotConfig(maxCapturesPerMinute = 0) }
        assertFailsWith<IllegalArgumentException> { ScreenshotConfig(maxCapturesPerMinute = 61) }
    }

    @Test fun `maxCapturesPerMinute boundaries are valid`() {
        assertEquals(1, ScreenshotConfig(maxCapturesPerMinute = 1).maxCapturesPerMinute)
        assertEquals(60, ScreenshotConfig(maxCapturesPerMinute = 60).maxCapturesPerMinute)
    }

    @Test fun `maxPayloadKb must be in 1 to 4096`() {
        assertFailsWith<IllegalArgumentException> { ScreenshotConfig(maxPayloadKb = 0) }
        assertFailsWith<IllegalArgumentException> { ScreenshotConfig(maxPayloadKb = 4097) }
    }

    @Test fun `maxPayloadKb boundaries are valid`() {
        assertEquals(1, ScreenshotConfig(maxPayloadKb = 1).maxPayloadKb)
        assertEquals(4096, ScreenshotConfig(maxPayloadKb = 4096).maxPayloadKb)
    }

    @Test fun `custom dimensions are accepted`() {
        val config = ScreenshotConfig(maxWidthPx = 1920, maxHeightPx = 1080)
        assertEquals(1920, config.maxWidthPx)
        assertEquals(1080, config.maxHeightPx)
    }

    @Test fun `captureOnScreenView defaults to false`() {
        assertFalse(ScreenshotConfig().captureOnScreenView)
    }

    @Test fun `captureOnScreenView can be enabled`() {
        assertTrue(ScreenshotConfig(captureOnScreenView = true).captureOnScreenView)
    }

    @Test fun `screenViewDelayMs defaults to 500`() {
        assertEquals(500L, ScreenshotConfig().screenViewDelayMs)
    }

    @Test fun `screenViewDelayMs can be zero`() {
        assertEquals(0L, ScreenshotConfig(screenViewDelayMs = 0).screenViewDelayMs)
    }

    @Test fun `screenViewDelayMs must not exceed 5000`() {
        assertFailsWith<IllegalArgumentException> { ScreenshotConfig(screenViewDelayMs = 5001) }
    }

    @Test fun `screenViewDelayMs must not be negative`() {
        assertFailsWith<IllegalArgumentException> { ScreenshotConfig(screenViewDelayMs = -1) }
    }

    @Test fun `screenViewDelayMs boundaries are valid`() {
        assertEquals(0L, ScreenshotConfig(screenViewDelayMs = 0).screenViewDelayMs)
        assertEquals(5000L, ScreenshotConfig(screenViewDelayMs = 5000).screenViewDelayMs)
    }
}
