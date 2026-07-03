/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.autocapture

import android.content.Context

/**
 * Crash-loop self-disable guard (SDK_SAFETY).
 *
 * On every launch, BEFORE any SDK initialization, [evaluateOnLaunch] inspects
 * the crash marker [RecoveryTracker] left behind if the previous session died
 * from an uncaught exception:
 *
 * - marker present → increment a persisted consecutive-crash counter. Once the
 *   counter reaches the configured threshold the verdict is [Verdict.DISABLED]
 *   and the SDK must not initialize for this launch.
 * - marker absent (previous session was clean) → reset the counter to zero.
 *
 * On the DISABLED path the crash marker is cleared: the SDK stays inert for
 * that launch, so nothing else would ever consume the marker, and a stale one
 * would keep the SDK disabled forever. Clearing it makes the next launch count
 * as clean, which resets the counter — the guard self-clears after exactly one
 * disabled launch unless crashes resume. On the PROCEED path the marker is
 * left untouched so [RecoveryTracker] can still emit `app.recovery`.
 *
 * Mirrors iOS `CrashLoopDetector` — zero platform drift.
 */
/**
 * Thrown by `MobileOtel.initialize` when [CrashLoopDetector] returns
 * [CrashLoopDetector.Verdict.DISABLED]. `OTelMobile.start`'s catch-all turns
 * it into the documented degraded/no-op state — the host app never sees it.
 */
class CrashLoopDisabledException(message: String) : IllegalStateException(message)

object CrashLoopDetector {

    enum class Verdict { PROCEED, DISABLED }

    // Same prefs file RecoveryTracker uses — the crash marker lives there.
    private const val PREFS_NAME = "otel_recovery"
    private const val KEY_CRASH_MARKER = "crash_marker"
    private const val KEY_CRASH_LOOP_COUNT = "crash_loop_count"

    fun evaluateOnLaunch(context: Context, threshold: Int): Verdict {
        if (threshold <= 0) return Verdict.PROCEED
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_CRASH_MARKER, false)) {
            prefs.edit().putInt(KEY_CRASH_LOOP_COUNT, 0).apply()
            return Verdict.PROCEED
        }
        val count = prefs.getInt(KEY_CRASH_LOOP_COUNT, 0) + 1
        return if (count >= threshold) {
            // Synchronous commit: the very next thing the caller does is skip
            // init, and if the host crashes again immediately we must not
            // lose the count.
            prefs.edit()
                .putInt(KEY_CRASH_LOOP_COUNT, count)
                .remove(KEY_CRASH_MARKER)
                .commit()
            Verdict.DISABLED
        } else {
            prefs.edit().putInt(KEY_CRASH_LOOP_COUNT, count).apply()
            Verdict.PROCEED
        }
    }

    fun consecutiveCrashCount(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_CRASH_LOOP_COUNT, 0)
}
