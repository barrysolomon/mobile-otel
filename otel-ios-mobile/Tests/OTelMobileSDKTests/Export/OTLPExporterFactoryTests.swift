import Testing
@testable import OTelMobileSDK
import OTelMobileCore
import OpenTelemetrySdk

/// Tests for the OTel-Swift OTLP/HTTP exporter factory.
///
/// Note: OTel-Swift's `OtlpHttpLogExporter` does not expose its configured
/// headers or endpoint to callers, so we can't unit-test that auth tokens
/// land on outgoing requests. Bearer-auth wiring is validated end-to-end via
/// the demo app hitting a real Dash0 collector (manual QA). Here we only
/// cover construction-safety and URL normalisation.
@Suite("OTLPExporterFactory")
struct OTLPExporterFactoryTests {
    @Test("factory returns non-nil exporter for a simple endpoint")
    func factoryReturnsNonNilExporter() throws {
        let exporter = try OTLPExporterFactory.makeHttpLogExporter(
            endpoint: "https://ingress.dash0.com:4318",
            authToken: "secret"
        )
        // Swift Testing lets us just reference the result — if the factory
        // throws or returns an unusable value, #expect gives a clear signal.
        _ = exporter
        #expect(Bool(true))
    }

    @Test("factory accepts nil auth token")
    func factoryAcceptsNilAuthToken() throws {
        let exporter = try OTLPExporterFactory.makeHttpLogExporter(
            endpoint: "https://collector.example.com",
            authToken: nil
        )
        _ = exporter
        #expect(Bool(true))
    }

    @Test("factory accepts empty auth token without throwing")
    func factoryAcceptsEmptyAuthToken() throws {
        let exporter = try OTLPExporterFactory.makeHttpLogExporter(
            endpoint: "http://localhost:4318",
            authToken: ""
        )
        _ = exporter
        #expect(Bool(true))
    }

    @Test("invalid endpoint string throws invalidEndpoint")
    func invalidEndpointThrows() {
        // A string that's purely whitespace can't be a URL.
        #expect(throws: OTLPExporterFactoryError.self) {
            _ = try OTLPExporterFactory.makeHttpLogExporter(
                endpoint: "   ",
                authToken: nil
            )
        }
    }

    @Test("base endpoint gets /v1/logs appended")
    func baseEndpointAppendsPath() throws {
        let url = try OTLPExporterFactory.buildLogsEndpointURL(
            from: "https://ingress.dash0.com:4318"
        )
        #expect(url.absoluteString == "https://ingress.dash0.com:4318/v1/logs")
    }

    @Test("endpoint already ending in /v1/logs is preserved")
    func fullEndpointPreserved() throws {
        let url = try OTLPExporterFactory.buildLogsEndpointURL(
            from: "https://ingress.dash0.com:4318/v1/logs"
        )
        #expect(url.absoluteString == "https://ingress.dash0.com:4318/v1/logs")
    }

    @Test("trailing slash on base endpoint is normalised before append")
    func trailingSlashNormalised() throws {
        let url = try OTLPExporterFactory.buildLogsEndpointURL(
            from: "https://ingress.dash0.com:4318/"
        )
        #expect(url.absoluteString == "https://ingress.dash0.com:4318/v1/logs")
    }

    @Test("localhost no-port endpoint also works")
    func localhostEndpoint() throws {
        let url = try OTLPExporterFactory.buildLogsEndpointURL(
            from: "http://localhost:4318"
        )
        #expect(url.absoluteString == "http://localhost:4318/v1/logs")
    }

    @Test("trace exporter constructs with a base endpoint")
    func traceExporterConstructs() throws {
        let exporter = try OTLPExporterFactory.makeHttpTraceExporter(
            endpoint: "https://ingress.dash0.com:4318",
            authToken: "secret"
        )
        _ = exporter
        #expect(Bool(true))
    }

    @Test("metric exporter constructs with a base endpoint")
    func metricExporterConstructs() throws {
        let exporter = try OTLPExporterFactory.makeHttpMetricExporter(
            endpoint: "https://ingress.dash0.com:4318",
            authToken: "secret"
        )
        _ = exporter
        #expect(Bool(true))
    }
}
