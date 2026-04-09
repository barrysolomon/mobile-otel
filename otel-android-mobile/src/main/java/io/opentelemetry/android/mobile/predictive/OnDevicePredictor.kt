/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.predictive

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import io.opentelemetry.android.mobile.instrumentation.Incubating
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.math.abs

/**
 * Lightweight on-device predictor using heuristics and anomaly detection.
 *
 * Predicts potential issues based on device health signals:
 * - App crashes (OOM, low memory)
 * - Network failures (connectivity loss)
 * - Performance degradation (thermal throttling)
 * - Battery drain
 *
 * **Prediction Strategy:**
 * 1. Rule-based heuristics (fast, deterministic)
 * 2. Statistical anomaly detection (trend analysis)
 * 3. Pattern recognition (historical baseline)
 *
 * **Execution Time:** Target <5ms for predictions
 *
 * Usage:
 * ```kotlin
 * val predictor = OnDevicePredictor.getInstance(context)
 * val prediction = predictor.predict()
 *
 * if (prediction.crashRisk > 0.7) {
 *     // Take defensive action
 * }
 * ```
 */
@Incubating
class OnDevicePredictor private constructor(
    private val context: Context
) {
    private val TAG = "OnDevicePredictor"

    private val healthMonitor = DeviceHealthMonitor.getInstance(context)
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Historical snapshots for trend analysis (last N snapshots)
    private val historySize = 20
    private val healthHistory = ConcurrentLinkedDeque<DeviceHealthSnapshot>()

    // Network history for connectivity prediction
    private val networkHistory = ConcurrentLinkedDeque<NetworkSnapshot>()

    init {
        // Register network callback for connectivity monitoring
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    recordNetworkEvent(NetworkEvent.AVAILABLE)
                }

                override fun onLost(network: Network) {
                    recordNetworkEvent(NetworkEvent.LOST)
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    val signalStrength = if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        // WiFi signal strength (placeholder)
                        100
                    } else {
                        // Cellular signal strength (placeholder)
                        50
                    }
                    recordNetworkSnapshot(signalStrength)
                }
            })
        }
    }

    /**
     * Generates predictions for potential issues.
     * This should be fast (<5ms).
     */
    fun predict(): Prediction {
        // Get current device health
        val currentHealth = healthMonitor.getCurrentSnapshot()

        // Update history
        healthHistory.addLast(currentHealth)
        if (healthHistory.size > historySize) {
            healthHistory.removeFirst()
        }

        return Prediction(
            timestampMs = System.currentTimeMillis(),
            crashRisk = predictCrashRisk(currentHealth),
            networkLossRisk = predictNetworkLoss(),
            performanceDegradationRisk = predictPerformanceDegradation(currentHealth),
            batteryDrainRisk = predictBatteryDrain(currentHealth),
            confidence = calculateConfidence()
        )
    }

    // Crash prediction

    private fun predictCrashRisk(health: DeviceHealthSnapshot): Double {
        var risk = 0.0

        // Heuristic 1: Low memory
        when (health.memoryPressure) {
            MemoryPressure.CRITICAL -> risk += 0.6
            MemoryPressure.HIGH -> risk += 0.3
            MemoryPressure.MODERATE -> risk += 0.1
            else -> {}
        }

        // Heuristic 2: Very low available memory (absolute threshold)
        if (health.availableMemoryMb < 50) {
            risk += 0.4
        }

        // Heuristic 3: Memory trend (rapid decline)
        val memoryTrend = calculateMemoryTrend()
        if (memoryTrend < -10) {  // Declining >10MB/minute
            risk += 0.3
        }

        return risk.coerceIn(0.0, 1.0)
    }

    private fun calculateMemoryTrend(): Double {
        if (healthHistory.size < 3) return 0.0

        val recent = healthHistory.toList().takeLast(3)
        val firstMem = recent.first().availableMemoryMb.toDouble()
        val lastMem = recent.last().availableMemoryMb.toDouble()
        val timeSpanMinutes = (recent.last().timestampMs - recent.first().timestampMs) / 60000.0

        if (timeSpanMinutes < 0.1) return 0.0

        return (lastMem - firstMem) / timeSpanMinutes
    }

    // Network loss prediction

    @SuppressLint("MissingPermission") // Permission declared in app manifest, not library
    private fun predictNetworkLoss(): Double {
        var risk = 0.0

        // Heuristic 1: Recent network instability
        val recentLosses = networkHistory.toList().takeLast(5).count { it.event == NetworkEvent.LOST }
        if (recentLosses >= 2) {
            risk += 0.5
        }

        // Heuristic 2: Signal strength trending down
        val signalTrend = calculateSignalTrend()
        if (signalTrend < -10) {  // Signal declining
            risk += 0.3
        }

        // Heuristic 3: No active network
        val hasNetwork = connectivityManager.activeNetwork != null
        if (!hasNetwork) {
            risk = 1.0  // Already lost
        }

        return risk.coerceIn(0.0, 1.0)
    }

    private fun calculateSignalTrend(): Double {
        if (networkHistory.size < 3) return 0.0

        val recent = networkHistory.toList().takeLast(3)
        val firstSignal = recent.first().signalStrength.toDouble()
        val lastSignal = recent.last().signalStrength.toDouble()

        return lastSignal - firstSignal
    }

    // Performance degradation prediction

    private fun predictPerformanceDegradation(health: DeviceHealthSnapshot): Double {
        var risk = 0.0

        // Heuristic 1: Thermal throttling
        when (health.thermalState) {
            ThermalState.SEVERE, ThermalState.CRITICAL -> risk += 0.6
            ThermalState.MODERATE -> risk += 0.3
            ThermalState.LIGHT -> risk += 0.1
            else -> {}
        }

        // Heuristic 2: High memory pressure (affects performance)
        if (health.memoryPressure == MemoryPressure.HIGH || health.memoryPressure == MemoryPressure.CRITICAL) {
            risk += 0.2
        }

        return risk.coerceIn(0.0, 1.0)
    }

    // Battery drain prediction

    private fun predictBatteryDrain(health: DeviceHealthSnapshot): Double {
        var risk = 0.0

        // Heuristic 1: Low battery level
        when {
            health.batteryLevel < 10 -> risk += 0.6
            health.batteryLevel < 20 -> risk += 0.3
            health.batteryLevel < 30 -> risk += 0.1
        }

        // Heuristic 2: High drain rate
        if (!health.batteryCharging && health.batteryDrainRatePercPerMin > 1.0) {
            risk += 0.4  // >1% per minute is very high
        }

        return risk.coerceIn(0.0, 1.0)
    }

    // Confidence calculation

    private fun calculateConfidence(): Double {
        // Confidence based on history size
        val historyFactor = (healthHistory.size.toDouble() / historySize.toDouble()).coerceIn(0.0, 1.0)

        // Base confidence starts at 0.5, increases with history
        return 0.5 + (historyFactor * 0.5)
    }

    // Network monitoring helpers

    private fun recordNetworkEvent(event: NetworkEvent) {
        networkHistory.addLast(NetworkSnapshot(
            timestampMs = System.currentTimeMillis(),
            event = event,
            signalStrength = 0
        ))

        if (networkHistory.size > historySize) {
            networkHistory.removeFirst()
        }

        Log.d(TAG, "Network event: $event")
    }

    private fun recordNetworkSnapshot(signalStrength: Int) {
        networkHistory.addLast(NetworkSnapshot(
            timestampMs = System.currentTimeMillis(),
            event = NetworkEvent.AVAILABLE,
            signalStrength = signalStrength
        ))

        if (networkHistory.size > historySize) {
            networkHistory.removeFirst()
        }
    }

    companion object {
        @Volatile
        private var instance: OnDevicePredictor? = null

        fun getInstance(context: Context): OnDevicePredictor {
            return instance ?: synchronized(this) {
                instance ?: OnDevicePredictor(context.applicationContext).also { instance = it }
            }
        }
    }
}

/**
 * Prediction result containing risk scores for various issue types.
 */
@Incubating
data class Prediction(
    val timestampMs: Long,
    val crashRisk: Double,              // 0.0-1.0
    val networkLossRisk: Double,        // 0.0-1.0
    val performanceDegradationRisk: Double,  // 0.0-1.0
    val batteryDrainRisk: Double,       // 0.0-1.0
    val confidence: Double              // 0.0-1.0
) {
    /**
     * Returns true if any risk is above threshold.
     */
    fun hasHighRisk(threshold: Double = 0.7): Boolean {
        return crashRisk >= threshold ||
               networkLossRisk >= threshold ||
               performanceDegradationRisk >= threshold ||
               batteryDrainRisk >= threshold
    }

    /**
     * Gets the highest risk score.
     */
    fun getMaxRisk(): Double {
        return maxOf(crashRisk, networkLossRisk, performanceDegradationRisk, batteryDrainRisk)
    }
}

/**
 * Network snapshot for connectivity tracking.
 */
private data class NetworkSnapshot(
    val timestampMs: Long,
    val event: NetworkEvent,
    val signalStrength: Int
)

private enum class NetworkEvent {
    AVAILABLE,
    LOST
}
