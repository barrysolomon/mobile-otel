import Testing
@testable import WireframeInstrumentation
import OpenTelemetryApi
import OpenTelemetrySdk
@testable import OTelMobileCore
#if canImport(UIKit) && (os(iOS) || os(tvOS))
import UIKit
#endif
#if canImport(SwiftUI)
import SwiftUI
#endif

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

    @Test("redacted node drops all text-bearing fields and emits redacted:true")
    func redactedNodeDropsText() {
        // Even when the caller passes text fields, a redacted node must not
        // carry them — the invariant is enforced by the initializer.
        let node = WireframeNode(
            type: "UITextField",
            bounds: [0, 0, 100, 20],
            accessibilityIdentifier: "card_number",
            hint: "Card number",
            accessibilityLabel: "4111 1111 1111 1111",
            redacted: true
        )
        #expect(node.redacted)
        #expect(node.accessibilityIdentifier == nil)
        #expect(node.hint == nil)
        #expect(node.accessibilityLabel == nil)
        let json = node.toJson()
        #expect(json.contains("\"redacted\":true"))
        #expect(!json.contains("4111"))
        #expect(!json.contains("card_number"))
        #expect(!json.contains("Card number"))
        // Layout is preserved.
        #expect(json.contains("\"bounds\":[0,0,100,20]"))
    }

    @Test("non-redacted node keeps text fields")
    func nonRedactedKeepsText() {
        let node = WireframeNode(
            type: "UILabel", bounds: [0, 0, 50, 20],
            accessibilityLabel: "Welcome", redacted: false
        )
        #expect(node.accessibilityLabel == "Welcome")
        #expect(!node.toJson().contains("\"redacted\""))
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

// MARK: - Consent gate

@Suite("WireframeConsentGate", .serialized)
struct WireframeConsentGateTests {

    @Test("nil consent gate allows capture")
    func nilGateAllows() {
        let inst = WireframeInstrumentation(config: WireframeConfig(enabled: true))
        #expect(inst.consentAllows(trigger: "screen_view", screenName: "Home"))
    }

    @Test("consent gate returning false denies capture")
    func gateFalseDenies() {
        let config = WireframeConfig(enabled: true).withConsentGate { _ in false }
        let inst = WireframeInstrumentation(config: config)
        #expect(!inst.consentAllows(trigger: "tap", screenName: "Home"))
    }

    @Test("consent gate returning true allows capture")
    func gateTrueAllows() {
        let config = WireframeConfig(enabled: true).withConsentGate { _ in true }
        let inst = WireframeInstrumentation(config: config)
        #expect(inst.consentAllows(trigger: "tap", screenName: "Home"))
    }

    @Test("consent gate receives wireframe kind and correct trigger")
    func gateReceivesContext() {
        final class Box: @unchecked Sendable { var ctx: CaptureContext? }
        let box = Box()
        let config = WireframeConfig(enabled: true).withConsentGate { ctx in
            box.ctx = ctx
            return true
        }
        let inst = WireframeInstrumentation(config: config)
        _ = inst.consentAllows(trigger: "screen_view", screenName: "Profile")
        #expect(box.ctx?.kind == .wireframe)
        #expect(box.ctx?.trigger == .screenView)
        #expect(box.ctx?.screenName == "Profile")
    }
}

// MARK: - Deterministic redaction in the tree walk (UIKit)

#if canImport(UIKit) && (os(iOS) || os(tvOS))
@MainActor
@Suite("WireframeRedactionWalk", .serialized)
struct WireframeRedactionWalkTests {

    @Test("buildTree marks secure field redacted and does not descend")
    func secureFieldRedacted() {
        let root = UIView(frame: CGRect(x: 0, y: 0, width: 200, height: 200))
        let secure = UITextField(frame: CGRect(x: 0, y: 0, width: 100, height: 20))
        secure.isSecureTextEntry = true
        secure.accessibilityLabel = "supersecret"
        // A child inside the secure field whose label would otherwise leak.
        let leaky = UILabel(frame: CGRect(x: 0, y: 0, width: 100, height: 20))
        leaky.accessibilityLabel = "supersecret"
        secure.addSubview(leaky)
        root.addSubview(secure)

        let inst = WireframeInstrumentation()
        let tree = inst.buildTree(view: root, depth: 0)
        let json = tree.toJson()
        #expect(json.contains("\"redacted\":true"))
        #expect(!json.contains("supersecret"))
        // Did not descend into the secure field's subtree.
        let secureNode = tree.children.first { $0.redacted }
        #expect(secureNode?.children.isEmpty == true)
    }

    @Test("buildTree marks explicitly tagged view redacted")
    func taggedViewRedacted() {
        let root = UIView(frame: CGRect(x: 0, y: 0, width: 200, height: 200))
        let tagged = UILabel(frame: CGRect(x: 0, y: 0, width: 100, height: 20))
        tagged.accessibilityLabel = "1234-5678-9012"
        Dash0.redact(tagged)
        root.addSubview(tagged)

        let inst = WireframeInstrumentation()
        let json = inst.buildTree(view: root, depth: 0).toJson()
        #expect(json.contains("\"redacted\":true"))
        #expect(!json.contains("1234-5678-9012"))
    }

    @Test("buildTree keeps non-sensitive labels visible")
    func nonSensitiveVisible() {
        let root = UIView(frame: CGRect(x: 0, y: 0, width: 200, height: 200))
        let label = UILabel(frame: CGRect(x: 0, y: 0, width: 100, height: 20))
        label.accessibilityLabel = "Welcome back"
        root.addSubview(label)
        let inst = WireframeInstrumentation(config: WireframeConfig(includeContentDescription: true))
        let json = inst.buildTree(view: root, depth: 0).toJson()
        #expect(json.contains("Welcome back"))
    }
}
#endif

// MARK: - SwiftUI .dash0Redacted() tagging mechanism

#if canImport(SwiftUI) && canImport(UIKit) && (os(iOS) || os(tvOS))
@MainActor
@Suite("SwiftUIRedactionTagging", .serialized)
struct SwiftUIRedactionTaggingTests {

    // SwiftUI rendering is impractical to unit-test, so we assert the
    // mechanism instead: the backing UIView that `.dash0Redacted()` installs
    // (built by the single source of truth `makeTagged()`, which `makeUIView`
    // calls) carries the sensitive tag, and the shared redaction policy +
    // capture walk pick it up. This is the exact robustness guarantee that
    // replaces the old SwiftUI class-name heuristic.
    @available(iOS 15.0, tvOS 15.0, *)
    @Test("the backing tag view is marked sensitive and is masked by the walk")
    func backingViewIsMasked() {
        let backing = Dash0RedactionBackingView.makeTagged()
        #expect(backing.dash0IsMarkedSensitive)
        #expect(Dash0RedactionPolicy.shouldRedact(backing, redactAllText: false))

        // And the wireframe/screenshot walk masks the region the backing view
        // occupies when it is mounted in the tree.
        let root = UIView(frame: CGRect(x: 0, y: 0, width: 200, height: 200))
        backing.frame = CGRect(x: 0, y: 0, width: 100, height: 30)
        root.addSubview(backing)
        let inst = WireframeInstrumentation()
        let json = inst.buildTree(view: root, depth: 0).toJson()
        #expect(json.contains("\"redacted\":true"))
    }
}
#endif

// MARK: - Test helpers

private struct TestSessionProvider: SessionProvider, Sendable {
    let sessionId: String = "test-session-001"
    func rotateSession() -> String { sessionId }
}
