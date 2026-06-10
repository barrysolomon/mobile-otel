import Testing
@testable import OTelMobileSDK

@Suite("ConfigPoller")
struct ConfigPollerTests {
    @Test("appendQuery adds param when absent")
    func appendAdds() {
        let url = ConfigPoller.testHelperAppendQuery(
            urlString: "https://gateway.dash0.com/config",
            key: "dsl_version",
            value: "2"
        )
        #expect(url.contains("dsl_version=2"))
    }

    @Test("appendQuery replaces existing param")
    func appendReplaces() {
        let url = ConfigPoller.testHelperAppendQuery(
            urlString: "https://gateway.dash0.com/config?dsl_version=1",
            key: "dsl_version",
            value: "2"
        )
        // Exactly one dsl_version=, and it's 2
        let count = url.components(separatedBy: "dsl_version=").count - 1
        #expect(count == 1)
        #expect(url.contains("dsl_version=2"))
        #expect(!url.contains("dsl_version=1"))
    }

    @Test("appendQuery preserves other params")
    func appendPreserves() {
        let url = ConfigPoller.testHelperAppendQuery(
            urlString: "https://gateway.dash0.com/config?foo=bar",
            key: "dsl_version",
            value: "2"
        )
        #expect(url.contains("foo=bar"))
        #expect(url.contains("dsl_version=2"))
    }

    @Test("empty endpoint throws PollerError.invalidEndpoint")
    func emptyEndpoint() async {
        // URL(string: "") returns nil — guaranteed invalid on every Swift
        // version. Newer URL impls (iOS 17+) are too permissive about
        // garbage-string inputs, so we use the empty-string base case.
        do {
            _ = try ConfigPoller.testHelperBuild(endpoint: "")
            Issue.record("Expected throw for empty endpoint")
        } catch {
            // Expected.
        }
    }

    // ── Warm-start ordering guard (gateway always wins over persisted) ──────

    private static let enabledJSON =
        #"{ "version": 2, "sdk": { "enabled": true }, "workflows": [] }"#
    private static let disabledJSON =
        #"{ "version": 2, "sdk": { "enabled": false }, "workflows": [] }"#

    @Test("gateway-then-persisted: stale persisted disable does NOT clobber fresh gateway enable")
    func gatewayThenPersistedDoesNotClobber() async {
        // Cold-start race where the gateway result lands FIRST: a subsequent
        // stale persisted `enabled = false` must be discarded so the SDK stays
        // enabled per the fresh gateway config.
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: false))
        let poller = ConfigPoller.testHelperBuildWithGate(
            gate: gate, suiteName: "io.dash0.test.warmstart.gw-first"
        )
        let gw = await poller.testApply(jsonString: Self.enabledJSON, source: "gateway")
        #expect(gw == true)
        #expect(poller.testGatewayConfigApplied == true)
        let persisted = await poller.testApply(jsonString: Self.disabledJSON, source: "persisted")
        #expect(persisted == false, "stale persisted apply must be discarded once a gateway result won")
        #expect(gate.isEnabled == true, "gateway enable must survive the stale persisted disable")
    }

    @Test("persisted-then-gateway: fresh gateway enable overrides persisted disable")
    func persistedThenGatewayOverrides() async {
        // Cold-start race where the persisted snapshot lands FIRST (disabled),
        // then the gateway returns a fresh enable. The gateway must win.
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: true))
        let poller = ConfigPoller.testHelperBuildWithGate(
            gate: gate, suiteName: "io.dash0.test.warmstart.persist-first"
        )
        let persisted = await poller.testApply(jsonString: Self.disabledJSON, source: "persisted")
        #expect(persisted == true, "a persisted disable IS applied on a true cold start")
        #expect(gate.isEnabled == false)
        let gw = await poller.testApply(jsonString: Self.enabledJSON, source: "gateway")
        #expect(gw == true)
        #expect(gate.isEnabled == true, "fresh gateway enable must override the persisted disable")
    }

    @Test("persisted disable IS applied on a true cold start (keep-last across restarts)")
    func persistedDisableAppliedColdStart() async {
        // No gateway result has arrived — the persisted disable must take
        // effect so a kill switch survives an app restart.
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: true))
        let poller = ConfigPoller.testHelperBuildWithGate(
            gate: gate, suiteName: "io.dash0.test.warmstart.cold"
        )
        let persisted = await poller.testApply(jsonString: Self.disabledJSON, source: "persisted")
        #expect(persisted == true)
        #expect(gate.isEnabled == false, "persisted disable must apply when no gateway result has arrived")
    }
}
