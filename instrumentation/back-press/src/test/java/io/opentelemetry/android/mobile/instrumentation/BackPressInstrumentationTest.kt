// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import android.view.KeyEvent
import io.mockk.*
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackPressInstrumentationTest {
    @get:Rule val otelRule = OpenTelemetryRule.create()

    @Test fun `instrumentationName is correct`() {
        assertEquals("io.opentelemetry.android.mobile.back_press", BackPressInstrumentation().instrumentationName)
    }

    @Test fun `registers as WindowEventListener on install`() {
        val app = mockk<Application>(relaxed = true)
        val hub = spyk(WindowEventHub())
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        BackPressInstrumentation().install(app, ctx)

        verify { hub.addListener(any()) }
    }

    @Test fun `uninstall removes WindowEventListener`() {
        val app = mockk<Application>(relaxed = true)
        val hub = spyk(WindowEventHub())
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        val inst = BackPressInstrumentation()
        inst.install(app, ctx)
        inst.uninstall()

        verify { hub.removeListener(any()) }
    }

    @Test fun `back press emits log record`() {
        val app = mockk<Application>(relaxed = true)
        val hub = WindowEventHub()
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        BackPressInstrumentation().install(app, ctx)

        val event = mockk<KeyEvent>()
        every { event.keyCode } returns KeyEvent.KEYCODE_BACK
        every { event.action } returns KeyEvent.ACTION_UP
        hub.dispatchKeyEvent(event, mockk(relaxed = true))

        assertTrue(otelRule.logRecords.any { it.bodyValue?.asString() == "ui.back_press" })
    }
}
