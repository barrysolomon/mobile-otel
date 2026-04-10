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

    @Test fun `instrumentationName returns the name passed at construction`() {
        val upstream = mockk<AndroidInstrumentation>(relaxed = true)
        val adapter = UpstreamInstrumentationAdapter(upstream, "upstream.crash")
        assertEquals("upstream.crash", adapter.instrumentationName)
    }

    @Test fun `install creates InstallationContext and calls upstream install`() {
        val upstream = mockk<AndroidInstrumentation>(relaxed = true)
        val adapter = UpstreamInstrumentationAdapter(upstream, "upstream.session")
        val ctx = makeContext()

        adapter.install(app, ctx)

        verify { upstream.install(match<InstallationContext> {
            it.application === app && it.openTelemetry === ctx.openTelemetry
        }) }
    }

    @Test fun `install passes SessionManager that delegates to context sessionProvider`() {
        val upstream = mockk<AndroidInstrumentation>(relaxed = true)
        val sessionProvider = DefaultMobileSessionProvider()
        val ctx = InstrumentationContext(otelRule.openTelemetry, sessionProvider, WindowEventHub(), app)
        val adapter = UpstreamInstrumentationAdapter(upstream, "upstream.session")

        adapter.install(app, ctx)

        verify { upstream.install(match<InstallationContext> {
            it.sessionManager.getSessionId() == sessionProvider.getSessionId()
        }) }
    }

    @Test fun `uninstall is a no-op and does not throw`() {
        val upstream = mockk<AndroidInstrumentation>(relaxed = true)
        val adapter = UpstreamInstrumentationAdapter(upstream, "upstream.session")

        // uninstall before install -- should be safe (default no-op from interface)
        adapter.uninstall()
    }
}
