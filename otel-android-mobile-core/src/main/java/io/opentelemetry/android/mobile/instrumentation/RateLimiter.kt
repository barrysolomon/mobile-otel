// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe, rolling-window rate limiter.
 *
 * Tracks event timestamps within a sliding window (default 60 seconds) and rejects
 * events that would exceed [maxPerWindow]. Unlike a reset-at-boundary counter, this
 * provides a true rolling limit — no burst-at-boundary edge cases.
 *
 * Used by instrumentation modules (screenshot, wireframe, errors, etc.) to prevent
 * excessive telemetry emission during rapid-fire scenarios (crash loops, rapid taps, etc.).
 *
 * @param maxPerWindow Maximum number of events allowed within the rolling window.
 * @param windowMs Length of the rolling window in milliseconds. Default: 60,000 (1 minute).
 * @param clock Time source in epoch millis. Defaults to [System.currentTimeMillis];
 *   injectable so tests can advance time deterministically instead of using
 *   `Thread.sleep` with tight windows (which flakes on loaded CI runners).
 */
@Incubating
class RateLimiter(
    private val maxPerWindow: Int,
    private val windowMs: Long = 60_000L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val timestamps = CopyOnWriteArrayList<Long>()

    /**
     * Attempt to acquire a permit. Returns `true` if the event is allowed,
     * `false` if the rate limit has been reached.
     */
    fun tryAcquire(): Boolean {
        val now = clock()
        prune(now)
        if (timestamps.size >= maxPerWindow) return false
        timestamps.add(now)
        return true
    }

    /**
     * Remove timestamps outside the rolling window.
     */
    private fun prune(now: Long) {
        val cutoff = now - windowMs
        timestamps.removeAll { it < cutoff }
    }

    /**
     * Reset the limiter, clearing all tracked timestamps.
     */
    fun reset() {
        timestamps.clear()
    }

    /** Current count of events within the window. */
    val currentCount: Int get() {
        prune(clock())
        return timestamps.size
    }
}
