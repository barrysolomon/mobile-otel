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
}
