// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

import Foundation
import Testing
@testable import ErrorsInstrumentation
import OpenTelemetryApi
import OpenTelemetrySdk

/// Unit tests for the rate-limiter + dedup gate ported from Android
/// (`RateLimiter.kt` + `ErrorConfig` dedup). These exercise the throttle in
/// isolation with an injectable clock for determinism, plus an integration
/// suite that drives `ErrorsInstrumentation.recordError` end-to-end and
/// asserts the emitted-log count.
@Suite("ErrorRecordingThrottle")
struct ErrorRecordingThrottleTests {

    private func fp(_ s: String) -> String {
        ErrorRecordingThrottle.fingerprint(type: "E", message: s)
    }

    // MARK: - Rate limit

    @Test("over-limit distinct errors are dropped within the window")
    func rateLimitDropsOverLimit() {
        let throttle = ErrorRecordingThrottle(config: ErrorRecordingConfig(rateLimit: 3))
        let t0: TimeInterval = 1000
        // 5 distinct fingerprints (so dedup never fires); only 3 should pass.
        var emitted = 0
        for i in 1...5 {
            if throttle.shouldEmit(fingerprint: fp("err\(i)"), now: t0) { emitted += 1 }
        }
        #expect(emitted == 3)
        #expect(throttle.droppedByRateLimit == 2)
    }

    @Test("rolling window resets — events outside the window free up slots")
    func rateLimitWindowResets() {
        let throttle = ErrorRecordingThrottle(
            config: ErrorRecordingConfig(rateLimit: 2, rateLimitWindowSeconds: 60, deduplicateWindowSeconds: 1)
        )
        let t0: TimeInterval = 0
        #expect(throttle.shouldEmit(fingerprint: fp("a"), now: t0))
        #expect(throttle.shouldEmit(fingerprint: fp("b"), now: t0))
        // Third within the window is rate-limited.
        #expect(!throttle.shouldEmit(fingerprint: fp("c"), now: t0 + 1))
        // After the 60 s window slides past the first two, slots free up.
        #expect(throttle.shouldEmit(fingerprint: fp("d"), now: t0 + 61))
        #expect(throttle.shouldEmit(fingerprint: fp("e"), now: t0 + 61))
    }

    @Test("default rate limit matches Android (10 per minute)")
    func defaultRateLimitMatchesAndroid() {
        let throttle = ErrorRecordingThrottle(config: .default)
        let t0: TimeInterval = 0
        var emitted = 0
        for i in 1...15 {
            // distinct fingerprints to isolate the rate limit from dedup
            if throttle.shouldEmit(fingerprint: fp("e\(i)"), now: t0) { emitted += 1 }
        }
        #expect(emitted == 10)
    }

    // MARK: - Dedup

    @Test("identical errors within the dedup window are suppressed")
    func dedupSuppressesIdentical() {
        let throttle = ErrorRecordingThrottle(config: .default)
        let t0: TimeInterval = 0
        #expect(throttle.shouldEmit(fingerprint: fp("boom"), now: t0))
        #expect(!throttle.shouldEmit(fingerprint: fp("boom"), now: t0 + 1))
        #expect(!throttle.shouldEmit(fingerprint: fp("boom"), now: t0 + 60))
        #expect(throttle.droppedByDedup == 2)
    }

    @Test("distinct errors are not deduplicated")
    func dedupAllowsDistinct() {
        let throttle = ErrorRecordingThrottle(config: .default)
        let t0: TimeInterval = 0
        #expect(throttle.shouldEmit(fingerprint: fp("a"), now: t0))
        #expect(throttle.shouldEmit(fingerprint: fp("b"), now: t0))
        #expect(throttle.shouldEmit(fingerprint: fp("c"), now: t0))
        #expect(throttle.droppedByDedup == 0)
    }

    @Test("identical error re-emits after the dedup window elapses")
    func dedupWindowExpires() {
        let throttle = ErrorRecordingThrottle(
            config: ErrorRecordingConfig(rateLimit: 100, deduplicateWindowSeconds: 300)
        )
        let t0: TimeInterval = 0
        #expect(throttle.shouldEmit(fingerprint: fp("boom"), now: t0))
        #expect(!throttle.shouldEmit(fingerprint: fp("boom"), now: t0 + 299))
        // 300 s later the window has elapsed → re-emits.
        #expect(throttle.shouldEmit(fingerprint: fp("boom"), now: t0 + 301))
    }

    // MARK: - enabled=false

    @Test("disabled config emits everything (legacy unbounded behavior)")
    func disabledEmitsAll() {
        let throttle = ErrorRecordingThrottle(config: .unlimited)
        let t0: TimeInterval = 0
        var emitted = 0
        for _ in 1...100 {
            if throttle.shouldEmit(fingerprint: fp("same"), now: t0) { emitted += 1 }
        }
        #expect(emitted == 100)
        #expect(throttle.droppedByRateLimit == 0)
        #expect(throttle.droppedByDedup == 0)
    }

    // MARK: - Thread safety

    @Test("concurrent shouldEmit never exceeds the rate limit")
    func concurrentRespectsLimit() async {
        let limit = 50
        let throttle = ErrorRecordingThrottle(
            // Big window + tiny dedup so the rate limit is the sole gate and
            // every call uses a distinct fingerprint.
            config: ErrorRecordingConfig(rateLimit: limit, rateLimitWindowSeconds: 3600, deduplicateWindowSeconds: 0.0001)
        )
        let t0: TimeInterval = 1_000_000
        let counter = AcceptCounter()
        await withTaskGroup(of: Bool.self) { group in
            for i in 0..<1000 {
                group.addTask {
                    throttle.shouldEmit(fingerprint: self.fp("c\(i)"), now: t0)
                }
            }
            for await ok in group where ok { await counter.inc() }
        }
        let accepted = await counter.value
        // Exactly `limit` should be accepted; the rest rate-limited. The
        // critical invariant is the limit is never exceeded under contention.
        #expect(accepted == limit, "accepted \(accepted), expected \(limit)")
    }

    private actor AcceptCounter {
        private(set) var value = 0
        func inc() { value += 1 }
    }
}
