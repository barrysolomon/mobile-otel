package com.mobile.observability.demo.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "crash_markers")
data class CrashMarkerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "processed")
    val processed: Boolean = false
)

@Dao
interface CrashMarkerDao {
    @Insert
    suspend fun insert(marker: CrashMarkerEntity)

    @Query("SELECT * FROM crash_markers WHERE processed = 0 ORDER BY timestamp DESC LIMIT 1")
    suspend fun getUnprocessedMarker(): CrashMarkerEntity?

    @Query("UPDATE crash_markers SET processed = 1 WHERE id = :id")
    suspend fun markAsProcessed(id: Long)

    @Query("DELETE FROM crash_markers WHERE processed = 1 AND timestamp < :timestamp")
    suspend fun deleteOldProcessed(timestamp: Long)
}
