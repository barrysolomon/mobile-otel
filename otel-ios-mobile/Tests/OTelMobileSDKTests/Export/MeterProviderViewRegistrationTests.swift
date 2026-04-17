import Testing
@testable import OTelMobileSDK
import OpenTelemetryApi
import OpenTelemetrySdk

/// Regression test for an `opentelemetry-swift-core` bug: `ViewRegistry.findViews`
/// only consults the explicit `registeredViews` list — the per-instrument-type
/// defaults built in `ViewRegistry.init` are dead code. Without at least one
/// registered view, `registerSynchronousMetricStorage` creates zero storages,
/// so `counter.add()` / `histogram.record()` silently drop every value.
///
/// `OTelMobile.start(config:)` works around this by registering a catch-all
/// view at `MeterProviderBuilder` time. This test reproduces that exact builder
/// chain against an in-memory capturing exporter and asserts that:
///
///   1. Counter + histogram values recorded via the SDK reach the exporter.
///   2. Dropping the catch-all view would re-introduce the silent drop (the
///      `regression` test case documents that bug surface).
///
/// If upstream fixes `ViewRegistry.findViews`, the `regression` test will
/// start failing — that's our signal to remove the workaround.
@Suite("MeterProviderViewRegistration")
struct MeterProviderViewRegistrationTests {
    @Test("counter + histogram export with catch-all view registered")
    func exportsWhenCatchAllViewRegistered() {
        let exporter = InMemoryMetricExporter()
        let reader = PeriodicMetricReaderBuilder(exporter: exporter)
            .setInterval(timeInterval: 3600) // long — we call forceFlush instead
            .build()

        let resource = Resource(attributes: [
            "service.name": .string("meter-view-test"),
        ])
        let meterProvider = MeterProviderSdk.builder()
            .setResource(resource: resource)
            .registerMetricReader(reader: reader)
            .registerView(
                selector: InstrumentSelector.builder()
                    .setInstrument(name: ".*")
                    .build(),
                view: View.builder().build()
            )
            .build()

        let meter = meterProvider.get(name: "io.dash0.mobile.test")
        let counter = meter.counterBuilder(name: "test.counter").build()
        counter.add(value: 7, attributes: ["tag": .string("x")])

        let histogram = meter.histogramBuilder(name: "test.histogram").build()
        histogram.record(value: 42.5, attributes: ["tag": .string("y")])

        _ = meterProvider.forceFlush()

        let names = Set(exporter.capturedMetricNames)
        #expect(names.contains("test.counter"))
        #expect(names.contains("test.histogram"))
    }

    /// Dropping `registerView(...)` takes us back to the upstream behaviour:
    /// zero storages registered, every write a no-op. If this test ever
    /// starts failing (exporter receives metrics even without the explicit
    /// view), upstream has fixed `ViewRegistry.findViews` and the workaround
    /// in `OTelMobile.start(config:)` can be removed.
    @Test("regression: no export without catch-all view (upstream ViewRegistry bug)")
    func regressionNoExportWithoutView() {
        let exporter = InMemoryMetricExporter()
        let reader = PeriodicMetricReaderBuilder(exporter: exporter)
            .setInterval(timeInterval: 3600)
            .build()

        let meterProvider = MeterProviderSdk.builder()
            .setResource(resource: Resource(attributes: [
                "service.name": .string("meter-view-test"),
            ]))
            .registerMetricReader(reader: reader)
            // Intentionally NO registerView(...) — this is the bug surface.
            .build()

        let meter = meterProvider.get(name: "io.dash0.mobile.test")
        let counter = meter.counterBuilder(name: "silent.counter").build()
        counter.add(value: 99)

        _ = meterProvider.forceFlush()

        #expect(exporter.capturedMetricNames.isEmpty,
                "upstream ViewRegistry.findViews now falls back to defaults — drop the catch-all view workaround in OTelMobile.start(config:)")
    }
}

/// Minimal in-memory `MetricExporter` for unit tests. Single-threaded — we
/// flush synchronously on the test thread, so no locking is needed.
private final class InMemoryMetricExporter: MetricExporter, @unchecked Sendable {
    private(set) var capturedMetricNames: [String] = []

    func export(metrics: [MetricData]) -> ExportResult {
        capturedMetricNames.append(contentsOf: metrics.map { $0.name })
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
