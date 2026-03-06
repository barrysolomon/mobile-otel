// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import android.view.MotionEvent
import io.mockk.*
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class TapInstrumentationTest {
    @get:Rule val otelRule = OpenTelemetryRule.create()

    @Test fun `instrumentationName is correct`() {
        assertEquals("io.opentelemetry.android.mobile.tap", TapInstrumentation().instrumentationName)
    }

    @Test fun `registers as WindowEventListener on install`() {
        val app = mockk<Application>(relaxed = true)
        every { app.registerActivityLifecycleCallbacks(any()) } just runs
        val hub = spyk(WindowEventHub())
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        TapInstrumentation().install(app, ctx)

        verify { hub.addListener(any()) }
    }

    @Test fun `uninstall removes WindowEventListener`() {
        val app = mockk<Application>(relaxed = true)
        every { app.registerActivityLifecycleCallbacks(any()) } just runs
        every { app.unregisterActivityLifecycleCallbacks(any()) } just runs
        val hub = spyk(WindowEventHub())
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        val inst = TapInstrumentation()
        inst.install(app, ctx)
        inst.uninstall()

        verify { hub.removeListener(any()) }
    }
}
