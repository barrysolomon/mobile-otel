import Testing
@testable import OTelMobileSDK

/// `FleetAlertHandler` has four validation stages (expiry, dedup, rate
/// limit, privacy gates) plus action dispatch. Every path gets a test.
///
/// Foundation types (UUID, Date, ISO formatter) arrive through the
/// `FleetAlert.makeForTesting(...)` / `FleetAlertDeduplicator.makeEphemeralForTesting()`
/// helpers so test files don't have to `import Foundation` (Command Line
/// Tools' `_Testing_Foundation` overlay is incomplete).
@Suite("FleetAlertHandler")
struct FleetAlertHandlerTests {
    @Test("expired alert is rejected")
    func expired() {
        let h = FleetAlertHandler(
            dedup: .makeEphemeralForTesting(),
            flushWindow: { _ in }
        )
        let result = h.handle(.makeForTesting(expiresSecondsAhead: -60))
        #expect(!result.accepted)
        #expect(result.reason == "expired")
    }

    @Test("invalid expiresAt format is rejected")
    func invalidExpiry() {
        let h = FleetAlertHandler(
            dedup: .makeEphemeralForTesting(),
            flushWindow: { _ in }
        )
        let result = h.handle(.makeWithInvalidExpiryForTesting())
        #expect(!result.accepted)
        #expect(result.reason == "invalid_expires_at")
    }

    @Test("duplicate alertId is rejected")
    func duplicate() {
        let h = FleetAlertHandler(
            dedup: .makeEphemeralForTesting(),
            flushWindow: { _ in }
        )
        let a = FleetAlert.makeForTesting(alertId: "dup-1")
        let first = h.handle(a)
        #expect(first.accepted)
        let second = h.handle(a)
        #expect(!second.accepted)
        #expect(second.reason == "duplicate")
    }

    @Test("flush_buffer action calls the flushWindow closure with the configured minutes")
    func flushAction() {
        final class Box: @unchecked Sendable { var value: UInt64? }
        let box = Box()
        let h = FleetAlertHandler(
            dedup: .makeEphemeralForTesting(),
            flushWindow: { box.value = $0 }
        )
        let a = FleetAlert.makeForTesting(
            alertId: "flush-1",
            actions: [FleetAction(type: "flush_buffer", config: ["minutes": "7"])]
        )
        let result = h.handle(a)
        #expect(result.accepted)
        #expect(result.actionsExecuted == ["flush_buffer"])
        #expect(box.value == 7)
    }

    @Test("flush_buffer respects allowFleetFlush=false")
    func flushGated() {
        let cfg = FleetAlertHandler.Config(allowFleetFlush: false)
        let h = FleetAlertHandler(
            config: cfg,
            dedup: .makeEphemeralForTesting(),
            flushWindow: { _ in }
        )
        let a = FleetAlert.makeForTesting(
            alertId: "flush-gated",
            actions: [FleetAction(type: "flush_buffer", config: ["minutes": "5"])]
        )
        let result = h.handle(a)
        // Alert is accepted, but the individual action is skipped due to
        // the privacy gate.
        #expect(result.accepted)
        #expect(result.actionsSkipped.contains("flush_buffer:privacy_gate"))
        #expect(result.actionsExecuted.isEmpty)
    }

    @Test("set_sampling higher priority preempts existing override")
    func samplingPriorityWins() {
        let h = FleetAlertHandler(
            dedup: .makeEphemeralForTesting(),
            flushWindow: { _ in }
        )
        let first = FleetAlert.makeForTesting(
            alertId: "s1", priority: 1,
            actions: [FleetAction(type: "set_sampling", config: ["rate": "0.1", "duration_seconds": "600"])]
        )
        _ = h.handle(first)
        let second = FleetAlert.makeForTesting(
            alertId: "s2", priority: 5,
            actions: [FleetAction(type: "set_sampling", config: ["rate": "0.5", "duration_seconds": "600"])]
        )
        _ = h.handle(second)
        #expect(h.activeSamplingOverride()?.rate == 0.5)
        #expect(h.activeSamplingOverride()?.priority == 5)
    }

    @Test("set_sampling lower priority does NOT preempt")
    func samplingPriorityLoses() {
        let h = FleetAlertHandler(
            dedup: .makeEphemeralForTesting(),
            flushWindow: { _ in }
        )
        let high = FleetAlert.makeForTesting(
            alertId: "high", priority: 10,
            actions: [FleetAction(type: "set_sampling", config: ["rate": "0.1", "duration_seconds": "600"])]
        )
        _ = h.handle(high)
        let low = FleetAlert.makeForTesting(
            alertId: "low", priority: 1,
            actions: [FleetAction(type: "set_sampling", config: ["rate": "0.9", "duration_seconds": "600"])]
        )
        let result = h.handle(low)
        #expect(result.actionsSkipped.contains("set_sampling:lower_priority"))
        #expect(h.activeSamplingOverride()?.rate == 0.1)
    }

    @Test("rate limit kicks in at max alerts per window")
    func rateLimit() {
        let cfg = FleetAlertHandler.Config(maxAlertsPerWindow: 3, rateLimitWindowSeconds: 3600)
        let h = FleetAlertHandler(
            config: cfg,
            dedup: .makeEphemeralForTesting(),
            flushWindow: { _ in }
        )
        for i in 0..<3 {
            _ = h.handle(.makeForTesting(alertId: "rate-\(i)"))
        }
        let overflow = h.handle(.makeForTesting(alertId: "rate-3"))
        #expect(!overflow.accepted)
        #expect(overflow.reason == "rate_limited")
    }
}
