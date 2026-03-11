/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.predictive

import android.content.Context
import android.util.Log
import io.opentelemetry.android.mobile.buffering.MobileLogRecordProcessor
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Export policy that integrates predictive intelligence.
 *
 * This policy extends the standard export behavior with predictive actions:
 *
 * **Predictive Actions:**
 * 1. **Pre-emptive flush**: Flush buffers when network loss is predicted
 * 2. **Crash risk flush**: Flush buffers when crash risk is high
 * 3. **Predictive events**: Emit prediction events to OTEL for monitoring
 *
 * **Integration with MobileLogRecordProcessor:**
 * The predictor runs in the background and triggers forceFlush() on the processor
 * when high-risk conditions are detected, ensuring telemetry is exported before
 * the predicted failure occurs.
 *
 * Usage:
 * ```kotlin
 * val policy = PredictiveExportPolicy.builder(context)
 *     .setProcessor(mobileProcessor)
 *     .setLogger(logger)
 *     .setPredictionIntervalSeconds(10)
 *     .setHighRiskThreshold(0.7)
 *     .build()
 * ```
 */
class PredictiveExportPolicy private constructor(
    private val context: Context,
    private val processor: MobileLogRecordProcessor?,
    private val logger: Logger?,
    private val predictor: OnDevicePredictor,
    private val healthMonitor: DeviceHealthMonitor,
    private val predictionIntervalSeconds: Long,
    private val highRiskThreshold: Double,
    // When false, the caller drives runPredictionCycle() externally (e.g. via HYBRID heartbeat).
    // Setting this true AND wiring predictionCycleHook would run prediction twice per tick.
    private val startOwnScheduler: Boolean
) {
    private val TAG = "PredictiveExportPolicy"

    // Current prediction (updated periodically)
    private val currentPrediction = AtomicReference<Prediction>()

    // Executor for background prediction tasks
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    // Prediction listener (optional callback)
    private var predictionListener: PredictionListener? = null

    init {
        if (startOwnScheduler) {
            // Only start a self-owned scheduler when the caller is NOT driving prediction cycles
            // externally. In HYBRID mode MobileLogRecordProcessor.emitHeartbeat() calls
            // runPredictionCycle() via predictionCycleHook — starting a second scheduler here
            // would fire flushWindow() twice per tick, re-exporting already-exported events.
            executor.scheduleAtFixedRate(
                { runPredictionCycle() },
                0,
                predictionIntervalSeconds,
                TimeUnit.SECONDS
            )
            Log.i(TAG, "Initialized with own scheduler: interval=${predictionIntervalSeconds}s, threshold=$highRiskThreshold")
        } else {
            Log.i(TAG, "Initialized without own scheduler (driven externally): threshold=$highRiskThreshold")
        }
    }

    /**
     * Runs a prediction cycle and takes appropriate actions.
     *
     * Exposed as `internal` so [MobileLogRecordProcessor] can invoke it from
     * the HYBRID heartbeat tick, co-locating the prediction cycle with the
     * device.heartbeat log emission on a single shared timer.
     */
    internal fun runPredictionCycle() {
        try {
            // Update health snapshot
            healthMonitor.updateSnapshot()

            // Generate prediction
            val prediction = predictor.predict()
            currentPrediction.set(prediction)

            // Emit prediction event as OTEL log (for backend monitoring/analysis)
            emitPredictionEvent(prediction)

            // Take actions based on prediction
            if (prediction.hasHighRisk(highRiskThreshold)) {
                handleHighRiskPrediction(prediction)
            }

            // Notify listener
            predictionListener?.onPrediction(prediction)

        } catch (e: Exception) {
            Log.e(TAG, "Error in prediction cycle", e)
        }
    }

    /**
     * Handles high-risk predictions with appropriate actions.
     */
    private fun handleHighRiskPrediction(prediction: Prediction) {
        Log.w(TAG, "High risk detected: crash=${prediction.crashRisk}, " +
                "networkLoss=${prediction.networkLossRisk}, " +
                "perfDegradation=${prediction.performanceDegradationRisk}, " +
                "batteryDrain=${prediction.batteryDrainRisk}")

        // Action 1: Pre-emptive flush if network loss imminent — export last 2 min before
        // connectivity drops. forceFlush() would dump the entire buffer unnecessarily.
        if (prediction.networkLossRisk >= highRiskThreshold) {
            Log.i(TAG, "Network loss predicted (${prediction.networkLossRisk}), flushing last 2 min")
            processor?.flushWindow(2)
        }

        // Action 2: If crash risk high, flush the last 5 min so pre-crash context is captured.
        // forceFlush() would dump the entire buffer; a selective window is sufficient and cheaper.
        if (prediction.crashRisk >= highRiskThreshold) {
            Log.i(TAG, "Crash risk high (${prediction.crashRisk}), flushing last 5 min")
            processor?.flushWindow(5)
        }

        // Action 3: Emit high-risk alert event
        emitHighRiskAlert(prediction)
    }

    /**
     * Emits a prediction event to OTEL for monitoring.
     *
     * This creates visibility into predictions at the backend,
     * enabling analysis of prediction accuracy and model tuning.
     */
    private fun emitPredictionEvent(prediction: Prediction) {
        logger?.logRecordBuilder()
            ?.setBody("prediction.cycle")
            ?.setSeverity(Severity.DEBUG)
            ?.setAllAttributes(
                Attributes.builder()
                    .put(AttributeKey.doubleKey("prediction.crash_risk"), prediction.crashRisk)
                    .put(AttributeKey.doubleKey("prediction.network_loss_risk"), prediction.networkLossRisk)
                    .put(AttributeKey.doubleKey("prediction.perf_degradation_risk"), prediction.performanceDegradationRisk)
                    .put(AttributeKey.doubleKey("prediction.battery_drain_risk"), prediction.batteryDrainRisk)
                    .put(AttributeKey.doubleKey("prediction.confidence"), prediction.confidence)
                    .put(AttributeKey.doubleKey("prediction.max_risk"), prediction.getMaxRisk())
                    .build()
            )
            ?.emit()
    }

    /**
     * Emits a high-risk alert event.
     */
    private fun emitHighRiskAlert(prediction: Prediction) {
        logger?.logRecordBuilder()
            ?.setBody("prediction.high_risk_alert")
            ?.setSeverity(Severity.WARN)
            ?.setAllAttributes(
                Attributes.builder()
                    .put(AttributeKey.doubleKey("prediction.crash_risk"), prediction.crashRisk)
                    .put(AttributeKey.doubleKey("prediction.network_loss_risk"), prediction.networkLossRisk)
                    .put(AttributeKey.doubleKey("prediction.perf_degradation_risk"), prediction.performanceDegradationRisk)
                    .put(AttributeKey.doubleKey("prediction.battery_drain_risk"), prediction.batteryDrainRisk)
                    .put(AttributeKey.doubleKey("prediction.max_risk"), prediction.getMaxRisk())
                    .put(AttributeKey.booleanKey("prediction.flush_triggered"), true)
                    .build()
            )
            ?.emit()
    }

    /**
     * Gets the current prediction (may be null if not yet run).
     */
    fun getCurrentPrediction(): Prediction? {
        return currentPrediction.get()
    }

    /**
     * Sets a listener to be notified of predictions.
     */
    fun setPredictionListener(listener: PredictionListener) {
        this.predictionListener = listener
    }

    /**
     * Checks if network loss is imminent (for use by processor).
     */
    fun isNetworkLossImminent(): Boolean {
        val prediction = currentPrediction.get() ?: return false
        return prediction.networkLossRisk >= highRiskThreshold
    }

    /**
     * Checks if crash risk is high (for use by processor).
     */
    fun isCrashRiskHigh(): Boolean {
        val prediction = currentPrediction.get() ?: return false
        return prediction.crashRisk >= highRiskThreshold
    }

    /**
     * Shuts down the policy and releases resources.
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
     * Builder for PredictiveExportPolicy.
     */
    class Builder(private val context: Context) {
        private var processor: MobileLogRecordProcessor? = null
        private var logger: Logger? = null
        private var predictor: OnDevicePredictor? = null
        private var healthMonitor: DeviceHealthMonitor? = null
        private var predictionIntervalSeconds: Long = 30
        private var highRiskThreshold: Double = 0.7
        private var startOwnScheduler: Boolean = true

        fun setProcessor(processor: MobileLogRecordProcessor) = apply {
            this.processor = processor
        }

        fun setLogger(logger: Logger) = apply {
            this.logger = logger
        }

        fun setPredictor(predictor: OnDevicePredictor) = apply {
            this.predictor = predictor
        }

        fun setHealthMonitor(monitor: DeviceHealthMonitor) = apply {
            this.healthMonitor = monitor
        }

        fun setPredictionIntervalSeconds(seconds: Long) = apply {
            this.predictionIntervalSeconds = seconds
        }

        fun setHighRiskThreshold(threshold: Double) = apply {
            this.highRiskThreshold = threshold
        }

        /**
         * Set to false when the caller will drive [runPredictionCycle] externally
         * (e.g. via [MobileLogRecordProcessor.predictionCycleHook] in HYBRID mode).
         * Leaving this true while also wiring predictionCycleHook will fire prediction
         * twice per heartbeat tick and cause duplicate exports.
         */
        fun setStartOwnScheduler(start: Boolean) = apply {
            this.startOwnScheduler = start
        }

        fun build(): PredictiveExportPolicy {
            return PredictiveExportPolicy(
                context = context,
                processor = processor,
                logger = logger,
                predictor = predictor ?: OnDevicePredictor.getInstance(context),
                healthMonitor = healthMonitor ?: DeviceHealthMonitor.getInstance(context),
                predictionIntervalSeconds = predictionIntervalSeconds,
                highRiskThreshold = highRiskThreshold,
                startOwnScheduler = startOwnScheduler
            )
        }
    }

    companion object {
        fun builder(context: Context): Builder = Builder(context)
    }
}

/**
 * Listener for prediction events.
 */
interface PredictionListener {
    /**
     * Called when a new prediction is generated.
     */
    fun onPrediction(prediction: Prediction)
}
