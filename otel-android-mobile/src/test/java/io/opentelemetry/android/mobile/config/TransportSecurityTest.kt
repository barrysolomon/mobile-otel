/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.config

import okhttp3.tls.HeldCertificate
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.cert.CertificateException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit + behaviour tests for [TransportSecurity], the Android counterpart to the
 * iOS `TransportSecurity` enum. Covers HTTPS enforcement, the loopback carve-out,
 * SPKI/DER certificate pinning (build + fail-closed evaluation), and HMAC-SHA256
 * config-signature verification (hex/base64, constant-time).
 *
 * Robolectric is required so `android.util.Base64` / `android.util.Log` are real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransportSecurityTest {

    // ── HTTPS enforcement ─────────────────────────────────────────────────────

    @Test
    fun `https endpoint is always permitted`() {
        assertTrue(TransportSecurity.enforceHttps("https://collector.example.com:4317", allowInsecure = false))
        assertTrue(TransportSecurity.enforceHttps("https://collector.example.com:4317", allowInsecure = true))
    }

    @Test
    fun `cleartext http to non-loopback host rejected by default`() {
        assertFalse(TransportSecurity.enforceHttps("http://collector.example.com:4317", allowInsecure = false))
    }

    @Test
    fun `cleartext http to non-loopback host permitted when allowInsecure true`() {
        assertTrue(TransportSecurity.enforceHttps("http://collector.example.com:4317", allowInsecure = true))
    }

    @Test
    fun `localhost http is exempt regardless of allowInsecure`() {
        assertTrue(TransportSecurity.enforceHttps("http://localhost:4317", allowInsecure = false))
        assertTrue(TransportSecurity.enforceHttps("http://127.0.0.1:4317", allowInsecure = false))
        assertTrue(TransportSecurity.enforceHttps("http://10.0.2.2:4317", allowInsecure = false))
        assertTrue(TransportSecurity.enforceHttps("http://[::1]:4317", allowInsecure = false))
        assertTrue(TransportSecurity.enforceHttps("http://my-collector.local:4317", allowInsecure = false))
    }

    @Test
    fun `isLocalHost matches iOS carve-out plus android emulator alias`() {
        assertTrue(TransportSecurity.isLocalHost("localhost"))
        assertTrue(TransportSecurity.isLocalHost("LOCALHOST"))
        assertTrue(TransportSecurity.isLocalHost("127.0.0.1"))
        assertTrue(TransportSecurity.isLocalHost("::1"))
        assertTrue(TransportSecurity.isLocalHost("0:0:0:0:0:0:0:1"))
        assertTrue(TransportSecurity.isLocalHost("10.0.2.2"))
        assertTrue(TransportSecurity.isLocalHost("printer.local"))
        assertFalse(TransportSecurity.isLocalHost("collector.example.com"))
        assertFalse(TransportSecurity.isLocalHost("2001:db8::1"))
        assertFalse(TransportSecurity.isLocalHost(null))
        assertFalse(TransportSecurity.isLocalHost(""))
    }

    @Test
    fun `hostOf strips scheme port path and ipv6 brackets`() {
        assertTrue(TransportSecurity.hostOf("https://collector.example.com:4317/v1/logs") == "collector.example.com")
        assertTrue(TransportSecurity.hostOf("http://[::1]:4317") == "::1")
        assertTrue(TransportSecurity.hostOf("https://user:pass@host.example/path") == "host.example")
    }

    // ── HMAC-SHA256 config-signature verification ─────────────────────────────

    private fun hmacHex(payload: ByteArray, key: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(payload).joinToString("") { "%02x".format(it) }
    }

    private fun hmacBase64(payload: ByteArray, key: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return android.util.Base64.encodeToString(mac.doFinal(payload), android.util.Base64.NO_WRAP)
    }

    @Test
    fun `valid hex HMAC verifies`() {
        val key = "super-secret-shared-key".toByteArray()
        val payload = """{"version":2,"sdk":{"enabled":true}}""".toByteArray()
        val sig = hmacHex(payload, key)
        assertTrue(TransportSecurity.verifyHmacSha256(payload, key, sig))
        // Case-insensitive hex accepted.
        assertTrue(TransportSecurity.verifyHmacSha256(payload, key, sig.uppercase()))
    }

    @Test
    fun `valid base64 HMAC verifies`() {
        val key = "super-secret-shared-key".toByteArray()
        val payload = """{"version":2}""".toByteArray()
        val sig = hmacBase64(payload, key)
        assertTrue(TransportSecurity.verifyHmacSha256(payload, key, sig))
    }

    @Test
    fun `wrong signature fails verification`() {
        val key = "k".toByteArray()
        val payload = "body".toByteArray()
        val good = hmacHex(payload, key)
        // Flip one hex nibble.
        val tampered = (if (good[0] == 'a') "b" else "a") + good.substring(1)
        assertFalse(TransportSecurity.verifyHmacSha256(payload, key, tampered))
    }

    @Test
    fun `signature computed with different key fails`() {
        val payload = "body".toByteArray()
        val sig = hmacHex(payload, "key-A".toByteArray())
        assertFalse(TransportSecurity.verifyHmacSha256(payload, "key-B".toByteArray(), sig))
    }

    @Test
    fun `tampered payload fails verification`() {
        val key = "k".toByteArray()
        val sig = hmacHex("original".toByteArray(), key)
        assertFalse(TransportSecurity.verifyHmacSha256("tampered".toByteArray(), key, sig))
    }

    @Test
    fun `empty key or empty signature never verifies`() {
        val payload = "body".toByteArray()
        assertFalse(TransportSecurity.verifyHmacSha256(payload, ByteArray(0), "abcd"))
        assertFalse(TransportSecurity.verifyHmacSha256(payload, "k".toByteArray(), ""))
        assertFalse(TransportSecurity.verifyHmacSha256(payload, "k".toByteArray(), "   "))
    }

    @Test
    fun `malformed signature does not throw and returns false`() {
        val payload = "body".toByteArray()
        // Odd-length / non-hex strings are rejected gracefully.
        assertFalse(TransportSecurity.verifyHmacSha256(payload, "k".toByteArray(), "xyz"))
        assertFalse(TransportSecurity.verifyHmacSha256(payload, "k".toByteArray(), "abc"))
    }

    @Test
    fun `constantTimeEquals is correct`() {
        assertTrue(TransportSecurity.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(TransportSecurity.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(TransportSecurity.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
        assertTrue(TransportSecurity.constantTimeEquals(ByteArray(0), ByteArray(0)))
    }

    // ── Pinning: PinningConfig + helpers ──────────────────────────────────────

    @Test
    fun `empty PinningConfig is empty and produces no pinner or sslcontext`() {
        val empty = TransportSecurity.PinningConfig()
        assertTrue(empty.isEmpty)
        assertNull(TransportSecurity.certificatePinner("collector.example.com", empty))
        assertNull(TransportSecurity.certificatePinner("collector.example.com", null))
        assertNull(TransportSecurity.pinningSslContext(empty))
        assertNull(TransportSecurity.pinningSslContext(null))
    }

    @Test
    fun `spkiSha256Base64 round-trips a real certificate`() {
        val held = HeldCertificate.Builder().commonName("test").build()
        val der = held.certificate.encoded
        val pin = TransportSecurity.spkiSha256Base64(der)
        assertNotNull(pin)
        // Matches OkHttp's own computed pin for the same cert.
        val okhttpPin = okhttp3.CertificatePinner.pin(held.certificate).removePrefix("sha256/")
        assertTrue(pin == okhttpPin, "computed SPKI pin must equal OkHttp's pin: $pin vs $okhttpPin")
    }

    @Test
    fun `certificatePinner builds from spki pin`() {
        val held = HeldCertificate.Builder().commonName("test").build()
        val pin = TransportSecurity.spkiSha256Base64(held.certificate.encoded)!!
        val pinner = TransportSecurity.certificatePinner(
            "collector.example.com",
            TransportSecurity.PinningConfig(spkiSha256Pins = setOf(pin)),
        )
        assertNotNull(pinner)
    }

    @Test
    fun `certificatePinner builds from DER cert pin`() {
        val held = HeldCertificate.Builder().commonName("test").build()
        val pinner = TransportSecurity.certificatePinner(
            "collector.example.com",
            TransportSecurity.PinningConfig(certificates = listOf(held.certificate.encoded)),
        )
        assertNotNull(pinner)
    }

    @Test
    fun `pinningSslContext produced for non-empty config`() {
        val held = HeldCertificate.Builder().commonName("test").build()
        val pin = TransportSecurity.spkiSha256Base64(held.certificate.encoded)!!
        val ctx = TransportSecurity.pinningSslContext(
            TransportSecurity.PinningConfig(spkiSha256Pins = setOf(pin)),
        )
        assertNotNull(ctx)
    }

    // ── PinningTrustManager fail-closed behaviour ─────────────────────────────

    /** A delegate trust manager that always passes, isolating the PIN check. */
    private val alwaysTrust = object : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    }

    @Test
    fun `matching spki pin passes the trust manager`() {
        val held = HeldCertificate.Builder().commonName("test").build()
        val pin = TransportSecurity.spkiSha256Base64(held.certificate.encoded)!!
        val tm = PinningTrustManager(alwaysTrust, TransportSecurity.PinningConfig(spkiSha256Pins = setOf(pin)))
        // Should NOT throw.
        tm.checkServerTrusted(arrayOf(held.certificate), "RSA")
    }

    @Test
    fun `matching DER cert pin passes the trust manager`() {
        val held = HeldCertificate.Builder().commonName("test").build()
        val tm = PinningTrustManager(
            alwaysTrust,
            TransportSecurity.PinningConfig(certificates = listOf(held.certificate.encoded)),
        )
        tm.checkServerTrusted(arrayOf(held.certificate), "RSA")
    }

    @Test
    fun `mismatched pin fails the connection fail-closed`() {
        val served = HeldCertificate.Builder().commonName("served").build()
        val other = HeldCertificate.Builder().commonName("other").build()
        val otherPin = TransportSecurity.spkiSha256Base64(other.certificate.encoded)!!
        val tm = PinningTrustManager(alwaysTrust, TransportSecurity.PinningConfig(spkiSha256Pins = setOf(otherPin)))
        var threw = false
        try {
            tm.checkServerTrusted(arrayOf(served.certificate), "RSA")
        } catch (e: CertificateException) {
            threw = true
        }
        assertTrue(threw, "pin mismatch must fail the connection (CertificateException), not pass")
    }

    @Test
    fun `empty chain fails the trust manager`() {
        val held = HeldCertificate.Builder().commonName("x").build()
        val pin = TransportSecurity.spkiSha256Base64(held.certificate.encoded)!!
        val tm = PinningTrustManager(alwaysTrust, TransportSecurity.PinningConfig(spkiSha256Pins = setOf(pin)))
        var threw = false
        try {
            tm.checkServerTrusted(arrayOf(), "RSA")
        } catch (e: CertificateException) {
            threw = true
        }
        assertTrue(threw)
    }
}
