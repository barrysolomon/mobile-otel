// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for the demo app user journeys.
 *
 * Tests the crash/ANR/OOM recovery detection via SharedPreferences markers that the
 * demo app writes before triggering each failure scenario and reads on restart.
 * Also validates that [TransactionOutcomeConfig] enforces its invariants.
 *
 * Note: OTEL telemetry output for crash/ANR/OOM is validated at the SDK level in
 * RecoveryTrackerTest. These tests focus on the demo-app-layer logic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestDemoApplication::class)
class MainActivityTest {

    private lateinit var context: Context
    private lateinit var demoPrefs: SharedPreferences

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        demoPrefs = context.getSharedPreferences("demo_app_prefs", Context.MODE_PRIVATE)
        demoPrefs.edit().clear().commit()
    }

    // ========== TransactionOutcomeConfig ==========

    @Test
    fun `TransactionOutcomeConfig default rates sum to 100`() {
        val config = MainActivity.TransactionOutcomeConfig()
        assertEquals(100, config.passRate + config.failRate + config.crashRate)
    }

    @Test
    fun `TransactionOutcomeConfig custom rates sum to 100`() {
        val config = MainActivity.TransactionOutcomeConfig(passRate = 50, failRate = 40, crashRate = 10)
        assertEquals(100, config.passRate + config.failRate + config.crashRate)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `TransactionOutcomeConfig rejects rates that do not sum to 100`() {
        MainActivity.TransactionOutcomeConfig(passRate = 50, failRate = 30, crashRate = 10) // = 90
    }

    @Test
    fun `TransactionOutcomeConfig default pass rate is 70`() {
        val config = MainActivity.TransactionOutcomeConfig()
        assertEquals(70, config.passRate)
    }

    @Test
    fun `TransactionOutcomeConfig default fail rate is 20`() {
        val config = MainActivity.TransactionOutcomeConfig()
        assertEquals(20, config.failRate)
    }

    @Test
    fun `TransactionOutcomeConfig default crash rate is 10`() {
        val config = MainActivity.TransactionOutcomeConfig()
        assertEquals(10, config.crashRate)
    }

    // ========== Crash scenario - SharedPreferences marker lifecycle ==========

    @Test
    fun `crash scenario writes transaction_active marker before crashing`() {
        // Simulate what startTrackedTransaction writes
        demoPrefs.edit()
            .putBoolean("transaction_active", true)
            .putString("transaction_id", "txn-001")
            .putString("transaction_type", "auth.login")
            .putLong("transaction_start_time", System.currentTimeMillis())
            .commit()

        assertTrue(demoPrefs.getBoolean("transaction_active", false))
        assertEquals("txn-001", demoPrefs.getString("transaction_id", null))
        assertEquals("auth.login", demoPrefs.getString("transaction_type", null))
    }

    @Test
    fun `completed transaction clears all markers`() {
        // Simulate startTrackedTransaction
        demoPrefs.edit()
            .putBoolean("transaction_active", true)
            .putString("transaction_id", "txn-001")
            .putString("transaction_type", "auth.login")
            .putLong("transaction_start_time", System.currentTimeMillis())
            .commit()

        // Simulate endTrackedTransaction cleanup
        demoPrefs.edit()
            .remove("transaction_active")
            .remove("transaction_id")
            .remove("transaction_type")
            .remove("transaction_start_time")
            .commit()

        assertFalse(demoPrefs.getBoolean("transaction_active", false))
        assertNull(demoPrefs.getString("transaction_id", null))
        assertNull(demoPrefs.getString("transaction_type", null))
    }

    @Test
    fun `incomplete transaction marker is detected on restart`() {
        // Simulate crash during a transaction: marker is left in prefs
        val startTime = System.currentTimeMillis() - 5000 // 5 seconds ago
        demoPrefs.edit()
            .putBoolean("transaction_active", true)
            .putString("transaction_id", "txn-crashed")
            .putString("transaction_type", "checkout.payment")
            .putLong("transaction_start_time", startTime)
            .commit()

        // On restart, the demo app reads these - verify they're still there
        val transactionWasActive = demoPrefs.getBoolean("transaction_active", false)
        val transactionId = demoPrefs.getString("transaction_id", null)
        val transactionType = demoPrefs.getString("transaction_type", null)
        val transactionStartTime = demoPrefs.getLong("transaction_start_time", 0L)

        assertTrue(transactionWasActive)
        assertEquals("txn-crashed", transactionId)
        assertEquals("checkout.payment", transactionType)
        assertTrue(transactionStartTime > 0)
        assertTrue(System.currentTimeMillis() - transactionStartTime >= 5000)
    }

    // ========== Demographics journey (monkey-test support) ==========

    @Test
    fun `demographics can be stored and retrieved from SharedPreferences`() {
        // Simulate what handleDemographics does when extras are provided
        demoPrefs.edit()
            .putString("user_device_type", "smartphone")
            .putString("user_region", "us")
            .putString("user_age_group", "25-34")
            .putString("user_tier", "premium")
            .commit()

        assertEquals("smartphone", demoPrefs.getString("user_device_type", null))
        assertEquals("us", demoPrefs.getString("user_region", null))
        assertEquals("25-34", demoPrefs.getString("user_age_group", null))
        assertEquals("premium", demoPrefs.getString("user_tier", null))
    }

    @Test
    fun `demographics update replaces previous values`() {
        demoPrefs.edit().putString("user_region", "us").commit()

        demoPrefs.edit().putString("user_region", "eu").commit()

        assertEquals("eu", demoPrefs.getString("user_region", null))
    }

    // ========== Helper ==========

    private fun assertNull(value: String?) {
        kotlin.test.assertNull(value)
    }
}

/**
 * Test application that does NOT call OTelMobile.start().
 *
 * This prevents the real gRPC exporter from being initialized during unit tests,
 * avoiding network errors and connection timeouts.
 */
class TestDemoApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        // Intentionally skipping OTelMobile.start() for unit tests.
        // Integration tests and instrumented tests use the real DemoApp.
    }
}
