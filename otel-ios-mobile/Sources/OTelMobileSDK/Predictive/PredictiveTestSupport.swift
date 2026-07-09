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
    /// Fresh, detached instance for tests. This must NEVER return `.shared`:
    /// Swift Testing runs tests in parallel by default, so handing every
    /// test a reset of the same singleton let one test wipe another's
    /// history mid-run (confidence saturated at 0.875 instead of 1.0 —
    /// 1/12 full-suite runs, 2026-07-09).
    public static func makeForTesting() -> OnDevicePredictor {
        OnDevicePredictor()
    }
}

