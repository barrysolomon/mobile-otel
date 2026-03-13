/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.predictive

import android.content.Context
import android.util.Log
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement
import io.opentelemetry.api.metrics.ObservableLongMeasurement
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Collects device health metrics as OpenTelemetry metrics.
 *
 * Exposes device health signals as OTEL metrics for monitoring:
 * - Memory: available_memory_mb, memory_pressure
 * - Battery: battery_level, battery_drain_rate
 * - Predictions: crash_risk, network_loss_risk, etc.
 *
 * These metrics are exported to the collector alongside logs and traces,
 * providing a complete observability picture.
 *
 * Usage:
 * ```kotlin
 * val collector = HealthMetricsCollector.builder(context)
 *     .setOpenTelemetry(openTelemetry)
 *     .setCollectionIntervalSeconds(30)
 *     .build()
 *
 * // Metrics are automatically collected and exported
 * ```
 */
class HealthMetricsCollector private constructor(
    private val context: Context,
    private val openTelemetry: OpenTelemetry?,
    private val healthMonitor: DeviceHealthMonitor,
    private val predictor: OnDevicePredictor?,
    private val collectionIntervalSeconds: Long
) {
    private val TAG = "HealthMetricsCollector"

    private val meter: Meter? = openTelemetry?.getMeter("io.opentelemetry.android.mobile.health")
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    init {
        if (meter != null) {
            registerMetrics()
            Log.i(TAG, "Initialized: collection interval=${collectionIntervalSeconds}s")
        } else {
            Log.w(TAG, "OpenTelemetry not provided, metrics will not be collected")
        }

        // Schedule periodic snapshot updates
        executor.scheduleAtFixedRate(
            { healthMonitor.updateSnapshot() },
            0,
            collectionIntervalSeconds,
            TimeUnit.SECONDS
        )
    }

    /**
     * Registers all health metrics with OpenTelemetry.
     */
    private fun registerMetrics() {
        // Memory metrics
        meter?.gaugeBuilder("device.memory.available")
            ?.setDescription("Available memory in bytes")
            ?.setUnit("By")
            ?.buildWithCallback { measurement: ObservableDoubleMeasurement ->
                val snapshot = healthMonitor.getCurrentSnapshot()
                measurement.record(snapshot.availableMemoryMb.toDouble() * 1024 * 1024)
            }

        meter?.gaugeBuilder("device.memory.used")
            ?.setDescription("Used memory in bytes")
            ?.setUnit("By")
            ?.buildWithCallback { measurement: ObservableDoubleMeasurement ->
                val snapshot = healthMonitor.getCurrentSnapshot()
                measurement.record(snapshot.usedMemoryMb.toDouble() * 1024 * 1024)
            }

        meter?.gaugeBuilder("device.memory.pressure")
            ?.setDescription("Memory pressure level (0=NORMAL, 1=MODERATE, 2=HIGH, 3=CRITICAL)")
            ?.buildWithCallback { measurement: ObservableDoubleMeasurement ->
                val snapshot = healthMonitor.getCurrentSnapshot()
                val pressureValue = when (snapshot.memoryPressure) {
                    MemoryPressure.NORMAL -> 0.0
                    MemoryPressure.MODERATE -> 1.0
                    MemoryPressure.HIGH -> 2.0
                    MemoryPressure.CRITICAL -> 3.0
                }
                measurement.record(pressureValue)
            }

        // Battery metrics
        meter?.gaugeBuilder("device.battery.level")
            ?.setDescription("Battery level percentage")
            ?.setUnit("%")
            ?.buildWithCallback { measurement: ObservableDoubleMeasurement ->
                val snapshot = healthMonitor.getCurrentSnapshot()
                measurement.record(snapshot.batteryLevel.toDouble())
            }

        meter?.gaugeBuilder("device.battery.charging")
            ?.setDescription("Battery charging state (0=not charging, 1=charging)")
            ?.buildWithCallback { measurement: ObservableDoubleMeasurement ->
                val snapshot = healthMonitor.getCurrentSnapshot()
                measurement.record(if (snapshot.batteryCharging) 1.0 else 0.0)
            }

        meter?.gaugeBuilder("device.battery.drain_rate")
            ?.setDescription("Battery drain rate in percent per minute")
            ?.setUnit("%/min")
            ?.buildWithCallback { measurement: ObservableDoubleMeasurement ->
                val snapshot = healthMonitor.getCurrentSnapshot()
                measurement.record(snapshot.batteryDrainRatePercPerMin)
            }

        // Storage metrics
        meter?.gaugeBuilder("device.storage.available")
            ?.setDescription("Available storage in bytes")
            ?.setUnit("By")
            ?.buildWithCallback { measurement: ObservableDoubleMeasurement ->
                val snapshot = healthMonitor.getCurrentSnapshot()
                measurement.record(snapshot.availableStorageMb.toDouble() * 1024 * 1024)
            }

        // Thermal metrics
        meter?.gaugeBuilder("device.thermal.state")
            ?.setDescription("Thermal state (0=NORMAL, 1=LIGHT, 2=MODERATE, 3=SEVERE, 4=CRITICAL)")
            ?.buildWithCallback { measurement: ObservableDoubleMeasurement ->
                val snapshot = healthMonitor.getCurrentSnapshot()
                val thermalValue = when (snapshot.thermalState) {
                    ThermalState.NORMAL -> 0.0
                    ThermalState.LIGHT -> 1.0
                    ThermalState.MODERATE -> 2.0
                    ThermalState.SEVERE -> 3.0
                    ThermalState.CRITICAL -> 4.0
                    ThermalState.EMERGENCY -> 5.0
                    ThermalState.SHUTDOWN -> 6.0
                    else -> -1.0
                }
                measurement.record(thermalValue)
            }

        // Prediction metrics (if predictor available)
        if (predictor != null) {
            meter?.gaugeBuilder("prediction.crash.risk")
                ?.setDescription("Predicted crash risk (0.0-1.0)")
                ?.buildWithCallback { measurement: ObservableDoubleMeasurement ->
                    val prediction = predictor.predict()
                    measurement.record(prediction.crashRisk)
                }

            meter?.gaugeBuilder("prediction.network_loss.risk")
                ?.setDescription("Predicted network loss risk (0.0-1.0)")
                ?.buildWithCallback { measurement: ObservableDoubleMeasurement ->
                    val prediction = predictor.predict()
                    measurement.record(prediction.networkLossRisk)
                }

            meter?.gaugeBuilder("prediction.performance_degradation.risk")
                ?.setDescription("Predicted performance degradation risk (0.0-1.0)")
                ?.buildWithCallback { measurement: ObservableDoubleMeasurement ->
                    val prediction = predictor.predict()
                    measurement.record(prediction.performanceDegradationRisk)
                }

            meter?.gaugeBuilder("prediction.battery_drain.risk")
                ?.setDescription("Predicted battery drain risk (0.0-1.0)")
                ?.buildWithCallback { measurement: ObservableDoubleMeasurement ->
                    val prediction = predictor.predict()
                    measurement.record(prediction.batteryDrainRisk)
                }

            meter?.gaugeBuilder("prediction.confidence")
                ?.setDescription("Prediction confidence (0.0-1.0)")
                ?.buildWithCallback { measurement: ObservableDoubleMeasurement ->
                    val prediction = predictor.predict()
                    measurement.record(prediction.confidence)
                }
        }

        Log.i(TAG, "Registered ${if (predictor != null) 14 else 9} health metrics")
    }

    /**
     * Shuts down the collector and releases resources.
     */
    fun shutdown() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
        }
    }

    /**
     * Builder for HealthMetricsCollector.
     */
    class Builder(private val context: Context) {
        private var openTelemetry: OpenTelemetry? = null
        private var healthMonitor: DeviceHealthMonitor? = null
        private var predictor: OnDevicePredictor? = null
        private var collectionIntervalSeconds: Long = 30  // Every 30 seconds

        fun setOpenTelemetry(openTelemetry: OpenTelemetry) = apply {
            this.openTelemetry = openTelemetry
        }

        fun setHealthMonitor(monitor: DeviceHealthMonitor) = apply {
            this.healthMonitor = monitor
        }

        fun setPredictor(predictor: OnDevicePredictor) = apply {
            this.predictor = predictor
        }

        fun setCollectionIntervalSeconds(seconds: Long) = apply {
            this.collectionIntervalSeconds = seconds
        }

        fun build(): HealthMetricsCollector {
            return HealthMetricsCollector(
                context = context,
                openTelemetry = openTelemetry,
                healthMonitor = healthMonitor ?: DeviceHealthMonitor.getInstance(context),
                predictor = predictor,
                collectionIntervalSeconds = collectionIntervalSeconds
            )
        }
    }

    companion object {
        fun builder(context: Context): Builder = Builder(context)
    }
}
