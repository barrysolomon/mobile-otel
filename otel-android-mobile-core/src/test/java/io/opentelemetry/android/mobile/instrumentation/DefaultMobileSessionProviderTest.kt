// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultMobileSessionProviderTest {

    @Test fun `getSessionId returns non-empty string`() {
        val provider = DefaultMobileSessionProvider()
        assertTrue(provider.getSessionId().isNotEmpty())
    }

    @Test fun `two providers have different session ids`() {
        val p1 = DefaultMobileSessionProvider()
        val p2 = DefaultMobileSessionProvider()
        assertNotEquals(p1.getSessionId(), p2.getSessionId())
    }

    @Test fun `getViewId increments on each screen view`() {
        val provider = DefaultMobileSessionProvider()
        provider.onScreenView("ScreenA")
        val v1 = provider.getViewId()
        provider.onScreenView("ScreenB")
        val v2 = provider.getViewId()
        assertNotEquals(v1, v2)
    }

    @Test fun `getCurrentScreenName returns null before any screen view`() {
        val provider = DefaultMobileSessionProvider()
        assertNull(provider.getCurrentScreenName())
    }

    @Test fun `getCurrentScreenName returns last screen viewed`() {
        val provider = DefaultMobileSessionProvider()
        provider.onScreenView("HomeScreen")
        assertEquals("HomeScreen", provider.getCurrentScreenName())
        provider.onScreenView("DetailScreen")
        assertEquals("DetailScreen", provider.getCurrentScreenName())
    }

    @Test fun `onAppForeground returns false when within renewal window`() {
        val provider = DefaultMobileSessionProvider(renewalMs = 60 * 60 * 1000L)
        val now = System.currentTimeMillis()
        provider.onAppBackground(now - 1000L)  // went background 1s ago
        val renewed = provider.onAppForeground(now)
        assertEquals(false, renewed)
    }

    @Test fun `onAppForeground returns true and generates new session when outside renewal window`() {
        val provider = DefaultMobileSessionProvider(renewalMs = 100L)
        val originalId = provider.getSessionId()
        val now = System.currentTimeMillis()
        provider.onAppBackground(now - 1000L)  // went background 1s ago, renewal is 100ms
        val renewed = provider.onAppForeground(now)
        assertEquals(true, renewed)
        assertNotEquals(originalId, provider.getSessionId())
    }

    @Test fun `onAppForeground returns false when no background time recorded`() {
        val provider = DefaultMobileSessionProvider()
        val renewed = provider.onAppForeground(System.currentTimeMillis())
        assertEquals(false, renewed)
    }
}
