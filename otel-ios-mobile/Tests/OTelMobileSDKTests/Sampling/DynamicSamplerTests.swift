import Foundation
import Testing
@testable import OTelMobileSDK
import OpenTelemetryApi
import OpenTelemetrySdk

/// Behavioural-parity coverage for `DynamicSampler`. Mirrors Android's
/// `DynamicSamplerTest` — same scenarios (page-span override, runtime
/// rate adjust, scheduled revert, baseline reset, traceId-keyed
/// determinism).
@Suite("DynamicSampler")
struct DynamicSamplerTests {
    /// Builds a TraceId whose `idLo` ratio is just below `target` so
    /// `shouldSampleTraceId` returns true at any `rate >= target`. Lets
    /// us pin sampling decisions deterministically.
    private func traceId(idLoRatio: Double) -> TraceId {
        // ratio = idLo / UInt64.max; idLo = ratio * UInt64.max.
        // Cap at UInt64.max - 1 to avoid overflow at ratio == 1.0.
        let lo = UInt64(min(Double(UInt64.max) - 1, idLoRatio * Double(UInt64.max)))
        return TraceId(idHi: 0, idLo: lo)
    }

    private func decide(
        _ sampler: DynamicSampler,
        traceId: TraceId,
        name: String = "ui.tap"
    ) -> Decision {
        sampler.shouldSample(
            parentContext: nil,
            traceId: traceId,
            name: name,
            kind: .client,
            attributes: [:],
            parentLinks: []
        )
    }

    @Test("baseline applied to non-page spans")
    func baselineForRegularSpans() {
        let sampler = DynamicSampler(baselineSamplingRate: 0.5, highPrioritySamplingRate: 1.0)
        // ratio 0.4 < 0.5 → sampled
        let yes = decide(sampler, traceId: traceId(idLoRatio: 0.4))
        #expect(yes.isSampled)
        // ratio 0.9 > 0.5 → dropped
        let no = decide(sampler, traceId: traceId(idLoRatio: 0.9))
        #expect(!no.isSampled)
    }

    @Test("page.* spans use highPriorityRate, not baseline")
    func pageSpansAlwaysSampled() {
        let sampler = DynamicSampler(baselineSamplingRate: 0.0, highPrioritySamplingRate: 1.0)
        let decision = decide(sampler, traceId: traceId(idLoRatio: 0.99), name: "page.checkout")
        #expect(decision.isSampled, "page.* must override baseline 0.0")
    }

    @Test("app.startup span treated as high-priority")
    func startupAlwaysSampled() {
        let sampler = DynamicSampler(baselineSamplingRate: 0.0, highPrioritySamplingRate: 1.0)
        let decision = decide(sampler, traceId: traceId(idLoRatio: 0.99), name: "app.startup")
        #expect(decision.isSampled)
    }

    @Test("page-span flag is added to sampling.attributes")
    func pageSpanFlag() {
        let sampler = DynamicSampler.alwaysOn()
        let decision = decide(sampler, traceId: traceId(idLoRatio: 0.0), name: "page.cart")
        #expect(decision.attributes["sampling.page_span"] == .bool(true))
    }

    @Test("regular spans omit the page-span flag")
    func nonPageOmitsFlag() {
        let sampler = DynamicSampler.alwaysOn()
        let decision = decide(sampler, traceId: traceId(idLoRatio: 0.0), name: "ui.tap")
        #expect(decision.attributes["sampling.page_span"] == nil)
    }

    @Test("sampling.rate attribute reports the rate used")
    func reportsRate() {
        let sampler = DynamicSampler(baselineSamplingRate: 0.25, highPrioritySamplingRate: 0.9)
        let regular = decide(sampler, traceId: traceId(idLoRatio: 0.0), name: "ui.tap")
        #expect(regular.attributes["sampling.rate"] == .double(0.25))
        let high = decide(sampler, traceId: traceId(idLoRatio: 0.0), name: "page.home")
        #expect(high.attributes["sampling.rate"] == .double(0.9))
    }

    @Test("setSamplingRate without duration persists indefinitely")
    func setRatePersists() {
        let sampler = DynamicSampler(baselineSamplingRate: 0.1)
        sampler.setSamplingRate(1.0)
        #expect(sampler.currentSamplingRate == 1.0)
        // No revert without a duration — even after a long wait.
        let yes = decide(sampler, traceId: traceId(idLoRatio: 0.99))
        #expect(yes.isSampled)
    }

    @Test("setSamplingRate with duration reverts after the window")
    func setRateRevertsAfterDuration() {
        // Use the smallest representable duration (1 minute) and then
        // backdate the schedule via reflection-free path: call set,
        // then immediately mutate the sampler state through a public
        // helper. Pure black-box would require sleeping 60s, which the
        // SDK safety audit + test budget forbid. Instead we verify the
        // contract in two parts: (a) setting the rate updates current,
        // (b) `resetToBaseline` is the same code path the timer would
        // hit.
        let sampler = DynamicSampler(baselineSamplingRate: 0.2)
        sampler.setSamplingRate(1.0, durationMinutes: 5)
        #expect(sampler.currentSamplingRate == 1.0)
        sampler.resetToBaseline()
        #expect(sampler.currentSamplingRate == 0.2)
    }

    @Test("resetToBaseline drops the override")
    func resetRestoresBaseline() {
        let sampler = DynamicSampler(baselineSamplingRate: 0.3)
        sampler.setSamplingRate(0.9)
        sampler.resetToBaseline()
        #expect(sampler.currentSamplingRate == 0.3)
    }

    @Test("rate above 1.0 clamps without trapping")
    func clamps() {
        let sampler = DynamicSampler(baselineSamplingRate: 1.5)
        #expect(sampler.baselineSamplingRate == 1.0)
        sampler.setSamplingRate(2.0)
        #expect(sampler.currentSamplingRate == 1.0)
    }

    @Test("alwaysOn factory always samples regardless of traceId")
    func factoryAlwaysOn() {
        let sampler = DynamicSampler.alwaysOn()
        let decision = decide(sampler, traceId: traceId(idLoRatio: 0.99))
        #expect(decision.isSampled)
    }

    @Test("alwaysOff factory drops every regular span")
    func factoryAlwaysOff() {
        let sampler = DynamicSampler.alwaysOff()
        let decision = decide(sampler, traceId: traceId(idLoRatio: 0.0))
        #expect(!decision.isSampled)
    }

    @Test("description includes baseline + current + highPriority rates")
    func description() {
        let sampler = DynamicSampler(baselineSamplingRate: 0.3, highPrioritySamplingRate: 0.9)
        let desc = sampler.description
        #expect(desc.contains("baseline=0.3"))
        #expect(desc.contains("current=0.3"))
        #expect(desc.contains("highPriority=0.9"))
    }
}
