import Foundation

/// Disk-persisted OTLP trace request body. When the in-memory OTLP/HTTP
/// trace exporter's underlying POST fails (network error, 5xx, 429), the
/// raw serialized request body is spilled to disk so the next process
/// launch can replay it.
///
/// We persist the body but NOT the original endpoint or headers. Routing
/// decisions (where to send) and credentials (auth, dataset) come from
/// the user's CURRENT `MobileConfig` at recovery time. This is correct
/// for the realistic lifecycle events the obvious "store everything"
/// design fails at: token rotation, region migration, dataset rename, or
/// fixing a typo'd endpoint between the failed-export launch and the
/// recovery launch. The body is byte-identical OTLP protobuf, so the
/// collector receives the original spans regardless of where the request
/// is now addressed.
///
/// `id` is the sqlite rowid; only meaningful on reads. `requestKey` is a
/// UUID generated at persist time — not derived from body bytes, because
/// legitimate retries can produce byte-identical payloads that deserve
/// their own rows.
public struct BufferedSpanRequest: Sendable {
    public let id: Int64
    public let requestKey: String
    public let body: Data
    public let sessionId: String
    public let sizeBytes: Int
    public let createdAt: Date

    public init(
        id: Int64 = 0,
        requestKey: String,
        body: Data,
        sessionId: String,
        createdAt: Date = Date()
    ) {
        self.id = id
        self.requestKey = requestKey
        self.body = body
        self.sessionId = sessionId
        self.sizeBytes = body.count
        self.createdAt = Date(timeIntervalSince1970: max(0, createdAt.timeIntervalSince1970))
    }

    /// Build a fresh `BufferedSpanRequest` for a pending POST. Assigns a
    /// UUID request key so concurrent retries of byte-identical payloads
    /// each get their own row.
    public static func pending(
        body: Data,
        sessionId: String
    ) -> BufferedSpanRequest {
        BufferedSpanRequest(
            requestKey: UUID().uuidString,
            body: body,
            sessionId: sessionId
        )
    }
}

extension BufferedSpanRequest: Equatable {
    public static func == (lhs: BufferedSpanRequest, rhs: BufferedSpanRequest) -> Bool {
        lhs.requestKey == rhs.requestKey
            && lhs.body == rhs.body
            && lhs.sessionId == rhs.sessionId
    }
}
