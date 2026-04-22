import Foundation
import OpenTelemetrySdk

/// Disk-persisted span wrapper. Mirrors `BufferedEvent` but carries the
/// upstream `SpanData` (Codable) instead of `ReadableLogRecord`. Used by
/// `DiskSpanBuffer` for fail-to-disk persistence when the OTLP trace
/// exporter returns `.failure`.
///
/// `id` is the sqlite rowid; only meaningful on reads. `spanKey` is the
/// dedup unique index (traceId hex + spanId hex) — prevents duplicate
/// disk rows when the same batch is re-presented during RetryableExporter
/// backoffs or crash-safety mid-persist.
public struct BufferedSpan: Sendable {
    public let id: Int64
    public let spanKey: String
    public let startTimeUnixNano: UInt64
    public let sessionId: String
    public let record: SpanData?
    public let recordData: Data
    public let sizeBytes: Int
    public let createdAt: Date

    public init(
        id: Int64 = 0,
        spanKey: String,
        startTimeUnixNano: UInt64,
        sessionId: String,
        record: SpanData? = nil,
        recordData: Data = Data(),
        createdAt: Date = Date()
    ) {
        self.id = id
        self.spanKey = spanKey
        self.startTimeUnixNano = startTimeUnixNano
        self.sessionId = sessionId
        self.record = record
        self.recordData = recordData
        self.sizeBytes = recordData.count
        self.createdAt = createdAt
    }

    /// Build a `BufferedSpan` from an upstream `SpanData`. Encodes the
    /// record to JSON for disk persistence. Returns `nil` if encoding
    /// fails — per SDK_SAFETY.md, buffer malfunction must never crash
    /// the host.
    public static func from(
        _ span: SpanData,
        sessionId: String,
        encoder: JSONEncoder = JSONEncoder()
    ) -> BufferedSpan? {
        guard let data = try? encoder.encode(span) else { return nil }
        let key = span.traceId.hexString + span.spanId.hexString
        let secs = max(0, span.startTime.timeIntervalSince1970)
        let startNs = UInt64(secs * 1_000_000_000)
        return BufferedSpan(
            spanKey: key,
            startTimeUnixNano: startNs,
            sessionId: sessionId,
            record: span,
            recordData: data
        )
    }
}

extension BufferedSpan: Equatable {
    public static func == (lhs: BufferedSpan, rhs: BufferedSpan) -> Bool {
        lhs.spanKey == rhs.spanKey && lhs.recordData == rhs.recordData
    }
}
