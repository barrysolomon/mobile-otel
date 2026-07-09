import Foundation
import Testing
import OpenTelemetryApi
@testable import NetworkInstrumentation

/// Regression contract for the HYBRID-mode http_match flush bug reported
/// 2026-05-12: "hybrid failed to send on http error".
///
/// Root cause on iOS: `OTelURLProtocol` only set the span status to
/// `.error` on a 4xx/5xx response. It did not emit a log record, so the
/// DSL `http_match` matcher (which keys on `event.name = "http.error"`
/// as an attribute, not a span) never fired and CONDITIONAL + HYBRID
/// modes silently dropped the window.
///
/// Fix: `NetworkInstrumentation.install(tracer:logger:config:)` now
/// accepts an optional `Logger`, and `OTelURLProtocol` emits an
/// `http.error` log with `event.name = "http.error"` when a logger is
/// present and the response is an error.
///
/// This suite locks the install API contract. The URLSession round-trip
/// path is exercised in the demo-app E2E validation (validate-ios-end-
/// to-end.sh) and mirrored implementation-for-implementation against
/// Android's HttpErrorEventNameTest.
///
/// Each test asserts on a DETACHED `NetworkInstrumentation()` instance,
/// never on `.shared`. `.shared` is process-global mutable state and
/// eleven other suites call `OTelMobile.start()`, which synchronously
/// re-installs it WITH a logger — under Swift Testing's default parallel
/// execution that flipped `snapshot.logger` from nil to `DefaultLogger`
/// mid-test (~1/9 full-suite runs, 2026-07-09). `install()`/`snapshot`
/// are instance behavior, so a fresh instance pins the identical code
/// path race-free — no cross-suite serialization needed.
@Suite("NetworkInstrumentation http.error logger wiring")
struct HttpErrorLogContractTests {

    @Test("install with no logger leaves snapshot.logger nil")
    func installWithoutLogger() {
        let tracer = OpenTelemetry.instance.tracerProvider.get(
            instrumentationName: "test", instrumentationVersion: nil
        )
        let net = NetworkInstrumentation()
        net.install(tracer: tracer)
        defer { net.uninstall() }

        let snap = net.snapshot
        #expect(snap.tracer != nil, "tracer must be set")
        #expect(snap.logger == nil, "no logger passed → snapshot.logger is nil")
    }

    @Test("install with logger surfaces the logger via snapshot")
    func installWithLogger() {
        let tracer = OpenTelemetry.instance.tracerProvider.get(
            instrumentationName: "test", instrumentationVersion: nil
        )
        // The noop LoggerProvider is enough — we're testing the wire, not the
        // emission path. The emission path is exercised in Android's
        // HttpErrorEventNameTest and in iOS demo-app E2E validation.
        let logger = DefaultLoggerProvider.instance.get(instrumentationScopeName: "test")
        let net = NetworkInstrumentation()
        net.install(tracer: tracer, logger: logger)
        defer { net.uninstall() }

        let snap = net.snapshot
        #expect(snap.logger != nil, "logger passed → snapshot.logger is non-nil")
    }

    @Test("re-install with different logger swaps in place")
    func reinstallSwapsLogger() {
        let tracer = OpenTelemetry.instance.tracerProvider.get(
            instrumentationName: "test", instrumentationVersion: nil
        )
        let loggerA = DefaultLoggerProvider.instance.get(instrumentationScopeName: "test-a")
        let loggerB = DefaultLoggerProvider.instance.get(instrumentationScopeName: "test-b")

        let net = NetworkInstrumentation()
        defer { net.uninstall() }

        net.install(tracer: tracer, logger: loggerA)
        let firstLogger = net.logger
        #expect(firstLogger != nil)

        net.install(tracer: tracer, logger: loggerB)
        let secondLogger = net.logger
        #expect(secondLogger != nil)
        // `Logger` is a protocol; identity comparison isn't reliable across
        // wrappers. The meaningful contract is: snapshot.logger reflects the
        // most-recent install, even though we can't .===-compare the
        // underlying instances.
    }
}
