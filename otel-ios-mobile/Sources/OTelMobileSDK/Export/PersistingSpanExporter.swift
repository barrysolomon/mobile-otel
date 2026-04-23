import Foundation
import OpenTelemetrySdk

/// `SpanExporter` decorator. On `.failure` from the delegate, persists the
/// batch to `DiskSpanBuffer` for recovery on next launch.
///
/// `SpanExporter.export` is synchronous, but our disk buffer is actor-
/// isolated (async). We bridge with a `DispatchSemaphore` so the export
/// call does not return until the disk write completes (or the 5s cap
/// elapses). This mirrors the log-side pattern where
/// `MobileLogRecordProcessor.onEmit` awaits `diskBuffer.insert` inline.
///
/// The wait happens on BSP's background BlockOperation thread, so briefly
/// blocking it on sqlite I/O is acceptable.
public final class PersistingSpanExporter: SpanExporter, @unchecked Sendable {
    private let delegate: SpanExporter
    private let diskBuffer: DiskSpanBuffer?
    private let sessionId: String
    private let persistTimeout: TimeInterval

    public init(
        delegate: SpanExporter,
        diskBuffer: DiskSpanBuffer?,
        sessionId: String,
        persistTimeout: TimeInterval = 5
    ) {
        self.delegate = delegate
        self.diskBuffer = diskBuffer
        self.sessionId = sessionId
        self.persistTimeout = persistTimeout
    }

    public func export(
        spans: [SpanData],
        explicitTimeout: TimeInterval?
    ) -> SpanExporterResultCode {
        let result = delegate.export(spans: spans, explicitTimeout: explicitTimeout)
        guard result == .failure,
              let buffer = diskBuffer,
              !spans.isEmpty else { return result }
        let semaphore = DispatchSemaphore(value: 0)
        // Safe across shutdown: DiskSpanBuffer.persist early-returns when closed.
        Task {
            await buffer.persist(spans, sessionId: sessionId)
            semaphore.signal()
        }
        _ = semaphore.wait(timeout: .now() + persistTimeout)
        return result
    }

    public func flush(explicitTimeout: TimeInterval?) -> SpanExporterResultCode {
        delegate.flush(explicitTimeout: explicitTimeout)
    }

    public func shutdown(explicitTimeout: TimeInterval?) {
        delegate.shutdown(explicitTimeout: explicitTimeout)
    }
}
