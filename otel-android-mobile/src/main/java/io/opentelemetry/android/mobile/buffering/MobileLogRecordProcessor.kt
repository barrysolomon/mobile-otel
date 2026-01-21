package io.opentelemetry.android.mobile.buffering

import android.content.Context
import android.util.Log
import io.opentelemetry.android.mobile.policy.PolicyEvaluator
import io.opentelemetry.context.Context as OtelContext
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.LogRecordProcessor
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    private val ramBufferSize: Int,
    private val diskBufferMb: Int,
    private val diskBufferTtlHours: Int,
    private val collectorEndpoint: String
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
    private val policyEvaluator = PolicyEvaluator(collectorEndpoint)

    // Executor for background tasks
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(2)

    // Shutdown flag
    private val isShutdown = AtomicBoolean(false)

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

        Log.i(TAG, "Initialized: RAM buffer size=$ramBufferSize, Disk buffer=${diskBufferMb}MB, TTL=${diskBufferTtlHours}h")
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
    override fun onEmit(context: OtelContext, logRecord: LogRecordData) {
        if (isShutdown.get()) {
            Log.w(TAG, "Processor is shutdown, dropping log record")
            return
        }

        // Add to RAM buffer
        ramBuffer.offer(logRecord)
        val count = ramBufferCount.incrementAndGet()

        // Check if we need to overflow to disk
        if (count > ramBufferSize) {
            executor.submit { overflowToDisk() }
        }

        // Evaluate policies to see if we should flush
        executor.submit { evaluatePolicies(logRecord) }
    }

    /**
     * Evaluates policies against the new log record to determine if a flush is needed.
     *
     * This runs asynchronously to avoid blocking the logging thread.
     */
    private fun evaluatePolicies(logRecord: LogRecordData) {
        try {
            val matchResult = policyEvaluator.evaluate(logRecord)
            if (matchResult != null) {
                Log.i(TAG, "Policy matched: ${matchResult.policyId}, flushing window")
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
     *
     * @param windowMinutes Number of minutes to look back
     * @return CompletableResultCode indicating success/failure
     */
    private fun flushWindow(windowMinutes: Int): CompletableResultCode {
        if (isShutdown.get()) {
            return CompletableResultCode.ofFailure()
        }

        try {
            val windowStartMs = System.currentTimeMillis() - (windowMinutes * 60 * 1000L)
            val eventsToFlush = mutableListOf<LogRecordData>()

            // Collect from RAM buffer
            ramBuffer.forEach { logRecord ->
                if (logRecord.timestampEpochNanos / 1_000_000 >= windowStartMs) {
                    eventsToFlush.add(logRecord)
                }
            }

            // Collect from disk buffer
            val diskEvents = diskBuffer.getEventsInWindow(windowStartMs)
            eventsToFlush.addAll(diskEvents)

            Log.i(TAG, "Flushing ${eventsToFlush.size} events from last $windowMinutes minutes")

            // Export in batches
            val batchSize = 100
            val results = eventsToFlush.chunked(batchSize).map { batch ->
                exporter.export(batch)
            }

            // Wait for all batches to complete
            return CompletableResultCode.ofAll(results)

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
        if (isShutdown.get()) {
            return CompletableResultCode.ofFailure()
        }

        try {
            Log.i(TAG, "Force flush: exporting all buffered events")

            val allEvents = mutableListOf<LogRecordData>()

            // Collect all events from RAM
            allEvents.addAll(ramBuffer)

            // Collect all events from disk
            allEvents.addAll(diskBuffer.getAllEvents())

            Log.i(TAG, "Force flushing ${allEvents.size} events")

            // Export in batches
            val batchSize = 100
            val results = allEvents.chunked(batchSize).map { batch ->
                exporter.export(batch)
            }

            // Clear buffers after successful export
            val result = CompletableResultCode.ofAll(results)
            result.whenComplete {
                if (it.isSuccess) {
                    ramBuffer.clear()
                    ramBufferCount.set(0)
                    diskBuffer.clearAll()
                    Log.i(TAG, "Force flush completed successfully")
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

            // Force flush before shutdown
            val flushResult = forceFlush()

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
        private var ramBufferSize: Int = 5000
        private var diskBufferMb: Int = 50
        private var diskBufferTtlHours: Int = 24
        private var collectorEndpoint: String = ""

        fun setExporter(exporter: LogRecordExporter) = apply { this.exporter = exporter }
        fun setRamBufferSize(size: Int) = apply { this.ramBufferSize = size }
        fun setDiskBufferMb(sizeMb: Int) = apply { this.diskBufferMb = sizeMb }
        fun setDiskBufferTtlHours(hours: Int) = apply { this.diskBufferTtlHours = hours }
        fun setCollectorEndpoint(endpoint: String) = apply { this.collectorEndpoint = endpoint }

        fun build(): MobileLogRecordProcessor {
            return MobileLogRecordProcessor(
                context = context,
                exporter = requireNotNull(exporter) { "Exporter is required" },
                ramBufferSize = ramBufferSize,
                diskBufferMb = diskBufferMb,
                diskBufferTtlHours = diskBufferTtlHours,
                collectorEndpoint = collectorEndpoint
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
