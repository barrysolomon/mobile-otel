/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.core

import android.util.Log
import java.io.File
import java.util.UUID

/**
 * Provides a stable per-boot identifier for correlating monotonic timestamps
 * across process restarts within the same device boot.
 *
 * Uses the Linux kernel's `/proc/sys/kernel/random/boot_id` which is a UUID
 * generated once per device boot. This allows [android.os.SystemClock.elapsedRealtime]
 * timestamps from a previous process (same boot) to remain valid for
 * flush window calculations.
 *
 * Falls back to a per-process UUID if boot_id is unreadable (some security policies).
 */
internal object BootTracker {

    private const val TAG = "BootTracker"

    val currentBootId: String by lazy {
        try {
            File("/proc/sys/kernel/random/boot_id").readText().trim()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read boot_id, falling back to per-process UUID", e)
            UUID.randomUUID().toString()
        }
    }
}
