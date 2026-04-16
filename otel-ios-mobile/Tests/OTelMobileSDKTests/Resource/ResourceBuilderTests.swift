import Testing
@testable import OTelMobileSDK
import OpenTelemetryApi
import OpenTelemetrySdk

/// Tests for `ResourceBuilder.buildMobileResource`. Runs on whatever host the
/// test process happens to be on — the `os.*` block adapts via `#if os(iOS)`
/// so these pass on both macOS (CLT) and the iPhone simulator.
///
/// `device.*` iOS attributes (identifier, vendor id) are not asserted here
/// because they're non-deterministic on the simulator and absent on macOS —
/// the service / SDK / OS coverage exercised below is what the integration
/// owners (Dash0 ingest, app owners looking at resource.* in the UI) care
/// about.
@Suite("ResourceBuilder")
struct ResourceBuilderTests {
    @Test("resource exposes service.name and service.version")
    func buildsWithServiceAttrs() {
        let resource = ResourceBuilder.buildMobileResource(
            serviceName: "demo-ios",
            serviceVersion: "1.2.3"
        )
        #expect(resource.attributes["service.name"] == .string("demo-ios"))
        #expect(resource.attributes["service.version"] == .string("1.2.3"))
    }

    @Test("resource exposes telemetry.sdk.* attributes")
    func buildsWithSdkAttrs() {
        let resource = ResourceBuilder.buildMobileResource(
            serviceName: "svc",
            serviceVersion: "1.0"
        )
        #expect(resource.attributes["telemetry.sdk.name"] == .string("io.dash0.mobile"))
        #expect(resource.attributes["telemetry.sdk.language"] == .string("swift"))
        #expect(resource.attributes["telemetry.sdk.version"] == .string(ResourceBuilder.sdkVersion))
    }

    @Test("resource carries os.* attributes for host platform")
    func containsOsAttributes() {
        let resource = ResourceBuilder.buildMobileResource(
            serviceName: "svc",
            serviceVersion: "1.0"
        )
        // os.type is "darwin" on both macOS and iOS — the only two platforms
        // we compile for.
        #expect(resource.attributes["os.type"] == .string("darwin"))

        // os.name differs between macOS (tests on host) and iOS simulator.
        // Accept either to keep the test useful on both.
        let expectedOsName: AttributeValue
        #if os(iOS)
        expectedOsName = .string("iOS")
        #else
        expectedOsName = .string("macOS")
        #endif
        #expect(resource.attributes["os.name"] == expectedOsName)

        // os.version is populated on both platforms (UIDevice on iOS,
        // ProcessInfo on macOS) — assert non-nil rather than matching an
        // exact string that varies by runner.
        #expect(resource.attributes["os.version"] != nil)

        // device.manufacturer is always "Apple" on the two platforms.
        #expect(resource.attributes["device.manufacturer"] == .string("Apple"))
    }

    @Test("extra attributes override defaults")
    func mergesExtraAttributes() {
        let resource = ResourceBuilder.buildMobileResource(
            serviceName: "base",
            serviceVersion: "1.0",
            extraAttributes: [
                // Override an existing default.
                "service.name": "overridden",
                // Add a brand-new key.
                "dash0.dataset": "mobile-qa",
            ]
        )
        #expect(resource.attributes["service.name"] == .string("overridden"))
        #expect(resource.attributes["dash0.dataset"] == .string("mobile-qa"))
        // Other defaults still present.
        #expect(resource.attributes["telemetry.sdk.name"] == .string("io.dash0.mobile"))
    }
}
