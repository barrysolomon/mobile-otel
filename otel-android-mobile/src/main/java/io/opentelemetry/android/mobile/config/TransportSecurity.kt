/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.config

import android.util.Log
import okhttp3.CertificatePinner
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Transport-security primitives shared by the OTLP exporters and the
 * remote-config poller. This is the Android counterpart to the iOS
 * `TransportSecurity` enum and matches its API names / semantics so the
 * cross-platform "transport security" claim is real on both platforms:
 *
 *  1. **HTTPS enforcement** ([enforceHttps]) — reject a cleartext `http://`
 *     endpoint to a non-loopback host unless the caller opts in
 *     (`allowInsecureTransport`). Loopback / localhost stays exempt for local
 *     collector development (matches iOS `isLocalHost`, plus the Android
 *     emulator-loopback `10.0.2.2`).
 *  2. **Public-key / certificate pinning** ([PinningConfig], [pinningSslContext],
 *     [certificatePinner]) — an optional set of SPKI SHA-256 pins and/or
 *     DER-encoded certificates. On the OTLP/HTTP path the pins are enforced via
 *     a pinning [X509TrustManager] (the OTLP HTTP exporter builder exposes only
 *     `setSslContext`, not the underlying OkHttp client, so we cannot use OkHttp
 *     `CertificatePinner` there). On the config-poller path — which owns its own
 *     OkHttp client — the pins are enforced with OkHttp `CertificatePinner`.
 *     Either way a pin mismatch fails ONLY that TLS connection (fail-closed for
 *     the connection), never the host process. Mirrors iOS `PinningConfig`.
 *  3. **Config-payload integrity** ([verifyHmacSha256]) — optional HMAC-SHA256
 *     verification of the fetched remote-config body so a MITM/OTA attacker
 *     cannot push an unsigned kill-switch payload. Mirrors iOS `verifyHMAC`.
 *
 * None of these primitives ever throw into the host. HTTPS rejection returns a
 * boolean the caller converts into a graceful "export/poll disabled" no-op; a
 * pin mismatch fails only the offending connection; HMAC verification returns
 * `false` (never throws) on any malformed input.
 */
object TransportSecurity {

    private const val TAG = "TransportSecurity"

    /**
     * Hosts that are exempt from the HTTPS requirement because they are not
     * reachable by a network attacker. Mirrors iOS `isLocalHost` (localhost /
     * 127.0.0.1 / ::1 / `*.local`) and additionally exempts the Android
     * emulator host-loopback alias `10.0.2.2` and the fully-expanded IPv6
     * loopback, consistent with the SDK's prior [MobileConfig] localhost logic.
     */
    private val LOOPBACK_HOSTS = setOf(
        "localhost",
        "127.0.0.1",
        "10.0.2.2",
        "::1",
        "0:0:0:0:0:0:0:1",
    )

    // ── HTTPS enforcement ─────────────────────────────────────────────────────

    /**
     * `true` when [host] is a loopback / local-development address exempt from
     * the HTTPS requirement. Case-insensitive. Matches iOS `isLocalHost` (with
     * the documented Android-emulator carve-out, see [LOOPBACK_HOSTS]).
     */
    fun isLocalHost(host: String?): Boolean {
        val h = (host ?: "").lowercase()
        if (h.isEmpty()) return false
        return h in LOOPBACK_HOSTS || h.endsWith(".local")
    }

    /**
     * Extract the bare host from a `scheme://authority/...` endpoint, stripping
     * the scheme, userinfo, port, path, and IPv6 brackets. Returns `null` when
     * the endpoint cannot be parsed.
     */
    internal fun hostOf(endpoint: String): String? {
        val noScheme = endpoint.substringAfter("://", endpoint)
        // authority ends at the first '/', '?' or '#'
        val authority = noScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        // strip userinfo (user:pass@host)
        val afterUserInfo = if (authority.contains('@')) authority.substringAfterLast('@') else authority
        if (afterUserInfo.isEmpty()) return null
        return if (afterUserInfo.startsWith("[")) {
            // bracketed IPv6 literal: [::1]:4317 → ::1
            afterUserInfo.substringAfter('[').substringBefore(']')
        } else {
            afterUserInfo.substringBefore(':')
        }
    }

    /**
     * Enforce the HTTPS transport policy for [endpoint].
     *
     * - `https://` (or any non-`http` scheme) — always permitted.
     * - `http://` to a loopback / local host — permitted (dev carve-out).
     * - `http://` to any other host — permitted **iff** [allowInsecure],
     *   otherwise rejected.
     *
     * Returns `true` when the endpoint may be used, `false` when it must be
     * rejected. Unlike iOS (which throws a typed error), this returns a boolean
     * so the Android callers degrade gracefully without exception flow — the
     * host is never crashed on a transport-policy failure. Logs loudly in both
     * the `allowInsecure` (permitted-but-unencrypted) and rejected cases.
     */
    fun enforceHttps(endpoint: String, allowInsecure: Boolean): Boolean {
        val trimmed = endpoint.trim()
        val scheme = trimmed.substringBefore("://", "").lowercase()
        if (scheme != "http") return true // https / non-http: fine
        val host = hostOf(trimmed)
        if (isLocalHost(host)) return true
        if (allowInsecure) {
            Log.e(
                TAG,
                "SECURITY: endpoint '$endpoint' uses cleartext http:// to a non-loopback host — " +
                    "permitted only because allowInsecureTransport=true. Telemetry (and any PII / " +
                    "ingest auth token) is UNENCRYPTED on the wire.",
            )
            return true
        }
        Log.e(
            TAG,
            "SECURITY: endpoint '$endpoint' uses cleartext http:// to a non-loopback host and " +
                "allowInsecureTransport=false — rejecting this transport so PII never leaves the " +
                "device in cleartext by default. The associated pipeline is disabled (the SDK does " +
                "NOT crash the host). Use https:// or set allowInsecureTransport=true for a " +
                "deliberate, network-isolated deployment.",
        )
        return false
    }

    // ── Pinning ────────────────────────────────────────────────────────────────

    /**
     * SPKI SHA-256 public-key pinning configuration, optionally combined with
     * whole-certificate (DER) pins. Applied to BOTH the OTLP export connections
     * and the config-poller connection. Mirrors iOS `TransportSecurity.PinningConfig`
     * (`spkiSHA256Pins` + `certificates`).
     *
     * At least one pin must be present for the config to be meaningful; an empty
     * config is treated as "no pinning".
     *
     * @property spkiSha256Pins Base64-encoded SHA-256 hashes of the server
     *   certificate's SubjectPublicKeyInfo (SPKI) — the same `sha256/…` format
     *   OkHttp's [CertificatePinner] and HPKP use, WITHOUT the `sha256/` prefix
     *   (the prefix is added internally when building the OkHttp pinner). Public-
     *   key pinning survives certificate renewal as long as the key is reused, so
     *   this is the preferred form. Matches iOS `spkiSHA256Pins`.
     * @property certificates DER-encoded certificates to pin in full. Use when
     *   you want to pin a specific leaf/intermediate cert rather than just its
     *   key. Matches iOS `certificates`.
     */
    data class PinningConfig(
        val spkiSha256Pins: Set<String> = emptySet(),
        val certificates: List<ByteArray> = emptyList(),
    ) {
        /** `true` when no pins of either kind are configured. */
        val isEmpty: Boolean get() = spkiSha256Pins.isEmpty() && certificates.isEmpty()

        // data class with a ByteArray member needs value-based equals/hashCode.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PinningConfig) return false
            if (spkiSha256Pins != other.spkiSha256Pins) return false
            if (certificates.size != other.certificates.size) return false
            for (i in certificates.indices) {
                if (!certificates[i].contentEquals(other.certificates[i])) return false
            }
            return true
        }

        override fun hashCode(): Int {
            var result = spkiSha256Pins.hashCode()
            for (c in certificates) result = 31 * result + c.contentHashCode()
            return result
        }
    }

    /**
     * Build an OkHttp [CertificatePinner] enforcing [pinning] for [hostname].
     * Returns `null` when [pinning] is `null`/empty (caller uses an unpinned
     * client — prior behaviour). Used by the config poller, which owns its own
     * OkHttp client. A pin mismatch fails that connection (fail-closed); OkHttp
     * surfaces it as an `SSLPeerUnverifiedException` the poller catches and
     * retries, never crashing the host.
     *
     * DER certificate pins are converted to SPKI SHA-256 pins so a single
     * [CertificatePinner] enforces both pin kinds. A DER cert that cannot be
     * parsed is skipped with a warning rather than failing the build.
     */
    fun certificatePinner(hostname: String, pinning: PinningConfig?): CertificatePinner? {
        if (pinning == null || pinning.isEmpty) return null
        val builder = CertificatePinner.Builder()
        var pinCount = 0
        for (pin in pinning.spkiSha256Pins) {
            if (pin.isBlank()) continue
            builder.add(hostname, "sha256/$pin")
            pinCount++
        }
        for (der in pinning.certificates) {
            val spki = spkiSha256Base64(der)
            if (spki != null) {
                builder.add(hostname, "sha256/$spki")
                pinCount++
            } else {
                Log.w(TAG, "Skipping unparseable DER certificate pin for '$hostname'")
            }
        }
        if (pinCount == 0) {
            Log.w(TAG, "PinningConfig produced no usable pins for '$hostname'; proceeding unpinned")
            return null
        }
        return builder.build()
    }

    /**
     * Build an [SSLContext] whose [X509TrustManager] enforces [pinning] in
     * addition to the platform's default trust evaluation. Returns `null` when
     * [pinning] is `null`/empty (caller leaves the exporter on the default TLS
     * stack — prior behaviour) or when the platform trust manager cannot be
     * resolved (degrade gracefully to default TLS rather than failing).
     *
     * This is the OTLP/HTTP path's pinning mechanism: the OTLP HTTP exporter
     * builder exposes `setSslContext(SSLContext, X509TrustManager)` but NOT the
     * underlying OkHttp client, so OkHttp `CertificatePinner` cannot be attached
     * there. A pinned [SSLContext] gives the equivalent fail-closed handshake
     * behaviour. Pinning is an ADDITION to default trust, not a replacement —
     * the chain must pass the platform's validation first (matches iOS).
     */
    fun pinningSslContext(pinning: PinningConfig?): Pair<SSLContext, X509TrustManager>? {
        if (pinning == null || pinning.isEmpty) return null
        val defaultTm = defaultTrustManager() ?: run {
            Log.w(TAG, "No platform X509TrustManager available; skipping OTLP pinning (default TLS used)")
            return null
        }
        val pinningTm = PinningTrustManager(defaultTm, pinning)
        return try {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(pinningTm), null)
            Pair(ctx, pinningTm)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to build pinned SSLContext; falling back to default TLS", t)
            null
        }
    }

    private fun defaultTrustManager(): X509TrustManager? {
        return try {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as java.security.KeyStore?)
            tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not obtain default X509TrustManager", t)
            null
        }
    }

    /**
     * Compute the base64-encoded SHA-256 of a DER certificate's
     * SubjectPublicKeyInfo (the `sha256/…` pin form). Returns `null` when the
     * certificate cannot be parsed.
     */
    internal fun spkiSha256Base64(der: ByteArray): String? {
        return try {
            val cert = parseCertificate(der) ?: return null
            spkiSha256Base64(cert)
        } catch (t: Throwable) {
            null
        }
    }

    private fun spkiSha256Base64(cert: X509Certificate): String {
        // X509Certificate.publicKey.encoded is the full DER-encoded
        // SubjectPublicKeyInfo (header + key bits), so hashing it directly
        // yields the same pin OkHttp / `openssl … -pubkey | dgst -sha256`
        // produce. No manual ASN.1 header reconstruction needed (unlike iOS,
        // whose SecKey API returns raw key bits).
        val spki = cert.publicKey.encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(spki)
        return android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)
    }

    private fun parseCertificate(der: ByteArray): X509Certificate? {
        return try {
            val cf = CertificateFactory.getInstance("X.509")
            cf.generateCertificate(der.inputStream()) as? X509Certificate
        } catch (e: CertificateException) {
            null
        }
    }

    // ── Config integrity (HMAC-SHA256) ──────────────────────────────────────────

    /**
     * Verify an HMAC-SHA256 signature over [payload] using [key].
     *
     * [expectedSignature] is the lowercase-hex (or base64) encoding the gateway
     * sent in the `X-Dash0-Config-Signature` header. Both encodings are accepted
     * so operators can use whichever their signer emits. A constant-time
     * comparison avoids leaking timing information. Mirrors iOS `verifyHMAC`.
     *
     * Returns `false` (never throws) on any malformed input so a caller can
     * safely treat verification failure as "do not apply".
     */
    fun verifyHmacSha256(payload: ByteArray, key: ByteArray, expectedSignature: String): Boolean {
        if (key.isEmpty() || expectedSignature.isBlank()) return false
        val computed = try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            mac.doFinal(payload)
        } catch (t: Throwable) {
            return false
        }
        val trimmed = expectedSignature.trim()
        // Accept hex or base64 for the wire signature; compare constant-time
        // against each accepted decoding.
        val candidates = listOfNotNull(decodeHex(trimmed), decodeBase64(trimmed))
        if (candidates.isEmpty()) return false
        return candidates.any { constantTimeEquals(it, computed) }
    }

    /**
     * Constant-time byte comparison. Returns `false` immediately on a length
     * mismatch (length is not secret), otherwise inspects every byte so timing
     * does not reveal where two equal-length values diverge. Mirrors iOS
     * `constantTimeEquals`.
     */
    internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }

    private fun decodeHex(s: String): ByteArray? {
        var str = s
        if (str.startsWith("0x") || str.startsWith("0X")) str = str.substring(2)
        if (str.isEmpty() || str.length % 2 != 0) return null
        val out = ByteArray(str.length / 2)
        var i = 0
        while (i < str.length) {
            val hi = Character.digit(str[i], 16)
            val lo = Character.digit(str[i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }

    private fun decodeBase64(s: String): ByteArray? {
        return try {
            android.util.Base64.decode(s, android.util.Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * The header the gateway sends carrying the HMAC-SHA256 signature (hex or
     * base64) over the raw response body. Matched case-insensitively by callers.
     * Matches the iOS `ConfigPoller.signatureHeaderName`.
     */
    const val SIGNATURE_HEADER_NAME = "X-Dash0-Config-Signature"
}

/**
 * [X509TrustManager] that runs the platform's default trust evaluation first
 * (system roots, expiry, hostname is bound by the SSL engine separately), then
 * requires at least one certificate in the validated chain to match a configured
 * SPKI SHA-256 pin or DER certificate pin. On mismatch it throws
 * [CertificateException], which fails the TLS handshake (fail-closed for the
 * connection) without raising into the host process. Android counterpart to the
 * iOS `PinningURLSessionDelegate`.
 */
internal class PinningTrustManager(
    private val delegate: X509TrustManager,
    private val pinning: TransportSecurity.PinningConfig,
) : X509TrustManager {

    private val spkiPins: Set<String> = pinning.spkiSha256Pins.filter { it.isNotBlank() }.toSet()

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // 1. Default platform trust must pass first — pinning is additive, not a
        //    replacement for chain validation.
        delegate.checkServerTrusted(chain, authType)
        if (chain == null || chain.isEmpty()) {
            throw CertificateException("Pinning: empty certificate chain")
        }
        // 2. Require a pin match somewhere in the validated chain.
        for (cert in chain) {
            // Whole-certificate (DER) pin.
            val der = cert.encoded
            if (pinning.certificates.any { TransportSecurity.constantTimeEquals(it, der) }) {
                return
            }
            // SPKI SHA-256 pin.
            val spki = spkiSha256Base64(cert)
            if (spki != null && spki in spkiPins) {
                return
            }
        }
        throw CertificateException(
            "Pinning: certificate pin mismatch; failing connection (pinning is fail-closed)",
        )
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        delegate.checkClientTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    private fun spkiSha256Base64(cert: X509Certificate): String? {
        return try {
            val spki = cert.publicKey.encoded
            val digest = MessageDigest.getInstance("SHA-256").digest(spki)
            android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)
        } catch (t: Throwable) {
            null
        }
    }
}
