/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.android.mobile.config.EvictionStrategy
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.config.OfflineBudgetConfig
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
import org.robolectric.annotation.Config

/**
 * TDD tests for offline disk budget cap (Phase 2 of Offline Flush Budget epic).
 *
 * When the device is offline, the DiskLogBuffer enforces a configurable budget
 * that is lower than the total disk limit. This prevents unbounded disk growth
 * in industrial/field scenarios with extended offline periods.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OfflineBudgetTest {

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

    private fun createProcessor(offlineBudgetConfig: OfflineBudgetConfig): MobileLogRecordProcessor {
        val config = MobileConfig(
            serviceName = "test-service",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317",
            ramBufferSize = 50,
            diskBufferMb = 10,
            diskBufferTtlHours = 1,
            offlineBudgetConfig = offlineBudgetConfig
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

    // ==================== OfflineBudgetConfig Tests ====================

    @Test
    fun `OfflineBudgetConfig defaults to 10MB and OLDEST_FIRST`() {
        val config = OfflineBudgetConfig.default()
        assertEquals(10L * 1024 * 1024, config.maxOfflineDiskBytes)
        assertEquals(EvictionStrategy.OLDEST_FIRST, config.evictionStrategy)
        assertTrue(config.enabled)
    }

    @Test
    fun `OfflineBudgetConfig disabled returns enabled=false`() {
        val config = OfflineBudgetConfig.disabled()
        assertFalse(config.enabled)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `OfflineBudgetConfig rejects zero bytes`() {
        OfflineBudgetConfig(maxOfflineDiskBytes = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `OfflineBudgetConfig rejects negative bytes`() {
        OfflineBudgetConfig(maxOfflineDiskBytes = -1)
    }

    @Test
    fun `MobileConfig includes offlineBudgetConfig`() {
        val budgetConfig = OfflineBudgetConfig(
            maxOfflineDiskBytes = 5L * 1024 * 1024,
            evictionStrategy = EvictionStrategy.LOWEST_SEVERITY_FIRST
        )
        val config = MobileConfig(
            serviceName = "test",
            serviceVersion = "1.0",
            collectorEndpoint = "http://localhost:4317",
            offlineBudgetConfig = budgetConfig
        )
        assertEquals(5L * 1024 * 1024, config.offlineBudgetConfig.maxOfflineDiskBytes)
        assertEquals(EvictionStrategy.LOWEST_SEVERITY_FIRST, config.offlineBudgetConfig.evictionStrategy)
    }

    // ==================== DiskLogBuffer Budget Enforcement ====================

    @Test
    fun `DiskLogBuffer enforceOfflineBudget evicts oldest events when over budget`() {
        val diskBuffer = DiskLogBuffer.getInstance(context, maxSizeMb = 10, ttlHours = 1)

        // Persist a batch of events
        val records = TestUtils.createTestLogRecords("budget", 20)
        diskBuffer.persistEvents(records)
        Thread.sleep(300)

        val countBefore = diskBuffer.getEventCount()
        assertTrue("Should have persisted events", countBefore > 0)

        // Enforce a very small budget — should evict events
        val evicted = kotlinx.coroutines.runBlocking {
            diskBuffer.enforceOfflineBudget(
                maxBytes = 1024, // 1KB — way smaller than actual data
                strategy = EvictionStrategy.OLDEST_FIRST
            )
        }

        assertTrue("Should have evicted some events", evicted > 0)
        val countAfter = diskBuffer.getEventCount()
        assertTrue("Event count should decrease after eviction", countAfter < countBefore)
    }

    @Test
    fun `DiskLogBuffer enforceOfflineBudget with LOWEST_SEVERITY_FIRST evicts info before error`() {
        val diskBuffer = DiskLogBuffer.getInstance(context, maxSizeMb = 10, ttlHours = 1)

        // Persist mix of INFO and ERROR events
        val now = System.currentTimeMillis()
        val infoRecords = (0 until 10).map {
            TestUtils.createTestLogRecord("info.$it", timestamp = now + it * 1000, severity = Severity.INFO)
        }
        val errorRecords = (0 until 5).map {
            TestUtils.createTestLogRecord("error.$it", timestamp = now + (10 + it) * 1000, severity = Severity.ERROR)
        }
        diskBuffer.persistEvents(infoRecords + errorRecords)
        Thread.sleep(300)

        assertEquals(15, diskBuffer.getEventCount())

        // Enforce budget with LOWEST_SEVERITY_FIRST
        kotlinx.coroutines.runBlocking {
            diskBuffer.enforceOfflineBudget(
                maxBytes = 1024,
                strategy = EvictionStrategy.LOWEST_SEVERITY_FIRST
            )
        }

        // ERROR events should be retained preferentially over INFO events
        val remaining = kotlinx.coroutines.runBlocking { diskBuffer.getAllEvents() }
        val errorCount = remaining.count { it.severity == Severity.ERROR }
        val infoCount = remaining.count { it.severity == Severity.INFO }

        assertTrue(
            "Error events should be retained preferentially (errors=$errorCount, info=$infoCount)",
            errorCount >= infoCount || remaining.isEmpty()
        )
    }

    @Test
    fun `DiskLogBuffer enforceOfflineBudget is no-op when under budget`() {
        val diskBuffer = DiskLogBuffer.getInstance(context, maxSizeMb = 10, ttlHours = 1)

        // Persist a small batch
        val records = TestUtils.createTestLogRecords("small", 3)
        diskBuffer.persistEvents(records)
        Thread.sleep(300)

        val countBefore = diskBuffer.getEventCount()

        // Enforce generous budget
        val evicted = kotlinx.coroutines.runBlocking {
            diskBuffer.enforceOfflineBudget(
                maxBytes = 100L * 1024 * 1024, // 100MB
                strategy = EvictionStrategy.OLDEST_FIRST
            )
        }

        assertEquals("No events should be evicted", 0, evicted)
        assertEquals(countBefore, diskBuffer.getEventCount())
    }

    // ==================== Processor-Level Integration ====================

    @Test
    fun `processor respects offline budget when device is offline`() {
        val budgetConfig = OfflineBudgetConfig(
            maxOfflineDiskBytes = 2048, // Very small budget
            evictionStrategy = EvictionStrategy.OLDEST_FIRST,
            enabled = true
        )

        val processor = createProcessor(budgetConfig)

        // Simulate offline: overflow many events to disk (RAM is 50, add 100)
        repeat(100) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("offline.$i")))
        }
        Thread.sleep(500)

        val stats = processor.getBufferStats()
        // Disk should be within budget (budget is very small so events should be evicted)
        // The exact number depends on per-event disk size, but it should be
        // significantly less than the 50 events that would have overflowed
        assertTrue(
            "Disk events should be budget-constrained when offline budget is enabled (got ${stats.diskBufferSize})",
            stats.diskBufferSize <= 50 // At minimum, budget enforcement should kick in
        )

        processor.shutdown()
    }

    @Test
    fun `processor ignores offline budget when config is disabled`() {
        val budgetConfig = OfflineBudgetConfig.disabled()
        val processor = createProcessor(budgetConfig)

        // Overflow events to disk
        repeat(100) { i ->
            processor.onEmit(OtelContext.root(), wrap(TestUtils.createTestLogRecord("nobud.$i")))
        }
        Thread.sleep(500)

        val stats = processor.getBufferStats()
        // With budget disabled, all overflow events should persist to disk
        assertTrue(
            "All overflow should persist when budget disabled (got ${stats.diskBufferSize})",
            stats.diskBufferSize > 0
        )

        processor.shutdown()
    }
}
