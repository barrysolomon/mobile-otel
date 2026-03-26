// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WireframeConfigTest {

    @Test fun `default config has sensible values`() {
        val config = WireframeConfig()
        assertTrue(config.enabled)
        assertTrue(config.captureOnScreenView)
        assertFalse(config.captureOnTap)
        assertTrue(config.captureOnError)
        assertEquals(30, config.maxDepth)
        assertTrue(config.includeResourceIds)
        assertFalse(config.includeTextHints)
        assertFalse(config.includeContentDescription)
        assertTrue(config.includeClickableState)
        assertEquals(30, config.maxCapturesPerMinute)
    }

    @Test fun `can disable`() {
        assertFalse(WireframeConfig(enabled = false).enabled)
    }

    @Test fun `can enable tap capture`() {
        assertTrue(WireframeConfig(captureOnTap = true).captureOnTap)
    }

    @Test fun `can disable screen view capture`() {
        assertFalse(WireframeConfig(captureOnScreenView = false).captureOnScreenView)
    }

    @Test fun `can enable text hints`() {
        assertTrue(WireframeConfig(includeTextHints = true).includeTextHints)
    }

    @Test fun `can enable content descriptions`() {
        assertTrue(WireframeConfig(includeContentDescription = true).includeContentDescription)
    }

    @Test fun `can disable resource IDs`() {
        assertFalse(WireframeConfig(includeResourceIds = false).includeResourceIds)
    }

    @Test fun `can disable clickable state`() {
        assertFalse(WireframeConfig(includeClickableState = false).includeClickableState)
    }

    @Test fun `maxDepth must be in 1 to 100`() {
        assertFailsWith<IllegalArgumentException> { WireframeConfig(maxDepth = 0) }
        assertFailsWith<IllegalArgumentException> { WireframeConfig(maxDepth = 101) }
    }

    @Test fun `maxDepth boundaries are valid`() {
        assertEquals(1, WireframeConfig(maxDepth = 1).maxDepth)
        assertEquals(100, WireframeConfig(maxDepth = 100).maxDepth)
    }

    @Test fun `maxCapturesPerMinute must be in 1 to 120`() {
        assertFailsWith<IllegalArgumentException> { WireframeConfig(maxCapturesPerMinute = 0) }
        assertFailsWith<IllegalArgumentException> { WireframeConfig(maxCapturesPerMinute = 121) }
    }

    @Test fun `maxCapturesPerMinute boundaries are valid`() {
        assertEquals(1, WireframeConfig(maxCapturesPerMinute = 1).maxCapturesPerMinute)
        assertEquals(120, WireframeConfig(maxCapturesPerMinute = 120).maxCapturesPerMinute)
    }
}
