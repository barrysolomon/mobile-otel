package io.opentelemetry.android.mobile.fleet

import android.util.Log
import io.opentelemetry.android.mobile.buffering.MobileLogRecordProcessor
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.config.PrivacyConfig
import java.time.Instant

class FleetAlertHandler(
    private val processor: MobileLogRecordProcessor,
    private val config: MobileConfig,
    private val deduplicator: FleetAlertDeduplicator,
    private val privacyConfig: PrivacyConfig = PrivacyConfig(),
) {
    private val tag = "FleetAlertHandler"
    private val alertTimestamps = mutableListOf<Long>()
    private val maxAlertsPerHour = 5
    private val activeOverrides = mutableMapOf<String, ActiveOverride>()

    fun onFleetAlert(alert: FleetAlert): FleetAlertResult {
        // Validate expiry
        try {
            val expires = Instant.parse(alert.expiresAt)
            if (Instant.now().isAfter(expires)) {
                Log.d(tag, "Fleet alert ${alert.alertId} expired, skipping")
                return FleetAlertResult(alert.alertId, executed = false, reason = "expired")
            }
        } catch (e: Exception) {
            Log.w(tag, "Invalid expiresAt: ${alert.expiresAt}")
            return FleetAlertResult(alert.alertId, executed = false, reason = "invalid_expiry")
        }

        // Dedup
        if (deduplicator.isProcessed(alert.alertId)) {
            Log.d(tag, "Fleet alert ${alert.alertId} already processed, skipping")
            return FleetAlertResult(alert.alertId, executed = false, reason = "duplicate")
        }

        // Rate limit
        if (isRateLimited()) {
            Log.w(tag, "Fleet alert rate limit exceeded")
            return FleetAlertResult(alert.alertId, executed = false, reason = "rate_limited")
        }

        // Execute actions
        val executedActions = mutableListOf<String>()
        val skippedActions = mutableListOf<String>()

        for (action in alert.actions) {
            try {
                val result = executeAction(action, alert.priority)
                if (result) {
                    executedActions.add(action.type)
                } else {
                    skippedActions.add("${action.type}:preempted_or_blocked")
                }
            } catch (e: Exception) {
                Log.e(tag, "Error executing action ${action.type}: ${e.message}")
                skippedActions.add("${action.type}:error")
            }
        }

        deduplicator.markProcessed(alert.alertId)
        recordAlertTimestamp()

        return FleetAlertResult(
            alertId = alert.alertId,
            executed = true,
            actionsExecuted = executedActions,
            actionsSkipped = skippedActions,
        )
    }

    private fun executeAction(action: FleetAction, alertPriority: Int): Boolean {
        return when (action.type) {
            "flush_buffer" -> {
                if (!privacyConfig.allowFleetFlush) return false
                val minutes = action.config["minutes"]?.toIntOrNull() ?: 5
                processor.flushWindow(minutes)
                true
            }
            "set_sampling" -> {
                if (!privacyConfig.allowFleetSampling) return false
                val existing = activeOverrides["sampling"]
                if (existing != null && existing.priority < alertPriority) {
                    return false
                }
                val durationMin = action.config["duration_minutes"]?.toIntOrNull() ?: 10
                activeOverrides["sampling"] = ActiveOverride(
                    key = "sampling", priority = alertPriority, expiresAtMs = System.currentTimeMillis() + durationMin * 60_000L
                )
                true
            }
            "take_screenshot" -> {
                if (!privacyConfig.allowFleetScreenshot) {
                    return false
                }
                true
            }
            else -> {
                Log.w(tag, "Unknown fleet action type: ${action.type}")
                false
            }
        }
    }

    private fun isRateLimited(): Boolean {
        val oneHourAgo = System.currentTimeMillis() - 3_600_000L
        alertTimestamps.removeAll { it < oneHourAgo }
        return alertTimestamps.size >= maxAlertsPerHour
    }

    private fun recordAlertTimestamp() {
        alertTimestamps.add(System.currentTimeMillis())
    }
}

data class FleetAlertResult(
    val alertId: String,
    val executed: Boolean,
    val reason: String? = null,
    val actionsExecuted: List<String> = emptyList(),
    val actionsSkipped: List<String> = emptyList(),
)

data class ActiveOverride(
    val key: String,
    val priority: Int,
    val expiresAtMs: Long,
)
