/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
package io.opentelemetry.android.mobile.fleet

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.TimeUnit

class FleetAlertDeduplicator(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("fleet_alert_dedup", Context.MODE_PRIVATE)

    fun isProcessed(alertId: String): Boolean = prefs.contains(alertId)

    fun markProcessed(alertId: String) {
        prefs.edit().putString(alertId, System.currentTimeMillis().toString()).apply()
        cleanup()
    }

    private fun cleanup() {
        val cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)
        val editor = prefs.edit()
        prefs.all.forEach { (key, value) ->
            val ts = (value as? String)?.toLongOrNull() ?: 0
            if (ts < cutoff) {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
