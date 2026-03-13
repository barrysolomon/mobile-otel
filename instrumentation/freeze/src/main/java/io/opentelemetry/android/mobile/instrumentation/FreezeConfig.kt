// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import io.opentelemetry.android.mobile.instrumentation.Incubating

@Incubating
data class FreezeConfig(
    val freezeThresholdMs: Long = 2_000,
    val anrThresholdMs: Long = 5_000,
    val enabled: Boolean = true
) {
    init {
        require(freezeThresholdMs >= 250) { "freezeThresholdMs must be >= 250ms" }
        require(anrThresholdMs >= freezeThresholdMs) { "anrThresholdMs must be >= freezeThresholdMs" }
    }
}
