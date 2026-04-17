import Foundation
import OpenTelemetryApi
import OTelMobileCore

/// Auto-instrumentation for URLSession HTTP requests.
///
/// Usage:
/// ```swift
/// let mobile = try OTelMobile.start(config: config)
/// if let tracer = mobile.tracer {
///     NetworkInstrumentation.shared.install(tracer: tracer)
/// }
/// ```
///
/// From install time forward, every `URLSession.shared.dataTask(...)` (and
/// most custom sessions, via protocolClasses swizzle) emits a `client` span
/// named after the HTTP method with OTel HTTP semconv attributes
/// (`http.request.method`, `url.full`, `http.response.status_code`, etc.).
public final class NetworkInstrumentation: @unchecked Sendable {
    public static let shared = NetworkInstrumentation()

    private let lock = NSLock()

    // Storage for the state that OTelURLProtocol reads on the hot path.
    // All reads and writes go through the lock; we NEVER return the raw
    // storage without copying behind the lock — that would race with a
    // concurrent install/uninstall.
    private var _tracer: Tracer?
    private var _config: NetworkConfig?
    private var _enabled = false

    /// Thread-safe snapshot of the current install state. Used by
    /// OTelURLProtocol.canInit / startLoading. Reads are O(1) behind the
    /// same NSLock that guards install/uninstall.
    var snapshot: (tracer: Tracer?, config: NetworkConfig?, enabled: Bool) {
        lock.lock(); defer { lock.unlock() }
        return (_tracer, _config, _enabled)
    }

    // Read-only accessors retained for backward compat; all route through
    // the lock so we never return torn state. Prefer `snapshot` on the hot
    // path if reading multiple fields together — one lock round trip.
    var tracer: Tracer? {
        lock.lock(); defer { lock.unlock() }
        return _tracer
    }

    var config: NetworkConfig? {
        lock.lock(); defer { lock.unlock() }
        return _config
    }

    var enabled: Bool {
        lock.lock(); defer { lock.unlock() }
        return _enabled
    }

    private init() {}

    /// Install the URLProtocol and protocol-class swizzle.
    /// Safe to call multiple times — second call updates tracer/config in place.
    public func install(tracer: Tracer, config: NetworkConfig = .default) {
        lock.lock()
        defer { lock.unlock() }
        _tracer = tracer
        _config = config
        if !_enabled {
            URLProtocol.registerClass(OTelURLProtocol.self)
            URLSessionConfigurationSwizzle.install()
            _enabled = true
        }
    }

    /// Stop capturing new requests. In-flight requests finish under the protocol.
    public func uninstall() {
        lock.lock()
        defer { lock.unlock() }
        _enabled = false
        URLProtocol.unregisterClass(OTelURLProtocol.self)
        URLSessionConfigurationSwizzle.uninstall()
    }
}
