import Foundation
import Testing
import OpenTelemetryProtocolExporterHttp
import OTelMobileCore
@testable import OTelMobileSDK

@Suite("PersistingTraceHTTPClient")
struct PersistingTraceHTTPClientTests {

    // MARK: - shouldPersist decision matrix

    @Test("shouldPersist: .failure (network error) → true")
    func shouldPersistOnNetworkError() {
        let err = URLError(.notConnectedToInternet)
        #expect(PersistingTraceHTTPClient.shouldPersist(result: .failure(err)) == true)
    }

    @Test("shouldPersist: HTTP 200 → false")
    func shouldPersistOn200() {
        let resp = HTTPURLResponse(url: URL(string: "https://x")!, statusCode: 200, httpVersion: nil, headerFields: nil)!
        #expect(PersistingTraceHTTPClient.shouldPersist(result: .success(resp)) == false)
    }

    @Test("shouldPersist: HTTP 400 → false (client bug, replay won't help)")
    func shouldPersistOn400() {
        let resp = HTTPURLResponse(url: URL(string: "https://x")!, statusCode: 400, httpVersion: nil, headerFields: nil)!
        #expect(PersistingTraceHTTPClient.shouldPersist(result: .success(resp)) == false)
    }

    @Test("shouldPersist: HTTP 401 → false")
    func shouldPersistOn401() {
        let resp = HTTPURLResponse(url: URL(string: "https://x")!, statusCode: 401, httpVersion: nil, headerFields: nil)!
        #expect(PersistingTraceHTTPClient.shouldPersist(result: .success(resp)) == false)
    }

    @Test("shouldPersist: HTTP 429 (rate limit) → true")
    func shouldPersistOn429() {
        let resp = HTTPURLResponse(url: URL(string: "https://x")!, statusCode: 429, httpVersion: nil, headerFields: nil)!
        #expect(PersistingTraceHTTPClient.shouldPersist(result: .success(resp)) == true)
    }

    @Test("shouldPersist: HTTP 500 → true")
    func shouldPersistOn500() {
        let resp = HTTPURLResponse(url: URL(string: "https://x")!, statusCode: 500, httpVersion: nil, headerFields: nil)!
        #expect(PersistingTraceHTTPClient.shouldPersist(result: .success(resp)) == true)
    }

    @Test("shouldPersist: HTTP 503 → true")
    func shouldPersistOn503() {
        let resp = HTTPURLResponse(url: URL(string: "https://x")!, statusCode: 503, httpVersion: nil, headerFields: nil)!
        #expect(PersistingTraceHTTPClient.shouldPersist(result: .success(resp)) == true)
    }

    // MARK: - Integration with DiskSpanBuffer via stub delegate

    @Test("on network error: body + sessionId persist to disk")
    func networkErrorPersists() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        let delegate = StubHTTPClient(result: .failure(URLError(.cannotFindHost)))
        let provider = StubSessionProvider(sessionId: "test-session")
        let client = PersistingTraceHTTPClient(
            delegate: delegate, diskBuffer: buffer, sessionProvider: provider)

        // Endpoint and headers on the URLRequest are deliberately NOT
        // persisted. The buffer holds only the body + session id, plus
        // the dedup key + timestamps. Replay-time routing comes from
        // the user's current MobileConfig — a deliberate design choice
        // so token rotation, region migration, dataset rename, and
        // typo fixes between launches all do the right thing.
        var req = URLRequest(url: URL(string: "https://collector.example.com/v1/traces")!)
        req.httpMethod = "POST"
        req.setValue("application/x-protobuf", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer abc", forHTTPHeaderField: "Authorization")
        req.httpBody = Data([0xDE, 0xAD, 0xBE, 0xEF])

        let callbackDone = DispatchSemaphore(value: 0)
        client.send(request: req) { result in
            // Completion forwards the same result.
            switch result {
            case .failure: callbackDone.signal()
            case .success: Issue.record("unexpected success")
            }
        }
        _ = callbackDone.wait(timeout: .now() + 2)

        // Give the async persist Task time to run (callback hands off to Task).
        try await Task.sleep(nanoseconds: 200_000_000)

        let rows = await buffer.fetchAll(limit: 10)
        #expect(rows.count == 1)
        #expect(DiskSpanBufferTestSupport.bodyMatches(rows[0], bytes: [0xDE, 0xAD, 0xBE, 0xEF]))
        #expect(rows[0].sessionId == "test-session")
    }

    @Test("on HTTP 200: nothing persists")
    func successDoesNotPersist() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        let resp = HTTPURLResponse(url: URL(string: "https://x")!, statusCode: 200,
                                   httpVersion: nil, headerFields: nil)!
        let delegate = StubHTTPClient(result: .success(resp))
        let client = PersistingTraceHTTPClient(
            delegate: delegate, diskBuffer: buffer,
            sessionProvider: StubSessionProvider())

        var req = URLRequest(url: URL(string: "https://x/v1/traces")!)
        req.httpBody = Data([0x01, 0x02])

        let done = DispatchSemaphore(value: 0)
        client.send(request: req) { _ in done.signal() }
        _ = done.wait(timeout: .now() + 2)
        try await Task.sleep(nanoseconds: 200_000_000)

        #expect(await buffer.rowCount() == 0)
    }

    @Test("empty body is never persisted, even on failure")
    func emptyBodyNotPersisted() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        let delegate = StubHTTPClient(result: .failure(URLError(.timedOut)))
        let client = PersistingTraceHTTPClient(
            delegate: delegate, diskBuffer: buffer,
            sessionProvider: StubSessionProvider())

        var req = URLRequest(url: URL(string: "https://x/v1/traces")!)
        req.httpBody = nil

        let done = DispatchSemaphore(value: 0)
        client.send(request: req) { _ in done.signal() }
        _ = done.wait(timeout: .now() + 2)
        try await Task.sleep(nanoseconds: 200_000_000)

        #expect(await buffer.rowCount() == 0)
    }

    @Test("session id read per-call reflects rotations")
    func sessionIdRotation() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        let delegate = StubHTTPClient(result: .failure(URLError(.cannotFindHost)))
        let provider = StubSessionProvider(sessionId: "sess-A")
        let client = PersistingTraceHTTPClient(
            delegate: delegate, diskBuffer: buffer, sessionProvider: provider)

        var req = URLRequest(url: URL(string: "https://x/v1/traces")!)
        req.httpBody = Data([0x01])

        let done1 = DispatchSemaphore(value: 0)
        client.send(request: req) { _ in done1.signal() }
        _ = done1.wait(timeout: .now() + 2)
        try await Task.sleep(nanoseconds: 200_000_000)

        // Simulate inactivity rotation.
        provider.sessionId = "sess-B"

        var req2 = URLRequest(url: URL(string: "https://x/v1/traces")!)
        req2.httpBody = Data([0x02])
        let done2 = DispatchSemaphore(value: 0)
        client.send(request: req2) { _ in done2.signal() }
        _ = done2.wait(timeout: .now() + 2)
        try await Task.sleep(nanoseconds: 200_000_000)

        let rows = await buffer.fetchAll(limit: 10)
        #expect(rows.count == 2)
        #expect(rows.contains { $0.sessionId == "sess-A" })
        #expect(rows.contains { $0.sessionId == "sess-B" })
    }
}

final class StubHTTPClient: HTTPClient, @unchecked Sendable {
    private let result: Result<HTTPURLResponse, Error>
    var capturedRequests: [URLRequest] = []

    init(result: Result<HTTPURLResponse, Error>) {
        self.result = result
    }

    func send(request: URLRequest,
              completion: @escaping (Result<HTTPURLResponse, Error>) -> Void) {
        capturedRequests.append(request)
        completion(result)
    }
}

final class StubSessionProvider: SessionProvider, @unchecked Sendable {
    var sessionId: String
    init(sessionId: String = "stub-session") { self.sessionId = sessionId }
    func rotateSession() -> String {
        sessionId = UUID().uuidString
        return sessionId
    }
}
