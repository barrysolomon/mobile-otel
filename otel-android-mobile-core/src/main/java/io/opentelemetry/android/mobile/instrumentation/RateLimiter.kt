// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

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
    // Guarded by `this`. A plain list under a lock, NOT a concurrent
    // collection: the old CopyOnWriteArrayList version had a check-then-act
    // race between the size check and the add, so under contention the
    // limiter admitted far more than maxPerWindow (observed >2x in CI) —
    // and an event storm is EXACTLY the high-contention moment the limiter
    // exists for. Call sites are human-event-rate (taps, screenshots,
    // errors), so the lock cost is irrelevant.
    private val timestamps = ArrayList<Long>()

    /**
     * Attempt to acquire a permit. Returns `true` if the event is allowed,
     * `false` if the rate limit has been reached. Atomic: the limit is
     * enforced exactly, including under concurrent callers.
     */
    fun tryAcquire(): Boolean = synchronized(this) {
        val now = clock()
        pruneLocked(now)
        if (timestamps.size >= maxPerWindow) {
            false
        } else {
            timestamps.add(now)
            true
        }
    }

    /** Remove timestamps outside the rolling window. Caller holds the lock. */
    private fun pruneLocked(now: Long) {
        val cutoff = now - windowMs
        timestamps.removeAll { it < cutoff }
    }

    /**
     * Reset the limiter, clearing all tracked timestamps.
     */
    fun reset() = synchronized(this) {
        timestamps.clear()
    }

    /** Current count of events within the window. */
    val currentCount: Int get() = synchronized(this) {
        pruneLocked(clock())
        timestamps.size
    }
}
