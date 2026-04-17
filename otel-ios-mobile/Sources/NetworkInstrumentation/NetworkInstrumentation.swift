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
    private(set) var tracer: Tracer?
    private(set) var config: NetworkConfig?
    private(set) var enabled = false

    private init() {}

    /// Install the URLProtocol and protocol-class swizzle.
    /// Safe to call multiple times — second call updates tracer/config in place.
    public func install(tracer: Tracer, config: NetworkConfig = .default) {
        lock.lock()
        defer { lock.unlock() }
        self.tracer = tracer
        self.config = config
        if !enabled {
            URLProtocol.registerClass(OTelURLProtocol.self)
            URLSessionConfigurationSwizzle.install()
            enabled = true
        }
    }

    /// Stop capturing new requests. In-flight requests finish under the protocol.
    public func uninstall() {
        lock.lock()
        defer { lock.unlock() }
        enabled = false
        URLProtocol.unregisterClass(OTelURLProtocol.self)
        URLSessionConfigurationSwizzle.uninstall()
    }
}
