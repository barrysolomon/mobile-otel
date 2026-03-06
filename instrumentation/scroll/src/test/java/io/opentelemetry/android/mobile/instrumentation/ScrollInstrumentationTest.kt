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

class ScrollInstrumentationTest {
    @get:Rule val otelRule = OpenTelemetryRule.create()

    @Test fun `instrumentationName is correct`() {
        assertEquals("io.opentelemetry.android.mobile.scroll", ScrollInstrumentation().instrumentationName)
    }

    @Test fun `registers as WindowEventListener on install`() {
        val app = mockk<Application>(relaxed = true)
        val hub = spyk(WindowEventHub())
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        ScrollInstrumentation().install(app, ctx)

        verify { hub.addListener(any()) }
    }

    @Test fun `uninstall removes WindowEventListener`() {
        val app = mockk<Application>(relaxed = true)
        val hub = spyk(WindowEventHub())
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        val inst = ScrollInstrumentation()
        inst.install(app, ctx)
        inst.uninstall()

        verify { hub.removeListener(any()) }
    }

    @Test fun `emitScroll emits log record with correct body`() {
        val app = mockk<Application>(relaxed = true)
        val hub = WindowEventHub()
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        val inst = ScrollInstrumentation()
        inst.install(app, ctx)
        inst.emitScroll(0, 100)

        assertTrue(otelRule.logRecords.any { it.body.asString() == "ui.scroll" })
    }
}
