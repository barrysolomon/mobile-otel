/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import android.os.SystemClock
import io.opentelemetry.api.common.AttributeType
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
     * Cheap approximate size of this event in bytes, used by the RAM buffer's
     * total-byte budget and per-event byte cap (SDK_SAFETY non-negotiable #3,
     * iOS parity with `RAMEventBuffer.swift` / `BufferedEvent.sizeBytes`).
     *
     * Estimated from the body length plus each attribute's key and value
     * (UTF-16 length × 2 ≈ bytes; primitives counted at a fixed 8 B). This is
     * an estimate, not an exact serialized size — it is O(attributes), runs
     * once at construction, and is good enough to keep large screenshot /
     * wireframe events from ballooning RAM. Computed eagerly so the hot
     * eviction loop never re-walks attributes.
     */
    val sizeBytes: Int = estimateSize(logRecord)

    companion object {
        private val counter = AtomicLong(0)
        private fun nextSeqId(): Long = counter.incrementAndGet()

        /**
         * Approximate byte size of a log record. Walks the body + attributes
         * once. String values are charged 2 B/char (UTF-16 upper bound);
         * non-string primitives a flat 8 B. Best-effort: any exception falls
         * back to a small constant so a pathological record can't crash the
         * logging thread.
         */
        internal fun estimateSize(record: LogRecordData): Int {
            return try {
                var bytes = record.body.asString().length * 2
                record.attributes.forEach { key, value ->
                    bytes += key.key.length * 2
                    bytes += when (key.type) {
                        AttributeType.STRING -> (value as? String)?.length?.times(2) ?: 8
                        AttributeType.STRING_ARRAY ->
                            (value as? List<*>)?.sumOf { ((it as? String)?.length ?: 0) * 2 } ?: 8
                        else -> 8
                    }
                }
                bytes
            } catch (_: Throwable) {
                64
            }
        }

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
