# Upstream bug: `ViewRegistry.findViews` silently drops metrics when no explicit view is registered

This is issue-ready text to file against [`open-telemetry/opentelemetry-swift-core`](https://github.com/open-telemetry/opentelemetry-swift). Copy sections verbatim into the GitHub issue body.

---

## Title

`MeterProviderSdk` silently drops all metric data when no `View` is explicitly registered

## What version of OpenTelemetry are you using?

`opentelemetry-swift-core` 2.x, verified on the current `main` at the time of filing (commit SHA to be filled in at filing time).

## What language layer and version are you using?

Swift 5.9, iOS 16+, macOS 13+. Reproduced on both platforms.

## What did you do?

Build a `MeterProviderSdk` the canonical way — one metric reader, no explicit `View` registration — then record against a counter:

```swift
import OpenTelemetryApi
import OpenTelemetrySdk

let exporter = MyInMemoryMetricExporter()  // any MetricExporter; see repro below
let reader = PeriodicMetricReaderBuilder(exporter: exporter)
    .setInterval(timeInterval: 3600)       // long; we call forceFlush manually
    .build()

let meterProvider = MeterProviderSdk.builder()
    .setResource(resource: Resource(attributes: ["service.name": .string("demo")]))
    .registerMetricReader(reader: reader)
    .build()

let meter = meterProvider.get(name: "demo")
let counter = meter.counterBuilder(name: "demo.counter").build()
counter.add(value: 7, attributes: ["tag": .string("x")])

_ = meterProvider.forceFlush()
```

## What did you expect to see?

The exporter receives a `MetricData` for `demo.counter` with the recorded value, in the default aggregation for a counter (sum, cumulative).

## What did you see instead?

The exporter's `export(metrics:)` is called with an empty array, OR never called at all. No metrics reach the wire. No error is logged. The behavior is silent data loss.

Adding a catch-all `.registerView(...)` call fixes it:

```swift
let meterProvider = MeterProviderSdk.builder()
    .setResource(resource: ...)
    .registerMetricReader(reader: reader)
    .registerView(
        selector: InstrumentSelector.builder().setInstrument(name: ".*").build(),
        view: View.builder().build()
    )
    .build()
```

## Root cause

`ViewRegistry.findViews` in `Sources/OpenTelemetrySdk/Metrics/View/ViewRegistry.swift` only consults the `registeredViews` array:

```swift
public func findViews(descriptor: InstrumentDescriptor, meterScope: InstrumentationScopeInfo) -> [RegisteredView] {
    return registeredViews.filter { view in
        // ... selector matching ...
    }
}
```

`ViewRegistry.init` builds an `instrumentDefaultRegisteredView` dict keyed by `InstrumentType`, populated from the reader's `aggregationSelector.getDefaultAggregation(for: type)`. This field is written in `init` and never read by any method on the class. It's dead code.

Consequence: when `registeredViews` is empty (the default for any caller who doesn't explicitly register a view), `findViews` returns `[]` for every instrument. `MeterSharedState.registerSynchronousMetricStorage` then builds an empty `MultiWritableMetricStorage` and returns it. Every subsequent `counter.add()` / `histogram.record()` writes to a no-op storage. The periodic reader collects zero `MetricData` each cycle and its `doRun()` short-circuits on the `if metricData.isEmpty { return .success }` guard before calling the exporter.

## Proposed fix

When `findViews` would return an empty list, fall back to the per-instrument-type default that `init` already built:

```swift
public func findViews(descriptor: InstrumentDescriptor, meterScope: InstrumentationScopeInfo) -> [RegisteredView] {
    let matches = registeredViews.filter { view in
        // existing selector matching
    }
    if !matches.isEmpty {
        return matches
    }
    // Fall back to the default view for this instrument type so callers
    // that don't explicitly register a view still get storage created.
    if let defaultView = instrumentDefaultRegisteredView[descriptor.type] {
        return [defaultView]
    }
    return []
}
```

This matches the behavior documented in the OTel metrics SDK spec, which specifies that "if no View matches... the default View should be used" — currently the Swift implementation silently diverges.

## Minimal reproduction

A self-contained test that fails without the fix and passes with it:

```swift
@Test
func metricsDropSilentlyWithoutExplicitView() {
    let exporter = InMemoryMetricExporter()
    let reader = PeriodicMetricReaderBuilder(exporter: exporter)
        .setInterval(timeInterval: 3600).build()
    let meterProvider = MeterProviderSdk.builder()
        .setResource(resource: Resource(attributes: [
            "service.name": .string("demo"),
        ]))
        .registerMetricReader(reader: reader)
        .build()

    let meter = meterProvider.get(name: "demo")
    let counter = meter.counterBuilder(name: "demo.counter").build()
    counter.add(value: 99)

    _ = meterProvider.forceFlush()

    #expect(!exporter.capturedMetricNames.isEmpty,
        "counter value should reach the exporter without requiring a catch-all view")
}

private final class InMemoryMetricExporter: MetricExporter, @unchecked Sendable {
    private(set) var capturedMetricNames: [String] = []
    func export(metrics: [MetricData]) -> ExportResult {
        capturedMetricNames.append(contentsOf: metrics.map { $0.name })
        return .success
    }
    func flush() -> ExportResult { .success }
    func shutdown() -> ExportResult { .success }
    func getAggregationTemporality(for instrument: InstrumentType) -> AggregationTemporality { .cumulative }
    func getDefaultAggregation(for instrument: InstrumentType) -> Aggregation { Aggregations.defaultAggregation() }
}
```

## Impact

Every caller who builds a `MeterProviderSdk` the canonical way and doesn't know to register a catch-all view loses 100% of their metrics with no error signal. We found this while porting iOS metrics to our Mobile Observability SDK and ended up shipping a workaround with a regression test that documents the bug surface — the test flips to "failing" the day this is fixed upstream, and we can remove the workaround. Happy to contribute the fix and the regression test upstream.

## Workaround

Register a catch-all view:

```swift
.registerView(
    selector: InstrumentSelector.builder().setInstrument(name: ".*").build(),
    view: View.builder().build()
)
```
