import Testing
import Foundation
@testable import OTelMobileSDK
import OpenTelemetrySdk

/// Construction-safety coverage for threading `pinning` through the
/// synchronous log exporter into its `SynchronousHTTPClient` / `URLSession`.
/// (Actual pin enforcement happens during the TLS handshake and is validated
/// end-to-end against a real collector; here we cover the wiring surface — the
/// new `pinning` parameter must construct cleanly in all three shapes and the
/// exporter must shut down without throwing.)
@Suite("SynchronousLogRecordExporter transport")
struct SynchronousLogRecordExporterTransportTests {
    private let endpoint = URL(string: "https://ingress.us-west-2.aws.dash0.com:4318/v1/logs")!

    @Test("constructs with no pinning (prior behaviour)")
    func constructsWithoutPinning() {
        let exporter = SynchronousLogRecordExporter(
            endpoint: endpoint, authToken: "secret", extraHeaders: [:]
        )
        exporter.shutdown(explicitTimeout: nil)
        #expect(Bool(true)) // constructed + shut down without throwing
    }

    @Test("constructs with an explicit empty pinning config")
    func constructsWithEmptyPinning() {
        let exporter = SynchronousLogRecordExporter(
            endpoint: endpoint, authToken: nil, extraHeaders: [:],
            pinning: .init()
        )
        exporter.shutdown(explicitTimeout: nil)
        #expect(Bool(true))
    }

    @Test("constructs with a non-empty pinning config")
    func constructsWithPinning() {
        let exporter = SynchronousLogRecordExporter(
            endpoint: endpoint, authToken: "secret", extraHeaders: ["Dash0-Dataset": "demo"],
            pinning: .init(spkiSHA256Pins: ["AAAA"])
        )
        exporter.shutdown(explicitTimeout: nil)
        #expect(Bool(true))
    }
}
