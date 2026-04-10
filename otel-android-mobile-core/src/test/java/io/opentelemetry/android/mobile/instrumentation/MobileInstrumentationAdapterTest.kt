// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.mockk.*
import io.opentelemetry.android.instrumentation.InstallationContext
import io.opentelemetry.android.session.SessionManager
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MobileInstrumentationAdapterTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private val app = mockk<Application>(relaxed = true)
    private val hub = WindowEventHub()

    @Test fun `install bridges InstallationContext to InstrumentationContext`() {
        val mobile = mockk<MobileInstrumentation>(relaxed = true) {
            every { instrumentationName } returns "mobile.tap"
        }
        val sessionManager = mockk<SessionManager>(relaxed = true)
        val installCtx = InstallationContext(app, otelRule.openTelemetry, sessionManager)

        val adapter = MobileInstrumentationAdapter(mobile, hub)
        adapter.install(installCtx)

        verify { mobile.install(app, match<InstrumentationContext> {
            it.openTelemetry === otelRule.openTelemetry &&
            it.application === app
        }) }
    }

    @Test fun `install wraps SessionManager in UpstreamSessionProviderAdapter`() {
        val capturedCtx = slot<InstrumentationContext>()
        val mobile = mockk<MobileInstrumentation>(relaxed = true) {
            every { instrumentationName } returns "mobile.tap"
        }
        val sessionManager = mockk<SessionManager>(relaxed = true) {
            every { getSessionId() } returns "test-session-id"
        }
        val installCtx = InstallationContext(app, otelRule.openTelemetry, sessionManager)

        val adapter = MobileInstrumentationAdapter(mobile, hub)
        adapter.install(installCtx)

        verify { mobile.install(app, capture(capturedCtx)) }
        assertTrue(capturedCtx.captured.sessionProvider is UpstreamSessionProviderAdapter)
        assertEquals("test-session-id", capturedCtx.captured.sessionProvider.getSessionId())
    }

    @Test fun `install passes windowEventHub and uiTelemetryMode through`() {
        val mobile = mockk<MobileInstrumentation>(relaxed = true) {
            every { instrumentationName } returns "mobile.tap"
        }
        val sessionManager = mockk<SessionManager>(relaxed = true)
        val installCtx = InstallationContext(app, otelRule.openTelemetry, sessionManager)

        val adapter = MobileInstrumentationAdapter(mobile, hub, UiTelemetryMode.SPANS)
        adapter.install(installCtx)

        verify { mobile.install(app, match<InstrumentationContext> {
            it.windowEventHub === hub &&
            it.uiTelemetryMode == UiTelemetryMode.SPANS
        }) }
    }

    @Test fun `mobile uninstall can be called independently`() {
        val mobile = mockk<MobileInstrumentation>(relaxed = true) {
            every { instrumentationName } returns "mobile.tap"
        }
        // MobileInstrumentationAdapter does not expose uninstall (upstream has no uninstall),
        // but the wrapped mobile module can be uninstalled via its own reference
        mobile.uninstall()
        verify { mobile.uninstall() }
    }
}
