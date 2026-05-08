import Foundation
import OpenTelemetryApi

/// Predictive export layer. On a cadence (default 30 s), this policy:
/// 1. updates `DeviceHealthMonitor`
/// 2. feeds the fresh snapshot into `OnDevicePredictor`
/// 3. emits a `prediction.cycle` DEBUG log with the four risk scores
/// 4. if any risk exceeds `highRiskThreshold`, triggers
///    `processor.flushWindow(...)` and emits a `prediction.high_risk_alert`
///    WARN log.
///
/// Android parity: mirrors `io.opentelemetry.android.mobile.predictive.PredictiveExportPolicy`.
/// The Android impl has two modes (own scheduler or "piggyback on
/// processor heartbeat"); iOS ships only the own-scheduler mode today —
/// iOS's processor doesn't have a heartbeat loop to hook into. `start()`
/// is idempotent; multiple calls are coalesced.
public final class PredictiveExportPolicy: @unchecked Sendable {
    public struct Config: Sendable {
        /// How often to run a prediction cycle. Default 30 s.
        public var intervalSeconds: UInt64
        /// Risk score at or above which the SDK takes action.
        public var highRiskThreshold: Double
        /// On a high-crash-risk, flush the last N minutes of buffer.
        public var crashRiskFlushWindowMinutes: UInt64
        /// On a high-network-loss-risk, flush the last N minutes of buffer
        /// (tighter than the crash path — loss is imminent, not happened).
        public var networkRiskFlushWindowMinutes: UInt64

        /// When true, suppress the networkLossRisk → flushWindow action.
        /// Set when OfflinePolicy is handling offline scenarios (e.g. errorOnly, dropAll).
        public var suppressNetworkLossFlush: Bool

        public static let `default` = Config(
            intervalSeconds: 30,
            highRiskThreshold: 0.7,
            crashRiskFlushWindowMinutes: 5,
            networkRiskFlushWindowMinutes: 2,
            suppressNetworkLossFlush: false
        )

        public init(
            intervalSeconds: UInt64 = 30,
            highRiskThreshold: Double = 0.7,
            crashRiskFlushWindowMinutes: UInt64 = 5,
            networkRiskFlushWindowMinutes: UInt64 = 2,
            suppressNetworkLossFlush: Bool = false
        ) {
            self.intervalSeconds = intervalSeconds
            self.highRiskThreshold = highRiskThreshold
            self.crashRiskFlushWindowMinutes = crashRiskFlushWindowMinutes
            self.networkRiskFlushWindowMinutes = networkRiskFlushWindowMinutes
            self.suppressNetworkLossFlush = suppressNetworkLossFlush
        }
    }

    public typealias FlushWindowClosure = @Sendable (UInt64) -> Void

    private let config: Config
    private let logger: Logger
    private let flushWindow: FlushWindowClosure
    private let monitor: DeviceHealthMonitor
    private let predictor: OnDevicePredictor
    private let queue = DispatchQueue(
        label: "io.dash0.mobile.PredictiveExportPolicy", qos: .utility
    )
    private var timer: DispatchSourceTimer?
    private let stateLock = NSLock()
    private var latest: Prediction?

    public init(
        config: Config = .default,
        logger: Logger,
        monitor: DeviceHealthMonitor = .shared,
        predictor: OnDevicePredictor = .shared,
        flushWindow: @escaping FlushWindowClosure
    ) {
        self.config = config
        self.logger = logger
        self.monitor = monitor
        self.predictor = predictor
        self.flushWindow = flushWindow
    }

    public func start() {
        queue.async { [weak self] in
            guard let self = self, self.timer == nil else { return }
            let t = DispatchSource.makeTimerSource(queue: self.queue)
            let interval = DispatchTimeInterval.seconds(Int(self.config.intervalSeconds))
            t.schedule(deadline: .now() + interval, repeating: interval)
            t.setEventHandler { [weak self] in self?.tick() }
            t.resume()
            self.timer = t
        }
    }

    public func stop() {
        queue.async { [weak self] in
            self?.timer?.cancel()
            self?.timer = nil
        }
    }

    public func currentPrediction() -> Prediction? {
        stateLock.lock(); defer { stateLock.unlock() }
        return latest
    }

    public func isNetworkLossImminent() -> Bool {
        (currentPrediction()?.networkLossRisk ?? 0) >= config.highRiskThreshold
    }

    public func isCrashRiskHigh() -> Bool {
        (currentPrediction()?.crashRisk ?? 0) >= config.highRiskThreshold
    }

    // MARK: - Cycle

    /// Test-seam: runs the prediction-action logic with an externally provided
    /// prediction, bypassing the predictor + monitor. Enables unit tests to
    /// verify gating without touching the shared singleton.
    internal func runCycle(with prediction: Prediction) {
        applyPrediction(prediction)
    }

    private func tick() {
        let snapshot = monitor.updateSnapshot()
        predictor.record(snapshot: snapshot)
        let prediction = predictor.predict(using: snapshot)
        applyPrediction(prediction)
    }

    private func applyPrediction(_ prediction: Prediction) {
        stateLock.lock()
        latest = prediction
        stateLock.unlock()

        let baseAttrs: [String: AttributeValue] = [
            "event.name": .string("prediction.cycle"),
            "prediction.crash_risk": .double(prediction.crashRisk),
            "prediction.network_loss_risk": .double(prediction.networkLossRisk),
            "prediction.perf_degradation_risk": .double(prediction.performanceDegradationRisk),
            "prediction.battery_drain_risk": .double(prediction.batteryDrainRisk),
            "prediction.confidence": .double(prediction.confidence),
            "prediction.max_risk": .double(prediction.maxRisk()),
        ]
        logger.logRecordBuilder()
            .setBody(.string("prediction.cycle"))
            .setSeverity(.debug)
            .setAttributes(baseAttrs)
            .emit()

        guard prediction.hasHighRisk(threshold: config.highRiskThreshold) else { return }

        var flushMinutes: UInt64 = 0
        if prediction.crashRisk >= config.highRiskThreshold {
            flushMinutes = max(flushMinutes, config.crashRiskFlushWindowMinutes)
        }
        if prediction.networkLossRisk >= config.highRiskThreshold,
           !config.suppressNetworkLossFlush {
            flushMinutes = max(flushMinutes, config.networkRiskFlushWindowMinutes)
        }

        var alertAttrs = baseAttrs
        alertAttrs["event.name"] = .string("prediction.high_risk_alert")
        alertAttrs["prediction.flush_triggered"] = .bool(flushMinutes > 0)
        alertAttrs["prediction.flush_window_minutes"] = .int(Int(flushMinutes))

        logger.logRecordBuilder()
            .setBody(.string("prediction.high_risk_alert"))
            .setSeverity(.warn)
            .setAttributes(alertAttrs)
            .emit()

        if flushMinutes > 0 {
            flushWindow(flushMinutes)
        }
    }
}
