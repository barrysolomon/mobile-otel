// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

/**
 * Format for screenshot capture output.
 */
@Incubating
enum class ScreenshotFormat {
    PNG,
    JPEG
}

/**
 * Configuration for [ScreenshotInstrumentation].
 *
 * The screenshot is emitted as a **data URL** (`data:image/jpeg;base64,…`) in the
 * `mobile.screenshot.data_url` log attribute, making it directly renderable in any
 * dashboard or browser that supports data URIs.
 *
 * **Payload size guide** (approximate, depends on screen content):
 * | Resolution  | JPEG q50 | PNG      |
 * |-------------|----------|----------|
 * | 480×960     | 20–67 KB | 67–267 KB |
 * | 320×640     | 10–35 KB | 30–120 KB |
 *
 * Defaults are tuned for a reasonable trade-off: JPEG at quality 50, 480×960 max,
 * with a 200 KB payload cap. Override [maxPayloadKb] to suit your backend's limits.
 *
 * @property enabled Whether screenshot capture is active.
 * @property maxWidthPx Maximum width in pixels — images are downscaled to fit. Lower = smaller payload.
 * @property maxHeightPx Maximum height in pixels — images are downscaled to fit.
 * @property quality Compression quality 0–100 (only affects JPEG; PNG is lossless).
 * @property format Output image format. JPEG recommended — typically 3–5× smaller than PNG.
 * @property maxPayloadKb Maximum encoded payload size in KB. If the compressed + base64 image
 *   exceeds this, the capture is silently dropped. Prevents oversized log records from
 *   hitting OTLP message-size limits (default gRPC max is 4 MB).
 * @property redactTextViews When true, solid rectangles are drawn over all [android.widget.TextView]
 *   bounds in the captured image, masking text content for privacy.
 * @property captureOnError Automatically capture a screenshot when an uncaught exception occurs.
 * @property captureOnScreenView Automatically capture a screenshot on each screen transition
 *   (activity resume). Off by default — produces significant payload volume when enabled.
 * @property screenViewDelayMs Delay in milliseconds after activity resume before capturing
 *   the screenshot. Allows the screen to finish rendering. Only used when [captureOnScreenView] is true.
 * @property maxCapturesPerMinute Rate limit to prevent excessive captures (e.g., in a crash loop).
 */
@Incubating
data class ScreenshotConfig(
    val enabled: Boolean = true,
    val maxWidthPx: Int = 480,
    val maxHeightPx: Int = 960,
    val quality: Int = 50,
    val format: ScreenshotFormat = ScreenshotFormat.JPEG,
    val maxPayloadKb: Int = 200,
    val redactTextViews: Boolean = true,
    val captureOnError: Boolean = true,
    val captureOnScreenView: Boolean = false,
    val screenViewDelayMs: Long = 500,
    val maxCapturesPerMinute: Int = 5
) {
    init {
        require(maxWidthPx in 1..4096) { "maxWidthPx must be in 1..4096" }
        require(maxHeightPx in 1..4096) { "maxHeightPx must be in 1..4096" }
        require(quality in 0..100) { "quality must be in 0..100" }
        require(maxPayloadKb in 1..4096) { "maxPayloadKb must be in 1..4096" }
        require(screenViewDelayMs in 0..5000) { "screenViewDelayMs must be in 0..5000" }
        require(maxCapturesPerMinute in 1..60) { "maxCapturesPerMinute must be in 1..60" }
    }
}
