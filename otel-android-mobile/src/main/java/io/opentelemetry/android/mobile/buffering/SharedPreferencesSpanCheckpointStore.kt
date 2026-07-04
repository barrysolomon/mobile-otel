// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.buffering

import android.content.Context
import org.json.JSONObject

/**
 * [SpanCheckpointStore] backed by SharedPreferences.
 *
 * Each checkpoint is stored as a JSON string keyed by spanId under the
 * "dash0_open_spans" preference file. SharedPreferences commits are synchronous
 * (commit(), not apply()) so the checkpoint is durable before onStart returns —
 * a crash immediately after onStart will still find the entry on next launch.
 */
internal class SharedPreferencesSpanCheckpointStore(context: Context) : SpanCheckpointStore {

    private val prefs = context.getSharedPreferences("dash0_open_spans", Context.MODE_PRIVATE)

    override fun checkpoint(entry: SpanCheckpoint) {
        val json = JSONObject().apply {
            put("traceId", entry.traceId)
            put("spanId", entry.spanId)
            put("parentSpanId", entry.parentSpanId)
            put("name", entry.name)
            put("startEpochNanos", entry.startEpochNanos)
        }.toString()
        prefs.edit().putString(entry.spanId, json).commit()
    }

    override fun remove(spanId: String) {
        prefs.edit().remove(spanId).commit()
    }

    override fun readAll(): List<SpanCheckpoint> {
        return prefs.all.values.mapNotNull { raw ->
            runCatching {
                val j = JSONObject(raw as String)
                SpanCheckpoint(
                    traceId = j.getString("traceId"),
                    spanId = j.getString("spanId"),
                    parentSpanId = j.optString("parentSpanId", ""),
                    name = j.getString("name"),
                    startEpochNanos = j.getLong("startEpochNanos"),
                )
            }.getOrNull()
        }
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }
}
