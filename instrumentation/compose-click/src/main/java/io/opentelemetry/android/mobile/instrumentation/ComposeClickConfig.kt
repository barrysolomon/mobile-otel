// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

data class ComposeClickConfig(
    val enabled: Boolean = true,
    val captureTestTag: Boolean = true,
    val captureContentDescription: Boolean = true,
    val captureRole: Boolean = true,
)
