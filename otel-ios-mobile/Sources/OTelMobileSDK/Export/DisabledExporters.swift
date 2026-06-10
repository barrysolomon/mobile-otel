import Foundation
import OpenTelemetrySdk

/// No-op exporters used to **gracefully disable** a telemetry pipeline when the
/// configured endpoint fails the HTTPS transport policy (cleartext `http://` to
/// a non-loopback host without `allowInsecureTransport`).
///
/// Rationale (host-safety, see task §1): a rejected insecure endpoint must
/// neither crash the host nor throw out of `OTelMobile.start`. Instead, the SDK
/// substitutes these no-op exporters so the rest of the pipeline (buffering,
/// session, instrumentation) constructs normally and the app keeps running —
/// just with export turned off. Each exporter reports `.success` so upstream
/// batch processors don't churn on retries for data that is intentionally
/// dropped.
struct DisabledLogRecordExporter: LogRecordExporter {
    func export(logRecords: [ReadableLogRecord], explicitTimeout: TimeInterval?) -> ExportResult { .success }
    func forceFlush(explicitTimeout: TimeInterval?) -> ExportResult { .success }
    func shutdown(explicitTimeout: TimeInterval?) {}
}

/// `SpanExporter` is declared `public protocol SpanExporter: AnyObject, Sendable`,
/// so its conformers must be reference types. This no-op is stateless, so the
/// `Sendable` requirement is satisfied without any synchronization.
final class DisabledSpanExporter: SpanExporter {
    func export(spans: [SpanData], explicitTimeout: TimeInterval?) -> SpanExporterResultCode { .success }
    func flush(explicitTimeout: TimeInterval?) -> SpanExporterResultCode { .success }
    func shutdown(explicitTimeout: TimeInterval?) {}
}

/// `MetricExporter` refines `AggregationTemporalitySelectorProtocol` and
/// `DefaultAggregationSelector`. The default-aggregation requirement is met by
/// the protocol's own extension; the temporality selector has no default, so
/// it is supplied here (cumulative — the conventional default for a no-op).
struct DisabledMetricExporter: MetricExporter {
    func export(metrics: [MetricData]) -> ExportResult { .success }
    func flush() -> ExportResult { .success }
    func shutdown() -> ExportResult { .success }
    func getAggregationTemporality(for instrument: InstrumentType) -> AggregationTemporality { .cumulative }
}
