import Foundation
import OpenTelemetryProtocolExporterHttp
import OTelMobileCore

#if canImport(FoundationNetworking)
  import FoundationNetworking
#endif

/// Custom `HTTPClient` that intercepts OTLP/HTTP trace POSTs. On a failed
/// response (network error, 5xx, or 429), the raw request body and its
/// routing context (endpoint + headers) are persisted to `DiskSpanBuffer`
/// so the next process launch can replay them via
/// `OTelMobile.recoverSpanRequests`.
///
/// ## Why intercept at the HTTPClient layer
///
/// The obvious-looking design — a `SpanExporter` decorator that watches
/// for `.failure` from the upstream `OtlpHttpTraceExporter` — does NOT
/// work. Upstream's implementation returns `.success` synchronously from
/// `export()` BEFORE the HTTP call completes, and only surfaces failures
/// via its internal `httpClient.send(request:) { result in ... }`
/// callback. A decorator at the SpanExporter level sees only `.success`,
/// making the persist-on-failure branch dead code.
///
/// The `HTTPClient` layer is the first place failures are actually
/// observable. The trade-off is that we work with serialized bytes at
/// this layer instead of decoded `SpanData` — which is fine, because
/// we're going to POST those exact bytes again on recovery anyway.
///
/// ## What triggers a persist
///
/// - `Result.failure(_)` — any URLSession error (DNS, timeout, TLS,
///   unreachable host).
/// - `HTTPURLResponse.statusCode >= 500` — collector is having a bad
///   time; retry later.
/// - `HTTPURLResponse.statusCode == 429` — explicit backpressure.
///
/// 4xx statuses other than 429 are NOT persisted — the body is wrong or
/// unauthorized and replay won't help. That distinction prevents the disk
/// buffer from filling up with permanently-bad payloads.
///
/// ## Session id handling
///
/// The session id is read from `SessionProvider` at persist time (not
/// construction time), so spans persisted after a 15-minute inactivity
/// rotation carry the correct session id. This matches how
/// `MobileLogRecordProcessor` reads session fresh per-event — zero
/// signal drift.
public final class PersistingTraceHTTPClient: HTTPClient, @unchecked Sendable {
    private let delegate: HTTPClient
    private let diskBuffer: DiskSpanBuffer
    private let sessionProvider: SessionProvider
    private let persistTimeout: TimeInterval

    public init(
        delegate: HTTPClient = BaseHTTPClient(),
        diskBuffer: DiskSpanBuffer,
        sessionProvider: SessionProvider,
        persistTimeout: TimeInterval = 5
    ) {
        self.delegate = delegate
        self.diskBuffer = diskBuffer
        self.sessionProvider = sessionProvider
        self.persistTimeout = persistTimeout
    }

    public func send(
        request: URLRequest,
        completion: @escaping (Result<HTTPURLResponse, Error>) -> Void
    ) {
        // Snapshot request shape NOW, on the calling thread — URLRequest
        // is a value type but URL/headerFields are read-mostly so this is
        // a cheap defensive copy that also avoids racing with any later
        // mutation the caller might do before the callback fires.
        let endpoint = request.url
        let body = request.httpBody ?? Data()
        let headers = request.allHTTPHeaderFields ?? [:]

        delegate.send(request: request) { [weak self] result in
            // Forward the original completion immediately. The upstream
            // exporter ignores the callback's result for its synchronous
            // return value anyway, so our persist work running after the
            // callback has zero observable impact on BSP — but we still
            // want the caller unblocked before any disk I/O.
            completion(result)

            guard let self = self else { return }
            guard Self.shouldPersist(result: result),
                  let endpoint = endpoint,
                  !body.isEmpty else { return }

            let bufferedRequest = BufferedSpanRequest.pending(
                endpoint: endpoint,
                headers: headers,
                body: body,
                sessionId: self.sessionProvider.sessionId
            )

            // Bridge from the URLSession callback thread to the
            // DiskSpanBuffer actor via a DispatchSemaphore with a short
            // cap. Blocking the BSP background thread for <5s on sqlite
            // I/O is acceptable; it's the same pattern
            // MobileLogRecordProcessor uses when spilling logs.
            let semaphore = DispatchSemaphore(value: 0)
            let buffer = self.diskBuffer
            let timeout = self.persistTimeout
            Task {
                await buffer.persist(bufferedRequest)
                semaphore.signal()
            }
            _ = semaphore.wait(timeout: .now() + timeout)
        }
    }

    /// Exposed for testing: returns true iff the result is a failure mode
    /// that warrants disk persistence. Network errors always do;
    /// HTTP status codes only when retryable.
    public static func shouldPersist(result: Result<HTTPURLResponse, Error>) -> Bool {
        switch result {
        case .failure:
            return true
        case .success(let response):
            let status = response.statusCode
            return status >= 500 || status == 429
        }
    }
}
