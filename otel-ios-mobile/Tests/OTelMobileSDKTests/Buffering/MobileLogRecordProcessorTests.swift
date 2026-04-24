import Testing
@testable import OTelMobileSDK
import OTelMobileCore

// MARK: - Mocks

fileprivate final class MockSessionProvider: SessionProvider, @unchecked Sendable {
    var sessionId: String { "mock-session" }
    func rotateSession() -> String { "mock-session" }
}

fileprivate actor MockExporter: BufferedEventExporter {
    private(set) var received: [BufferedEvent] = []
    private(set) var callCount: Int = 0

    func export(_ events: [BufferedEvent]) async -> BufferExportResult {
        received.append(contentsOf: events)
        callCount += 1
        return .success
    }
}

// MARK: - Suite

@Suite("MobileLogRecordProcessor")
struct MobileLogRecordProcessorTests {
    private func makeProcessor() -> (MobileLogRecordProcessor, MockExporter, RAMEventBuffer) {
        let buffer = RAMEventBuffer(capacity: 100)
        let exporter = MockExporter()
        let processor = MobileLogRecordProcessor(
            buffer: buffer,
            exporter: exporter,
            sessionProvider: MockSessionProvider()
        )
        return (processor, exporter, buffer)
    }

    @Test("onEmitBuffersEvent")
    func onEmitBuffersEvent() async {
        let (processor, _, buffer) = makeProcessor()
        await processor.emitForTesting(body: "one")
        await processor.emitForTesting(body: "two")
        await processor.emitForTesting(body: "three")
        let peeked = await buffer.peek()
        #expect(peeked.count == 3)
    }

    @Test("forceFlushDrainsBuffer")
    func forceFlushDrainsBuffer() async {
        let (processor, exporter, buffer) = makeProcessor()
        await processor.emitForTesting(body: "a")
        await processor.emitForTesting(body: "b")
        _ = processor.forceFlush()
        let remaining = await buffer.count
        #expect(remaining == 0)
        let received = await exporter.received
        #expect(received.count == 2)
    }

    @Test("flushWindowOnlyFlushesRecent")
    func flushWindowOnlyFlushesRecent() async {
        let (processor, exporter, _) = makeProcessor()
        let now = BufferedEvent.currentTimestampMs()
        // Seed directly via the test injection seam so timestamps are exact.
        // Oldest is 2 minutes back (outside the 1-minute window).
        await processor.injectEvent(
            BufferedEvent.makeForTesting(
                sequenceId: processor.nextSequenceId(),
                timestampMs: now - 120_000,
                payload: "oldest"
            )
        )
        await processor.injectEvent(
            BufferedEvent.makeForTesting(
                sequenceId: processor.nextSequenceId(),
                timestampMs: now - 30_000,
                payload: "middle"
            )
        )
        await processor.injectEvent(
            BufferedEvent.makeForTesting(
                sequenceId: processor.nextSequenceId(),
                timestampMs: now,
                payload: "newest"
            )
        )
        let result = await processor.flushWindow(minutes: 1)
        #expect(result == .success)
        let received = await exporter.received
        #expect(received.count == 2)
    }

    @Test("sequenceIdsMonotonicAcrossEmits")
    func sequenceIdsMonotonicAcrossEmits() async {
        let (processor, exporter, _) = makeProcessor()
        await processor.emitForTesting(body: "one")
        await processor.emitForTesting(body: "two")
        await processor.emitForTesting(body: "three")
        _ = processor.forceFlush()
        let received = await exporter.received
        #expect(received.count == 3)
        // sequenceId is assigned synchronously in onEmit in call order
        // (1, 2, 3). Buffer.append runs on a detached Task, so ARRIVAL
        // order is non-deterministic — assert the set instead of a
        // pairwise comparison.
        let seqIds = Set(received.map { $0.sequenceId })
        #expect(seqIds.count == 3)
    }

    @Test("startContinuousFlush: periodic timer drains buffered events")
    func continuousFlushFiresPeriodically() async throws {
        let (processor, exporter, _) = makeProcessor()
        await processor.emitForTesting(body: "pre")
        // 1s is the floor on the timer (clamped). Using that gives the
        // test a predictable bound without the test itself having to
        // wait 30s to observe the default cadence.
        processor.startContinuousFlush(intervalSeconds: 1)
        // Let one tick elapse. 1.5s gives headroom for the DispatchSource
        // scheduling jitter.
        try await Task.sleep(nanoseconds: 1_500_000_000)
        let received = await exporter.received
        #expect(received.count == 1)
        processor.stopContinuousFlush()
    }

    @Test("stopContinuousFlush: timer stops firing after stop")
    func continuousFlushStops() async throws {
        let (processor, exporter, _) = makeProcessor()
        processor.startContinuousFlush(intervalSeconds: 1)
        processor.stopContinuousFlush()
        // Emit AFTER stop — a running timer would pick this up within 1s.
        await processor.emitForTesting(body: "after-stop")
        try await Task.sleep(nanoseconds: 1_500_000_000)
        let received = await exporter.received
        // The stopped timer must not have flushed — event stays buffered.
        #expect(received.isEmpty)
    }

    @Test("startContinuousFlush: calling twice replaces previous timer")
    func continuousFlushRestarts() async throws {
        let (processor, exporter, _) = makeProcessor()
        processor.startContinuousFlush(intervalSeconds: 1)
        // Replace with another timer before the first ticks. If the
        // previous timer weren't cancelled, we'd see duplicate flush
        // attempts landing the same event twice.
        processor.startContinuousFlush(intervalSeconds: 1)
        await processor.emitForTesting(body: "single")
        try await Task.sleep(nanoseconds: 1_500_000_000)
        let received = await exporter.received
        #expect(received.count == 1)
        processor.stopContinuousFlush()
    }
}
