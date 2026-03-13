// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import io.opentelemetry.android.mobile.instrumentation.Incubating

/**
 * Configuration for [TextInputInstrumentation].
 *
 * By default, captures non-sensitive field metadata: character count and whether
 * the field was non-empty. Text content is **never** captured unless explicitly
 * opted in via [captureTextContent] and the field's resource ID is in [textContentAllowlist].
 *
 * @param captureCharCount Emit `ui.element.char_count` — length of text when focus leaves the field.
 *   Safe by default: reveals how much was typed, not what.
 * @param captureIsSet Emit `ui.element.is_set` — true if the field was non-empty when focus left.
 *   Useful for detecting skipped required fields.
 * @param captureTextContent Opt-in: emit `ui.element.text` with the raw text value.
 *   **Only emitted for fields whose resource ID is in [textContentAllowlist].**
 *   Do NOT enable for password, credit-card, or other sensitive fields.
 * @param textContentAllowlist Resource IDs of fields allowed to capture raw text.
 *   Ignored unless [captureTextContent] is true.
 */
@Incubating
data class TextInputConfig(
    val captureCharCount: Boolean = true,
    val captureIsSet: Boolean = true,
    val captureTextContent: Boolean = false,
    val textContentAllowlist: Set<String> = emptySet()
)
