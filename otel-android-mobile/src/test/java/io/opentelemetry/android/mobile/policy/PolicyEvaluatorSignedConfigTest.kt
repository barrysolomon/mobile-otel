/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.policy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.config.TransportSecurity
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Signed-config integrity tests for [PolicyEvaluator] (iOS parity with
 * `ConfigPoller`'s `X-Dash0-Config-Signature` verification):
 *
 * - valid HMAC ⇒ config applies
 * - invalid HMAC with key set ⇒ KEEP last-applied (do not apply unverified)
 * - missing signature with key set ⇒ KEEP last-applied
 * - no key set ⇒ backward-compatible (apply any parseable config)
 *
 * Drives the REAL [PolicyEvaluator.fetchConfig] path over a loopback MockWebServer
 * (127.0.0.1 is exempt from HTTPS enforcement). Fetch is async, so we poll the
 * internal `policyConfig` with a bounded timeout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PolicyEvaluatorSignedConfigTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    private val signedConfigJson = """{"version":2,"workflows":[{"id":"signed-wf","enabled":true,"states":[{"id":"s","matchers":[{"type":"crash"}],"on_match":{"actions":[{"type":"flush_buffer","config":{"minutes":3}}]}}]}]}"""

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        server.close()
    }

    private fun signHex(body: String, key: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun makeConfig(signingKey: ByteArray?): MobileConfig {
        val base = server.url("/").toString().removeSuffix("/")
        return MobileConfig(
            serviceName = "test-app",
            serviceVersion = "1.0.0",
            collectorEndpoint = base,
            configSigningKey = signingKey,
            configPollIntervalSeconds = 3600, // single fetch in the test window
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun policyRef(evaluator: PolicyEvaluator): AtomicReference<PolicyConfig?> {
        val field = PolicyEvaluator::class.java.getDeclaredField("policyConfig")
        field.isAccessible = true
        return field.get(evaluator) as AtomicReference<PolicyConfig?>
    }

    /** Poll until [predicate] holds or the timeout elapses. */
    private fun awaitUntil(timeoutMs: Long = 5000, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(25)
        }
        return predicate()
    }

    @Test
    fun `valid HMAC applies the fetched config`() {
        val key = "shared-secret".toByteArray()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader(TransportSecurity.SIGNATURE_HEADER_NAME, signHex(signedConfigJson, key))
                .body(signedConfigJson)
                .build(),
        )
        val evaluator = PolicyEvaluator(context, makeConfig(key))
        val ref = policyRef(evaluator)

        val applied = awaitUntil { ref.get()?.policies?.any { it.id == "signed-wf" } == true }
        assertTrue(applied, "valid HMAC must apply the signed config")
        evaluator.shutdown()
    }

    @Test
    fun `invalid HMAC keeps last-applied and does not apply`() {
        val key = "shared-secret".toByteArray()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader(TransportSecurity.SIGNATURE_HEADER_NAME, "deadbeef") // wrong signature
                .body(signedConfigJson)
                .build(),
        )
        val evaluator = PolicyEvaluator(context, makeConfig(key))
        val ref = policyRef(evaluator)
        // Seed a known "last-applied" config so we can prove it is preserved.
        val lastApplied = PolicyConfig(listOf(Policy("last-good", true, Match("and", mapOf("event.name" to Condition(equals = "x"))), Actions(2))))
        ref.set(lastApplied)

        // Wait for the request to be consumed, then assert the bad config was NOT applied.
        assertNotNull(server.takeRequest())
        // Give the coroutine a moment; the verification-failure branch must not mutate policyConfig.
        Thread.sleep(300)
        val current = ref.get()
        assertNotNull(current)
        assertEquals("last-good", current.policies.single().id, "invalid HMAC must keep last-applied config")
        assertTrue(current.policies.none { it.id == "signed-wf" }, "unverified config must NOT be applied")
        evaluator.shutdown()
    }

    @Test
    fun `missing signature with key set keeps last-applied`() {
        val key = "shared-secret".toByteArray()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(signedConfigJson) // no signature header
                .build(),
        )
        val evaluator = PolicyEvaluator(context, makeConfig(key))
        val ref = policyRef(evaluator)
        ref.set(null) // last-applied is "none" → must remain null (falls back to defaults)

        assertNotNull(server.takeRequest())
        Thread.sleep(300)
        assertNull(ref.get(), "missing signature with key set must NOT apply config (keep last-applied = null)")
        evaluator.shutdown()
    }

    @Test
    fun `no signing key applies config (backward compatible)`() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(signedConfigJson) // no signature, no key → apply as before
                .build(),
        )
        val evaluator = PolicyEvaluator(context, makeConfig(signingKey = null))
        val ref = policyRef(evaluator)

        val applied = awaitUntil { ref.get()?.policies?.any { it.id == "signed-wf" } == true }
        assertTrue(applied, "with no signing key, config applies as before (backward compatible)")
        evaluator.shutdown()
    }

    @Test
    fun `valid base64 signature also applies`() {
        val key = "shared-secret".toByteArray()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val b64 = android.util.Base64.encodeToString(mac.doFinal(signedConfigJson.toByteArray()), android.util.Base64.NO_WRAP)
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader(TransportSecurity.SIGNATURE_HEADER_NAME, b64)
                .body(signedConfigJson)
                .build(),
        )
        val evaluator = PolicyEvaluator(context, makeConfig(key))
        val ref = policyRef(evaluator)

        val applied = awaitUntil { ref.get()?.policies?.any { it.id == "signed-wf" } == true }
        assertTrue(applied, "base64-encoded signature must also verify and apply")
        evaluator.shutdown()
    }
}
