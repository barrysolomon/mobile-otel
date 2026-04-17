import Foundation
import OpenTelemetryApi
import OpenTelemetrySdk
import OTelMobileCore
import NetworkInstrumentation
import LifecycleInstrumentation
import ErrorsInstrumentation
// ScreenInstrumentation import kept for future re-enable — see TODO in start(config:).
import ScreenInstrumentation

/// Public entry point for the Dash0 Mobile Observability iOS SDK.
///
/// Thin-slice implementation: no UIApplication lifecycle, no auto-instrumentation.
/// Callers invoke `emit(body:severity:)` manually. The buffer → exporter
/// pipeline is fully wired and exercised by the end-to-end smoke test.
///
/// As of this release the SDK wires all three OTel signals — logs, traces,
/// and metrics — sharing a single `Resource` built by `ResourceBuilder`. The
/// `tracer`, `meter` and `resource` properties expose the underlying
/// OpenTelemetry handles so application code can create spans or record
/// metrics directly through the public API.
public final class OTelMobile: @unchecked Sendable {
    private let processor: MobileLogRecordProcessor
    /// OpenTelemetry logger for the `io.dash0.mobile` instrumentation scope.
    /// Exposed so instrumentation modules (lifecycle, errors, etc.) and
    /// application code can emit log records directly.
    public let logger: Logger
    public let config: MobileConfig
    public let sessionProvider: SessionProvider

    /// Shared resource carried by every log, span, and metric emitted through
    /// this SDK instance. Exposed so callers can inspect it (e.g. in tests,
    /// debug UIs, or log enrichers).
    public let resource: Resource

    /// OpenTelemetry tracer for the `io.dash0.mobile` instrumentation scope.
    /// Use this to create spans from application code. `nil` when the test
    /// overload `start(config:exporter:)` is used — that path doesn't build a
    /// TracerProvider.
    public let tracer: Tracer?

    /// OpenTelemetry meter for the `io.dash0.mobile` instrumentation scope.
    /// Use this to record custom metrics. `nil` under the test overload.
    public let meter: MeterSdk?

    /// Device stats collector. Call `deviceStats.start(meter:)` to begin
    /// recording device health gauges; `stop()` to pause. Always non-nil;
    /// when `meter` is nil the collector cannot be started.
    public let deviceStats: DeviceStatsCollector

    /// Underlying trace/meter providers. Held so `forceFlush()` can drain
    /// their batch processors / periodic readers on demand. Optional because
    /// the test-overload `start(config:exporter:)` doesn't wire them.
    private let tracerProvider: TracerProviderSdk?
    private let meterProvider: MeterProviderSdk?

    private init(
        config: MobileConfig,
        processor: MobileLogRecordProcessor,
        logger: Logger,
        sessionProvider: SessionProvider,
        resource: Resource,
        tracer: Tracer?,
        meter: MeterSdk?,
        deviceStats: DeviceStatsCollector,
        tracerProvider: TracerProviderSdk? = nil,
        meterProvider: MeterProviderSdk? = nil
    ) {
        self.config = config
        self.processor = processor
        self.logger = logger
        self.sessionProvider = sessionProvider
        self.resource = resource
        self.tracer = tracer
        self.meter = meter
        self.deviceStats = deviceStats
        self.tracerProvider = tracerProvider
        self.meterProvider = meterProvider
    }

    /// Wires the SDK with a caller-supplied `BufferedEventExporter`.
    /// Use this overload from tests, demos, and application code that already
    /// owns an exporter (e.g. an OTLP/gRPC adapter).
    ///
    /// Traces and metrics are NOT wired on this path — the exporter here is a
    /// log/event adapter. Callers that need traces or metrics should use the
    /// production `start(config:)` entry point.
    public static func start(
        config: MobileConfig,
        exporter: BufferedEventExporter
    ) throws -> OTelMobile {
        let sessionProvider = StaticSessionProvider()
        let buffer = RAMEventBuffer(capacity: config.bufferConfig.ramEvents)

        let processor = MobileLogRecordProcessor(
            buffer: buffer,
            exporter: exporter,
            sessionProvider: sessionProvider
        )

        // Wire OTel-Swift's LoggerProvider through our processor so that
        // `logger.logRecordBuilder().emit()` lands in the buffer.
        let resource = ResourceBuilder.buildMobileResource(
            serviceName: config.serviceName,
            serviceVersion: config.serviceVersion
        )
        let loggerProvider = LoggerProviderBuilder()
            .with(resource: resource)
            .with(processors: [processor])
            .build()
        let logger = loggerProvider.get(instrumentationScopeName: "io.dash0.mobile")

        return OTelMobile(
            config: config,
            processor: processor,
            logger: logger,
            sessionProvider: sessionProvider,
            resource: resource,
            tracer: nil,
            meter: nil,
            deviceStats: DeviceStatsCollector()
        )
    }

    /// Production wiring: builds OTel-Swift's OTLP/HTTP log, trace, and
    /// metric exporters against `config.endpoint` (with optional Bearer auth
    /// from `config.authToken`), assembles each exporter with an appropriate
    /// batch/periodic reader, and registers everything against three
    /// providers (Logger, Tracer, Meter) sharing a single `Resource`.
    ///
    /// - For logs, we register BOTH our buffer processor AND the OTLP batch
    ///   processor on the LoggerProvider. Each `emit` is fanned out: the
    ///   buffer captures for selective-flush/replay semantics, and the batch
    ///   processor streams to the real collector.
    /// - For traces, a single `BatchSpanProcessor` exports to OTLP.
    /// - For metrics, a `PeriodicMetricReaderBuilder` is driven by the
    ///   `MeterProvider` internals at a 10s cadence.
    ///
    /// The injectable-exporter overload `start(config:exporter:)` remains
    /// available for unit tests and bespoke transport adapters.
    public static func start(config: MobileConfig) throws -> OTelMobile {
        let sessionProvider = StaticSessionProvider()
        let buffer = RAMEventBuffer(capacity: config.bufferConfig.ramEvents)

        // Build the OTLP exporters — one per signal. Each handles its own
        // URL normalisation (`/v1/logs`, `/v1/traces`, `/v1/metrics`).
        let otlpLogExporter = try OTLPExporterFactory.makeHttpLogExporter(
            endpoint: config.endpoint,
            authToken: config.authToken,
            extraHeaders: config.extraHeaders
        )

        // OTel-native buffer pipeline: selective / force flush drains
        // buffered `ReadableLogRecord`s through the same OTLP/HTTP exporter
        // the batch processor uses. No custom JSON encoding.
        let bufferProcessor = MobileLogRecordProcessor(
            buffer: buffer,
            otelExporter: otlpLogExporter,
            sessionProvider: sessionProvider
        )
        let otlpTraceExporter = try OTLPExporterFactory.makeHttpTraceExporter(
            endpoint: config.endpoint,
            authToken: config.authToken,
            extraHeaders: config.extraHeaders
        )
        let otlpMetricExporter = try OTLPExporterFactory.makeHttpMetricExporter(
            endpoint: config.endpoint,
            authToken: config.authToken,
            extraHeaders: config.extraHeaders
        )

        // Small schedule delay for demo visibility — production deployments
        // can tune this up; 2s gives crisp turnaround during live demos
        // without being so aggressive that we spam the collector.
        let batchLogProcessor = BatchLogRecordProcessor(
            logRecordExporter: otlpLogExporter,
            scheduleDelay: 2,
            exportTimeout: 30,
            maxQueueSize: 2048,
            maxExportBatchSize: 512
        )
        let batchSpanProcessor = BatchSpanProcessor(
            spanExporter: otlpTraceExporter,
            scheduleDelay: 2,
            exportTimeout: 30,
            maxQueueSize: 2048,
            maxExportBatchSize: 512
        )
        // Metrics are naturally periodic — a 10s cadence balances freshness
        // and overhead for device health gauges.
        let metricReader = PeriodicMetricReaderBuilder(exporter: otlpMetricExporter)
            .setInterval(timeInterval: 10)
            .build()

        let resource = ResourceBuilder.buildMobileResource(
            serviceName: config.serviceName,
            serviceVersion: config.serviceVersion
        )

        // `with(processors:)` appends — both processors receive every
        // emitted log record.
        let loggerProvider = LoggerProviderBuilder()
            .with(resource: resource)
            .with(processors: [bufferProcessor, batchLogProcessor])
            .build()
        let logger = loggerProvider.get(instrumentationScopeName: "io.dash0.mobile")

        let tracerProvider = TracerProviderBuilder()
            .with(resource: resource)
            .add(spanProcessor: batchSpanProcessor)
            .build()
        let tracer = tracerProvider.get(
            instrumentationName: "io.dash0.mobile",
            instrumentationVersion: ResourceBuilder.sdkVersion
        )

        // MeterProviderSdk.builder() returns a NoopMeterProviderBuilder which
        // only transitions to the real MeterProviderBuilder once a metric
        // reader is registered. See NoopMeterProviderBuilder.swift in
        // opentelemetry-swift-core.
        let meterProvider = MeterProviderSdk.builder()
            .setResource(resource: resource)
            .registerMetricReader(reader: metricReader)
            .build()
        let meter = meterProvider.get(name: "io.dash0.mobile")

        let instance = OTelMobile(
            config: config,
            processor: bufferProcessor,
            logger: logger,
            sessionProvider: sessionProvider,
            resource: resource,
            tracer: tracer,
            meter: meter,
            deviceStats: DeviceStatsCollector(),
            tracerProvider: tracerProvider,
            meterProvider: meterProvider
        )

        // Auto-install instrumentation modules based on config.autoCaptureOptions.
        // Matches the Android SDK's "all-on-by-default" UX: one OTelMobile.start
        // call wires logs + traces + metrics + network + lifecycle + errors without
        // any further setup from app code. Opt out by tailoring
        // `autoCaptureOptions` in MobileConfig.
        //
        // IMPORTANT: these installs must happen AFTER the SwiftUI scene has
        // initialized its own UIWindow and URL-loading machinery. Doing them
        // synchronously during OTelMobile.start() (which callers invoke from
        // App.init / @StateObject closures) leaves SwiftUI stuck on the launch
        // screen — URLSessionConfiguration.protocolClasses swizzle + signal
        // handlers + NSException handler setup collectively race with UIKit's
        // scene setup. Deferring to the main queue's next tick lets the first
        // SwiftUI render complete first.
        let opts = config.autoCaptureOptions
        DispatchQueue.main.async {
            if opts.contains(.network) {
                NetworkInstrumentation.shared.install(tracer: tracer)
            }
            if opts.contains(.lifecycle) {
                LifecycleInstrumentation.shared.install(tracer: tracer, logger: logger)
            }
            if opts.contains(.errors) {
                ErrorsInstrumentation.shared.install(logger: logger)
            }
            // TODO: ScreenInstrumentation's UIViewController swizzle needs a
            // safer install path (SwiftUI's UIHostingController hierarchy is
            // sensitive to viewDidAppear/Disappear swizzles). Re-enable once we
            // use a ViewModifier-based SwiftUI integration + optional UIKit
            // swizzle gated by opt-in.
            // if opts.contains(.screen) {
            //     ScreenInstrumentation.shared.install(tracer: tracer, logger: logger)
            // }
        }

        return instance
    }

    // MARK: - Public API

    /// Emit a log event with an optional severity. Thin-slice convenience —
    /// real instrumentation modules will land in subsequent tasks.
    public func emit(body: String, severity: Severity = .info) {
        emit(body: body, severity: severity, attributes: [:])
    }

    /// Emit a log event with attributes attached. Use this when you want the
    /// log record to carry structured metadata searchable in the backend.
    public func emit(body: String, severity: Severity, attributes: [String: AttributeValue]) {
        var builder = logger.logRecordBuilder()
            .setBody(AttributeValue.string(body))
            .setSeverity(severity)
        if !attributes.isEmpty {
            builder = builder.setAttributes(attributes)
        }
        builder.emit()
    }

    /// Synchronously flush every pending signal: the internal log buffer,
    /// the batch span processor, and the periodic metric reader. Returns the
    /// log-buffer result (success or failure with reason). Trace and metric
    /// flush errors are logged but not surfaced here — batch processors
    /// retry on their own cadence regardless.
    @discardableResult
    public func forceFlush() -> BufferExportResult {
        // Traces: synchronous call, no return value.
        tracerProvider?.forceFlush()
        // Metrics: fires the periodic reader's export path immediately.
        _ = meterProvider?.forceFlush()
        // Logs buffer: our dual-tier pipeline.
        return processor.forceFlushBuffered()
    }

    /// Selective time-window flush: export events from the last `minutes`.
    @discardableResult
    public func flushWindow(minutes: UInt64) async -> BufferExportResult {
        await processor.flushWindow(minutes: minutes)
    }
}
