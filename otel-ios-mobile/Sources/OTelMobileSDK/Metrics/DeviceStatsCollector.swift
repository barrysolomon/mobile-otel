/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation
import OpenTelemetryApi
import OpenTelemetrySdk
#if canImport(UIKit)
import UIKit
#endif

/// Periodically records device health metrics (memory, battery, thermal,
/// storage) as OpenTelemetry gauges.
///
/// Driven by a `DispatchSourceTimer` on a dedicated utility queue rather
/// than `Task.detached { while !Task.isCancelled { ... } }` — the latter
/// pattern silently doesn't execute in SwiftUI-hosted iOS apps under some
/// actor-isolation conditions we saw on the `iPhone` branch. A GCD timer
/// is independent of actor scheduling and always fires.
///
/// Any single reading failure is swallowed so the loop keeps running.
/// Memory snapshots are taken from the current process
/// (`mach_task_basic_info`); storage and battery reads touch OS APIs that
/// may not be available in every simulator configuration.
public final class DeviceStatsCollector: @unchecked Sendable {
    private let lock = NSLock()
    private let queue = DispatchQueue(
        label: "io.dash0.mobile.DeviceStatsCollector", qos: .utility
    )
    private var timer: DispatchSourceTimer?
    // Gauge instruments are held as `var` because OTel-Swift's gauge
    // protocol declares `mutating func record`.
    private var memoryUsedGauge: DoubleGauge?
    private var memoryAvailGauge: DoubleGauge?
    private var batteryGauge: DoubleGauge?
    private var thermalGauge: LongGauge?
    private var storageGauge: DoubleGauge?
    // NOTE: the SDK-state gauges (`sdk.enabled` / `sdk.sample_rate`) used to
    // live here, gated behind `.deviceStats`. They moved to the dedicated
    // `SDKStateGaugeCollector`, which `OTelMobile.start` runs UNCONDITIONALLY
    // (independent of `autoCaptureOptions`) so a remotely-disabled SDK stays
    // observable even when device-stats capture is turned off. See
    // `docs/design/remote-kill-switch.md`.

    public init() {}

    /// Whether the timer loop is currently scheduled.
    public var running: Bool {
        lock.lock(); defer { lock.unlock() }
        return timer != nil
    }

    /// Start the periodic collection loop. No-op if already running.
    /// Gauge instruments are built once up-front and reused across ticks.
    ///
    /// SDK-state gauges (`sdk.enabled` / `sdk.sample_rate`) are NOT emitted
    /// here — they live in `SDKStateGaugeCollector`, which runs unconditionally
    /// so a disabled SDK stays observable regardless of `.deviceStats`.
    public func start(meter: MeterSdk, intervalSeconds: UInt64 = 5) {
        lock.lock()
        guard timer == nil else { lock.unlock(); return }

        // Build each gauge once; ignore rebuild requests on subsequent
        // start() calls (no-op branch above).
        memoryUsedGauge = meter.gaugeBuilder(name: "device.memory.used_mb").build()
        memoryAvailGauge = meter.gaugeBuilder(name: "device.memory.available_mb").build()
        batteryGauge = meter.gaugeBuilder(name: "device.battery.level").build()
        thermalGauge = meter.gaugeBuilder(name: "device.thermal.state").ofLongs().build()
        storageGauge = meter.gaugeBuilder(name: "device.storage.available_mb").build()

        #if canImport(UIKit) && !os(watchOS)
        DispatchQueue.main.async {
            UIDevice.current.isBatteryMonitoringEnabled = true
        }
        #endif

        let t = DispatchSource.makeTimerSource(queue: queue)
        let interval = DispatchTimeInterval.seconds(Int(intervalSeconds))
        // Fire the first sample slightly after the start call so gauges
        // populate quickly without racing the caller's frame render.
        t.schedule(deadline: .now() + .milliseconds(500), repeating: interval)
        t.setEventHandler { [weak self] in self?.tick() }
        t.resume()
        timer = t
        lock.unlock()
    }

    /// Stop the periodic collection loop. Already-built gauge instruments
    /// remain registered on the MeterProvider; this just halts new samples.
    public func stop() {
        lock.lock()
        timer?.cancel()
        timer = nil
        lock.unlock()
    }

    // MARK: - Tick

    private func tick() {
        lock.lock()
        var memoryUsed = memoryUsedGauge
        var memoryAvail = memoryAvailGauge
        var battery = batteryGauge
        var thermal = thermalGauge
        var storage = storageGauge
        lock.unlock()

        if let mem = Self.memoryUsedMb() { memoryUsed?.record(value: mem) }
        if let avail = Self.memoryAvailableMb() { memoryAvail?.record(value: avail) }

        #if canImport(UIKit) && !os(watchOS)
        let level = Self.batteryLevel()
        if level >= 0 { battery?.record(value: Double(level)) }
        #endif

        thermal?.record(value: ProcessInfo.processInfo.thermalState.rawValue)

        if let st = Self.storageAvailableMb() { storage?.record(value: st) }

        // Writeback not required — the optional gauge types are reference
        // types under the hood, so .record() mutations don't need to be
        // stored back into self.
        _ = memoryUsed; _ = memoryAvail; _ = battery; _ = thermal; _ = storage
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

    #if canImport(UIKit) && !os(watchOS)
    private static func batteryLevel() -> Float {
        if Thread.isMainThread { return UIDevice.current.batteryLevel }
        return DispatchQueue.main.sync { UIDevice.current.batteryLevel }
    }
    #endif
}
