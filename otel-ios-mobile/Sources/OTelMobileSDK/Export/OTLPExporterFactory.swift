import Foundation
import OpenTelemetrySdk
import OpenTelemetryProtocolExporterCommon
import OpenTelemetryProtocolExporterHttp
#if canImport(GRPC)
import GRPC
import NIO
import OpenTelemetryProtocolExporterGrpc
#endif

/// Errors produced when building OTLP exporters from `MobileConfig` values.
public enum OTLPExporterFactoryError: Error, Equatable {
    /// The `endpoint` string failed to parse into a `URL`.
    case invalidEndpoint(String)
}

#if canImport(GRPC)
/// Wraps an OTLP/gRPC exporter together with the `EventLoopGroup` that owns
/// its channel. Keep the bundle alive for the lifetime of the exporter;
/// call `shutdown()` on app teardown.
public final class GrpcExporterBundle<ExporterT>: @unchecked Sendable {
    public let exporter: ExporterT
    private let group: EventLoopGroup
    private var shutdownCalled = false
    private let lock = NSLock()

    init(exporter: ExporterT, group: EventLoopGroup) {
        self.exporter = exporter
        self.group = group
    }

    public func shutdown() {
        lock.lock(); defer { lock.unlock() }
        guard !shutdownCalled else { return }
        shutdownCalled = true
        try? group.syncShutdownGracefully()
    }

    deinit {
        if !shutdownCalled {
            // Best-effort cleanup; deinit must not throw.
            try? group.syncShutdownGracefully()
        }
    }
}
#endif

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
        // Use protobuf on the wire; OTel-Swift's `exportAsJson` default is
        // `true`, but the server cost savings from protobuf matter for mobile.
        let otlpConfig = buildOtlpConfig(authToken: authToken, extraHeaders: extraHeaders)
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

    /// Build an OTLP/HTTP trace exporter. Same semantics as
    /// `makeHttpLogExporter`: appends `/v1/traces` if missing, bearer auth,
    /// gzipped protobuf over the wire.
    ///
    /// `httpClient` is injected when the caller wants to intercept POST
    /// outcomes — typically to persist failed batches to disk via
    /// `PersistingTraceHTTPClient`. When nil, the upstream default
    /// (`BaseHTTPClient` over `URLSession.ephemeral`) is used.
    public static func makeHttpTraceExporter(
        endpoint: String,
        authToken: String?,
        extraHeaders: [String: String] = [:],
        httpClient: HTTPClient? = nil
    ) throws -> OtlpHttpTraceExporter {
        let url = try buildSignalEndpointURL(from: endpoint, signalPath: "/v1/traces")
        let otlpConfig = buildOtlpConfig(authToken: authToken, extraHeaders: extraHeaders)
        if let httpClient = httpClient {
            return OtlpHttpTraceExporter(
                endpoint: url,
                config: otlpConfig,
                httpClient: httpClient,
                envVarHeaders: nil
            )
        }
        return OtlpHttpTraceExporter(
            endpoint: url,
            config: otlpConfig,
            envVarHeaders: nil
        )
    }

    /// Build an OTLP/HTTP metric exporter. Same semantics as
    /// `makeHttpLogExporter`: appends `/v1/metrics` if missing, bearer auth,
    /// gzipped protobuf over the wire.
    public static func makeHttpMetricExporter(
        endpoint: String,
        authToken: String?,
        extraHeaders: [String: String] = [:]
    ) throws -> OtlpHttpMetricExporter {
        let url = try buildSignalEndpointURL(from: endpoint, signalPath: "/v1/metrics")
        let otlpConfig = buildOtlpConfig(authToken: authToken, extraHeaders: extraHeaders)
        return OtlpHttpMetricExporter(
            endpoint: url,
            config: otlpConfig,
            envVarHeaders: nil
        )
    }

    /// Derive the full logs-ingest URL from a user-supplied endpoint string.
    ///
    /// Visible-internal for tests; callers should use `makeHttpLogExporter`.
    static func buildLogsEndpointURL(from endpoint: String) throws -> URL {
        try buildSignalEndpointURL(from: endpoint, signalPath: "/v1/logs")
    }

    /// Derive the full traces-ingest URL from a user-supplied endpoint
    /// string. Used by `OTelMobile.recoverSpanRequests` to route replays
    /// to whatever endpoint the SDK is currently configured with —
    /// independent of whatever was captured at the failed-export time.
    static func buildTracesEndpointURL(from endpoint: String) throws -> URL {
        try buildSignalEndpointURL(from: endpoint, signalPath: "/v1/traces")
    }

    /// Generic endpoint-normalisation used by the log/trace/metric factories.
    /// If the caller already supplied a URL ending in `signalPath`, it's kept
    /// as-is; otherwise `signalPath` is appended (trailing-slash safe).
    static func buildSignalEndpointURL(from endpoint: String, signalPath: String) throws -> URL {
        let trimmed = endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let base = URL(string: trimmed) else {
            throw OTLPExporterFactoryError.invalidEndpoint(endpoint)
        }

        // If caller already supplied the full /v1/<signal> URL, use it as-is.
        if base.path.hasSuffix(signalPath) || base.path.hasSuffix(signalPath + "/") {
            return base
        }

        // Append /v1/<signal>, preserving scheme/host/port/query.
        guard var comps = URLComponents(url: base, resolvingAgainstBaseURL: false) else {
            throw OTLPExporterFactoryError.invalidEndpoint(endpoint)
        }
        let basePath = comps.path.hasSuffix("/")
            ? String(comps.path.dropLast())
            : comps.path
        comps.path = basePath + signalPath

        guard let finalURL = comps.url else {
            throw OTLPExporterFactoryError.invalidEndpoint(endpoint)
        }
        return finalURL
    }

#if canImport(GRPC)

    // MARK: - OTLP/gRPC factories (opt-in)

    /// Build an OTLP/gRPC log exporter. Most customers use OTLP/HTTP via
    /// `makeHttpLogExporter`; use this when you need gRPC (enterprise
    /// collector deployments, lower overhead on high-volume pipelines).
    ///
    /// The returned `GrpcExporterBundle<LogRecordExporter>` owns an
    /// `EventLoopGroup` — keep the bundle alive as long as the exporter is
    /// in use. On app shutdown, call `bundle.shutdown()` to release resources.
    ///
    /// Endpoint format: `https://host:4317` (gRPC standard port). TLS is
    /// selected automatically based on scheme.
    public static func makeGrpcLogExporter(
        endpoint: String,
        authToken: String?,
        extraHeaders: [String: String] = [:]
    ) throws -> GrpcExporterBundle<OtlpLogExporter> {
        let channelInfo = try makeGrpcChannel(endpoint: endpoint)
        let config = buildOtlpConfig(authToken: authToken, extraHeaders: extraHeaders)
        let exporter = OtlpLogExporter(
            channel: channelInfo.channel,
            config: config,
            envVarHeaders: nil
        )
        return GrpcExporterBundle(exporter: exporter, group: channelInfo.group)
    }

    /// Build an OTLP/gRPC trace exporter. See `makeGrpcLogExporter` for details.
    public static func makeGrpcTraceExporter(
        endpoint: String,
        authToken: String?,
        extraHeaders: [String: String] = [:]
    ) throws -> GrpcExporterBundle<OtlpTraceExporter> {
        let channelInfo = try makeGrpcChannel(endpoint: endpoint)
        let config = buildOtlpConfig(authToken: authToken, extraHeaders: extraHeaders)
        let exporter = OtlpTraceExporter(
            channel: channelInfo.channel,
            config: config,
            envVarHeaders: nil
        )
        return GrpcExporterBundle(exporter: exporter, group: channelInfo.group)
    }

    /// Parse an endpoint string like `https://host:port` and open a
    /// platform-appropriate gRPC channel. Scheme selects TLS on/off.
    private static func makeGrpcChannel(endpoint: String) throws -> (channel: GRPCChannel, group: EventLoopGroup) {
        let trimmed = endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let url = URL(string: trimmed),
              let host = url.host else {
            throw OTLPExporterFactoryError.invalidEndpoint(endpoint)
        }
        let port = url.port ?? (url.scheme?.lowercased() == "https" ? 4317 : 4317)
        let useTLS = (url.scheme?.lowercased() ?? "https") == "https"

        // Single-thread event loop is plenty for a mobile exporter — the SDK
        // batches small payloads, not high-throughput streaming.
        let group = MultiThreadedEventLoopGroup(numberOfThreads: 1)
        let builder: ClientConnection.Builder = useTLS
            ? ClientConnection.usingPlatformAppropriateTLS(for: group)
            : ClientConnection.insecure(group: group)
        let channel = builder.connect(host: host, port: port)
        return (channel, group)
    }

#endif

    /// Build the shared OtlpConfiguration used by all three exporters. Auth
    /// token goes on the `Authorization: Bearer` header; any caller-supplied
    /// headers are merged (empty strings are filtered out upstream).
    private static func buildOtlpConfig(
        authToken: String?,
        extraHeaders: [String: String]
    ) -> OtlpConfiguration {
        var headerPairs: [(String, String)] = []
        if let token = authToken, !token.isEmpty {
            headerPairs.append(("Authorization", "Bearer \(token)"))
        }
        for (key, value) in extraHeaders {
            headerPairs.append((key, value))
        }
        return OtlpConfiguration(
            timeout: OtlpConfiguration.DefaultTimeoutInterval,
            compression: .gzip,
            headers: headerPairs.isEmpty ? nil : headerPairs,
            exportAsJson: false
        )
    }
}
