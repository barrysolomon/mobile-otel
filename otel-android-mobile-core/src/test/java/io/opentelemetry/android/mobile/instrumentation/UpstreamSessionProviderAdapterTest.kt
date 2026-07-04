// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import io.opentelemetry.android.session.SessionProvider
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class UpstreamSessionProviderAdapterTest {
    private val upstream = object : SessionProvider {
        override fun getSessionId(): String = "upstream-session-123"
    }
    private val adapter = UpstreamSessionProviderAdapter(upstream)

    @Test fun `getSessionId delegates to upstream`() {
        assertEquals("upstream-session-123", adapter.getSessionId())
    }
    @Test fun `getViewId returns empty string`() { assertEquals("", adapter.getViewId()) }
    @Test fun `getCurrentScreenName returns null`() { assertNull(adapter.getCurrentScreenName()) }
    @Test fun `getPreviousScreenName returns null`() { assertNull(adapter.getPreviousScreenName()) }
    @Test fun `getTimeOnScreenMs returns zero`() { assertEquals(0L, adapter.getTimeOnScreenMs()) }
    @Test fun `onAppForeground returns false`() { assertFalse(adapter.onAppForeground(System.currentTimeMillis())) }
    @Test fun `onScreenView is no-op`() { adapter.onScreenView("SomeScreen") }
    @Test fun `onAppBackground is no-op`() { adapter.onAppBackground(System.currentTimeMillis()) }
    @Test fun `sessionHadError returns false`() { assertFalse(adapter.sessionHadError()) }
}
