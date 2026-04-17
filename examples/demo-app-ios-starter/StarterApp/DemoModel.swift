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

            // Dual-platform auto-demo mode: when launched with env
            // DASH0_AUTO_DEMO=1 (or with launch arg --auto-demo), the app
            // rotates through Log / Trace / Metric emission every few seconds
            // so a companion script can run Android + iOS side-by-side and
            // emit continuously without needing UI automation. Opt-in ONLY.
            if ProcessInfo.processInfo.environment["DASH0_AUTO_DEMO"] == "1"
                || CommandLine.arguments.contains("--auto-demo") {
                self.startAutoDemo()
            }
        }
    }

    // MARK: - Auto demo loop (for parallel Android+iOS scripts)

    @MainActor
    private func startAutoDemo() {
        guard mobile != nil else {
            status = "auto-demo: SDK not started, aborting"
            return
        }
        status = "auto-demo: emitting continuously (logs + traces + metrics)"
        Task.detached { [weak self] in
            var i = 0
            while !Task.isCancelled {
                guard let self = self else { return }
                let step = i % 5
                await MainActor.run {
                    switch step {
                    case 0: self.emitInfoLog()
                    case 1: self.emitSimpleSpan()
                    case 2: self.incrementCounter()
                    case 3: self.emitNestedSpan()
                    case 4: self.emitWarnLog(); self.recordHistogram()
                    default: break
                    }
                }
                i += 1
                // ~2 Hz emission — enough to see activity in Dash0
                // without spamming. Aligns with Android demo's 500ms cadence.
                try? await Task.sleep(nanoseconds: 500_000_000)
            }
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

    // NOTE: span "work" happens on a background Task. Never block the main
    // actor with Thread.sleep — that's exactly the kind of thing that makes
    // customers blame the SDK for UI jank.

    func emitSimpleSpan() {
        guard let tracer = mobile?.tracer else { return }
        let index = spansEmitted + 1
        spansEmitted += 1
        Task.detached {
            let span = tracer.spanBuilder(spanName: "user.action").startSpan()
            span.setAttribute(key: "ui.action", value: "simple_span_button")
            span.setAttribute(key: "action.index", value: index)
            try? await Task.sleep(nanoseconds: 50_000_000) // 50ms off main
            span.end()
        }
    }

    func emitNestedSpan() {
        guard let tracer = mobile?.tracer else { return }
        spansEmitted += 4
        Task.detached {
            let parent = tracer.spanBuilder(spanName: "ui.workflow.checkout").startSpan()
            parent.setAttribute(key: "workflow.step", value: "checkout")
            parent.setAttribute(key: "user.id", value: "demo-user-42")

            let fetch = tracer.spanBuilder(spanName: "network.fetch_cart")
                .setParent(parent).startSpan()
            fetch.setAttribute(key: "http.method", value: "GET")
            fetch.setAttribute(key: "http.route", value: "/api/cart")
            try? await Task.sleep(nanoseconds: 80_000_000)
            fetch.end()

            let render = tracer.spanBuilder(spanName: "ui.render_cart")
                .setParent(parent).startSpan()
            render.setAttribute(key: "render.items", value: 3)
            try? await Task.sleep(nanoseconds: 30_000_000)
            render.end()

            let analytics = tracer.spanBuilder(spanName: "analytics.report_view")
                .setParent(parent).startSpan()
            analytics.setAttribute(key: "analytics.provider", value: "dash0")
            try? await Task.sleep(nanoseconds: 20_000_000)
            analytics.end()

            parent.end()
        }
    }

    func emitErrorSpan() {
        guard let tracer = mobile?.tracer else { return }
        spansEmitted += 1
        Task.detached {
            let span = tracer.spanBuilder(spanName: "checkout.payment").startSpan()
            span.setAttribute(key: "payment.method", value: "card")
            span.setAttribute(key: "payment.amount", value: 99.99)
            span.setAttribute(key: "payment.currency", value: "USD")
            try? await Task.sleep(nanoseconds: 100_000_000)
            span.status = .error(description: "card declined")
            span.end()
        }
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
