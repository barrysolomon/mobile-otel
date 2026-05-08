import Testing
@testable import OTelMobileSDK

@Suite("PredictiveExportPolicyGating")
struct PredictiveExportPolicyGatingTests {

    private func highNetworkLossPrediction() -> Prediction {
        Prediction(
            crashRisk: 0.1,
            networkLossRisk: 0.9,
            performanceDegradationRisk: 0.1,
            batteryDrainRisk: 0.1,
            confidence: 0.85
        )
    }

    private func makePolicy(
        suppressNetworkLossFlush: Bool,
        flushCapture: GatingFlushCapture
    ) -> PredictiveExportPolicy {
        let config = PredictiveExportPolicy.Config(
            intervalSeconds: 999,
            highRiskThreshold: 0.7,
            crashRiskFlushWindowMinutes: 5,
            networkRiskFlushWindowMinutes: 2,
            suppressNetworkLossFlush: suppressNetworkLossFlush
        )
        return PredictiveExportPolicy.makeForTesting(
            config: config,
            flushWindow: { minutes in flushCapture.record(minutes) }
        )
    }

    @Test("networkLossRisk flush is NOT suppressed when gating is off")
    func networkFlushNotSuppressed() {
        let capture = GatingFlushCapture()
        let policy = makePolicy(suppressNetworkLossFlush: false, flushCapture: capture)
        policy.runCycle(with: highNetworkLossPrediction())
        #expect(capture.callCount == 1)
        #expect(capture.lastCall == 2)
    }

    @Test("networkLossRisk flush IS suppressed when gating is on")
    func networkFlushSuppressed() {
        let capture = GatingFlushCapture()
        let policy = makePolicy(suppressNetworkLossFlush: true, flushCapture: capture)
        policy.runCycle(with: highNetworkLossPrediction())
        #expect(capture.callCount == 0, "network loss flush should be suppressed")
    }

    @Test("crash risk flush still fires even when network loss is suppressed")
    func crashFlushFiresWithGating() {
        let capture = GatingFlushCapture()
        let policy = makePolicy(suppressNetworkLossFlush: true, flushCapture: capture)
        let prediction = Prediction(
            crashRisk: 0.9,
            networkLossRisk: 0.9,
            performanceDegradationRisk: 0.1,
            batteryDrainRisk: 0.1,
            confidence: 0.85
        )
        policy.runCycle(with: prediction)
        #expect(capture.callCount == 1, "crash flush should fire")
        #expect(capture.lastCall == 5, "crash flush window should be 5 min")
    }

    @Test("low risk prediction does not trigger any flush")
    func lowRiskNoFlush() {
        let capture = GatingFlushCapture()
        let policy = makePolicy(suppressNetworkLossFlush: false, flushCapture: capture)
        let prediction = Prediction(
            crashRisk: 0.1,
            networkLossRisk: 0.2,
            performanceDegradationRisk: 0.1,
            batteryDrainRisk: 0.1,
            confidence: 0.85
        )
        policy.runCycle(with: prediction)
        #expect(capture.callCount == 0)
    }
}

// MARK: - Test doubles

fileprivate final class GatingFlushCapture: @unchecked Sendable {
    private var calls: [UInt64] = []

    func record(_ minutes: UInt64) {
        calls.append(minutes)
    }

    var callCount: Int { calls.count }
    var lastCall: UInt64? { calls.last }
}
