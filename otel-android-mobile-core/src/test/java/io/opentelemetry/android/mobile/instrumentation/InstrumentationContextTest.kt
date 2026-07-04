// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.mockk.mockk
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class InstrumentationContextTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    @Test fun `tracer delegates to openTelemetry`() {
        val ctx = makeContext()
        assertNotNull(ctx.tracer("test.scope"))
    }

    @Test fun `logger delegates to openTelemetry`() {
        val ctx = makeContext()
        assertNotNull(ctx.logger("test.scope"))
    }

    @Test fun `meter delegates to openTelemetry`() {
        val ctx = makeContext()
        assertNotNull(ctx.meter("test.scope"))
    }

    @Test fun `sessionProvider is accessible`() {
        val sessionProvider = DefaultMobileSessionProvider()
        val ctx = InstrumentationContext(
            otelRule.openTelemetry,
            sessionProvider,
            WindowEventHub(),
            mockk()
        )
        assertSame(sessionProvider, ctx.sessionProvider)
    }

    @Test fun `windowEventHub is accessible`() {
        val hub = WindowEventHub()
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, mockk())
        assertSame(hub, ctx.windowEventHub)
    }

    private fun makeContext(): InstrumentationContext = InstrumentationContext(
        otelRule.openTelemetry,
        DefaultMobileSessionProvider(),
        WindowEventHub(),
        mockk<Application>()
    )
}
