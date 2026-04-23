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

    @Test("fetchAll returns rows ordered by id ascending")
    func fetchAllOrdered() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        let a = DiskSpanBufferTestSupport.fakeSpan(name: "span.a")
        let b = DiskSpanBufferTestSupport.fakeSpan(name: "span.b")
        await buffer.persist([a, b], sessionId: "sess-1")

        let rows = await buffer.fetchAll(limit: 100)
        #expect(rows.count == 2)
        #expect(rows[0].id < rows[1].id)
        #expect(rows[0].record?.name == "span.a")
    }

    @Test("fetchAll honors the limit")
    func fetchAllRespectsLimit() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        for i in 0..<5 {
            await buffer.persist([DiskSpanBufferTestSupport.fakeSpan(name: "span.\(i)")], sessionId: "sess-1")
        }
        let rows = await buffer.fetchAll(limit: 2)
        #expect(rows.count == 2)
        #expect(rows[0].record?.name == "span.0")
    }

    @Test("deleteUpTo removes rows with id <= anchor")
    func deleteUpToAnchor() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        for i in 0..<4 {
            await buffer.persist([DiskSpanBufferTestSupport.fakeSpan(name: "span.\(i)")], sessionId: "sess-1")
        }
        let rows = await buffer.fetchAll(limit: 10)
        let anchor = rows[1].id
        await buffer.deleteUpTo(id: anchor)

        let remaining = await buffer.fetchAll(limit: 10)
        #expect(remaining.count == 2)
        #expect(remaining.allSatisfy { $0.id > anchor })
    }

    @Test("fetchAll returns nil record on corrupt row but preserves recordData")
    func fetchAllCorruptRow() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)

        await buffer.persist([DiskSpanBufferTestSupport.fakeSpan(name: "span.corrupt")],
                             sessionId: "sess-1")
        await buffer.shutdown()

        // Corrupt the row's JSON blob via a side-channel connection.
        let garbage: [UInt8] = [0xFF, 0xFE, 0xFD]
        DiskSpanBufferTestSupport.overwriteRecordJson(dbPath: dbPath, bytes: garbage)

        let reopened = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await reopened.shutdown() } }

        let rows = await reopened.fetchAll(limit: 10)
        #expect(rows.count == 1)
        #expect(rows[0].record == nil)
        #expect(DiskSpanBufferTestSupport.recordDataMatches(rows[0], bytes: garbage))
    }
}
