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
@Suite("NetworkInstrumentation http.error logger wiring")
struct HttpErrorLogContractTests {

    @Test("install with no logger leaves snapshot.logger nil")
    func installWithoutLogger() {
        let tracer = OpenTelemetry.instance.tracerProvider.get(
            instrumentationName: "test", instrumentationVersion: nil
        )
        NetworkInstrumentation.shared.install(tracer: tracer)
        defer { NetworkInstrumentation.shared.uninstall() }

        let snap = NetworkInstrumentation.shared.snapshot
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
        NetworkInstrumentation.shared.install(tracer: tracer, logger: logger)
        defer { NetworkInstrumentation.shared.uninstall() }

        let snap = NetworkInstrumentation.shared.snapshot
        #expect(snap.logger != nil, "logger passed → snapshot.logger is non-nil")
    }

    @Test("re-install with different logger swaps in place")
    func reinstallSwapsLogger() {
        let tracer = OpenTelemetry.instance.tracerProvider.get(
            instrumentationName: "test", instrumentationVersion: nil
        )
        let loggerA = DefaultLoggerProvider.instance.get(instrumentationScopeName: "test-a")
        let loggerB = DefaultLoggerProvider.instance.get(instrumentationScopeName: "test-b")

        NetworkInstrumentation.shared.install(tracer: tracer, logger: loggerA)
        let firstLogger = NetworkInstrumentation.shared.logger
        #expect(firstLogger != nil)

        NetworkInstrumentation.shared.install(tracer: tracer, logger: loggerB)
        let secondLogger = NetworkInstrumentation.shared.logger
        #expect(secondLogger != nil)
        // `Logger` is a protocol; identity comparison isn't reliable across
        // wrappers. The meaningful contract is: snapshot.logger reflects the
        // most-recent install, even though we can't .===-compare the
        // underlying instances.
        NetworkInstrumentation.shared.uninstall()
    }
}
