/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.config

import okhttp3.tls.HeldCertificate
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the new transport-security fields on [MobileConfig] (iOS parity):
 * `allowInsecureTransport`, `pinningConfig`, `configSigningKey` — including the
 * builder wiring and that they default OFF / backward-compatible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransportSecurityConfigTest {

    private fun base(
        endpoint: String = "https://collector.example.com:4317",
        allowInsecure: Boolean = false,
        pinning: TransportSecurity.PinningConfig? = null,
        signingKey: ByteArray? = null,
    ) = MobileConfig(
        serviceName = "app",
        serviceVersion = "1.0.0",
        collectorEndpoint = endpoint,
        allowInsecureTransport = allowInsecure,
        pinningConfig = pinning,
        configSigningKey = signingKey,
    )

    @Test
    fun `new transport fields default off and backward-compatible`() {
        val c = MobileConfig(
            serviceName = "app",
            serviceVersion = "1.0.0",
            collectorEndpoint = "https://collector.example.com:4317",
        )
        assertFalse(c.allowInsecureTransport)
        assertNull(c.pinningConfig)
        assertNull(c.configSigningKey)
    }

    @Test
    fun `allowInsecureTransport can be set directly`() {
        assertTrue(base(allowInsecure = true).allowInsecureTransport)
    }

    @Test
    fun `cleartext non-loopback config does not throw (graceful, enforced at network path)`() {
        // Construction never throws regardless of allowInsecureTransport — the
        // actual rejection happens at the OTLP/poller network paths.
        base(endpoint = "http://collector.example.com:4317", allowInsecure = false)
        base(endpoint = "http://collector.example.com:4317", allowInsecure = true)
    }

    @Test
    fun `pinningConfig is carried on the config`() {
        val held = HeldCertificate.Builder().commonName("x").build()
        val pin = TransportSecurity.spkiSha256Base64(held.certificate.encoded)!!
        val c = base(pinning = TransportSecurity.PinningConfig(spkiSha256Pins = setOf(pin)))
        assertNotNull(c.pinningConfig)
        assertEquals(setOf(pin), c.pinningConfig!!.spkiSha256Pins)
    }

    @Test
    fun `configSigningKey is carried on the config`() {
        val key = "secret".toByteArray()
        assertNotNull(base(signingKey = key).configSigningKey)
    }

    // ── Builder wiring ────────────────────────────────────────────────────────

    @Test
    fun `builder wires all three transport-security fields`() {
        val held = HeldCertificate.Builder().commonName("x").build()
        val pin = TransportSecurity.spkiSha256Base64(held.certificate.encoded)!!
        val pinning = TransportSecurity.PinningConfig(spkiSha256Pins = setOf(pin))
        val key = "k".toByteArray()

        val c = MobileConfig.builder()
            .setServiceName("app")
            .setServiceVersion("1.0.0")
            .setCollectorEndpoint("https://collector.example.com:4317")
            .setAllowInsecureTransport(true)
            .setPinningConfig(pinning)
            .setConfigSigningKey(key)
            .build()

        assertTrue(c.allowInsecureTransport)
        assertEquals(pinning, c.pinningConfig)
        assertNotNull(c.configSigningKey)
    }

    @Test
    fun `builder defaults are off`() {
        val c = MobileConfig.builder()
            .setServiceName("app")
            .setServiceVersion("1.0.0")
            .setCollectorEndpoint("https://collector.example.com:4317")
            .build()
        assertFalse(c.allowInsecureTransport)
        assertNull(c.pinningConfig)
        assertNull(c.configSigningKey)
    }
}
