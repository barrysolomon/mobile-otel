/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import android.os.SystemClock
import io.opentelemetry.sdk.logs.data.LogRecordData
import java.util.concurrent.atomic.AtomicLong

/**
 * Wraps a [LogRecordData] with a monotonic timestamp for clock-skew-safe
 * flush window calculations.
 *
 * [monotonicMs] is captured from [SystemClock.elapsedRealtime] at event
 * creation time. It is immune to wall-clock adjustments (NTP, user changes)
 * and is used internally for window filtering. The original OTel timestamps
 * on [logRecord] are preserved unchanged for export.
 *
 * [seqId] is a process-wide monotonic sequence number used to deduplicate
 * events that exist in both RAM and disk (crash-safety mirrors). When a RAM
 * event is persisted to disk, the seqId is stored alongside it so flushWindow()
 * and forceFlush() can skip disk events whose seqId is still present in RAM.
 */
internal data class BufferedEvent(
    val logRecord: LogRecordData,
    val monotonicMs: Long = SystemClock.elapsedRealtime(),
    val seqId: Long = nextSeqId()
) {
    companion object {
        private val counter = AtomicLong(0)
        private fun nextSeqId(): Long = counter.incrementAndGet()
    }
}
