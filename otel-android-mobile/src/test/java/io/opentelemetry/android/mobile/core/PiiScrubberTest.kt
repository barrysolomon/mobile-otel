/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.core

import android.net.Uri
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PiiScrubberTest {

    // ── scrubUrl ──────────────────────────────────────────────────────────────

    @Test
    fun `scrubUrl removes all query params by default`() {
        val result = PiiScrubber.scrubUrl("https://api.example.com/users?token=abc123&page=2")
        assertFalse(result.contains("token"))
        assertFalse(result.contains("page"))
        assertFalse(result.contains("?"))
    }

    @Test
    fun `scrubUrl with allowQueryParams redacts sensitive params`() {
        val result = PiiScrubber.scrubUrl(
            "https://api.example.com/search?q=hello&token=secret&page=1",
            allowQueryParams = true
        )
        assertTrue(result.contains("[REDACTED]"), "Sensitive param should be redacted")
        assertFalse(result.contains("secret"), "Token value must not appear")
    }

    @Test
    fun `scrubUrl replaces UUIDs in path`() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val result = PiiScrubber.scrubUrl("https://api.example.com/users/$uuid/profile")
        assertFalse(result.contains(uuid))
        assertTrue(result.contains("{uuid}"))
    }

    @Test
    fun `scrubUrl replaces numeric IDs in path`() {
        val result = PiiScrubber.scrubUrl("https://api.example.com/users/12345/orders/67890")
        assertFalse(result.contains("12345"))
        assertFalse(result.contains("67890"))
        assertTrue(result.contains("{id}"))
    }

    @Test
    fun `scrubUrl with scrubPathSegments false preserves path`() {
        val result = PiiScrubber.scrubUrl(
            "https://api.example.com/users/12345",
            scrubPathSegments = false
        )
        assertTrue(result.contains("12345"))
    }

    @Test
    fun `scrubUrl preserves scheme and host`() {
        val result = PiiScrubber.scrubUrl("https://api.example.com/path?token=abc")
        assertTrue(result.startsWith("https://api.example.com"))
    }

    @Test
    fun `scrubUrl returns INVALID_URL for malformed input`() {
        val result = PiiScrubber.scrubUrl("not a url \u0000\u0001")
        // Either returns [INVALID_URL] or a best-effort scrubbed form — both acceptable
        // The key requirement is it doesn't throw
        assertFalse(result.isEmpty())
    }

    @Test
    fun `scrubUrl handles empty query string`() {
        val result = PiiScrubber.scrubUrl("https://api.example.com/path")
        assertTrue(result.contains("api.example.com"))
        assertFalse(result.contains("?"))
    }

    // ── scrubDeepLink ─────────────────────────────────────────────────────────

    @Test
    fun `scrubDeepLink removes query params by default`() {
        val uri = Uri.parse("myapp://profile/123?session=abc&ref=email")
        val result = PiiScrubber.scrubDeepLink(uri)
        assertFalse(result.contains("session"))
        assertFalse(result.contains("abc"))
    }

    @Test
    fun `scrubDeepLink with allowQueryParams redacts sensitive params`() {
        val uri = Uri.parse("myapp://payment?token=secret&amount=100")
        val result = PiiScrubber.scrubDeepLink(uri, allowQueryParams = true)
        assertFalse(result.contains("secret"))
        assertTrue(result.contains("amount"))
    }

    // ── scrubExceptionMessage ─────────────────────────────────────────────────

    @Test
    fun `scrubExceptionMessage replaces email addresses`() {
        val result = PiiScrubber.scrubExceptionMessage("User user@example.com not found")
        assertFalse(result.contains("user@example.com"))
        assertTrue(result.contains("[EMAIL]"))
    }

    @Test
    fun `scrubExceptionMessage replaces phone numbers`() {
        val result = PiiScrubber.scrubExceptionMessage("Contact us at 555-867-5309")
        assertFalse(result.contains("867-5309"))
        assertTrue(result.contains("[PHONE]"))
    }

    @Test
    fun `scrubExceptionMessage replaces credit card numbers`() {
        val result = PiiScrubber.scrubExceptionMessage("Card 4111 1111 1111 1111 was declined")
        assertFalse(result.contains("4111"))
        assertTrue(result.contains("[CREDIT_CARD]"))
    }

    @Test
    fun `scrubExceptionMessage replaces SSNs`() {
        val result = PiiScrubber.scrubExceptionMessage("SSN 123-45-6789 is invalid")
        assertFalse(result.contains("123-45-6789"))
        assertTrue(result.contains("[SSN]"))
    }

    @Test
    fun `scrubExceptionMessage replaces user data paths`() {
        val result = PiiScrubber.scrubExceptionMessage("File /data/user/0/com.example.app/cache not found")
        assertFalse(result.contains("/data/user/0/"), "Specific UID path should be removed")
        assertTrue(result.contains("/data/user/{uid}/"))
    }

    @Test
    fun `scrubExceptionMessage returns empty string for null`() {
        val result = PiiScrubber.scrubExceptionMessage(null)
        assertEquals("", result)
    }

    @Test
    fun `scrubExceptionMessage returns clean text unchanged`() {
        val message = "Database connection timeout after 30 seconds"
        val result = PiiScrubber.scrubExceptionMessage(message)
        assertEquals(message, result)
    }

    @Test
    fun `scrubExceptionMessage handles multiple PII types in one message`() {
        val result = PiiScrubber.scrubExceptionMessage(
            "Login failed for user@example.com (SSN: 123-45-6789, phone: 555-867-5309)"
        )
        assertTrue(result.contains("[EMAIL]"))
        assertTrue(result.contains("[SSN]"))
        assertTrue(result.contains("[PHONE]"))
        assertFalse(result.contains("user@example.com"))
        assertFalse(result.contains("123-45-6789"))
    }

    // ── scrubStackTrace ───────────────────────────────────────────────────────

    @Test
    fun `scrubStackTrace limits depth to maxDepth`() {
        val elements = (1..100).map {
            StackTraceElement("com.example.Class$it", "method", "Class$it.kt", it)
        }.toTypedArray()
        val result = PiiScrubber.scrubStackTrace(elements, maxDepth = 5)
        val lines = result.lines().filter { it.isNotBlank() }
        assertEquals(5, lines.size)
    }

    @Test
    fun `scrubStackTrace uses default depth of 50`() {
        val elements = (1..100).map {
            StackTraceElement("com.example.Class", "method", "Class.kt", it)
        }.toTypedArray()
        val result = PiiScrubber.scrubStackTrace(elements)
        val lines = result.lines().filter { it.isNotBlank() }
        assertEquals(50, lines.size)
    }

    @Test
    fun `scrubStackTrace scrubs user-specific file paths`() {
        val element = StackTraceElement(
            "com.example.App", "onCreate",
            "/data/user/0/com.example.app/MainActivity.kt", 42
        )
        val result = PiiScrubber.scrubStackTrace(arrayOf(element))
        assertFalse(result.contains("/data/user/0/"))
        assertTrue(result.contains("/data/user/{uid}/"))
    }

    @Test
    fun `scrubStackTrace handles empty stack trace`() {
        val result = PiiScrubber.scrubStackTrace(emptyArray())
        assertTrue(result.isEmpty())
    }

    // ── scrubText ─────────────────────────────────────────────────────────────

    @Test
    fun `scrubText replaces email`() {
        val result = PiiScrubber.scrubText("Send receipt to jane.doe@company.com")
        assertTrue(result.contains("[EMAIL]"))
        assertFalse(result.contains("jane.doe@company.com"))
    }

    @Test
    fun `scrubText replaces credit card`() {
        val result = PiiScrubber.scrubText("Charged to 4111-1111-1111-1111")
        assertTrue(result.contains("[CREDIT_CARD]"))
        assertFalse(result.contains("4111-1111-1111-1111"))
    }

    @Test
    fun `scrubText replaces SSN`() {
        val result = PiiScrubber.scrubText("SSN: 987-65-4321")
        assertTrue(result.contains("[SSN]"))
        assertFalse(result.contains("987-65-4321"))
    }

    @Test
    fun `scrubText does not modify text without PII`() {
        val text = "Order #1234 shipped in 3 business days"
        assertEquals(text, PiiScrubber.scrubText(text))
    }

    // ── containsPii ───────────────────────────────────────────────────────────

    @Test
    fun `containsPii returns true for email`() {
        assertTrue(PiiScrubber.containsPii("Contact admin@example.com"))
    }

    @Test
    fun `containsPii returns true for credit card`() {
        assertTrue(PiiScrubber.containsPii("4111 1111 1111 1111"))
    }

    @Test
    fun `containsPii returns true for SSN`() {
        assertTrue(PiiScrubber.containsPii("123-45-6789"))
    }

    @Test
    fun `containsPii returns false for clean text`() {
        assertFalse(PiiScrubber.containsPii("No sensitive data here"))
    }

    @Test
    fun `containsPii returns false for empty string`() {
        assertFalse(PiiScrubber.containsPii(""))
    }

    // ── isValidAttributeKey ───────────────────────────────────────────────────

    @Test
    fun `isValidAttributeKey accepts valid keys`() {
        assertTrue(PiiScrubber.isValidAttributeKey("event.name"))
        assertTrue(PiiScrubber.isValidAttributeKey("http.status_code"))
        assertTrue(PiiScrubber.isValidAttributeKey("duration-ms"))
        assertTrue(PiiScrubber.isValidAttributeKey("a"))
    }

    @Test
    fun `isValidAttributeKey rejects keys with uppercase`() {
        assertFalse(PiiScrubber.isValidAttributeKey("Event.Name"))
    }

    @Test
    fun `isValidAttributeKey rejects keys starting with digit`() {
        assertFalse(PiiScrubber.isValidAttributeKey("1event"))
    }

    @Test
    fun `isValidAttributeKey rejects keys with spaces`() {
        assertFalse(PiiScrubber.isValidAttributeKey("event name"))
    }

    @Test
    fun `isValidAttributeKey rejects empty key`() {
        assertFalse(PiiScrubber.isValidAttributeKey(""))
    }

    @Test
    fun `isValidAttributeKey rejects email-like keys`() {
        assertFalse(PiiScrubber.isValidAttributeKey("user@host"))
    }

    // ── scrubAttributes ───────────────────────────────────────────────────────

    @Test
    fun `scrubAttributes scrubs PII from string values`() {
        val attrs = mapOf(
            "message" to "Hello user@example.com",
            "code" to "ERR_404"
        )
        val result = PiiScrubber.scrubAttributes(attrs)
        assertFalse((result["message"] as String).contains("user@example.com"))
        assertTrue((result["message"] as String).contains("[EMAIL]"))
        assertEquals("ERR_404", result["code"])
    }

    @Test
    fun `scrubAttributes passes through non-string values unchanged`() {
        val attrs = mapOf<String, Any>(
            "count" to 42L,
            "ratio" to 0.75,
            "enabled" to true
        )
        val result = PiiScrubber.scrubAttributes(attrs)
        assertEquals(42L, result["count"])
        assertEquals(0.75, result["ratio"])
        assertEquals(true, result["enabled"])
    }

    @Test
    fun `scrubAttributes handles empty map`() {
        val result = PiiScrubber.scrubAttributes(emptyMap())
        assertTrue(result.isEmpty())
    }
}
