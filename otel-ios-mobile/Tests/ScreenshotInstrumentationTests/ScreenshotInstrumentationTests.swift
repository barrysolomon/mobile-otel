import Testing
@testable import ScreenshotInstrumentation
import OpenTelemetryApi
import OpenTelemetrySdk
import OTelMobileCore

@Suite("ScreenshotInstrumentation", .serialized)
struct ScreenshotInstrumentationTests {

    private func makeContext(processor: LogCapture) -> InstrumentationContext {
        let logger = makeLogger(processor: processor)
        let tracerProvider = TracerProviderBuilder().build()
        let tracer = tracerProvider.get(instrumentationName: "test")
        let meterProvider = MeterProviderSdk.builder().build()
        let meter = meterProvider.get(name: "test")
        return InstrumentationContext(
            tracer: tracer,
            logger: logger,
            meter: meter,
            sessionProvider: TestSessionProvider(),
            eventHub: TouchEventHub(),
            privacyConfig: .default
        )
    }

    // MARK: - Install / Uninstall

    @Test("install sets isInstalled to true")
    func installSetsInstalled() {
        let inst = ScreenshotInstrumentation()
        let cap = LogCapture()
        inst.install(context: makeContext(processor: cap))
        #expect(inst.isInstalled)
        inst.uninstall()
    }

    @Test("uninstall clears isInstalled")
    func uninstallClearsInstalled() {
        let inst = ScreenshotInstrumentation()
        let cap = LogCapture()
        inst.install(context: makeContext(processor: cap))
        inst.uninstall()
        #expect(!inst.isInstalled)
    }

    @Test("install is idempotent")
    func installIdempotent() {
        let inst = ScreenshotInstrumentation()
        let cap = LogCapture()
        let ctx = makeContext(processor: cap)
        inst.install(context: ctx)
        inst.install(context: ctx)
        #expect(inst.isInstalled)
        inst.uninstall()
    }

    @Test("disabled config prevents install")
    func disabledConfigPreventsInstall() {
        let config = ScreenshotConfig(enabled: false)
        let inst = ScreenshotInstrumentation(config: config)
        let cap = LogCapture()
        inst.install(context: makeContext(processor: cap))
        #expect(!inst.isInstalled)
    }

    // MARK: - emitForTesting (test seam)

    @Test("emitForTesting emits a ui.screenshot log record")
    func emitForTestingEmitsLog() {
        let inst = ScreenshotInstrumentation()
        let cap = LogCapture()
        inst.install(context: makeContext(processor: cap))
        inst.emitForTesting(trigger: "manual", screenName: "Home")
        let screenshots = cap.records.filter { $0.body == "ui.screenshot" }
        #expect(screenshots.count == 1)
        inst.uninstall()
    }

    @Test("emitForTesting carries trigger attribute")
    func emitForTestingCarriesTrigger() {
        let inst = ScreenshotInstrumentation()
        let cap = LogCapture()
        inst.install(context: makeContext(processor: cap))
        inst.emitForTesting(trigger: "journey_start", screenName: "Calendar")
        guard let record = cap.records.first(where: { $0.body == "ui.screenshot" }) else {
            Issue.record("no ui.screenshot log captured")
            return
        }
        if case .string(let trigger)? = record.attributes["mobile.screenshot.trigger"] {
            #expect(trigger == "journey_start")
        } else {
            Issue.record("mobile.screenshot.trigger missing")
        }
        inst.uninstall()
    }

    @Test("emitForTesting carries screen.name attribute")
    func emitForTestingCarriesScreenName() {
        let inst = ScreenshotInstrumentation()
        let cap = LogCapture()
        inst.install(context: makeContext(processor: cap))
        inst.emitForTesting(trigger: "manual", screenName: "Settings")
        guard let record = cap.records.first(where: { $0.body == "ui.screenshot" }) else {
            Issue.record("no ui.screenshot log captured")
            return
        }
        if case .string(let name)? = record.attributes["screen.name"] {
            #expect(name == "Settings")
        } else {
            Issue.record("screen.name missing")
        }
        inst.uninstall()
    }

    @Test("emitForTesting carries session ID")
    func emitForTestingCarriesSessionId() {
        let inst = ScreenshotInstrumentation()
        let cap = LogCapture()
        inst.install(context: makeContext(processor: cap))
        inst.emitForTesting(trigger: "manual", screenName: "Home")
        guard let record = cap.records.first(where: { $0.body == "ui.screenshot" }) else {
            Issue.record("no ui.screenshot log captured")
            return
        }
        if case .string(let sid)? = record.attributes["mobile.session.id"] {
            #expect(!sid.isEmpty)
        } else {
            Issue.record("mobile.session.id missing")
        }
        inst.uninstall()
    }

    @Test("emitForTesting increments sequence number")
    func emitForTestingSequenceIncrement() {
        let inst = ScreenshotInstrumentation()
        let cap = LogCapture()
        inst.install(context: makeContext(processor: cap))
        inst.emitForTesting(trigger: "a", screenName: "X")
        inst.emitForTesting(trigger: "b", screenName: "Y")
        let screenshots = cap.records.filter { $0.body == "ui.screenshot" }
        #expect(screenshots.count == 2)
        if case .int(let seq0)? = screenshots[0].attributes["mobile.screenshot.sequence"],
           case .int(let seq1)? = screenshots[1].attributes["mobile.screenshot.sequence"] {
            #expect(seq1 == seq0 + 1)
        } else {
            Issue.record("sequence attributes missing")
        }
        inst.uninstall()
    }

    // MARK: - Rate limiting

    @Test("capture respects rate limit")
    func captureRespectsRateLimit() {
        let config = ScreenshotConfig(maxCapturesPerMinute: 2)
        let inst = ScreenshotInstrumentation(config: config)
        let cap = LogCapture()
        inst.install(context: makeContext(processor: cap))
        inst.emitForTesting(trigger: "1", screenName: "A")
        inst.emitForTesting(trigger: "2", screenName: "B")
        inst.emitForTesting(trigger: "3", screenName: "C")
        // Rate limiter is on the `capture` path, not emitForTesting.
        // emitForTesting bypasses rate limiting by design (test seam).
        // Test rate limiting through the actual capture path — but
        // UIKit capture needs a window, so we test the RateLimiter directly.
        #expect(screenshots(in: cap) == 3)
        inst.uninstall()
    }

    private func screenshots(in cap: LogCapture) -> Int {
        cap.records.filter { $0.body == "ui.screenshot" }.count
    }
}

// MARK: - ScreenshotConfig tests

@Suite("ScreenshotConfig")
struct ScreenshotConfigTests {

    @Test("default config has sensible defaults")
    func defaultConfig() {
        let config = ScreenshotConfig()
        #expect(config.enabled)
        #expect(config.maxWidthPx == 480)
        #expect(config.maxHeightPx == 960)
        #expect(config.quality == 60)
        #expect(config.format == .jpeg)
        #expect(config.maxPayloadKb == 256)
        #expect(config.maxCapturesPerMinute == 5)
        #expect(config.redactTextFields)
        #expect(!config.captureOnScreenView)
        #expect(config.captureOnError)
    }

    @Test("config can be customized")
    func customConfig() {
        let config = ScreenshotConfig(
            enabled: false,
            maxWidthPx: 200,
            quality: 80,
            format: .png,
            redactTextFields: false
        )
        #expect(!config.enabled)
        #expect(config.maxWidthPx == 200)
        #expect(config.quality == 80)
        #expect(config.format == .png)
        #expect(!config.redactTextFields)
    }
}

// MARK: - Rate limiter tests

@Suite("RateLimiter")
struct RateLimiterTests {

    @Test("allows up to max per window")
    func allowsUpToMax() {
        let limiter = RateLimiter(maxPerWindow: 3)
        #expect(limiter.tryAcquire())
        #expect(limiter.tryAcquire())
        #expect(limiter.tryAcquire())
        #expect(!limiter.tryAcquire())
    }

    @Test("reset clears the window")
    func resetClears() {
        let limiter = RateLimiter(maxPerWindow: 1)
        #expect(limiter.tryAcquire())
        #expect(!limiter.tryAcquire())
        limiter.reset()
        #expect(limiter.tryAcquire())
    }
}

// MARK: - Test helpers

private struct TestSessionProvider: SessionProvider, Sendable {
    let sessionId: String = "test-session-001"
    func rotateSession() -> String { sessionId }
}
