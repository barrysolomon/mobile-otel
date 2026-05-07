import Testing
@testable import WireframeInstrumentation
import OpenTelemetryApi
import OpenTelemetrySdk
import OTelMobileCore

@Suite("WireframeInstrumentation", .serialized)
struct WireframeInstrumentationTests {

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
        let inst = WireframeInstrumentation()
        let cap = LogCapture()
        inst.install(context: makeContext(processor: cap))
        #expect(inst.isInstalled)
        inst.uninstall()
    }

    @Test("uninstall clears isInstalled")
    func uninstallClearsInstalled() {
        let inst = WireframeInstrumentation()
        let cap = LogCapture()
        inst.install(context: makeContext(processor: cap))
        inst.uninstall()
        #expect(!inst.isInstalled)
    }

    @Test("install is idempotent")
    func installIdempotent() {
        let inst = WireframeInstrumentation()
        let cap = LogCapture()
        let ctx = makeContext(processor: cap)
        inst.install(context: ctx)
        inst.install(context: ctx)
        #expect(inst.isInstalled)
        inst.uninstall()
    }

    @Test("disabled config prevents install")
    func disabledConfigPreventsInstall() {
        let config = WireframeConfig(enabled: false)
        let inst = WireframeInstrumentation(config: config)
        let cap = LogCapture()
        inst.install(context: makeContext(processor: cap))
        #expect(!inst.isInstalled)
    }

    // MARK: - emitForTesting (test seam)

    @Test("emitForTesting emits a ui.wireframe log record")
    func emitForTestingEmitsLog() {
        let inst = WireframeInstrumentation()
        let cap = LogCapture()
        inst.install(context: makeContext(processor: cap))
        inst.emitForTesting(trigger: "manual", screenName: "Home")
        let wireframes = cap.records.filter { $0.body == "ui.wireframe" }
        #expect(wireframes.count == 1)
        inst.uninstall()
    }

    @Test("emitForTesting carries trigger attribute")
    func emitForTestingCarriesTrigger() {
        let inst = WireframeInstrumentation()
        let cap = LogCapture()
        inst.install(context: makeContext(processor: cap))
        inst.emitForTesting(trigger: "journey_end", screenName: "Cart")
        guard let record = cap.records.first(where: { $0.body == "ui.wireframe" }) else {
            Issue.record("no ui.wireframe log captured")
            return
        }
        if case .string(let trigger)? = record.attributes["mobile.wireframe.trigger"] {
            #expect(trigger == "journey_end")
        } else {
            Issue.record("mobile.wireframe.trigger missing")
        }
        inst.uninstall()
    }

    @Test("emitForTesting carries screen.name attribute")
    func emitForTestingCarriesScreenName() {
        let inst = WireframeInstrumentation()
        let cap = LogCapture()
        inst.install(context: makeContext(processor: cap))
        inst.emitForTesting(trigger: "manual", screenName: "Profile")
        guard let record = cap.records.first(where: { $0.body == "ui.wireframe" }) else {
            Issue.record("no ui.wireframe log captured")
            return
        }
        if case .string(let name)? = record.attributes["screen.name"] {
            #expect(name == "Profile")
        } else {
            Issue.record("screen.name missing")
        }
        inst.uninstall()
    }

    @Test("emitForTesting carries session ID")
    func emitForTestingCarriesSessionId() {
        let inst = WireframeInstrumentation()
        let cap = LogCapture()
        inst.install(context: makeContext(processor: cap))
        inst.emitForTesting(trigger: "manual", screenName: "Home")
        guard let record = cap.records.first(where: { $0.body == "ui.wireframe" }) else {
            Issue.record("no ui.wireframe log captured")
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
        let inst = WireframeInstrumentation()
        let cap = LogCapture()
        inst.install(context: makeContext(processor: cap))
        inst.emitForTesting(trigger: "a", screenName: "X")
        inst.emitForTesting(trigger: "b", screenName: "Y")
        let wireframes = cap.records.filter { $0.body == "ui.wireframe" }
        #expect(wireframes.count == 2)
        if case .int(let seq0)? = wireframes[0].attributes["mobile.wireframe.sequence"],
           case .int(let seq1)? = wireframes[1].attributes["mobile.wireframe.sequence"] {
            #expect(seq1 == seq0 + 1)
        } else {
            Issue.record("sequence attributes missing")
        }
        inst.uninstall()
    }
}

// MARK: - WireframeNode tests

@Suite("WireframeNode")
struct WireframeNodeTests {

    @Test("toJson produces valid JSON for a leaf node")
    func toJsonLeaf() {
        let node = WireframeNode(type: "UILabel", bounds: [10, 20, 100, 40])
        let json = node.toJson()
        #expect(json.contains("\"type\":\"UILabel\""))
        #expect(json.contains("\"bounds\":[10,20,100,40]"))
        #expect(!json.contains("\"children\""))
    }

    @Test("toJson includes children when present")
    func toJsonWithChildren() {
        let child = WireframeNode(type: "UILabel", bounds: [0, 0, 50, 20])
        let parent = WireframeNode(type: "UIView", bounds: [0, 0, 100, 100], children: [child])
        let json = parent.toJson()
        #expect(json.contains("\"children\":[{"))
        #expect(json.contains("\"type\":\"UILabel\""))
    }

    @Test("toJson includes optional fields when set")
    func toJsonOptionalFields() {
        let node = WireframeNode(
            type: "UIButton",
            bounds: [0, 0, 80, 40],
            accessibilityIdentifier: "btn_submit",
            accessibilityLabel: "Submit",
            isInteractive: true,
            isEnabled: true
        )
        let json = node.toJson()
        #expect(json.contains("\"id\":\"btn_submit\""))
        #expect(json.contains("\"label\":\"Submit\""))
        #expect(json.contains("\"interactive\":true"))
        #expect(json.contains("\"enabled\":true"))
    }

    @Test("toJson marks truncated nodes")
    func toJsonTruncated() {
        let node = WireframeNode(type: "UIView", bounds: [0, 0, 100, 100], truncated: true)
        let json = node.toJson()
        #expect(json.contains("\"truncated\":true"))
    }

    @Test("nodeCount counts all nodes recursively")
    func nodeCountRecursive() {
        let leaf1 = WireframeNode(type: "UILabel", bounds: [0, 0, 50, 20])
        let leaf2 = WireframeNode(type: "UIButton", bounds: [0, 0, 80, 40])
        let parent = WireframeNode(type: "UIView", bounds: [0, 0, 100, 100], children: [leaf1, leaf2])
        #expect(parent.nodeCount() == 3)
    }

    @Test("toJson escapes special characters")
    func toJsonEscapes() {
        let node = WireframeNode(type: "UIView", bounds: [0, 0, 1, 1], hint: "Enter \"name\"")
        let json = node.toJson()
        #expect(json.contains("\\\"name\\\""))
    }
}

// MARK: - WireframeConfig tests

@Suite("WireframeConfig")
struct WireframeConfigTests {

    @Test("default config has sensible defaults")
    func defaultConfig() {
        let config = WireframeConfig()
        #expect(config.enabled)
        #expect(config.maxCapturesPerMinute == 30)
        #expect(config.maxDepth == 20)
        #expect(config.captureOnScreenView)
        #expect(!config.captureOnTap)
        #expect(config.captureOnError)
        #expect(config.includeAccessibilityIdentifiers)
        #expect(!config.includeTextHints)
        #expect(config.includeContentDescription)
        #expect(config.includeInteractionState)
    }
}

// MARK: - Test helpers

private struct TestSessionProvider: SessionProvider, Sendable {
    let sessionId: String = "test-session-001"
    func rotateSession() -> String { sessionId }
}
