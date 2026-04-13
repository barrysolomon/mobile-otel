/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.debug

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.android.mobile.instrumentation.InstrumentationContext
import io.opentelemetry.android.mobile.instrumentation.MobileSessionProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for [DebugWidgetInstrumentation] — validates lifecycle, config gating,
 * and instrumentation name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DebugWidgetInstrumentationTest {

    private lateinit var app: Application
    private lateinit var mockContext: InstrumentationContext

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        val mockSession = mockk<MobileSessionProvider>(relaxed = true)
        every { mockSession.getSessionId() } returns "test-session-123"

        mockContext = mockk(relaxed = true)
        every { mockContext.application } returns app
        every { mockContext.sessionProvider } returns mockSession
    }

    // ── Instrumentation name ────────────────────────────────────────────────

    @Test
    fun `instrumentation name is correct`() {
        val instr = DebugWidgetInstrumentation()
        assertEquals("io.opentelemetry.android.mobile.debug-widget", instr.instrumentationName)
    }

    // ── Disabled config ─────────────────────────────────────────────────────

    @Test
    fun `install with disabled config is no-op`() {
        val instr = DebugWidgetInstrumentation(DebugWidgetConfig(enabled = false))
        instr.install(app, mockContext)
        // No exception, no lifecycle callbacks registered.
        // Uninstall should also be safe:
        instr.uninstall()
    }

    // ── Enabled config ──────────────────────────────────────────────────────

    @Test
    fun `install with enabled config does not crash`() {
        val instr = DebugWidgetInstrumentation(DebugWidgetConfig(enabled = true))
        instr.install(app, mockContext)
        // Lifecycle callbacks registered. No activity active yet so no views attached.
        instr.uninstall()
    }

    @Test
    fun `uninstall without prior install is safe`() {
        val instr = DebugWidgetInstrumentation(DebugWidgetConfig(enabled = true))
        instr.uninstall() // Should not throw
    }

    @Test
    fun `double uninstall is safe`() {
        val instr = DebugWidgetInstrumentation(DebugWidgetConfig(enabled = true))
        instr.install(app, mockContext)
        instr.uninstall()
        instr.uninstall() // Should not throw
    }

    @Test
    fun `install with custom config corners does not crash`() {
        for (corner in DebugWidgetConfig.Corner.entries) {
            val instr = DebugWidgetInstrumentation(
                DebugWidgetConfig(enabled = true, initialCorner = corner)
            )
            instr.install(app, mockContext)
            instr.uninstall()
        }
    }

    @Test
    fun `install with custom refresh interval does not crash`() {
        val instr = DebugWidgetInstrumentation(
            DebugWidgetConfig(enabled = true, refreshIntervalMs = 500)
        )
        instr.install(app, mockContext)
        instr.uninstall()
    }
}
