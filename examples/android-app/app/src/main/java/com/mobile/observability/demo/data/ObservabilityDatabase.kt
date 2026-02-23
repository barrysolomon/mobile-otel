package com.mobile.observability.demo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [EventEntity::class, CrashMarkerEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ObservabilityDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun crashMarkerDao(): CrashMarkerDao

    companion object {
        @Volatile
        private var INSTANCE: ObservabilityDatabase? = null

        fun getDatabase(context: Context): ObservabilityDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ObservabilityDatabase::class.java,
                    "observability_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
