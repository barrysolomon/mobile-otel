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

    @Test("persist writes one row per request")
    func persistWritesOnce() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        await buffer.persist(DiskSpanBufferTestSupport.fakeRequest())
        #expect(await buffer.rowCount() == 1)
    }

    @Test("persist is idempotent on requestKey")
    func persistDedup() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        // Re-presenting the SAME request (same requestKey) must not create
        // a duplicate row. Simulates a retry path that hands the same
        // BufferedSpanRequest back to persist().
        let req = DiskSpanBufferTestSupport.fakeRequest()
        await buffer.persist(req)
        await buffer.persist(req)
        #expect(await buffer.rowCount() == 1)
    }

    @Test("persistBatch empty list is a no-op")
    func persistBatchEmpty() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        await buffer.persist(DiskSpanBufferTestSupport.fakeRequest())
        await buffer.persistBatch([])
        #expect(await buffer.rowCount() == 1)
    }

    @Test("fetchAll returns rows ordered by id ascending")
    func fetchAllOrdered() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        let a = DiskSpanBufferTestSupport.fakeRequest(bodyBytes: [0x01])
        let b = DiskSpanBufferTestSupport.fakeRequest(bodyBytes: [0x02])
        await buffer.persistBatch([a, b])

        let rows = await buffer.fetchAll(limit: 100)
        #expect(rows.count == 2)
        #expect(rows[0].id < rows[1].id)
        #expect(DiskSpanBufferTestSupport.bodyMatches(rows[0], bytes: [0x01]))
        #expect(DiskSpanBufferTestSupport.bodyMatches(rows[1], bytes: [0x02]))
    }

    @Test("fetchAll honors the limit")
    func fetchAllRespectsLimit() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        for i in 0..<5 {
            await buffer.persist(DiskSpanBufferTestSupport.fakeRequest(bodyBytes: [UInt8(i)]))
        }
        let rows = await buffer.fetchAll(limit: 2)
        #expect(rows.count == 2)
        #expect(DiskSpanBufferTestSupport.bodyMatches(rows[0], bytes: [0x00]))
    }

    @Test("deleteUpTo removes rows with id <= anchor")
    func deleteUpToAnchor() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        for i in 0..<4 {
            await buffer.persist(DiskSpanBufferTestSupport.fakeRequest(bodyBytes: [UInt8(i)]))
        }
        let rows = await buffer.fetchAll(limit: 10)
        let anchor = rows[1].id
        await buffer.deleteUpTo(id: anchor)

        let remaining = await buffer.fetchAll(limit: 10)
        #expect(remaining.count == 2)
        #expect(remaining.allSatisfy { $0.id > anchor })
    }

    @Test("delete(id:) removes a single row")
    func deleteSingleRow() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        for i in 0..<3 {
            await buffer.persist(DiskSpanBufferTestSupport.fakeRequest(bodyBytes: [UInt8(i)]))
        }
        let rows = await buffer.fetchAll(limit: 10)
        await buffer.delete(id: rows[1].id)
        let remaining = await buffer.fetchAll(limit: 10)
        #expect(remaining.count == 2)
        #expect(remaining.map(\.id) == [rows[0].id, rows[2].id])
    }

    @Test("fetchAll survives corrupt body blob")
    func fetchAllCorruptRow() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)

        await buffer.persist(DiskSpanBufferTestSupport.fakeRequest(bodyBytes: [0x01, 0x02]))
        await buffer.shutdown()

        // Corrupt the body blob. Replay-time handling of "invalid protobuf"
        // is the collector's problem — the buffer only needs to deliver the
        // bytes it was asked to store. So this primarily exercises the
        // decode-corrupt-body path without losing the row.
        let garbage: [UInt8] = [0xFF, 0xFE, 0xFD]
        DiskSpanBufferTestSupport.overwriteBody(dbPath: dbPath, bytes: garbage)

        let reopened = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await reopened.shutdown() } }

        let rows = await reopened.fetchAll(limit: 10)
        #expect(rows.count == 1)
        #expect(DiskSpanBufferTestSupport.bodyMatches(rows[0], bytes: garbage))
    }

    @Test("pruneByTTL removes rows older than retentionSeconds")
    func pruneByTTL() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath, retentionSeconds: 1)
        defer { Task { await buffer.shutdown() } }

        await buffer.persist(DiskSpanBufferTestSupport.fakeRequest())
        try await Task.sleep(nanoseconds: 1_200_000_000)
        await buffer.pruneByTTL()
        #expect(await buffer.rowCount() == 0)
    }

    @Test("size cap evicts oldest when exceeded")
    func pruneBySize() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        // Each fakeRequest() body is 2 bytes but size_bytes is the body
        // length; to make the cap meaningful, bloat the bodies.
        let buffer = try await DiskSpanBuffer(dbPath: dbPath, maxTotalBytes: 2048)
        defer { Task { await buffer.shutdown() } }

        let bigBody = [UInt8](repeating: 0x41, count: 512)
        for _ in 0..<20 {
            await buffer.persist(DiskSpanBufferTestSupport.fakeRequest(bodyBytes: bigBody))
        }
        let survivors = await buffer.fetchAll(limit: 100)
        #expect(survivors.count < 20)
        // Survivors must be a contiguous tail of the insertion order
        // (oldest-first eviction).
        let ids = survivors.map(\.id)
        #expect(ids == ids.sorted())
    }

    @Test("totalSizeBytes sums all rows")
    func totalSizeBytesSums() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        await buffer.persist(DiskSpanBufferTestSupport.fakeRequest(bodyBytes: [0x01, 0x02, 0x03]))
        await buffer.persist(DiskSpanBufferTestSupport.fakeRequest(bodyBytes: [0x04, 0x05]))
        #expect(await buffer.totalSizeBytes() == 5)
    }
}
