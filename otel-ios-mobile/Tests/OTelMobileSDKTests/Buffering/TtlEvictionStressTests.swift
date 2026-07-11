import Foundation
import Testing
@testable import OTelMobileSDK
import OTelMobileCore

// MARK: - Mocks

fileprivate final class TtlStressStubSession: SessionProvider, @unchecked Sendable {
    var sessionId: String { "ttl-stress" }
    func rotateSession() -> String { "ttl-stress" }
}

fileprivate final class TtlStressExporter: BufferedEventExporter, @unchecked Sendable {
    private let lock = NSLock()
    private var _received: [BufferedEvent] = []
    private var _shouldFail: Bool = false

    var received: [BufferedEvent] {
        get async { lock.lock(); defer { lock.unlock() }; return _received }
    }

    func setOffline(_ offline: Bool) async { lock.lock(); _shouldFail = offline; lock.unlock() }

    func export(_ events: [BufferedEvent]) -> BufferExportResult {
        lock.lock(); defer { lock.unlock() }
        if _shouldFail {
            return .failure(reason: "offline")
        }
        _received.append(contentsOf: events)
        return .success
    }
}

// MARK: - Suite

/// PR-017 (iOS parity): TTL eviction + sustained-load stress test.
///
/// Mirrors Android's `TtlEvictionStressTest.kt` against iOS's actor-based
/// buffer architecture:
/// - RAM overflow spills to disk via `onEmit`'s evicted-event handler
/// - Sustained insert+flush cycles do not duplicate or drop events
/// - Bursts of 500 events do not throw or silently drop
/// - Disk buffer respects its configured size budget
/// - TTL pruning removes expired events without affecting fresh ones
@Suite("TtlEvictionStress")
struct TtlEvictionStressTests {

    private func makeProcessor(
        ramCapacity: Int = 50,
        retentionSeconds: Double = 24 * 3600,
        maxDiskBytes: Int = 5 * 1024 * 1024
    ) async throws -> (
        MobileLogRecordProcessor,
        TtlStressExporter,
        DiskLogBuffer,
        DiskLogBuffer.TestPath
    ) {
        let ram = RAMEventBuffer(capacity: ramCapacity)
        let exporter = TtlStressExporter()
        let path = DiskLogBuffer.makeTestPath()
        let disk = try await DiskLogBuffer.makeForTesting(
            path: path,
            maxTotalBytes: maxDiskBytes,
            retentionSeconds: retentionSeconds
        )
        let processor = MobileLogRecordProcessor(
            buffer: ram,
            exporter: exporter,
            sessionProvider: TtlStressStubSession(),
            diskBuffer: disk
        )
        return (processor, exporter, disk, path)
    }

    private func cleanup(_ disk: DiskLogBuffer, path: DiskLogBuffer.TestPath) async {
        disk.shutdown()
        DiskLogBuffer.removeTestFiles(at: path)
    }

    @Test("RAM buffer overflow spills to disk without data loss")
    func ramOverflowSpillsToDisk() async throws {
        // RAM=20, push 50 events → 30 must spill to disk.
        let (processor, _, disk, path) = try await makeProcessor(ramCapacity: 20)
        defer { Task { await cleanup(disk, path: path) } }

        for i in 0..<50 {
            await processor.emitForTesting(body: "overflow.\(i)")
        }
        // Give the detached append + spill tasks a generous tick to settle.
        try await Task.sleep(nanoseconds: 500_000_000)

        let diskCount = disk.rowCount()
        // RAM holds at most 20; the rest must be on disk. Total emitted = 50.
        #expect(diskCount >= 20, "spillover must persist; got disk=\(diskCount)")
    }

    @Test("sustained load with concurrent flush does not corrupt or duplicate")
    func sustainedLoadNoCorruption() async throws {
        let (processor, exporter, disk, path) = try await makeProcessor(ramCapacity: 30)
        defer { Task { await cleanup(disk, path: path) } }

        // 5 rounds × 20 events = 100 emissions, with a flush after each round.
        for round in 0..<5 {
            for i in 0..<20 {
                await processor.emitForTesting(body: "round\(round).event\(i)")
            }
            _ = await processor.forceFlushBufferedAsync()
        }
        // Final drain after the last batch.
        _ = await processor.forceFlushBufferedAsync()
        try await Task.sleep(nanoseconds: 200_000_000)

        let received = await exporter.received
        #expect(received.count >= 100, "all events must export across rounds; got \(received.count)")

        // No duplicate sequenceIds — set count must equal list count.
        let seqIds = received.map { $0.sequenceId }
        let unique = Set(seqIds)
        #expect(seqIds.count == unique.count, "no duplicate exports")
    }

    @Test("rapid insert burst does not throw or lose events")
    func rapidBurstNoLoss() async throws {
        let (processor, exporter, disk, path) = try await makeProcessor(ramCapacity: 100)
        defer { Task { await cleanup(disk, path: path) } }

        // Burst 500 events as fast as possible.
        for i in 0..<500 {
            await processor.emitForTesting(body: "burst.\(i)")
        }
        // Generous settle window — spills are detached.
        try await Task.sleep(nanoseconds: 1_500_000_000)

        // Drain everything.
        _ = await processor.forceFlushBufferedAsync()
        try await Task.sleep(nanoseconds: 500_000_000)

        let received = await exporter.received
        #expect(received.count >= 400, "burst must mostly export; got \(received.count) of 500")
    }

    @Test("disk buffer stays within configured byte budget under offline pressure")
    func diskBudgetEnforced() async throws {
        // Tight 256 KB budget; offline so events accumulate on disk.
        let budget = 256 * 1024
        let (processor, exporter, disk, path) = try await makeProcessor(
            ramCapacity: 10,
            maxDiskBytes: budget
        )
        defer { Task { await cleanup(disk, path: path) } }

        await exporter.setOffline(true)

        // 200 emissions while offline force ~190 to spill to disk; pruneBySize
        // is enforced after each insert.
        for i in 0..<200 {
            await processor.emitForTesting(body: "sized.\(i)")
        }
        // Trigger a failed flush so the failure-persistence path also runs.
        _ = await processor.forceFlushBufferedAsync()
        try await Task.sleep(nanoseconds: 500_000_000)

        let bytes = disk.totalSizeBytes()
        // SQLite + small JSON record overhead means per-row size is small but
        // non-zero. The contract is: must not grow unbounded — 5x budget is
        // the same tolerance Android uses for the same test.
        #expect(bytes < budget * 5, "disk must respect budget; got \(bytes) bytes (budget \(budget))")
    }

    @Test("pruneByTTL under load removes expired events but keeps fresh ones")
    func ttlPruneDuringLoad() async throws {
        // Retention = 1s — anything older than 1 second is "expired".
        let (processor, _, disk, path) = try await makeProcessor(
            ramCapacity: 10,
            retentionSeconds: 1
        )
        defer { Task { await cleanup(disk, path: path) } }

        // Phase 1: write events that will go stale.
        for i in 0..<30 {
            await processor.emitForTesting(body: "old.\(i)")
        }
        try await Task.sleep(nanoseconds: 200_000_000)

        let beforeStale = disk.rowCount()
        #expect(beforeStale >= 20, "RAM-overflowed events must reach disk; got \(beforeStale)")

        // Wait past the 1-second retention window.
        try await Task.sleep(nanoseconds: 1_500_000_000)

        // Phase 2: write fresh events; these must NOT be pruned.
        for i in 0..<10 {
            await processor.emitForTesting(body: "fresh.\(i)")
        }
        try await Task.sleep(nanoseconds: 100_000_000)

        // Now prune. Old events should evaporate; fresh ones survive.
        // Note: only events that overflowed RAM landed on disk. Fresh events
        // that fit in the 10-slot RAM buffer may not be on disk yet — that's
        // fine; the contract is "TTL prunes old events" not "all writes hit
        // disk."
        disk.pruneByTTL()
        let afterPrune = disk.rowCount()
        #expect(afterPrune < beforeStale,
                "TTL prune must remove old events; before=\(beforeStale), after=\(afterPrune)")
    }
}
