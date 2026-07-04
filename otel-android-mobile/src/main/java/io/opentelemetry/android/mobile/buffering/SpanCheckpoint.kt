// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.buffering

/**
 * Persisted identity of a span that was open when the process last ran.
 * Written on [PersistingSpanProcessor.onStart], removed on [PersistingSpanProcessor.onEnd].
 * Any row still present at next launch represents a span whose process was killed
 * before it could end — i.e. a crash victim.
 */
data class SpanCheckpoint(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String,   // empty string when there is no parent
    val name: String,
    val startEpochNanos: Long,
)

/**
 * Storage contract for [SpanCheckpoint] entries.
 * Production implementation: [SharedPreferencesSpanCheckpointStore].
 * Test implementation: [io.opentelemetry.android.mobile.buffering.InMemorySpanCheckpointStore].
 */
interface SpanCheckpointStore {
    fun checkpoint(entry: SpanCheckpoint)
    fun remove(spanId: String)
    fun readAll(): List<SpanCheckpoint>
    fun clear()
}
