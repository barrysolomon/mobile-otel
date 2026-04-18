import Testing
@testable import OTelMobileSDK

@Suite("SamplingConfig")
struct SamplingConfigTests {
    @Test("default strategy is traceIdRatio at 10%")
    func defaults() {
        let config = SamplingConfig()
        #expect(config.strategy == .traceIdRatio)
        #expect(config.samplingRate == 0.1)
        #expect(config.highPrioritySamplingRate == 1.0)
    }

    @Test("alwaysOn factory sets 100% sampling")
    func alwaysOnFactory() {
        let config = SamplingConfig.alwaysOn()
        #expect(config.strategy == .alwaysOn)
        #expect(config.samplingRate == 1.0)
    }

    @Test("alwaysOff factory sets 0% sampling")
    func alwaysOffFactory() {
        let config = SamplingConfig.alwaysOff()
        #expect(config.strategy == .alwaysOff)
        #expect(config.samplingRate == 0.0)
    }

    @Test("production factory uses traceIdRatio with custom rate")
    func productionFactory() {
        let config = SamplingConfig.production(rate: 0.25)
        #expect(config.strategy == .traceIdRatio)
        #expect(config.samplingRate == 0.25)
    }

    @Test("dynamic factory keeps high-priority rate separate")
    func dynamicFactory() {
        let config = SamplingConfig.dynamic(normalRate: 0.05, highPriorityRate: 0.8)
        #expect(config.strategy == .dynamic)
        #expect(config.samplingRate == 0.05)
        #expect(config.highPrioritySamplingRate == 0.8)
    }

    @Test("parentBased factory wires parentBasedRoot to traceIdRatio")
    func parentBasedFactory() {
        let config = SamplingConfig.parentBased(rootRate: 0.4)
        #expect(config.strategy == .parentBased)
        #expect(config.parentBasedRoot == .traceIdRatio)
        #expect(config.parentBasedRootSamplingRate == 0.4)
    }

    @Test("rates above 1.0 clamp to 1.0 instead of trapping")
    func clampsAboveOne() {
        let config = SamplingConfig(samplingRate: 1.5, highPrioritySamplingRate: 99.0)
        #expect(config.samplingRate == 1.0)
        #expect(config.highPrioritySamplingRate == 1.0)
    }

    @Test("rates below 0.0 clamp to 0.0")
    func clampsBelowZero() {
        let config = SamplingConfig(samplingRate: -0.5)
        #expect(config.samplingRate == 0.0)
    }
}
