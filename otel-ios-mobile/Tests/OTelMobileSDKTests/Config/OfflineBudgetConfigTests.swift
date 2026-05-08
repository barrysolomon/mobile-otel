import Testing
@testable import OTelMobileSDK

@Suite("OfflineBudgetConfig")
struct OfflineBudgetConfigTests {

    @Test("default config has 10MB budget, oldest-first, enabled")
    func defaultConfig() {
        let config = OfflineBudgetConfig.default
        #expect(config.maxOfflineDiskBytes == 10 * 1024 * 1024)
        #expect(config.evictionStrategy == .oldestFirst)
        #expect(config.enabled == true)
    }

    @Test("disabled config has enabled=false")
    func disabledConfig() {
        let config = OfflineBudgetConfig.disabled
        #expect(config.enabled == false)
    }

    @Test("custom config preserves values")
    func customConfig() {
        let config = OfflineBudgetConfig(
            maxOfflineDiskBytes: 5 * 1024 * 1024,
            evictionStrategy: .lowestSeverityFirst,
            enabled: true
        )
        #expect(config.maxOfflineDiskBytes == 5 * 1024 * 1024)
        #expect(config.evictionStrategy == .lowestSeverityFirst)
    }

    @Test("MobileConfig integrates offline budget")
    func mobileConfigIntegration() {
        let budget = OfflineBudgetConfig(maxOfflineDiskBytes: 2 * 1024 * 1024)
        let config = MobileConfig(
            serviceName: "test",
            endpoint: "http://localhost",
            offlineBudgetConfig: budget
        )
        #expect(config.offlineBudgetConfig.maxOfflineDiskBytes == 2 * 1024 * 1024)
    }

    @Test("MobileConfig defaults to .default budget")
    func mobileConfigDefault() {
        let config = MobileConfig(serviceName: "test", endpoint: "http://localhost")
        #expect(config.offlineBudgetConfig.enabled == true)
        #expect(config.offlineBudgetConfig.maxOfflineDiskBytes == 10 * 1024 * 1024)
    }
}
