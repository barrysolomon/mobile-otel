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

    /// Production constructor: drains buffered records through an upstream OTel
    /// `LogRecordExporter` on forceFlush/selective-flush.
    public init(
        buffer: RAMEventBuffer,
        otelExporter: LogRecordExporter,
        sessionProvider: SessionProvider,
        sequenceCounter: SequenceCounter = SequenceCounter()
    ) {
        self.buffer = buffer
        self.legacyExporter = nil
        self.otelExporter = otelExporter
        self.sequenceCounter = sequenceCounter
        self.sessionProvider = sessionProvider
    }

    /// Test-only constructor: delivers `[BufferedEvent]` batches to an
    /// inspector that doesn't need to implement the full OTel exporter
    /// contract. Use `init(buffer:otelExporter:...)` in production.
    public init(
        buffer: RAMEventBuffer,
        exporter: BufferedEventExporter,
        sessionProvider: SessionProvider,
        sequenceCounter: SequenceCounter = SequenceCounter()
    ) {
        self.buffer = buffer
        self.legacyExporter = exporter
        self.otelExporter = nil
        self.sequenceCounter = sequenceCounter
        self.sessionProvider = sessionProvider
    }

    // MARK: - LogRecordProcessor

    public func onEmit(logRecord: ReadableLogRecord) {
        let event = makeEvent(from: logRecord)
        // Fire-and-forget append. `onEmit` is synchronous per the protocol
        // contract; the actor append completes asynchronously. Tests that need
        // to observe the buffer after `onEmit` should `await` on a peek/flush
        // after giving the detached task a chance to run.
        Task.detached { [buffer] in
            _ = await buffer.append(event)
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
    /// or legacy exporter.
    @discardableResult
    public func flushWindow(minutes: UInt64) async -> BufferExportResult {
        let events = await buffer.flushWindow(lastMs: minutes * 60 * 1000)
        if events.isEmpty { return .success }
        return await exportBuffered(events: events)
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
            let events = await self.buffer.flush()
            if !events.isEmpty {
                box.value = await self.exportBuffered(events: events)
            }
            semaphore.signal()
        }
        semaphore.wait()
        return box.value
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
