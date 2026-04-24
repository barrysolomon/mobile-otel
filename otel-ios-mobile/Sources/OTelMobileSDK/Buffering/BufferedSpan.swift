import Foundation

/// Disk-persisted OTLP trace request. When the in-memory OTLP/HTTP trace
/// exporter's underlying POST fails (network error, 5xx, 429), the bytes
/// it tried to send are spilled to disk so the next process launch can
/// replay them.
///
/// Storing the raw serialized request body (pre-gzipped protobuf) instead
/// of the decoded `[SpanData]` avoids re-serialization on replay and keeps
/// the collector's view of the payload byte-identical across attempts —
/// good for idempotency and for preserving any adapter-specific encoding
/// choices the upstream exporter made.
///
/// `id` is the sqlite rowid; only meaningful on reads. `requestKey` is a
/// UUID generated at persist time — not derived from body bytes, because
/// legitimate retries can produce byte-identical payloads that deserve
/// their own rows.
public struct BufferedSpanRequest: Sendable {
    public let id: Int64
    public let requestKey: String
    public let endpoint: URL
    public let headers: [String: String]
    public let body: Data
    public let sessionId: String
    public let sizeBytes: Int
    public let createdAt: Date

    public init(
        id: Int64 = 0,
        requestKey: String,
        endpoint: URL,
        headers: [String: String],
        body: Data,
        sessionId: String,
        createdAt: Date = Date()
    ) {
        self.id = id
        self.requestKey = requestKey
        self.endpoint = endpoint
        self.headers = headers
        self.body = body
        self.sessionId = sessionId
        self.sizeBytes = body.count
        self.createdAt = Date(timeIntervalSince1970: max(0, createdAt.timeIntervalSince1970))
    }

    /// Build a fresh `BufferedSpanRequest` for a pending POST. Assigns a
    /// UUID request key so concurrent retries of byte-identical payloads
    /// each get their own row.
    public static func pending(
        endpoint: URL,
        headers: [String: String],
        body: Data,
        sessionId: String
    ) -> BufferedSpanRequest {
        BufferedSpanRequest(
            requestKey: UUID().uuidString,
            endpoint: endpoint,
            headers: headers,
            body: body,
            sessionId: sessionId
        )
    }
}

extension BufferedSpanRequest: Equatable {
    public static func == (lhs: BufferedSpanRequest, rhs: BufferedSpanRequest) -> Bool {
        lhs.requestKey == rhs.requestKey
            && lhs.endpoint == rhs.endpoint
            && lhs.body == rhs.body
            && lhs.sessionId == rhs.sessionId
    }
}
