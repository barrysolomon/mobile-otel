package io.opentelemetry.android.mobile.fleet

import kotlinx.serialization.Serializable

@Serializable
data class FleetAlert(
    val type: String = "fleet_alert",
    val alertId: String,
    val cascadeChainId: String,
    val hop: Int = 0,
    val priority: Int = 2,
    val sourceTrigger: String,
    val sourceCohort: String,
    val sourceDeviceCount: Int = 0,
    val actions: List<FleetAction>,
    val expiresAt: String,
    val signature: String,
    val issuedAt: String,
    val truncated: Boolean = false,
    val totalActions: Int = 0,
)

@Serializable
data class FleetAction(
    val type: String,
    val config: Map<String, String> = emptyMap(),
)
