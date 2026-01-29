package io.opentelemetry.android.mobile.core

import android.net.Uri

/**
 * Utility for scrubbing Personally Identifiable Information (PII) from telemetry data.
 *
 * This class provides methods to remove or redact sensitive information from:
 * - URLs and deep links
 * - Exception messages
 * - Stack traces
 * - Custom text
 *
 * All scrubbing is privacy-first by default (aggressive redaction).
 */
object PiiScrubber {

    // Common PII patterns
    private val EMAIL_PATTERN = Regex(
        "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}",
        RegexOption.IGNORE_CASE
    )

    private val PHONE_PATTERN = Regex(
        "\\+?[1-9]\\d{1,14}|\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}"
    )

    private val CREDIT_CARD_PATTERN = Regex(
        "\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b"
    )

    private val SSN_PATTERN = Regex(
        "\\b\\d{3}-\\d{2}-\\d{4}\\b"
    )

    // Common sensitive query parameters
    private val SENSITIVE_QUERY_PARAMS = setOf(
        "token", "api_key", "apikey", "api-key",
        "session", "session_id", "sessionid", "session-id",
        "access_token", "accesstoken", "access-token",
        "auth", "authorization",
        "password", "passwd", "pwd",
        "secret", "private_key", "privatekey",
        "credit_card", "creditcard", "cc",
        "ssn", "social_security"
    )

    // User-specific path patterns (Android)
    private val USER_PATH_PATTERN = Regex(
        "/data/user/\\d+/"
    )

    /**
     * Scrub a URL by removing sensitive query parameters and path segments.
     *
     * @param url The URL to scrub
     * @param allowQueryParams If true, only redact sensitive params instead of removing all
     * @param scrubPathSegments If true, replace UUIDs and IDs in path with placeholders
     * @return Scrubbed URL
     */
    fun scrubUrl(
        url: String,
        allowQueryParams: Boolean = false,
        scrubPathSegments: Boolean = true
    ): String {
        return try {
            val uri = Uri.parse(url)
            val builder = uri.buildUpon()

            // Handle query parameters
            if (!allowQueryParams) {
                builder.clearQuery()
            } else {
                // Redact sensitive parameters
                uri.queryParameterNames.forEach { param ->
                    if (param.lowercase() in SENSITIVE_QUERY_PARAMS) {
                        builder.clearQuery()
                        builder.appendQueryParameter(param, "[REDACTED]")
                    }
                }
            }

            // Scrub path segments
            if (scrubPathSegments) {
                val scrubbedPath = uri.path
                    ?.replace(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "{uuid}")
                    ?.replace(Regex("/\\d+(?=/|$)"), "/{id}")
                builder.path(scrubbedPath)
            }

            builder.build().toString()
        } catch (e: Exception) {
            "[INVALID_URL]"
        }
    }

    /**
     * Scrub a deep link URI by removing query parameters and sensitive path segments.
     *
     * @param uri The deep link URI
     * @param allowQueryParams If true, keep non-sensitive query params
     * @return Scrubbed URI string
     */
    fun scrubDeepLink(uri: Uri, allowQueryParams: Boolean = false): String {
        return scrubUrl(uri.toString(), allowQueryParams, scrubPathSegments = true)
    }

    /**
     * Scrub an exception message by removing common PII patterns.
     *
     * @param message The exception message
     * @return Scrubbed message
     */
    fun scrubExceptionMessage(message: String?): String {
        if (message == null) return ""

        var scrubbed = message

        // Remove emails
        scrubbed = EMAIL_PATTERN.replace(scrubbed, "[EMAIL]")

        // Remove phone numbers
        scrubbed = PHONE_PATTERN.replace(scrubbed, "[PHONE]")

        // Remove credit cards
        scrubbed = CREDIT_CARD_PATTERN.replace(scrubbed, "[CREDIT_CARD]")

        // Remove SSNs
        scrubbed = SSN_PATTERN.replace(scrubbed, "[SSN]")

        // Remove user-specific paths
        scrubbed = USER_PATH_PATTERN.replace(scrubbed, "/data/user/{uid}/")

        return scrubbed
    }

    /**
     * Scrub a stack trace by removing user-specific file paths.
     *
     * @param stackTrace Array of stack trace elements
     * @param maxDepth Maximum stack trace depth to include (default: 50)
     * @return Scrubbed stack trace as string
     */
    fun scrubStackTrace(
        stackTrace: Array<StackTraceElement>,
        maxDepth: Int = 50
    ): String {
        return stackTrace
            .take(maxDepth)
            .joinToString("\n") { element ->
                val fileName = element.fileName?.let { file ->
                    // Remove user-specific paths
                    file.replace(USER_PATH_PATTERN, "/data/user/{uid}/")
                } ?: "Unknown"

                "${element.className}.${element.methodName}($fileName:${element.lineNumber})"
            }
    }

    /**
     * Scrub arbitrary text by removing common PII patterns.
     *
     * @param text The text to scrub
     * @return Scrubbed text
     */
    fun scrubText(text: String): String {
        var scrubbed = text

        // Remove emails
        scrubbed = EMAIL_PATTERN.replace(scrubbed, "[EMAIL]")

        // Remove phone numbers
        scrubbed = PHONE_PATTERN.replace(scrubbed, "[PHONE]")

        // Remove credit cards
        scrubbed = CREDIT_CARD_PATTERN.replace(scrubbed, "[CREDIT_CARD]")

        // Remove SSNs
        scrubbed = SSN_PATTERN.replace(scrubbed, "[SSN]")

        return scrubbed
    }

    /**
     * Check if a string contains potential PII.
     *
     * @param text Text to check
     * @return True if potential PII detected
     */
    fun containsPii(text: String): Boolean {
        return EMAIL_PATTERN.containsMatchIn(text) ||
                PHONE_PATTERN.containsMatchIn(text) ||
                CREDIT_CARD_PATTERN.containsMatchIn(text) ||
                SSN_PATTERN.containsMatchIn(text)
    }

    /**
     * Validate that an attribute key is safe (no PII in the key itself).
     *
     * @param key Attribute key
     * @return True if key is valid
     */
    fun isValidAttributeKey(key: String): Boolean {
        // Must be alphanumeric + underscores/dots/dashes
        return key.matches(Regex("^[a-z][a-z0-9._-]*$"))
    }

    /**
     * Scrub a map of attributes by removing PII from values.
     *
     * @param attributes Map of attributes
     * @return Scrubbed attribute map
     */
    fun scrubAttributes(attributes: Map<String, Any>): Map<String, Any> {
        return attributes.mapValues { (_, value) ->
            when (value) {
                is String -> scrubText(value)
                else -> value
            }
        }
    }
}
