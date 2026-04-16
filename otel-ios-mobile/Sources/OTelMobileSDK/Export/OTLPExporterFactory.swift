import Foundation
import OpenTelemetrySdk
import OpenTelemetryProtocolExporterCommon
import OpenTelemetryProtocolExporterHttp

/// Errors produced when building OTLP exporters from `MobileConfig` values.
public enum OTLPExporterFactoryError: Error, Equatable {
    /// The `endpoint` string failed to parse into a `URL`.
    case invalidEndpoint(String)
}

/// Factory that builds OTel-Swift OTLP/HTTP exporters from our config.
///
/// This is the single point of contact with the OTel-Swift exporter API so
/// that future work (gRPC exporter, mTLS, custom HTTP clients) only needs to
/// extend one file.
public enum OTLPExporterFactory {
    /// Build an OTLP/HTTP log exporter.
    ///
    /// The exporter follows standard OTLP/HTTP convention: if the caller
    /// passes a base URL like `https://ingress.dash0.com:4318`, we append
    /// `/v1/logs`. If they already included a path ending in `/v1/logs`, we
    /// leave it alone.
    ///
    /// When `authToken` is non-nil, it is sent as
    /// `Authorization: Bearer <token>` — Dash0's required scheme.
    ///
    /// - Parameters:
    ///   - endpoint: OTLP/HTTP collector endpoint (base URL or full `/v1/logs`).
    ///   - authToken: Optional bearer token. Empty strings are treated as nil.
    ///   - extraHeaders: Additional headers merged onto the request.
    public static func makeHttpLogExporter(
        endpoint: String,
        authToken: String?,
        extraHeaders: [String: String] = [:]
    ) throws -> LogRecordExporter {
        let url = try buildLogsEndpointURL(from: endpoint)

        var headerPairs: [(String, String)] = []
        if let token = authToken, !token.isEmpty {
            headerPairs.append(("Authorization", "Bearer \(token)"))
        }
        for (key, value) in extraHeaders {
            headerPairs.append((key, value))
        }

        // Use protobuf on the wire; OTel-Swift's `exportAsJson` default is
        // `true`, but the server cost savings from protobuf matter for mobile.
        let otlpConfig = OtlpConfiguration(
            timeout: OtlpConfiguration.DefaultTimeoutInterval,
            compression: .gzip,
            headers: headerPairs.isEmpty ? nil : headerPairs,
            exportAsJson: false
        )

        // We pass `envVarHeaders: nil` so that OTLP env-var headers don't
        // override the caller-supplied auth token. OTel-Swift's default is
        // `EnvVarHeaders.attributes`, which in a mobile process is usually
        // nil but we don't want that coupling.
        return OtlpHttpLogExporter(
            endpoint: url,
            config: otlpConfig,
            envVarHeaders: nil
        )
    }

    /// Derive the full logs-ingest URL from a user-supplied endpoint string.
    ///
    /// Visible-internal for tests; callers should use `makeHttpLogExporter`.
    static func buildLogsEndpointURL(from endpoint: String) throws -> URL {
        let trimmed = endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let base = URL(string: trimmed) else {
            throw OTLPExporterFactoryError.invalidEndpoint(endpoint)
        }

        // If caller already supplied the full /v1/logs URL, use it as-is.
        if base.path.hasSuffix("/v1/logs") || base.path.hasSuffix("/v1/logs/") {
            return base
        }

        // Append /v1/logs, preserving scheme/host/port/query.
        guard var comps = URLComponents(url: base, resolvingAgainstBaseURL: false) else {
            throw OTLPExporterFactoryError.invalidEndpoint(endpoint)
        }
        let basePath = comps.path.hasSuffix("/")
            ? String(comps.path.dropLast())
            : comps.path
        comps.path = basePath + "/v1/logs"

        guard let finalURL = comps.url else {
            throw OTLPExporterFactoryError.invalidEndpoint(endpoint)
        }
        return finalURL
    }
}
