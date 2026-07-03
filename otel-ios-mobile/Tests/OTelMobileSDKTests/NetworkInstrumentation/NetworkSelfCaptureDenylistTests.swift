import Testing
@testable import OTelMobileSDK
import NetworkInstrumentation

/// `OTelMobile.start(config:)` auto-adds the configured OTLP endpoint host
/// to `NetworkConfig.ignoredHosts` so that NetworkInstrumentation (which
/// intercepts all URLSession traffic via URLProtocol) does NOT capture the
/// SDK's own OTLP export calls. Without this, every `POST /v1/traces` or
/// `POST /v1/logs` would generate a span that itself would be exported —
/// an amplification loop visible in Dash0 as many `POST` spans pointing at
/// the ingress host.
///
/// These tests pin that contract: rename the host-derivation logic or drop
/// it entirely and one of these cases will fail loudly.
@Suite("NetworkSelfCaptureDenylist")
struct NetworkSelfCaptureDenylistTests {
    @Test("endpoint host is added to ignoredHosts (lowercased)")
    func endpointHostIsDenylisted() {
        let config = OTelMobile.makeNetworkConfig(
            endpoint: "https://ingress.us-west-2.aws.dash0.com:4318"
        )
        #expect(config.ignoredHosts.contains("ingress.us-west-2.aws.dash0.com"))
    }

    @Test("uppercase host is normalised to lowercase")
    func uppercaseHostLowercased() {
        let config = OTelMobile.makeNetworkConfig(
            endpoint: "https://INGRESS.US-WEST-2.AWS.DASH0.COM:4318"
        )
        // NetworkInstrumentation matches hosts case-insensitively by storing
        // them lowercased; we rely on that invariant here.
        #expect(config.ignoredHosts.contains("ingress.us-west-2.aws.dash0.com"))
    }

    @Test("other NetworkConfig defaults are preserved when the host is added")
    func preservesDefaults() {
        let base = NetworkConfig.default
        let config = OTelMobile.makeNetworkConfig(
            endpoint: "https://ingress.us-west-2.aws.dash0.com:4318"
        )
        #expect(config.stripQueryStrings == base.stripQueryStrings)
        #expect(config.capturedResponseHeaders == base.capturedResponseHeaders)
        #expect(config.capturedRequestHeaders == base.capturedRequestHeaders)
        #expect(config.errorStatusThreshold == base.errorStatusThreshold)
        #expect(config.propagateTraceContext == base.propagateTraceContext)
        #expect(config.allowedHosts == base.allowedHosts)
    }

    @Test("unparseable endpoint falls back to defaults (no crash, no host added)")
    func unparseableEndpointFallsBack() {
        let base = NetworkConfig.default
        let config = OTelMobile.makeNetworkConfig(endpoint: "not-a-url")
        // URL(string:) accepts many strings but extracting .host may yield
        // nil. We return the base config unchanged in that case.
        #expect(config.ignoredHosts == base.ignoredHosts)
    }

    @Test("http scheme works too (not just https)")
    func httpSchemeWorks() {
        let config = OTelMobile.makeNetworkConfig(
            endpoint: "http://localhost:4318"
        )
        #expect(config.ignoredHosts.contains("localhost"))
    }
}
