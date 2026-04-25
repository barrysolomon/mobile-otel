import Foundation
import Testing
import OpenTelemetryProtocolExporterHttp
@testable import OTelMobileSDK

@Suite("OTelMobile span recovery")
struct OTelMobileSpanRecoveryTests {
    private static let testEndpoint = URL(string: "https://collector.example.com/v1/traces")!
    private static let testHeaders: [String: String] = [
        "Content-Type": "application/x-protobuf",
        "Content-Encoding": "gzip",
        "Authorization": "Bearer test-token",
        "Dash0-Dataset": "test-dataset",
    ]

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
            from: buffer,
            endpoint: Self.testEndpoint,
            headers: Self.testHeaders,
            httpClient: client,
            batchSize: 2)

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
            from: buffer,
            endpoint: Self.testEndpoint,
            headers: Self.testHeaders,
            httpClient: client,
            batchSize: 64)

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
            from: buffer,
            endpoint: Self.testEndpoint,
            headers: Self.testHeaders,
            httpClient: client,
            batchSize: 64)

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
            from: buffer,
            endpoint: Self.testEndpoint,
            headers: Self.testHeaders,
            httpClient: client,
            batchSize: 64)

        #expect(replayed == 0)
        #expect(client.capturedRequests.isEmpty)
    }

    @Test("replay routes to caller-supplied endpoint + headers, body from disk")
    func replayUsesCurrentConfigForRouting() async throws {
        // Regression for the design change from captured-endpoint to
        // current-configured. The original failed export's destination
        // and credentials are NOT preserved through replay; what's
        // preserved is the body (the actual telemetry payload). Routing
        // comes from whatever the caller passes in — driven by the live
        // MobileConfig at the recovery launch.
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        let original = DiskSpanBufferTestSupport.fakeRequest(
            bodyBytes: [0xDE, 0xAD, 0xBE, 0xEF])
        await buffer.persist(original)

        // Caller supplies a NEW endpoint + headers — simulates the user
        // having rotated tokens or migrated regions between launches.
        let newEndpoint = URL(string: "https://eu-central-1.collector.example.com/v1/traces")!
        let newHeaders: [String: String] = [
            "Content-Type": "application/x-protobuf",
            "Content-Encoding": "gzip",
            "Authorization": "Bearer NEW-token",
            "Dash0-Dataset": "new-dataset",
        ]

        let resp = HTTPURLResponse(url: URL(string: "https://x")!,
                                   statusCode: 200, httpVersion: nil,
                                   headerFields: nil)!
        let client = ReplayCapturingHTTPClient(result: .success(resp))
        _ = await OTelMobile.recoverSpanRequests(
            from: buffer,
            endpoint: newEndpoint,
            headers: newHeaders,
            httpClient: client,
            batchSize: 64)

        #expect(client.capturedRequests.count == 1)
        let req = client.capturedRequests[0]
        // Replay POSTs to the NEW endpoint + carries the NEW credentials.
        #expect(req.url?.absoluteString == "https://eu-central-1.collector.example.com/v1/traces")
        #expect(req.value(forHTTPHeaderField: "Authorization") == "Bearer NEW-token")
        #expect(req.value(forHTTPHeaderField: "Dash0-Dataset") == "new-dataset")
        // Body comes from disk byte-identical — telemetry payload survives.
        #expect(req.httpBody == Data([0xDE, 0xAD, 0xBE, 0xEF]))
        #expect(req.httpMethod == "POST")
    }

    @Test("buildReplayHeaders produces full header map for replay")
    func buildReplayHeadersShape() {
        let headers = OTelMobile.buildReplayHeaders(
            authToken: "abc",
            extraHeaders: ["Dash0-Dataset": "ds-1", "X-Custom": "v"])
        #expect(headers["Authorization"] == "Bearer abc")
        #expect(headers["Content-Type"] == "application/x-protobuf")
        #expect(headers["Content-Encoding"] == "gzip")
        #expect(headers["Dash0-Dataset"] == "ds-1")
        #expect(headers["X-Custom"] == "v")
    }

    @Test("buildReplayHeaders omits Authorization when authToken is nil")
    func buildReplayHeadersNoAuth() {
        let headers = OTelMobile.buildReplayHeaders(
            authToken: nil, extraHeaders: [:])
        #expect(headers["Authorization"] == nil)
        #expect(headers["Content-Type"] == "application/x-protobuf")
    }

    @Test("buildReplayHeaders omits Authorization when authToken is empty string")
    func buildReplayHeadersEmptyAuth() {
        let headers = OTelMobile.buildReplayHeaders(
            authToken: "", extraHeaders: [:])
        #expect(headers["Authorization"] == nil)
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
