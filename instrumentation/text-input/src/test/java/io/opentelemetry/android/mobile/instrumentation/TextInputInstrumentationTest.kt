// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.mockk.*
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextInputInstrumentationTest {
    @get:Rule val otelRule = OpenTelemetryRule.create()

    @Test fun `instrumentationName is correct`() {
        assertEquals("io.opentelemetry.android.mobile.text_input", TextInputInstrumentation().instrumentationName)
    }

    @Test fun `registers as WindowEventListener on install`() {
        val app = mockk<Application>(relaxed = true)
        val hub = spyk(WindowEventHub())
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        TextInputInstrumentation().install(app, ctx)

        verify { hub.addListener(any()) }
    }

    @Test fun `uninstall removes WindowEventListener`() {
        val app = mockk<Application>(relaxed = true)
        val hub = spyk(WindowEventHub())
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        val inst = TextInputInstrumentation()
        inst.install(app, ctx)
        inst.uninstall()

        verify { hub.removeListener(any()) }
    }

    @Test fun `emitTextInput emits log record with correct body`() {
        val app = mockk<Application>(relaxed = true)
        val hub = WindowEventHub()
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        val inst = TextInputInstrumentation()
        inst.install(app, ctx)
        inst.emitTextInput(resourceId = "email_field", enabled = true)

        assertTrue(otelRule.logRecords.any { it.body.asString() == "ui.text_input" })
    }
}
