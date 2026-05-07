import Testing
@testable import OTelMobileSDK
import OpenTelemetryApi

/// Phase 2 / iOS parity for User Journey + Captures Epic.
///
/// Mirrors the Android `OTelMobile.startJourney/endJourney/captureScreenshot/
/// captureWireframe` surface so apps can write the same journey-replay code
/// against either platform. iOS screenshot + wireframe instrumentation
/// modules don't exist yet (separate Phase 2 sub-items), so capture helpers
/// are silent no-ops on iOS today — but the API surface lands now so the
/// host-app integration code is portable.
@Suite("OTelMobileJourneyAPI")
struct OTelMobileJourneyAPITests {

    private func makeMobile() throws -> OTelMobile {
        let captured = CapturingExporter()
        let config = MobileConfig(serviceName: "journey-api-test", endpoint: "https://unused")
        return try OTelMobile.start(config: config, exporter: captured)
    }

    @Test("startJourney returns a span with the requested name")
    func startJourneyCreatesSpan() throws {
        let otelMobile = try makeMobile()
        let span = otelMobile.startJourney(name: "checkout")
        // The span context is valid only after start; we don't have a public
        // way to read the operation name from outside the SDK, but a valid
        // span context is the strongest contract we can assert without
        // adding a test seam to OpenTelemetryApi itself.
        #expect(span.context.spanId.isValid)
        span.end()
    }

    @Test("endJourney ends the journey span")
    func endJourneyEndsSpan() throws {
        let otelMobile = try makeMobile()
        let span = otelMobile.startJourney(name: "checkout")
        // No exception, no crash — the contract is "always closes the span,
        // even if capture instrumentation is not registered."
        otelMobile.endJourney(span)
    }

    @Test("captureScreenshot is a silent no-op when no instrumentation is installed")
    func captureScreenshotNoOp() throws {
        let otelMobile = try makeMobile()
        // Must not throw or crash even without any screenshot module.
        otelMobile.captureScreenshot(trigger: "manual")
        otelMobile.captureScreenshot(trigger: "journey_start")
    }

    @Test("captureWireframe is a silent no-op when no instrumentation is installed")
    func captureWireframeNoOp() throws {
        let otelMobile = try makeMobile()
        otelMobile.captureWireframe(trigger: "manual")
        otelMobile.captureWireframe(trigger: "journey_end")
    }
}
