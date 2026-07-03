import Foundation

/// SDK-internal exporter abstraction used by tests that need direct access
/// to `BufferedEvent` (sequence ID, raw payload, etc.) — fields the upstream
/// OTel `LogRecordExporter` doesn't expose. Production wiring uses the OTel
/// path exclusively via `MobileLogRecordProcessor.init(buffer:otelExporter:...)`;
/// this protocol exists only so tests can introspect the buffer at the
/// `BufferedEvent` granularity (e.g., asserting seqId-based dedup).
///
/// New tests should prefer the OTel-native `RecordingLogExporter`-style mock
/// pattern shown in `HybridHttpErrorFlushTests`. Only reach for this protocol
/// when the test genuinely depends on `BufferedEvent` fields the OTel record
/// doesn't have.
internal protocol BufferedEventExporter: Sendable {
    func export(_ events: [BufferedEvent]) async -> BufferExportResult
}

/// Result of a buffered-event export attempt. Named to avoid collision with
/// OTel-Swift's top-level `ExportResult` enum, which `MobileLogRecordProcessor`
/// must also produce for the `LogRecordProcessor` protocol.
///
/// Public because `MobileLogRecordProcessor.flushWindow` returns it on the
/// public API surface; the protocol above stays internal.
public enum BufferExportResult: Sendable, Equatable {
    case success
    case failure(reason: String)
}

/// Exporter that drops everything. Used by the crash-loop guard's inert
/// `OTelMobile.start(config:)` path — the instance must be constructible and
/// safe to call, but nothing may leave the device.
internal struct NoopBufferedEventExporter: BufferedEventExporter {
    func export(_ events: [BufferedEvent]) async -> BufferExportResult { .success }
}
