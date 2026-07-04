// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo

/**
 * Feature flags for demo telemetry behaviour.
 *
 * [captureInteractionTraces] — when true, user interactions that result in an outbound
 * API call or page render are wrapped in a parent OTel trace span, so the full flow
 * (user action → network call → UI update) appears as a single trace.
 *
 * [showDebugToolbar] — when true, the collapsible Debug Tools bar is visible at the top of
 * every activity. Set to false to hide it (e.g. when demoing to external audiences or when
 * you don't want fault-injection buttons visible). Changing this at runtime takes effect the
 * next time the activity is created; call Activity.applyDebugToolbarVisibility() to update
 * an already-running activity immediately.
 */
object TelemetryFlags {
    var captureInteractionTraces: Boolean = true
    var showDebugToolbar: Boolean = true
}
