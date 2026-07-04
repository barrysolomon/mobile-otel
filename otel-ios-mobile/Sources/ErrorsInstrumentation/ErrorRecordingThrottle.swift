// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

import Foundation

/// Thread-safe rate-limiter + deduplicator for `recordError`.
///
/// Mirrors the Android SDK's two-stage gate (see `RateLimiter.kt` +
/// `ErrorInstrumentation.shouldCaptureError`):
///
/// 1. **Dedup** — identical errors (same fingerprint: `error.type` +
///    scrubbed `error.message`) seen within `deduplicateWindow` are dropped.
///    Android stamps the fingerprint timestamp and re-emits only after the
///    window elapses.
/// 2. **Rate limit** — a rolling-window counter (default 10 / 60 s) drops any
///    error that would exceed the limit, regardless of fingerprint. This is a
///    true rolling window (timestamps pruned by age), so there is no
///    burst-at-boundary edge case — identical to Android's `RateLimiter`.
///
/// Ordering matches Android: rate limit is checked *first* (so a flood of
/// distinct errors is capped), then dedup. A dropped error never consumes a
/// dedup slot beyond its single fingerprint stamp.
///
/// All state is guarded by a single `NSLock`; `shouldEmit` is the only mutating
/// entry point and is safe to call concurrently from many threads.
final class ErrorRecordingThrottle: @unchecked Sendable {
    private let config: ErrorRecordingConfig
    private let lock = NSLock()

    /// Monotonic-ish timestamps (seconds) of recently emitted errors, used for
    /// the rolling rate-limit window. Pruned on every check.
    private var emitTimestamps: [TimeInterval] = []

    /// Fingerprint → last-emit time (seconds). Used for the dedup window.
    private var lastSeen: [String: TimeInterval] = [:]

    /// Count of errors dropped because they exceeded the rate limit. Exposed
    /// for tests and a future health gauge (parallels iOS RAM buffer's
    /// `droppedOversizeCount`).
    private(set) var droppedByRateLimit: Int = 0

    /// Count of errors dropped because an identical error was seen within the
    /// dedup window.
    private(set) var droppedByDedup: Int = 0

    init(config: ErrorRecordingConfig) {
        self.config = config
    }

    /// Build the dedup fingerprint from the same fields Android keys on:
    /// exception type + message. The message is already scrubbed by the caller.
    static func fingerprint(type: String, message: String) -> String {
        "\(type)|\(message)"
    }

    /// Decide whether an error with this fingerprint should be emitted now.
    /// Returns `true` to emit, `false` to drop (rate-limited or deduplicated).
    ///
    /// `now` is injectable for deterministic tests; production passes the
    /// default `Date().timeIntervalSinceReferenceDate`.
    func shouldEmit(
        fingerprint: String,
        now: TimeInterval = Date().timeIntervalSinceReferenceDate
    ) -> Bool {
        guard config.enabled else { return true }

        lock.lock()
        defer { lock.unlock() }

        // Stage 1: rolling-window rate limit (checked first, matching Android).
        let rateCutoff = now - config.rateLimitWindowSeconds
        emitTimestamps.removeAll { $0 < rateCutoff }
        if emitTimestamps.count >= config.rateLimit {
            droppedByRateLimit += 1
            return false
        }

        // Stage 2: dedup within window.
        if let last = lastSeen[fingerprint], (now - last) < config.deduplicateWindowSeconds {
            droppedByDedup += 1
            return false
        }

        // Accepted: consume a rate-limit slot and stamp the fingerprint.
        emitTimestamps.append(now)
        lastSeen[fingerprint] = now

        // Opportunistically prune dedup entries that have fully expired so the
        // map can't grow unbounded under a stream of distinct errors.
        pruneExpiredFingerprintsLocked(now: now)

        return true
    }

    /// Remove dedup entries older than the dedup window. Caller holds the lock.
    private func pruneExpiredFingerprintsLocked(now: TimeInterval) {
        guard lastSeen.count > 1 else { return }
        let cutoff = now - config.deduplicateWindowSeconds
        lastSeen = lastSeen.filter { $0.value >= cutoff }
    }

    /// Clear all throttle state. Test seam.
    func reset() {
        lock.lock(); defer { lock.unlock() }
        emitTimestamps.removeAll()
        lastSeen.removeAll()
        droppedByRateLimit = 0
        droppedByDedup = 0
    }
}
