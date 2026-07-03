import Testing
import Foundation
@testable import OTelMobileSDK
import OpenTelemetrySdk
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

/// Unit coverage for the transport-security primitives wired into the OTLP
/// exporters, the crash-replay path, and the config poller:
///   - HTTPS enforcement (cleartext rejection + loopback/opt-in carve-outs)
///   - PinningConfig value semantics
///   - HMAC-SHA256 config-integrity verification (hex + base64, constant-time,
///     tamper / wrong-key / malformed-input rejection)
///   - the hex-decoding helper
@Suite("TransportSecurity")
struct TransportSecurityTests {

    // MARK: - HTTPS enforcement

    @Test("https endpoint is always permitted")
    func httpsPermitted() throws {
        let url = URL(string: "https://ingress.us-west-2.aws.dash0.com:4318")!
        try TransportSecurity.enforceHTTPS(url, allowInsecure: false)
        #expect(Bool(true)) // no throw
    }

    @Test("cleartext http to a non-loopback host is rejected by default")
    func cleartextRejected() {
        let url = URL(string: "http://collector.example.com:4318")!
        #expect(throws: TransportSecurity.TransportError.self) {
            try TransportSecurity.enforceHTTPS(url, allowInsecure: false)
        }
    }

    @Test("cleartext http is permitted when allowInsecure is set")
    func cleartextAllowedWithOptIn() throws {
        let url = URL(string: "http://collector.example.com:4318")!
        try TransportSecurity.enforceHTTPS(url, allowInsecure: true)
        #expect(Bool(true)) // no throw
    }

    @Test("loopback and *.local hosts are exempt from the https requirement")
    func loopbackExempt() throws {
        for host in ["http://localhost:4318",
                     "http://127.0.0.1:4318",
                     "http://[::1]:4318",
                     "http://my-mac.local:4318"] {
            let url = URL(string: host)!
            try TransportSecurity.enforceHTTPS(url, allowInsecure: false)
        }
        #expect(Bool(true)) // none threw
    }

    @Test("isLocalHost classifies loopback / mDNS names")
    func isLocalHostClassification() {
        #expect(TransportSecurity.isLocalHost("localhost"))
        #expect(TransportSecurity.isLocalHost("127.0.0.1"))
        #expect(TransportSecurity.isLocalHost("::1"))
        #expect(TransportSecurity.isLocalHost("printer.local"))
        #expect(TransportSecurity.isLocalHost("LOCALHOST")) // case-insensitive
        #expect(!TransportSecurity.isLocalHost("collector.example.com"))
        #expect(!TransportSecurity.isLocalHost(nil))
    }

    @Test("rejected-transport error carries the offending URL")
    func transportErrorDescription() {
        let err = TransportSecurity.TransportError.insecureTransportRejected("http://x.example")
        #expect(err.description.contains("http://x.example"))
        #expect(err.description.contains("allowInsecureTransport"))
    }

    // MARK: - PinningConfig

    @Test("empty pinning config is detected as empty")
    func emptyPinning() {
        #expect(TransportSecurity.PinningConfig().isEmpty)
        #expect(TransportSecurity.PinningConfig(spkiSHA256Pins: [], certificates: []).isEmpty)
    }

    @Test("non-empty pinning config (spki or cert) is not empty")
    func nonEmptyPinning() {
        #expect(!TransportSecurity.PinningConfig(spkiSHA256Pins: ["abc="]).isEmpty)
        #expect(!TransportSecurity.PinningConfig(certificates: [Data([0x30, 0x82])]).isEmpty)
    }

    @Test("makePinnedSession returns a plain session when pinning is nil/empty")
    func plainSessionWhenNoPinning() {
        let cfg = URLSessionConfiguration.ephemeral
        let s1 = TransportSecurity.makePinnedSession(pinning: nil, configuration: cfg)
        let s2 = TransportSecurity.makePinnedSession(pinning: .init(), configuration: cfg)
        // A delegate-less session has a nil delegate; the pinned variant installs one.
        #expect(s1.delegate == nil)
        #expect(s2.delegate == nil)
    }

    @Test("makePinnedSession installs the pinning delegate when pins exist")
    func pinnedSessionInstallsDelegate() {
        let cfg = URLSessionConfiguration.ephemeral
        let session = TransportSecurity.makePinnedSession(
            pinning: .init(spkiSHA256Pins: ["AAAA"]),
            configuration: cfg
        )
        #expect(session.delegate is PinningURLSessionDelegate)
        session.invalidateAndCancel() // release the retained delegate
    }

    // MARK: - HMAC config integrity

    private func hmacHex(_ payload: Data, key: Data) -> String {
        let mac = HMAC<SHA256>.authenticationCode(for: payload, using: SymmetricKey(data: key))
        return Data(mac).map { String(format: "%02x", $0) }.joined()
    }

    private func hmacBase64(_ payload: Data, key: Data) -> String {
        let mac = HMAC<SHA256>.authenticationCode(for: payload, using: SymmetricKey(data: key))
        return Data(mac).base64EncodedString()
    }

    @Test("verifyHMAC accepts a correct hex signature")
    func verifyHexSignature() {
        let payload = Data("{\"sdk\":{\"enabled\":true}}".utf8)
        let key = Data("super-secret-key".utf8)
        let sig = hmacHex(payload, key: key)
        #expect(TransportSecurity.verifyHMAC(payload: payload, key: key, expectedSignature: sig))
    }

    @Test("verifyHMAC accepts a correct base64 signature")
    func verifyBase64Signature() {
        let payload = Data("{\"sdk\":{\"enabled\":true}}".utf8)
        let key = Data("super-secret-key".utf8)
        let sig = hmacBase64(payload, key: key)
        #expect(TransportSecurity.verifyHMAC(payload: payload, key: key, expectedSignature: sig))
    }

    @Test("verifyHMAC rejects a tampered payload (kill-switch MITM vector)")
    func verifyRejectsTamperedPayload() {
        let original = Data("{\"sdk\":{\"enabled\":true}}".utf8)
        let key = Data("super-secret-key".utf8)
        let sig = hmacHex(original, key: key)
        // Attacker swaps in a kill-switch payload but can't recompute the MAC.
        let tampered = Data("{\"sdk\":{\"enabled\":false}}".utf8)
        #expect(!TransportSecurity.verifyHMAC(payload: tampered, key: key, expectedSignature: sig))
    }

    @Test("verifyHMAC rejects a wrong key")
    func verifyRejectsWrongKey() {
        let payload = Data("body".utf8)
        let sig = hmacHex(payload, key: Data("right-key".utf8))
        #expect(!TransportSecurity.verifyHMAC(payload: payload, key: Data("wrong-key".utf8), expectedSignature: sig))
    }

    @Test("verifyHMAC returns false for empty key or empty signature")
    func verifyRejectsEmptyInputs() {
        let payload = Data("body".utf8)
        #expect(!TransportSecurity.verifyHMAC(payload: payload, key: Data(), expectedSignature: "deadbeef"))
        #expect(!TransportSecurity.verifyHMAC(payload: payload, key: Data("k".utf8), expectedSignature: ""))
    }

    @Test("verifyHMAC returns false for a malformed (non-hex, non-base64) signature")
    func verifyRejectsMalformedSignature() {
        let payload = Data("body".utf8)
        let key = Data("k".utf8)
        // "zz" is neither valid hex (z not hex) nor decodes to the right length.
        #expect(!TransportSecurity.verifyHMAC(payload: payload, key: key, expectedSignature: "zz"))
    }

    @Test("constantTimeEquals is correct for equal / unequal / mismatched-length inputs")
    func constantTimeEquals() {
        #expect(TransportSecurity.constantTimeEquals(Data([1, 2, 3]), Data([1, 2, 3])))
        #expect(!TransportSecurity.constantTimeEquals(Data([1, 2, 3]), Data([1, 2, 4])))
        #expect(!TransportSecurity.constantTimeEquals(Data([1, 2, 3]), Data([1, 2])))
        #expect(TransportSecurity.constantTimeEquals(Data(), Data()))
    }

    // MARK: - Hex decoding helper

    @Test("Data(hexString:) decodes valid hex, with and without 0x prefix")
    func hexDecode() {
        #expect(Data(hexString: "deadbeef") == Data([0xde, 0xad, 0xbe, 0xef]))
        #expect(Data(hexString: "0xDEADBEEF") == Data([0xde, 0xad, 0xbe, 0xef]))
        #expect(Data(hexString: "") == Data())
    }

    @Test("Data(hexString:) rejects odd-length and non-hex input")
    func hexDecodeRejects() {
        #expect(Data(hexString: "abc") == nil)   // odd length
        #expect(Data(hexString: "zz") == nil)     // non-hex chars
    }
}

/// Coverage for the no-op exporters substituted when transport is disabled.
@Suite("DisabledExporters")
struct DisabledExportersTests {
    @Test("disabled log exporter reports success and never throws")
    func disabledLog() {
        let e = DisabledLogRecordExporter()
        #expect(e.export(logRecords: [], explicitTimeout: nil) == .success)
        #expect(e.forceFlush(explicitTimeout: nil) == .success)
        e.shutdown(explicitTimeout: nil)
    }

    @Test("disabled span exporter (a class, per SpanExporter being AnyObject) reports success")
    func disabledSpan() {
        let e = DisabledSpanExporter()
        #expect(e.export(spans: [], explicitTimeout: nil) == .success)
        #expect(e.flush(explicitTimeout: nil) == .success)
        e.shutdown(explicitTimeout: nil)
    }

    @Test("disabled metric exporter reports success and selects cumulative temporality")
    func disabledMetric() {
        let e = DisabledMetricExporter()
        #expect(e.export(metrics: []) == .success)
        #expect(e.flush() == .success)
        #expect(e.shutdown() == .success)
        #expect(e.getAggregationTemporality(for: .counter) == .cumulative)
    }
}
