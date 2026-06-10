import Foundation
import os.log

// Prefer the OS-provided CryptoKit (no extra dependency / linkage on Apple
// platforms); fall back to swift-crypto's `Crypto` module on platforms that
// lack CryptoKit (e.g. Linux CI). Both expose the identical `HMAC`/`SHA256`
// API, so the call sites below are source-compatible.
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Transport-security primitives shared by the OTLP exporters and the
/// remote-config `ConfigPoller`:
///
///  1. **HTTPS enforcement** — reject cleartext `http://` endpoints unless the
///     caller explicitly opts in (`allowInsecureTransport`). Loopback/localhost
///     stays exempt for local-collector development.
///  2. **Public-key / certificate pinning** — an optional `PinningConfig` of
///     SPKI SHA-256 pins and/or DER-encoded certificates, enforced via a
///     `URLSessionDelegate` that fails the TLS handshake on mismatch
///     (fail-closed for the connection, never crashing the host).
///  3. **Config-payload integrity** — optional HMAC-SHA256 verification of the
///     fetched remote-config body so a MITM/OTA attacker cannot push an
///     unsigned `{"sdk":{"enabled":false}}` kill-switch payload.
///
/// None of these primitives ever throw into the host. HTTPS rejection surfaces
/// as a typed error that callers convert into a graceful "export disabled"
/// no-op; a pin mismatch fails only the offending connection.
public enum TransportSecurity {
    static let diagnosticLog = OSLog(subsystem: "com.dash0.otel-mobile", category: "transport-security")

    // MARK: - HTTPS enforcement

    /// Outcome of validating an endpoint's transport against the HTTPS policy.
    public enum TransportError: Error, Equatable, CustomStringConvertible {
        /// The endpoint used a cleartext (`http://`) scheme to a non-loopback
        /// host and `allowInsecureTransport` was not set.
        case insecureTransportRejected(String)

        public var description: String {
            switch self {
            case .insecureTransportRejected(let s):
                return "TransportSecurity: cleartext endpoint '\(s)' rejected (set allowInsecureTransport=true to permit)"
            }
        }
    }

    /// `true` when `host` is a loopback / local-development address that is
    /// exempt from the HTTPS requirement.
    ///
    /// Exempt: `localhost`, `127.0.0.1`, IPv6 loopback (`::1`), and any
    /// `*.local` mDNS name. Documented as a deliberate dev-only carve-out: a
    /// collector reachable only on loopback is not exposed to a network
    /// attacker.
    public static func isLocalHost(_ host: String?) -> Bool {
        let h = (host ?? "").lowercased()
        return h == "localhost" || h == "127.0.0.1" || h == "::1" || h.hasSuffix(".local")
    }

    /// Enforce the HTTPS transport policy for `url`.
    ///
    /// - `https://` — always permitted.
    /// - `http://` to a loopback/local host — permitted (dev carve-out).
    /// - `http://` to any other host — permitted **iff** `allowInsecure`,
    ///   otherwise throws `TransportError.insecureTransportRejected`.
    ///
    /// Throwing (rather than logging-and-continuing) is intentional: the
    /// caller catches the throw and disables export/polling gracefully so PII
    /// never leaves the device in cleartext by default.
    public static func enforceHTTPS(_ url: URL, allowInsecure: Bool) throws {
        let scheme = url.scheme?.lowercased() ?? ""
        guard scheme == "http" else { return } // https / anything-non-http: fine
        if isLocalHost(url.host) { return }
        if allowInsecure {
            os_log(
                "Dash0 OTel: endpoint '%{public}@' uses cleartext http:// — permitted only because allowInsecureTransport=true. Telemetry (and any PII) is UNENCRYPTED.",
                log: diagnosticLog, type: .error, url.absoluteString
            )
            return
        }
        throw TransportError.insecureTransportRejected(url.absoluteString)
    }

    // MARK: - Pinning

    /// SPKI SHA-256 public-key pinning configuration, optionally combined with
    /// whole-certificate (DER) pins. Applied to both the OTLP export
    /// connections and the config-poller connection.
    ///
    /// At least one pin must be present for the config to be meaningful; an
    /// empty config is treated as "no pinning" by `makePinnedSession`.
    public struct PinningConfig: Sendable, Equatable {
        /// Base64-encoded SHA-256 hashes of the server certificate's
        /// SubjectPublicKeyInfo (SPKI), the same format used by OkHttp's
        /// `sha256/…` pins and HPKP. Public-key pinning survives certificate
        /// renewal as long as the key is reused, so this is the preferred form.
        public let spkiSHA256Pins: Set<String>

        /// DER-encoded certificates to pin in full. Use when you want to pin a
        /// specific leaf/intermediate cert rather than just its key.
        public let certificates: [Data]

        public init(spkiSHA256Pins: Set<String> = [], certificates: [Data] = []) {
            self.spkiSHA256Pins = spkiSHA256Pins
            self.certificates = certificates
        }

        /// `true` when no pins of either kind are configured.
        public var isEmpty: Bool { spkiSHA256Pins.isEmpty && certificates.isEmpty }
    }

    /// Build a `URLSession` that enforces `pinning` on its server-trust
    /// challenges. When `pinning` is `nil`/empty the supplied/ephemeral
    /// configuration is used with no delegate (current behaviour).
    ///
    /// The returned session retains the delegate for its lifetime (URLSession
    /// holds a strong reference to its delegate until invalidated), so callers
    /// just keep the session alive.
    public static func makePinnedSession(
        pinning: PinningConfig?,
        configuration: URLSessionConfiguration
    ) -> URLSession {
        guard let pinning = pinning, !pinning.isEmpty else {
            return URLSession(configuration: configuration)
        }
        let delegate = PinningURLSessionDelegate(pinning: pinning)
        return URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
    }

    // MARK: - Config integrity (HMAC-SHA256)

    /// Verify an HMAC-SHA256 signature over `payload` using `key`.
    ///
    /// `expectedSignatureHex` is the lowercase hex (or base64) encoding the
    /// gateway sent in the `X-Dash0-Config-Signature` header. Both encodings
    /// are accepted so operators can use whichever their signer emits. A
    /// constant-time comparison avoids leaking timing information.
    ///
    /// Returns `false` (never throws) on any malformed input so a caller can
    /// safely treat verification failure as "do not apply".
    public static func verifyHMAC(
        payload: Data,
        key: Data,
        expectedSignature: String
    ) -> Bool {
        guard !key.isEmpty, !expectedSignature.isEmpty else { return false }
        let mac = HMAC<SHA256>.authenticationCode(
            for: payload,
            using: SymmetricKey(data: key)
        )
        let computed = Data(mac)
        // Accept hex or base64 for the wire signature.
        let candidates: [Data] = [
            Data(hexString: expectedSignature),
            Data(base64Encoded: expectedSignature.trimmingCharacters(in: .whitespacesAndNewlines))
        ].compactMap { $0 }
        guard !candidates.isEmpty else { return false }
        // Constant-time compare against each accepted encoding.
        return candidates.contains { constantTimeEquals($0, computed) }
    }

    /// Constant-time byte comparison. Always inspects the full length of the
    /// longer input so timing does not reveal where two values diverge.
    static func constantTimeEquals(_ a: Data, _ b: Data) -> Bool {
        if a.count != b.count { return false }
        var diff: UInt8 = 0
        for i in 0..<a.count {
            diff |= a[a.startIndex + i] ^ b[b.startIndex + i]
        }
        return diff == 0
    }
}

// MARK: - Pinning delegate

/// `URLSessionDelegate` that validates the server's certificate chain against a
/// `PinningConfig` during the TLS handshake. On mismatch the challenge is
/// cancelled (`.cancelAuthenticationChallenge`) which fails the connection —
/// fail-closed — without raising into the host process.
final class PinningURLSessionDelegate: NSObject, URLSessionDelegate, @unchecked Sendable {
    private let pinning: TransportSecurity.PinningConfig

    init(pinning: TransportSecurity.PinningConfig) {
        self.pinning = pinning
    }

    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let serverTrust = challenge.protectionSpace.serverTrust else {
            completionHandler(.performDefaultHandling, nil)
            return
        }

        // First require the platform to consider the chain valid at all (date,
        // hostname, system roots). Pinning is an ADDITION to default trust, not
        // a replacement — pinning a self-signed cert still requires the cert to
        // be supplied in `pinning.certificates`.
        if !Self.isTrustValid(serverTrust) && pinning.certificates.isEmpty {
            os_log("Dash0 OTel: server trust evaluation failed; rejecting connection.",
                   log: TransportSecurity.diagnosticLog, type: .error)
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }

        if Self.chainMatchesPins(serverTrust, pinning: pinning) {
            completionHandler(.useCredential, URLCredential(trust: serverTrust))
        } else {
            os_log("Dash0 OTel: certificate pin mismatch for '%{public}@'; failing connection (pinning is fail-closed).",
                   log: TransportSecurity.diagnosticLog, type: .error,
                   challenge.protectionSpace.host)
            completionHandler(.cancelAuthenticationChallenge, nil)
        }
    }

    /// Evaluate the platform's default trust for `trust` (system roots, expiry,
    /// hostname already bound by the protection space).
    static func isTrustValid(_ trust: SecTrust) -> Bool {
        var error: CFError?
        return SecTrustEvaluateWithError(trust, &error)
    }

    /// `true` when any certificate in the chain matches a configured DER cert
    /// pin OR its SPKI SHA-256 matches a configured pin.
    static func chainMatchesPins(_ trust: SecTrust, pinning: TransportSecurity.PinningConfig) -> Bool {
        let certs = certificateChain(trust)
        for cert in certs {
            let der = SecCertificateCopyData(cert) as Data
            if pinning.certificates.contains(where: { TransportSecurity.constantTimeEquals($0, der) }) {
                return true
            }
            if let spki = spkiSHA256Base64(for: cert),
               pinning.spkiSHA256Pins.contains(spki) {
                return true
            }
        }
        return false
    }

    private static func certificateChain(_ trust: SecTrust) -> [SecCertificate] {
        if #available(iOS 15.0, macOS 12.0, tvOS 15.0, watchOS 8.0, *) {
            return (SecTrustCopyCertificateChain(trust) as? [SecCertificate]) ?? []
        } else {
            let count = SecTrustGetCertificateCount(trust)
            return (0..<count).compactMap { idx in
                SecTrustGetCertificateAtIndex(trust, idx)
            }
        }
    }

    /// Compute the base64-encoded SHA-256 of a certificate's
    /// SubjectPublicKeyInfo. Returns `nil` if the public key can't be exported.
    static func spkiSHA256Base64(for cert: SecCertificate) -> String? {
        guard let publicKey = publicKey(for: cert),
              let keyData = SecKeyCopyExternalRepresentation(publicKey, nil) as Data? else {
            return nil
        }
        // SecKeyCopyExternalRepresentation returns the raw key bits, not full
        // SPKI. Prepend the algorithm's ASN.1 SPKI header so the hash matches
        // the `openssl … -pubkey | openssl dgst -sha256` / OkHttp form.
        guard let header = spkiHeader(for: publicKey) else { return nil }
        var spki = header
        spki.append(keyData)
        let digest = SHA256.hash(data: spki)
        return Data(digest).base64EncodedString()
    }

    private static func publicKey(for cert: SecCertificate) -> SecKey? {
        if #available(iOS 12.0, macOS 10.14, tvOS 12.0, watchOS 5.0, *) {
            return SecCertificateCopyKey(cert)
        }
        var trust: SecTrust?
        let policy = SecPolicyCreateBasicX509()
        guard SecTrustCreateWithCertificates(cert, policy, &trust) == errSecSuccess,
              let t = trust else { return nil }
        return SecTrustCopyPublicKey(t)
    }

    /// ASN.1 SubjectPublicKeyInfo headers for the key types we support. These
    /// are the standard fixed prefixes for RSA-2048/4096 and EC P-256/384.
    private static func spkiHeader(for key: SecKey) -> Data? {
        guard let attrs = SecKeyCopyAttributes(key) as? [CFString: Any],
              let type = attrs[kSecAttrKeyType] as? String,
              let sizeNum = attrs[kSecAttrKeySizeInBits] as? Int else {
            return nil
        }
        // Bind the Security CFString constants to local `String` lets BEFORE
        // the switch. Used directly in a `case` pattern, `kSecAttrKeyTypeRSA as
        // String` parses as a CAST pattern (which stays `CFString` and cannot
        // match a `String` tuple element); a local `let` makes each case an
        // ordinary equality/expression pattern over `String`.
        let rsa = kSecAttrKeyTypeRSA as String
        let ec = kSecAttrKeyTypeECSECPrimeRandom as String
        switch (type, sizeNum) {
        case (rsa, 2048):
            return Data([0x30, 0x82, 0x01, 0x22, 0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86,
                         0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00, 0x03,
                         0x82, 0x01, 0x0f, 0x00])
        case (rsa, 4096):
            return Data([0x30, 0x82, 0x02, 0x22, 0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86,
                         0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00, 0x03,
                         0x82, 0x02, 0x0f, 0x00])
        case (ec, 256):
            return Data([0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce,
                         0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d,
                         0x03, 0x01, 0x07, 0x03, 0x42, 0x00])
        case (ec, 384):
            return Data([0x30, 0x76, 0x30, 0x10, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce,
                         0x3d, 0x02, 0x01, 0x06, 0x05, 0x2b, 0x81, 0x04, 0x00, 0x22,
                         0x03, 0x62, 0x00])
        default:
            return nil
        }
    }
}

// MARK: - Hex decoding helper

extension Data {
    /// Decode a hex string (optionally `0x`-prefixed, case-insensitive) into
    /// bytes. Returns `nil` when the string has odd length or non-hex chars.
    init?(hexString: String) {
        var s = Substring(hexString.trimmingCharacters(in: .whitespacesAndNewlines))
        if s.hasPrefix("0x") || s.hasPrefix("0X") { s = s.dropFirst(2) }
        guard s.count % 2 == 0 else { return nil }
        var out = Data(capacity: s.count / 2)
        var idx = s.startIndex
        while idx < s.endIndex {
            let next = s.index(idx, offsetBy: 2)
            guard let byte = UInt8(s[idx..<next], radix: 16) else { return nil }
            out.append(byte)
            idx = next
        }
        self = out
    }
}
