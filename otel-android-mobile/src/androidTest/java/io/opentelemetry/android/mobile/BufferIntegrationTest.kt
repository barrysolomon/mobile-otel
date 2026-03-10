/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.opentelemetry.android.mobile.buffering.DiskLogBuffer
import io.opentelemetry.android.mobile.buffering.MobileLogRecordProcessor
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.context.Context as OtelContext
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.logs.ReadWriteLogRecord
import io.opentelemetry.sdk.logs.data.Body
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.resources.Resource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Instrumented integration test for the full buffer flow.
 *
 * Exercises the end-to-end path:
 *   emit log record → RAM buffer → [disk overflow] → flushWindow() → exporter receives records
 *
 * Unlike the Robolectric unit tests this runs on a real Android device or emulator,
 * giving confidence that the Room database and Android system APIs behave correctly
 * under real conditions.
 *
 * Run with:
 *   cd examples/demo-app && ./gradlew :otel-android-mobile:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class BufferIntegrationTest {

    private lateinit var context: Context
    private lateinit var captureExporter: CaptureExporter
    private lateinit var processor: MobileLogRecordProcessor

    @Before
    fun setUp() {
        DiskLogBuffer.resetForTesting()
        context = ApplicationProvider.getApplicationContext()
        captureExporter = CaptureExporter()

        val config = MobileConfig(
            serviceName = "integration-test",
            serviceVersion = "0.0.1",
            collectorEndpoint = "http://localhost:4317",
            ramBufferSize = 50,     // small so overflow-to-disk tests are fast
            diskBufferMb = 5,
            diskBufferTtlHours = 1
        )
        val meter = OpenTelemetry.noop().meterProvider.get("test")
        processor = MobileLogRecordProcessor.builder(context)
            .setExporter(captureExporter)
            .setConfig(config)
            .setMeter(meter)
            .setRamBufferSize(config.ramBufferSize)
            .setDiskBufferMb(config.diskBufferMb)
            .setDiskBufferTtlHours(config.diskBufferTtlHours)
            .build()
    }

    @After
    fun tearDown() {
        processor.shutdown()
        DiskLogBuffer.resetForTesting()
    }

    // ── RAM buffer → flushWindow ─────────────────────────────────────────────

    /**
     * Core integration test: a log emitted to the processor should be returned
     * by flushWindow() and forwarded to the exporter exactly once.
     */
    @Test
    fun emittedLogAppearsInFlushWindow() {
        val body = "integration.test.event"
        val attrs = mapOf("env" to "test", "index" to 1)

        emit(createLog(body, attrs))

        val result = processor.flushWindow(1)
        assertTrue("flushWindow should succeed", result.isSuccess)

        // Allow the async whenComplete callback to run
        Thread.sleep(300)

        assertEquals("Exactly one record should be exported", 1, captureExporter.count())
        val exported = captureExporter.all().first()
        assertEquals("Exported body must match emitted body", body, exported.body.asString())
        assertEquals("Attribute 'env' must be preserved", "test",
            exported.attributes.get(AttributeKey.stringKey("env")))
    }

    /**
     * Multiple records emitted within the flush window should all be exported.
     */
    @Test
    fun multipleEmittedLogsAllAppearInFlushWindow() {
        val count = 10
        repeat(count) { i ->
            emit(createLog("event.$i", mapOf("idx" to i)))
        }

        processor.flushWindow(1)
        Thread.sleep(300)

        assertEquals("All $count emitted records must be exported", count, captureExporter.count())
    }

    /**
     * Only records whose timestamp falls within the requested window should be exported.
     * Records emitted with an old timestamp must be excluded.
     */
    @Test
    fun flushWindowFiltersOldRecordsByTimestamp() {
        val now = System.currentTimeMillis()

        // Old record: 10 minutes ago — outside a 2-minute window
        emit(createLogAt("old.event", now - 10 * 60 * 1000L))

        // Recent record: 30 seconds ago — inside a 2-minute window
        emit(createLogAt("recent.event", now - 30_000L))

        processor.flushWindow(2)
        Thread.sleep(300)

        assertEquals("Only the recent record should be exported", 1, captureExporter.count())
        assertEquals("recent.event", captureExporter.all().first().body.asString())
    }

    /**
     * After a successful flushWindow(), the RAM buffer should be cleared so that
     * a second flushWindow() (outside cooldown) exports nothing further.
     */
    @Test
    fun successfulFlushClearsRamBuffer() {
        emit(createLog("cleared.after.flush"))
        processor.flushWindow(1)
        Thread.sleep(300)

        val countAfterFirstFlush = captureExporter.count()
        assertEquals("First flush should export 1 record", 1, countAfterFirstFlush)

        // Verify RAM buffer is empty via buffer stats
        val stats = processor.getBufferStats()
        assertEquals("RAM buffer should be empty after flush", 0, stats.ramBufferSize)
    }

    // ── Disk overflow → flushWindow ──────────────────────────────────────────

    /**
     * When the RAM buffer fills up, the oldest records overflow to disk.
     * flushWindow() must recover those disk events and include them in the export.
     *
     * Note: DiskLogBuffer.toLogRecordData() is fully implemented (not a stub), so
     * disk-resident events are deserialised and forwarded to the exporter correctly.
     */
    @Test
    fun diskOverflowEventsAreIncludedInFlushWindow() {
        val ramCapacity = 50   // matches setUp() ramBufferSize
        val total = ramCapacity + 20   // 20 events overflow to disk

        // Emit enough records to overflow the RAM buffer into disk
        repeat(total) { i ->
            emit(createLog("overflow.event.$i"))
        }

        // Allow background overflow task to run (scheduled every 5s but also submitted on overflow)
        Thread.sleep(1500)

        val stats = processor.getBufferStats()
        assertTrue(
            "Some events should have been moved to disk (disk=${stats.diskBufferSize})",
            stats.diskBufferSize > 0
        )

        // flushWindow should collect RAM + disk events
        processor.flushWindow(5)
        Thread.sleep(500)

        assertTrue(
            "Export should include disk events; got ${captureExporter.count()}, emitted $total",
            captureExporter.count() >= total
        )
    }

    // ── Attribute round-trip through disk ───────────────────────────────────

    /**
     * Attributes on a disk-resident record must survive serialisation/deserialisation.
     * We emit a record with a known string attribute, force it to disk, then flush
     * and verify the attribute is present in the exported record.
     */
    @Test
    fun attributesRoundTripThroughDiskBuffer() {
        val attributeKey = "integration.marker"
        val attributeValue = "disk-round-trip"

        // Emit enough to overflow to disk
        val ramCapacity = 50
        repeat(ramCapacity) { i ->
            emit(createLog("pre-overflow.$i"))
        }
        // This record should overflow to disk (RAM is already at capacity)
        emit(createLog("marker.event", mapOf(attributeKey to attributeValue)))

        Thread.sleep(1500)   // wait for background overflow

        processor.flushWindow(5)
        Thread.sleep(500)

        val markerRecord = captureExporter.all()
            .find { it.body.asString() == "marker.event" }

        assertTrue(
            "marker.event should be exported (found ${captureExporter.count()} records total)",
            markerRecord != null
        )
        assertEquals(
            "String attribute must survive disk round-trip",
            attributeValue,
            markerRecord!!.attributes.get(AttributeKey.stringKey(attributeKey))
        )
    }

    // ── flushWindow edge cases ───────────────────────────────────────────────

    /**
     * flushWindow(0) is a no-op per the contract in MobileLogRecordProcessor.
     */
    @Test
    fun flushWindowZeroIsNoOp() {
        emit(createLog("should.not.export"))
        val result = processor.flushWindow(0)
        Thread.sleep(100)

        assertTrue("flushWindow(0) must return success", result.isSuccess)
        assertEquals("flushWindow(0) must export nothing", 0, captureExporter.count())
    }

    /**
     * flushWindow() on an empty buffer returns success without calling the exporter.
     */
    @Test
    fun flushWindowOnEmptyBufferSucceeds() {
        val result = processor.flushWindow(1)
        Thread.sleep(100)

        assertTrue("Empty-buffer flush must succeed", result.isSuccess)
        assertEquals("Empty-buffer flush must export nothing", 0, captureExporter.count())
    }

    // ── Severity and body preservation ──────────────────────────────────────

    /**
     * The severity level of an emitted record must be preserved through the buffer
     * and returned correctly by the exporter.
     */
    @Test
    fun severityIsPreservedThroughBuffer() {
        emit(createLog("warn.event", severity = Severity.WARN))
        processor.flushWindow(1)
        Thread.sleep(300)

        assertEquals(1, captureExporter.count())
        assertEquals(Severity.WARN, captureExporter.all().first().severity)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun createLog(
        body: String,
        attributes: Map<String, Any> = emptyMap(),
        severity: Severity = Severity.INFO,
        timestampMs: Long = System.currentTimeMillis()
    ): LogRecordData {
        val attrsBuilder = Attributes.builder()
        attributes.forEach { (k, v) ->
            when (v) {
                is String  -> attrsBuilder.put(AttributeKey.stringKey(k), v)
                is Int     -> attrsBuilder.put(AttributeKey.longKey(k), v.toLong())
                is Long    -> attrsBuilder.put(AttributeKey.longKey(k), v)
                is Double  -> attrsBuilder.put(AttributeKey.doubleKey(k), v)
                is Boolean -> attrsBuilder.put(AttributeKey.booleanKey(k), v)
            }
        }
        val builtAttrs = attrsBuilder.build()
        val tsNanos = timestampMs * 1_000_000L
        return object : LogRecordData {
            override fun getResource(): Resource = Resource.empty()
            override fun getInstrumentationScopeInfo(): InstrumentationScopeInfo =
                InstrumentationScopeInfo.empty()
            override fun getTimestampEpochNanos(): Long = tsNanos
            override fun getObservedTimestampEpochNanos(): Long = tsNanos
            override fun getSpanContext(): SpanContext = SpanContext.getInvalid()
            override fun getSeverity(): Severity = severity
            override fun getSeverityText(): String = severity.name
            override fun getBody(): Body = object : Body {
                override fun asString() = body
                override fun getType() = Body.Type.STRING
            }
            override fun getAttributes(): Attributes = builtAttrs
            override fun getTotalAttributeCount(): Int = builtAttrs.size()
        }
    }

    /** Convenience: create a log record pinned to a specific epoch-millisecond timestamp. */
    private fun createLogAt(body: String, timestampMs: Long) =
        createLog(body, timestampMs = timestampMs)

    /** Wraps a LogRecordData in a ReadWriteLogRecord and passes it to the processor. */
    private fun emit(data: LogRecordData) {
        val rwRecord = object : ReadWriteLogRecord {
            override fun toLogRecordData(): LogRecordData = data
            // setAttribute is required by the interface but unused in our flow
            override fun <T : Any> setAttribute(key: AttributeKey<T>, value: T): ReadWriteLogRecord =
                this
        }
        processor.onEmit(OtelContext.root(), rwRecord)
    }

    // ── Minimal in-process exporter ─────────────────────────────────────────

    /**
     * Thread-safe in-memory exporter used instead of InMemoryLogRecordExporter
     * (which is not on the androidTestImplementation classpath) or MockLogRecordExporter
     * (which lives in the testImplementation source set and uses Mockk).
     */
    private class CaptureExporter : LogRecordExporter {
        private val records: MutableList<LogRecordData> =
            Collections.synchronizedList(mutableListOf())
        private val calls = AtomicInteger(0)

        override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
            calls.incrementAndGet()
            records.addAll(logs)
            return CompletableResultCode.ofSuccess()
        }

        override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
        override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()

        fun count(): Int = records.size
        fun all(): List<LogRecordData> = records.toList()
    }
}
