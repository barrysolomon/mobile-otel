import Foundation
import OpenTelemetryApi
import OpenTelemetrySdk
#if canImport(UIKit)
import UIKit
#endif

/// Periodically records device health metrics (memory, battery, thermal,
/// storage) as OpenTelemetry gauges. Driven by the application — call
/// `start(meter:intervalSeconds:)` to begin emitting, `stop()` to pause.
/// Starting multiple times is idempotent.
///
/// The collector is deliberately conservative: any single reading failure is
/// swallowed so the loop keeps running. Memory snapshots are taken from the
/// current process (`mach_task_basic_info`); storage and battery reads touch
/// OS APIs that may not be available in every simulator configuration.
///
/// Because `Meter` in OpenTelemetry-Swift carries associated types it cannot
/// be used behind `any Meter`. We accept the concrete SDK type `MeterSdk`
/// which is what `MeterProviderSdk.get(name:)` returns.
public final class DeviceStatsCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var task: Task<Void, Never>?
    private var isRunning = false

    public init() {}

    /// Whether the collector loop is currently running.
    public var running: Bool {
        lock.lock(); defer { lock.unlock() }
        return isRunning
    }

    /// Start the periodic collection loop. No-op if already running.
    /// `intervalSeconds` is the gap between samples. Gauge instruments are
    /// built once up-front and reused across iterations.
    @MainActor
    public func start(meter: MeterSdk, intervalSeconds: UInt64 = 5) {
        lock.lock()
        guard !isRunning else { lock.unlock(); return }
        isRunning = true
        lock.unlock()

        // Enable battery monitoring so UIDevice.batteryLevel returns a real
        // value (it reports -1 when monitoring is disabled).
        #if canImport(UIKit)
        UIDevice.current.isBatteryMonitoringEnabled = true
        #endif

        // Build each gauge instrument once; OTel-Swift's gauge API is a
        // builder that returns a concrete DoubleGauge (or LongGauge via
        // ofLongs()).
        let memoryGauge = meter.gaugeBuilder(name: "device.memory.used_mb").build()
        let memoryAvailGauge = meter.gaugeBuilder(name: "device.memory.available_mb").build()
        let batteryGauge = meter.gaugeBuilder(name: "device.battery.level").build()
        let thermalGauge = meter.gaugeBuilder(name: "device.thermal.state").ofLongs().build()
        let storageGauge = meter.gaugeBuilder(name: "device.storage.available_mb").build()

        let intervalNanos = intervalSeconds * 1_000_000_000
        task = Task.detached { [weak self] in
            while !Task.isCancelled {
                guard let self = self else { return }
                guard self.running else { return }

                if let memUsed = Self.memoryUsedMb() {
                    memoryGauge.record(value: memUsed)
                }
                if let memAvailMb = Self.memoryAvailableMb() {
                    memoryAvailGauge.record(value: memAvailMb)
                }
                #if canImport(UIKit)
                let level = await MainActor.run { UIDevice.current.batteryLevel }
                if level >= 0 {
                    batteryGauge.record(value: Double(level))
                }
                #endif
                let thermalState = ProcessInfo.processInfo.thermalState.rawValue
                thermalGauge.record(value: thermalState)

                if let storageMb = Self.storageAvailableMb() {
                    storageGauge.record(value: storageMb)
                }

                try? await Task.sleep(nanoseconds: intervalNanos)
            }
        }
    }

    /// Stop the periodic collection loop. The already-built gauge instruments
    /// on the MeterProvider continue to exist; this just halts new samples.
    public func stop() {
        lock.lock()
        isRunning = false
        task?.cancel()
        task = nil
        lock.unlock()
    }

    // MARK: - Sampling helpers

    private static func memoryUsedMb() -> Double? {
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(
            MemoryLayout<mach_task_basic_info>.size / MemoryLayout<integer_t>.size
        )
        let result = withUnsafeMutablePointer(to: &info) { ptr -> kern_return_t in
            ptr.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { rebound in
                task_info(
                    mach_task_self_,
                    task_flavor_t(MACH_TASK_BASIC_INFO),
                    rebound,
                    &count
                )
            }
        }
        guard result == KERN_SUCCESS else { return nil }
        return Double(info.resident_size) / (1024 * 1024)
    }

    private static func memoryAvailableMb() -> Double? {
        let total = Double(ProcessInfo.processInfo.physicalMemory) / (1024 * 1024)
        guard let used = memoryUsedMb() else { return nil }
        return max(0, total - used)
    }

    private static func storageAvailableMb() -> Double? {
        guard let url = FileManager.default
                .urls(for: .cachesDirectory, in: .userDomainMask).first,
              let values = try? url.resourceValues(
                forKeys: [.volumeAvailableCapacityForImportantUsageKey]
              ),
              let bytes = values.volumeAvailableCapacityForImportantUsage
        else { return nil }
        return Double(bytes) / (1024 * 1024)
    }
}
