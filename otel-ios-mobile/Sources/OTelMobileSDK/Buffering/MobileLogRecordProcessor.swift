import Foundation
import Network
import OTelMobileCore
import OpenTelemetryApi
import OpenTelemetrySdk

/// Processes emitted log records by buffering them in RAM and flushing via
/// an upstream OTel `LogRecordExporter` (OTLP/HTTP, OTLP/gRPC, etc.).
///
/// This processor is 100% OTel-native — it holds the upstream
/// `ReadableLogRecord` directly (no custom JSON encoding) and drains buffered
/// records through `LogRecordExporter.export(logRecords:)`. The architecture
/// mirrors the Android SDK's `MobileLogRecordProcessor` which holds a
/// `LogRecordExporter` and emits `LogRecordData` instances unchanged.
///
/// A legacy `BufferedEventExporter` overload is retained for tests that want
/// to observe buffer contents without a real OTLP exporter. The OTel path is
/// preferred for production wiring.
public final class MobileLogRecordProcessor: LogRecordProcessor, @unchecked Sendable {
    private let buffer: RAMEventBuffer
    private let legacyExporter: BufferedEventExporter?
    private let otelExporter: LogRecordExporter?
    private let sequenceCounter: SequenceCounter
    private let sessionProvider: SessionProvider

    /// Optional disk buffer. When non-nil:
    /// - RAM evictions are spilled here (survives process death).
    /// - `forceFlushBuffered()` / `flushWindow(minutes:)` drain RAM first, then
    ///   any disk-resident events, deduplicating by `sequenceId`.
    /// - After a successful export we `deleteUpTo(sequenceId:)` to clear the
    ///   drained window.
    ///
    /// Backward compatible: when `nil`, the processor behaves exactly as the
    /// RAM-only implementation.
    private let diskBuffer: DiskLogBuffer?

    /// Per-record attributes stamped onto every emitted log record. Mirrors
    /// Android's per-record injection of `extraResourceAttributes` — Dash0
    /// ingestion drops unknown Resource-level attributes, so UAT cell_id
    /// and export_mode must ride as record-level attributes.
    private let extraRecordAttributes: [String: String]

    /// Offline policy controlling what gets buffered when the device is offline.
    /// Default `.bufferAll` preserves existing behaviour.
    private let offlinePolicy: OfflinePolicy

    /// Coalesces identical error events within a time window. Reduces noise
    /// in offline/degraded scenarios where the same error fires repeatedly.
    private let errorCoalescer: ErrorCoalescer

    /// Optional policy evaluator. When non-nil, every `onEmit` runs the event's
    /// attributes through `evaluator.evaluate(...)`. A matching policy triggers
    /// a selective `flushWindow(minutes:)` for that window — this is the
    /// mechanism that turns DSL v2 rules (from a gateway or code) into real
    /// export behaviour.
    ///
    /// Backward compatible: when `nil`, policies are not consulted — the
    /// processor behaves exactly like the earlier no-policy implementation.
    private let policyEvaluator: PolicyEvaluator?

    /// Optional remote kill-switch + global-sampling gate. When non-nil, every
    /// `onEmit` consults `remoteGate.shouldEmitLog()` BEFORE any buffering or
    /// coalescing work: a remotely-disabled SDK drops the record immediately,
    /// and a global `sample_rate < 1` applies a probabilistic per-record drop.
    /// Read is synchronous and allocation-free (see `RemoteGate`), so it never
    /// blocks the OTel-synchronous `onEmit` contract.
    ///
    /// Backward compatible: when `nil`, no gating is applied — the processor
    /// behaves exactly as before. Shared with the span sampler via `OTelMobile`
    /// init so logs and spans honour the same `(enabled, sampleRate)`.
    private let remoteGate: RemoteGate?

    /// Optional hook invoked once per policy match with the policy id (e.g.
    /// `"crash-recovery"`). Mirrors Android's `policyMatchHook`. Wired by
    /// `OTelMobile.start` to capture a screenshot + wireframe for journey-
    /// replay context alongside every flush trigger.
    ///
    /// Best-effort — must not throw; silent no-op if the screenshot/wireframe
    /// modules aren't installed. Always invoked BEFORE the flush so the
    /// captures land in the same flush window.
    public var policyMatchHook: ((String) -> Void)?

    /// Periodic flush timer used in CONTINUOUS export mode. When running,
    /// every `intervalSeconds` the timer calls `forceFlushBuffered()` so
    /// long-lived apps in CONTINUOUS mode don't depend on backgrounding
    /// or policy triggers for logs to land in the backend.
    ///
    /// Mirror of Android's `executor.scheduleAtFixedRate(forceFlush, N, N, SECONDS)`
    /// loop inside Android's `MobileLogRecordProcessor`. Before this was
    /// added, iOS compensated by attaching a second upstream
    /// `BatchLogRecordProcessor` to the `LoggerProvider`, but that caused
    /// every log record to be double-exported (both processors ran
    /// independently and each POSTed the record to OTLP). See the
    /// project's 2026-04-23b session notes for the bug investigation.
    ///
    /// Only used in CONTINUOUS mode. CONDITIONAL + HYBRID flush only on
    /// policy match, which matches Android behaviour.
    private var continuousTimer: DispatchSourceTimer?
    private let timerQueue = DispatchQueue(label: "io.dash0.mobile.MobileLogRecordProcessor.timer",
                                           qos: .utility)

    /// NF-010: Active network-restored subscription, if any. The processor owns
    /// the listener so it can be detached on re-attach / shutdown. Pure storage —
    /// the watcher's `addListener` already holds a weak ref, so we hold strong
    /// here to keep it alive for the processor's lifetime.
    private let watcherLock = NSLock()
    private var networkWatcher: NetworkAvailabilityWatcher?
    private var networkListener: NetworkRestoredListener?

    /// Production constructor: drains buffered records through an upstream OTel
    /// `LogRecordExporter` on forceFlush/selective-flush.
    public init(
        buffer: RAMEventBuffer,
        otelExporter: LogRecordExporter,
        sessionProvider: SessionProvider,
        sequenceCounter: SequenceCounter = SequenceCounter(),
        diskBuffer: DiskLogBuffer? = nil,
        policyEvaluator: PolicyEvaluator? = nil,
        remoteGate: RemoteGate? = nil,
        extraRecordAttributes: [String: String] = [:],
        offlinePolicy: OfflinePolicy = .bufferAll,
        errorCoalescer: ErrorCoalescer = ErrorCoalescer()
    ) {
        self.buffer = buffer
        self.legacyExporter = nil
        self.otelExporter = otelExporter
        self.sequenceCounter = sequenceCounter
        self.sessionProvider = sessionProvider
        self.diskBuffer = diskBuffer
        self.policyEvaluator = policyEvaluator
        self.remoteGate = remoteGate
        self.extraRecordAttributes = extraRecordAttributes
        self.offlinePolicy = offlinePolicy
        self.errorCoalescer = errorCoalescer
    }

    /// SDK-internal test constructor. Delivers `[BufferedEvent]` batches so
    /// tests can introspect seqId / payload — fields the public OTel path
    /// doesn't surface. Internal-scoped intentionally; production callers use
    /// `init(buffer:otelExporter:...)`.
    internal init(
        buffer: RAMEventBuffer,
        exporter: BufferedEventExporter,
        sessionProvider: SessionProvider,
        sequenceCounter: SequenceCounter = SequenceCounter(),
        diskBuffer: DiskLogBuffer? = nil,
        policyEvaluator: PolicyEvaluator? = nil,
        remoteGate: RemoteGate? = nil,
        extraRecordAttributes: [String: String] = [:],
        offlinePolicy: OfflinePolicy = .bufferAll,
        errorCoalescer: ErrorCoalescer = ErrorCoalescer()
    ) {
        self.buffer = buffer
        self.legacyExporter = exporter
        self.otelExporter = nil
        self.sequenceCounter = sequenceCounter
        self.sessionProvider = sessionProvider
        self.diskBuffer = diskBuffer
        self.policyEvaluator = policyEvaluator
        self.remoteGate = remoteGate
        self.extraRecordAttributes = extraRecordAttributes
        self.offlinePolicy = offlinePolicy
        self.errorCoalescer = errorCoalescer
    }

    // MARK: - LogRecordProcessor

    public func onEmit(logRecord: ReadableLogRecord) {
        // Remote kill switch + global sampling — consulted at the TOP of
        // onEmit, BEFORE any enrichment, offline filtering, or error
        // coalescing, so a remotely-disabled SDK does NO per-event work for
        // this record (no coalescer-state mutation, no attribute stamping).
        // Matches the Android processor (MobileLogRecordProcessor.onEmit) and
        // the spec's "does no work when disabled" contract. Synchronous,
        // allocation-free read of the shared gate: `!enabled` ⇒ drop;
        // `sample_rate < 1` ⇒ probabilistic drop. The gate reads only gate
        // state (not the record), so running it before enrichment is correct.
        // RN-originated telemetry rides the same logger → this same `onEmit`,
        // so this single gate covers React Native with no RN-side change. The
        // SDK-state gauges are emitted on a separate path and remain unaffected.
        if let gate = remoteGate, !gate.shouldEmitLog() { return }

        var enriched = logRecord
        for (key, value) in extraRecordAttributes where !key.isEmpty {
            enriched.setAttribute(key: key, value: value)
        }

        // Offline policy filtering: when the device is offline, drop events
        // below the configured severity threshold.
        if Self.isDeviceOffline() {
            if offlinePolicy.dropsAll { return }
            if let minSeverity = offlinePolicy.minBufferSeverity {
                guard let recordSeverity = enriched.severity,
                      recordSeverity.rawValue >= minSeverity.rawValue else { return }
            }
        }

        // Error coalescing: suppress duplicate errors within the window.
        if errorCoalescer.tryCoalesce(enriched) { return }

        let event = makeEvent(from: enriched)
        // Fire-and-forget append. `onEmit` is synchronous per the protocol
        // contract; the actor append completes asynchronously. Tests that need
        // to observe the buffer after `onEmit` should `await` on a peek/flush
        // after giving the detached task a chance to run.
        //
        // If a disk buffer is configured, any event evicted from the RAM
        // buffer (capacity or size-budget eviction) is spilled to disk so we
        // preserve it across process death.
        //
        // If a policy evaluator is configured, the event's attributes are
        // run through it and a matching policy schedules a selective flush.
        // Both branches run concurrently off `onEmit`'s synchronous thread —
        // evaluation cost never blocks the emit path.
        Task.detached { [buffer, diskBuffer, policyEvaluator, weak self] in
            let evicted = await buffer.append(event)
            if let evicted = evicted, let diskBuffer = diskBuffer {
                await diskBuffer.insert(evicted)
            }
            if let evaluator = policyEvaluator, let self = self {
                let attrs = Self.attributesForEval(logRecord)
                if let match = await evaluator.evaluate(attributes: attrs) {
                    // Capture journey-replay artifacts BEFORE the flush so they
                    // land in the same flush window. Wrapped so a capture failure
                    // can't derail the flush.
                    self.policyMatchHook?(match.policyId)
                    _ = await self.flushWindow(minutes: UInt64(match.flushWindowMinutes))
                }
            }
        }
    }

    // MARK: - Policy attribute projection

    /// Flatten a `ReadableLogRecord` down to `[String: String]` for policy
    /// evaluation. Matches Android's `getAttributeValue(...)` fallback chain
    /// shape: body becomes `event.name`, severity becomes `severity_number`,
    /// and each attribute is stringified regardless of its typed variant so
    /// DSL operators (equals/contains/regex/gt/gte) can consume it.
    static func attributesForEval(_ record: ReadableLogRecord) -> [String: String] {
        var out: [String: String] = [:]
        if let body = record.body {
            out["event.name"] = Self.stringify(body)
        }
        if let sev = record.severity {
            out["severity_number"] = String(sev.rawValue)
            out["severity"] = "\(sev)"
        }
        for (k, v) in record.attributes {
            out[k] = Self.stringify(v)
        }
        return out
    }

    /// String projection of every `AttributeValue` case the DSL can reason
    /// about. Arrays join with commas (DSL contains/regex handle that fine).
    /// `@unknown default` covers any future case additions so the SDK keeps
    /// working even when opentelemetry-swift adds new variants.
    static func stringify(_ value: AttributeValue) -> String {
        switch value {
        case .string(let s):       return s
        case .bool(let b):         return String(b)
        case .int(let i):          return String(i)
        case .double(let d):       return String(d)
        case .stringArray(let a):  return a.joined(separator: ",")
        case .boolArray(let a):    return a.map { String($0) }.joined(separator: ",")
        case .intArray(let a):     return a.map { String($0) }.joined(separator: ",")
        case .doubleArray(let a):  return a.map { String($0) }.joined(separator: ",")
        case .array:               return ""
        case .set:                 return ""
        @unknown default:          return ""
        }
    }

    /// OTel `LogRecordProcessor` protocol implementation. Drains RAM + disk
    /// (delegates to `forceFlushBuffered()`) and adapts the richer
    /// `BufferExportResult` to the binary `ExportResult` upstream expects.
    ///
    /// Previously this method drained RAM only, leaving disk-buffered events
    /// undrained on shutdown — which silently broke the offline-survives-reconnect
    /// promise for callers that hit the OTel public protocol surface but never
    /// the SDK-internal `forceFlushBuffered`. Architecture-hardening epic
    /// Track 5: one public drain method, always drains both tiers. See
    /// docs/contracts/buffer-drain-surface.md.
    public func forceFlush(explicitTimeout: TimeInterval? = nil) -> ExportResult {
        // explicitTimeout is part of the OTel protocol surface; forceFlushBuffered
        // uses its own DispatchSemaphore-based wait without a caller-supplied
        // bound, so the timeout is honoured implicitly by the underlying
        // SynchronousHTTPClient's request.timeoutInterval. Surfaced here so the
        // signature remains protocol-compliant.
        _ = explicitTimeout
        switch forceFlushBuffered() {
        case .success: return .success
        case .failure: return .failure
        }
    }

    public func shutdown(explicitTimeout: TimeInterval? = nil) -> ExportResult {
        stopContinuousFlush()
        return forceFlush(explicitTimeout: explicitTimeout)
    }

    // MARK: - Periodic flush (CONTINUOUS mode)

    /// Schedule a repeating `forceFlushBuffered()` call every
    /// `intervalSeconds`. Idempotent — calling twice replaces the
    /// previous timer. Call from `OTelMobile.start` when
    /// `MobileConfig.exportMode == .continuous`.
    ///
    /// Why this exists: on iOS, the RAM buffer holds emitted events until
    /// something explicitly drains it (policy trigger, backgrounding,
    /// FATAL severity, explicit forceFlush). In CONTINUOUS mode callers
    /// expect a steady trickle of exports even when the app is quietly
    /// in the foreground — this timer provides that.
    public func startContinuousFlush(intervalSeconds: UInt64) {
        stopContinuousFlush()
        // Clamp to a reasonable floor — a 0s timer would spin the CPU.
        let interval = max(1, intervalSeconds)
        let timer = DispatchSource.makeTimerSource(queue: timerQueue)
        timer.schedule(deadline: .now() + .seconds(Int(interval)),
                       repeating: .seconds(Int(interval)))
        timer.setEventHandler { [weak self] in
            _ = self?.forceFlushBuffered()
        }
        timer.resume()
        continuousTimer = timer
    }

    /// Stop the periodic flush timer if one is running. Idempotent.
    public func stopContinuousFlush() {
        continuousTimer?.cancel()
        continuousTimer = nil
    }

    // MARK: - Selective flush

    /// Export the last `minutes` of buffered events via the configured OTel
    /// or legacy exporter. When a `diskBuffer` is configured, also drains the
    /// matching window from disk (dedup-by-seqId against the RAM events).
    @discardableResult
    public func flushWindow(minutes: UInt64) async -> BufferExportResult {
        let windowMs = minutes * 60 * 1000
        let ramEvents = await buffer.flushWindow(lastMs: windowMs)
        let combined = await combineWithDisk(ramEvents: ramEvents, windowMs: windowMs)
        if combined.isEmpty { return .success }
        let result = await exportBuffered(events: combined)
        if case .success = result, let disk = diskBuffer,
           let maxSeq = combined.map({ $0.sequenceId }).max() {
            await disk.deleteUpTo(sequenceId: maxSeq)
        }
        return result
    }

    // MARK: - Buffer-level flush (public API surface)

    /// Synchronously flush buffered events through the configured exporter and
    /// return the richer `BufferExportResult`. Used by `OTelMobile.forceFlush()`.
    ///
    /// Failure-persistence contract: if export fails AND a disk buffer is
    /// configured, every RAM event that was flushed gets persisted to disk
    /// before returning. Without this, `buffer.flush()` has already emptied
    /// the RAM buffer — so a failed export would silently drop the events.
    /// This is the offline-survives-reconnect promise: flush-on-offline must
    /// leave telemetry recoverable on the next successful export attempt,
    /// either in the same process (via the RAM/disk dedupe path on the next
    /// forceFlush) or in a future process (via start-time recovery).
    @discardableResult
    internal func forceFlushBuffered() -> BufferExportResult {
        let semaphore = DispatchSemaphore(value: 0)
        final class Box: @unchecked Sendable { var value: BufferExportResult = .success }
        let box = Box()
        Task.detached { [weak self] in
            guard let self = self else { semaphore.signal(); return }
            let ramEvents = await self.buffer.flush()
            let combined = await self.combineWithDisk(ramEvents: ramEvents, windowMs: nil)
            if !combined.isEmpty {
                box.value = await self.exportBuffered(events: combined)
                if case .success = box.value, let disk = self.diskBuffer,
                   let maxSeq = combined.map({ $0.sequenceId }).max() {
                    await disk.deleteUpTo(sequenceId: maxSeq)
                } else if case .failure = box.value, let disk = self.diskBuffer {
                    // Export failed. `buffer.flush()` above already emptied
                    // the RAM buffer, so these events only survive if we
                    // persist them now. Dedup-by-seqId: events already on
                    // disk are harmless to skip; we only insert the
                    // RAM-originated ones so we don't double up.
                    let diskEvents = await disk.fetchAll()
                    let onDisk = Set(diskEvents.map { $0.sequenceId })
                    for event in combined where !onDisk.contains(event.sequenceId) {
                        await disk.insert(event)
                    }
                }
            }
            semaphore.signal()
        }
        semaphore.wait()
        return box.value
    }

    // MARK: - NF-010: Network-restored flush hook

    /// Subscribe this processor to a `NetworkAvailabilityWatcher`. On every
    /// LOST → AVAILABLE transition the watcher emits, the processor invokes
    /// `forceFlushBuffered()` so any RAM+disk buffered events drain immediately
    /// on reconnection — no app restart, no unrelated policy trigger required.
    ///
    /// Re-attaching swaps the previous subscription. Pass `nil` to detach.
    ///
    /// **iOS note:** unlike Android which calls `flushWindow(minutes)`, iOS
    /// uses `forceFlushBuffered()` because the iOS offline failure-persistence
    /// contract drains RAM into disk on each failed flush — the right recovery
    /// path is "drain RAM + disk", not a time-windowed slice. The `minutes`
    /// parameter is preserved for API parity with Android but is reserved for
    /// future use (e.g. honouring a flush window on long offline streaks).
    public func attachNetworkWatcher(_ watcher: NetworkAvailabilityWatcher?, minutes _: UInt64) {
        watcherLock.lock(); defer { watcherLock.unlock() }
        if let prior = networkWatcher, let priorListener = networkListener {
            prior.removeListener(priorListener)
        }
        networkWatcher = watcher
        networkListener = nil

        guard let watcher = watcher else { return }
        let listener = NetworkRestoredListener(processor: self)
        networkListener = listener
        watcher.addListener(listener)
    }

    /// Invoked by the network-restored listener. Public-internal so the
    /// listener (a private class declared in the same module) can call back
    /// without breaking the actor isolation contract.
    func onNetworkRestored() {
        // forceFlushBuffered() is blocking (DispatchSemaphore). Hop onto a
        // detached Task so the watcher's notify path doesn't stall.
        Task.detached { [weak self] in
            _ = self?.forceFlushBuffered()
        }
    }

    /// Drains the disk buffer (full or windowed) and merges with the provided
    /// RAM events, deduplicating by `sequenceId`. RAM events take priority —
    /// they are the authoritative copy when a crash-mirror was written to
    /// disk and then the RAM event was still alive at flush time.
    private func combineWithDisk(
        ramEvents: [BufferedEvent],
        windowMs: UInt64?
    ) async -> [BufferedEvent] {
        guard let disk = diskBuffer else { return ramEvents }
        let diskEvents: [BufferedEvent]
        if let windowMs = windowMs {
            diskEvents = await disk.fetchWindow(lastMs: windowMs)
        } else {
            diskEvents = await disk.fetchAll()
        }
        if diskEvents.isEmpty { return ramEvents }
        // Dedup: start with RAM (wins), layer in disk events whose seqId is
        // not already present.
        var seen = Set<UInt64>()
        var combined: [BufferedEvent] = []
        combined.reserveCapacity(ramEvents.count + diskEvents.count)
        for event in ramEvents {
            if seen.insert(event.sequenceId).inserted {
                combined.append(event)
            }
        }
        for event in diskEvents where seen.insert(event.sequenceId).inserted {
            combined.append(event)
        }
        return combined
    }

    /// Recovery path called by `OTelMobile.start(config:)` on app launch when
    /// the disk buffer already holds events from a previous process. Drains
    /// disk contents through the exporter and on success clears them. Runs
    /// on a detached task; never blocks startup.
    public func recoverFromDisk() async -> BufferExportResult {
        guard let disk = diskBuffer else { return .success }
        let events = await disk.fetchAll(limit: 10_000)
        guard !events.isEmpty else { return .success }
        let result = await exportBuffered(events: events)
        if case .success = result, let maxSeq = events.map({ $0.sequenceId }).max() {
            await disk.deleteUpTo(sequenceId: maxSeq)
        }
        return result
    }

    /// Returns a snapshot of disk-buffer stats (row count, total bytes).
    /// `nil` when no disk buffer is configured. Used by recovery emission
    /// so startup can report the size of the pending backlog.
    public func diskStats() async -> (count: Int, bytes: Int)? {
        guard let disk = diskBuffer else { return nil }
        let count = await disk.rowCount()
        let bytes = await disk.totalSizeBytes()
        return (count, bytes)
    }

    // MARK: - Network reachability

    /// Test-seam override. When non-nil, `isDeviceOffline()` returns this
    /// value instead of querying `NWPathMonitor`. Only set from tests.
    static var _offlineOverride: Bool?

    /// Snapshot check for network reachability. Uses the `Network` framework's
    /// `NWPathMonitor` current path. On macOS / Simulator this always returns
    /// `.satisfied` — real offline testing requires a device or explicit
    /// override via `_offlineOverride`.
    static func isDeviceOffline() -> Bool {
        if let override = _offlineOverride { return override }
        let monitor = NWPathMonitor()
        let path = monitor.currentPath
        monitor.cancel()
        return path.status != .satisfied
    }

    // MARK: - Internal export routing

    /// Routes a batch of buffered events through the configured sink — OTel
    /// `LogRecordExporter` in production, `BufferedEventExporter` in tests.
    private func exportBuffered(events: [BufferedEvent]) async -> BufferExportResult {
        if let otelExporter = otelExporter {
            // OTel-native path: preserve the upstream ReadableLogRecord and
            // hand it to the OTel exporter directly. No custom encoding.
            let records = events.compactMap { $0.record }
            guard !records.isEmpty else { return .success }
            let result = otelExporter.export(logRecords: records, explicitTimeout: nil)
            switch result {
            case .success: return .success
            case .failure: return .failure(reason: "OTel exporter failure")
            }
        }
        if let legacy = legacyExporter {
            return await legacy.export(events)
        }
        return .success
    }

    private func exportThroughConfiguredSink(events: [BufferedEvent]) async -> ExportResult {
        switch await exportBuffered(events: events) {
        case .success: return .success
        case .failure: return .failure
        }
    }

    // MARK: - Test / integration helpers

    /// Cooperative wait that yields the current task repeatedly for up to
    /// `timeoutMs` milliseconds. Useful for letting fire-and-forget detached
    /// buffer-append tasks (spawned from the synchronous `onEmit`) settle
    /// before a test reads the buffer.
    ///
    /// Lives here (as opposed to the test target) so tests and demos can call
    /// it without `@testable import` or an explicit `Foundation` import.
    public static func waitForBufferedAppends(timeoutMs: UInt64) async throws {
        // Uses Date + Task.sleep(nanoseconds:) so we stay iOS 15 compatible.
        // ContinuousClock / Duration / Task.sleep(for:) require iOS 16+.
        let deadline = Date().addingTimeInterval(Double(timeoutMs) / 1000.0)
        while Date() < deadline {
            await Task.yield()
            try await Task.sleep(nanoseconds: 10_000_000) // 10ms
        }
    }

    // MARK: - Test seam

    /// Test-only hook that bypasses the OTel-Swift `ReadableLogRecord`
    /// encoding path. This awaits the buffer append so tests can observe the
    /// result deterministically.
    internal func injectEvent(_ event: BufferedEvent) async {
        _ = await buffer.append(event)
    }

    /// Test-only accessor that returns the sequence ID that would be assigned
    /// to the next event. Tests use this to seed deterministic sequence IDs
    /// without going through `onEmit`.
    internal func nextSequenceId() -> UInt64 {
        sequenceCounter.next()
    }

    // MARK: - Helpers

    /// Build a BufferedEvent that holds the upstream OTel `ReadableLogRecord`
    /// directly. No custom JSON encoding — the OTel exporter at flush time
    /// produces the wire format (OTLP/protobuf, OTLP/JSON, etc.).
    private func makeEvent(from logRecord: ReadableLogRecord) -> BufferedEvent {
        let seqId = sequenceCounter.next()
        let sessionId = sessionProvider.sessionId
        let timestampMs = UInt64(logRecord.timestamp.timeIntervalSince1970 * 1000)
        return BufferedEvent(
            sequenceId: seqId,
            timestampMs: timestampMs,
            sessionId: sessionId,
            record: logRecord
        )
    }
}

/// NF-010: Bridge between the watcher's `addListener` weak ref and the
/// processor's `onNetworkRestored()` callback. Lives outside the main class
/// because Swift class declarations can't be nested inside an open-ended
/// `final class` and still satisfy the watcher's `AnyObject`-constrained
/// `Listener` protocol cleanly.
private final class NetworkRestoredListener: NetworkAvailabilityListener {
    private weak var processor: MobileLogRecordProcessor?
    init(processor: MobileLogRecordProcessor) { self.processor = processor }
    func onTransition(_ transition: NetworkAvailabilityWatcher.Transition) {
        guard case .restored = transition else { return }
        processor?.onNetworkRestored()
    }
}
