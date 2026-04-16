import Foundation

/// Thin-slice exporter abstraction. Task 10 replaces this with a concrete
/// `RetryableExporter` wrapping OTel-Swift's `LogRecordExporter`.
public protocol BufferedEventExporter: Sendable {
    func export(_ events: [BufferedEvent]) async -> BufferExportResult
}

/// Result of a buffered-event export attempt. Named to avoid collision with
/// OTel-Swift's top-level `ExportResult` enum, which `MobileLogRecordProcessor`
/// must also produce for the `LogRecordProcessor` protocol.
public enum BufferExportResult: Sendable, Equatable {
    case success
    case failure(reason: String)
}
