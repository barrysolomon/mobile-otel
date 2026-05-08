import Testing
@testable import OTelMobileSDK
import OTelMobileCore

@Suite("ErrorCoalescer")
struct ErrorCoalescerTests {

    @Test("first error is NOT coalesced")
    func firstNotCoalesced() {
        let coalescer = ErrorCoalescer()
        let record = ErrorCoalescer.makeTestRecord(exceptionType: "NPE", exceptionMessage: "null ref")
        #expect(coalescer.tryCoalesce(record) == false)
    }

    @Test("duplicate within window IS coalesced")
    func duplicateCoalesced() {
        let coalescer = ErrorCoalescer()
        let r1 = ErrorCoalescer.makeTestRecord(exceptionType: "NPE", exceptionMessage: "null ref")
        let r2 = ErrorCoalescer.makeTestRecord(exceptionType: "NPE", exceptionMessage: "null ref")
        #expect(coalescer.tryCoalesce(r1) == false)
        #expect(coalescer.tryCoalesce(r2) == true)
    }

    @Test("different exception types are NOT coalesced")
    func differentTypesNotCoalesced() {
        let coalescer = ErrorCoalescer()
        let r1 = ErrorCoalescer.makeTestRecord(exceptionType: "NPE")
        let r2 = ErrorCoalescer.makeTestRecord(exceptionType: "IOError")
        #expect(coalescer.tryCoalesce(r1) == false)
        #expect(coalescer.tryCoalesce(r2) == false)
    }

    @Test("different messages are NOT coalesced")
    func differentMessagesNotCoalesced() {
        let coalescer = ErrorCoalescer()
        let r1 = ErrorCoalescer.makeTestRecord(exceptionType: "NPE", exceptionMessage: "msg A")
        let r2 = ErrorCoalescer.makeTestRecord(exceptionType: "NPE", exceptionMessage: "msg B")
        #expect(coalescer.tryCoalesce(r1) == false)
        #expect(coalescer.tryCoalesce(r2) == false)
    }

    @Test("INFO is not eligible for coalescing")
    func infoNotEligible() {
        let coalescer = ErrorCoalescer()
        let r = ErrorCoalescer.makeTestRecord(severity: .info, exceptionType: "NPE")
        #expect(coalescer.tryCoalesce(r) == false)
        #expect(coalescer.tryCoalesce(r) == false)
    }

    @Test("WARN is not eligible at default minSeverity")
    func warnNotEligible() {
        let coalescer = ErrorCoalescer()
        let r = ErrorCoalescer.makeTestRecord(severity: .warn, exceptionType: "NPE")
        #expect(coalescer.tryCoalesce(r) == false)
        #expect(coalescer.tryCoalesce(r) == false)
    }

    @Test("custom minSeverity of WARN makes WARN eligible")
    func customMinSeverity() {
        let coalescer = ErrorCoalescer(minSeverity: .warn)
        let r1 = ErrorCoalescer.makeTestRecord(severity: .warn, exceptionType: "NPE")
        let r2 = ErrorCoalescer.makeTestRecord(severity: .warn, exceptionType: "NPE")
        #expect(coalescer.tryCoalesce(r1) == false)
        #expect(coalescer.tryCoalesce(r2) == true)
    }

    @Test("count tracking works")
    func countTracking() {
        let coalescer = ErrorCoalescer()
        let r = ErrorCoalescer.makeTestRecord(exceptionType: "NPE", exceptionMessage: "null")
        _ = coalescer.tryCoalesce(r)
        _ = coalescer.tryCoalesce(r)
        _ = coalescer.tryCoalesce(r)
        #expect(coalescer.getCount(for: r) == 3)
    }

    @Test("drainCoalesced returns entries with count > 1")
    func drainCoalesced() {
        let coalescer = ErrorCoalescer()
        let r = ErrorCoalescer.makeTestRecord(exceptionType: "NPE", exceptionMessage: "null")
        _ = coalescer.tryCoalesce(r)
        _ = coalescer.tryCoalesce(r)
        _ = coalescer.tryCoalesce(r)
        let drained = coalescer.drainCoalesced()
        #expect(drained.count == 1)
        #expect(drained[0].count == 3)
    }

    @Test("drain clears drained entries")
    func drainClears() {
        let coalescer = ErrorCoalescer()
        let r = ErrorCoalescer.makeTestRecord(exceptionType: "NPE", exceptionMessage: "null")
        _ = coalescer.tryCoalesce(r)
        _ = coalescer.tryCoalesce(r)
        _ = coalescer.drainCoalesced()
        #expect(coalescer.activeGroupCount == 0)
    }

    @Test("body-based coalescing when no exception type")
    func bodyBasedCoalescing() {
        let coalescer = ErrorCoalescer()
        let r1 = ErrorCoalescer.makeTestRecord(body: "same body", severity: .error)
        let r2 = ErrorCoalescer.makeTestRecord(body: "same body", severity: .error)
        #expect(coalescer.tryCoalesce(r1) == false)
        #expect(coalescer.tryCoalesce(r2) == true)
    }

    @Test("clear resets all state")
    func clearResets() {
        let coalescer = ErrorCoalescer()
        let r = ErrorCoalescer.makeTestRecord(exceptionType: "NPE")
        _ = coalescer.tryCoalesce(r)
        _ = coalescer.tryCoalesce(r)
        coalescer.clear()
        #expect(coalescer.activeGroupCount == 0)
        #expect(coalescer.tryCoalesce(r) == false)
    }

    @Test("error coalescing integration with processor onEmit")
    func processorIntegration() async {
        MobileLogRecordProcessor._offlineOverride = false
        defer { MobileLogRecordProcessor._offlineOverride = nil }

        let buffer = RAMEventBuffer(capacity: 100)
        let exporter = InertExporter()
        let processor = MobileLogRecordProcessor(
            buffer: buffer,
            exporter: exporter,
            sessionProvider: CoalescerStubSession()
        )

        for _ in 0..<3 {
            await processor.emitForTesting(body: "repeated error", severity: .error)
        }
        let events = await buffer.peek()
        #expect(events.count == 1, "only the first occurrence should pass through coalescing")
    }
}

// MARK: - Test helpers

fileprivate final class CoalescerStubSession: SessionProvider, @unchecked Sendable {
    var sessionId: String { "coalescer-session" }
    func rotateSession() -> String { "coalescer-session" }
}

fileprivate actor InertExporter: BufferedEventExporter {
    func export(_ events: [BufferedEvent]) async -> BufferExportResult { .success }
}
