import Foundation
import OpenTelemetryApi
import OpenTelemetrySdk
import OpenTelemetryProtocolExporterHttp
import OTelMobileCore
import NetworkInstrumentation
import LifecycleInstrumentation
import ErrorsInstrumentation
// ScreenInstrumentation import kept for future re-enable — see TODO in start(config:).
import ScreenInstrumentation
import FreezeInstrumentation
import VitalsInstrumentation
#if canImport(UIKit)
import UIKit
#endif

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

    /// Policy evaluator holding the currently-active DSL v2 policies.
    /// Consumers call `policyEvaluator.evaluate(attributes:)` to check an
    /// event against the policy set. Always non-nil; starts empty.
    ///
    /// To feed policies from a gateway, construct a `ConfigPoller` with this
    /// evaluator and call `poller.start()`.
    public let policyEvaluator: PolicyEvaluator

    /// Lazily refreshed live-device context (battery / network / locale /
    /// thermal / device class). Used by the policy evaluator to match
    /// geo/device DSL conditions, and by callers that want to tag their own
    /// events with a consistent device profile.
    public let contextSnapshotProvider: ContextSnapshotProvider

    /// Predictive-export orchestrator. Nil when `config.enablePredictiveExport`
    /// is false.
    public let predictiveExportPolicy: PredictiveExportPolicy?

    /// Applies incoming `FleetAlert` payloads to local actions (flush,
    /// sampling override, screenshot). Always non-nil; the SDK does not
    /// listen for inbound alerts on its own — the host app (or a future
    /// config poller) feeds them via `fleetAlertHandler.handle(alert)`.
    public let fleetAlertHandler: FleetAlertHandler

    /// Underlying trace/meter providers. Held so `forceFlush()` can drain
    /// their batch processors / periodic readers on demand. Optional because
    /// the test-overload `start(config:exporter:)` doesn't wire them.
    private let tracerProvider: TracerProviderSdk?
    private let meterProvider: MeterProviderSdk?

    /// Disk buffer for spans, if configured. Held so the post-start
    /// recovery Task.detached can call `stats()` / `fetchAll()` /
    /// `deleteUpTo(_:)` / `pruneByTTL(_:)` to drain events persisted by a
    /// previous process. Nil when the caller did not pass a
    /// `spanDiskBuffer` — in that case PersistingSpanExporter passes
    /// through transparently and no recovery is needed.
    private let spanDiskBuffer: DiskSpanBuffer?

    /// NotificationCenter observers registered by `start(config:)` so we
    /// can auto-`forceFlush()` on backgrounding/termination. Held on the
    /// instance so they live as long as the SDK does.
    fileprivate var autoFlushObservers: [NSObjectProtocol] = []

    private init(
        config: MobileConfig,
        processor: MobileLogRecordProcessor,
        logger: Logger,
        sessionProvider: SessionProvider,
        resource: Resource,
        tracer: Tracer?,
        meter: MeterSdk?,
        deviceStats: DeviceStatsCollector,
        policyEvaluator: PolicyEvaluator,
        contextSnapshotProvider: ContextSnapshotProvider,
        predictiveExportPolicy: PredictiveExportPolicy?,
        fleetAlertHandler: FleetAlertHandler,
        tracerProvider: TracerProviderSdk? = nil,
        meterProvider: MeterProviderSdk? = nil,
        spanDiskBuffer: DiskSpanBuffer? = nil
    ) {
        self.config = config
        self.processor = processor
        self.logger = logger
        self.sessionProvider = sessionProvider
        self.resource = resource
        self.tracer = tracer
        self.meter = meter
        self.deviceStats = deviceStats
        self.policyEvaluator = policyEvaluator
        self.contextSnapshotProvider = contextSnapshotProvider
        self.predictiveExportPolicy = predictiveExportPolicy
        self.fleetAlertHandler = fleetAlertHandler
        self.tracerProvider = tracerProvider
        self.meterProvider = meterProvider
        self.spanDiskBuffer = spanDiskBuffer
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
            serviceVersion: config.serviceVersion,
            extraAttributes: config.extraResourceAttributes
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
            deviceStats: DeviceStatsCollector(),
            policyEvaluator: PolicyEvaluator(),
            contextSnapshotProvider: ContextSnapshotProvider(),
            predictiveExportPolicy: nil,
            fleetAlertHandler: FleetAlertHandler(flushWindow: { _ in })
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
    ///
    /// - Parameters:
    ///   - config: SDK configuration (endpoint, auth, features, sampling).
    ///   - diskBuffer: Optional sqlite-backed spill buffer for log records.
    ///     When non-nil, RAM-evicted events spill to disk and drain on
    ///     start-time recovery.
    ///   - spanDiskBuffer: Optional sqlite-backed buffer for failed span
    ///     export batches. When non-nil, BatchSpanProcessor failures
    ///     persist to disk and drain on start-time recovery.
    public static func start(
        config: MobileConfig,
        diskBuffer: DiskLogBuffer? = nil,
        spanDiskBuffer: DiskSpanBuffer? = nil
    ) throws -> OTelMobile {
        // SessionManager with UUID rotation on inactivity timeout and
        // UserDefaults persistence. Replaces the earlier StaticSessionProvider
        // (which minted a fresh UUID per-launch and never rotated).
        let sessionProvider = SessionManager()
        let buffer = RAMEventBuffer(capacity: config.bufferConfig.ramEvents)

        // Build the OTLP exporters — one per signal. Each handles its own
        // URL normalisation (`/v1/logs`, `/v1/traces`, `/v1/metrics`).
        //
        // Logs use SynchronousLogRecordExporter — a wrapper that
        // constructs an `OtlpHttpLogExporter` with a blocking
        // `HTTPClient`, so `export(...)` returns the REAL send result
        // instead of the upstream always-`.success` that breaks every
        // downstream retry/persist decorator. See the class doc for why.
        let logsEndpointURL = try OTLPExporterFactory.buildLogsEndpointURL(
            from: config.endpoint
        )
        let baseLogExporter = SynchronousLogRecordExporter(
            endpoint: logsEndpointURL,
            authToken: config.authToken,
            extraHeaders: config.extraHeaders
        )
        // Decorate with RetryableExporter so transient failures get
        // exponential-backoff retries (3 attempts, 1s → 60s ceiling) and
        // every transition publishes through `ExportStatusManager`.
        // Mirrors Android: only the log exporter is wrapped — span and
        // metric retry behaviour stays delegated to the upstream batch
        // processors.
        let otlpLogExporter = RetryableExporter(delegate: baseLogExporter)

        // Policy evaluator is created once and shared between the processor
        // (which consults it on every onEmit) and the OTelMobile instance
        // (which exposes it publicly so a ConfigPoller — or app code — can
        // push policy updates). Starts with an empty policy list; remains
        // empty unless `enablePolicyPolling` is true OR app code calls
        // `mobile.policyEvaluator.updatePolicies(...)` directly.
        let policyEvaluator = PolicyEvaluator()

        // OTel-native buffer pipeline: selective / force flush drains
        // buffered `ReadableLogRecord`s through the same OTLP/HTTP exporter
        // the batch processor uses. No custom JSON encoding.
        //
        // When a `diskBuffer` is supplied, RAM-evicted events are spilled to
        // disk and drained back out on `forceFlushBuffered()` / start-time
        // recovery (see below). Passing `nil` preserves the RAM-only
        // behaviour from earlier releases.
        let bufferProcessor = MobileLogRecordProcessor(
            buffer: buffer,
            otelExporter: otlpLogExporter,
            sessionProvider: sessionProvider,
            diskBuffer: diskBuffer,
            policyEvaluator: policyEvaluator
        )
        // Trace export pipeline: BatchSpanProcessor → OtlpHttpTraceExporter,
        // with an optional PersistingTraceHTTPClient inserted at the HTTP
        // layer when `spanDiskBuffer` is supplied. That client sees every
        // POST outcome (success, network error, 5xx, 429) and spills the
        // raw request body to disk on retryable failures so the next
        // process launch can replay it via recoverSpanRequests.
        //
        // Why not decorate the SpanExporter? Upstream's
        // OtlpHttpTraceExporter.export() returns .success synchronously
        // before the HTTP call completes — failure is only observable in
        // the HTTP callback. A SpanExporter-level decorator sees only
        // .success and cannot trigger persist. See the PersistingTraceHTTPClient
        // doc comment for the full rationale.
        let traceHTTPClient: HTTPClient? = spanDiskBuffer.map { buffer in
            PersistingTraceHTTPClient(
                diskBuffer: buffer,
                sessionProvider: sessionProvider
            )
        }
        let otlpTraceExporter = try OTLPExporterFactory.makeHttpTraceExporter(
            endpoint: config.endpoint,
            authToken: config.authToken,
            extraHeaders: config.extraHeaders,
            httpClient: traceHTTPClient
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
            serviceVersion: config.serviceVersion,
            extraAttributes: config.extraResourceAttributes
        )

        // `with(processors:)` appends — both processors receive every
        // emitted log record.
        let loggerProvider = LoggerProviderBuilder()
            .with(resource: resource)
            .with(processors: [bufferProcessor, batchLogProcessor])
            .build()
        let logger = loggerProvider.get(instrumentationScopeName: "io.dash0.mobile")

        // Build the sampler from MobileConfig. Default is dynamic (10%
        // baseline / 100% for page.* + app.startup) so trace waterfalls
        // stay intact at low rates. Override via `MobileConfig.samplingConfig`.
        let sampler = SamplerFactory.createSampler(config.samplingConfig)
        let tracerProvider = TracerProviderBuilder()
            .with(resource: resource)
            .with(sampler: sampler)
            .add(spanProcessor: batchSpanProcessor)
            .build()
        let tracer = tracerProvider.get(
            instrumentationName: "io.dash0.mobile",
            instrumentationVersion: ResourceBuilder.sdkVersion
        )

        // `MeterProviderSdk.builder()` returns a `NoopMeterProviderBuilder`
        // that only becomes a real `MeterProviderBuilder` after a metric
        // reader is registered — see `NoopMeterProviderBuilder.swift`.
        //
        // The explicit catch-all `registerView(...)` is a workaround for an
        // upstream bug: `ViewRegistry.findViews` only consults the explicit
        // `registeredViews` list. The `instrumentDefaultRegisteredView`
        // defaults it builds in `init` are never read. Without at least one
        // registered view, `registerSynchronousMetricStorage` creates zero
        // storages and every `counter.add()` / `histogram.record()` is
        // silently dropped. A single catch-all view routes every instrument
        // through default aggregation, restoring the expected behaviour.
        //
        // See: `Tests/.../MeterProviderViewRegistrationTests.swift` — if the
        // `regression: no export without catch-all view` case starts failing,
        // upstream has fixed it and this workaround can be removed.
        let meterProvider = MeterProviderSdk.builder()
            .setResource(resource: resource)
            .registerMetricReader(reader: metricReader)
            .registerView(
                selector: InstrumentSelector.builder()
                    .setInstrument(name: ".*")
                    .build(),
                view: View.builder().build()
            )
            .build()
        let meter = meterProvider.get(name: "io.dash0.mobile")

        // A shared `flushWindow` closure used by both PredictiveExportPolicy
        // and FleetAlertHandler. Wraps the async processor call in a
        // detached Task so callers (which live on a DispatchQueue, not an
        // actor) can fire-and-forget.
        let flushWindowClosure: @Sendable (UInt64) -> Void = { [bufferProcessor] minutes in
            Task.detached {
                _ = await bufferProcessor.flushWindow(minutes: minutes)
            }
        }

        let contextSnapshotProvider = ContextSnapshotProvider()
        let fleetAlertHandler = FleetAlertHandler(flushWindow: flushWindowClosure)
        let predictivePolicy: PredictiveExportPolicy?
        if config.enablePredictiveExport {
            predictivePolicy = PredictiveExportPolicy(
                config: .init(
                    intervalSeconds: config.predictiveExportIntervalSeconds
                ),
                logger: logger,
                flushWindow: flushWindowClosure
            )
        } else {
            predictivePolicy = nil
        }

        let instance = OTelMobile(
            config: config,
            processor: bufferProcessor,
            logger: logger,
            sessionProvider: sessionProvider,
            resource: resource,
            tracer: tracer,
            meter: meter,
            deviceStats: DeviceStatsCollector(),
            policyEvaluator: policyEvaluator,
            contextSnapshotProvider: contextSnapshotProvider,
            predictiveExportPolicy: predictivePolicy,
            fleetAlertHandler: fleetAlertHandler,
            tracerProvider: tracerProvider,
            meterProvider: meterProvider,
            spanDiskBuffer: spanDiskBuffer
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
        let networkConfig = Self.makeNetworkConfig(endpoint: config.endpoint)
        // Install NetworkInstrumentation SYNCHRONOUSLY — it's just an
        // Obj-C runtime swizzle + URLProtocol.registerClass, no UIKit
        // touches, no signal handlers. Deferring it via
        // DispatchQueue.main.async meant any URLSession request the app
        // fired before the first main-queue tick — including anything in
        // App.init, .onAppear, or synchronous bootstrap — completed
        // through a non-swizzled URLSession and was silently uncaptured.
        if opts.contains(.network) {
            NetworkInstrumentation.shared.install(tracer: tracer, config: networkConfig)
        }
        DispatchQueue.main.async {
            if opts.contains(.lifecycle) {
                LifecycleInstrumentation.shared.install(tracer: tracer, logger: logger)
            }
            if opts.contains(.errors) {
                ErrorsInstrumentation.shared.install(logger: logger)
            }
            if opts.contains(.freeze) {
                FreezeInstrumentation.shared.install(logger: logger)
            }
            if opts.contains(.vitals) {
                VitalsInstrumentation.shared.install(logger: logger)
                // AppStartInstrumentation rides under the .vitals
                // capability — same conceptual area, but emits SPANS
                // (cold/warm/startup) so the trace timeline carries
                // launch context. The `app.startup` span name is
                // intentionally what `DynamicSampler` boosts to
                // high-priority — keeps startup spans in the sampled
                // set even at low baseline rates.
                AppStartInstrumentation.shared.install(tracer: tracer)
            }
            if opts.contains(.deviceStats) {
                // Auto-start the continuous gauge loop. Before today this
                // required customers to call `mobile.deviceStats.start(meter:)`
                // themselves — easy to miss, so the default demo emitted zero
                // health telemetry. Opt out by dropping `.deviceStats` from
                // `autoCaptureOptions`.
                instance.deviceStats.start(
                    meter: meter,
                    intervalSeconds: config.deviceStatsIntervalSeconds
                )
            }
            // Kick off the predictive-export cycle if configured. Off by
            // default because it emits a DEBUG log every interval even
            // when the app is idle.
            instance.predictiveExportPolicy?.start()
            // Config polling: when enabled, construct a ConfigPoller and
            // start it. Any failure is logged via the poller itself; we
            // never want a bad gateway URL to crash startup.
            if config.enablePolicyPolling {
                if let poller = try? ConfigPoller(
                    gatewayEndpoint: config.endpoint,
                    authToken: config.authToken,
                    extraHeaders: config.extraHeaders,
                    pollingIntervalSeconds: TimeInterval(config.pollingIntervalSeconds),
                    evaluator: instance.policyEvaluator,
                    logger: logger
                ) {
                    poller.start()
                }
            }
            // Crash-safety recovery: if the disk buffer holds events from a
            // previous process, emit a marker log with the backlog size then
            // drain them through the exporter. Non-blocking — runs on a
            // detached Task so the first SwiftUI render is not delayed.
            if diskBuffer != nil || spanDiskBuffer != nil {
                Task.detached { [bufferProcessor, logger, spanDiskBuffer, otlpTraceExporter] in
                    let logStats = await bufferProcessor.diskStats()
                    let spanStats: (count: Int, bytes: Int)? = await {
                        guard let b = spanDiskBuffer else { return nil }
                        let count = await b.rowCount()
                        guard count > 0 else { return nil }
                        let bytes = await b.totalSizeBytes()
                        return (count, bytes)
                    }()
                    let logCount = logStats?.count ?? 0
                    let spanCount = spanStats?.count ?? 0
                    guard logCount > 0 || spanCount > 0 else { return }

                    // Combined marker — a single `app.recovery_start` log
                    // carries whichever of {event,span}_count/bytes_pending
                    // are non-zero. Keeps the breadcrumb additive so log-only
                    // dashboards from the pre-span-persist era keep working.
                    var attrs: [String: AttributeValue] = [:]
                    if let s = logStats, s.count > 0 {
                        attrs["dash0.recovery.event_count"] = .int(s.count)
                        attrs["dash0.recovery.bytes_pending"] = .int(s.bytes)
                    }
                    if let s = spanStats {
                        attrs["dash0.recovery.span_count"] = .int(s.count)
                        attrs["dash0.recovery.span_bytes_pending"] = .int(s.bytes)
                    }
                    logger.logRecordBuilder()
                        .setBody(AttributeValue.string("app.recovery_start"))
                        .setSeverity(.info)
                        .setAttributes(attrs)
                        .emit()

                    if logCount > 0 {
                        _ = await bufferProcessor.recoverFromDisk()
                    }
                    if let b = spanDiskBuffer, spanCount > 0 {
                        // Use a vanilla BaseHTTPClient for replay so retry
                        // failures don't re-persist the same rows we just
                        // drained. Each row's endpoint + headers are the
                        // originals (captured at the time of the failed
                        // export), so routing is identical to what a live
                        // export would have been.
                        _ = await OTelMobile.recoverSpanRequests(
                            from: b,
                            httpClient: BaseHTTPClient(),
                            batchSize: 64)
                    }
                }
            }
            if opts.contains(.screen) {
                // SAFE SwiftUI-bridge path: enables `.trackScreen("Name")` and
                // `.trackTaps(target:)` ViewModifiers. The UIKit swizzle (which
                // races with SwiftUI hosting controller lifecycle) stays
                // OPT-IN via `enableUIKitSwizzle: true` for pure-UIKit apps.
                ScreenInstrumentation.shared.install(tracer: tracer, logger: logger)
            }

            // Auto-forceFlush on backgrounding. Without this, a customer app
            // that goes offline (airplane mode, lost Wi-Fi, etc.), then
            // backgrounds, then terminates before reconnecting, loses every
            // log that was still in RAM — `forceFlushBuffered` only
            // persists on-failure when it's actually invoked. Wiring the
            // flush to the OS lifecycle edge means the no-loss promise
            // doesn't require any customer code. UIApplication + UIScene
            // observers mirror the pattern used by LifecycleInstrumentation
            // so scene-based SwiftUI apps get coverage too.
            #if canImport(UIKit) && (os(iOS) || os(tvOS))
            let nc = NotificationCenter.default
            instance.autoFlushObservers.append(nc.addObserver(
                forName: UIApplication.didEnterBackgroundNotification,
                object: nil, queue: nil
            ) { [weak instance] _ in _ = instance?.forceFlush() })
            instance.autoFlushObservers.append(nc.addObserver(
                forName: UIScene.didEnterBackgroundNotification,
                object: nil, queue: nil
            ) { [weak instance] _ in _ = instance?.forceFlush() })
            instance.autoFlushObservers.append(nc.addObserver(
                forName: UIApplication.willTerminateNotification,
                object: nil, queue: nil
            ) { [weak instance] _ in _ = instance?.forceFlush() })
            #endif
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

    // MARK: - Internal helpers (testable)

    /// Build the `NetworkConfig` used by auto-installed `NetworkInstrumentation`.
    ///
    /// Self-capture avoidance: `NetworkInstrumentation` intercepts every
    /// URLSession request via `URLProtocol`, which would otherwise include the
    /// SDK's own OTLP export calls. This helper adds the configured endpoint
    /// host to `ignoredHosts` by default so exports don't generate spans that
    /// then need to be exported themselves. Callers can still override by
    /// calling `NetworkInstrumentation.shared.install(tracer:config:)` directly
    /// after `start()` with their own `NetworkConfig`.
    ///
    /// Exposed at `internal` so `@testable import` can exercise the mapping
    /// from endpoint string to `ignoredHosts`.
    static func makeNetworkConfig(endpoint: String) -> NetworkConfig {
        let base = NetworkConfig.default
        guard let host = URL(string: endpoint)?.host?.lowercased() else {
            return base
        }
        return NetworkConfig(
            ignoredHosts: base.ignoredHosts.union([host]),
            allowedHosts: base.allowedHosts,
            stripQueryStrings: base.stripQueryStrings,
            capturedResponseHeaders: base.capturedResponseHeaders,
            capturedRequestHeaders: base.capturedRequestHeaders,
            errorStatusThreshold: base.errorStatusThreshold,
            propagateTraceContext: base.propagateTraceContext
        )
    }

    /// Drains all persisted spans through `exporter` in `batchSize` chunks.
    /// Read-then-conditionally-delete: rows are only removed from disk
    /// after a successful export. Mirrors `MobileLogRecordProcessor.recoverFromDisk`.
    ///
    /// Public for testability. Called from the `Task.detached` recovery
    /// block inside `start()`.
    /// Replay persisted OTLP trace requests through `httpClient`. Each row
    /// is POSTed individually; a row is deleted only after its POST returns
    /// 2xx. Retryable failures (5xx, 429, network error) leave the row on
    /// disk for the next launch.
    ///
    /// Returns the number of rows successfully replayed and deleted.
    ///
    /// Public for testability. Called from the `Task.detached` recovery
    /// block inside `start()`.
    public static func recoverSpanRequests(
        from buffer: DiskSpanBuffer,
        httpClient: HTTPClient,
        batchSize: Int = 64,
        perRequestTimeout: TimeInterval = 30
    ) async -> Int {
        var replayed = 0
        outer: while true {
            let batch = await buffer.fetchAll(limit: batchSize)
            if batch.isEmpty { break }
            for row in batch {
                let outcome = await Self.replayOne(
                    row: row,
                    httpClient: httpClient,
                    timeout: perRequestTimeout)
                switch outcome {
                case .delete:
                    await buffer.delete(id: row.id)
                    replayed += 1
                case .retry:
                    // Stop at first retryable failure — leave the rest for
                    // the next launch so we don't spin on a dead network.
                    break outer
                case .drop:
                    // Non-retryable client error (400/401/etc.) — deleting
                    // here prevents the disk from accumulating permanently-
                    // bad requests that no replay would ever succeed on.
                    await buffer.delete(id: row.id)
                }
            }
        }
        return replayed
    }

    private enum ReplayOutcome { case delete, retry, drop }

    private static func replayOne(
        row: BufferedSpanRequest,
        httpClient: HTTPClient,
        timeout: TimeInterval
    ) async -> ReplayOutcome {
        var request = URLRequest(url: row.endpoint)
        request.httpMethod = "POST"
        for (k, v) in row.headers {
            request.setValue(v, forHTTPHeaderField: k)
        }
        request.httpBody = row.body

        return await withCheckedContinuation { cont in
            let semaphore = DispatchSemaphore(value: 0)
            var outcome: ReplayOutcome = .retry
            httpClient.send(request: request) { result in
                switch result {
                case .success(let resp):
                    let status = resp.statusCode
                    if (200..<300).contains(status) {
                        outcome = .delete
                    } else if status >= 500 || status == 429 {
                        outcome = .retry
                    } else {
                        // 4xx non-429: client/auth error, won't succeed later.
                        outcome = .drop
                    }
                case .failure:
                    outcome = .retry
                }
                semaphore.signal()
            }
            // Cap wait on the BSP background thread so a hanging call does
            // not block the rest of recovery indefinitely.
            _ = semaphore.wait(timeout: .now() + timeout)
            cont.resume(returning: outcome)
        }
    }
}
