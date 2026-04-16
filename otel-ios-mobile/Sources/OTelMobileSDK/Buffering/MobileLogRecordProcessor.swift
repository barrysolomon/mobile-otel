import Foundation
import OTelMobileCore
import OpenTelemetryApi
import OpenTelemetrySdk

/// Processes emitted log records by buffering them in RAM and flushing to an
/// injected exporter on forceFlush or selective window flush.
///
/// Thin-slice implementation — disk spill and real OTLP protobuf serialization
/// will be added in a later task. This version JSON-encodes a minimal projection
/// of each `ReadableLogRecord` into `BufferedEvent.eventData`.
public final class MobileLogRecordProcessor: LogRecordProcessor, @unchecked Sendable {
    private let buffer: RAMEventBuffer
    private let exporter: BufferedEventExporter
    private let sequenceCounter: SequenceCounter
    private let sessionProvider: SessionProvider

    public init(
        buffer: RAMEventBuffer,
        exporter: BufferedEventExporter,
        sessionProvider: SessionProvider,
        sequenceCounter: SequenceCounter = SequenceCounter()
    ) {
        self.buffer = buffer
        self.exporter = exporter
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
        var bridged: ExportResult = .success
        Task.detached { [buffer, exporter] in
            let events = await buffer.flush()
            if !events.isEmpty {
                let result = await exporter.export(events)
                bridged = Self.bridge(result)
            }
            semaphore.signal()
        }
        if let timeout = explicitTimeout {
            _ = semaphore.wait(timeout: .now() + timeout)
        } else {
            semaphore.wait()
        }
        return bridged
    }

    public func shutdown(explicitTimeout: TimeInterval? = nil) -> ExportResult {
        forceFlush(explicitTimeout: explicitTimeout)
    }

    // MARK: - Selective flush

    /// Export the last `minutes` of buffered events.
    @discardableResult
    public func flushWindow(minutes: UInt64) async -> BufferExportResult {
        let events = await buffer.flushWindow(lastMs: minutes * 60 * 1000)
        if events.isEmpty { return .success }
        return await exporter.export(events)
    }

    // MARK: - Buffer-level flush (public API surface)

    /// Synchronously flush buffered events to the exporter and return the
    /// buffer-level result. The existing `forceFlush(explicitTimeout:)`
    /// conforms to OTel-Swift's `LogRecordProcessor` protocol and therefore
    /// returns the protocol-bridged `ExportResult`. This variant preserves the
    /// richer `BufferExportResult` (including failure reason) for callers that
    /// want it — notably `OTelMobile.forceFlush()`.
    @discardableResult
    public func forceFlushBuffered() -> BufferExportResult {
        let semaphore = DispatchSemaphore(value: 0)
        // Wrap in a reference box so the detached task can write the result.
        final class Box: @unchecked Sendable { var value: BufferExportResult = .success }
        let box = Box()
        Task.detached { [buffer, exporter] in
            let events = await buffer.flush()
            if !events.isEmpty {
                box.value = await exporter.export(events)
            }
            semaphore.signal()
        }
        semaphore.wait()
        return box.value
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
        let start = ContinuousClock().now
        let budget = Duration.milliseconds(Int64(timeoutMs))
        while ContinuousClock().now - start < budget {
            await Task.yield()
            try await Task.sleep(for: .milliseconds(10))
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

    private func makeEvent(from logRecord: ReadableLogRecord) -> BufferedEvent {
        let seqId = sequenceCounter.next()
        let sessionId = sessionProvider.sessionId
        let timestampMs = UInt64(logRecord.timestamp.timeIntervalSince1970 * 1000)
        let data = Self.encode(logRecord)
        return BufferedEvent(
            sequenceId: seqId,
            timestampMs: timestampMs,
            sessionId: sessionId,
            eventData: data
        )
    }

    /// Thin-slice encoding: JSON with a minimal projection. Task 10+ will
    /// switch to OTLP protobuf via the real `LogRecordExporter`.
    private static func encode(_ logRecord: ReadableLogRecord) -> Data {
        struct Payload: Swift.Encodable {
            let body: String
            let severity: Int
            let timestampMs: UInt64
        }
        let severity = logRecord.severity?.rawValue ?? 0
        let bodyString: String
        if let body = logRecord.body {
            bodyString = "\(body)"
        } else {
            bodyString = ""
        }
        let payload = Payload(
            body: bodyString,
            severity: severity,
            timestampMs: UInt64(logRecord.timestamp.timeIntervalSince1970 * 1000)
        )
        return (try? JSONEncoder().encode(payload)) ?? Data()
    }

    private static func bridge(_ result: BufferExportResult) -> ExportResult {
        switch result {
        case .success: return .success
        case .failure: return .failure
        }
    }
}
