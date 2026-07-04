// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.mockk.*
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class OTelMobileBuilderTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    @Test fun `build returns non-null handle`() {
        val app = mockk<Application>(relaxed = true)
        val handle = OTelMobileBuilder(app, otelRule.openTelemetry).build()
        assertNotNull(handle)
    }

    @Test fun `build installs all added instrumentations`() {
        val app = mockk<Application>(relaxed = true)
        val i1 = mockk<MobileInstrumentation>(relaxed = true)
        val i2 = mockk<MobileInstrumentation>(relaxed = true)

        OTelMobileBuilder(app, otelRule.openTelemetry)
            .addInstrumentation(i1)
            .addInstrumentation(i2)
            .build()

        verify { i1.install(app, any<InstrumentationContext>()) }
        verify { i2.install(app, any<InstrumentationContext>()) }
    }

    @Test fun `setSessionProvider passes custom provider to InstrumentationContext`() {
        val app = mockk<Application>(relaxed = true)
        val sessionProvider = mockk<MobileSessionProvider>(relaxed = true)
        val instrumentation = mockk<MobileInstrumentation>(relaxed = true)
        every { instrumentation.instrumentationName } returns "test"

        val contextSlot = slot<InstrumentationContext>()
        every { instrumentation.install(any(), capture(contextSlot)) } just runs

        OTelMobileBuilder(app, otelRule.openTelemetry)
            .setSessionProvider(sessionProvider)
            .addInstrumentation(instrumentation)
            .build()

        assertSame(sessionProvider, contextSlot.captured.sessionProvider)
    }

    @Test fun `handle stop calls uninstall on all instrumentations`() {
        val app = mockk<Application>(relaxed = true)
        val instrumentation = mockk<MobileInstrumentation>(relaxed = true)

        val handle = OTelMobileBuilder(app, otelRule.openTelemetry)
            .addInstrumentation(instrumentation)
            .build()

        handle.stop()
        verify { instrumentation.uninstall() }
    }

    @Test fun `handle getTracer returns valid tracer`() {
        val app = mockk<Application>(relaxed = true)
        val handle = OTelMobileBuilder(app, otelRule.openTelemetry).build()
        assertNotNull(handle.getTracer("test"))
    }

    @Test fun `handle getLogger returns valid logger`() {
        val app = mockk<Application>(relaxed = true)
        val handle = OTelMobileBuilder(app, otelRule.openTelemetry).build()
        assertNotNull(handle.getLogger("test"))
    }

    @Test fun `handle getMeter returns valid meter`() {
        val app = mockk<Application>(relaxed = true)
        val handle = OTelMobileBuilder(app, otelRule.openTelemetry).build()
        assertNotNull(handle.getMeter("test"))
    }

    @Test fun `discoverAllInstrumentations with manual module does not crash`() {
        val app = mockk<Application>(relaxed = true)
        val manual = mockk<MobileInstrumentation>(relaxed = true)
        every { manual.instrumentationName } returns "test-module"

        val handle = OTelMobileBuilder(app, otelRule.openTelemetry)
            .addInstrumentation(manual)
            .discoverAllInstrumentations()
            .build()

        assertNotNull(handle)
        verify { manual.install(app, any<InstrumentationContext>()) }
    }
}
