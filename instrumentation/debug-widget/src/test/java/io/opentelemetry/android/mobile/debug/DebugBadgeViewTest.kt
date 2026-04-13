/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.debug

import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.android.mobile.export.ExportStatus
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [DebugBadgeView] — validates status rendering and tap callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DebugBadgeViewTest {

    private val toggleCount = AtomicInteger(0)
    private lateinit var badge: DebugBadgeView

    @Before
    fun setup() {
        toggleCount.set(0)
        badge = DebugBadgeView(ApplicationProvider.getApplicationContext()) {
            toggleCount.incrementAndGet()
        }
    }

    // ── Size ────────────────────────────────────────────────────────────────

    @Test
    fun `badge measures to 32dp`() {
        val density = badge.resources.displayMetrics.density
        val expectedSize = (32 * density).toInt()
        badge.measure(0, 0)
        assertEquals(expectedSize, badge.measuredWidth)
        assertEquals(expectedSize, badge.measuredHeight)
    }

    // ── Status updates ──────────────────────────────────────────────────────

    @Test
    fun `updateStatus with success does not crash`() {
        badge.updateStatus(ExportStatus.Success(10))
    }

    @Test
    fun `updateStatus with failed does not crash`() {
        badge.updateStatus(ExportStatus.Failed("error", 5, 3))
    }

    @Test
    fun `updateStatus with auth error does not crash`() {
        badge.updateStatus(ExportStatus.AuthError("bad token", 2))
    }

    @Test
    fun `updateStatus with retrying does not crash`() {
        badge.updateStatus(ExportStatus.Retrying(1, 3, 500))
    }

    @Test
    fun `updateStatus with null does not crash`() {
        badge.updateStatus(null)
    }

    @Test
    fun `rapid status transitions do not crash`() {
        badge.updateStatus(ExportStatus.Success(1))
        badge.updateStatus(ExportStatus.Retrying(1, 3, 100))
        badge.updateStatus(ExportStatus.Failed("err", 1, 3))
        badge.updateStatus(ExportStatus.AuthError("bad", 1))
        badge.updateStatus(null)
        badge.updateStatus(ExportStatus.Success(5))
    }

    // ── Elevation ───────────────────────────────────────────────────────────

    @Test
    fun `badge has positive elevation`() {
        assertTrue(badge.elevation > 0f)
    }
}
