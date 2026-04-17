import Foundation
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

    /// Optional policy evaluator. When non-nil, every `onEmit` runs the event's
    /// attributes through `evaluator.evaluate(...)`. A matching policy triggers
    /// a selective `flushWindow(minutes:)` for that window — this is the
    /// mechanism that turns DSL v2 rules (from a gateway or code) into real
    /// export behaviour.
    ///
    /// Backward compatible: when `nil`, policies are not consulted — the
    /// processor behaves exactly like the earlier no-policy implementation.
    private let policyEvaluator: PolicyEvaluator?

    /// Production constructor: drains buffered records through an upstream OTel
    /// `LogRecordExporter` on forceFlush/selective-flush.
    public init(
        buffer: RAMEventBuffer,
        otelExporter: LogRecordExporter,
        sessionProvider: SessionProvider,
        sequenceCounter: SequenceCounter = SequenceCounter(),
        diskBuffer: DiskLogBuffer? = nil,
        policyEvaluator: PolicyEvaluator? = nil
    ) {
        self.buffer = buffer
        self.legacyExporter = nil
        self.otelExporter = otelExporter
        self.sequenceCounter = sequenceCounter
        self.sessionProvider = sessionProvider
        self.diskBuffer = diskBuffer
        self.policyEvaluator = policyEvaluator
    }

    /// Test-only constructor: delivers `[BufferedEvent]` batches to an
    /// inspector that doesn't need to implement the full OTel exporter
    /// contract. Use `init(buffer:otelExporter:...)` in production.
    public init(
        buffer: RAMEventBuffer,
        exporter: BufferedEventExporter,
        sessionProvider: SessionProvider,
        sequenceCounter: SequenceCounter = SequenceCounter(),
        diskBuffer: DiskLogBuffer? = nil,
        policyEvaluator: PolicyEvaluator? = nil
    ) {
        self.buffer = buffer
        self.legacyExporter = exporter
        self.otelExporter = nil
        self.sequenceCounter = sequenceCounter
        self.sessionProvider = sessionProvider
        self.diskBuffer = diskBuffer
        self.policyEvaluator = policyEvaluator
    }

    // MARK: - LogRecordProcessor

    public func onEmit(logRecord: ReadableLogRecord) {
        let event = makeEvent(from: logRecord)
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

    public func forceFlush(explicitTimeout: TimeInterval? = nil) -> ExportResult {
        let semaphore = DispatchSemaphore(value: 0)
        final class Box: @unchecked Sendable { var value: ExportResult = .success }
        let box = Box()
        Task.detached { [weak self] in
            guard let self = self else { semaphore.signal(); return }
            let events = await self.buffer.flush()
            if !events.isEmpty {
                box.value = await self.exportThroughConfiguredSink(events: events)
            }
            semaphore.signal()
        }
        if let timeout = explicitTimeout {
            _ = semaphore.wait(timeout: .now() + timeout)
        } else {
            semaphore.wait()
        }
        return box.value
    }

    public func shutdown(explicitTimeout: TimeInterval? = nil) -> ExportResult {
        forceFlush(explicitTimeout: explicitTimeout)
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
    @discardableResult
    public func forceFlushBuffered() -> BufferExportResult {
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
                }
            }
            semaphore.signal()
        }
        semaphore.wait()
        return box.value
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
