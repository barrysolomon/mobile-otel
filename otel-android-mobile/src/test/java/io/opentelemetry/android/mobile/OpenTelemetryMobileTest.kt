// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.android.mobile.instrumentation.MobileSessionProvider
import io.opentelemetry.android.mobile.instrumentation.OTelMobileHandle
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OpenTelemetryMobileTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    @Test fun `openTelemetry returns the SDK instance`() {
        assertNotNull(createMobile().openTelemetry)
    }

    @Test fun `sessionId delegates to sessionProvider`() {
        val sp = mockk<MobileSessionProvider> {
            every { getSessionId() } returns "test-session-123"
        }
        assertEquals("test-session-123", createMobile(sessionProvider = sp).sessionId)
    }

    @Test fun `getTracer returns a tracer`() {
        assertNotNull(createMobile().getTracer("test"))
    }

    @Test fun `getLogger returns a logger`() {
        assertNotNull(createMobile().getLogger("test"))
    }

    @Test fun `getMeter returns a meter`() {
        assertNotNull(createMobile().getMeter("test"))
    }

    @Test fun `shutdown delegates to handle stop`() {
        val handle = mockk<OTelMobileHandle>(relaxed = true)
        createMobile(handle = handle).shutdown(10)
        verify { handle.stop(10) }
    }

    private fun createMobile(
        handle: OTelMobileHandle = mockk(relaxed = true),
        sessionProvider: MobileSessionProvider = mockk { every { getSessionId() } returns "default" },
        loggerProvider: MobileLoggerProvider = mockk(relaxed = true)
    ) = OpenTelemetryMobile(otelRule.openTelemetry, handle, sessionProvider, loggerProvider)
}
