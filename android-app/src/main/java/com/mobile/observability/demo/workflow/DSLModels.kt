package com.mobile.observability.demo.workflow

import com.google.gson.annotations.SerializedName

data class DSLConfig(
    val version: Int,
    val limits: Limits,
    val workflows: List<Workflow>
)

data class Limits(
    @SerializedName("diskMb")
    val diskMb: Int,
    @SerializedName("ramEvents")
    val ramEvents: Int,
    @SerializedName("retentionHours")
    val retentionHours: Int
)

data class Workflow(
    val id: String,
    val enabled: Boolean,
    val trigger: Trigger,
    val actions: List<Action>
)

data class Trigger(
    val any: List<TriggerCondition>? = null,
    val all: List<TriggerCondition>? = null
)

data class TriggerCondition(
    val event: String? = null,
    val where: List<Predicate>? = null
)

data class Predicate(
    val attr: String,
    val op: String, // ==, !=, >, >=, <, <=, contains, regex
    val value: Any
)

data class Action(
    val type: String, // annotate_trigger, flush_window, set_sampling
    @SerializedName("trigger_id")
    val triggerId: String? = null,
    val reason: String? = null,
    val minutes: Int? = null,
    val scope: String? = null, // session, device
    val rate: Double? = null,
    @SerializedName("duration_minutes")
    val durationMinutes: Int? = null
)

// Event data structure
data class Event(
    val eventName: String,
    val timestamp: Long,
    val attributes: Map<String, Any>
)

// Result of trigger evaluation
data class TriggerResult(
    val triggered: Boolean,
    val workflowId: String,
    val actions: List<Action>
)
