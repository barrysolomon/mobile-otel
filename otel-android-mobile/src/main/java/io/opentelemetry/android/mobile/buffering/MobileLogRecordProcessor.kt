/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import android.content.Context
import android.util.Log
import io.opentelemetry.android.mobile.policy.PolicyEvaluator
import io.opentelemetry.android.mobile.metrics.DeviceMetricsCollector
import io.opentelemetry.android.mobile.metrics.CaptureReason
import io.opentelemetry.context.Context as OtelContext
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.LogRecordProcessor
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.ReadWriteLogRecord
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

/**
 * Mobile-optimized LogRecordProcessor with two-tier ring buffer and conditional export.
 *
 * This processor implements a sophisticated buffering strategy for mobile environments:
 *
 * **Architecture:**
 * ```
 * Events → RAM Buffer (fast, volatile) → Disk Buffer (persistent) → OTLP Export
 *          ↑ 5000 events                  ↑ 50MB, 24h TTL
 * ```
 *
 * **Buffering Strategy:**
 * 1. **RAM Buffer**: Fast ConcurrentLinkedQueue with bounded size (default: 5000 events)
 *    - New events always go to RAM first
 *    - Oldest events overflow to disk when RAM buffer is full
 *    - Events stay in RAM until flushed or overflowed
 *
 * 2. **Disk Buffer**: Room-based persistent storage (default: 50MB, 24h TTL)
 *    - Receives overflow from RAM buffer
 *    - Survives app restarts and crashes
 *    - Automatic TTL-based cleanup
 *
 * **Export Behavior:**
 * - **Conditional**: Events are exported only when workflow policies trigger
 * - **Selective**: Only relevant time windows are exported (e.g., "last 2 minutes")
 * - **Efficient**: Batch export with OTLP/gRPC
 *
 * **Thread Safety:**
 * - All operations are thread-safe
 * - Uses ConcurrentLinkedQueue for lock-free RAM buffer
 * - Room handles disk I/O thread safety
 *
 * Usage:
 * ```kotlin
 * val processor = MobileLogRecordProcessor.builder(context)
 *     .setExporter(otlpExporter)
 *     .setRamBufferSize(5000)
 *     .setDiskBufferMb(50)
 *     .build()
 * ```
 *
 * @see DiskLogBuffer for disk persistence implementation
 * @see PolicyEvaluator for conditional export logic
 */
class MobileLogRecordProcessor private constructor(
    private val context: Context,
    private val exporter: LogRecordExporter,
    private val config: io.opentelemetry.android.mobile.config.MobileConfig,
    private val meter: io.opentelemetry.api.metrics.Meter,
    private val ramBufferSize: Int,
    private val diskBufferMb: Int,
    private val diskBufferTtlHours: Int
) : LogRecordProcessor {

    private val TAG = "MobileLogRecordProcessor"

    // RAM buffer: fast, in-memory, bounded queue
    private val ramBuffer = ConcurrentLinkedQueue<LogRecordData>()
    private val ramBufferCount = AtomicInteger(0)

    // Disk buffer: persistent storage with Room
    private val diskBuffer: DiskLogBuffer = DiskLogBuffer.getInstance(
        context,
        maxSizeMb = diskBufferMb,
        ttlHours = diskBufferTtlHours
    )

    // Policy evaluator: determines when to flush
    private val policyEvaluator = PolicyEvaluator(context, config)

    // Device metrics collector: captures device health metrics on triggers
    private val deviceMetricsCollector = DeviceMetricsCollector(context, meter, config.deviceMetricsConfig)

    // Executor for background tasks
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(2)

    // Shutdown flag
    private val isShutdown = AtomicBoolean(false)

    // Ring buffer OTel gauges — held to prevent GC of async callbacks
    private val ramEventsGauge = meter.gaugeBuilder("buffer.ram.events")
        .setDescription("Current number of events in the RAM ring buffer")
        .setUnit("{events}")
        .ofLongs()
        .buildWithCallback { obs -> obs.record(ramBufferCount.get().toLong()) }
    private val ramCapacityGauge = meter.gaugeBuilder("buffer.ram.capacity")
        .setDescription("Maximum capacity of the RAM ring buffer")
        .setUnit("{events}")
        .ofLongs()
        .buildWithCallback { obs -> obs.record(ramBufferSize.toLong()) }
    private val diskEventsGauge = meter.gaugeBuilder("buffer.disk.events")
        .setDescription("Current number of events in the disk ring buffer")
        .setUnit("{events}")
        .ofLongs()
        .buildWithCallback { obs -> obs.record(diskBuffer.getEventCount().toLong()) }

    init {
        // Schedule periodic disk overflow (every 5 seconds)
        executor.scheduleAtFixedRate(
            { overflowToDisk() },
            5, 5, TimeUnit.SECONDS
        )

        // Schedule periodic cleanup (every hour)
        executor.scheduleAtFixedRate(
            { diskBuffer.cleanup() },
            1, 1, TimeUnit.HOURS
        )

        // Schedule periodic device metrics capture and log flush in CONTINUOUS and HYBRID modes.
        // HYBRID adds the same periodic export as CONTINUOUS, plus policy-triggered flush windows.
        if (config.exportMode == io.opentelemetry.android.mobile.config.ExportMode.CONTINUOUS ||
            config.exportMode == io.opentelemetry.android.mobile.config.ExportMode.HYBRID) {
            val captureIntervalSeconds = config.metricExportIntervalSeconds
            executor.scheduleAtFixedRate(
                {
                    try {
                        deviceMetricsCollector.captureMetrics(CaptureReason.SCHEDULED_FLUSH, force = true)
                        Log.d(TAG, "Periodic device metrics captured")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error capturing periodic device metrics", e)
                    }
                },
                captureIntervalSeconds,
                captureIntervalSeconds,
                TimeUnit.SECONDS
            )
            // Flush all buffered log records on the same schedule as traces
            val flushIntervalSeconds = config.traceExportIntervalSeconds
            executor.scheduleAtFixedRate(
                {
                    if (!isShutdown.get()) {
                        try {
                            val count = ramBufferCount.get()
                            if (count > 0) {
                                Log.d(TAG, "Periodic log flush: exporting $count buffered events")
                                forceFlush()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in periodic log flush", e)
                        }
                    }
                },
                flushIntervalSeconds,
                flushIntervalSeconds,
                TimeUnit.SECONDS
            )
            Log.i(TAG, "Periodic export enabled: metrics every ${captureIntervalSeconds}s, logs every ${flushIntervalSeconds}s")
        }

        Log.i(TAG, "Initialized: RAM buffer size=$ramBufferSize, Disk buffer=${diskBufferMb}MB, TTL=${diskBufferTtlHours}h, Export mode=${config.exportMode}")
    }

    /**
     * Called when a log record is emitted.
     *
     * This is called synchronously on the logging thread, so it must be fast.
     * The log record is added to the RAM buffer and returned immediately.
     *
     * @param context OTEL context (not Android Context)
     * @param logRecord The log record to process
     */
    override fun onEmit(context: OtelContext, logRecord: ReadWriteLogRecord) {
        if (isShutdown.get()) {
            Log.w(TAG, "Processor is shutdown, dropping log record")
            return
        }

        // Convert to LogRecordData for processing
        val logRecordData = logRecord.toLogRecordData()

        // Add to RAM buffer
        ramBuffer.offer(logRecordData)
        val count = ramBufferCount.incrementAndGet()

        // Check if we need to overflow to disk
        if (count > ramBufferSize) {
            executor.submit { overflowToDisk() }
        }

        // Evaluate export policies only in modes where policy triggers drive export.
        // CONDITIONAL: policy match is the only export path — always evaluate.
        // HYBRID:      policy match supplements the periodic export — always evaluate.
        // CONTINUOUS:  periodic flush is the only export path — policy evaluation is
        //              not needed and would cause spurious out-of-schedule exports.
        if (config.exportMode == io.opentelemetry.android.mobile.config.ExportMode.CONDITIONAL ||
            config.exportMode == io.opentelemetry.android.mobile.config.ExportMode.HYBRID) {
            executor.submit { evaluatePolicies(logRecordData) }
        }
    }

    /**
     * Evaluates policies against the new log record to determine if a flush is needed.
     *
     * This runs asynchronously to avoid blocking the logging thread.
     * When a policy matches, it also captures device metrics for debugging context.
     */
    private fun evaluatePolicies(logRecord: LogRecordData) {
        try {
            val matchResult = policyEvaluator.evaluate(logRecord)
            if (matchResult != null) {
                Log.i(TAG, "Policy matched: ${matchResult.policyId}, capturing device metrics and flushing window")

                // Capture device metrics on policy match for debugging context
                val captureReason = when (matchResult.policyId) {
                    "ui-freeze-detector" -> CaptureReason.WORKFLOW_TRIGGER
                    "crash-recovery" -> CaptureReason.CRASH
                    "app-foreground" -> CaptureReason.APP_START
                    else -> CaptureReason.WORKFLOW_TRIGGER
                }
                deviceMetricsCollector.captureMetrics(captureReason)

                // Flush the time window
                flushWindow(matchResult.flushWindowMinutes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error evaluating policies", e)
        }
    }

    /**
     * Flushes a time window of events (e.g., "last 2 minutes").
     *
     * This implements selective flushing by:
     * 1. Collecting events from RAM buffer within the time window
     * 2. Collecting events from disk buffer within the time window
     * 3. Exporting all collected events via OTLP
     * 4. Removing successfully exported events from both buffers
     *
     * @param windowMinutes Number of minutes to look back
     * @return CompletableResultCode indicating success/failure
     */
    fun flushWindow(windowMinutes: Int): CompletableResultCode {
        if (isShutdown.get()) {
            return CompletableResultCode.ofFailure()
        }

        if (windowMinutes <= 0) {
            return CompletableResultCode.ofSuccess()
        }

        try {
            val windowStartMs = System.currentTimeMillis() - (windowMinutes * 60 * 1000L)

            // Snapshot the RAM events to export. Track by object identity so we can remove
            // exactly these objects later without touching events that arrived during export.
            val ramEventsToFlush = mutableListOf<LogRecordData>()
            ramBuffer.forEach { logRecord ->
                if (logRecord.timestampEpochNanos / 1_000_000 >= windowStartMs) {
                    ramEventsToFlush.add(logRecord)
                }
            }

            val diskEventsToFlush = runBlocking { diskBuffer.getEventsInWindow(windowStartMs) }
            val allEventsToFlush = ramEventsToFlush + diskEventsToFlush

            if (allEventsToFlush.isEmpty()) return CompletableResultCode.ofSuccess()

            Log.i(TAG, "Flushing ${allEventsToFlush.size} events " +
                "(${ramEventsToFlush.size} RAM + ${diskEventsToFlush.size} disk) " +
                "from last $windowMinutes minutes")

            val results = allEventsToFlush.chunked(100).map { batch -> exporter.export(batch) }
            val result = CompletableResultCode.ofAll(results)

            result.whenComplete {
                if (result.isSuccess) {
                    try {
                        // Remove ONLY the exact exported objects from the RAM buffer.
                        // Using an identity set avoids the clear()+addAll() race condition
                        // that would silently drop events written during the export window.
                        val exportedIds = Collections.newSetFromMap(
                            IdentityHashMap<LogRecordData, Boolean>()
                        )
                        exportedIds.addAll(ramEventsToFlush)

                        var removed = 0
                        ramBuffer.removeIf { event ->
                            exportedIds.contains(event).also { matched ->
                                if (matched) removed++
                            }
                        }
                        ramBufferCount.addAndGet(-removed)

                        // Remove the disk window — disk events don't have the same race risk
                        // because DiskLogBuffer uses Room transactions for atomicity.
                        runBlocking { diskBuffer.deleteEventsInWindow(windowStartMs) }

                        Log.i(TAG, "Cleared $removed RAM + ${diskEventsToFlush.size} disk events after successful flush")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error clearing events after successful flush", e)
                    }
                } else {
                    Log.w(TAG, "Window flush failed, keeping events in buffer for retry")
                }
            }

            return result

        } catch (e: Exception) {
            Log.e(TAG, "Error flushing window", e)
            return CompletableResultCode.ofFailure()
        }
    }

    /**
     * Overflows old events from RAM buffer to disk buffer.
     *
     * This maintains the bounded RAM buffer by moving the oldest events to disk
     * when the RAM buffer exceeds its size limit.
     */
    private fun overflowToDisk() {
        if (isShutdown.get()) return

        try {
            val currentCount = ramBufferCount.get()
            if (currentCount <= ramBufferSize) {
                return // No overflow needed
            }

            val overflowCount = currentCount - ramBufferSize
            val eventsToMove = mutableListOf<LogRecordData>()

            // Remove oldest events from RAM (FIFO)
            repeat(overflowCount) {
                ramBuffer.poll()?.let { eventsToMove.add(it) }
            }

            // Persist to disk
            if (eventsToMove.isNotEmpty()) {
                diskBuffer.persistEvents(eventsToMove)
                ramBufferCount.addAndGet(-eventsToMove.size)
                Log.d(TAG, "Overflowed ${eventsToMove.size} events to disk")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error overflowing to disk", e)
        }
    }

    /**
     * Forces an immediate flush of all buffered events.
     *
     * This exports ALL events from both RAM and disk buffers, regardless of policies.
     * Used for critical scenarios like app shutdown or user-triggered flush.
     *
     * @return CompletableResultCode indicating success/failure
     */
    override fun forceFlush(): CompletableResultCode {
        try {
            Log.i(TAG, "Force flush: exporting all buffered events")

            // Snapshot the RAM buffer before exporting. New events that arrive during the
            // export must not be lost — we remove only the exact snapshotted objects via
            // identity equality, the same pattern used in flushWindow().
            val ramSnapshot = ramBuffer.toList()

            val diskEvents = runBlocking { diskBuffer.getAllEvents() }
            val allEvents = ramSnapshot + diskEvents

            Log.i(TAG, "Force flushing ${allEvents.size} events (${ramSnapshot.size} RAM + ${diskEvents.size} disk)")

            if (allEvents.isEmpty()) return CompletableResultCode.ofSuccess()

            val results = allEvents.chunked(100).map { batch -> exporter.export(batch) }
            val result = CompletableResultCode.ofAll(results)

            result.whenComplete {
                if (result.isSuccess) {
                    // Remove exactly the snapshotted RAM events by object identity.
                    // Events that arrived after the snapshot was taken are preserved.
                    val exportedIds = Collections.newSetFromMap(
                        IdentityHashMap<LogRecordData, Boolean>()
                    )
                    exportedIds.addAll(ramSnapshot)
                    var removed = 0
                    ramBuffer.removeIf { event ->
                        exportedIds.contains(event).also { matched -> if (matched) removed++ }
                    }
                    ramBufferCount.addAndGet(-removed)
                    runBlocking { diskBuffer.clearAll() }
                    Log.i(TAG, "Force flush completed: removed $removed RAM + ${diskEvents.size} disk events")
                } else {
                    Log.w(TAG, "Force flush failed, keeping events in buffer")
                }
            }

            return result

        } catch (e: Exception) {
            Log.e(TAG, "Error during force flush", e)
            return CompletableResultCode.ofFailure()
        }
    }

    /**
     * Shuts down the processor and releases resources.
     *
     * This performs a final flush and shuts down the executor.
     * After shutdown, the processor cannot be used.
     *
     * @return CompletableResultCode indicating success/failure
     */
    override fun shutdown(): CompletableResultCode {
        if (isShutdown.getAndSet(true)) {
            return CompletableResultCode.ofSuccess()
        }

        try {
            Log.i(TAG, "Shutting down processor")

            // Export and clear the RAM buffer only. Disk events are intentionally preserved
            // for crash-recovery: the next process start can find and re-export them.
            val ramEvents = ramBuffer.toList()
            ramBuffer.clear()
            ramBufferCount.set(0)

            val flushResult = if (ramEvents.isNotEmpty()) {
                val batchSize = 100
                val results = ramEvents.chunked(batchSize).map { batch -> exporter.export(batch) }
                CompletableResultCode.ofAll(results)
            } else {
                CompletableResultCode.ofSuccess()
            }

            // Shutdown executor
            executor.shutdown()
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }

            // Shutdown exporter
            val exporterResult = exporter.shutdown()

            return CompletableResultCode.ofAll(listOf(flushResult, exporterResult))

        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown", e)
            return CompletableResultCode.ofFailure()
        }
    }

    /**
     * Gets the current buffer statistics for monitoring.
     */
    fun getBufferStats(): BufferStats {
        return BufferStats(
            ramBufferSize = ramBufferCount.get(),
            diskBufferSize = diskBuffer.getEventCount(),
            ramBufferCapacity = ramBufferSize,
            diskBufferCapacityMb = diskBufferMb
        )
    }

    /**
     * Statistics about current buffer state.
     */
    data class BufferStats(
        val ramBufferSize: Int,
        val diskBufferSize: Int,
        val ramBufferCapacity: Int,
        val diskBufferCapacityMb: Int
    )

    /**
     * Builder for MobileLogRecordProcessor.
     */
    class Builder(private val context: Context) {
        private var exporter: LogRecordExporter? = null
        private var config: io.opentelemetry.android.mobile.config.MobileConfig? = null
        private var meter: io.opentelemetry.api.metrics.Meter? = null
        private var ramBufferSize: Int = 5000
        private var diskBufferMb: Int = 50
        private var diskBufferTtlHours: Int = 24

        fun setExporter(exporter: LogRecordExporter) = apply { this.exporter = exporter }
        fun setConfig(config: io.opentelemetry.android.mobile.config.MobileConfig) = apply { this.config = config }
        fun setMeter(meter: io.opentelemetry.api.metrics.Meter) = apply { this.meter = meter }
        fun setRamBufferSize(size: Int) = apply { this.ramBufferSize = size }
        fun setDiskBufferMb(sizeMb: Int) = apply { this.diskBufferMb = sizeMb }
        fun setDiskBufferTtlHours(hours: Int) = apply { this.diskBufferTtlHours = hours }

        fun build(): MobileLogRecordProcessor {
            return MobileLogRecordProcessor(
                context = context,
                exporter = requireNotNull(exporter) { "Exporter is required" },
                config = requireNotNull(config) { "Config is required" },
                meter = requireNotNull(meter) { "Meter is required" },
                ramBufferSize = ramBufferSize,
                diskBufferMb = diskBufferMb,
                diskBufferTtlHours = diskBufferTtlHours
            )
        }
    }

    companion object {
        /**
         * Creates a new builder for MobileLogRecordProcessor.
         */
        fun builder(context: Context): Builder = Builder(context)
    }
}
