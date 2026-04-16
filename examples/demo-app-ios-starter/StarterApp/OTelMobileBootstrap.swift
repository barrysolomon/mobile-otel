import Foundation
import OTelMobileSDK
import OTelMobileCore

/// Loads Dash0 credentials from a bundled `otel-config.json` (or falls back to
/// the `.template`), then boots `OTelMobile` with the production OTLP/HTTP
/// exporter wired to Dash0.
enum OTelMobileBootstrap {
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

    /// Try `otel-config.json` first (user-provided, gitignored), fall back to
    /// `otel-config.json.template` (committed, placeholder values) so the app
    /// still launches in a "not configured" state instead of crashing.
    static func loadConfig() -> DemoConfig? {
        let candidates: [(name: String, ext: String)] = [
            ("otel-config", "json"),
            ("otel-config.json", "template"),
        ]
        for (name, ext) in candidates {
            guard let url = Bundle.main.url(forResource: name, withExtension: ext) else {
                continue
            }
            guard let data = try? Data(contentsOf: url) else {
                continue
            }
            if let decoded = try? JSONDecoder().decode(DemoConfig.self, from: data) {
                return decoded
            }
        }
        return nil
    }

    /// Detect the shipped template values so the app can display a helpful
    /// status instead of firing requests with a placeholder bearer token.
    static func isPlaceholder(_ config: DemoConfig) -> Bool {
        config.authToken.contains("YOUR_")
            || config.endpoint.contains("YOUR-")
            || config.endpoint.contains("YOUR_")
    }

    struct BootstrapResult {
        let mobile: OTelMobile?
        let config: DemoConfig?
        let status: String
    }

    static func start() -> BootstrapResult {
        guard let demo = loadConfig() else {
            let status = "No otel-config.json found — demo will not send events"
            print("WARN \(status)")
            return BootstrapResult(mobile: nil, config: nil, status: status)
        }

        if isPlaceholder(demo) {
            let status = "otel-config.json has placeholder values — fill them in to send real events"
            print("WARN \(status)")
            return BootstrapResult(mobile: nil, config: demo, status: status)
        }

        let config = MobileConfig(
            serviceName: demo.serviceName,
            serviceVersion: demo.serviceVersion,
            endpoint: demo.endpoint,
            authToken: demo.authToken,
            extraHeaders: ["Dash0-Dataset": demo.dataset]
        )

        do {
            let mobile = try OTelMobile.start(config: config)
            let status = "SDK started — endpoint=\(demo.endpoint) dataset=\(demo.dataset)"
            print("OK \(status)")
            return BootstrapResult(mobile: mobile, config: demo, status: status)
        } catch {
            let status = "OTelMobile.start failed: \(error)"
            print("ERR \(status)")
            return BootstrapResult(mobile: nil, config: demo, status: status)
        }
    }
}
