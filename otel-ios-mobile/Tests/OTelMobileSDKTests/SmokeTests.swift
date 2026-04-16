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
        #expect(events[0].sequenceId < events[1].sequenceId)
        #expect(events[1].sequenceId < events[2].sequenceId)

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
