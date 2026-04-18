import Foundation
import OTelMobileSDK
import OTelMobileCore

/// Reads credentials from bundled `otel-config.json` (or the committed
/// `.template` fallback) and boots OTelMobile. Mirrors the Starter demo's
/// `OTelMobileBootstrap` so both demos share the same config shape.
enum ShopBootstrap {
    struct DemoConfig: Decodable {
        let serviceName: String
        let serviceVersion: String
        let endpoint: String
        let authToken: String
        let dataset: String

        enum CodingKeys: String, CodingKey {
            case serviceName = "service_name"
            case serviceVersion = "service_version"
            case endpoint
            case authToken = "auth_token"
            case dataset
        }
    }

    struct BootResult {
        let mobile: OTelMobile?
        let config: DemoConfig?
        let status: String
    }

    static func start() -> BootResult {
        guard let demo = loadConfig() else {
            return BootResult(mobile: nil, config: nil,
                              status: "No otel-config.json — demo runs offline")
        }
        if demo.authToken.contains("YOUR_") || demo.endpoint.contains("YOUR-") {
            return BootResult(mobile: nil, config: demo,
                              status: "otel-config has placeholders — fill in Dash0 creds")
        }
        // Under XCUITest, the test runner talks to the app over localhost.
        // Our NetworkInstrumentation's `URLProtocol` swizzle intercepts
        // every `URLSession` request, including XCTest's accessibility
        // bridge, which causes the test snapshot queries to return an
        // empty tree. Detect the test mode (launch arg set by the
        // AstronomyShopUITests target) and drop the network
        // auto-capture so the bridge is untouched. All other
        // instrumentation stays on.
        let underUITest = CommandLine.arguments.contains("-DASH0_UI_TEST")
        let autoCapture: AutoCaptureOptions = underUITest
            ? [.lifecycle, .screen, .errors, .freeze, .vitals, .deviceStats]
            : .all

        let config = MobileConfig(
            serviceName: demo.serviceName,
            serviceVersion: demo.serviceVersion,
            endpoint: demo.endpoint,
            authToken: demo.authToken,
            autoCaptureOptions: autoCapture,
            extraHeaders: ["Dash0-Dataset": demo.dataset]
        )
        do {
            let mobile = try OTelMobile.start(config: config)
            return BootResult(mobile: mobile, config: demo,
                              status: "SDK started → \(demo.dataset) on \(demo.endpoint)")
        } catch {
            return BootResult(mobile: nil, config: demo,
                              status: "SDK start failed: \(error)")
        }
    }

    static func loadConfig() -> DemoConfig? {
        let candidates: [(String, String)] = [
            ("otel-config", "json"),
            ("otel-config.json", "template"),
        ]
        for (name, ext) in candidates {
            guard let url = Bundle.main.url(forResource: name, withExtension: ext),
                  let data = try? Data(contentsOf: url) else { continue }
            if let cfg = try? JSONDecoder().decode(DemoConfig.self, from: data) {
                return cfg
            }
        }
        return nil
    }
}
