import Testing
@testable import OTelMobileSDK
import OpenTelemetryApi
import OpenTelemetrySdk

/// Verifies that the SDK-state self-telemetry gauges (`sdk.enabled`,
/// `sdk.sample_rate`) are emitted by `SDKStateGaugeCollector` WITHOUT any
/// dependency on `DeviceStatsCollector` / the `.deviceStats` capture option.
///
/// This pins the kill-switch observability invariant from
/// `docs/design/remote-kill-switch.md`: an operator who disables `.deviceStats`
/// must still be able to see whether the SDK is remotely enabled and at what
/// sample rate. Previously the gauges were built inside `DeviceStatsCollector`
/// behind the `.deviceStats` gate, so disabling device stats made a disabled
/// fleet invisible.
@Suite("SDKStateGaugeCollector")
struct SDKStateGaugeCollectorTests {

    /// Build a meter wired to an in-memory exporter, mirroring the catch-all
    /// view registration `OTelMobile.start(config:)` performs (required for the
    /// upstream ViewRegistry workaround — see MeterProviderViewRegistrationTests).
    private func makeMeter(_ exporter: InMemoryMetricExporter) -> (MeterProviderSdk, MeterSdk) {
        let reader = PeriodicMetricReaderBuilder(exporter: exporter)
            .setInterval(timeInterval: 3600) // long — we forceFlush manually
            .build()
        let provider = MeterProviderSdk.builder()
            .setResource(resource: Resource(attributes: [
                "service.name": .string("sdk-state-gauge-test"),
            ]))
            .registerMetricReader(reader: reader)
            .registerView(
                selector: InstrumentSelector.builder().setInstrument(name: ".*").build(),
                view: View.builder().build()
            )
            .build()
        return (provider, provider.get(name: "io.dash0.mobile.test"))
    }

    @Test("SDK-state gauges emit without .deviceStats (collector run standalone)")
    func gaugesEmitWithoutDeviceStats() {
        let exporter = InMemoryMetricExporter()
        let (provider, meter) = makeMeter(exporter)

        // NB: no DeviceStatsCollector is started here — only the dedicated
        // SDK-state collector. This is exactly the "operator disabled
        // .deviceStats" configuration.
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: true, sampleRate: 1.0))
        let collector = SDKStateGaugeCollector()
        collector.start(meter: meter, remoteGate: gate)
        collector.emitOnce() // emit immediately rather than waiting for the timer
        _ = provider.forceFlush()

        let names = Set(exporter.capturedMetricNames)
        #expect(names.contains("sdk.enabled"))
        #expect(names.contains("sdk.sample_rate"))
        collector.stop()
    }

    @Test("disabled gate still emits sdk.enabled = 0 (observable when remotely disabled)")
    func disabledGateStillObservable() {
        let exporter = InMemoryMetricExporter()
        let (provider, meter) = makeMeter(exporter)

        // Kill switch flipped: enabled = false. The gauges must STILL emit so
        // operators can see the disabled state.
        let gate = RemoteGate(initial: SDKRemoteConfig(enabled: false, sampleRate: 0.0))
        let collector = SDKStateGaugeCollector()
        collector.start(meter: meter, remoteGate: gate)
        collector.emitOnce()
        _ = provider.forceFlush()

        let captured = exporter.capturedMetrics
        let enabled = captured.first { $0.name == "sdk.enabled" }
        #expect(enabled != nil, "sdk.enabled must emit even when the SDK is disabled")
        // The recorded value is the gauge's last data point: 0 (disabled).
        if let point = enabled?.data.points.first {
            #expect(point.value(asLong: true) == 0)
        } else {
            Issue.record("sdk.enabled produced no data point")
        }
        collector.stop()
    }
}

/// Minimal in-memory `MetricExporter` capturing both names and full metric
/// data. Single-threaded — we flush synchronously on the test thread.
private final class InMemoryMetricExporter: MetricExporter, @unchecked Sendable {
    private(set) var capturedMetrics: [MetricData] = []
    var capturedMetricNames: [String] { capturedMetrics.map { $0.name } }

    func export(metrics: [MetricData]) -> ExportResult {
        capturedMetrics.append(contentsOf: metrics)
        return .success
    }

    func flush() -> ExportResult { .success }
    func shutdown() -> ExportResult { .success }

    func getAggregationTemporality(for instrument: InstrumentType) -> AggregationTemporality {
        AggregationTemporality.cumulative
    }

    func getDefaultAggregation(for instrument: InstrumentType) -> Aggregation {
        Aggregations.defaultAggregation()
    }
}

/// Reads a numeric value from a `PointData`, treating it as a long or double.
/// OTel-Swift's `PointData` exposes typed data via subclasses; we accept either.
private extension PointData {
    func value(asLong: Bool) -> Int {
        if let lp = self as? LongPointData { return lp.value }
        if let dp = self as? DoublePointData { return Int(dp.value) }
        return -1
    }
}
