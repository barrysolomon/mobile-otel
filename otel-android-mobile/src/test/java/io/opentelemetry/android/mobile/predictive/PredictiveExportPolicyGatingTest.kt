/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.predictive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.android.mobile.buffering.DiskLogBuffer
import io.opentelemetry.android.mobile.buffering.MobileLogRecordProcessor
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TDD tests for PredictiveExportPolicy gating (Phase 5 of Offline Flush Budget epic).
 *
 * When offline policy is active, the prediction-driven networkLossRisk → flushWindow(2)
 * should be suppressed to avoid wasting battery on exports that will fail anyway.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PredictiveExportPolicyGatingTest {

    private lateinit var context: Context
    private lateinit var mockProcessor: MobileLogRecordProcessor
    private lateinit var mockPredictor: OnDevicePredictor
    private lateinit var mockHealthMonitor: DeviceHealthMonitor

    @Before
    fun setup() {
        DiskLogBuffer.resetForTesting()
        context = ApplicationProvider.getApplicationContext()
        mockProcessor = mockk(relaxed = true)
        mockPredictor = mockk(relaxed = true)
        mockHealthMonitor = mockk(relaxed = true)
    }

    @After
    fun teardown() {
        DiskLogBuffer.resetForTesting()
    }

    private fun highNetworkLossPrediction() = Prediction(
        timestampMs = System.currentTimeMillis(),
        crashRisk = 0.1,
        networkLossRisk = 0.9,
        performanceDegradationRisk = 0.1,
        batteryDrainRisk = 0.1,
        confidence = 0.85
    )

    @Test
    fun `networkLossRisk flush is NOT suppressed when gating is off`() {
        every { mockPredictor.predict() } returns highNetworkLossPrediction()

        val policy = PredictiveExportPolicy.builder(context)
            .setProcessor(mockProcessor)
            .setPredictor(mockPredictor)
            .setHealthMonitor(mockHealthMonitor)
            .setHighRiskThreshold(0.7)
            .setStartOwnScheduler(false)
            .setSuppressNetworkLossFlush(false)
            .build()

        policy.runPredictionCycle()

        verify(exactly = 1) { mockProcessor.flushWindow(2) }

        policy.shutdown()
    }

    @Test
    fun `networkLossRisk flush IS suppressed when gating is on`() {
        every { mockPredictor.predict() } returns highNetworkLossPrediction()

        val policy = PredictiveExportPolicy.builder(context)
            .setProcessor(mockProcessor)
            .setPredictor(mockPredictor)
            .setHealthMonitor(mockHealthMonitor)
            .setHighRiskThreshold(0.7)
            .setStartOwnScheduler(false)
            .setSuppressNetworkLossFlush(true)
            .build()

        policy.runPredictionCycle()

        verify(exactly = 0) { mockProcessor.flushWindow(2) }

        policy.shutdown()
    }

    @Test
    fun `crash risk flush still fires even when network loss is suppressed`() {
        every { mockPredictor.predict() } returns Prediction(
            timestampMs = System.currentTimeMillis(),
            crashRisk = 0.9,
            networkLossRisk = 0.9,
            performanceDegradationRisk = 0.1,
            batteryDrainRisk = 0.1,
            confidence = 0.85
        )

        val policy = PredictiveExportPolicy.builder(context)
            .setProcessor(mockProcessor)
            .setPredictor(mockPredictor)
            .setHealthMonitor(mockHealthMonitor)
            .setHighRiskThreshold(0.7)
            .setStartOwnScheduler(false)
            .setSuppressNetworkLossFlush(true)
            .build()

        policy.runPredictionCycle()

        // Network loss flush should be suppressed
        verify(exactly = 0) { mockProcessor.flushWindow(2) }
        // Crash flush should still fire
        verify(exactly = 1) { mockProcessor.flushWindow(5) }

        policy.shutdown()
    }

    @Test
    fun `low risk prediction does not trigger any flush`() {
        every { mockPredictor.predict() } returns Prediction(
            timestampMs = System.currentTimeMillis(),
            crashRisk = 0.1,
            networkLossRisk = 0.2,
            performanceDegradationRisk = 0.1,
            batteryDrainRisk = 0.1,
            confidence = 0.85
        )

        val policy = PredictiveExportPolicy.builder(context)
            .setProcessor(mockProcessor)
            .setPredictor(mockPredictor)
            .setHealthMonitor(mockHealthMonitor)
            .setHighRiskThreshold(0.7)
            .setStartOwnScheduler(false)
            .setSuppressNetworkLossFlush(false)
            .build()

        policy.runPredictionCycle()

        verify(exactly = 0) { mockProcessor.flushWindow(any()) }

        policy.shutdown()
    }
}
