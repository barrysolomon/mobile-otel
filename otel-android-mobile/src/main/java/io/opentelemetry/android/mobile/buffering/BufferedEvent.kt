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
    /**
     * Cheap estimate of this event's serialized footprint in bytes (body length
     * plus each attribute key + stringified value). Used by the RAM buffer to
     * enforce byte caps (SDK_SAFETY.md non-negotiable #3) without serializing.
     * Computed once and cached — [LogRecordData] is effectively immutable here.
     */
    val estimatedBytes: Long by lazy {
        var total = logRecord.body.asString().length.toLong()
        logRecord.attributes.forEach { key, value ->
            total += key.key.length + value.toString().length
        }
        total
    }

    companion object {
        private val counter = AtomicLong(0)
        private fun nextSeqId(): Long = counter.incrementAndGet()

        /**
         * Seeds the seqId counter so that new events start after [startValue].
         * Must be called once at startup with the max seqId from the disk buffer,
         * otherwise crash-mirrored events from a previous process will have the
         * same seqIds as new events, causing the dedup filter in forceFlush() to
         * drop them.
         */
        internal fun seedCounter(startValue: Long) {
            counter.set(startValue)
        }
    }
}
