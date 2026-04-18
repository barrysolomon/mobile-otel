import Testing
@testable import OTelMobileSDK
import OpenTelemetrySdk

@Suite("SamplerFactory")
struct SamplerFactoryTests {
    @Test("alwaysOn config returns OTel-Swift's AlwaysOnSampler")
    func alwaysOn() {
        let sampler = SamplerFactory.createSampler(.alwaysOn())
        #expect(sampler.description.contains("AlwaysOn"))
    }

    @Test("alwaysOff config returns OTel-Swift's AlwaysOffSampler")
    func alwaysOff() {
        let sampler = SamplerFactory.createSampler(.alwaysOff())
        #expect(sampler.description.contains("AlwaysOff"))
    }

    @Test("traceIdRatio config returns a TraceIdRatioBased sampler")
    func traceIdRatio() {
        let sampler = SamplerFactory.createSampler(.production(rate: 0.25))
        // OTel-Swift's TraceIdRatioBased description starts with the
        // class name. We don't pin the exact format because that's
        // upstream-controlled.
        #expect(sampler.description.contains("TraceIdRatio"))
    }

    @Test("dynamic config returns a DynamicSampler")
    func dynamic() {
        let sampler = SamplerFactory.createSampler(.dynamic(normalRate: 0.05, highPriorityRate: 0.95))
        guard let dyn = sampler as? DynamicSampler else {
            Issue.record("expected DynamicSampler, got \(type(of: sampler))")
            return
        }
        #expect(dyn.baselineSamplingRate == 0.05)
        #expect(dyn.highPrioritySamplingRate == 0.95)
    }

    @Test("parentBased config wraps a traceIdRatio root")
    func parentBased() {
        let sampler = SamplerFactory.createSampler(.parentBased(rootRate: 0.3))
        // ParentBased's description includes "ParentBased{root:..."
        #expect(sampler.description.contains("ParentBased"))
    }

    @Test("createDynamicSampler returns a DynamicSampler with the supplied rates")
    func createDynamicShortcut() {
        let dyn = SamplerFactory.createDynamicSampler(baselineRate: 0.2, highPriorityRate: 0.8)
        #expect(dyn.baselineSamplingRate == 0.2)
        #expect(dyn.highPrioritySamplingRate == 0.8)
    }
}
