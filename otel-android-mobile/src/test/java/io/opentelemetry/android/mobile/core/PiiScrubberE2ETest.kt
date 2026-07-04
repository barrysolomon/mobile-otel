/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.core

import android.net.Uri
import io.opentelemetry.android.mobile.autocapture.AutoCaptureOptions
import io.opentelemetry.android.mobile.autocapture.PrivacyMode
import io.opentelemetry.android.mobile.autocapture.PrivacyUtils
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * PR-019: PII scrubbing end-to-end validation.
 *
 * Verifies the full privacy pipeline:
 * - PrivacyMode.STRICT hashes UI text (tap targets, content descriptions)
 * - PrivacyMode.RELAXED passes raw text through
 * - PiiScrubber strips PII from URLs, exceptions, and attributes
 * - Deep links are scrubbed before export
 * - Hash salt changes output (so different customers can't correlate)
 * - Multiple PII types in one payload are all caught
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PiiScrubberE2ETest {

    // ── PrivacyMode integration ──────────────────────────────────────────────

    @Test
    fun `STRICT mode hashes UI text instead of passing raw`() {
        val options = AutoCaptureOptions(privacyMode = PrivacyMode.STRICT)
        val rawText = "John Doe"

        val result = PrivacyUtils.maybeHash(rawText, options)

        assertNotEquals(rawText, result, "STRICT mode must not pass raw text through")
        assertTrue(result!!.matches(Regex("[0-9a-f]{64}")), "Should be SHA-256 hex (64 chars), got: $result")
    }

    @Test
    fun `RELAXED mode passes raw text through`() {
        val options = AutoCaptureOptions(privacyMode = PrivacyMode.RELAXED)
        val rawText = "John Doe"

        val result = PrivacyUtils.maybeHash(rawText, options)

        assertEquals(rawText, result, "RELAXED mode should return raw text")
    }

    @Test
    fun `hash salt produces different output for same input`() {
        val unsalted = AutoCaptureOptions(privacyMode = PrivacyMode.STRICT, hashSalt = null)
        val salted = AutoCaptureOptions(privacyMode = PrivacyMode.STRICT, hashSalt = "customer-secret-123")

        val result1 = PrivacyUtils.maybeHash("Submit Order", unsalted)
        val result2 = PrivacyUtils.maybeHash("Submit Order", salted)

        assertNotEquals(result1, result2, "Different salts must produce different hashes")
    }

    @Test
    fun `same salt produces deterministic output`() {
        val options = AutoCaptureOptions(privacyMode = PrivacyMode.STRICT, hashSalt = "fixed-salt")

        val result1 = PrivacyUtils.maybeHash("Login Button", options)
        val result2 = PrivacyUtils.maybeHash("Login Button", options)

        assertEquals(result1, result2, "Same input + same salt must produce same hash")
    }

    @Test
    fun `null and empty text returns null`() {
        val options = AutoCaptureOptions(privacyMode = PrivacyMode.STRICT)

        assertEquals(null, PrivacyUtils.maybeHash(null, options))
        assertEquals(null, PrivacyUtils.maybeHash("", options))
        assertEquals(null, PrivacyUtils.maybeHash("   ", options))
    }

    // ── URL scrubbing through export path ────────────────────────────────────

    @Test
    fun `network URL with auth token is scrubbed before export`() {
        val rawUrl = "https://api.example.com/v1/users/550e8400-e29b-41d4-a716-446655440000/orders?auth_token=sk-live-abc123&page=2"

        val scrubbed = PiiScrubber.scrubUrl(rawUrl)

        assertFalse(scrubbed.contains("sk-live-abc123"), "Auth token must be stripped")
        assertFalse(scrubbed.contains("550e8400"), "UUID must be replaced")
        assertTrue(scrubbed.contains("{uuid}"), "UUID should become {uuid}")
        assertFalse(scrubbed.contains("?"), "Query string must be stripped by default")
        assertTrue(scrubbed.startsWith("https://api.example.com"), "Host must be preserved")
    }

    @Test
    fun `deep link with PII is scrubbed for navigation spans`() {
        val uri = Uri.parse("myapp://checkout?card=4111111111111111&email=user@example.com&amount=99.99")

        val scrubbed = PiiScrubber.scrubDeepLink(uri)

        assertFalse(scrubbed.contains("4111111111111111"), "Credit card must be stripped")
        assertFalse(scrubbed.contains("user@example.com"), "Email must be stripped")
        assertTrue(scrubbed.startsWith("myapp://checkout"), "Scheme and path must be preserved")
    }

    // ── Exception message scrubbing ──────────────────────────────────────────

    @Test
    fun `crash exception with mixed PII is fully scrubbed`() {
        val message = "Failed to process payment for user@company.com " +
            "(card: 4111 1111 1111 1111, SSN: 123-45-6789) " +
            "at /data/user/0/com.example.app/databases/orders.db"

        val scrubbed = PiiScrubber.scrubExceptionMessage(message)

        assertFalse(scrubbed.contains("user@company.com"), "Email must be scrubbed")
        assertFalse(scrubbed.contains("4111"), "Credit card must be scrubbed")
        assertFalse(scrubbed.contains("123-45-6789"), "SSN must be scrubbed")
        assertFalse(scrubbed.contains("/data/user/0/"), "User data path must be scrubbed")
        assertTrue(scrubbed.contains("[EMAIL]"))
        assertTrue(scrubbed.contains("[CREDIT_CARD]"))
        assertTrue(scrubbed.contains("[SSN]"))
        assertTrue(scrubbed.contains("/data/user/{uid}/"))
    }

    // ── Attribute map scrubbing ──────────────────────────────────────────────

    @Test
    fun `exported attributes have PII scrubbed from string values`() {
        val attrs = mapOf<String, Any>(
            "http.url" to "https://api.example.com/users/12345?token=secret",
            "user.email" to "john.doe@example.com",
            "http.status_code" to 200L,
            "error.message" to "Auth failed for SSN 987-65-4321",
            "response.time_ms" to 42.5
        )

        val scrubbed = PiiScrubber.scrubAttributes(attrs)

        assertFalse((scrubbed["user.email"] as String).contains("john.doe@example.com"),
            "Email in attribute value must be scrubbed")
        assertTrue((scrubbed["user.email"] as String).contains("[EMAIL]"))
        assertFalse((scrubbed["error.message"] as String).contains("987-65-4321"),
            "SSN in error message must be scrubbed")
        assertEquals(200L, scrubbed["http.status_code"], "Non-string values must pass through")
        assertEquals(42.5, scrubbed["response.time_ms"], "Numeric values must pass through")
    }

    @Test
    fun `stack trace depth is bounded to prevent PII leakage in deep traces`() {
        val deepTrace = (1..200).map {
            StackTraceElement(
                "com.customer.secret.Class$it",
                "method",
                "/data/user/0/com.customer.app/Class$it.kt",
                it
            )
        }.toTypedArray()

        val scrubbed = PiiScrubber.scrubStackTrace(deepTrace, maxDepth = 10)
        val lines = scrubbed.lines().filter { it.isNotBlank() }

        assertEquals(10, lines.size, "Stack trace must be bounded to maxDepth")
        assertFalse(scrubbed.contains("/data/user/0/"), "User data paths must be scrubbed in stack frames")
    }

    // ── Edge cases ───────────────────────────────────────────────────────────

    @Test
    fun `containsPii detects all PII types for pre-export gating`() {
        assertTrue(PiiScrubber.containsPii("Contact admin@example.com for help"))
        assertTrue(PiiScrubber.containsPii("Card number: 4111 1111 1111 1111"))
        assertTrue(PiiScrubber.containsPii("SSN: 123-45-6789"))
        assertFalse(PiiScrubber.containsPii("Error code: ERR_TIMEOUT after 30s"))
    }

    @Test
    fun `attribute key validation rejects PII-like keys`() {
        assertFalse(PiiScrubber.isValidAttributeKey("user@host"), "Email-like key must be rejected")
        assertFalse(PiiScrubber.isValidAttributeKey("123-45-6789"), "SSN-like key must be rejected")
        assertTrue(PiiScrubber.isValidAttributeKey("http.status_code"), "Valid OTel key must pass")
        assertTrue(PiiScrubber.isValidAttributeKey("event.name"), "Dotted key must pass")
    }
}
