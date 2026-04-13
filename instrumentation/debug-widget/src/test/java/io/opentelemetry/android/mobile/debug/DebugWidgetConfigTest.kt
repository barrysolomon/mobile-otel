/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.debug

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Tests for [DebugWidgetConfig] — validates defaults, validation, and corner enum.
 */
class DebugWidgetConfigTest {

    // ── Defaults ────────────────────────────────────────────────────────────

    @Test
    fun `default config is disabled`() {
        val config = DebugWidgetConfig()
        assertFalse(config.enabled)
    }

    @Test
    fun `default refresh interval is 2000ms`() {
        val config = DebugWidgetConfig()
        assertEquals(2000L, config.refreshIntervalMs)
    }

    @Test
    fun `default corner is TOP_RIGHT`() {
        val config = DebugWidgetConfig()
        assertEquals(DebugWidgetConfig.Corner.TOP_RIGHT, config.initialCorner)
    }

    // ── Custom values ───────────────────────────────────────────────────────

    @Test
    fun `enabled config is enabled`() {
        val config = DebugWidgetConfig(enabled = true)
        assertTrue(config.enabled)
    }

    @Test
    fun `custom refresh interval is respected`() {
        val config = DebugWidgetConfig(refreshIntervalMs = 5000)
        assertEquals(5000L, config.refreshIntervalMs)
    }

    @Test
    fun `all corner values are valid`() {
        for (corner in DebugWidgetConfig.Corner.entries) {
            val config = DebugWidgetConfig(initialCorner = corner)
            assertEquals(corner, config.initialCorner)
        }
    }

    @Test
    fun `four corners exist`() {
        assertEquals(4, DebugWidgetConfig.Corner.entries.size)
    }

    // ── Validation ──────────────────────────────────────────────────────────

    @Test
    fun `refresh interval at lower bound 500ms is valid`() {
        val config = DebugWidgetConfig(refreshIntervalMs = 500)
        assertEquals(500L, config.refreshIntervalMs)
    }

    @Test
    fun `refresh interval at upper bound 30000ms is valid`() {
        val config = DebugWidgetConfig(refreshIntervalMs = 30_000)
        assertEquals(30_000L, config.refreshIntervalMs)
    }

    @Test
    fun `refresh interval below 500ms throws`() {
        assertFailsWith<IllegalArgumentException> {
            DebugWidgetConfig(refreshIntervalMs = 499)
        }
    }

    @Test
    fun `refresh interval above 30000ms throws`() {
        assertFailsWith<IllegalArgumentException> {
            DebugWidgetConfig(refreshIntervalMs = 30_001)
        }
    }

    @Test
    fun `refresh interval of 0 throws`() {
        assertFailsWith<IllegalArgumentException> {
            DebugWidgetConfig(refreshIntervalMs = 0)
        }
    }

    @Test
    fun `negative refresh interval throws`() {
        assertFailsWith<IllegalArgumentException> {
            DebugWidgetConfig(refreshIntervalMs = -1000)
        }
    }

    // ── Data class behavior ─────────────────────────────────────────────────

    @Test
    fun `copy with enabled change works`() {
        val original = DebugWidgetConfig(enabled = false)
        val copy = original.copy(enabled = true)
        assertTrue(copy.enabled)
        assertFalse(original.enabled)
    }

    @Test
    fun `equality works`() {
        val a = DebugWidgetConfig(enabled = true, refreshIntervalMs = 1000, initialCorner = DebugWidgetConfig.Corner.BOTTOM_LEFT)
        val b = DebugWidgetConfig(enabled = true, refreshIntervalMs = 1000, initialCorner = DebugWidgetConfig.Corner.BOTTOM_LEFT)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
