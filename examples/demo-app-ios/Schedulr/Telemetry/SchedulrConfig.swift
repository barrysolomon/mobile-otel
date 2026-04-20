import Foundation
import OTelMobileSDK

/// Resolves the `MobileConfig` for the Schedulr demo. Reads
/// `Schedulr/otel-config.json` from the bundle if present (the file is
/// `.gitignore`d — copy from `otel-config.json.template` and fill in
/// real Dash0 credentials). Falls back to a no-export local config so
/// the app still launches cleanly without credentials.
struct SchedulrConfig {
    let serviceName: String
    let endpoint: String
    let authToken: String?
    let dataset: String?
    let backendUrl: String

    static func load() -> SchedulrConfig {
        let bundle = Bundle.main
        if let url = bundle.url(forResource: "otel-config", withExtension: "json"),
           let data = try? Data(contentsOf: url),
           let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            return SchedulrConfig(
                serviceName: (json["serviceName"] as? String) ?? "otel-ios-schedulr",
                endpoint: (json["endpoint"] as? String) ?? "https://localhost",
                authToken: json["authToken"] as? String,
                dataset: json["dataset"] as? String,
                backendUrl: (json["backendUrl"] as? String) ?? "http://localhost:3001"
            )
        }
        // Local-only fallback. Endpoint is bogus so OTLP exports fail —
        // RetryableExporter logs failures via ExportStatusManager but
        // doesn't crash the host app.
        return SchedulrConfig(
            serviceName: "otel-ios-schedulr",
            endpoint: "https://unconfigured.example.invalid",
            authToken: nil,
            dataset: nil,
            backendUrl: "http://localhost:3001"
        )
    }

    /// Builds the actual SDK config with extra headers for Dash0
    /// dataset routing (matches Android's `Dash0-Dataset` header).
    func toMobileConfig() -> MobileConfig {
        var headers: [String: String] = [:]
        if let dataset = dataset {
            headers["Dash0-Dataset"] = dataset
        }
        return MobileConfig(
            serviceName: serviceName,
            endpoint: endpoint,
            authToken: authToken,
            extraHeaders: headers
        )
    }
}
