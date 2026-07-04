/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.config.OfflineBudgetConfig
import io.opentelemetry.android.mobile.config.OfflinePolicy
import io.opentelemetry.android.mobile.config.minBufferSeverity
import io.opentelemetry.android.mobile.config.dropsAll
import io.opentelemetry.android.mobile.testing.MockLogRecordExporter
import io.opentelemetry.android.mobile.testing.TestUtils
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.context.Context as OtelContext
import io.opentelemetry.sdk.logs.ReadWriteLogRecord
import io.opentelemetry.sdk.logs.data.LogRecordData
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetworkInfo

/**
 * TDD tests for offline policy (Phase 4 of Offline Flush Budget epic).
 *
 * Validates that the OfflinePolicy controls what gets buffered when
 * the device has no network connectivity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OfflinePolicyTest {

    private lateinit var context: Context
    private lateinit var mockExporter: MockLogRecordExporter

    @Before
    fun setup() {
        DiskLogBuffer.resetForTesting()
        context = ApplicationProvider.getApplicationContext()
        mockExporter = MockLogRecordExporter()
    }

    @After
    fun teardown() {
        DiskLogBuffer.resetForTesting()
    }

    private fun wrap(data: LogRecordData): ReadWriteLogRecord {
        val mock = mockk<ReadWriteLogRecord>(relaxed = true)
        every { mock.toLogRecordData() } returns data
        return mock
    }

    private fun createProcessor(offlinePolicy: OfflinePolicy): MobileLogRecordProcessor {
        val config = MobileConfig(
            serviceName = "test-service",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317",
            ramBufferSize = 100,
            diskBufferMb = 10,
            diskBufferTtlHours = 1,
            offlinePolicy = offlinePolicy
        )

        val meter = OpenTelemetry.noop().meterProvider.get("test")
        return MobileLogRecordProcessor.builder(context)
            .setExporter(mockExporter)
            .setConfig(config)
            .setMeter(meter)
            .setRamBufferSize(config.ramBufferSize)
            .setDiskBufferMb(config.diskBufferMb)
            .setDiskBufferTtlHours(config.diskBufferTtlHours)
            .build()
    }

    private fun setNetworkOffline() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadow = Shadows.shadowOf(cm)
        shadow.setActiveNetworkInfo(null)
    }

    private fun setNetworkOnline() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadow = Shadows.shadowOf(cm)
        shadow.setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(
                null, // detailedState
                ConnectivityManager.TYPE_WIFI,
                0, // subtype
                true, // isAvailable
                true  // isConnected
            )
        )
    }

    // ==================== OfflinePolicy Config Tests ====================

    @Test
    fun `OfflinePolicy BUFFER_ALL has no min severity`() {
        assertNull(OfflinePolicy.BUFFER_ALL.minBufferSeverity())
        assertFalse(OfflinePolicy.BUFFER_ALL.dropsAll())
    }

    @Test
    fun `OfflinePolicy ERROR_ONLY has ERROR min severity`() {
        assertEquals(Severity.ERROR, OfflinePolicy.ERROR_ONLY.minBufferSeverity())
        assertFalse(OfflinePolicy.ERROR_ONLY.dropsAll())
    }

    @Test
    fun `OfflinePolicy WARN_AND_ABOVE has WARN min severity`() {
        assertEquals(Severity.WARN, OfflinePolicy.WARN_AND_ABOVE.minBufferSeverity())
    }

    @Test
    fun `OfflinePolicy DROP_ALL drops everything`() {
        assertTrue(OfflinePolicy.DROP_ALL.dropsAll())
    }

    @Test
    fun `MobileConfig includes offlinePolicy`() {
        val config = MobileConfig(
            serviceName = "test",
            serviceVersion = "1.0",
            collectorEndpoint = "http://localhost:4317",
            offlinePolicy = OfflinePolicy.ERROR_ONLY
        )
        assertEquals(OfflinePolicy.ERROR_ONLY, config.offlinePolicy)
    }

    // ==================== Processor Behavior Tests ====================

    @Test
    fun `ERROR_ONLY drops INFO events when offline`() {
        setNetworkOffline()
        val processor = createProcessor(OfflinePolicy.ERROR_ONLY)

        // Emit INFO events — should be dropped
        repeat(5) { i ->
            processor.onEmit(OtelContext.root(), wrap(
                TestUtils.createTestLogRecord("info.$i", severity = Severity.INFO)
            ))
        }

        val stats = processor.getBufferStats()
        assertEquals("INFO events should be dropped when offline + ERROR_ONLY", 0, stats.ramBufferSize)

        processor.shutdown()
    }

    @Test
    fun `ERROR_ONLY buffers ERROR events when offline`() {
        setNetworkOffline()
        val processor = createProcessor(OfflinePolicy.ERROR_ONLY)

        // Emit ERROR events — should be buffered
        repeat(3) { i ->
            processor.onEmit(OtelContext.root(), wrap(
                TestUtils.createTestLogRecord("error.$i", severity = Severity.ERROR)
            ))
        }

        val stats = processor.getBufferStats()
        assertEquals("ERROR events should be buffered when offline + ERROR_ONLY", 3, stats.ramBufferSize)

        processor.shutdown()
    }

    @Test
    fun `ERROR_ONLY buffers all events when online`() {
        setNetworkOnline()
        val processor = createProcessor(OfflinePolicy.ERROR_ONLY)

        // Emit INFO events — should be buffered since we're online
        repeat(5) { i ->
            processor.onEmit(OtelContext.root(), wrap(
                TestUtils.createTestLogRecord("info.$i", severity = Severity.INFO)
            ))
        }

        val stats = processor.getBufferStats()
        assertEquals("All events should buffer when online", 5, stats.ramBufferSize)

        processor.shutdown()
    }

    @Test
    fun `BUFFER_ALL buffers everything when offline`() {
        setNetworkOffline()
        val processor = createProcessor(OfflinePolicy.BUFFER_ALL)

        repeat(5) { i ->
            processor.onEmit(OtelContext.root(), wrap(
                TestUtils.createTestLogRecord("event.$i", severity = Severity.INFO)
            ))
        }

        val stats = processor.getBufferStats()
        assertEquals("BUFFER_ALL should buffer everything", 5, stats.ramBufferSize)

        processor.shutdown()
    }

    @Test
    fun `DROP_ALL drops everything when offline`() {
        setNetworkOffline()
        val processor = createProcessor(OfflinePolicy.DROP_ALL)

        repeat(5) { i ->
            processor.onEmit(OtelContext.root(), wrap(
                TestUtils.createTestLogRecord("event.$i", severity = Severity.ERROR)
            ))
        }

        val stats = processor.getBufferStats()
        assertEquals("DROP_ALL should drop even ERROR events when offline", 0, stats.ramBufferSize)

        processor.shutdown()
    }

    @Test
    fun `WARN_AND_ABOVE drops DEBUG and INFO when offline`() {
        setNetworkOffline()
        val processor = createProcessor(OfflinePolicy.WARN_AND_ABOVE)

        processor.onEmit(OtelContext.root(), wrap(
            TestUtils.createTestLogRecord("debug.event", severity = Severity.DEBUG)
        ))
        processor.onEmit(OtelContext.root(), wrap(
            TestUtils.createTestLogRecord("info.event", severity = Severity.INFO)
        ))
        processor.onEmit(OtelContext.root(), wrap(
            TestUtils.createTestLogRecord("warn.event", severity = Severity.WARN)
        ))
        processor.onEmit(OtelContext.root(), wrap(
            TestUtils.createTestLogRecord("error.event", severity = Severity.ERROR)
        ))

        val stats = processor.getBufferStats()
        assertEquals("Only WARN and ERROR should be buffered", 2, stats.ramBufferSize)

        processor.shutdown()
    }

    // ==================== Error Coalescing Integration ====================

    @Test
    fun `error coalescing suppresses duplicate errors in onEmit`() {
        setNetworkOnline()
        val processor = createProcessor(OfflinePolicy.BUFFER_ALL)

        // Emit the same error 5 times rapidly. The coalescer keys on
        // exception.type|exception.message, not the body — so use a body that does
        // NOT match the built-in crash-recovery policy ("app.crash"): that policy
        // fires an async flushWindow() which races this test's buffer assertion
        // (the export could clear RAM before getBufferStats() reads it). This test
        // is about coalescing in onEmit, not policy-triggered export.
        repeat(5) {
            processor.onEmit(OtelContext.root(), wrap(
                TestUtils.createTestLogRecord(
                    body = "app.error",
                    attributes = mapOf(
                        "exception.type" to "NullPointerException",
                        "exception.message" to "null reference"
                    ),
                    severity = Severity.ERROR
                )
            ))
        }

        val stats = processor.getBufferStats()
        assertEquals(
            "Only first occurrence should be buffered, rest coalesced",
            1,
            stats.ramBufferSize
        )

        processor.shutdown()
    }
}
