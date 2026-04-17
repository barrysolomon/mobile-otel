import Foundation
import OTelMobileCore
import OpenTelemetryApi

/// Polls the gateway for DSL v2 policy config on a timer, parses it via
/// `PolicyParser`, and pushes the result to a `PolicyEvaluator`. Mirrors
/// Android's config-poll loop.
///
/// Endpoints:
/// - `GET <endpoint>/config?dsl_version=2` — response is DSL v2 JSON
/// - On success: parse, if compile succeeds push to evaluator, persist to
///   UserDefaults so next launch has a warm start
/// - On failure (HTTP error / network error / parse error): retry with
///   exponential backoff capped at `maxBackoffSeconds`
///
/// Policy updates are idempotent — the evaluator swaps the policy list
/// atomically. A dropped poll is never a correctness issue, only freshness.
///
/// SAFETY:
/// - Uses an `ephemeral` URLSession with a 15s timeout. Never hangs.
/// - All scheduling on a DispatchSourceTimer, never the main thread.
/// - Network failures are logged via `print` but never raise or crash.
public final class ConfigPoller: @unchecked Sendable {
    private static let lastConfigKey = "io.dash0.mobile.lastKnownPolicyConfig"

    private let endpoint: URL
    private let authToken: String?
    private let extraHeaders: [String: String]
    private let pollingInterval: TimeInterval
    private let maxBackoffSeconds: TimeInterval
    private let defaults: UserDefaults
    private let evaluator: PolicyEvaluator
    private let logger: Logger?

    private let lock = NSLock()
    private var timer: DispatchSourceTimer?
    private var consecutiveFailures = 0
    private var stopped = false
    private let session: URLSession

    public init(
        gatewayEndpoint: String,
        authToken: String?,
        extraHeaders: [String: String] = [:],
        pollingIntervalSeconds: TimeInterval = 300,
        maxBackoffSeconds: TimeInterval = 3600,
        evaluator: PolicyEvaluator,
        logger: Logger? = nil,
        defaults: UserDefaults = .standard
    ) throws {
        guard let base = URL(string: gatewayEndpoint) else {
            throw PollerError.invalidEndpoint(gatewayEndpoint)
        }
        // Normalize to `<base>/config?dsl_version=2`, adding /config if not
        // already present.
        let configURL: URL = {
            if base.path.contains("/config") {
                return Self.appendQuery(base, key: "dsl_version", value: "2")
            }
            let withConfig = base.appendingPathComponent("config")
            return Self.appendQuery(withConfig, key: "dsl_version", value: "2")
        }()
        self.endpoint = configURL
        self.authToken = authToken
        self.extraHeaders = extraHeaders
        self.pollingInterval = pollingIntervalSeconds
        self.maxBackoffSeconds = maxBackoffSeconds
        self.defaults = defaults
        self.evaluator = evaluator
        self.logger = logger

        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 15
        cfg.timeoutIntervalForResource = 30
        // Don't let our poller get captured by an app's URLProtocol stack.
        cfg.protocolClasses = []
        self.session = URLSession(configuration: cfg)
    }

    /// Start polling. First poll runs immediately on a background queue;
    /// subsequent polls run on `pollingIntervalSeconds` cadence unless a
    /// failure triggers backoff. If there's a previously-persisted config in
    /// UserDefaults, it's loaded synchronously so the evaluator has policies
    /// before the first network fetch completes.
    public func start() {
        // Warm-start from persisted config.
        if let data = defaults.data(forKey: Self.lastConfigKey) {
            Task.detached { [weak self] in
                await self?.applyConfig(data: data, source: "persisted")
            }
        }
        schedule(delayMs: 0)
    }

    public func stop() {
        lock.lock(); defer { lock.unlock() }
        stopped = true
        timer?.cancel()
        timer = nil
    }

    // MARK: - Scheduling

    private func schedule(delayMs: Int) {
        lock.lock()
        guard !stopped else { lock.unlock(); return }
        timer?.cancel()
        let queue = DispatchQueue(label: "io.dash0.mobile.ConfigPoller", qos: .utility)
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + .milliseconds(delayMs))
        t.setEventHandler { [weak self] in
            self?.fetchOnce()
        }
        timer = t
        t.resume()
        lock.unlock()
    }

    private func scheduleNextAfter(success: Bool) {
        lock.lock()
        if success {
            consecutiveFailures = 0
            let nextMs = Int(pollingInterval * 1000)
            lock.unlock()
            schedule(delayMs: nextMs)
        } else {
            consecutiveFailures += 1
            let backoff = min(pollingInterval * pow(2, Double(consecutiveFailures - 1)), maxBackoffSeconds)
            lock.unlock()
            schedule(delayMs: Int(backoff * 1000))
        }
    }

    // MARK: - Fetch + apply

    private func fetchOnce() {
        var request = URLRequest(url: endpoint)
        request.httpMethod = "GET"
        if let token = authToken, !token.isEmpty {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        for (k, v) in extraHeaders {
            request.setValue(v, forHTTPHeaderField: k)
        }
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        session.dataTask(with: request) { [weak self] data, response, error in
            guard let self = self else { return }
            if let error = error {
                self.logFetchFailure("network", error: error.localizedDescription)
                self.scheduleNextAfter(success: false)
                return
            }
            guard let http = response as? HTTPURLResponse else {
                self.logFetchFailure("no-response", error: "no HTTPURLResponse")
                self.scheduleNextAfter(success: false)
                return
            }
            guard (200...299).contains(http.statusCode), let data = data else {
                self.logFetchFailure("http", error: "status \(http.statusCode)")
                self.scheduleNextAfter(success: false)
                return
            }
            Task.detached { [weak self] in
                let applied = await (self?.applyConfig(data: data, source: "gateway") ?? false)
                self?.scheduleNextAfter(success: applied)
            }
        }.resume()
    }

    @discardableResult
    private func applyConfig(data: Data, source: String) async -> Bool {
        guard let json = String(data: data, encoding: .utf8) else {
            logFetchFailure("encoding", error: "response was not utf-8")
            return false
        }
        guard let config = PolicyParser.parseConfigV2(jsonString: json) else {
            logFetchFailure("parse", error: "parseConfigV2 returned nil")
            return false
        }
        await evaluator.updatePolicies(config.policies)
        // Persist to warm-start the next launch.
        defaults.set(data, forKey: Self.lastConfigKey)
        logger?.logRecordBuilder()
            .setBody(AttributeValue.string("policy.config_applied"))
            .setSeverity(.info)
            .setAttributes([
                "event.name": .string("policy.config_applied"),
                "policy.count": .int(config.policies.count),
                "policy.source": .string(source),
            ])
            .emit()
        return true
    }

    private func logFetchFailure(_ kind: String, error: String) {
        logger?.logRecordBuilder()
            .setBody(AttributeValue.string("policy.config_fetch_failed"))
            .setSeverity(.warn)
            .setAttributes([
                "event.name": .string("policy.config_fetch_failed"),
                "failure.kind": .string(kind),
                "failure.message": .string(error),
            ])
            .emit()
    }

    // MARK: - URL helpers

    static func appendQuery(_ url: URL, key: String, value: String) -> URL {
        guard var comps = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return url
        }
        var items = comps.queryItems ?? []
        items.removeAll { $0.name == key }
        items.append(URLQueryItem(name: key, value: value))
        comps.queryItems = items
        return comps.url ?? url
    }

    public enum PollerError: Error, CustomStringConvertible {
        case invalidEndpoint(String)
        public var description: String {
            switch self {
            case .invalidEndpoint(let s): return "ConfigPoller: invalid endpoint \(s)"
            }
        }
    }
}
