import Foundation

public struct BufferedEvent: Sendable, Equatable {
    public let sequenceId: UInt64
    public let timestampMs: UInt64
    public let sessionId: String
    public let eventData: Data      // serialized OTLP LogRecord (protobuf) — opaque to the buffer
    public let sizeBytes: Int
    public let createdAt: Date

    public init(sequenceId: UInt64, timestampMs: UInt64, sessionId: String, eventData: Data, createdAt: Date = Date()) {
        self.sequenceId = sequenceId
        self.timestampMs = timestampMs
        self.sessionId = sessionId
        self.eventData = eventData
        self.sizeBytes = eventData.count
        self.createdAt = createdAt
    }
}
