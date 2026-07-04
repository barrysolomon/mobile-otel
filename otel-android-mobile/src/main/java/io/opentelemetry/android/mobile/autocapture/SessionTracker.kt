/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.autocapture

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class SessionTracker(
    private val options: AutoCaptureOptions
) {
    private val sessionIdRef = AtomicReference(UUID.randomUUID().toString())
    private val viewIdRef = AtomicReference(UUID.randomUUID().toString())

    @Volatile
    private var lastBackgroundAtMs: Long = 0

    @Volatile
    private var currentScreenName: String? = null

    fun getSessionId(): String = sessionIdRef.get()

    fun getViewId(): String = viewIdRef.get()

    fun getCurrentScreenName(): String? = currentScreenName

    fun onAppForeground(nowMs: Long): Boolean {
        val lastBackground = lastBackgroundAtMs
        if (lastBackground > 0 && nowMs - lastBackground >= options.sessionRenewalMs) {
            sessionIdRef.set(UUID.randomUUID().toString())
            viewIdRef.set(UUID.randomUUID().toString())
            return true
        }
        return false
    }

    fun onAppBackground(nowMs: Long) {
        lastBackgroundAtMs = nowMs
    }

    fun onScreenView(screenName: String) {
        currentScreenName = screenName
        viewIdRef.set(UUID.randomUUID().toString())
    }
}
