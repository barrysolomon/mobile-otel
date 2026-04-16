import Foundation
import OpenTelemetryApi
import OpenTelemetrySdk
import OTelMobileCore

/// Public entry point for the Dash0 Mobile Observability iOS SDK.
///
/// Thin-slice implementation: no UIApplication lifecycle, no auto-instrumentation.
/// Callers invoke `emit(body:severity:)` manually. The buffer → exporter
/// pipeline is fully wired and exercised by the end-to-end smoke test.
public final class OTelMobile: @unchecked Sendable {
    private let processor: MobileLogRecordProcessor
    private let logger: Logger
    public let config: MobileConfig
    public let sessionProvider: SessionProvider

    private init(
        config: MobileConfig,
        processor: MobileLogRecordProcessor,
        logger: Logger,
        sessionProvider: SessionProvider
    ) {
        self.config = config
        self.processor = processor
        self.logger = logger
        self.sessionProvider = sessionProvider
    }

    /// Wires the SDK with a caller-supplied `BufferedEventExporter`.
    /// Use this overload from tests, demos, and application code that already
    /// owns an exporter (e.g. an OTLP/gRPC adapter).
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
        let resource = Resource(attributes: [
            "service.name": AttributeValue.string(config.serviceName),
            "service.version": AttributeValue.string(config.serviceVersion),
        ])
        let loggerProvider = LoggerProviderBuilder()
            .with(resource: resource)
            .with(processors: [processor])
            .build()
        let logger = loggerProvider.get(instrumentationScopeName: "io.dash0.mobile")

        return OTelMobile(
            config: config,
            processor: processor,
            logger: logger,
            sessionProvider: sessionProvider
        )
    }

    /// Production wiring: builds OTel-Swift's OTLP/HTTP log exporter against
    /// `config.endpoint` (with optional Bearer auth from `config.authToken`),
    /// wraps it in a batch processor, and registers BOTH our buffer
    /// processor AND the batch processor on the LoggerProvider. Each `emit`
    /// is fanned out to both: the buffer processor captures for
    /// selective-flush/replay semantics, and the batch processor streams to
    /// the real collector.
    ///
    /// The injectable-exporter overload `start(config:exporter:)` remains
    /// available for unit tests and bespoke transport adapters.
    public static func start(config: MobileConfig) throws -> OTelMobile {
        // `CapturingExporter` plays the role of the buffer's terminal sink.
        // Selective flush / forceFlush() drains buffered events to it so
        // they remain inspectable; real-time export happens via the
        // separate BatchLogRecordProcessor below.
        let bufferSink = CapturingExporter()
        let sessionProvider = StaticSessionProvider()
        let buffer = RAMEventBuffer(capacity: config.bufferConfig.ramEvents)

        let bufferProcessor = MobileLogRecordProcessor(
            buffer: buffer,
            exporter: bufferSink,
            sessionProvider: sessionProvider
        )

        let otlpExporter = try OTLPExporterFactory.makeHttpLogExporter(
            endpoint: config.endpoint,
            authToken: config.authToken,
            extraHeaders: config.extraHeaders
        )
        // Small schedule delay for demo visibility — production deployments
        // can tune this up; 2s gives crisp turnaround during live demos
        // without being so aggressive that we spam the collector.
        let batchProcessor = BatchLogRecordProcessor(
            logRecordExporter: otlpExporter,
            scheduleDelay: 2,
            exportTimeout: 30,
            maxQueueSize: 2048,
            maxExportBatchSize: 512
        )

        let resource = Resource(attributes: [
            "service.name": AttributeValue.string(config.serviceName),
            "service.version": AttributeValue.string(config.serviceVersion),
        ])
        // `with(processors:)` appends — both processors receive every
        // emitted log record.
        let loggerProvider = LoggerProviderBuilder()
            .with(resource: resource)
            .with(processors: [bufferProcessor, batchProcessor])
            .build()
        let logger = loggerProvider.get(instrumentationScopeName: "io.dash0.mobile")

        return OTelMobile(
            config: config,
            processor: bufferProcessor,
            logger: logger,
            sessionProvider: sessionProvider
        )
    }

    // MARK: - Public API

    /// Emit a log event with an optional severity. Thin-slice convenience —
    /// real instrumentation modules will land in subsequent tasks.
    public func emit(body: String, severity: Severity = .info) {
        logger.logRecordBuilder()
            .setBody(AttributeValue.string(body))
            .setSeverity(severity)
            .emit()
    }

    /// Synchronously flush all buffered events to the exporter.
    /// Returns the buffer-level result (success or failure with reason).
    @discardableResult
    public func forceFlush() -> BufferExportResult {
        processor.forceFlushBuffered()
    }

    /// Selective time-window flush: export events from the last `minutes`.
    @discardableResult
    public func flushWindow(minutes: UInt64) async -> BufferExportResult {
        await processor.flushWindow(minutes: minutes)
    }
}
