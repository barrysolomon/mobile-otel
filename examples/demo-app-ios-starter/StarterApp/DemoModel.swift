import Foundation
import OpenTelemetryApi
import OpenTelemetrySdk
import OTelMobileSDK
import OTelMobileCore
import ErrorsInstrumentation

@MainActor
final class DemoModel: ObservableObject {
    @Published var status: String = "Starting..."
    @Published var datasetName: String = "(not loaded)"
    @Published var logsEmitted: Int = 0
    @Published var spansEmitted: Int = 0
    @Published var metricsEmitted: Int = 0
    @Published var networkCalls: Int = 0
    @Published var deviceStatsOn: Bool = false
    @Published var lastFlushResult: String = ""

    private(set) var mobile: OTelMobile?

    // Lazy-constructed metric instruments (built on first press).
    private var buttonCounter: LongCounter?
    private var requestHistogram: DoubleHistogram?

    init() {
        Task { @MainActor in
            let result = OTelMobileBootstrap.start()
            self.mobile = result.mobile
            self.datasetName = result.config?.dataset ?? "(not loaded)"
            self.status = result.mobile != nil
                ? "SDK ready — interact below to generate telemetry"
                : result.status
        }
    }

    // MARK: - Resource attributes (for UI display)

    var resourceAttributes: [(key: String, value: String)] {
        guard let resource = mobile?.resource else { return [] }
        return resource.attributes
            .compactMap { (key, value) -> (String, String)? in
                if case .string(let s) = value { return (key, s) }
                if case .int(let i) = value { return (key, String(i)) }
                if case .double(let d) = value { return (key, String(d)) }
                if case .bool(let b) = value { return (key, String(b)) }
                return nil
            }
            .sorted { $0.0 < $1.0 }
    }

    // MARK: - Logs

    func emitInfoLog() {
        logsEmitted += 1
        mobile?.emit(
            body: "user.button_tap",
            severity: .info,
            attributes: [
                "ui.element": .string("info_button"),
                "log.index": .int(logsEmitted),
            ]
        )
    }

    func emitWarnLog() {
        logsEmitted += 1
        mobile?.emit(
            body: "user.form_validation_failed",
            severity: .warn,
            attributes: [
                "form.name": .string("signup"),
                "log.index": .int(logsEmitted),
            ]
        )
    }

    func emitErrorLog() {
        logsEmitted += 1
        mobile?.emit(
            body: "error.simulated",
            severity: .error,
            attributes: [
                "error.type": .string("DemoSimulated"),
                "log.index": .int(logsEmitted),
            ]
        )
    }

    // MARK: - Traces

    func emitSimpleSpan() {
        guard let tracer = mobile?.tracer else { return }
        let span = tracer.spanBuilder(spanName: "user.action").startSpan()
        span.setAttribute(key: "ui.action", value: "simple_span_button")
        span.setAttribute(key: "action.index", value: spansEmitted + 1)
        // Simulate 50ms of work
        Thread.sleep(forTimeInterval: 0.05)
        span.end()
        spansEmitted += 1
    }

    func emitNestedSpan() {
        guard let tracer = mobile?.tracer else { return }

        let parent = tracer.spanBuilder(spanName: "ui.workflow.checkout").startSpan()
        parent.setAttribute(key: "workflow.step", value: "checkout")
        parent.setAttribute(key: "user.id", value: "demo-user-42")

        // Child 1: fetch cart (80ms)
        let fetch = tracer.spanBuilder(spanName: "network.fetch_cart")
            .setParent(parent)
            .startSpan()
        fetch.setAttribute(key: "http.method", value: "GET")
        fetch.setAttribute(key: "http.route", value: "/api/cart")
        Thread.sleep(forTimeInterval: 0.08)
        fetch.end()

        // Child 2: render cart (30ms)
        let render = tracer.spanBuilder(spanName: "ui.render_cart")
            .setParent(parent)
            .startSpan()
        render.setAttribute(key: "render.items", value: 3)
        Thread.sleep(forTimeInterval: 0.03)
        render.end()

        // Child 3: analytics (20ms)
        let analytics = tracer.spanBuilder(spanName: "analytics.report_view")
            .setParent(parent)
            .startSpan()
        analytics.setAttribute(key: "analytics.provider", value: "dash0")
        Thread.sleep(forTimeInterval: 0.02)
        analytics.end()

        parent.end()

        spansEmitted += 4
    }

    func emitErrorSpan() {
        guard let tracer = mobile?.tracer else { return }

        let span = tracer.spanBuilder(spanName: "checkout.payment").startSpan()
        span.setAttribute(key: "payment.method", value: "card")
        span.setAttribute(key: "payment.amount", value: 99.99)
        span.setAttribute(key: "payment.currency", value: "USD")
        Thread.sleep(forTimeInterval: 0.1)
        span.status = .error(description: "card declined")
        span.end()
        spansEmitted += 1
    }

    // MARK: - Metrics

    func incrementCounter() {
        guard let meter = mobile?.meter else { return }
        if buttonCounter == nil {
            buttonCounter = meter.counterBuilder(name: "demo.button_press").build()
        }
        buttonCounter?.add(value: 1, attributes: ["button": .string("counter")])
        metricsEmitted += 1
    }

    func recordHistogram() {
        guard let meter = mobile?.meter else { return }
        if requestHistogram == nil {
            requestHistogram = meter.histogramBuilder(name: "demo.request_duration_ms").build()
        }
        let value = Double.random(in: 50...500)
        requestHistogram?.record(value: value, attributes: ["endpoint": .string("/api/demo")])
        metricsEmitted += 1
    }

    // MARK: - Errors (auto-instrumented via ErrorsInstrumentation)

    func recordCaughtError() {
        struct DemoError: Error { let code: Int }
        let err = DemoError(code: 42)
        ErrorsInstrumentation.shared.recordError(err, attributes: [
            "demo.source": .string("caught_error_button"),
        ])
    }

    /// Intentionally crashes the process. Relaunching the app emits `app.crash`
    /// with the saved stack via the crash-marker recovery path.
    func crashNow() {
        fatalError("Demo: user tapped Crash Now")
    }

    // MARK: - Network (auto-instrumented via NetworkInstrumentation)

    func fetchHttpbinJson() {
        networkCalls += 1
        Task.detached {
            let url = URL(string: "https://httpbin.org/json")!
            _ = try? await URLSession.shared.data(from: url)
        }
    }

    func fetchHttpbin5xx() {
        networkCalls += 1
        Task.detached {
            let url = URL(string: "https://httpbin.org/status/500")!
            _ = try? await URLSession.shared.data(from: url)
        }
    }

    // MARK: - Device stats

    func toggleDeviceStats() {
        guard let mobile = mobile, let meter = mobile.meter else {
            status = "Meter unavailable — can't start device stats"
            return
        }
        if deviceStatsOn {
            mobile.deviceStats.stop()
            deviceStatsOn = false
            status = "Device stats collection stopped"
        } else {
            mobile.deviceStats.start(meter: meter, intervalSeconds: 5)
            deviceStatsOn = true
            status = "Device stats collecting every 5s (memory, battery, thermal, storage)"
        }
    }

    // MARK: - Flush

    func forceFlush() {
        guard let result = mobile?.forceFlush() else {
            lastFlushResult = "no SDK"
            return
        }
        lastFlushResult = "\(result)"
        status = "Flush: \(result) — batch processors will also stream on their own cadence"
    }
}
