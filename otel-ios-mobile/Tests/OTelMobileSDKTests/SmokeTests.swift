import Testing
@testable import OTelMobileSDK
import OTelMobileCore

@Suite("EndToEndSmoke")
struct SmokeTests {
    @Test("emit -> forceFlush -> CapturingExporter receives all events")
    func endToEndEmitFlushCapture() async throws {
        let captured = CapturingExporter()
        let config = MobileConfig(
            serviceName: "smoke-test",
            endpoint: "https://unused"
        )

        let mobile = try OTelMobile.start(config: config, exporter: captured)

        mobile.emit(body: "user.login")
        mobile.emit(body: "user.click", severity: .debug)
        mobile.emit(body: "error.network", severity: .error)

        // Let fire-and-forget buffer-append tasks settle before flushing.
        try await MobileLogRecordProcessor.waitForBufferedAppends(timeoutMs: 500)

        let result = mobile.forceFlush()
        #expect(result == .success)

        let events = await captured.events
        #expect(events.count == 3)
        // sequenceId is assigned synchronously in `onEmit` (1, 2, 3 in call
        // order) but the buffer.append runs on a detached Task, so the
        // ORDER in which events land is non-deterministic under concurrency.
        // Assert the set — the monotonicity invariant is already covered by
        // `MobileLogRecordProcessor.sequenceIdsMonotonicAcrossEmits`.
        let seqIds = Set(events.map { $0.sequenceId })
        #expect(seqIds == [1, 2, 3])

        // All should share the same session id, UUID-formatted (8-4-4-4-12).
        let sessionIds = Set(events.map { $0.sessionId })
        #expect(sessionIds.count == 1)
        let sid = sessionIds.first!
        #expect(sid.count == 36)
        #expect(sid.contains("-"))
    }

    @Test("forceFlush with no events returns success")
    func emptyFlush() async throws {
        let captured = CapturingExporter()
        let config = MobileConfig(serviceName: "smoke", endpoint: "https://unused")
        let mobile = try OTelMobile.start(config: config, exporter: captured)
        #expect(mobile.forceFlush() == .success)
        let events = await captured.events
        #expect(events.isEmpty)
    }
}
