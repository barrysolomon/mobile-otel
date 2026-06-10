import Foundation
import OpenTelemetryApi
import OpenTelemetrySdk

/// Sampler whose rate can be adjusted at runtime. Direct port of
/// Android's `DynamicSampler` with the same observable behaviour:
///
/// - Baseline rate applied to every span by default.
/// - High-priority rate applied to `page.*` and `app.startup` spans
///   (detected by name, OTel-native — no required attribute). This
///   keeps the trace waterfall intact even at low baseline rates: if a
///   page span were dropped, every tap / scroll / API call on that
///   screen would lose its parent context.
/// - `setSamplingRate(_:durationMinutes:)` lets a policy evaluator
///   bump the rate temporarily after an error / threshold crossing,
///   reverting to baseline when the duration elapses.
/// - Sampling decision keyed on `traceId.idLo` so distributed traces
///   stay whole — same trace id always gets the same decision. iOS gets
///   `idLo` directly from the `TraceId` struct, no hex-string parse.
///
/// Thread safety: a single `NSLock` guards the mutable state. The
/// Android version uses a `ReentrantReadWriteLock` for the throughput
/// win on read-heavy workloads; on iOS the call site (BatchSpanProcessor
/// worker) is single-threaded enough that the optimisation isn't worth
/// the extra surface area.
public final class DynamicSampler: Sampler, @unchecked Sendable {
    public let baselineSamplingRate: Double
    public let highPrioritySamplingRate: Double

    private let lock = NSLock()
    private var _currentSamplingRate: Double
    private var scheduledRevertTime: Date?

    public init(
        baselineSamplingRate: Double = 0.1,
        highPrioritySamplingRate: Double = 1.0
    ) {
        let baseline = DynamicSampler.clamp(baselineSamplingRate)
        let highPriority = DynamicSampler.clamp(highPrioritySamplingRate)
        self.baselineSamplingRate = baseline
        self.highPrioritySamplingRate = highPriority
        self._currentSamplingRate = baseline
    }

    private static func clamp(_ value: Double) -> Double {
        return min(1.0, max(0.0, value))
    }

    // MARK: - Sampler

    public func shouldSample(
        parentContext: SpanContext?,
        traceId: TraceId,
        name: String,
        kind: SpanKind,
        attributes: [String: AttributeValue],
        parentLinks: [SpanData.Link]
    ) -> Decision {
        // Fold any pending scheduled-revert into the live rate before
        // evaluating. We do this lazily on the next sampling call to
        // avoid spinning a timer thread for every scheduled bump.
        checkScheduledRevert()

        let isPageSpan = name.hasPrefix("page.") || name == "app.startup"
        let rate = isPageSpan
            ? highPrioritySamplingRate
            : currentSamplingRate

        let sampled = shouldSampleTraceId(traceId, rate: rate)
        var samplingAttributes: [String: AttributeValue] = [
            "sampling.rate": .double(rate),
            "sampling.strategy": .string("dynamic"),
        ]
        if isPageSpan {
            samplingAttributes["sampling.page_span"] = .bool(true)
        }
        return DynamicDecision(isSampled: sampled, attributes: samplingAttributes)
    }

    public var description: String {
        "DynamicSampler{baseline=\(baselineSamplingRate), current=\(currentSamplingRate), highPriority=\(highPrioritySamplingRate)}"
    }

    // MARK: - Runtime adjustment

    /// Sets the current sampling rate. When `durationMinutes` is
    /// non-nil, the rate reverts to baseline after that many minutes.
    public func setSamplingRate(_ rate: Double, durationMinutes: Int? = nil) {
        let clamped = DynamicSampler.clamp(rate)
        lock.lock(); defer { lock.unlock() }
        _currentSamplingRate = clamped
        if let minutes = durationMinutes, minutes > 0 {
            scheduledRevertTime = Date().addingTimeInterval(TimeInterval(minutes) * 60.0)
        } else {
            scheduledRevertTime = nil
        }
    }

    /// Resets the current rate to the baseline immediately and clears
    /// any pending scheduled revert.
    public func resetToBaseline() {
        lock.lock(); defer { lock.unlock() }
        _currentSamplingRate = baselineSamplingRate
        scheduledRevertTime = nil
    }

    public var currentSamplingRate: Double {
        lock.lock(); defer { lock.unlock() }
        return _currentSamplingRate
    }

    // MARK: - Private

    /// If a scheduled revert is set and overdue, snap back to baseline.
    /// Called from `shouldSample` so we never spin a separate timer.
    private func checkScheduledRevert() {
        lock.lock()
        let revertTime = scheduledRevertTime
        let baseline = baselineSamplingRate
        lock.unlock()
        guard let revertTime = revertTime, Date() >= revertTime else { return }
        lock.lock(); defer { lock.unlock() }
        // Re-check under the lock — another thread may have revoked
        // the schedule between our peek and the bump.
        if scheduledRevertTime == revertTime {
            _currentSamplingRate = baseline
            scheduledRevertTime = nil
        }
    }

    /// Trace-id-keyed sampling. Keys on the LOWER 8 bytes (low 64 bits) of
    /// the trace id, converts to an unsigned `[0, 1)` ratio, and samples if
    /// `ratio < rate`. iOS gets the low 64 bits directly as `TraceId.idLo`
    /// — no hex parse needed. This is the trailing-8-bytes key mandated by
    /// the OTel `TraceIdRatioBasedSampler`, and Android's
    /// `DynamicSampler.shouldSampleTraceId` keys on the same trailing bytes
    /// (`traceId.substring(16, 32)`), so the two platforms produce identical
    /// keep/drop decisions for the same `(traceId, rate)`.
    private func shouldSampleTraceId(_ traceId: TraceId, rate: Double) -> Bool {
        if rate >= 1.0 { return true }
        if rate <= 0.0 { return false }
        let ratio = Double(traceId.idLo) / Double(UInt64.max)
        return ratio < rate
    }

    // MARK: - Convenience constructors (mirror Android companion object)

    public static func alwaysOn() -> DynamicSampler {
        DynamicSampler(baselineSamplingRate: 1.0)
    }

    public static func alwaysOff() -> DynamicSampler {
        DynamicSampler(baselineSamplingRate: 0.0)
    }

    public static func production(rate: Double = 0.1) -> DynamicSampler {
        DynamicSampler(baselineSamplingRate: rate)
    }
}

/// Concrete `Decision` returned by `DynamicSampler`. Lives at file
/// scope rather than as a nested type because OTel-Swift's `Decision`
/// is a protocol and conformance must be public if we want callers to
/// pattern-match on the concrete type in tests.
private struct DynamicDecision: Decision {
    let isSampled: Bool
    let attributes: [String: AttributeValue]
}
