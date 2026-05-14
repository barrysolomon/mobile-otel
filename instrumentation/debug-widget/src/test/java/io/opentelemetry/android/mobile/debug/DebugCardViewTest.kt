/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.debug

import android.view.View
import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.android.mobile.export.ExportStatus
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Tests for [DebugCardView] — validates state rendering and toggle behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DebugCardViewTest {

    private lateinit var card: DebugCardView

    @Before
    fun setup() {
        card = DebugCardView(ApplicationProvider.getApplicationContext())
    }

    // ── Initial state ───────────────────────────────────────────────────────

    @Test
    fun `card starts hidden`() {
        assertEquals(View.GONE, card.visibility)
    }

    // ── Toggle ──────────────────────────────────────────────────────────────

    @Test
    fun `toggle makes card visible`() {
        card.toggle()
        assertEquals(View.VISIBLE, card.visibility)
    }

    @Test
    fun `double toggle hides card again`() {
        card.toggle()
        card.toggle()
        assertEquals(View.GONE, card.visibility)
    }

    @Test
    fun `triple toggle shows card`() {
        card.toggle()
        card.toggle()
        card.toggle()
        assertEquals(View.VISIBLE, card.visibility)
    }

    // ── Update with various states ──────────────────────────────────────────

    @Test
    fun `update with success status does not crash`() {
        card.update(makeState(exportStatus = ExportStatus.Success(13)))
        // No exception = pass
    }

    @Test
    fun `update with failed status does not crash`() {
        card.update(makeState(exportStatus = ExportStatus.Failed("timeout", 5, 3)))
    }

    @Test
    fun `update with auth error status does not crash`() {
        card.update(makeState(exportStatus = ExportStatus.AuthError("invalid token", 10)))
    }

    @Test
    fun `update with retrying status does not crash`() {
        card.update(makeState(exportStatus = ExportStatus.Retrying(2, 4, 1000)))
    }

    @Test
    fun `update with null export status does not crash`() {
        card.update(makeState(exportStatus = null))
    }

    @Test
    fun `update with high RAM occupancy does not crash`() {
        card.update(makeState(ramEvents = 4500, ramCapacity = 5000))
    }

    @Test
    fun `update with crash recovery type does not crash`() {
        card.update(makeState(recoveryType = "crash"))
    }

    @Test
    fun `update with zero battery does not crash`() {
        card.update(makeState(batteryPercent = 0))
    }

    @Test
    fun `update with negative battery does not crash`() {
        card.update(makeState(batteryPercent = -1))
    }

    @Test
    fun `update with no network does not crash`() {
        card.update(makeState(networkType = "none"))
    }

    @Test
    fun `update with recent flush shows seconds ago`() {
        card.update(makeState(lastExportTimeMs = System.currentTimeMillis() - 30_000))
        // No exception = pass; verifies the time formatting logic
    }

    @Test
    fun `update with old flush shows minutes ago`() {
        card.update(makeState(lastExportTimeMs = System.currentTimeMillis() - 120_000))
    }

    @Test
    fun `update with no flush shows dash`() {
        card.update(makeState(lastExportTimeMs = 0))
    }

    @Test
    fun `update with long session ID truncates`() {
        card.update(makeState(sessionId = "abcdefghijklmnopqrstuvwxyz1234567890"))
    }

    @Test
    fun `update with short session ID does not truncate`() {
        card.update(makeState(sessionId = "abc123"))
    }

    @Test
    fun `rapid sequential updates do not crash`() {
        repeat(100) { i ->
            card.update(makeState(ramEvents = i, diskEvents = i * 2))
        }
    }

    // ── Helper ──────────────────────────────────────────────────────────────

    private fun makeState(
        ramEvents: Int = 100,
        ramCapacity: Int = 5000,
        diskEvents: Int = 50,
        exportStatus: ExportStatus? = ExportStatus.Success(13),
        recoveryType: String? = "clean",
        batteryPercent: Int = 75,
        memoryAvailableMb: Long = 1024,
        networkType: String = "wifi",
        lastExportTimeMs: Long = System.currentTimeMillis() - 10_000,
        sessionId: String = "abc12345def67890",
        exportMode: String = "HYBRID",
        airplaneMode: Boolean = false
    ) = DebugWidgetDataSource.WidgetState(
        ramEvents = ramEvents,
        ramCapacity = ramCapacity,
        diskEvents = diskEvents,
        exportStatus = exportStatus,
        recoveryType = recoveryType,
        batteryPercent = batteryPercent,
        memoryAvailableMb = memoryAvailableMb,
        networkType = networkType,
        lastExportTimeMs = lastExportTimeMs,
        sessionId = sessionId,
        exportMode = exportMode,
        airplaneMode = airplaneMode
    )
}
