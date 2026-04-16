import Testing
@testable import OTelMobileSDK
import OTelMobileCore

@Suite("MobileConfig")
struct MobileConfigTests {
    @Test("Default buffer config values")
    func defaultBufferConfig() {
        let config = BufferConfig.default
        #expect(config.ramEvents == 5000)
        #expect(config.diskMb == 50)
        #expect(config.retentionHours == 24)
    }

    @Test("Privacy presets")
    func privacyPresets() {
        #expect(PrivacyConfig.production.scrubPii)
        #expect(!PrivacyConfig.production.captureLocation)
        #expect(PrivacyConfig.production.redactTextOnScreenshots)

        #expect(!PrivacyConfig.debug.scrubPii)
        #expect(PrivacyConfig.debug.captureLocation)

        #expect(!PrivacyConfig.minimal.scrubPii)
        #expect(!PrivacyConfig.minimal.bucketCoordinates)
    }

    @Test("AutoCaptureOptions.all contains known flags")
    func autoCaptureAll() {
        let all = AutoCaptureOptions.all
        #expect(all.contains(.tap))
        #expect(all.contains(.network))
        #expect(all.contains(.errors))
        #expect(all.contains(.freeze))
    }

    @Test("AutoCaptureOptions custom combinations")
    func autoCaptureCustom() {
        let custom: AutoCaptureOptions = [.tap, .network]
        #expect(custom.contains(.tap))
        #expect(custom.contains(.network))
        #expect(!custom.contains(.scroll))
    }

    @Test("MobileConfig default field values")
    func mobileConfigDefaults() {
        let config = MobileConfig(serviceName: "test-app", endpoint: "https://collector:4317")
        #expect(config.exportMode == .conditional)
        #expect(config.pollingIntervalSeconds == 300)
        #expect(config.authToken == nil)
    }
}
