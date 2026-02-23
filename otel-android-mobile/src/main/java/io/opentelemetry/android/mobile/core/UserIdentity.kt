/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.core

/**
 * User identity information for associating telemetry with specific users.
 *
 * All user attributes are optional except userId. Email is hashed by default
 * for privacy protection.
 */
data class UserIdentity(
    /**
     * Unique identifier for the user. This is the only required field.
     * Example: "user_12345" or UUID
     */
    val userId: String,

    /**
     * User email address. Will be hashed (SHA-256) by default unless
     * hashEmail is set to false.
     */
    val email: String? = null,

    /**
     * User display name. Opt-in only - not captured by default.
     */
    val name: String? = null,

    /**
     * Custom user attributes (key-value pairs).
     * Example: mapOf("plan" to "premium", "region" to "us-west")
     */
    val customAttributes: Map<String, Any> = emptyMap(),

    /**
     * Whether to hash the email address before storing.
     * Default: true (hash for privacy)
     */
    val hashEmail: Boolean = true
)
