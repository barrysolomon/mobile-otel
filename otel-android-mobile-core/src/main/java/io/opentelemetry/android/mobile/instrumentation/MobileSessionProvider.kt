// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import io.opentelemetry.android.session.SessionProvider

/**
 * Swappable session management provider.
 *
 * The default implementation [DefaultMobileSessionProvider] matches the
 * existing SessionTracker behaviour. Apps with their own session
 * infrastructure can supply a custom implementation via
 * [OTelMobileBuilder.setSessionProvider].
 *
 * Follows the OTel provider pattern (IdGenerator, Sampler, TextMapPropagator).
 *
 * Extends upstream [SessionProvider] so that a [MobileSessionProvider] can be
 * passed directly to any upstream component that expects a [SessionProvider].
 */
@Incubating
interface MobileSessionProvider : SessionProvider {
    /** Returns the current session identifier. */
    override fun getSessionId(): String

    /** Returns the current view/screen identifier. Changes with each [onScreenView] call. */
    fun getViewId(): String

    /** Returns the name of the most recently viewed screen, or null if none yet. */
    fun getCurrentScreenName(): String?

    /** Returns the name of the screen viewed before the current one, or null if on first screen. */
    fun getPreviousScreenName(): String? = null

    /** Returns how long the current screen has been visible in milliseconds. */
    fun getTimeOnScreenMs(): Long = 0L

    /** Called when a new screen is viewed. Implementations should update viewId. */
    fun onScreenView(screenName: String)

    /**
     * Called when the app comes to the foreground.
     * @return true if the session was renewed (new session started), false otherwise.
     */
    fun onAppForeground(timestampMs: Long): Boolean

    /** Called when the app goes to the background. */
    fun onAppBackground(timestampMs: Long)

    /**
     * Mark the current session as having experienced an error or crash.
     * Called by error instrumentation when an exception is captured.
     * Default no-op so existing implementations are not forced to override.
     */
    fun markSessionError() {}

    /**
     * Returns whether the current session has had an error.
     * Default returns false for implementations that do not track release health.
     */
    fun sessionHadError(): Boolean = false
}
