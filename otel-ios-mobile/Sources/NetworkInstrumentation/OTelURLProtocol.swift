import Foundation
import OpenTelemetryApi
import OTelMobileCore

/// `URLProtocol` subclass that intercepts every `URLSession` HTTP request passing
/// through a session whose `URLSessionConfiguration.protocolClasses` includes it,
/// and emits an OpenTelemetry client span for each request using OTel HTTP
/// semantic conventions.
///
/// The request is forwarded through a fresh ephemeral `URLSession` (whose own
/// `protocolClasses` is empty) to avoid infinite recursion. A URLRequest
/// property tag is set as a second-line defence against recursion.
final class OTelURLProtocol: URLProtocol, URLSessionDataDelegate {
    static let handledKey = "io.dash0.mobile.OTelHandled"

    /// Headers that are NEVER captured onto a span, even if the caller puts
    /// them in `NetworkConfig.capturedRequestHeaders`. This is a hard floor.
    static let sensitiveHeaders: Set<String> = [
        "authorization", "cookie", "set-cookie", "proxy-authorization", "x-api-key",
    ]

    private var innerTask: URLSessionDataTask?
    private var innerSession: URLSession?
    private var span: Span?

    // MARK: - URLProtocol

    override class func canInit(with request: URLRequest) -> Bool {
        // Don't recurse on our re-issued request.
        if URLProtocol.property(forKey: handledKey, in: request) != nil {
            return false
        }
        guard let scheme = request.url?.scheme?.lowercased(),
              scheme == "http" || scheme == "https" else {
            return false
        }
        guard NetworkInstrumentation.shared.enabled,
              let config = NetworkInstrumentation.shared.config else {
            return false
        }
        if let host = request.url?.host?.lowercased() {
            if !config.allowedHosts.isEmpty, !config.allowedHosts.contains(host) { return false }
            if config.ignoredHosts.contains(host) { return false }
        }
        return true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        return request
    }

    override func startLoading() {
        guard let tracer = NetworkInstrumentation.shared.tracer,
              let config = NetworkInstrumentation.shared.config else {
            client?.urlProtocol(self, didFailWithError: URLError(.unknown))
            return
        }

        let method = (request.httpMethod ?? "GET").uppercased()
        let url = request.url

        // Span name per OTel HTTP semconv: HTTP method in caps.
        let span = tracer.spanBuilder(spanName: method)
            .setSpanKind(spanKind: .client)
            .startSpan()
        self.span = span

        // Attributes (OTel HTTP semconv)
        span.setAttribute(key: "http.request.method", value: method)
        if let url = url {
            if let scheme = url.scheme { span.setAttribute(key: "url.scheme", value: scheme) }
            if let host = url.host { span.setAttribute(key: "server.address", value: host) }
            if let port = url.port { span.setAttribute(key: "server.port", value: port) }
            span.setAttribute(key: "url.full", value: Self.scrubUrlString(url, config: config))
            span.setAttribute(key: "url.path", value: url.path)
        }

        // Captured request headers (non-sensitive)
        if let headers = request.allHTTPHeaderFields {
            for (name, value) in headers {
                let lower = name.lowercased()
                if Self.sensitiveHeaders.contains(lower) { continue }
                if config.capturedRequestHeaders.contains(lower) {
                    span.setAttribute(key: "http.request.header.\(lower)", value: value)
                }
            }
        }

        // Build the outgoing request. Apple's contract guarantees
        // `NSURLRequest.mutableCopy()` returns `NSMutableURLRequest`, but we
        // still guard with `as?` + fallback — the SDK must never crash the
        // host app, even if Foundation's contract somehow changed.
        let mutable = (request as NSURLRequest).mutableCopy()
        guard let outgoing = mutable as? NSMutableURLRequest else {
            // Defensive fallback: skip our tagging + traceparent injection.
            // The inner URLSession will still load the request; we just won't
            // mark it. This is strictly safer than crashing.
            span.end()
            self.span = nil
            client?.urlProtocol(self, didFailWithError: URLError(.unknown))
            return
        }
        URLProtocol.setProperty(true, forKey: Self.handledKey, in: outgoing)
        if config.propagateTraceContext {
            let ctx = span.context
            let traceId = ctx.traceId.hexString
            let spanId = ctx.spanId.hexString
            let sampled = ctx.isSampled ? "01" : "00"
            let traceparent = "00-\(traceId)-\(spanId)-\(sampled)"
            outgoing.setValue(traceparent, forHTTPHeaderField: "traceparent")
        }

        // Fresh session whose configuration does NOT include our URLProtocol —
        // belt-and-braces on top of the handledKey check in canInit.
        let sessionConfig = URLSessionConfiguration.ephemeral
        sessionConfig.protocolClasses = []
        let session = URLSession(configuration: sessionConfig, delegate: self, delegateQueue: nil)
        self.innerSession = session

        let task = session.dataTask(with: outgoing as URLRequest)
        self.innerTask = task
        task.resume()
    }

    override func stopLoading() {
        innerTask?.cancel()
        innerTask = nil
        innerSession?.finishTasksAndInvalidate()
        innerSession = nil
    }

    // MARK: - URLSessionDataDelegate

    func urlSession(_ session: URLSession,
                    dataTask: URLSessionDataTask,
                    didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        if let http = response as? HTTPURLResponse,
           let span = span,
           let config = NetworkInstrumentation.shared.config {
            span.setAttribute(key: "http.response.status_code", value: http.statusCode)
            for (name, value) in http.allHeaderFields {
                let nameStr = String(describing: name).lowercased()
                if config.capturedResponseHeaders.contains(nameStr) {
                    span.setAttribute(
                        key: "http.response.header.\(nameStr)",
                        value: String(describing: value)
                    )
                }
            }
            if http.statusCode >= config.errorStatusThreshold {
                span.status = .error(description: "HTTP \(http.statusCode)")
                // Emit a log record so the DSL `http_match` matcher can trigger a
                // conditional flush. `event.name = "http.error"` is the matcher's
                // contract — without this attribute the matcher never fires and
                // CONDITIONAL + HYBRID modes silently drop the window (the bug
                // reported 2026-05-12 "hybrid failed to send on http error").
                // Mirrors the Android OTelNetworkInterceptor http.error log shape.
                if let logger = NetworkInstrumentation.shared.logger {
                    var attrs: [String: AttributeValue] = [
                        "event.name": AttributeValue.string("http.error"),
                        "http.response.status_code": AttributeValue.int(http.statusCode),
                    ]
                    if let method = request.httpMethod {
                        attrs["http.request.method"] = AttributeValue.string(method.uppercased())
                    }
                    if let urlString = request.url?.absoluteString {
                        attrs["url.full"] = AttributeValue.string(urlString)
                    }
                    logger.logRecordBuilder()
                        .setBody(AttributeValue.string("http.error"))
                        .setSeverity(.error)
                        .setAttributes(attrs)
                        .emit()
                }
            } else {
                span.status = .ok
            }
        }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        client?.urlProtocol(self, didLoad: data)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error = error {
            client?.urlProtocol(self, didFailWithError: error)
            span?.setAttribute(key: "error.type", value: String(describing: type(of: error)))
            span?.status = .error(description: error.localizedDescription)
        } else {
            client?.urlProtocolDidFinishLoading(self)
        }
        span?.end()
        span = nil
    }

    // MARK: - Helpers

    /// Scrubs a captured URL using the supplied config:
    /// 1. When `config.scrubUrls` is true, delegates to `PiiScrubber.scrubUrl`
    ///    so sensitive query-param values become `[REDACTED]` and UUID/numeric
    ///    path segments become `{uuid}` / `{id}` (latter gated by
    ///    `config.scrubPathSegments`). `stripQueryStrings` chooses between
    ///    "drop the whole query" (true) and "redact only sensitive keys"
    ///    (false).
    /// 2. When `config.scrubUrls` is false, falls back to the legacy behavior
    ///    of stripping the query string when `stripQueryStrings` is true,
    ///    leaving the URL untouched otherwise.
    static func scrubUrlString(_ url: URL, config: NetworkConfig) -> String {
        if config.scrubUrls {
            return PiiScrubber.scrubUrl(
                url.absoluteString,
                allowQueryParams: !config.stripQueryStrings,
                scrubPathSegments: config.scrubPathSegments
            )
        }
        guard config.stripQueryStrings else { return url.absoluteString }
        var comps = URLComponents(url: url, resolvingAgainstBaseURL: false)
        comps?.query = nil
        return comps?.url?.absoluteString ?? url.absoluteString
    }
}
