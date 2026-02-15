/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.autocapture

import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SearchEvent
import android.view.View
import android.view.Window
import android.view.WindowManager

class WindowCallbackWrapper(
    private val window: Window,
    private val delegate: Window.Callback,
    private val tapCapture: TapCapture,
    private val backPressCapture: BackPressCapture?
) : Window.Callback {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            backPressCapture?.onBackPressed()
        }
        return delegate.dispatchKeyEvent(event)
    }

    override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean {
        return delegate.dispatchKeyShortcutEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        tapCapture.handleTouchEvent(window, event)
        return delegate.dispatchTouchEvent(event)
    }

    override fun dispatchTrackballEvent(event: MotionEvent): Boolean {
        return delegate.dispatchTrackballEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        return delegate.dispatchGenericMotionEvent(event)
    }

    override fun dispatchPopulateAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent): Boolean {
        return delegate.dispatchPopulateAccessibilityEvent(event)
    }

    override fun onCreatePanelView(featureId: Int): View? {
        return delegate.onCreatePanelView(featureId)
    }

    override fun onCreatePanelMenu(featureId: Int, menu: Menu): Boolean {
        return delegate.onCreatePanelMenu(featureId, menu)
    }

    override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean {
        return delegate.onPreparePanel(featureId, view, menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        return delegate.onMenuOpened(featureId, menu)
    }

    override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean {
        return delegate.onMenuItemSelected(featureId, item)
    }

    override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams) {
        delegate.onWindowAttributesChanged(attrs)
    }

    override fun onContentChanged() {
        delegate.onContentChanged()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        delegate.onWindowFocusChanged(hasFocus)
    }

    override fun onAttachedToWindow() {
        delegate.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        delegate.onDetachedFromWindow()
    }

    override fun onPanelClosed(featureId: Int, menu: Menu) {
        delegate.onPanelClosed(featureId, menu)
    }

    override fun onSearchRequested(): Boolean {
        return delegate.onSearchRequested()
    }

    override fun onSearchRequested(searchEvent: SearchEvent): Boolean {
        return delegate.onSearchRequested(searchEvent)
    }

    override fun onWindowStartingActionMode(callback: ActionMode.Callback): ActionMode? {
        return delegate.onWindowStartingActionMode(callback)
    }

    override fun onWindowStartingActionMode(callback: ActionMode.Callback, type: Int): ActionMode? {
        return delegate.onWindowStartingActionMode(callback, type)
    }

    override fun onActionModeStarted(mode: ActionMode) {
        delegate.onActionModeStarted(mode)
    }

    override fun onActionModeFinished(mode: ActionMode) {
        delegate.onActionModeFinished(mode)
    }
}
