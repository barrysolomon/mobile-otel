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
    private let remoteGate: RemoteGate?
    private let logger: Logger?

    private let lock = NSLock()
    private var timer: DispatchSourceTimer?
    private var consecutiveFailures = 0
    private var stopped = false
    private let session: URLSession

    /// Warm-start race guard. `start()` fires the persisted-config apply in a
    /// detached Task AND schedules the first network fetch concurrently; the
    /// two detached `applyConfig` calls can land in EITHER order. A fresh
    /// gateway result must always win over a stale persisted snapshot —
    /// otherwise a persisted `enabled = false` could clobber a just-fetched
    /// `enabled = true` until the next poll. We flip this flag (under `lock`)
    /// the instant a gateway config is applied; the persisted apply checks it
    /// first and no-ops if a gateway result already arrived. A persisted
    /// disable still applies on a true cold start (no gateway result yet),
    /// preserving keep-last-across-restarts. Guarded by `lock`.
    private var gatewayConfigApplied = false

    public init(
        gatewayEndpoint: String,
        authToken: String?,
        extraHeaders: [String: String] = [:],
        pollingIntervalSeconds: TimeInterval = 300,
        maxBackoffSeconds: TimeInterval = 3600,
        evaluator: PolicyEvaluator,
        remoteGate: RemoteGate? = nil,
        logger: Logger? = nil,
        defaults: UserDefaults = .standard
    ) throws {
        guard let base = URL(string: gatewayEndpoint) else {
            throw PollerError.invalidEndpoint(gatewayEndpoint)
        }
        // Warn (never crash) on cleartext config polling to a non-localhost
        // host. Policy config is fetched over this transport; http:// exposes
        // it to tampering / interception. localhost is allowed for local dev.
        if (base.scheme?.lowercased() ?? "") == "http" {
            let host = base.host?.lowercased() ?? ""
            let isLocal = host == "localhost" || host == "127.0.0.1" || host == "::1" || host.hasSuffix(".local")
            if !isLocal {
                NSLog("[Dash0] policy config endpoint '%@' uses cleartext http:// to a non-localhost host. Config will be fetched UNENCRYPTED. Use https://.", gatewayEndpoint)
            }
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
        self.remoteGate = remoteGate
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
        // Warm-start ordering guard. The persisted snapshot must never overwrite
        // a fresher gateway result, regardless of which detached Task runs
        // first OR how the `await` below interleaves them. A gateway apply
        // claims precedence by flipping `gatewayConfigApplied`; a persisted
        // apply bails out the moment it sees that flag set — both up-front (the
        // gateway already won before we started) and again right before the
        // gate write (the gateway won while we were suspended in
        // `updatePolicies`). The gate/persist mutations are the kill-switch-
        // critical writes, so the second check is taken under the lock
        // immediately before them, with no intervening `await`.
        let isGateway = (source == "gateway")
        lock.lock()
        if isGateway {
            gatewayConfigApplied = true
        } else if gatewayConfigApplied {
            // A gateway result already won — discard the stale persisted apply
            // without touching the evaluator, the gate, or UserDefaults.
            lock.unlock()
            return false
        }
        lock.unlock()
        await evaluator.updatePolicies(config.policies)
        // Re-check precedence before the kill-switch-critical writes: a gateway
        // result may have landed (and flipped the flag) while we were suspended
        // in `updatePolicies` above. If so, leave the gate and persisted
        // snapshot exactly as the gateway left them.
        lock.lock()
        if !isGateway && gatewayConfigApplied {
            lock.unlock()
            return false
        }
        lock.unlock()
        // Push the remote kill-switch + global-sampling state into the shared
        // gate. Precedence (spec §Fail-open): a config that PARSES but omits
        // the `sdk` block re-enables the fleet (absence = "no restriction"),
        // so an absent block maps to `.default` rather than leaving a prior
        // disabled state stuck. A transient fetch/parse FAILURE never reaches
        // here — `fetchOnce` returns early — so the last-applied gate value is
        // preserved automatically (fail-open / keep-last).
        remoteGate?.update(config.sdkConfig ?? .default)
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

    // MARK: - Test hooks (internal — exercised by ConfigPollerTests)

    /// Applies a single config through the real `applyConfig` path with an
    /// explicit `source` ("persisted" / "gateway"), so tests can drive the
    /// warm-start ordering guard deterministically (no network, no timer).
    @discardableResult
    func testApply(jsonString: String, source: String) async -> Bool {
        await applyConfig(data: Data(jsonString.utf8), source: source)
    }

    /// Whether a gateway config has claimed warm-start precedence. Internal
    /// read for tests asserting the ordering guard.
    var testGatewayConfigApplied: Bool {
        lock.lock(); defer { lock.unlock() }
        return gatewayConfigApplied
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
