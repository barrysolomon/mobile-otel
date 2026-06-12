/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.opentelemetry.android.mobile.core.BootTracker
import io.opentelemetry.android.mobile.instrumentation.Incubating
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
import io.opentelemetry.android.mobile.config.dropsAll
import io.opentelemetry.android.mobile.config.minBufferSeverity
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
internal class MobileLogRecordProcessor private constructor(
    private val context: Context,
    private val exporter: LogRecordExporter,
    private val config: io.opentelemetry.android.mobile.config.MobileConfig,
    private val meter: io.opentelemetry.api.metrics.Meter,
    private val ramBufferSize: Int,
    // RAM byte caps (SDK_SAFETY non-negotiable #3, iOS parity). maxTotalBytes is
    // the cumulative-size budget; maxEventBytes is the per-event drop threshold.
    private val ramBufferMaxTotalBytes: Long,
    private val ramBufferMaxEventBytes: Int,
    private val diskBufferMb: Int,
    private val diskBufferTtlHours: Int,
    // Used only in HYBRID mode to emit device.heartbeat log records.
    // Set after SDK init to avoid a circular dependency at construction time.
    @Volatile var heartbeatLogger: io.opentelemetry.api.logs.Logger? = null,
    // Optional hook invoked on each HYBRID heartbeat tick to run the prediction cycle
    // alongside the heartbeat.  Wired by MobileOtel after PredictiveExportPolicy is built.
    @Volatile var predictionCycleHook: (() -> Unit)? = null,
    // Optional hook invoked after every policy-triggered log flush to co-export buffered spans.
    // Wired by MobileLoggerProvider so HYBRID mode flushes spans and logs together on a trigger.
    @Volatile var spanFlushHook: (() -> Unit)? = null,
    // Optional hook invoked once per policy match with the policy id (e.g. "crash-recovery").
    // Wired by OTelMobile to capture a screenshot + wireframe for journey-replay context
    // alongside every flush trigger. Best-effort — must not throw; silent no-op if the
    // screenshot/wireframe modules aren't installed.
    @Volatile var policyMatchHook: ((policyId: String) -> Unit)? = null,
    // Shared remote kill-switch / global-sampling gate. The same instance is wired into
    // the [PolicyEvaluator] (which updates it from fetched config) and the span sampler,
    // so logs and spans are gated coherently. Defaults to an open gate so call sites that
    // don't supply one behave exactly as before (enabled, full rate).
    private val remoteGate: io.opentelemetry.android.mobile.policy.RemoteGate =
        io.opentelemetry.android.mobile.policy.RemoteGate()
) : LogRecordProcessor {

    private val TAG = "MobileLogRecordProcessor"

    // RAM buffer: fast, in-memory, bounded queue (wrapped with monotonic timestamp)
    private val ramBuffer = ConcurrentLinkedQueue<BufferedEvent>()
    private val ramBufferCount = AtomicInteger(0)

    // Cumulative estimated byte size of all events currently in [ramBuffer].
    // Kept in lock-step with adds/removes so the total-byte budget is O(1) to
    // check. May drift by a tiny amount under concurrent removal races, which
    // is harmless — it is a soft budget, re-floored to >=0 on every read.
    // SDK_SAFETY.md non-negotiable #3: the RAM tier must be byte-bounded, not just
    // count-bounded.
    private val ramBufferBytes = AtomicLong(0)

    // Count of events dropped because their estimated size exceeded
    // [ramBufferMaxEventBytes]. iOS parity with RAMEventBuffer.droppedOversizeCount.
    private val droppedOversizeCount = AtomicLong(0)

    // Disk buffer: persistent storage with Room. LAZY — opening Room (and
    // loading the SQLCipher native lib) costs ~100 ms, far over the HS-001
    // main-thread init budget. Every disk-buffer consumer already runs on a
    // background thread (overflow/mirror/flush executors, gauge collection,
    // the crash handler), so first-use construction never lands on main. The
    // warm-up task scheduled in init opens it almost immediately anyway.
    private val diskBuffer: DiskLogBuffer by lazy {
        DiskLogBuffer.getInstance(
            context,
            maxSizeMb = diskBufferMb,
            ttlHours = diskBufferTtlHours,
            encryptAtRest = config.encryptDiskBufferAtRest
        )
    }

    // Policy evaluator: determines when to flush. Shares the remote gate so the `sdk`
    // block fetched alongside flush policies updates the same kill switch the choke
    // points read.
    // LAZY: constructing the evaluator pulls in OkHttp (~25 ms of class
    // loading) and immediately fires an async config poll — neither belongs
    // on the main thread during init (HS-001). Both consumers (evaluate on
    // the policy executor, shutdown) run off-main; the warm-up task in init
    // builds it almost immediately, so the first config poll is delayed by
    // only a few ms.
    private val policyEvaluatorLazy = lazy { PolicyEvaluator(context, config, remoteGate = remoteGate) }
    private val policyEvaluator by policyEvaluatorLazy

    // Device metrics collector: captures device health metrics on triggers
    private val deviceMetricsCollector = DeviceMetricsCollector(context, meter, config.deviceMetricsConfig)

    // Executor for background tasks
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(2)

    private val isShutdown = AtomicBoolean(false)

    // Tracks the monotonic start time of the most recently seen screen view event.
    // Used to extend flushWindow() backward to capture the full current-screen context.
    private val currentScreenStartMonoMs = AtomicLong(0L)

    // Tracks the last successfully flushed window (monotonic time) to prevent
    // re-exporting the same data when overlapping policies fire within a short window.
    private val lastFlushEndMonoMs = AtomicLong(0L)
    private val lastFlushStartMonoMs = AtomicLong(0L)
    private val flushCooldownMs = 10_000L  // 10 seconds

    // Guards against concurrent flushWindow() calls (e.g. ui.freeze + app.anr emitted ms apart).
    // CAS from false→true to start a flush; reset to false when the export completes/fails.
    private val flushInProgress = AtomicBoolean(false)

    // Completion of the flush currently holding [flushInProgress]. A forceFlush() that
    // defers to an in-progress flush returns THIS instead of an instant fake success, so
    // callers observe real settlement (and a failed flush reports failure, enabling retry).
    private val activeFlushResult = java.util.concurrent.atomic.AtomicReference<CompletableResultCode?>(null)

    // Makes the RAM→disk eviction move atomic with respect to flush snapshots. Without it,
    // overflowToDisk() polls events out of RAM and persists them asynchronously — during
    // that window the events are visible in NEITHER tier, so a concurrent flush snapshot
    // silently under-exports them (observed: 245 of 500 burst events exported).
    private val bufferMoveLock = Any()

    // NF-003: Holds the active network-restored listener so [shutdown] can detach it.
    // Null when no watcher is attached. Only one attachment at a time — re-attaching swaps.
    @Volatile
    private var networkWatcher: io.opentelemetry.android.mobile.network.NetworkAvailabilityWatcher? = null
    @Volatile
    private var networkListener: io.opentelemetry.android.mobile.network.NetworkAvailabilityWatcher.Listener? = null

    // Error coalescer for grouping identical errors (Phase 3 of Offline Flush Budget)
    private val errorCoalescer = ErrorCoalescer()

    // Tracks RAM events that have already been mirrored to disk for crash safety.
    // Uses object identity so we only persist each event once, avoiding disk duplicates.
    private val persistedToDisk: MutableSet<BufferedEvent> = Collections.newSetFromMap(
        IdentityHashMap()
    )

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
    private val ramBytesGauge = meter.gaugeBuilder("buffer.ram.bytes")
        .setDescription("Estimated cumulative bytes held in the RAM ring buffer")
        .setUnit("By")
        .ofLongs()
        .buildWithCallback { obs -> obs.record(maxOf(0L, ramBufferBytes.get())) }
    private val ramDroppedOversizeGauge = meter.gaugeBuilder("buffer.ram.dropped_oversize")
        .setDescription("Events dropped because they exceeded the per-event byte cap")
        .setUnit("{events}")
        .ofLongs()
        .buildWithCallback { obs -> obs.record(droppedOversizeCount.get()) }

    // Remote kill-switch state gauges. These are deliberately exempt from the gate: an
    // operator must be able to observe a remotely-disabled SDK, so they continue to emit
    // even while `enabled = false`. This is the only telemetry that flows when disabled.
    private val sdkEnabledGauge = meter.gaugeBuilder("sdk.enabled")
        .setDescription("Whether the SDK is remotely enabled (1) or disabled (0)")
        .setUnit("{state}")
        .ofLongs()
        .buildWithCallback { obs -> obs.record(if (remoteGate.enabled) 1L else 0L) }
    private val sdkSampleRateGauge = meter.gaugeBuilder("sdk.sample_rate")
        .setDescription("Currently-applied global head-sampling rate (0.0 to 1.0)")
        .setUnit("1")
        .buildWithCallback { obs -> obs.record(remoteGate.sampleRate) }

    init {
        // SeqId uniqueness across process generations — WITHOUT the synchronous
        // disk read this used to do (Room open ≈ 100 ms on main; HS-001).
        // Wall-clock seeding guarantees this process's seqIds exceed any prior
        // process's: old ids derive from the OLD process's start millis plus its
        // event count, both strictly in the past. So crash-mirrored disk rows
        // can never collide with new RAM events in flush dedup (the silent-drop
        // bug class fixed in #38). The warm-up task below re-raises from the
        // true disk max as belt-and-braces for a device clock that rolled
        // backwards across the restart.
        BufferedEvent.raiseCounterTo(System.currentTimeMillis())
        executor.execute {
            try {
                val maxDiskSeqId = diskBuffer.getMaxSeqId() // forces the Room open, off-main
                if (maxDiskSeqId > 0) {
                    BufferedEvent.raiseCounterTo(maxDiskSeqId)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Disk buffer warm-up failed; will retry on first disk use", t)
            }
            try {
                policyEvaluator // build + start the config poller, off-main
            } catch (t: Throwable) {
                Log.w(TAG, "PolicyEvaluator warm-up failed; will retry on first evaluation", t)
            }
        }

        // Schedule periodic disk overflow (every 5 seconds)
        executor.scheduleAtFixedRate(
            { overflowToDisk() },
            5, 5, TimeUnit.SECONDS
        )

        // Crash-safe mirror: periodically copy all RAM events to disk so they survive
        // a force-kill. RAM stays intact (events are not removed here); disk copies are
        // deduplicated by the Room IGNORE conflict strategy on the unique timestamp index.
        executor.scheduleAtFixedRate(
            { persistRamToDiskForCrashSafety() },
            2, 2, TimeUnit.SECONDS
        )

        // Schedule periodic cleanup (every hour)
        executor.scheduleAtFixedRate(
            { diskBuffer.cleanup() },
            1, 1, TimeUnit.HOURS
        )

        // CONTINUOUS: periodic full flush + periodic device metrics.
        // HYBRID: periodic device metrics ONLY — no periodic forceFlush.
        //   Bulk events in HYBRID are only exported when a policy trigger fires (flushWindow).
        //   forceFlush() here would dump the entire buffer on a timer, defeating selective export.
        if (config.exportMode == io.opentelemetry.android.mobile.config.ExportMode.CONTINUOUS) {
            val captureIntervalSeconds = config.metricExportIntervalSeconds
            executor.scheduleAtFixedRate(
                {
                    try {
                        deviceMetricsCollector.captureMetrics(CaptureReason.SCHEDULED_FLUSH, force = true)
                        Log.d(TAG, "CONTINUOUS: periodic device metrics captured")
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
                                Log.d(TAG, "CONTINUOUS: periodic log flush, exporting $count buffered events")
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
            Log.i(TAG, "CONTINUOUS: metrics every ${captureIntervalSeconds}s, logs every ${flushIntervalSeconds}s")
        }

        // HYBRID: periodic device metrics only — no bulk log flush.
        // Heartbeat + prediction logs are emitted and immediately forwarded by emitHeartbeat().
        // Bulk events only export when a policy trigger calls flushWindow().
        if (config.exportMode == io.opentelemetry.android.mobile.config.ExportMode.HYBRID) {
            val captureIntervalSeconds = config.metricExportIntervalSeconds * 2L
            executor.scheduleAtFixedRate(
                {
                    try {
                        deviceMetricsCollector.captureMetrics(CaptureReason.SCHEDULED_FLUSH, force = true)
                        Log.d(TAG, "HYBRID: periodic device metrics captured")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error capturing HYBRID device metrics", e)
                    }
                },
                captureIntervalSeconds,
                captureIntervalSeconds,
                TimeUnit.SECONDS
            )
            Log.i(TAG, "HYBRID: device metrics every ${captureIntervalSeconds}s (no periodic bulk flush)")
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

        // Remote kill switch / global sampling — consulted BEFORE any buffering,
        // coalescing, or attribute work so a disabled SDK does no per-event work.
        // `enabled = false` drops everything; `sampleRate < 1.0` drops probabilistically
        // via a non-biased per-thread RNG. Covers RN-originated logs transitively, since
        // the bridge emits through this same processor (see remote-kill-switch.md §6).
        if (!remoteGate.allowEvent()) {
            return
        }

        // Stamp caller-supplied attributes (e.g., dash0.test.cell_id) onto every
        // record. Dash0 ingestion drops unknown Resource-level attributes
        // (verified 2026-05-05), so we attach them per-record instead. The DSL
        // surface keeps the historical `extraResourceAttributes` name; the
        // semantics are now per-record.
        config.extraResourceAttributes?.forEach { (key, value) ->
            if (key.isNotBlank()) {
                logRecord.setAttribute(
                    io.opentelemetry.api.common.AttributeKey.stringKey(key), value
                )
            }
        }

        // Convert to LogRecordData for processing
        val logRecordData = logRecord.toLogRecordData().ensureTimestamp()

        val body = logRecordData.body.asString()

        // Offline policy filtering: when offline and policy is not BUFFER_ALL,
        // drop events below the configured severity threshold.
        if (config.offlinePolicy != io.opentelemetry.android.mobile.config.OfflinePolicy.BUFFER_ALL) {
            if (isDeviceOffline()) {
                if (config.offlinePolicy.dropsAll()) {
                    Log.d(TAG, "Offline DROP_ALL: dropping $body")
                    return
                }
                val minSeverity = config.offlinePolicy.minBufferSeverity()
                if (minSeverity != null &&
                    logRecordData.severity.ordinal < minSeverity.ordinal) {
                    Log.d(TAG, "Offline ${config.offlinePolicy}: dropping $body (severity=${logRecordData.severity})")
                    return
                }
            }
        }

        // Error coalescing: suppress duplicate errors within the coalescing window
        if (errorCoalescer.tryCoalesce(logRecordData)) {
            Log.d(TAG, "Error coalesced: $body (count=${errorCoalescer.getCount(logRecordData)})")
            return
        }

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

        // Add to RAM buffer wrapped with monotonic timestamp
        val bufferedEvent = BufferedEvent(logRecordData)

        // Per-event byte cap (SDK_SAFETY non-negotiable #3, iOS parity). A
        // single oversized event (e.g. a giant screenshot/wireframe payload) is
        // dropped and counted rather than allowed to balloon RAM. We still
        // persist it to disk so the signal isn't lost entirely — disk has its
        // own size/TTL budget and is the right place for large blobs.
        if (bufferedEvent.sizeBytes > ramBufferMaxEventBytes) {
            droppedOversizeCount.incrementAndGet()
            Log.w(TAG, "Dropping oversize event from RAM (${bufferedEvent.sizeBytes}B > ${ramBufferMaxEventBytes}B): $body")
            executor.submit {
                try {
                    diskBuffer.persistBufferedEvents(listOf(bufferedEvent))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to persist oversize event to disk", e)
                }
            }
            return
        }

        ramBuffer.offer(bufferedEvent)
        val count = ramBufferCount.incrementAndGet()
        ramBufferBytes.addAndGet(bufferedEvent.sizeBytes.toLong())

        // Track screen start for window extension (monotonic time)
        if (body == "screen.view") {
            currentScreenStartMonoMs.set(bufferedEvent.monotonicMs)
        }

        // Check if we need to overflow to disk — either the count cap OR the
        // total-byte budget (whichever trips first). The byte budget is an
        // independent defense so a few large events overflow before the count
        // cap would, matching iOS RAMEventBuffer's maxTotalBytes behavior.
        if (count > ramBufferSize || ramBufferBytes.get() > ramBufferMaxTotalBytes) {
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
    @SuppressLint("MissingPermission") // Permission declared in app manifest, not library
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
                    .put(io.opentelemetry.api.common.AttributeKey.stringKey("network.connection.type"), networkType)
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
    @SuppressLint("MissingPermission") // Permission declared in app manifest, not library
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

                // Capture journey-replay artifacts (screenshot + wireframe) BEFORE the
                // flush so they land in the same flush window. Wrapped in try/catch so
                // a capture failure can't derail the flush.
                try { policyMatchHook?.invoke(matchResult.policyId) } catch (_: Throwable) {}

                // Prefer trace-correlated flush for precise context; fall back to window
                if (logRecord.spanContext.isValid) {
                    flushByTraceId(logRecord.spanContext.traceId, matchResult.flushWindowMinutes)
                } else {
                    flushWindow(matchResult.flushWindowMinutes)
                }

                // Co-flush buffered spans (HYBRID true-buffering mode: spans use 1-hour
                // BatchSpanProcessor delay and only export when a policy fires here).
                spanFlushHook?.invoke()
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
    @Incubating
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

        // Completes only after export AND buffer cleanup have settled (or on failure).
        // Stored in activeFlushResult so a deferring forceFlush() can return it.
        val flushDone = CompletableResultCode()
        activeFlushResult.set(flushDone)

        try {
            // Use monotonic time for window calculations — immune to wall-clock changes.
            // See docs/design/MONOTONIC_FLUSH_WINDOW.md for rationale.
            val monoNow = SystemClock.elapsedRealtime()
            val lastEndMono = lastFlushEndMonoMs.get()
            val lastStartMono = lastFlushStartMonoMs.get()

            // Suppress if the proposed window overlaps with the last flush AND the last
            // flush completed within the cooldown period.
            if (lastEndMono > 0 && (monoNow - lastEndMono) < flushCooldownMs) {
                val proposedStartMono = monoNow - (windowMinutes * 60 * 1000L)
                if (proposedStartMono < lastEndMono && monoNow > lastStartMono) {
                    Log.d(TAG, "flushWindow: suppressed duplicate flush — last flush ended ${monoNow - lastEndMono}ms ago and window overlaps")
                    flushInProgress.set(false)
                    flushDone.succeed()
                    return flushDone
                }
            }

            val monoWindowStart = monoNow - (windowMinutes * 60 * 1000L)

            // Extend backward to capture the full current page span, capped at 30 minutes
            val maxExtensionMonoMs = monoNow - (30 * 60 * 1000L)
            val screenStartMonoMs = currentScreenStartMonoMs.get()
            val effectiveMonoStart = if (screenStartMonoMs > 0 && screenStartMonoMs < monoWindowStart) {
                maxOf(screenStartMonoMs, maxExtensionMonoMs)
            } else {
                monoWindowStart
            }

            if (effectiveMonoStart < monoWindowStart) {
                Log.d(TAG, "flushWindow: extending window from ${windowMinutes}min to include screen start at ${monoWindowStart - effectiveMonoStart}ms earlier")
            }

            // Snapshot the RAM events to export using monotonic timestamps.
            // Track by object identity so we can remove exactly these after export.
            //
            // Snapshot under bufferMoveLock so an in-flight RAM→disk eviction is atomic
            // from our perspective: every event is in exactly one tier, never in limbo.
            val wallNow = System.currentTimeMillis()
            val wallWindowStart = wallNow - (windowMinutes * 60 * 1000L)
            val ramEventsToFlush = mutableListOf<BufferedEvent>()
            val allRamSeqIds = HashSet<Long>()
            val diskRows: List<DiskLogBuffer.DiskRow>
            synchronized(bufferMoveLock) {
                ramBuffer.forEach { event ->
                    if (event.monotonicMs >= effectiveMonoStart) {
                        ramEventsToFlush.add(event)
                    }
                    allRamSeqIds.add(event.seqId)
                }

                // Disk: use monotonic for same-boot events, wall-clock fallback for cross-boot.
                // Use seqId to deduplicate crash-safety mirrors that are still in RAM.
                diskRows = runBlocking {
                    diskBuffer.getEventsInWindowWithIds(
                        monoStartMs = effectiveMonoStart,
                        wallStartMs = wallWindowStart,
                        currentBootId = BootTracker.currentBootId
                    )
                }
            }
            val diskOverflowOnly = diskRows
                .filter { row -> row.seqId == 0L || row.seqId !in allRamSeqIds }
                .map { it.record }
            val allEventsToFlush = ramEventsToFlush.map { it.logRecord } + diskOverflowOnly

            // Cleanup deletes exactly the snapshotted disk rows — never rows persisted
            // after the snapshot (the old deleteEventsInWindow(start) wiped those too,
            // dropping events that arrived during the export without exporting them).
            val exportedDiskIds = diskRows.map { it.id }

            if (allEventsToFlush.isEmpty()) {
                flushInProgress.set(false)
                flushDone.succeed()
                return flushDone
            }

            Log.i(TAG, "Flushing ${allEventsToFlush.size} events " +
                "(${ramEventsToFlush.size} RAM + ${diskOverflowOnly.size} disk-only) " +
                "from last $windowMinutes minutes")

            val results = allEventsToFlush.chunked(100).map { batch -> exporter.export(batch) }
            val result = CompletableResultCode.ofAll(results)

            result.whenComplete {
                if (!result.isSuccess) {
                    // Failure: events stay buffered; there is no cleanup I/O to do. Release
                    // the gate HERE (an atomic set, safe on any thread) instead of via
                    // executor.submit — otherwise a retry races the executor and silently
                    // no-ops against a flush that is already over.
                    Log.w(TAG, "Window flush failed, keeping events in buffer for retry")
                    flushInProgress.set(false)
                    flushDone.fail()
                } else {
                    // whenComplete may fire on the exporter's completion thread (potentially
                    // the main thread for synchronous exporters). Submit cleanup I/O back to
                    // the executor so we never block whichever thread delivers this callback.
                    executor.submit {
                        try {
                            if (isShutdown.get()) {
                                Log.i(TAG, "Window flush exported but skipping buffer clear (shutdown/crash)")
                            } else {
                                lastFlushStartMonoMs.set(effectiveMonoStart)
                                lastFlushEndMonoMs.set(SystemClock.elapsedRealtime())

                                val exportedIds = Collections.newSetFromMap(
                                    IdentityHashMap<BufferedEvent, Boolean>()
                                )
                                exportedIds.addAll(ramEventsToFlush)

                                var removed = 0
                                var removedBytes = 0L
                                ramBuffer.removeIf { event ->
                                    exportedIds.contains(event).also { matched ->
                                        if (matched) { removed++; removedBytes += event.sizeBytes }
                                    }
                                }
                                ramBufferCount.addAndGet(-removed)
                                ramBufferBytes.addAndGet(-removedBytes)
                                synchronized(persistedToDisk) { persistedToDisk.removeAll(exportedIds) }

                                val deletedDisk = runBlocking {
                                    // Snapshot rows by id, PLUS any crash-mirror rows written
                                    // during the export whose RAM originals we just exported
                                    // (same seqId) — orphaned mirrors would re-export as
                                    // duplicates on the next flush.
                                    diskBuffer.deleteByIds(exportedDiskIds) +
                                        diskBuffer.deleteBySeqIds(ramEventsToFlush.map { it.seqId })
                                }

                                Log.i(TAG, "Cleared $removed RAM + $deletedDisk disk events after successful flush")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error clearing events after successful flush", e)
                        } finally {
                            flushInProgress.set(false)
                            flushDone.succeed()
                        }
                    }
                }
            }

            return result

        } catch (e: Exception) {
            Log.e(TAG, "Error flushing window", e)
            flushInProgress.set(false)
            flushDone.fail()
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
            val ramEventsToFlush = mutableListOf<BufferedEvent>()
            ramBuffer.forEach { event ->
                val sc = event.logRecord.spanContext
                if (sc.isValid && sc.traceId == traceId) {
                    ramEventsToFlush.add(event)
                }
            }

            val diskEventsToFlush = runBlocking { diskBuffer.getEventsByTraceId(traceId) }
            val allEventsToFlush = ramEventsToFlush.map { it.logRecord } + diskEventsToFlush

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
                        val exportedIds = Collections.newSetFromMap(IdentityHashMap<BufferedEvent, Boolean>())
                        exportedIds.addAll(ramEventsToFlush)
                        var removed = 0
                        var removedBytes = 0L
                        ramBuffer.removeIf { event ->
                            exportedIds.contains(event).also { matched -> if (matched) { removed++; removedBytes += event.sizeBytes } }
                        }
                        ramBufferCount.addAndGet(-removed)
                        ramBufferBytes.addAndGet(-removedBytes)
                        synchronized(persistedToDisk) { persistedToDisk.removeAll(exportedIds) }
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
     * Copies new RAM buffer events to disk for crash survivability.
     *
     * Does NOT remove events from RAM — this is a mirror write, not a move.
     * Only events not yet mirrored are written (tracked by object identity) to
     * avoid duplicate rows in the disk buffer.
     * On the next app start after a crash, disk events are re-exported and
     * then deleted, giving crash recovery even when shutdown() never ran.
     */
    private fun persistRamToDiskForCrashSafety() {
        if (isShutdown.get()) return
        val newEvents: List<BufferedEvent>
        synchronized(persistedToDisk) {
            // Prune stale entries that are no longer in the RAM buffer
            persistedToDisk.retainAll(ramBuffer.toSet())
            newEvents = ramBuffer.filter { !persistedToDisk.contains(it) }
            if (newEvents.isEmpty()) return
            persistedToDisk.addAll(newEvents)
        }
        try {
            diskBuffer.persistBufferedEvents(newEvents)
            Log.d(TAG, "Crash-mirror: persisted ${newEvents.size} new RAM events to disk")
        } catch (e: Exception) {
            Log.e(TAG, "Error in crash-safety mirror write", e)
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
            val overCount = ramBufferCount.get() > ramBufferSize
            val overBytes = ramBufferBytes.get() > ramBufferMaxTotalBytes
            if (!overCount && !overBytes) {
                return // No overflow needed (under both count cap and byte budget)
            }

            // The whole poll+persist move runs under bufferMoveLock, and the persist is
            // BLOCKING: from a flush snapshot's perspective (which takes the same lock)
            // the move is atomic — every event is visible in exactly one tier. The old
            // async persist left polled events invisible to a concurrent flush snapshot
            // (gone from RAM, not yet on disk), silently under-exporting them.
            synchronized(bufferMoveLock) {
                val eventsToMove = mutableListOf<BufferedEvent>()
                var movedBytes = 0L

                // Evict oldest events (FIFO) until BOTH caps are satisfied:
                //   - count back to <= ramBufferSize
                //   - cumulative bytes back to <= ramBufferMaxTotalBytes
                // Decrement the atomics AS WE POLL (not in one batch at the end) so
                // concurrent overflowToDisk tasks — one is submitted per over-cap
                // onEmit — see an accurate count/byte total and don't all over-evict
                // the same shared queue. Stop if the queue drains (poll returns null).
                while (ramBufferCount.get() > ramBufferSize || ramBufferBytes.get() > ramBufferMaxTotalBytes) {
                    val polled = ramBuffer.poll() ?: break
                    eventsToMove.add(polled)
                    movedBytes += polled.sizeBytes
                    ramBufferCount.decrementAndGet()
                    ramBufferBytes.addAndGet(-polled.sizeBytes.toLong())
                }

                // Persist to disk (already mirrored by crash-safety task, but persistEvents is idempotent
                // here because these are being removed from RAM — the crash-mirror set is cleaned up too)
                if (eventsToMove.isNotEmpty()) {
                    synchronized(persistedToDisk) { persistedToDisk.removeAll(eventsToMove.toSet()) }
                    diskBuffer.persistBufferedEventsBlocking(eventsToMove, enforceSize = true)
                    Log.d(TAG, "Overflowed ${eventsToMove.size} events (${movedBytes}B) to disk")
                }
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
    /**
     * NF-003: Subscribe this processor to a [io.opentelemetry.android.mobile.network.NetworkAvailabilityWatcher].
     *
     * On every LOST → AVAILABLE transition the watcher emits, the processor
     * runs [flushWindow] with [windowMinutes]. This closes the offline →
     * online gap where buffered events sat on disk after airplane mode
     * was toggled off, because nothing was waking the exporter.
     *
     * Re-attaching swaps the previous subscription. Pass `null` to detach.
     * See: `docs/epics/NETWORK_RESTORED_FLUSH_EPIC.md`.
     */
    fun attachNetworkWatcher(
        watcher: io.opentelemetry.android.mobile.network.NetworkAvailabilityWatcher?,
        windowMinutes: Int
    ) {
        // Detach any prior subscription first so the swap is atomic from the
        // caller's perspective.
        networkWatcher?.let { prior ->
            networkListener?.let(prior::removeListener)
        }
        networkListener = null
        networkWatcher = watcher

        if (watcher != null) {
            val listener = io.opentelemetry.android.mobile.network.NetworkAvailabilityWatcher.Listener { transition ->
                if (transition == io.opentelemetry.android.mobile.network.NetworkAvailabilityWatcher.Transition.Restored) {
                    Log.i(TAG, "Network restored — flushing $windowMinutes-minute window")
                    flushWindow(windowMinutes)
                }
            }
            networkListener = listener
            watcher.addListener(listener)
        }
    }

    override fun forceFlush(): CompletableResultCode {
        // Block any concurrent flushWindow() calls — forceFlush exports everything so
        // a simultaneous policy-triggered window flush would double-export the same events.
        // If a window flush is already running, wait for it to finish first (brief spin).
        // Serialize against flushWindow(). A window flush removes its RAM events only
        // AFTER its async export completes, so proceeding while one is in progress would
        // snapshot the SAME still-in-RAM events and export them a SECOND time — duplicates
        // with identical timestamps (the bug). Previously this "proceeded anyway"; instead
        // defer to the in-progress flush, which exports the current RAM buffer — our events
        // ride along or flush on the next cycle (never lost, just not double-sent).
        //
        // We must NOT block here: forceFlush runs on the shared scheduled executor whose
        // threads also run the gate-releasing completion, so a blocking wait could deadlock.
        if (!flushInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Force flush: deferred — a flush is already in progress (avoids double-export)")
            // Return the in-progress flush's completion, NOT an instant fake success.
            // Callers (and tests) can join() it to observe real settlement, and a
            // deferred-onto-a-failing-flush correctly reports failure so retry works.
            return activeFlushResult.get() ?: CompletableResultCode.ofSuccess()
        }
        // Completes only after export AND buffer cleanup have settled (or on failure).
        val flushDone = CompletableResultCode()
        activeFlushResult.set(flushDone)

        try {
            Log.i(TAG, "Force flush: exporting all buffered events")

            // Snapshot the RAM buffer before exporting. New events that arrive during the
            // export must not be lost — we remove only the exact snapshotted objects via
            // identity equality, the same pattern used in flushWindow().
            //
            // Snapshot under bufferMoveLock so an in-flight RAM→disk eviction is atomic
            // from our perspective: every event is in exactly one tier, never in limbo.
            val ramSnapshot: List<BufferedEvent>
            val diskRows: List<DiskLogBuffer.DiskRow>
            synchronized(bufferMoveLock) {
                ramSnapshot = ramBuffer.toList()
                diskRows = runBlocking { diskBuffer.getAllEventsWithIds() }
            }

            // Disk may contain crash-safety mirrors of events still in RAM.
            // Use seqId to deduplicate: skip disk events whose seqId matches a RAM event.
            val ramSeqIds = HashSet<Long>(ramSnapshot.size)
            ramSnapshot.forEach { ramSeqIds.add(it.seqId) }
            val diskOverflowOnly = diskRows
                .filter { row -> row.seqId == 0L || row.seqId !in ramSeqIds }
                .map { it.record }
            val allEvents = ramSnapshot.map { it.logRecord } + diskOverflowOnly

            // Every disk row in the snapshot is covered by this export (overflow rows
            // directly, crash-mirror rows via their RAM copies) — so cleanup deletes
            // exactly these row ids and ONLY these. Rows persisted after the snapshot
            // were NOT exported and must survive (clearAll() here used to drop them).
            val exportedDiskIds = diskRows.map { it.id }

            Log.i(TAG, "Force flushing ${allEvents.size} events (${ramSnapshot.size} RAM + ${diskOverflowOnly.size} disk-overflow)")

            if (allEvents.isEmpty()) {
                flushInProgress.set(false)
                flushDone.succeed()
                return flushDone
            }

            val results = allEvents.chunked(100).map { batch -> exporter.export(batch) }
            val result = CompletableResultCode.ofAll(results)

            result.whenComplete {
                if (!result.isSuccess) {
                    // Failure: events stay buffered; there is no cleanup I/O to do. Release
                    // the gate HERE (an atomic set, safe on any thread) instead of via
                    // executor.submit — otherwise an immediate retry forceFlush() races the
                    // executor and silently defers against a flush that is already over.
                    Log.w(TAG, "Force flush failed, keeping events in buffer")
                    flushInProgress.set(false)
                    flushDone.fail()
                } else {
                    executor.submit {
                        try {
                            if (isShutdown.get()) {
                                Log.i(TAG, "Force flush exported but skipping buffer clear (shutdown/crash)")
                            } else {
                                val exportedIds = Collections.newSetFromMap(
                                    IdentityHashMap<BufferedEvent, Boolean>()
                                )
                                exportedIds.addAll(ramSnapshot)
                                var removed = 0
                                var removedBytes = 0L
                                ramBuffer.removeIf { event ->
                                    exportedIds.contains(event).also { matched -> if (matched) { removed++; removedBytes += event.sizeBytes } }
                                }
                                ramBufferCount.addAndGet(-removed)
                                ramBufferBytes.addAndGet(-removedBytes)
                                synchronized(persistedToDisk) { persistedToDisk.removeAll(exportedIds) }
                                val deletedDisk = runBlocking {
                                    // Snapshot rows by id, PLUS any crash-mirror rows written
                                    // during the export whose RAM originals we just exported
                                    // (same seqId) — orphaned mirrors would re-export as
                                    // duplicates on the next flush.
                                    diskBuffer.deleteByIds(exportedDiskIds) +
                                        diskBuffer.deleteBySeqIds(ramSnapshot.map { it.seqId })
                                }
                                Log.i(TAG, "Force flush completed: removed $removed RAM + deleted $deletedDisk exported disk rows")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error clearing buffers after force flush", e)
                        } finally {
                            flushInProgress.set(false)
                            flushDone.succeed()
                        }
                    }
                }
            }

            return flushDone

        } catch (e: Exception) {
            Log.e(TAG, "Error during force flush", e)
            flushInProgress.set(false)
            flushDone.fail()
            return flushDone
        }
    }

    /**
     * Called from the uncaught exception handler on crash. Synchronously
     * persists the RAM buffer to disk and sets isShutdown to prevent any
     * in-flight or pending flushes from clearing the disk buffer. The next
     * launch detects non-empty disk → emits app.recovery_start.
     *
     * No network I/O — just a local SQLite write (milliseconds).
     */
    fun persistForCrash() {
        isShutdown.set(true)
        try {
            // Only write events not yet mirrored by the every-2s crash-safety task.
            // Without this filter, persistForCrash() writes the entire RAM buffer
            // again — including events already on disk — producing duplicate rows
            // (LogRecordEntity has autoGenerate=true primary keys, so identical
            // seqIds become separate rows). Recovery forceFlush() then re-exports
            // both copies. Verified 2026-05-14 with HYBRID+crash UAT cells.
            val events: List<BufferedEvent> = synchronized(persistedToDisk) {
                ramBuffer.filter { !persistedToDisk.contains(it) }.also { newEvents ->
                    persistedToDisk.addAll(newEvents)
                }
            }
            if (events.isNotEmpty()) {
                // SYNCHRONOUS write. The async `persistBufferedEvents` cannot be
                // trusted on the crash path — its coroutine may never get
                // scheduled before SIGKILL, which loses the most recent records
                // including `app.crash` itself. Verified 2026-05-14 on a real
                // Samsung A22: with the async variant, app.crash never landed
                // in Dash0 despite the recovery flush succeeding. See memory
                // `feedback_crash_handler_race.md`.
                diskBuffer.persistBufferedEventsBlocking(events)
                Log.i(TAG, "Crash persist: wrote ${events.size} new RAM events to disk (blocking)")
            } else {
                Log.i(TAG, "Crash persist: no new events (all already mirrored)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Crash persist failed", e)
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

            // NF-003: Release any attached network-watcher listener so we don't leak
            // a callback into a watcher that outlives this processor.
            attachNetworkWatcher(null, 0)

            // Export and clear the RAM buffer only. Disk events are intentionally preserved
            // for crash-recovery: the next process start can find and re-export them.
            val ramEvents = ramBuffer.toList()
            ramBuffer.clear()
            ramBufferCount.set(0)
            ramBufferBytes.set(0)
            synchronized(persistedToDisk) { persistedToDisk.clear() }

            val flushResult = if (ramEvents.isNotEmpty()) {
                val batchSize = 100
                val logRecords = ramEvents.map { it.logRecord }
                val results = logRecords.chunked(batchSize).map { batch -> exporter.export(batch) }
                CompletableResultCode.ofAll(results)
            } else {
                CompletableResultCode.ofSuccess()
            }

            // Shutdown executor
            executor.shutdown()
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }

            // Shutdown policy evaluator (cancels background coroutine + HTTP client)
            if (policyEvaluatorLazy.isInitialized()) policyEvaluator.shutdown()

            // Shutdown exporter
            val exporterResult = exporter.shutdown()

            return CompletableResultCode.ofAll(listOf(flushResult, exporterResult))

        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown", e)
            return CompletableResultCode.ofFailure()
        }
    }

    /**
     * Checks whether the device currently has network connectivity.
     * Uses ConnectivityManager to check for an active network.
     */
    internal fun isDeviceOffline(): Boolean {
        return try {
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return true
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                cm.activeNetwork == null
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.isConnected != true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Connectivity check failed, assuming offline", e)
            true
        }
    }

    /**
     * Returns the shared remote kill-switch / global-sampling gate this processor reads.
     * Exposed so the owning provider can wire the same instance into the span sampler.
     */
    fun getRemoteGate(): io.opentelemetry.android.mobile.policy.RemoteGate = remoteGate

    /**
     * Gets the current buffer statistics for monitoring.
     */
    fun getBufferStats(): BufferStats {
        return BufferStats(
            ramBufferSize = ramBufferCount.get(),
            diskBufferSize = diskBuffer.getEventCount(),
            ramBufferCapacity = ramBufferSize,
            diskBufferCapacityMb = diskBufferMb,
            ramBufferBytes = maxOf(0L, ramBufferBytes.get()),
            ramBufferMaxTotalBytes = ramBufferMaxTotalBytes,
            ramBufferMaxEventBytes = ramBufferMaxEventBytes,
            droppedOversizeCount = droppedOversizeCount.get()
        )
    }

    /**
     * Statistics about current buffer state.
     */

    /**
     * Builder for MobileLogRecordProcessor.
     */
    class Builder(private val context: Context) {
        private var exporter: LogRecordExporter? = null
        private var config: io.opentelemetry.android.mobile.config.MobileConfig? = null
        private var meter: io.opentelemetry.api.metrics.Meter? = null
        private var ramBufferSize: Int = 5000
        private var ramBufferMaxTotalBytes: Long = 10L * 1024 * 1024
        private var ramBufferMaxEventBytes: Int = 256 * 1024
        private var diskBufferMb: Int = 50
        private var diskBufferTtlHours: Int = 24
        private var remoteGate: io.opentelemetry.android.mobile.policy.RemoteGate? = null

        fun setExporter(exporter: LogRecordExporter) = apply { this.exporter = exporter }
        fun setConfig(config: io.opentelemetry.android.mobile.config.MobileConfig) = apply { this.config = config }
        fun setMeter(meter: io.opentelemetry.api.metrics.Meter) = apply { this.meter = meter }
        fun setRamBufferSize(size: Int) = apply { this.ramBufferSize = size }
        fun setRamBufferMaxTotalBytes(bytes: Long) = apply { this.ramBufferMaxTotalBytes = bytes }
        fun setRamBufferMaxEventBytes(bytes: Int) = apply { this.ramBufferMaxEventBytes = bytes }
        fun setDiskBufferMb(sizeMb: Int) = apply { this.diskBufferMb = sizeMb }
        fun setDiskBufferTtlHours(hours: Int) = apply { this.diskBufferTtlHours = hours }

        /**
         * Supplies the shared remote kill-switch / global-sampling gate. Pass the
         * same instance handed to the span sampler so logs and spans gate coherently.
         * When omitted, the processor uses a fresh open gate (enabled, full rate).
         */
        fun setRemoteGate(gate: io.opentelemetry.android.mobile.policy.RemoteGate) =
            apply { this.remoteGate = gate }

        fun build(): MobileLogRecordProcessor {
            return MobileLogRecordProcessor(
                context = context,
                exporter = requireNotNull(exporter) { "Exporter is required" },
                config = requireNotNull(config) { "Config is required" },
                meter = requireNotNull(meter) { "Meter is required" },
                ramBufferSize = ramBufferSize,
                ramBufferMaxTotalBytes = ramBufferMaxTotalBytes,
                ramBufferMaxEventBytes = ramBufferMaxEventBytes,
                diskBufferMb = diskBufferMb,
                diskBufferTtlHours = diskBufferTtlHours,
                remoteGate = remoteGate ?: io.opentelemetry.android.mobile.policy.RemoteGate()
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

/**
 * Returns the effective timestamp of a log record in epoch milliseconds.
 *
 * The OTel SDK sets timestampEpochNanos = 0 when no explicit timestamp is provided,
 * relying on observedTimestampEpochNanos instead. Using timestampEpochNanos directly
 * would cause all such events to fail time-window filters. This helper falls back to
 * observedTimestampEpochNanos (always set by the SDK) when timestamp is unset.
 */
private fun LogRecordData.effectiveTimestampMs(): Long {
    val tsNs = timestampEpochNanos
    return if (tsNs > 0) tsNs / 1_000_000 else observedTimestampEpochNanos / 1_000_000
}

/**
 * Ensures that [timestampEpochNanos] is always populated.
 *
 * The OTel SDK only auto-sets observedTimestampEpochNanos — most emitters don't call
 * setTimestamp(), leaving timestampEpochNanos = 0. Backends (Dash0) and the OTLP JSON
 * exporter surface this as a null timestamp, which hides the event's true time.
 *
 * This copies observedTimestamp into timestamp when timestamp is unset, so every
 * exported event has an explicit event time matching when it was generated.
 */
private fun LogRecordData.ensureTimestamp(): LogRecordData {
    if (timestampEpochNanos > 0) return this
    val effectiveTs = observedTimestampEpochNanos
    if (effectiveTs <= 0) return this
    val original = this
    return object : LogRecordData by original {
        override fun getTimestampEpochNanos(): Long = effectiveTs
    }
}
