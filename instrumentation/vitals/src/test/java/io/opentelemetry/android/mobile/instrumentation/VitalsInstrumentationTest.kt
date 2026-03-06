// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.mockk.mockk
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class VitalsInstrumentationTest {
    @get:Rule val otelRule = OpenTelemetryRule.create()

    @Test fun `instrumentationName is correct`() {
        assertEquals("io.opentelemetry.android.mobile.vitals", VitalsInstrumentation().instrumentationName)
    }

    @Test fun `install does not throw`() {
        val app = mockk<Application>(relaxed = true)
        val hub = WindowEventHub()
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)
        VitalsInstrumentation().install(app, ctx)
    }

    @Test fun `uninstall does not throw`() {
        val app = mockk<Application>(relaxed = true)
        val hub = WindowEventHub()
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)
        val inst = VitalsInstrumentation()
        inst.install(app, ctx)
        inst.uninstall()
    }
}
