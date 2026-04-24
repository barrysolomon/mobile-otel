import Foundation
import Testing
import OpenTelemetryProtocolExporterHttp
@testable import OTelMobileSDK

@Suite("OTelMobile span recovery")
struct OTelMobileSpanRecoveryTests {
    @Test("replays persisted requests and deletes rows on 2xx")
    func replaysAndDeletes() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        for i in 0..<3 {
            await buffer.persist(DiskSpanBufferTestSupport.fakeRequest(
                bodyBytes: [UInt8(i)]))
        }
        #expect(await buffer.rowCount() == 3)

        let resp = HTTPURLResponse(url: URL(string: "https://x")!,
                                   statusCode: 200, httpVersion: nil,
                                   headerFields: nil)!
        let client = ReplayCapturingHTTPClient(result: .success(resp))
        let replayed = await OTelMobile.recoverSpanRequests(
            from: buffer, httpClient: client, batchSize: 2)

        #expect(replayed == 3)
        #expect(client.capturedRequests.count == 3)
        #expect(await buffer.rowCount() == 0)
    }

    @Test("on 5xx: row remains, loop stops after first retryable failure")
    func retryableFailureRetainsRows() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        await buffer.persistBatch([
            DiskSpanBufferTestSupport.fakeRequest(bodyBytes: [0x01]),
            DiskSpanBufferTestSupport.fakeRequest(bodyBytes: [0x02])
        ])

        let resp = HTTPURLResponse(url: URL(string: "https://x")!,
                                   statusCode: 503, httpVersion: nil,
                                   headerFields: nil)!
        let client = ReplayCapturingHTTPClient(result: .success(resp))
        let replayed = await OTelMobile.recoverSpanRequests(
            from: buffer, httpClient: client, batchSize: 64)

        #expect(replayed == 0)
        #expect(await buffer.rowCount() == 2)
        // The implementation stops after the FIRST retryable failure, so
        // only one request was attempted — keeps a dead network from
        // hammering the user's bandwidth.
        #expect(client.capturedRequests.count == 1)
    }

    @Test("on 400: row is dropped (client error won't succeed later)")
    func nonRetryableFailureDropsRow() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        await buffer.persist(DiskSpanBufferTestSupport.fakeRequest())
        let resp = HTTPURLResponse(url: URL(string: "https://x")!,
                                   statusCode: 400, httpVersion: nil,
                                   headerFields: nil)!
        let client = ReplayCapturingHTTPClient(result: .success(resp))
        let replayed = await OTelMobile.recoverSpanRequests(
            from: buffer, httpClient: client, batchSize: 64)

        // Not counted as replayed (not successful), but row is gone —
        // prevents disk accumulation of permanently-bad requests.
        #expect(replayed == 0)
        #expect(await buffer.rowCount() == 0)
    }

    @Test("empty buffer: no HTTP calls, returns 0")
    func emptyBufferNoop() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        let resp = HTTPURLResponse(url: URL(string: "https://x")!,
                                   statusCode: 200, httpVersion: nil,
                                   headerFields: nil)!
        let client = ReplayCapturingHTTPClient(result: .success(resp))
        let replayed = await OTelMobile.recoverSpanRequests(
            from: buffer, httpClient: client, batchSize: 64)

        #expect(replayed == 0)
        #expect(client.capturedRequests.isEmpty)
    }

    @Test("replay preserves original endpoint, headers, and body bytes")
    func replayPreservesRequestShape() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        let original = DiskSpanBufferTestSupport.fakeRequest(
            bodyBytes: [0xDE, 0xAD, 0xBE, 0xEF],
            endpoint: "https://collector.example.com/v1/traces",
            extraHeaders: ["Authorization": "Bearer xyz", "Dash0-Dataset": "my-dataset"]
        )
        await buffer.persist(original)

        let resp = HTTPURLResponse(url: URL(string: "https://x")!,
                                   statusCode: 200, httpVersion: nil,
                                   headerFields: nil)!
        let client = ReplayCapturingHTTPClient(result: .success(resp))
        _ = await OTelMobile.recoverSpanRequests(
            from: buffer, httpClient: client, batchSize: 64)

        #expect(client.capturedRequests.count == 1)
        let req = client.capturedRequests[0]
        #expect(req.url?.absoluteString == "https://collector.example.com/v1/traces")
        #expect(req.value(forHTTPHeaderField: "Authorization") == "Bearer xyz")
        #expect(req.value(forHTTPHeaderField: "Dash0-Dataset") == "my-dataset")
        #expect(req.httpBody == Data([0xDE, 0xAD, 0xBE, 0xEF]))
        #expect(req.httpMethod == "POST")
    }
}

final class ReplayCapturingHTTPClient: HTTPClient, @unchecked Sendable {
    private let result: Result<HTTPURLResponse, Error>
    var capturedRequests: [URLRequest] = []
    private let lock = NSLock()

    init(result: Result<HTTPURLResponse, Error>) {
        self.result = result
    }

    func send(request: URLRequest,
              completion: @escaping (Result<HTTPURLResponse, Error>) -> Void) {
        lock.lock()
        capturedRequests.append(request)
        lock.unlock()
        completion(result)
    }
}
