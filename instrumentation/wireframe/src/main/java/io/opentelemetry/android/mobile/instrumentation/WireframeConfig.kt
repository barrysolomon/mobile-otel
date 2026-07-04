// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

/**
 * Configuration for [WireframeInstrumentation].
 *
 * Controls what is captured in the view-hierarchy wireframe and when captures are triggered.
 * The wireframe is a compact JSON tree (~1–5 KB per frame) describing the layout geometry
 * of every visible view, suitable for journey replay and structural analysis.
 *
 * @property enabled Whether wireframe capture is active.
 * @property captureOnScreenView Automatically capture a wireframe on every screen transition
 *   (pairs with [ScreenViewInstrumentation] events). This is the primary journey-building trigger.
 * @property captureOnTap Capture a wireframe after each tap event. **Off by default**:
 *   taps rarely change the view hierarchy, so per-tap captures produce duplicate JSON.
 *   Prefer associating taps with the most recent wireframe via the `mobile.wireframe.id`
 *   attribute (auto-attached by [TapInstrumentation] when this module is installed) and
 *   relying on [dedupeByContentHash] to suppress identical re-captures.
 * @property captureOnError Capture a wireframe when an uncaught exception occurs.
 * @property captureOnPolicyMatch Capture a wireframe whenever a buffered-export policy
 *   fires (crash-recovery, ui-freeze, http-error, etc.). The wireframe gets emitted into
 *   the same flush window, giving every server-side incident an attached "what was on
 *   screen" artifact. Default ON.
 * @property dedupeByContentHash When ON, the module hashes each captured wireframe and
 *   skips emit if the hash matches the previous capture — typical no-op taps and repeated
 *   `onResume` callbacks won't fill the buffer with duplicate JSON trees. Instead a
 *   `ui.wireframe.ref` log is emitted carrying the prior `mobile.wireframe.id`.
 * @property maxDepth Maximum view-tree traversal depth. Deeper trees are truncated with a
 *   `"truncated": true` marker. Keeps payload predictable for deeply nested layouts.
 * @property includeResourceIds Include Android resource IDs (e.g., `"btn_book"`) on views
 *   that have them. Useful for mapping wireframes to code, but may leak internal naming.
 * @property includeTextHints Include placeholder/hint text (e.g., "Enter name") but never
 *   actual user-entered text. Helps identify fields without exposing PII.
 * @property includeContentDescription Include accessibility `contentDescription` values.
 *   Helpful for understanding unlabelled icons/images.
 * @property includeClickableState Include `clickable` and `enabled` flags on each view.
 * @property maxCapturesPerMinute Rate limit to prevent excessive captures during rapid navigation.
 */
@Incubating
data class WireframeConfig(
    val enabled: Boolean = true,
    val captureOnScreenView: Boolean = true,
    val captureOnTap: Boolean = false,
    val captureOnError: Boolean = true,
    val captureOnPolicyMatch: Boolean = true,
    val dedupeByContentHash: Boolean = true,
    val maxDepth: Int = 30,
    val includeResourceIds: Boolean = true,
    val includeTextHints: Boolean = false,
    val includeContentDescription: Boolean = false,
    val includeClickableState: Boolean = true,
    val maxCapturesPerMinute: Int = 30
) {
    init {
        require(maxDepth in 1..100) { "maxDepth must be in 1..100" }
        require(maxCapturesPerMinute in 1..120) { "maxCapturesPerMinute must be in 1..120" }
    }
}
