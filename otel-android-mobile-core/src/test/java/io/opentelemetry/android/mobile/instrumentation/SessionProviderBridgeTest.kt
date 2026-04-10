// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import io.opentelemetry.android.session.SessionProvider
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionProviderBridgeTest {
    @Test fun `DefaultMobileSessionProvider is a SessionProvider`() {
        val provider: MobileSessionProvider = DefaultMobileSessionProvider()
        assertTrue(provider is SessionProvider)
    }

    @Test fun `SessionProvider getSessionId delegates to MobileSessionProvider`() {
        val provider = DefaultMobileSessionProvider()
        val asUpstream: SessionProvider = provider
        assertEquals(provider.getSessionId(), asUpstream.getSessionId())
    }
}
