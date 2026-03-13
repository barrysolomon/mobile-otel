// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window

import io.opentelemetry.android.mobile.instrumentation.Incubating

/**
 * Listener for window-level touch and key events.
 *
 * Instrumentation modules that need to observe raw touch/key events
 * (tap, scroll, text-input, back-press) register as [WindowEventListener]
 * instances with the [WindowEventHub]. The hub manages a single
 * [android.view.Window.Callback] wrapper per window and fans events out
 * to all registered listeners.
 *
 * Both methods have empty default implementations — implement only what
 * your instrumentation needs.
 */
@Incubating
interface WindowEventListener {
    /** Called when a [MotionEvent] is dispatched through the window. */
    fun onTouchEvent(event: MotionEvent, window: Window) {}

    /** Called when a [KeyEvent] is dispatched through the window. */
    fun onKeyEvent(event: KeyEvent, window: Window) {}
}
