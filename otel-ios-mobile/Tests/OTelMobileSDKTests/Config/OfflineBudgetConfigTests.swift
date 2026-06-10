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

    @Test("zero maxOfflineDiskBytes is clamped to the 64KB floor, not a crash")
    func zeroBudgetClampsToFloor() {
        // The safety fix replaced `precondition(maxOfflineDiskBytes > 0)`
        // with a clamp to a 64KB floor so a misconfigured value can't crash
        // the host app or silently disable persistence. Constructing with 0
        // must NOT trap and must yield the floor.
        let config = OfflineBudgetConfig(maxOfflineDiskBytes: 0)
        #expect(config.maxOfflineDiskBytes == 64 * 1024)
    }

    @Test("negative maxOfflineDiskBytes is clamped to the 64KB floor, not a crash")
    func negativeBudgetClampsToFloor() {
        // Int is signed, so a negative dev-supplied value is possible.
        // It must clamp to the floor rather than trapping or producing a
        // nonsensical negative budget.
        let config = OfflineBudgetConfig(maxOfflineDiskBytes: -1)
        #expect(config.maxOfflineDiskBytes == 64 * 1024)

        let veryNegative = OfflineBudgetConfig(maxOfflineDiskBytes: Int.min)
        #expect(veryNegative.maxOfflineDiskBytes == 64 * 1024)
    }

    @Test("a value above the floor is preserved unchanged")
    func aboveFloorPreserved() {
        // Guard against an over-eager clamp that would also rewrite valid
        // small-but-positive budgets. 100KB > 64KB floor, so it stands.
        let config = OfflineBudgetConfig(maxOfflineDiskBytes: 100 * 1024)
        #expect(config.maxOfflineDiskBytes == 100 * 1024)
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
