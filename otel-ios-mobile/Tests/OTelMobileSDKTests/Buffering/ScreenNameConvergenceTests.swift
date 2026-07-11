import Testing
@testable import OTelMobileSDK
import OTelMobileCore
import OpenTelemetryApi
import OpenTelemetrySdk

/// Semconv screen-name convergence (docs/SEMCONV_AUDIT.md): the processor's
/// `onEmit` choke point mirrors the legacy `screen.name` / `mobile.screen.name`
/// attributes onto the upstream-aligned `app.screen.name` (renamed in
/// opentelemetry-android 1.5.0) for EVERY log record. iOS side of the
/// cross-platform convergence — iOS historically emitted `screen.name` while
/// Android emitted `mobile.screen.name`; both now land `app.screen.name`.
/// Behavioural parity with Android's `ScreenNameConvergenceTest.kt`.
/// Legacy aliases drop at 1.0.
@Suite("ScreenNameConvergence")
struct ScreenNameConvergenceTests {

    fileprivate final class StubSessionProvider: SessionProvider, @unchecked Sendable {
        var sessionId: String { "conv-session" }
        func rotateSession() -> String { "conv-session" }
    }

    fileprivate struct StubExporter: BufferedEventExporter {
        func export(_ events: [BufferedEvent]) -> BufferExportResult { .success }
    }

    private func makeProcessor() -> (MobileLogRecordProcessor, RAMEventBuffer) {
        let buffer = RAMEventBuffer(capacity: 100)
        let processor = MobileLogRecordProcessor(
            buffer: buffer,
            exporter: StubExporter(),
            sessionProvider: StubSessionProvider()
        )
        return (processor, buffer)
    }

    @Test("legacy screen.name is mirrored onto app.screen.name")
    func legacyScreenNameMirrored() async {
        let (processor, buffer) = makeProcessor()
        await processor.emitForTesting(
            body: "ui.tap",
            attributes: ["screen.name": .string("BookScreen")]
        )
        let attrs = await buffer.peek().last?.record?.attributes
        #expect(attrs?["app.screen.name"] == .string("BookScreen"))
        #expect(attrs?["screen.name"] == .string("BookScreen"))
    }

    @Test("legacy mobile.screen.name is also mirrored")
    func legacyMobileScreenNameMirrored() async {
        let (processor, buffer) = makeProcessor()
        await processor.emitForTesting(
            body: "ui.tap",
            attributes: ["mobile.screen.name": .string("ProfileScreen")]
        )
        let attrs = await buffer.peek().last?.record?.attributes
        #expect(attrs?["app.screen.name"] == .string("ProfileScreen"))
    }

    @Test("an existing app.screen.name is never overwritten")
    func existingSemconvNotOverwritten() async {
        let (processor, buffer) = makeProcessor()
        await processor.emitForTesting(
            body: "custom",
            attributes: [
                "screen.name": .string("legacy-screen"),
                "app.screen.name": .string("explicit-screen"),
            ]
        )
        let attrs = await buffer.peek().last?.record?.attributes
        #expect(attrs?["app.screen.name"] == .string("explicit-screen"))
    }

    @Test("records without a screen name are untouched")
    func recordsWithoutScreenNameUntouched() async {
        let (processor, buffer) = makeProcessor()
        await processor.emitForTesting(body: "plain")
        let attrs = await buffer.peek().last?.record?.attributes
        #expect(attrs?["app.screen.name"] == nil)
        #expect(attrs?["screen.name"] == nil)
    }
}
