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
import java.util.concurrent.atomic.AtomicLong
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
    private val diskBufferTtlHours: Int,
    // Used only in HYBRID mode to emit device.heartbeat log records.
    // Set after SDK init to avoid a circular dependency at construction time.
    @Volatile var heartbeatLogger: io.opentelemetry.api.logs.Logger? = null,
    // Optional hook invoked on each HYBRID heartbeat tick to run the prediction cycle
    // alongside the heartbeat.  Wired by MobileOtel after PredictiveExportPolicy is built.
    @Volatile var predictionCycleHook: (() -> Unit)? = null
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

    // Tracks the start timestamp of the most recently seen screen view event.
    // Used to extend flushWindow() backward to capture the full current-screen context.
    private val currentScreenStartMs = AtomicLong(0L)

    // Tracks the last successfully flushed window to prevent re-exporting the same
    // data when overlapping policies fire within a short window.
    private val lastFlushEndMs = AtomicLong(0L)
    private val lastFlushStartMs = AtomicLong(0L)
    private val flushCooldownMs = 10_000L  // 10 seconds

    // Guards against concurrent flushWindow() calls (e.g. ui.freeze + app.anr emitted ms apart).
    // CAS from false→true to start a flush; reset to false when the export completes/fails.
    private val flushInProgress = AtomicBoolean(false)

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

        // CONTINUOUS + HYBRID: periodic device metrics capture + full log flush on schedule.
        // HYBRID also adds policy-triggered selective flushes on top of this.
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
            Log.i(TAG, "Periodic export: metrics every ${captureIntervalSeconds}s, logs every ${flushIntervalSeconds}s (mode=${config.exportMode})")
        }

        // HYBRID: conditional export (policy-triggered) + periodic device heartbeat log.
        // The heartbeat is a lightweight device.heartbeat log record emitted on the
        // predictionIntervalSeconds schedule. It flows through onEmit() → policy evaluation,
        // so it can also trigger a flush if a policy matches (e.g., low battery policy).
        // No periodic forceFlush — bulk data only exports when a policy fires.
        if (config.exportMode == io.opentelemetry.android.mobile.config.ExportMode.HYBRID) {
            val heartbeatIntervalSeconds = config.predictionIntervalSeconds
            executor.scheduleAtFixedRate(
                {
                    if (!isShutdown.get()) {
                        try {
                            emitHeartbeat()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error emitting heartbeat", e)
                        }
                    }
                },
                heartbeatIntervalSeconds,
                heartbeatIntervalSeconds,
                TimeUnit.SECONDS
            )
            Log.i(TAG, "HYBRID: conditional export + heartbeat every ${heartbeatIntervalSeconds}s")
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

        val body = logRecordData.body.asString()

        // HYBRID selective immediate export: heartbeat and prediction logs are the
        // lightweight periodic signal — export them directly without buffering so the
        // backend always receives a live device-health stream.  They are NOT added to
        // the ring buffer (no queue entry to remove later).
        if (config.exportMode == io.opentelemetry.android.mobile.config.ExportMode.HYBRID &&
            (body == "device.heartbeat" || body == "prediction.cycle" || body == "prediction.high_risk_alert")) {
            executor.submit {
                try {
                    exporter.export(listOf(logRecordData))
                    Log.d(TAG, "HYBRID: immediately exported $body")
                } catch (e: Exception) {
                    Log.e(TAG, "HYBRID: failed to export $body", e)
                }
            }
            return
        }

        // Add to RAM buffer
        ramBuffer.offer(logRecordData)
        val count = ramBufferCount.incrementAndGet()

        // Track screen start for window extension
        if (body == "screen.view") {
            currentScreenStartMs.set(logRecordData.timestampEpochNanos / 1_000_000)
        }

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
     * Emits a lightweight device.heartbeat log record for HYBRID mode.
     *
     * The heartbeat carries a snapshot of current device health (battery, memory, network,
     * buffer occupancy) as log attributes. It is injected directly into the SDK logger and
     * **immediately exported** by [onEmit] without entering the ring buffer — it is the
     * lightweight continuous signal in HYBRID mode.  The `prediction.cycle` log emitted by
     * [predictionCycleHook] on the same tick is also immediately exported.
     * Bulk event buffer data is only flushed when a policy trigger fires.
     *
     * Heartbeat attributes:
     * - `device.battery_percent`  — 0-100, or -1 when unavailable
     * - `device.memory_available_mb` — free RAM in MB
     * - `buffer.ram_events`       — current RAM buffer occupancy
     * - `buffer.disk_events`      — current disk buffer occupancy
     * - `network.type`            — wifi / cellular / none / unknown
     */
    private fun emitHeartbeat() {
        val logger = heartbeatLogger ?: run {
            Log.w(TAG, "HYBRID heartbeat skipped: heartbeatLogger not yet set")
            return
        }

        val battery  = deviceMetricsCollector.getBatteryLevel()
        val memoryMb = deviceMetricsCollector.getAvailableMemoryMb()
        val ram      = ramBufferCount.get()
        val disk     = diskBuffer.getEventCount()

        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager
        val networkType = if (cm != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val net  = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            when {
                caps == null -> "none"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)     -> "wifi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        } else "unknown"

        logger.logRecordBuilder()
            .setBody("device.heartbeat")
            .setSeverity(io.opentelemetry.api.logs.Severity.INFO)
            .setAllAttributes(
                io.opentelemetry.api.common.Attributes.builder()
                    .put(io.opentelemetry.api.common.AttributeKey.longKey("device.battery_percent"),    battery.toLong())
                    .put(io.opentelemetry.api.common.AttributeKey.longKey("device.memory_available_mb"), memoryMb)
                    .put(io.opentelemetry.api.common.AttributeKey.longKey("buffer.ram_events"),         ram.toLong())
                    .put(io.opentelemetry.api.common.AttributeKey.longKey("buffer.disk_events"),        disk.toLong())
                    .put(io.opentelemetry.api.common.AttributeKey.stringKey("network.type"),            networkType)
                    .build()
            )
            .emit()

        Log.d(TAG, "HYBRID heartbeat: battery=${battery}%, mem=${memoryMb}MB, buf=$ram+$disk, net=$networkType")

        // Run prediction cycle on the same tick so prediction.cycle and device.heartbeat
        // are co-emitted, sharing a single periodic timer in HYBRID mode.
        predictionCycleHook?.invoke()
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
                Log.i(TAG, "Policy matched: ${matchResult.policyId}")

                deviceMetricsCollector.captureMetrics(when (matchResult.policyId) {
                    "ui-freeze-detector" -> CaptureReason.WORKFLOW_TRIGGER
                    "crash-recovery" -> CaptureReason.CRASH
                    "app-foreground" -> CaptureReason.APP_START
                    else -> CaptureReason.WORKFLOW_TRIGGER
                })

                // Prefer trace-correlated flush for precise context; fall back to window
                if (logRecord.spanContext.isValid) {
                    flushByTraceId(logRecord.spanContext.traceId, matchResult.flushWindowMinutes)
                } else {
                    flushWindow(matchResult.flushWindowMinutes)
                }
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

        // Reject concurrent flush attempts — e.g. ui.freeze and app.anr emitted ms apart both
        // trigger evaluatePolicies() and would otherwise export the same events twice.
        if (!flushInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "flushWindow: skipped — flush already in progress")
            return CompletableResultCode.ofSuccess()
        }

        try {
            val now = System.currentTimeMillis()
            val lastEnd = lastFlushEndMs.get()
            val lastStart = lastFlushStartMs.get()

            // Suppress if the proposed window overlaps with the last flush AND the last
            // flush completed within the cooldown period.
            if (lastEnd > 0 && (now - lastEnd) < flushCooldownMs) {
                val proposedStart = now - (windowMinutes * 60 * 1000L)
                if (proposedStart < lastEnd && now > lastStart) {
                    Log.d(TAG, "flushWindow: suppressed duplicate flush — last flush ended ${now - lastEnd}ms ago and window overlaps")
                    flushInProgress.set(false)
                    return CompletableResultCode.ofSuccess()
                }
            }

            val requestedWindowStartMs = now - (windowMinutes * 60 * 1000L)

            // Extend backward to capture the full current page span, capped at 30 minutes
            val maxExtensionMs = now - (30 * 60 * 1000L)
            val screenStartMs = currentScreenStartMs.get()
            val windowStartMs = if (screenStartMs > 0 && screenStartMs < requestedWindowStartMs) {
                maxOf(screenStartMs, maxExtensionMs)
            } else {
                requestedWindowStartMs
            }

            if (windowStartMs < requestedWindowStartMs) {
                Log.d(TAG, "flushWindow: extending window from ${windowMinutes}min to include screen start at ${requestedWindowStartMs - windowStartMs}ms earlier")
            }

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

            if (allEventsToFlush.isEmpty()) {
                flushInProgress.set(false)
                return CompletableResultCode.ofSuccess()
            }

            Log.i(TAG, "Flushing ${allEventsToFlush.size} events " +
                "(${ramEventsToFlush.size} RAM + ${diskEventsToFlush.size} disk) " +
                "from last $windowMinutes minutes")

            val results = allEventsToFlush.chunked(100).map { batch -> exporter.export(batch) }
            val result = CompletableResultCode.ofAll(results)

            result.whenComplete {
                // whenComplete may fire on the exporter's completion thread (potentially the
                // main thread for synchronous exporters). Submit all I/O back to the executor
                // so we never block whichever thread delivers this callback.
                executor.submit {
                    if (result.isSuccess) {
                        try {
                            lastFlushStartMs.set(windowStartMs)
                            lastFlushEndMs.set(System.currentTimeMillis())

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
                        } finally {
                            flushInProgress.set(false)
                        }
                    } else {
                        Log.w(TAG, "Window flush failed, keeping events in buffer for retry")
                        flushInProgress.set(false)
                    }
                }
            }

            return result

        } catch (e: Exception) {
            Log.e(TAG, "Error flushing window", e)
            flushInProgress.set(false)
            return CompletableResultCode.ofFailure()
        }
    }

    /**
     * Flushes all events matching a specific trace ID from both buffer tiers.
     *
     * This is more precise than flushWindow() when the triggering event has a valid
     * SpanContext — it exports exactly the events belonging to the same trace, not
     * everything in a time window.
     *
     * Falls back to flushWindow(fallbackWindowMinutes) if traceId is blank or if the
     * trace has ≤1 event (too few to be useful without time-window context).
     *
     * @param traceId The OTel trace ID hex string to match (32 hex chars)
     * @param fallbackWindowMinutes Window to use if no trace-matched events found
     */
    fun flushByTraceId(traceId: String, fallbackWindowMinutes: Int = 5): CompletableResultCode {
        if (isShutdown.get()) return CompletableResultCode.ofFailure()
        if (traceId.isBlank()) return flushWindow(fallbackWindowMinutes)

        try {
            // Collect RAM events matching traceId
            val ramEventsToFlush = mutableListOf<LogRecordData>()
            ramBuffer.forEach { logRecord ->
                if (logRecord.spanContext.isValid && logRecord.spanContext.traceId == traceId) {
                    ramEventsToFlush.add(logRecord)
                }
            }

            val diskEventsToFlush = runBlocking { diskBuffer.getEventsByTraceId(traceId) }
            val allEventsToFlush = ramEventsToFlush + diskEventsToFlush

            // If trace-matched events are very few (e.g., only the trigger event itself),
            // fall back to time-window for fuller context
            if (allEventsToFlush.size <= 1) {
                Log.d(TAG, "flushByTraceId: only ${allEventsToFlush.size} events for trace $traceId, falling back to flushWindow($fallbackWindowMinutes)")
                return flushWindow(fallbackWindowMinutes)
            }

            Log.i(TAG, "flushByTraceId: exporting ${allEventsToFlush.size} events for trace $traceId (${ramEventsToFlush.size} RAM + ${diskEventsToFlush.size} disk)")

            val results = allEventsToFlush.chunked(100).map { batch -> exporter.export(batch) }
            val result = CompletableResultCode.ofAll(results)

            result.whenComplete {
                executor.submit {
                    if (result.isSuccess) {
                        val exportedIds = Collections.newSetFromMap(IdentityHashMap<LogRecordData, Boolean>())
                        exportedIds.addAll(ramEventsToFlush)
                        var removed = 0
                        ramBuffer.removeIf { event ->
                            exportedIds.contains(event).also { matched -> if (matched) removed++ }
                        }
                        ramBufferCount.addAndGet(-removed)
                        runBlocking { diskBuffer.deleteEventsByTraceId(traceId) }
                        Log.i(TAG, "flushByTraceId: cleared $removed RAM + ${diskEventsToFlush.size} disk events for trace $traceId")
                    } else {
                        Log.w(TAG, "flushByTraceId failed, keeping events in buffer for retry")
                    }
                }
            }

            return result
        } catch (e: Exception) {
            Log.e(TAG, "Error in flushByTraceId", e)
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
                executor.submit {
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
