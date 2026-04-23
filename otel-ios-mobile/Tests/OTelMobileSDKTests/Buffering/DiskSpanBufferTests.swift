import Testing
@testable import OTelMobileSDK

@Suite("DiskSpanBuffer")
struct DiskSpanBufferTests {
    @Test("open creates database file and table")
    func openCreatesDatabase() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }

        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        let rowCount = await buffer.rowCount()
        #expect(rowCount == 0)
        await buffer.shutdown()

        #expect(DiskSpanBufferTestSupport.fileExists(dbPath))
    }

    @Test("shutdown is idempotent")
    func shutdownIdempotent() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }

        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        await buffer.shutdown()
        await buffer.shutdown()
    }

    @Test("persist writes each span once")
    func persistWritesOnce() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        let span = DiskSpanBufferTestSupport.fakeSpan(name: "span.a")
        await buffer.persist([span], sessionId: "sess-1")
        #expect(await buffer.rowCount() == 1)
    }

    @Test("persist is idempotent on dedup key")
    func persistDedup() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        let span = DiskSpanBufferTestSupport.fakeSpan(name: "span.a")
        await buffer.persist([span], sessionId: "sess-1")
        await buffer.persist([span], sessionId: "sess-1")
        #expect(await buffer.rowCount() == 1)
    }

    @Test("persist empty list is a no-op")
    func persistEmpty() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        let span = DiskSpanBufferTestSupport.fakeSpan(name: "span.a")
        await buffer.persist([span], sessionId: "sess-1")
        await buffer.persist([], sessionId: "sess-1")
        #expect(await buffer.rowCount() == 1)
    }
}
