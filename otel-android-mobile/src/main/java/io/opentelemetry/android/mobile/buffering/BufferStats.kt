/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

/**
 * Snapshot of the dual-tier buffer's state. Returned by
 * [io.opentelemetry.android.mobile.MobileLoggerProvider.getBufferStats] and
 * [io.opentelemetry.android.mobile.MobileOtel.getBufferStats].
 *
 * Promoted from a nested type of the (now internal) log processor in 0.4.0
 * so the public flush/stats surface has a public home (docs/API_STABILITY.md).
 */
data class BufferStats(
    val ramBufferSize: Int,
    val diskBufferSize: Int,
    val ramBufferCapacity: Int,
    val diskBufferCapacityMb: Int,
    /** Estimated cumulative bytes currently held in the RAM buffer. */
    val ramBufferBytes: Long = 0,
    /** Configured total-byte budget for the RAM buffer. */
    val ramBufferMaxTotalBytes: Long = 0,
    /** Configured per-event byte cap for the RAM buffer. */
    val ramBufferMaxEventBytes: Int = 0,
    /** Events dropped because they exceeded [ramBufferMaxEventBytes]. */
    val droppedOversizeCount: Long = 0
)
