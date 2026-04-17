import Foundation
import OpenTelemetrySdk

/// Buffered event wrapper. Holds the upstream OTel `ReadableLogRecord`
/// (`record`) directly so the buffer pipeline is 100% OTel-native — the same
/// type the Android SDK's `BufferedEvent` wraps (`LogRecordData`). On flush,
/// we pass `[record]` to an OTel `LogRecordExporter` without custom encoding.
///
/// The legacy `eventData` field is retained (empty by default) for backward
/// compatibility with tests and a future disk-spill path that will need a
/// stable wire format — but the production RAM path does not read it.
public struct BufferedEvent: Sendable {
    public let sequenceId: UInt64
    public let timestampMs: UInt64
    public let sessionId: String
    /// OTel log record. Non-nil for records that went through `MobileLogRecordProcessor.onEmit`.
    public let record: ReadableLogRecord?
    public let eventData: Data
    public let sizeBytes: Int
    public let createdAt: Date

    public init(
        sequenceId: UInt64,
        timestampMs: UInt64,
        sessionId: String,
        record: ReadableLogRecord? = nil,
        eventData: Data = Data(),
        createdAt: Date = Date()
    ) {
        self.sequenceId = sequenceId
        self.timestampMs = timestampMs
        self.sessionId = sessionId
        self.record = record
        self.eventData = eventData
        self.sizeBytes = eventData.count
        self.createdAt = createdAt
    }
}

extension BufferedEvent: Equatable {
    public static func == (lhs: BufferedEvent, rhs: BufferedEvent) -> Bool {
        // Compare by stable identity fields; ReadableLogRecord isn't Equatable
        // in the OTel-Swift API, so we exclude it from the comparison. Tests
        // that care about record contents should inspect .record directly.
        lhs.sequenceId == rhs.sequenceId &&
            lhs.timestampMs == rhs.timestampMs &&
            lhs.sessionId == rhs.sessionId &&
            lhs.eventData == rhs.eventData &&
            lhs.sizeBytes == rhs.sizeBytes &&
            lhs.createdAt == rhs.createdAt
    }
}
