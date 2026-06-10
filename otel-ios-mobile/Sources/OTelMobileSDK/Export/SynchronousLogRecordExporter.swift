import Foundation
import OpenTelemetryApi
import OpenTelemetrySdk
import OpenTelemetryProtocolExporterCommon
import OpenTelemetryProtocolExporterHttp

/// Custom `HTTPClient` that sends synchronously (blocks on URLSession
/// completion via semaphore) and records the last send result so callers
/// can inspect it after the async-returning upstream exporter returns.
///
/// Default `BaseHTTPClient.send` fires a URLSession data task that
/// completes on a background queue. Upstream `OtlpHttpLogExporter.export`
/// does NOT wait for that — it returns `.success` immediately and lets
/// the completion run on its own. That breaks every downstream retry/
/// persist decorator because they can't see real failures.
///
/// This client blocks each `send` call on the completion before
/// returning, so the upstream completion handler runs synchronously
/// relative to the `export` call. It also publishes the outcome into
/// `lastResult` which the wrapping `SynchronousLogRecordExporter` reads
/// to turn an always-success `.export` return value into a real result.
final class SynchronousHTTPClient: NSObject, HTTPClient, @unchecked Sendable {
    private let session: URLSession
    private let lock = NSLock()
    private var _lastResult: Result<HTTPURLResponse, Error>?

    /// - Parameter pinning: optional certificate / public-key pinning. When
    ///   non-nil and non-empty the session is built with the pinning
    ///   `URLSessionDelegate` (`TransportSecurity.makePinnedSession`), so a pin
    ///   mismatch fails this connection (fail-closed) without crashing the
    ///   host. When `nil`/empty the session is built exactly as before — an
    ///   ephemeral, cache-disabled session with no delegate.
    init(pinning: TransportSecurity.PinningConfig? = nil) {
        let configuration: URLSessionConfiguration = .ephemeral
        configuration.urlCache = nil
        self.session = TransportSecurity.makePinnedSession(
            pinning: pinning,
            configuration: configuration
        )
        super.init()
    }

    func send(request: URLRequest,
              completion: @escaping (Result<HTTPURLResponse, Error>) -> Void) {
        let semaphore = DispatchSemaphore(value: 0)
        var captured: Result<HTTPURLResponse, Error>?
        let task = session.dataTask(with: request) { _, response, error in
            if let error = error {
                captured = .failure(error)
            } else if let http = response as? HTTPURLResponse {
                captured = .success(http)
            } else {
                captured = .failure(NSError(domain: "io.dash0.mobile.SynchronousHTTPClient",
                                            code: -1,
                                            userInfo: [NSLocalizedDescriptionKey: "no response"]))
            }
            semaphore.signal()
        }
        task.resume()
        // Block this thread until the URLSession completion fires, OR
        // until the request's own timeout (+ a small fudge). The
        // upstream `OtlpHttpLogExporter.createRequest` sets
        // `request.timeoutInterval` from `config.timeout`; URLSession
        // respects that, so we wait at most that long before giving up.
        let timeout = request.timeoutInterval.isFinite ? request.timeoutInterval + 2 : 35
        _ = semaphore.wait(timeout: .now() + timeout)
        let result = captured ?? .failure(NSError(
            domain: "io.dash0.mobile.SynchronousHTTPClient",
            code: -2,
            userInfo: [NSLocalizedDescriptionKey: "timed out waiting for URLSession"]
        ))
        lock.lock()
        _lastResult = result
        lock.unlock()
        // Invoke the upstream completion on the same thread so its
        // internal state mutations (pendingLogRecords re-append on
        // failure, exporterMetrics) run before the caller's
        // `export(...)` returns.
        completion(result)
    }

    var lastResult: Result<HTTPURLResponse, Error>? {
        lock.lock(); defer { lock.unlock() }
        return _lastResult
    }

    func resetLastResult() {
        lock.lock(); defer { lock.unlock() }
        _lastResult = nil
    }
}

/// Decorator that turns `OtlpHttpLogExporter.export(...)` — which is
/// fire-and-forget and always returns `.success` — into a synchronous
/// operation that returns the actual send result.
///
/// Upstream `opentelemetry-swift`'s `OtlpHttpLogExporter.export(logRecords:)`
/// queues records, kicks off an async URLSession send, and returns
/// `.success` immediately regardless of outcome. That breaks:
///   - `RetryableExporter`: never sees failure, never retries.
///   - `MobileLogRecordProcessor.forceFlushBuffered` on-failure-persist:
///     never fires because failure is never reported.
///   - The offline-survives-reconnect promise: drained RAM events are
///     neither sent nor persisted.
///
/// Mechanism: the wrapped exporter is constructed with a
/// `SynchronousHTTPClient` that blocks each URLSession call until the
/// response (or timeout/error) arrives. The wrapper reads the client's
/// `lastResult` after each `export` to synthesize a correct
/// `ExportResult`. HTTP 2xx → success; 4xx/5xx → failure; network
/// error → failure.
public final class SynchronousLogRecordExporter: LogRecordExporter, @unchecked Sendable {
    private let delegate: LogRecordExporter
    private let httpClient: SynchronousHTTPClient

    /// Build a synchronous log exporter pointing at `endpoint`. Mirrors
    /// the parameter surface of `OTLPExporterFactory.makeHttpLogExporter`
    /// so callers substitute this in one spot.
    ///
    /// - Parameter pinning: optional certificate / public-key pinning applied
    ///   to the underlying `URLSession`, consistent with the OTLP trace/metric
    ///   exporters. `nil`/empty means no pinning (current behaviour).
    public init(endpoint: URL,
                authToken: String?,
                extraHeaders: [String: String],
                pinning: TransportSecurity.PinningConfig? = nil) {
        let client = SynchronousHTTPClient(pinning: pinning)
        self.httpClient = client
        var headers: [(String, String)] = []
        if let authToken = authToken, !authToken.isEmpty {
            headers.append(("Authorization", "Bearer \(authToken)"))
        }
        for (k, v) in extraHeaders {
            headers.append((k, v))
        }
        let config = OpenTelemetryProtocolExporterCommon.OtlpConfiguration(
            timeout: 30,
            headers: headers
        )
        self.delegate = OtlpHttpLogExporter(
            endpoint: endpoint,
            config: config,
            httpClient: client,
            envVarHeaders: nil
        )
    }

    public func export(logRecords: [ReadableLogRecord], explicitTimeout: TimeInterval?) -> ExportResult {
        httpClient.resetLastResult()
        // Kick off the upstream export. Because our HTTPClient is
        // synchronous, by the time this returns, the URLSession call
        // has completed and the completion handler has run.
        _ = delegate.export(logRecords: logRecords, explicitTimeout: explicitTimeout)
        switch httpClient.lastResult {
        case .some(.success(let resp)):
            return (200...299).contains(resp.statusCode) ? .success : .failure
        case .some(.failure):
            return .failure
        case .none:
            // Upstream never invoked our HTTPClient (e.g. empty records
            // path). Treat as trivially successful.
            return .success
        }
    }

    public func forceFlush(explicitTimeout: TimeInterval?) -> ExportResult {
        delegate.forceFlush(explicitTimeout: explicitTimeout)
    }

    public func shutdown(explicitTimeout: TimeInterval?) {
        delegate.shutdown(explicitTimeout: explicitTimeout)
    }
}
