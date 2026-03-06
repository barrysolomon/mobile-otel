// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.mockk.*
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FreezeInstrumentationTest {
    @get:Rule val otelRule = OpenTelemetryRule.create()

    @Test fun `instrumentationName is correct`() {
        assertEquals("io.opentelemetry.android.mobile.freeze", FreezeInstrumentation().instrumentationName)
    }

    @Test fun `install starts watchdog`() {
        val app = mockk<Application>(relaxed = true)
        val hub = WindowEventHub()
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        val inst = FreezeInstrumentation()
        assertFalse(inst.isRunning)

        inst.install(app, ctx)
        assertTrue(inst.isRunning)

        inst.uninstall() // cleanup
    }

    @Test fun `uninstall stops watchdog`() {
        val app = mockk<Application>(relaxed = true)
        val hub = WindowEventHub()
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        val inst = FreezeInstrumentation()
        inst.install(app, ctx)
        assertTrue(inst.isRunning)

        inst.uninstall()
        assertFalse(inst.isRunning)
    }

    @Test fun `install with disabled config does not start watchdog`() {
        val app = mockk<Application>(relaxed = true)
        val hub = WindowEventHub()
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        val inst = FreezeInstrumentation(FreezeConfig(enabled = false))
        inst.install(app, ctx)

        assertFalse(inst.isRunning)
    }
}
