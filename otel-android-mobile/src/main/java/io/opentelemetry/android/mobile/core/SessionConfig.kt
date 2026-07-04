/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.core

/**
 * Configuration for session management.
 *
 * Sessions track user activity across app launches and provide a correlation ID
 * for all telemetry events within a session.
 */
data class SessionConfig(
    /**
     * Enable session tracking. If disabled, no session ID will be generated.
     */
    val enabled: Boolean = true,

    /**
     * Inactivity timeout in milliseconds. If the app is in background for longer
     * than this duration, a new session will start on next foreground.
     *
     * Default: 15 minutes (900,000 ms)
     */
    val inactivityTimeoutMs: Long = 15 * 60 * 1000,

    /**
     * Flush all buffered telemetry when session is terminated (e.g., logout).
     * This is useful in HYBRID mode to ensure session data is exported.
     *
     * Default: true
     */
    val flushOnTermination: Boolean = true,

    /**
     * Persist session ID across app restarts. If true, the same session continues
     * after app restart (unless inactivity timeout exceeded).
     *
     * Default: true
     */
    val persistSession: Boolean = true,
)
