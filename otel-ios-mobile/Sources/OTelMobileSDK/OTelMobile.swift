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
