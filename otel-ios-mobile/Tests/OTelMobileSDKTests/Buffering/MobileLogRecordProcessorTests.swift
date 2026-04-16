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
        #expect(received[0].sequenceId < received[1].sequenceId)
        #expect(received[1].sequenceId < received[2].sequenceId)
    }
}
