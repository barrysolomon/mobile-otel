/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.predictive

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import io.opentelemetry.android.mobile.instrumentation.Incubating
import java.util.concurrent.atomic.AtomicReference

/**
 * Monitors device health signals for predictive telemetry.
 *
 * Collects real-time metrics about device state including:
 * - Memory: Available heap, GC frequency, allocation rate
 * - CPU: Load, thermal state
 * - Battery: Level, charging state, drain rate
 * - Storage: Available space
 *
 * Thread-safe singleton that maintains current device health snapshot.
 *
 * Usage:
 * ```kotlin
 * val monitor = DeviceHealthMonitor.getInstance(context)
 * val snapshot = monitor.getCurrentSnapshot()
 * if (snapshot.availableMemoryMb < 50) {
 *     // Take action
 * }
 * ```
 */
@Incubating
class DeviceHealthMonitor private constructor(
    private val context: Context
) {
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    // Cached snapshot (updated periodically)
    private val currentSnapshot = AtomicReference<DeviceHealthSnapshot>()

    // Previous snapshot for calculating rates
    private val previousSnapshot = AtomicReference<DeviceHealthSnapshot>()

    init {
        // Initialize with first snapshot
        updateSnapshot()
    }

    /**
     * Gets the current device health snapshot.
     * This is fast (just returns cached values).
     */
    fun getCurrentSnapshot(): DeviceHealthSnapshot {
        return currentSnapshot.get() ?: updateSnapshot()
    }

    /**
     * Updates the health snapshot (call periodically from background thread).
     */
    fun updateSnapshot(): DeviceHealthSnapshot {
        val now = System.currentTimeMillis()
        val prev = currentSnapshot.get()

        val snapshot = DeviceHealthSnapshot(
            timestampMs = now,

            // Memory metrics
            availableMemoryMb = getAvailableMemoryMb(),
            totalMemoryMb = getTotalMemoryMb(),
            usedMemoryMb = getUsedMemoryMb(),
            memoryPressure = getMemoryPressure(),

            // Battery metrics
            batteryLevel = getBatteryLevel(),
            batteryCharging = isBatteryCharging(),
            batteryDrainRatePercPerMin = calculateBatteryDrainRate(prev),

            // Storage metrics
            availableStorageMb = getAvailableStorageMb(),

            // CPU/Thermal metrics
            thermalState = getThermalState(),

            // Historical counters (for rate calculation)
            gcInvocationCount = getGcInvocationCount()
        )

        previousSnapshot.set(currentSnapshot.get())
        currentSnapshot.set(snapshot)

        return snapshot
    }

    // Memory metrics

    private fun getAvailableMemoryMb(): Long {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.availMem / (1024 * 1024)
    }

    private fun getTotalMemoryMb(): Long {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024)
    }

    private fun getUsedMemoryMb(): Long {
        val memInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memInfo)
        return (memInfo.totalPss / 1024).toLong()
    }

    private fun getMemoryPressure(): MemoryPressure {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val availablePercent = (memInfo.availMem.toDouble() / memInfo.totalMem.toDouble()) * 100

        return when {
            availablePercent < 10 -> MemoryPressure.CRITICAL
            availablePercent < 25 -> MemoryPressure.HIGH
            availablePercent < 50 -> MemoryPressure.MODERATE
            else -> MemoryPressure.NORMAL
        }
    }

    private fun getGcInvocationCount(): Long {
        // Note: This is a proxy metric (not exact GC count)
        return Runtime.getRuntime().let { runtime ->
            (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        }
    }

    // Battery metrics

    private fun getBatteryLevel(): Int {
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun isBatteryCharging(): Boolean {
        return batteryManager.isCharging
    }

    private fun calculateBatteryDrainRate(prev: DeviceHealthSnapshot?): Double {
        if (prev == null) return 0.0

        val currentLevel = getBatteryLevel()
        val prevLevel = prev.batteryLevel
        val timeElapsedMinutes = (System.currentTimeMillis() - prev.timestampMs) / 60000.0

        if (timeElapsedMinutes < 0.1) return 0.0  // Too soon to calculate

        val levelDelta = prevLevel - currentLevel
        return levelDelta / timeElapsedMinutes
    }

    // Storage metrics

    private fun getAvailableStorageMb(): Long {
        val dataDir = context.dataDir
        return dataDir.freeSpace / (1024 * 1024)
    }

    // Thermal metrics

    private fun getThermalState(): ThermalState {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            return when (powerManager.currentThermalStatus) {
                android.os.PowerManager.THERMAL_STATUS_NONE -> ThermalState.NORMAL
                android.os.PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.LIGHT
                android.os.PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.MODERATE
                android.os.PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SEVERE
                android.os.PowerManager.THERMAL_STATUS_CRITICAL -> ThermalState.CRITICAL
                android.os.PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalState.EMERGENCY
                android.os.PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.SHUTDOWN
                else -> ThermalState.UNKNOWN
            }
        }
        return ThermalState.UNKNOWN
    }

    companion object {
        @Volatile
        private var instance: DeviceHealthMonitor? = null

        fun getInstance(context: Context): DeviceHealthMonitor {
            return instance ?: synchronized(this) {
                instance ?: DeviceHealthMonitor(context.applicationContext).also { instance = it }
            }
        }
    }
}

/**
 * Snapshot of device health at a point in time.
 */
@Incubating
data class DeviceHealthSnapshot(
    val timestampMs: Long,

    // Memory
    val availableMemoryMb: Long,
    val totalMemoryMb: Long,
    val usedMemoryMb: Long,
    val memoryPressure: MemoryPressure,

    // Battery
    val batteryLevel: Int,
    val batteryCharging: Boolean,
    val batteryDrainRatePercPerMin: Double,

    // Storage
    val availableStorageMb: Long,

    // Thermal
    val thermalState: ThermalState,

    // Counters (for rate calculation)
    val gcInvocationCount: Long
)

@Incubating
enum class MemoryPressure {
    NORMAL,     // >50% available
    MODERATE,   // 25-50% available
    HIGH,       // 10-25% available
    CRITICAL    // <10% available
}

@Incubating
enum class ThermalState {
    UNKNOWN,
    NORMAL,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY,
    SHUTDOWN
}
