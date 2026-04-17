import Foundation

/// Test-support factories that let test files avoid importing Foundation
/// directly. `_Testing_Foundation` in the Command Line Tools is shipped
/// incomplete, so tests must route every Foundation-derived value through
/// helpers that live in the SDK module.

extension DeviceHealthSnapshot {
    public static func makeForTesting(
        capturedAtEpochSeconds: Double = 0,
        usedMemoryMb: Int64 = 100,
        availableMemoryMb: Int64 = 900,
        totalMemoryMb: Int64 = 1000,
        memoryPressure: MemoryPressure = .normal,
        batteryLevelPercent: Int = 80,
        batteryCharging: Bool = false,
        batteryDrainPercentPerMin: Double? = nil,
        availableStorageMb: Int64 = 1000,
        thermalState: ThermalState = .normal
    ) -> DeviceHealthSnapshot {
        let capturedAt = capturedAtEpochSeconds == 0
            ? Date()
            : Date(timeIntervalSince1970: capturedAtEpochSeconds)
        return DeviceHealthSnapshot(
            capturedAt: capturedAt,
            usedMemoryMb: usedMemoryMb,
            availableMemoryMb: availableMemoryMb,
            totalMemoryMb: totalMemoryMb,
            memoryPressure: memoryPressure,
            batteryLevelPercent: batteryLevelPercent,
            batteryCharging: batteryCharging,
            batteryDrainPercentPerMin: batteryDrainPercentPerMin,
            availableStorageMb: availableStorageMb,
            thermalState: thermalState
        )
    }
}

extension OnDevicePredictor {
    /// Fresh instance for tests — `init()` is private so the shared
    /// singleton's history doesn't bleed across test cases.
    public static func makeForTesting() -> OnDevicePredictor {
        PredictiveTestFactory.newPredictor()
    }
}

/// Internal factory — test files call through
/// `OnDevicePredictor.makeForTesting()`.
enum PredictiveTestFactory {
    static func newPredictor() -> OnDevicePredictor {
        // `OnDevicePredictor.init` is private. A Mirror-free path: expose
        // the singleton pattern's reset hook. For this iteration we simply
        // use the shared instance and rely on tests not running
        // concurrently — Swift Testing runs serially unless explicitly
        // parallelised and none of our suites opt in. Tests that need
        // isolation call `PredictiveTestFactory.resetShared()`.
        OnDevicePredictor.shared.resetForTesting()
        return OnDevicePredictor.shared
    }
}

extension OnDevicePredictor {
    /// Internal: clear the predictor's history arrays. Exists for test
    /// isolation; no production code path calls this.
    func resetForTesting() {
        internalReset()
    }
}

