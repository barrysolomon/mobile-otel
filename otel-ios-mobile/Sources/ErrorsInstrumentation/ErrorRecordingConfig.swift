// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// Throttling configuration for `ErrorsInstrumentation.recordError`.
///
/// iOS previously had **no** bound on manual / caught-error recording: a crash
/// loop or a tight retry loop calling `recordError` could flood the telemetry
/// pipeline unbounded. This brings iOS to parity with the Android SDK's
/// `ErrorConfig`, which enforces both a rolling per-minute rate limit and a
/// time-windowed dedup of identical errors.
///
/// Defaults match Android exactly (see `ErrorConfig.kt`):
/// - `rateLimit` = 10 errors per rolling 60 s window
/// - `deduplicateWindow` = 5 minutes, keyed on `error.type` + `error.message`
///
/// All limits are opt-out: set `enabled = false` to restore the legacy
/// unbounded behavior, or widen the limits for debug builds.
public struct ErrorRecordingConfig: Sendable, Equatable {
    /// Master switch. When `false`, no rate-limiting or dedup is applied and
    /// every `recordError` call emits (legacy behavior).
    public let enabled: Bool

    /// Maximum number of errors emitted per rolling window. Matches Android's
    /// `ErrorConfig.rateLimit` (default 10). Must be > 0.
    public let rateLimit: Int

    /// Length of the rolling rate-limit window in seconds. Android uses a fixed
    /// 60 s rolling window in `RateLimiter`; exposed here for symmetry. Must be > 0.
    public let rateLimitWindowSeconds: TimeInterval

    /// Time window within which an identical error (same `error.type` +
    /// scrubbed `error.message`) is suppressed. Matches Android's
    /// `ErrorConfig.deduplicateWindowMs` (default 5 minutes). Must be > 0.
    public let deduplicateWindowSeconds: TimeInterval

    public init(
        enabled: Bool = true,
        rateLimit: Int = 10,
        rateLimitWindowSeconds: TimeInterval = 60,
        deduplicateWindowSeconds: TimeInterval = 5 * 60
    ) {
        precondition(rateLimit > 0, "rateLimit must be positive")
        precondition(rateLimitWindowSeconds > 0, "rateLimitWindowSeconds must be positive")
        precondition(deduplicateWindowSeconds > 0, "deduplicateWindowSeconds must be positive")
        self.enabled = enabled
        self.rateLimit = rateLimit
        self.rateLimitWindowSeconds = rateLimitWindowSeconds
        self.deduplicateWindowSeconds = deduplicateWindowSeconds
    }

    /// Android-matching production defaults: 10 errors/min, 5-minute dedup.
    public static let `default` = ErrorRecordingConfig()

    /// Disables throttling entirely — every recordError emits (legacy behavior).
    public static let unlimited = ErrorRecordingConfig(enabled: false)

    /// Debug posture: higher rate limit and a shorter dedup window so rapid
    /// distinct errors surface during development. Mirrors `ErrorConfig.debug()`.
    public static let debug = ErrorRecordingConfig(
        rateLimit: 50,
        deduplicateWindowSeconds: 60
    )
}
