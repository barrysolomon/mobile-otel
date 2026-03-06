// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

/**
 * Swappable session management provider.
 *
 * The default implementation [DefaultMobileSessionProvider] matches the
 * existing SessionTracker behaviour. Apps with their own session
 * infrastructure can supply a custom implementation via
 * [OTelMobileBuilder.setSessionProvider].
 *
 * Follows the OTel provider pattern (IdGenerator, Sampler, TextMapPropagator).
 */
@Incubating
interface MobileSessionProvider {
    /** Returns the current session identifier. */
    fun getSessionId(): String

    /** Returns the current view/screen identifier. Changes with each [onScreenView] call. */
    fun getViewId(): String

    /** Returns the name of the most recently viewed screen, or null if none yet. */
    fun getCurrentScreenName(): String?

    /** Called when a new screen is viewed. Implementations should update viewId. */
    fun onScreenView(screenName: String)

    /**
     * Called when the app comes to the foreground.
     * @return true if the session was renewed (new session started), false otherwise.
     */
    fun onAppForeground(timestampMs: Long): Boolean

    /** Called when the app goes to the background. */
    fun onAppBackground(timestampMs: Long)
}
