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

// MARK: - Tuple-keying regression suite
//
// Locked in after the 2026-05-12 misdiagnosis: the previous `body|<body>` fallback
// coalesced two `http.error` records to different URLs as duplicates, so HYBRID
// flushed one and silently lost the others. The new key shape distinguishes
// signal class + identifying attributes. See docs/contracts/error-coalescer.md.

extension ErrorCoalescerTests {

    @Test("http.error to DIFFERENT status codes are NOT coalesced (tuple key)")
    func httpErrorDifferentStatusNotCoalesced() {
        let coalescer = ErrorCoalescer()
        let r503 = ErrorCoalescer.makeTestRecord(
            body: "http.error", severity: .error,
            eventName: "http.error",
            attributes: ["http.response.status_code": "503", "url.full": "http://a/api"]
        )
        let r500 = ErrorCoalescer.makeTestRecord(
            body: "http.error", severity: .error,
            eventName: "http.error",
            attributes: ["http.response.status_code": "500", "url.full": "http://a/api"]
        )
        #expect(coalescer.tryCoalesce(r503) == false)
        #expect(coalescer.tryCoalesce(r500) == false,
                "different status_code on same URL must not collapse")
    }

    @Test("http.error to DIFFERENT URLs are NOT coalesced (tuple key)")
    func httpErrorDifferentUrlNotCoalesced() {
        let coalescer = ErrorCoalescer()
        let rA = ErrorCoalescer.makeTestRecord(
            body: "http.error", severity: .error,
            eventName: "http.error",
            attributes: ["http.response.status_code": "503", "url.full": "http://a/api"]
        )
        let rB = ErrorCoalescer.makeTestRecord(
            body: "http.error", severity: .error,
            eventName: "http.error",
            attributes: ["http.response.status_code": "503", "url.full": "http://b/api"]
        )
        #expect(coalescer.tryCoalesce(rA) == false)
        #expect(coalescer.tryCoalesce(rB) == false,
                "different URL on same status_code must not collapse")
    }

    @Test("identical http.error tuple IS coalesced")
    func httpErrorIdenticalTupleCoalesced() {
        let coalescer = ErrorCoalescer()
        let rA = ErrorCoalescer.makeTestRecord(
            body: "http.error", severity: .error,
            eventName: "http.error",
            attributes: ["http.response.status_code": "503", "url.full": "http://a/api"]
        )
        let rB = ErrorCoalescer.makeTestRecord(
            body: "http.error", severity: .error,
            eventName: "http.error",
            attributes: ["http.response.status_code": "503", "url.full": "http://a/api"]
        )
        #expect(coalescer.tryCoalesce(rA) == false)
        #expect(coalescer.tryCoalesce(rB) == true,
                "genuine duplicate http.error (same status, same URL) should collapse")
    }

    @Test("structured event with event.name but no exception bypasses coalescer")
    func structuredEventBypassesCoalescer() {
        let coalescer = ErrorCoalescer()
        // Two ui.tap records would have collapsed under the old body|<body> rule.
        let r1 = ErrorCoalescer.makeTestRecord(
            body: "ui.tap", severity: .error,
            eventName: "ui.tap",
            attributes: ["x": "10"]
        )
        let r2 = ErrorCoalescer.makeTestRecord(
            body: "ui.tap", severity: .error,
            eventName: "ui.tap",
            attributes: ["x": "200"]
        )
        #expect(coalescer.tryCoalesce(r1) == false)
        #expect(coalescer.tryCoalesce(r2) == false,
                "structured signals must not collapse on body alone")
    }

    @Test("raw body fallback still collapses when no event.name + no exception")
    func bodyFallbackStillCollapses() {
        let coalescer = ErrorCoalescer()
        // Legacy uncaught exception path: body present, no structured attrs.
        let r1 = ErrorCoalescer.makeTestRecord(body: "raw error msg", severity: .error)
        let r2 = ErrorCoalescer.makeTestRecord(body: "raw error msg", severity: .error)
        #expect(coalescer.tryCoalesce(r1) == false)
        #expect(coalescer.tryCoalesce(r2) == true,
                "legacy body-only records still collapse for genuine error storms")
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
