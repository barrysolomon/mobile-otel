import Foundation
import Testing
import OpenTelemetrySdk
@testable import OTelMobileSDK

@Suite("PersistingSpanExporter")
struct PersistingSpanExporterTests {
    @Test("delegate success: no disk write")
    func successNoWrite() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        let delegate = StubSpanExporter(result: .success)
        let exporter = PersistingSpanExporter(delegate: delegate, diskBuffer: buffer, sessionId: "s")

        let result = exporter.export(
            spans: [DiskSpanBufferTestSupport.fakeSpan(name: "ok")],
            explicitTimeout: nil)
        #expect(result == .success)
        #expect(await buffer.rowCount() == 0)
    }

    @Test("delegate failure: spans written to disk")
    func failureWritesDisk() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        let delegate = StubSpanExporter(result: .failure)
        let exporter = PersistingSpanExporter(delegate: delegate, diskBuffer: buffer, sessionId: "s")

        let result = exporter.export(
            spans: [DiskSpanBufferTestSupport.fakeSpan(name: "a"),
                    DiskSpanBufferTestSupport.fakeSpan(name: "b")],
            explicitTimeout: nil)
        #expect(result == .failure)
        #expect(await buffer.rowCount() == 2)
    }

    @Test("nil diskBuffer: passthrough, no crash")
    func nilBufferPassthrough() async throws {
        let delegate = StubSpanExporter(result: .failure)
        let exporter = PersistingSpanExporter(delegate: delegate, diskBuffer: nil, sessionId: "s")
        let result = exporter.export(
            spans: [DiskSpanBufferTestSupport.fakeSpan(name: "x")],
            explicitTimeout: nil)
        #expect(result == .failure)
    }

    @Test("empty span list: never writes even on failure")
    func emptyListNoWrite() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        let delegate = StubSpanExporter(result: .failure)
        let exporter = PersistingSpanExporter(delegate: delegate, diskBuffer: buffer, sessionId: "s")
        _ = exporter.export(spans: [], explicitTimeout: nil)
        #expect(await buffer.rowCount() == 0)
    }

    @Test("flush and shutdown pass through")
    func flushShutdownPassthrough() async throws {
        let delegate = StubSpanExporter(result: .success)
        let exporter = PersistingSpanExporter(delegate: delegate, diskBuffer: nil, sessionId: "s")
        #expect(exporter.flush(explicitTimeout: nil) == .success)
        exporter.shutdown(explicitTimeout: nil)
        #expect(delegate.shutdownCalled)
    }
}

final class StubSpanExporter: SpanExporter, @unchecked Sendable {
    let result: SpanExporterResultCode
    private(set) var shutdownCalled = false
    init(result: SpanExporterResultCode) { self.result = result }
    func export(spans: [SpanData], explicitTimeout: TimeInterval?) -> SpanExporterResultCode { result }
    func flush(explicitTimeout: TimeInterval?) -> SpanExporterResultCode { result }
    func shutdown(explicitTimeout: TimeInterval?) { shutdownCalled = true }
}
