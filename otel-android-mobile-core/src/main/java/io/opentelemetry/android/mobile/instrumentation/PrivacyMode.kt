// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import io.opentelemetry.android.mobile.instrumentation.Incubating

/**
 * Privacy modes for auto-capture payloads.
 */
@Incubating
enum class PrivacyMode {
    /** Hash UI text/labels and bucket coordinates. Safe default. */
    STRICT,
    /** Allow raw UI text/labels. Not recommended for production. */
    RELAXED
}
