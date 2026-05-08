/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import android.util.Log
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.sdk.logs.data.LogRecordData
import java.util.concurrent.ConcurrentHashMap

/**
 * Coalesces identical error events within a configurable time window.
 *
 * When multiple identical errors occur (same exception.type + exception.message),
 * only the first occurrence is emitted. On export, a `coalesced.count` attribute
 * is attached to represent the total number of occurrences.
 *
 * This reduces noise in offline/degraded-network scenarios where the same error
 * may fire repeatedly (e.g., network timeouts, API failures).
 *
 * Thread-safe via ConcurrentHashMap.
 */
class ErrorCoalescer(
    private val windowMs: Long = 60_000L,
    private val minSeverity: Severity = Severity.ERROR
) {
    private val TAG = "ErrorCoalescer"

    data class CoalescedEntry(
        val firstRecord: LogRecordData,
        val firstSeenMs: Long,
        var count: Int = 1
    )

    private val active = ConcurrentHashMap<String, CoalescedEntry>()

    /**
     * Attempts to coalesce a log record. Returns true if this record should be
     * suppressed (it was coalesced into an existing entry). Returns false if this
     * is the first occurrence or the record is not eligible for coalescing.
     */
    fun tryCoalesce(record: LogRecordData): Boolean {
        if (record.severity.ordinal < minSeverity.ordinal) {
            return false
        }

        val key = coalescingKey(record) ?: return false
        val now = System.currentTimeMillis()

        pruneExpired(now)

        val existing = active[key]
        if (existing != null && (now - existing.firstSeenMs) < windowMs) {
            existing.count++
            return true
        }

        active[key] = CoalescedEntry(
            firstRecord = record,
            firstSeenMs = now,
            count = 1
        )
        return false
    }

    /**
     * Returns all coalesced entries that have count > 1, meaning they represent
     * suppressed duplicates. The caller should attach `coalesced.count` to these
     * records before export.
     */
    fun drainCoalesced(): List<CoalescedEntry> {
        val now = System.currentTimeMillis()
        val result = mutableListOf<CoalescedEntry>()

        val iter = active.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            val value = entry.value
            if (value.count > 1 || (now - value.firstSeenMs) >= windowMs) {
                if (value.count > 1) {
                    result.add(value)
                }
                iter.remove()
            }
        }

        return result
    }

    /**
     * Returns the current coalesced count for a given error key, or 0 if not tracked.
     */
    fun getCount(record: LogRecordData): Int {
        val key = coalescingKey(record) ?: return 0
        return active[key]?.count ?: 0
    }

    /**
     * Returns the total number of actively tracked error groups.
     */
    fun activeGroupCount(): Int = active.size

    /**
     * Clears all tracked entries.
     */
    fun clear() {
        active.clear()
    }

    private fun coalescingKey(record: LogRecordData): String? {
        val exceptionType = record.attributes.get(AttributeKey.stringKey("exception.type"))
        val exceptionMsg = record.attributes.get(AttributeKey.stringKey("exception.message"))
        val body = record.body.asString()

        if (exceptionType != null) {
            return "$exceptionType|${exceptionMsg ?: ""}"
        }
        if (body.isNotBlank()) {
            return "body|$body"
        }
        return null
    }

    private fun pruneExpired(now: Long) {
        active.entries.removeIf { (_, entry) ->
            (now - entry.firstSeenMs) >= windowMs
        }
    }
}
