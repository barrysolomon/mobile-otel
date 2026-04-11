// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.mockk.*
import io.opentelemetry.android.instrumentation.AndroidInstrumentation
import io.opentelemetry.android.instrumentation.InstallationContext
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class UpstreamInstrumentationAdapterTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private val app = mockk<Application>(relaxed = true)

    private fun makeContext(): InstrumentationContext =
        InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), WindowEventHub(), app)

    @Test fun `instrumentationName returns upstream name`() {
        val upstream = mockk<AndroidInstrumentation>(relaxed = true)
        every { upstream.name } returns "upstream.crash"
        val adapter = UpstreamInstrumentationAdapter(upstream)
        assertEquals("upstream.crash", adapter.instrumentationName)
    }

    @Test fun `install creates InstallationContext and calls upstream install`() {
        val upstream = mockk<AndroidInstrumentation>(relaxed = true)
        every { upstream.name } returns "upstream.session"
        val adapter = UpstreamInstrumentationAdapter(upstream)
        val ctx = makeContext()

        adapter.install(app, ctx)

        verify { upstream.install(match<InstallationContext> {
            it.application == app && it.openTelemetry === ctx.openTelemetry
        }) }
    }

    @Test fun `install passes SessionProvider that delegates to context sessionProvider`() {
        val upstream = mockk<AndroidInstrumentation>(relaxed = true)
        every { upstream.name } returns "upstream.session"
        val sessionProvider = DefaultMobileSessionProvider()
        val ctx = InstrumentationContext(otelRule.openTelemetry, sessionProvider, WindowEventHub(), app)
        val adapter = UpstreamInstrumentationAdapter(upstream)

        adapter.install(app, ctx)

        verify { upstream.install(match<InstallationContext> {
            it.sessionProvider.getSessionId() == sessionProvider.getSessionId()
        }) }
    }

    @Test fun `uninstall is a no-op and does not throw`() {
        val upstream = mockk<AndroidInstrumentation>(relaxed = true)
        every { upstream.name } returns "upstream.session"
        val adapter = UpstreamInstrumentationAdapter(upstream)

        adapter.uninstall()
    }
}
