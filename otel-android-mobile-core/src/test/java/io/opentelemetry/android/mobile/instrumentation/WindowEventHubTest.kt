// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import kotlin.test.assertEquals

class WindowEventHubTest {

    @Test fun `addListener receives dispatched touch events`() {
        val hub = WindowEventHub()
        val listener = mockk<WindowEventListener>(relaxed = true)
        val event = mockk<MotionEvent>()
        val window = mockk<Window>()

        hub.addListener(listener)
        hub.dispatchTouchEvent(event, window)

        verify(exactly = 1) { listener.onTouchEvent(event, window) }
    }

    @Test fun `removeListener no longer receives touch events`() {
        val hub = WindowEventHub()
        val listener = mockk<WindowEventListener>(relaxed = true)
        val event = mockk<MotionEvent>()
        val window = mockk<Window>()

        hub.addListener(listener)
        hub.removeListener(listener)
        hub.dispatchTouchEvent(event, window)

        verify(exactly = 0) { listener.onTouchEvent(any(), any()) }
    }

    @Test fun `multiple listeners all receive touch events`() {
        val hub = WindowEventHub()
        val l1 = mockk<WindowEventListener>(relaxed = true)
        val l2 = mockk<WindowEventListener>(relaxed = true)
        val event = mockk<MotionEvent>()
        val window = mockk<Window>()

        hub.addListener(l1)
        hub.addListener(l2)
        hub.dispatchTouchEvent(event, window)

        verify(exactly = 1) { l1.onTouchEvent(event, window) }
        verify(exactly = 1) { l2.onTouchEvent(event, window) }
    }

    @Test fun `addListener is idempotent — same listener not added twice`() {
        val hub = WindowEventHub()
        val listener = mockk<WindowEventListener>(relaxed = true)
        val event = mockk<MotionEvent>()
        val window = mockk<Window>()

        hub.addListener(listener)
        hub.addListener(listener)  // add same listener twice
        hub.dispatchTouchEvent(event, window)

        verify(exactly = 1) { listener.onTouchEvent(event, window) }
    }

    @Test fun `addListener receives dispatched key events`() {
        val hub = WindowEventHub()
        val listener = mockk<WindowEventListener>(relaxed = true)
        val event = mockk<KeyEvent>()
        val window = mockk<Window>()

        hub.addListener(listener)
        hub.dispatchKeyEvent(event, window)

        verify(exactly = 1) { listener.onKeyEvent(event, window) }
    }

    @Test fun `listenerCount returns correct count`() {
        val hub = WindowEventHub()
        assertEquals(0, hub.listenerCount())
        val l1 = mockk<WindowEventListener>(relaxed = true)
        val l2 = mockk<WindowEventListener>(relaxed = true)
        hub.addListener(l1)
        assertEquals(1, hub.listenerCount())
        hub.addListener(l2)
        assertEquals(2, hub.listenerCount())
        hub.removeListener(l1)
        assertEquals(1, hub.listenerCount())
    }

    @Test fun `dispatch with no listeners does not throw`() {
        val hub = WindowEventHub()
        val event = mockk<MotionEvent>()
        val window = mockk<Window>()
        hub.dispatchTouchEvent(event, window)  // must not throw
    }
}
