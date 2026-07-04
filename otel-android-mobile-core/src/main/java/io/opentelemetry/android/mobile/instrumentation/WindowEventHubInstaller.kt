// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SearchEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import java.util.WeakHashMap

/**
 * Connects Android window touch/key events to [WindowEventHub].
 *
 * Installs a thin [Window.Callback] wrapper on each Activity's window when
 * the activity starts. The wrapper intercepts [Window.Callback.dispatchTouchEvent]
 * and [Window.Callback.dispatchKeyEvent], forwards them to the hub (which fans
 * them out to all registered [WindowEventListener] implementations such as
 * [TapInstrumentation]), then delegates to the original callback so normal
 * Android behaviour is preserved.
 *
 * Created and installed by [OTelMobileBuilder.build]; uninstalled by
 * [OTelMobileHandle.stop].
 */
internal class WindowEventHubInstaller(
    private val application: Application,
    private val hub: WindowEventHub
) {
    // WeakHashMap so destroyed activities are GC'd without explicit cleanup.
    private val wrapped = WeakHashMap<Activity, Boolean>()

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            if (wrapped.containsKey(activity)) return
            val window = activity.window ?: return
            val original = window.callback ?: return
            if (original is HubDispatcher) return   // already wrapped
            window.callback = HubDispatcher(window, original, hub)
            wrapped[activity] = true
        }

        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityResumed(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) { wrapped.remove(a) }
    }

    fun install() { application.registerActivityLifecycleCallbacks(callbacks) }

    fun uninstall() {
        application.unregisterActivityLifecycleCallbacks(callbacks)
        wrapped.clear()
    }

    /** Dispatches touch/key events to the hub then delegates to the original callback. */
    private class HubDispatcher(
        private val window: Window,
        private val delegate: Window.Callback,
        private val hub: WindowEventHub
    ) : Window.Callback {

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            // Hub dispatch is best-effort instrumentation: an SDK fault must never
            // break the host's input delegation. Always delegate to the host.
            try {
                hub.dispatchTouchEvent(event, window)
            } catch (t: Throwable) {
                Log.w(TAG, "hub.dispatchTouchEvent threw; swallowing", t)
            }
            return delegate.dispatchTouchEvent(event)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            try {
                hub.dispatchKeyEvent(event, window)
            } catch (t: Throwable) {
                Log.w(TAG, "hub.dispatchKeyEvent threw; swallowing", t)
            }
            return delegate.dispatchKeyEvent(event)
        }

        // All other Window.Callback methods delegate unchanged.
        override fun dispatchKeyShortcutEvent(event: KeyEvent) = delegate.dispatchKeyShortcutEvent(event)
        override fun dispatchTrackballEvent(event: MotionEvent) = delegate.dispatchTrackballEvent(event)
        override fun dispatchGenericMotionEvent(event: MotionEvent) = delegate.dispatchGenericMotionEvent(event)
        override fun dispatchPopulateAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent) =
            delegate.dispatchPopulateAccessibilityEvent(event)
        override fun onCreatePanelView(featureId: Int): View? = delegate.onCreatePanelView(featureId)
        override fun onCreatePanelMenu(featureId: Int, menu: Menu): Boolean = delegate.onCreatePanelMenu(featureId, menu)
        override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean = delegate.onPreparePanel(featureId, view, menu)
        override fun onMenuOpened(featureId: Int, menu: Menu): Boolean = delegate.onMenuOpened(featureId, menu)
        override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean = delegate.onMenuItemSelected(featureId, item)
        override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams) = delegate.onWindowAttributesChanged(attrs)
        override fun onContentChanged() = delegate.onContentChanged()
        override fun onWindowFocusChanged(hasFocus: Boolean) = delegate.onWindowFocusChanged(hasFocus)
        override fun onAttachedToWindow() = delegate.onAttachedToWindow()
        override fun onDetachedFromWindow() = delegate.onDetachedFromWindow()
        override fun onPanelClosed(featureId: Int, menu: Menu) = delegate.onPanelClosed(featureId, menu)
        override fun onSearchRequested(): Boolean = delegate.onSearchRequested()
        override fun onSearchRequested(searchEvent: SearchEvent): Boolean = delegate.onSearchRequested(searchEvent)
        override fun onWindowStartingActionMode(callback: ActionMode.Callback): ActionMode? = delegate.onWindowStartingActionMode(callback)
        override fun onWindowStartingActionMode(callback: ActionMode.Callback, type: Int): ActionMode? = delegate.onWindowStartingActionMode(callback, type)
        override fun onActionModeStarted(mode: ActionMode) = delegate.onActionModeStarted(mode)
        override fun onActionModeFinished(mode: ActionMode) = delegate.onActionModeFinished(mode)

        private companion object {
            private const val TAG = "HubDispatcher"
        }
    }
}
