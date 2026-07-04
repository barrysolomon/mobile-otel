// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

data class ComposeNavigationConfig(
    val enabled: Boolean = true,
    val emitScreenViewLogs: Boolean = true,
    val emitPageSpans: Boolean = true,
)
