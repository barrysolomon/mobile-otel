// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo

/**
 * Feature flags for demo telemetry behaviour.
 *
 * [captureInteractionTraces] — when true, user interactions that result in an outbound
 * API call or page render are wrapped in a parent OTel trace span, so the full flow
 * (user action → network call → UI update) appears as a single trace.
 */
object TelemetryFlags {
    var captureInteractionTraces: Boolean = true
}
