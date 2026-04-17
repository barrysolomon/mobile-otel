import Testing
@testable import OTelMobileSDK

/// The predictor is pure math over `DeviceHealthSnapshot` — fully testable
/// without system APIs. Foundation types arrive through
/// `DeviceHealthSnapshot.makeForTesting(...)` and
/// `OnDevicePredictor.makeForTesting()` so test files don't have to
/// `import Foundation` directly (Command Line Tools' `_Testing_Foundation`
/// overlay is incomplete).
@Suite("OnDevicePredictor")
struct OnDevicePredictorTests {
    @Test("idle device: all risks below threshold")
    func idleIsLowRisk() {
        let predictor = OnDevicePredictor.makeForTesting()
        let pred = predictor.predict(using: .makeForTesting())
        #expect(!pred.hasHighRisk(threshold: 0.7))
        #expect(pred.crashRisk < 0.5)
        #expect(pred.batteryDrainRisk < 0.5)
    }

    @Test("critical memory pressure + low available memory: high crash risk")
    func crashRiskHighOnMemoryPressure() {
        let predictor = OnDevicePredictor.makeForTesting()
        let snap = DeviceHealthSnapshot.makeForTesting(
            availableMemoryMb: 20, memoryPressure: .critical
        )
        let pred = predictor.predict(using: snap)
        // 0.6 (critical) + 0.4 (<50MB available) = 1.0
        #expect(pred.crashRisk >= 0.9)
        #expect(pred.hasHighRisk())
    }

    @Test("battery below 10% not charging: high battery drain risk")
    func batteryDrainRisk() {
        let predictor = OnDevicePredictor.makeForTesting()
        let snap = DeviceHealthSnapshot.makeForTesting(
            batteryLevelPercent: 5, batteryCharging: false,
            batteryDrainPercentPerMin: 1.5
        )
        let pred = predictor.predict(using: snap)
        // 0.6 (<10%) + 0.4 (drain > 1/min) = 1.0
        #expect(pred.batteryDrainRisk >= 0.9)
    }

    @Test("charging state zeroes the battery-drain risk even at low percent")
    func chargingZeroesBatteryRisk() {
        let predictor = OnDevicePredictor.makeForTesting()
        let snap = DeviceHealthSnapshot.makeForTesting(
            batteryLevelPercent: 5, batteryCharging: true
        )
        let pred = predictor.predict(using: snap)
        #expect(pred.batteryDrainRisk == 0)
    }

    @Test("severe thermal state drives performance-degradation risk high")
    func thermalPushesPerfRisk() {
        let predictor = OnDevicePredictor.makeForTesting()
        let snap = DeviceHealthSnapshot.makeForTesting(
            memoryPressure: .high, thermalState: .severe
        )
        let pred = predictor.predict(using: snap)
        // 0.6 (severe thermal) + 0.2 (high memory pressure) = 0.8
        #expect(pred.performanceDegradationRisk >= 0.7)
    }

    @Test("a .lost network event produces full network-loss risk")
    func networkLoss() {
        let predictor = OnDevicePredictor.makeForTesting()
        // NetworkEvent.init has a default `at: Date = Date()` so we don't
        // have to import Foundation just to pass a timestamp.
        predictor.recordNetworkEvent(NetworkEvent(kind: .lost))
        let pred = predictor.predict(using: .makeForTesting())
        #expect(pred.networkLossRisk >= 0.9)
    }

    @Test("confidence ramps from 0.5 to 1.0 as history fills")
    func confidenceRamp() {
        let predictor = OnDevicePredictor.makeForTesting()
        let initial = predictor.predict(using: .makeForTesting())
        #expect(initial.confidence == 0.5)

        for _ in 0..<20 {
            predictor.record(snapshot: .makeForTesting())
        }
        let saturated = predictor.predict(using: .makeForTesting())
        #expect(saturated.confidence == 1.0)
    }

    @Test("all risk scores are clamped 0..1")
    func risksClamped() {
        let predictor = OnDevicePredictor.makeForTesting()
        let extreme = DeviceHealthSnapshot.makeForTesting(
            availableMemoryMb: 10, memoryPressure: .critical,
            batteryLevelPercent: 5, batteryCharging: false,
            batteryDrainPercentPerMin: 10.0, thermalState: .critical
        )
        let pred = predictor.predict(using: extreme)
        #expect(pred.crashRisk <= 1.0)
        #expect(pred.networkLossRisk <= 1.0)
        #expect(pred.performanceDegradationRisk <= 1.0)
        #expect(pred.batteryDrainRisk <= 1.0)
        #expect(pred.crashRisk >= 0)
    }
}
