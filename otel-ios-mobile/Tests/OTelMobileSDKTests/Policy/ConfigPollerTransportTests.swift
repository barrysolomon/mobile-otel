import Testing
import Foundation
@testable import OTelMobileSDK

/// Transport-security coverage for the config poller: HTTPS enforcement at
/// construction, the loopback / opt-in carve-outs, and the case-insensitive
/// signature-header lookup that gates HMAC verification.
@Suite("ConfigPoller transport security")
struct ConfigPollerTransportTests {

    private func makePoller(
        endpoint: String,
        allowInsecure: Bool = false,
        pinning: TransportSecurity.PinningConfig? = nil,
        signingKey: Data? = nil
    ) throws -> ConfigPoller {
        try ConfigPoller(
            gatewayEndpoint: endpoint,
            authToken: nil,
            evaluator: PolicyEvaluator(),
            allowInsecureTransport: allowInsecure,
            pinning: pinning,
            configSigningKey: signingKey
        )
    }

    @Test("https config endpoint constructs without throwing")
    func httpsConstructs() throws {
        _ = try makePoller(endpoint: "https://gateway.dash0.com/config")
        #expect(Bool(true))
    }

    @Test("cleartext http config endpoint to a non-loopback host is rejected")
    func cleartextRejected() {
        #expect(throws: TransportSecurity.TransportError.self) {
            _ = try makePoller(endpoint: "http://gateway.example.com/config")
        }
    }

    @Test("cleartext http config endpoint is permitted with allowInsecureTransport")
    func cleartextAllowedWithOptIn() throws {
        _ = try makePoller(endpoint: "http://gateway.example.com/config", allowInsecure: true)
        #expect(Bool(true))
    }

    @Test("localhost config endpoint is exempt from the https requirement")
    func localhostExempt() throws {
        _ = try makePoller(endpoint: "http://localhost:4318/config")
        #expect(Bool(true))
    }

    @Test("poller with pinning + signing key constructs against an https endpoint")
    func pinnedSignedConstructs() throws {
        _ = try makePoller(
            endpoint: "https://gateway.dash0.com/config",
            pinning: .init(spkiSHA256Pins: ["AAAA"]),
            signingKey: Data("secret".utf8)
        )
        #expect(Bool(true))
    }

    // MARK: - signature header lookup

    @Test("signatureHeader reads the X-Dash0-Config-Signature header")
    func signatureHeaderExactCase() throws {
        let url = URL(string: "https://gateway.dash0.com/config")!
        let resp = HTTPURLResponse(
            url: url, statusCode: 200, httpVersion: nil,
            headerFields: ["X-Dash0-Config-Signature": "deadbeef"]
        )!
        #expect(ConfigPoller.signatureHeader(from: resp) == "deadbeef")
    }

    @Test("signatureHeader lookup is case-insensitive")
    func signatureHeaderCaseInsensitive() throws {
        let url = URL(string: "https://gateway.dash0.com/config")!
        let resp = HTTPURLResponse(
            url: url, statusCode: 200, httpVersion: nil,
            headerFields: ["x-dash0-config-signature": "cafef00d"]
        )!
        #expect(ConfigPoller.signatureHeader(from: resp) == "cafef00d")
    }

    @Test("signatureHeader returns nil when the header is absent")
    func signatureHeaderAbsent() throws {
        let url = URL(string: "https://gateway.dash0.com/config")!
        let resp = HTTPURLResponse(
            url: url, statusCode: 200, httpVersion: nil,
            headerFields: ["Content-Type": "application/json"]
        )!
        #expect(ConfigPoller.signatureHeader(from: resp) == nil)
    }
}
