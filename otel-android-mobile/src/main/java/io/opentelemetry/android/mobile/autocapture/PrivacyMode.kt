/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.autocapture

/**
 * Privacy modes for auto-capture payloads.
 */
enum class PrivacyMode {
    /**
     * Hash UI text/labels and bucket coordinates. Safe default.
     */
    STRICT,

    /**
     * Allow raw UI text/labels (not recommended for production).
     */
    RELAXED
}
