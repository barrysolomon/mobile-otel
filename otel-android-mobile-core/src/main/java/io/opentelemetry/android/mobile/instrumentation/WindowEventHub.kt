// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fan-out dispatcher for window touch and key events.
 *
 * The [InstrumentationRegistry] creates one [WindowEventHub] for the
 * application and registers a single [android.view.Window.Callback] wrapper
 * per activity window. All UI instrumentation modules ([WindowEventListener]
 * implementations) register with this hub — they receive events without
 * needing to wrap the window callback themselves.
 *
 * Thread-safe: uses [CopyOnWriteArrayList] so listeners can be added/removed
 * from any thread while dispatch proceeds on the main thread without locking.
 */
@Incubating
class WindowEventHub {

    private val listeners = CopyOnWriteArrayList<WindowEventListener>()

    /**
     * Registers [listener] to receive future touch and key events.
     * Idempotent — adding the same instance twice has no effect.
     */
    fun addListener(listener: WindowEventListener) {
        listeners.addIfAbsent(listener)
    }

    /**
     * Unregisters [listener]. No-op if [listener] was not registered.
     */
    fun removeListener(listener: WindowEventListener) {
        listeners.remove(listener)
    }

    /** Returns the number of currently registered listeners. */
    fun listenerCount(): Int = listeners.size

    /**
     * Dispatches [event] to all registered listeners.
     * Called by the Window.Callback wrapper on the main thread.
     */
    fun dispatchTouchEvent(event: MotionEvent, window: Window) {
        listeners.forEach { it.onTouchEvent(event, window) }
    }

    /**
     * Dispatches [event] to all registered listeners.
     * Called by the Window.Callback wrapper on the main thread.
     */
    fun dispatchKeyEvent(event: KeyEvent, window: Window) {
        listeners.forEach { it.onKeyEvent(event, window) }
    }
}
