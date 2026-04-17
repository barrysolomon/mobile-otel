import Foundation

/// Rule-based risk prediction. Computes four risk scores (`0.0..1.0`) from
/// a current `DeviceHealthSnapshot` and a bounded history of recent
/// snapshots. No ML, no statistics — same additive heuristics that
/// `OnDevicePredictor.kt` uses on Android, tuned for iOS's 4-level thermal
/// scale and process-scoped memory.
public struct Prediction: Sendable, Equatable {
    public let crashRisk: Double
    public let networkLossRisk: Double
    public let performanceDegradationRisk: Double
    public let batteryDrainRisk: Double
    /// `0.5..1.0` — ramps to 1.0 as snapshot history saturates.
    public let confidence: Double

    public func hasHighRisk(threshold: Double = 0.7) -> Bool {
        crashRisk >= threshold
            || networkLossRisk >= threshold
            || performanceDegradationRisk >= threshold
            || batteryDrainRisk >= threshold
    }

    public func maxRisk() -> Double {
        max(crashRisk, max(networkLossRisk, max(performanceDegradationRisk, batteryDrainRisk)))
    }

    public static let zero = Prediction(
        crashRisk: 0, networkLossRisk: 0,
        performanceDegradationRisk: 0, batteryDrainRisk: 0,
        confidence: 0.5
    )
}

/// Observed network-path event, used to compute the `networkLossRisk`
/// signal. The predictor maintains a bounded deque; the `ContextSnapshotProvider`
/// (or any callsite with a `NWPathMonitor`) can push events in.
public struct NetworkEvent: Sendable {
    public enum Kind: Sendable {
        case available, lost
    }
    public let kind: Kind
    public let at: Date
    public init(kind: Kind, at: Date = Date()) {
        self.kind = kind
        self.at = at
    }
}

public final class OnDevicePredictor: @unchecked Sendable {
    public static let shared = OnDevicePredictor()

    private let lock = NSLock()
    private var healthHistory: [DeviceHealthSnapshot] = []
    private var networkHistory: [NetworkEvent] = []
    private let maxHistory = 20

    private init() {}

    public func recordNetworkEvent(_ event: NetworkEvent) {
        lock.lock(); defer { lock.unlock() }
        networkHistory.append(event)
        if networkHistory.count > maxHistory {
            networkHistory.removeFirst(networkHistory.count - maxHistory)
        }
    }

    /// Feed a fresh snapshot into the predictor's history. The caller is
    /// expected to be `PredictiveExportPolicy` or a unit test — regular app
    /// code should drive this through the policy's scheduler.
    public func record(snapshot: DeviceHealthSnapshot) {
        lock.lock(); defer { lock.unlock() }
        healthHistory.append(snapshot)
        if healthHistory.count > maxHistory {
            healthHistory.removeFirst(healthHistory.count - maxHistory)
        }
    }

    /// Compute a prediction from the current snapshot plus accumulated
    /// history. `snapshot` is typically the value just produced by
    /// `DeviceHealthMonitor.updateSnapshot()`.
    public func predict(using snapshot: DeviceHealthSnapshot) -> Prediction {
        lock.lock()
        let health = healthHistory
        let network = networkHistory
        lock.unlock()

        let crash = crashRisk(current: snapshot, history: health)
        let netLoss = networkLossRisk(history: network)
        let perf = performanceDegradationRisk(current: snapshot)
        let battery = batteryDrainRisk(current: snapshot)
        // Android: 0.5 + history/20 * 0.5. Same shape on iOS.
        let confidence = min(1.0, 0.5 + Double(health.count) / Double(maxHistory) * 0.5)

        return Prediction(
            crashRisk: crash,
            networkLossRisk: netLoss,
            performanceDegradationRisk: perf,
            batteryDrainRisk: battery,
            confidence: confidence
        )
    }

    // MARK: - Risk rules

    private func crashRisk(current: DeviceHealthSnapshot, history: [DeviceHealthSnapshot]) -> Double {
        var risk = 0.0
        switch current.memoryPressure {
        case .normal: break
        case .moderate: risk += 0.1
        case .high: risk += 0.3
        case .critical: risk += 0.6
        }
        if current.availableMemoryMb < 50 { risk += 0.4 }

        // Trend signal: memory shrinking quickly over recent history.
        if history.count >= 3 {
            let tail = Array(history.suffix(3))
            let first = tail.first!
            let last = tail.last!
            let deltaMb = Double(last.availableMemoryMb - first.availableMemoryMb)
            let elapsedMinutes = last.capturedAt.timeIntervalSince(first.capturedAt) / 60.0
            if elapsedMinutes > 0 {
                let mbPerMin = deltaMb / elapsedMinutes
                if mbPerMin < -10 { risk += 0.3 }
            }
        }
        return clamp(risk)
    }

    private func networkLossRisk(history: [NetworkEvent]) -> Double {
        let recent = history.suffix(5)
        let lostCount = recent.filter { $0.kind == .lost }.count
        var risk = 0.0
        if lostCount >= 2 { risk += 0.5 }
        // If the very latest event was a .lost and we have no .available
        // after it, treat as near-certain.
        if let last = history.last, last.kind == .lost {
            risk = max(risk, 1.0)
        }
        return clamp(risk)
    }

    private func performanceDegradationRisk(current: DeviceHealthSnapshot) -> Double {
        var risk = 0.0
        switch current.thermalState {
        case .normal, .light: break
        case .moderate: risk += 0.3
        case .severe, .critical: risk += 0.6
        }
        switch current.memoryPressure {
        case .normal, .moderate: break
        case .high, .critical: risk += 0.2
        }
        return clamp(risk)
    }

    private func batteryDrainRisk(current: DeviceHealthSnapshot) -> Double {
        guard current.batteryLevelPercent >= 0 else { return 0 }
        if current.batteryCharging { return 0 }
        var risk = 0.0
        switch current.batteryLevelPercent {
        case ..<10: risk += 0.6
        case ..<20: risk += 0.3
        case ..<30: risk += 0.1
        default: break
        }
        if let rate = current.batteryDrainPercentPerMin, rate > 1.0 {
            risk += 0.4
        }
        return clamp(risk)
    }

    private func clamp(_ value: Double) -> Double {
        min(1.0, max(0.0, value))
    }

    /// Internal reset used only by test-support helpers. Wipes both
    /// history deques so tests see a predictor with empty state.
    func internalReset() {
        lock.lock(); defer { lock.unlock() }
        healthHistory.removeAll()
        networkHistory.removeAll()
    }
}
