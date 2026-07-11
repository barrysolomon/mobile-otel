import Foundation
import Testing
@testable import OTelMobileSDK
import OTelMobileCore

// MARK: - Mocks

fileprivate final class NetworkRestoredStubSession: SessionProvider, @unchecked Sendable {
    var sessionId: String { "network-restored-session" }
    func rotateSession() -> String { "network-restored-session" }
}

fileprivate final class RestoredTogglingExporter: BufferedEventExporter, @unchecked Sendable {
    private let lock = NSLock()
    private var _received: [BufferedEvent] = []
    private var _shouldFail: Bool = false
    var received: [BufferedEvent] {
        get async { lock.lock(); defer { lock.unlock() }; return _received }
    }
    func setOffline(_ offline: Bool) async { lock.lock(); _shouldFail = offline; lock.unlock() }
    func export(_ events: [BufferedEvent]) -> BufferExportResult {
        lock.lock(); defer { lock.unlock() }
        if _shouldFail { return .failure(reason: "offline") }
        _received.append(contentsOf: events)
        return .success
    }
}

// MARK: - Suite

/// NF-010 (iOS parity of Android NF-003): When the network transitions
/// LOST → AVAILABLE, the iOS `MobileLogRecordProcessor` must drain its
/// buffered events. This closes the demo gap reported on 2026-05-12:
/// booking failed in airplane mode → toggling airplane off produced no
/// telemetry because nothing woke the exporter.
///
/// The iOS contract calls `forceFlushBuffered()` (RAM + disk), NOT the
/// OTel-protocol `forceFlush()` (RAM only) — disk-resident events from
/// offline failure-persistence must drain too.
/// See memory/feedback_ios_forceflush_two_methods.md.
@Suite("NetworkRestoredFlush (iOS)")
struct NetworkRestoredFlushTests {

    private func makeProcessor() async throws -> (
        MobileLogRecordProcessor,
        RestoredTogglingExporter,
        DiskLogBuffer,
        DiskLogBuffer.TestPath
    ) {
        let buffer = RAMEventBuffer(capacity: 100)
        let exporter = RestoredTogglingExporter()
        let diskPath = DiskLogBuffer.makeTestPath()
        let disk = try await DiskLogBuffer.makeForTesting(path: diskPath)
        let processor = MobileLogRecordProcessor(
            buffer: buffer,
            exporter: exporter,
            sessionProvider: NetworkRestoredStubSession(),
            diskBuffer: disk
        )
        return (processor, exporter, disk, diskPath)
    }

    private func cleanup(_ disk: DiskLogBuffer, path: DiskLogBuffer.TestPath) async {
        disk.shutdown()
        DiskLogBuffer.removeTestFiles(at: path)
    }

    @Test("processor flushes buffered events on LOST then AVAILABLE transition")
    func flushOnNetworkRestored() async throws {
        let (processor, exporter, disk, path) = try await makeProcessor()
        defer { Task { await cleanup(disk, path: path) } }

        let watcher = NetworkAvailabilityWatcher()
        processor.attachNetworkWatcher(watcher, minutes: 60)

        // Phase 1: emit events while "offline"; flush persists them to disk.
        await exporter.setOffline(true)
        for i in 0..<5 {
            await processor.emitForTesting(body: "airplane.\(i)")
        }
        _ = await processor.forceFlushBufferedAsync()
        let exportedWhileOffline = await exporter.received.count
        #expect(exportedWhileOffline == 0)

        // Phase 2: network restored. Toggle exporter to online, fire transition.
        await exporter.setOffline(false)
        watcher.onLost()
        watcher.onAvailable()

        // attachNetworkWatcher dispatches the flush onto a background Task.
        // Poll briefly so we don't depend on a fixed sleep.
        var exportedCount = 0
        for _ in 0..<20 {
            try await Task.sleep(nanoseconds: 100_000_000)  // 100ms
            exportedCount = await exporter.received.count
            if exportedCount >= 5 { break }
        }
        #expect(exportedCount >= 5, "buffered events must export after network restored — got \(exportedCount)")
    }

    @Test("processor does NOT flush on bare AVAILABLE without prior LOST")
    func noFlushOnBareAvailable() async throws {
        let (processor, exporter, disk, path) = try await makeProcessor()
        defer { Task { await cleanup(disk, path: path) } }

        let watcher = NetworkAvailabilityWatcher()
        processor.attachNetworkWatcher(watcher, minutes: 60)
        await exporter.setOffline(false)

        // Emit + flush some events while "online" to establish a baseline.
        for i in 0..<3 {
            await processor.emitForTesting(body: "warmup.\(i)")
        }
        _ = await processor.forceFlushBufferedAsync()
        let baseline = await exporter.received.count

        // Spurious onAvailable (no prior onLost) — must NOT trigger flush.
        watcher.onAvailable()
        try await Task.sleep(nanoseconds: 300_000_000)  // 300ms

        let after = await exporter.received.count
        #expect(after == baseline, "spurious onAvailable must not trigger a flush — baseline \(baseline) → \(after)")
    }
}
