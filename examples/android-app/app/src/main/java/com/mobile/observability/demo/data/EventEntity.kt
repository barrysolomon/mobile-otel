package com.mobile.observability.demo.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["session_id"]),
        Index(value = ["flushed"])
    ]
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "event_name")
    val eventName: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "device_id")
    val deviceId: String,

    @ColumnInfo(name = "trigger_id")
    val triggerId: String? = null,

    @ColumnInfo(name = "config_version")
    val configVersion: Int,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long, // Unix timestamp in milliseconds

    @ColumnInfo(name = "attributes_json")
    val attributesJson: String, // JSON string of attributes

    @ColumnInfo(name = "flushed")
    val flushed: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
