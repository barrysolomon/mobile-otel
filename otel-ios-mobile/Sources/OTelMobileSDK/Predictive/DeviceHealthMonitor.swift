import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// A point-in-time read of process-level health signals, plus derived
/// pressure/state enums. Mirrors Android's `DeviceHealthSnapshot`.
///
/// Fields are value types so the snapshot can be passed across actor
/// boundaries without sharing. Any read that failed (API unavailable on
/// the current device / simulator) falls back to sensible neutrals rather
/// than `nil` — the downstream predictor expects complete snapshots.
public struct DeviceHealthSnapshot: Sendable, Equatable {
    public enum MemoryPressure: String, Sendable, Equatable {
        case normal, moderate, high, critical
    }

    public enum ThermalState: String, Sendable, Equatable {
        case normal, light, moderate, severe, critical
    }

    public let capturedAt: Date
    public let usedMemoryMb: Int64
    public let availableMemoryMb: Int64
    public let totalMemoryMb: Int64
    public let memoryPressure: MemoryPressure
    public let batteryLevelPercent: Int      // 0..100, -1 if unknown
    public let batteryCharging: Bool
    public let batteryDrainPercentPerMin: Double?
    public let availableStorageMb: Int64
    public let thermalState: ThermalState
}

/// Singleton that holds the latest `DeviceHealthSnapshot` plus one-step
/// history for delta-based rate calculations. On-demand only — no internal
/// scheduler. `PredictiveExportPolicy` is the expected caller.
///
/// Thread-safety: `currentSnapshot` and `updateSnapshot()` are protected
/// by an `NSLock`. Reads are cheap (copy the value-type snapshot);
/// `updateSnapshot()` is the heavy path that touches system APIs.
public final class DeviceHealthMonitor: @unchecked Sendable {
    public static let shared = DeviceHealthMonitor()

    private let lock = NSLock()
    private var latest: DeviceHealthSnapshot?
    private var previous: DeviceHealthSnapshot?

    private init() {
        #if canImport(UIKit) && !os(watchOS)
        DispatchQueue.main.async {
            UIDevice.current.isBatteryMonitoringEnabled = true
        }
        #endif
    }

    public func currentSnapshot() -> DeviceHealthSnapshot? {
        lock.lock(); defer { lock.unlock() }
        return latest
    }

    /// Re-read every system API and rotate history. Returns the freshly
    /// captured snapshot so callers can use it directly without a second
    /// `currentSnapshot()` round-trip.
    @discardableResult
    public func updateSnapshot() -> DeviceHealthSnapshot {
        let fresh = Self.captureSnapshot(previous: previous)
        lock.lock()
        previous = latest
        latest = fresh
        lock.unlock()
        return fresh
    }

    // MARK: - Capture

    private static func captureSnapshot(previous: DeviceHealthSnapshot?) -> DeviceHealthSnapshot {
        let now = Date()
        let totalMemoryBytes = ProcessInfo.processInfo.physicalMemory
        let totalMemoryMb = Int64(totalMemoryBytes) / (1024 * 1024)

        var usedMb: Int64 = 0
        if let bytes = appMemoryUsedBytes() {
            usedMb = Int64(bytes / (1024 * 1024))
        }
        // iOS doesn't expose system-wide available RAM. Best approximation:
        // total - this-process usage. Not identical to Android's semantics but
        // the trend signal (delta between snapshots) is what the predictor
        // actually uses.
        let availMb = max(0, totalMemoryMb - usedMb)

        let pressure: DeviceHealthSnapshot.MemoryPressure
        if totalMemoryMb > 0 {
            let pct = Double(availMb) / Double(totalMemoryMb)
            switch pct {
            case _ where pct < 0.10: pressure = .critical
            case _ where pct < 0.25: pressure = .high
            case _ where pct < 0.50: pressure = .moderate
            default: pressure = .normal
            }
        } else {
            pressure = .normal
        }

        let level = batteryLevelPercent()
        let charging = batteryCharging()

        var drainRate: Double? = nil
        if let prev = previous, prev.batteryLevelPercent >= 0, level >= 0 {
            let deltaLevel = prev.batteryLevelPercent - level
            let deltaMinutes = now.timeIntervalSince(prev.capturedAt) / 60.0
            if deltaMinutes > 0, deltaLevel != 0 {
                drainRate = Double(deltaLevel) / deltaMinutes
            }
        }

        return DeviceHealthSnapshot(
            capturedAt: now,
            usedMemoryMb: usedMb,
            availableMemoryMb: availMb,
            totalMemoryMb: totalMemoryMb,
            memoryPressure: pressure,
            batteryLevelPercent: level,
            batteryCharging: charging,
            batteryDrainPercentPerMin: drainRate,
            availableStorageMb: availableStorageMb() ?? 0,
            thermalState: thermalState()
        )
    }

    private static func appMemoryUsedBytes() -> UInt64? {
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(
            MemoryLayout<mach_task_basic_info>.size / MemoryLayout<integer_t>.size
        )
        let kr = withUnsafeMutablePointer(to: &info) { ptr -> kern_return_t in
            ptr.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { rebound in
                task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), rebound, &count)
            }
        }
        guard kr == KERN_SUCCESS else { return nil }
        return info.resident_size
    }

    private static func availableStorageMb() -> Int64? {
        guard let url = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first,
              let values = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]),
              let bytes = values.volumeAvailableCapacityForImportantUsage
        else { return nil }
        return Int64(bytes) / (1024 * 1024)
    }

    private static func batteryLevelPercent() -> Int {
        #if canImport(UIKit) && !os(watchOS)
        let readOnMain: () -> Int = {
            let lvl = UIDevice.current.batteryLevel
            return lvl >= 0 ? Int((lvl * 100).rounded()) : -1
        }
        if Thread.isMainThread { return readOnMain() }
        return DispatchQueue.main.sync(execute: readOnMain)
        #else
        return -1
        #endif
    }

    private static func batteryCharging() -> Bool {
        #if canImport(UIKit) && !os(watchOS)
        let readOnMain: () -> Bool = {
            switch UIDevice.current.batteryState {
            case .charging, .full: return true
            default: return false
            }
        }
        if Thread.isMainThread { return readOnMain() }
        return DispatchQueue.main.sync(execute: readOnMain)
        #else
        return false
        #endif
    }

    private static func thermalState() -> DeviceHealthSnapshot.ThermalState {
        // iOS has 4 levels vs Android's 7; map conservatively so low-pressure
        // cases don't trip predictor thresholds tuned for Android scales.
        switch ProcessInfo.processInfo.thermalState {
        case .nominal: return .normal
        case .fair: return .light
        case .serious: return .severe
        case .critical: return .critical
        @unknown default: return .normal
        }
    }
}
