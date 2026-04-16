import Testing
@testable import OTelMobileSDK

@Suite("RAMEventBuffer")
struct RAMEventBufferTests {
    private func makeEvent(id: UInt64, timestampMs: UInt64? = nil) -> BufferedEvent {
        BufferedEvent.makeForTesting(
            sequenceId: id,
            timestampMs: timestampMs,
            payload: "test-\(id)"
        )
    }

    @Test("appendWithinCapacity")
    func appendWithinCapacity() async {
        let buffer = RAMEventBuffer(capacity: 3)
        let evicted = await buffer.append(makeEvent(id: 1))
        #expect(evicted == nil)
        let count = await buffer.count
        #expect(count == 1)
    }

    @Test("overflowEvictsOldest")
    func overflowEvictsOldest() async {
        let buffer = RAMEventBuffer(capacity: 2)
        _ = await buffer.append(makeEvent(id: 1))
        _ = await buffer.append(makeEvent(id: 2))
        let evicted = await buffer.append(makeEvent(id: 3))
        #expect(evicted != nil)
        #expect(evicted?.sequenceId == 1)
        let count = await buffer.count
        #expect(count == 2)
    }

    @Test("flushReturnsAllEvents")
    func flushReturnsAllEvents() async {
        let buffer = RAMEventBuffer(capacity: 10)
        _ = await buffer.append(makeEvent(id: 1))
        _ = await buffer.append(makeEvent(id: 2))
        _ = await buffer.append(makeEvent(id: 3))
        let flushed = await buffer.flush()
        #expect(flushed.count == 3)
        let countAfter = await buffer.count
        #expect(countAfter == 0)
    }

    @Test("flushWindowReturnsOnlyMatching")
    func flushWindowReturnsOnlyMatching() async {
        let buffer = RAMEventBuffer(capacity: 10)
        let now = BufferedEvent.currentTimestampMs()
        _ = await buffer.append(makeEvent(id: 1, timestampMs: now - 60_000))
        _ = await buffer.append(makeEvent(id: 2, timestampMs: now - 30_000))
        _ = await buffer.append(makeEvent(id: 3, timestampMs: now))
        let matching = await buffer.flushWindow(lastMs: 45_000)
        #expect(matching.count == 2)
        #expect(matching.first?.sequenceId == 2)
    }

    @Test("sequenceIdsAreMonotonic")
    func sequenceIdsAreMonotonic() async {
        let buffer = RAMEventBuffer(capacity: 10)
        _ = await buffer.append(makeEvent(id: 1))
        _ = await buffer.append(makeEvent(id: 2))
        let flushed = await buffer.flush()
        #expect(flushed.count == 2)
        #expect(flushed[0].sequenceId < flushed[1].sequenceId)
    }
}
