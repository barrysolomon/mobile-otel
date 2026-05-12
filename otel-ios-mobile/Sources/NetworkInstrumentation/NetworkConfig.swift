import Foundation

/// Configuration for URLSession auto-instrumentation.
///
/// All host sets are stored lowercased for case-insensitive matching against
/// the URL host. Header name sets are stored lowercased too — HTTP header names
/// are case-insensitive per RFC 7230.
public struct NetworkConfig: Sendable {
    /// Hosts to skip entirely. Case-insensitive exact-match.
    public let ignoredHosts: Set<String>

    /// Captures only these hosts if non-empty (allowlist). Empty means "capture all".
    public let allowedHosts: Set<String>

    /// Strip query strings from URLs before recording (privacy).
    public let stripQueryStrings: Bool

    /// Record these response header names on the span. Case-insensitive.
    /// Common safe picks: "content-type", "content-length", "x-request-id".
    public let capturedResponseHeaders: Set<String>

    /// Record these request header names. By default empty (privacy-safe).
    /// Caller can add headers they know are safe. `Authorization`, `Cookie`,
    /// and other sensitive headers are always refused even if listed here.
    public let capturedRequestHeaders: Set<String>

    /// Status codes >= this count as errors on the span. Default 500 (server errors only).
    /// Set to 400 to mark client errors as span failures too.
    public let errorStatusThreshold: Int

    /// Inject W3C traceparent header on outgoing requests for distributed tracing.
    /// Default false — enable once backend is ready to correlate.
    public let propagateTraceContext: Bool

    /// Route the captured `url.full` through `PiiScrubber.scrubUrl` so emails,
    /// auth tokens, and other sensitive query-param values are tokenized
    /// (`[REDACTED]`) and UUID/numeric path segments collapse to
    /// `{uuid}` / `{id}`. When false, the URL is captured verbatim subject
    /// only to `stripQueryStrings`. Mirrors Android's `scrubUrls` flag.
    public let scrubUrls: Bool

    /// When `scrubUrls == true`, controls whether UUID + numeric IDs in the
    /// URL path are collapsed to placeholders. No effect when `scrubUrls`
    /// is false.
    public let scrubPathSegments: Bool

    public static let `default` = NetworkConfig(
        ignoredHosts: [],
        allowedHosts: [],
        stripQueryStrings: true,
        capturedResponseHeaders: ["content-type"],
        capturedRequestHeaders: [],
        errorStatusThreshold: 400,
        propagateTraceContext: false,
        scrubUrls: true,
        scrubPathSegments: true
    )

    public init(
        ignoredHosts: Set<String> = [],
        allowedHosts: Set<String> = [],
        stripQueryStrings: Bool = true,
        capturedResponseHeaders: Set<String> = ["content-type"],
        capturedRequestHeaders: Set<String> = [],
        errorStatusThreshold: Int = 400,
        propagateTraceContext: Bool = false,
        scrubUrls: Bool = true,
        scrubPathSegments: Bool = true
    ) {
        self.ignoredHosts = Set(ignoredHosts.map { $0.lowercased() })
        self.allowedHosts = Set(allowedHosts.map { $0.lowercased() })
        self.stripQueryStrings = stripQueryStrings
        self.capturedResponseHeaders = Set(capturedResponseHeaders.map { $0.lowercased() })
        self.capturedRequestHeaders = Set(capturedRequestHeaders.map { $0.lowercased() })
        self.errorStatusThreshold = errorStatusThreshold
        self.propagateTraceContext = propagateTraceContext
        self.scrubUrls = scrubUrls
        self.scrubPathSegments = scrubPathSegments
    }
}
