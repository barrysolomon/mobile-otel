import Testing
@testable import OTelMobileSDK
import OTelMobileCore

// MARK: - Mocks

fileprivate final class OfflineReconnectStubSession: SessionProvider, @unchecked Sendable {
    var sessionId: String { "offline-session" }
    func rotateSession() -> String { "offline-session" }
}

/// Mock exporter that can be flipped between failing (offline) and succeeding (online).
/// Mirrors Android's `MockLogRecordExporter.shouldFail` semantics.
fileprivate actor TogglingExporter: BufferedEventExporter {
    private(set) var received: [BufferedEvent] = []
    private(set) var callCount: Int = 0
    private var shouldFail: Bool = false

    func setOffline(_ offline: Bool) { shouldFail = offline }

    func export(_ events: [BufferedEvent]) async -> BufferExportResult {
        callCount += 1
        if shouldFail {
            return .failure(reason: "offline")
        }
        received.append(contentsOf: events)
        return .success
    }
}

// MARK: - Suite

/// PR-012 (iOS parity): Offline → online reconnection integration test.
///
/// The iOS contract differs from Android in mechanism but matches in promise:
/// **events emitted while offline must export on reconnection**.
///
/// - Android keeps events in the RAM buffer when export fails.
/// - iOS drains the RAM buffer unconditionally and persists to **disk** when
///   export fails (see `MobileLogRecordProcessor.forceFlushBuffered` failure-
///   persistence contract). On reconnection, the next flush combines RAM +
///   disk contents and exports them, deduping by `sequenceId`.
///
/// These tests configure a disk-backed processor and verify the iOS
/// offline-survives-reconnect promise end-to-end.
@Suite("OfflineReconnection")
struct OfflineReconnectionTests {

    private func makeProcessor() async throws -> (
        MobileLogRecordProcessor,
        TogglingExporter,
        DiskLogBuffer,
        DiskLogBuffer.TestPath
    ) {
        let buffer = RAMEventBuffer(capacity: 100)
        let exporter = TogglingExporter()
        let diskPath = DiskLogBuffer.makeTestPath()
        let disk = try await DiskLogBuffer.makeForTesting(path: diskPath)
        let processor = MobileLogRecordProcessor(
            buffer: buffer,
            exporter: exporter,
            sessionProvider: OfflineReconnectStubSession(),
            diskBuffer: disk
        )
        return (processor, exporter, disk, diskPath)
    }

    private func cleanup(_ disk: DiskLogBuffer, path: DiskLogBuffer.TestPath) async {
        await disk.shutdown()
        DiskLogBuffer.removeTestFiles(at: path)
    }

    @Test("events buffered offline export after reconnection")
    func bufferOfflineThenExport() async throws {
        let (processor, exporter, disk, path) = try await makeProcessor()
        defer { Task { await cleanup(disk, path: path) } }

        // Phase 1: offline. Emit 10 events, flush — export fails, events
        // land on disk (iOS failure-persistence contract).
        await exporter.setOffline(true)
        for i in 0..<10 {
            await processor.emitForTesting(body: "offline.\(i)")
        }
        let offlineResult = await processor.forceFlushBufferedAsync()
        if case .success = offlineResult {
            Issue.record("forceFlush should fail while offline")
        }
        let exportedWhileOffline = await exporter.received.count
        #expect(exportedWhileOffline == 0)

        // After failed flush, events must be persisted to disk so they
        // survive the offline window.
        let diskCount = await disk.rowCount()
        #expect(diskCount == 10, "events must land on disk after failed export")

        // Phase 2: reconnect. Same events flush out cleanly from disk.
        await exporter.setOffline(false)
        let onlineResult = await processor.forceFlushBufferedAsync()
        #expect(onlineResult == .success)

        let exportedAfter = await exporter.received.count
        #expect(exportedAfter == 10, "all offline-buffered events must export on reconnection")

        // Disk should be drained on successful export.
        let diskAfter = await disk.rowCount()
        #expect(diskAfter == 0)
    }

    @Test("events emitted offline do not duplicate on reconnection")
    func noDuplicatesOnReconnect() async throws {
        let (processor, exporter, disk, path) = try await makeProcessor()
        defer { Task { await cleanup(disk, path: path) } }

        await exporter.setOffline(true)
        for i in 0..<5 {
            await processor.emitForTesting(body: "dedup.\(i)")
        }
        // Failing flush persists events to disk.
        _ = await processor.forceFlushBufferedAsync()

        // Reconnect; combineWithDisk dedupes by sequenceId.
        await exporter.setOffline(false)
        _ = await processor.forceFlushBufferedAsync()

        let received = await exporter.received
        let uniqueIds = Set(received.map { $0.sequenceId })
        #expect(uniqueIds.count == 5)
        #expect(received.count == 5, "each event must export exactly once")
    }

    @Test("flushWindow works after reconnection for time-bounded export")
    func flushWindowAfterReconnect() async throws {
        let (processor, exporter, disk, path) = try await makeProcessor()
        defer { Task { await cleanup(disk, path: path) } }

        await exporter.setOffline(true)
        for i in 0..<5 {
            await processor.emitForTesting(body: "window.\(i)")
        }
        // Failed forceFlush persists events to disk.
        _ = await processor.forceFlushBufferedAsync()

        // Reconnect and flush a 5-minute window — should drain disk too.
        await exporter.setOffline(false)
        let result = await processor.flushWindow(minutes: 5)
        #expect(result == .success)

        let exportedCount = await exporter.received.count
        #expect(exportedCount >= 1, "windowed flush should drain recently-buffered events")
    }

    @Test("offline → online with intermittent failures eventually drains")
    func intermittentRecovery() async throws {
        let (processor, exporter, disk, path) = try await makeProcessor()
        defer { Task { await cleanup(disk, path: path) } }

        // First batch — offline, persists to disk.
        await exporter.setOffline(true)
        for i in 0..<3 {
            await processor.emitForTesting(body: "intermittent.a.\(i)")
        }
        _ = await processor.forceFlushBufferedAsync()

        // Brief reconnection — disk drains.
        await exporter.setOffline(false)
        _ = await processor.forceFlushBufferedAsync()
        let firstBatchExported = await exporter.received.count
        #expect(firstBatchExported == 3)
        let diskAfterFirstDrain = await disk.rowCount()
        #expect(diskAfterFirstDrain == 0)

        // Second offline window.
        await exporter.setOffline(true)
        for i in 0..<4 {
            await processor.emitForTesting(body: "intermittent.b.\(i)")
        }
        _ = await processor.forceFlushBufferedAsync()
        let stillFirstBatch = await exporter.received.count
        #expect(stillFirstBatch == 3, "no exports while offline")

        // Final reconnection — second batch drains from disk.
        await exporter.setOffline(false)
        _ = await processor.forceFlushBufferedAsync()
        let totalExported = await exporter.received.count
        #expect(totalExported == 7, "every event must export exactly once across reconnects")
    }
}
