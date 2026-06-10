import Foundation
import OpenTelemetryApi
import OpenTelemetrySdk

/// Emits the SDK-state self-telemetry gauges — `sdk.enabled` and
/// `sdk.sample_rate` — reflecting the live remote kill-switch / global-sampling
/// state held in `RemoteGate`.
///
/// ## Why this is its own collector (not folded into `DeviceStatsCollector`)
///
/// These two gauges are the operator's *only* window into a remotely-disabled
/// fleet: when the kill switch flips a device to `enabled = false`, all spans
/// and logs stop, so the SDK-state gauges are the one telemetry stream
/// deliberately exempt from the gate (see `docs/design/remote-kill-switch.md`).
/// They must therefore be emitted **whenever the SDK is started**, independent
/// of any `autoCaptureOptions` toggle. Previously they were built inside
/// `DeviceStatsCollector.start` and only emitted when `.deviceStats` was
/// enabled — an operator who turned device stats off went blind to kill-switch
/// state. Hosting them here, started unconditionally by `OTelMobile.start`,
/// guarantees a disabled SDK is always observable.
///
/// Mirrors `DeviceStatsCollector`'s GCD-timer design (a `DispatchSourceTimer`
/// on a dedicated utility queue) rather than a `Task` loop, for the same
/// SwiftUI actor-isolation reasons documented there.
public final class SDKStateGaugeCollector: @unchecked Sendable {
    private let lock = NSLock()
    private let queue = DispatchQueue(
        label: "io.dash0.mobile.SDKStateGaugeCollector", qos: .utility
    )
    private var timer: DispatchSourceTimer?
    // Gauge instruments are held as `var` because OTel-Swift's gauge
    // protocol declares `mutating func record`.
    private var sdkEnabledGauge: LongGauge?
    private var sdkSampleRateGauge: DoubleGauge?
    private var remoteGate: RemoteGate?

    public init() {}

    /// Whether the timer loop is currently scheduled.
    public var running: Bool {
        lock.lock(); defer { lock.unlock() }
        return timer != nil
    }

    /// Start the periodic SDK-state gauge loop. No-op if already running.
    /// Emits `sdk.enabled` (1/0) and `sdk.sample_rate` ([0,1]) every tick,
    /// reflecting the live gate state — including while the SDK is remotely
    /// disabled, so a disabled fleet stays observable.
    public func start(meter: MeterSdk, intervalSeconds: UInt64 = 5, remoteGate: RemoteGate) {
        lock.lock()
        guard timer == nil else { lock.unlock(); return }

        self.remoteGate = remoteGate
        sdkEnabledGauge = meter.gaugeBuilder(name: "sdk.enabled").ofLongs().build()
        sdkSampleRateGauge = meter.gaugeBuilder(name: "sdk.sample_rate").build()

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

    /// Stop the periodic loop. Already-built gauge instruments remain
    /// registered on the MeterProvider; this just halts new samples.
    public func stop() {
        lock.lock()
        timer?.cancel()
        timer = nil
        lock.unlock()
    }

    /// Emit one SDK-state sample immediately, off the timer schedule. Used by
    /// tests (and available to callers) to assert the gauges record without
    /// waiting for the first tick. No-op until `start` has built the gauges.
    public func emitOnce() {
        tick()
    }

    private func tick() {
        lock.lock()
        var sdkEnabled = sdkEnabledGauge
        var sdkSampleRate = sdkSampleRateGauge
        let gate = remoteGate
        lock.unlock()

        guard let gate = gate else { return }
        let snapshot = gate.current
        // Unconditional emission — no kill-switch gate on the gauge path — so
        // a disabled SDK is still observable.
        sdkEnabled?.record(value: snapshot.enabled ? 1 : 0)
        sdkSampleRate?.record(value: snapshot.sampleRate)

        // Writeback not required — the optional gauge types are reference
        // types under the hood, so .record() mutations don't need to be
        // stored back into self.
        _ = sdkEnabled; _ = sdkSampleRate
    }
}
