# OTEP: Conditional Export for Mobile OpenTelemetry SDKs

**Status:** Draft
**Authors:** OpenTelemetry Android Contributors
**Created:** 2026-03-09

---

## Motivation

Mobile devices operate under constraints that server-side and browser SDKs do not face: finite battery, intermittent connectivity, strict background execution limits, and the ever-present risk of abrupt process termination (crash, OOM kill, ANR). The current OTel specification addresses export timing only through batch processor intervals and force-flush calls. This leaves mobile SDK implementers without a standardized model for:

1. **Zero-bandwidth idle operation.** Periodic export means constant network activity even when the user session is uneventful. On mobile, each network wake-up consumes measurable battery. A large-scale app with millions of daily active users will collectively burn megawatt-hours exporting telemetry that is never examined.

2. **Pre-emptive export before data loss.** When a crash is imminent or connectivity is about to drop, the SDK has a brief window to flush buffered data. Without a trigger mechanism, that window is missed and the data is lost.

3. **Context-complete export around problems.** When an error does occur, operators want not just the error event but the full minute or two of user activity that preceded it. A raw-event stream exported on a timer does not preserve that context window.

This OTEP proposes formalizing three export modes — CONDITIONAL, CONTINUOUS, and HYBRID — and a minimal policy DSL for expressing trigger conditions. These concepts are already implemented and validated in the `otel-android-mobile` SDK; this document frames them as candidates for upstream standardization in `opentelemetry-android` and possibly the broader OTel specification.

---

## Proposed Solution

### Export Modes

Three named modes govern when buffered telemetry is transmitted to the collector.

#### CONDITIONAL

Data is buffered indefinitely and exported **only when a trigger condition fires**. No scheduled exports occur.

- Traces: buffered in RAM (configurable timeout, default 1 hour — effectively disabled until trigger)
- Metrics: buffered in RAM (same)
- Logs: flushed on trigger or explicit `forceFlush()` call

Battery impact: less than 0.5% additional drain. Network data: 1–5 MB/day, only on issues. Recommended for production.

#### CONTINUOUS

Data is exported on fixed schedules regardless of application state.

- Traces: exported every `traceExportIntervalSeconds` (default 30 s)
- Metrics: exported every `metricExportIntervalSeconds` (default 60 s)
- Logs: still policy-based (trigger or manual flush)

Battery impact: 3–5% additional drain. Network data: 50–200 MB/day. Recommended for development and QA.

#### HYBRID

Combines a lightweight periodic heartbeat with full conditional dumps on trigger.

- Traces: exported every `traceExportIntervalSeconds * 2` (default 60 s)
- Metrics: exported every `metricExportIntervalSeconds * 2` (default 120 s)
- Logs: policy-based with more frequent background heartbeats
- Full flush when any trigger condition is met

Battery impact: 1–2% additional drain. Network data: 10–50 MB/day. Recommended for production apps with higher observability requirements.

---

### Policy DSL

Export policies are expressed as JSON documents authored visually in a control plane UI and polled by devices on a configurable interval (default 60 s). The Android SDK evaluates the compiled DSL deterministically on-device using `PolicyEvaluator`, which runs in the `MobileLogRecordProcessor` pipeline and matches each incoming event against active trigger conditions in real time.

**DSL structure:**

```json
{
  "trigger": {
    "any": [
      { "event": "ui.freeze" },
      { "event": "ui.jank", "where": [{"attr": "duration_ms", "op": ">", "value": 2000}] }
    ]
  },
  "actions": [
    { "type": "flush_window", "minutes": 2, "scope": "session" },
    { "type": "annotate_trigger", "trigger_id": "ui-freeze" }
  ]
}
```

**Supported operators:** `equals`, `gt`, `lt`, `gte`, `lte`, `contains`, `regex`

**Supported actions:** `flush_window`, `flush_all`, `annotate_trigger`, `set_sampling`, `capture_device_metrics`

**Representative trigger conditions:**

| Condition | DSL expression |
|-----------|----------------|
| On any uncaught exception | `{ "event": "error.exception" }` |
| On HTTP 5xx to a specific path | `{ "event": "http.response", "where": [{"attr": "http.status_code", "op": "gte", "value": 500}, {"attr": "url.path", "op": "contains", "value": "/appointments"}] }` |
| On connectivity loss | `{ "event": "network.loss" }` |
| On UI freeze | `{ "event": "ui.freeze" }` |
| On battery critical | `{ "event": "device.battery_critical" }` |

Each matched trigger executes its associated actions. `flush_window` retrieves the last N minutes of events from the dual-tier ring buffer (RAM first, then disk) and exports them via OTLP/gRPC.

---

### Flush Triggers

The SDK recognizes seven classes of flush trigger:

1. **Policy match** — `PolicyEvaluator` fires when a DSL condition is satisfied by an incoming event.
2. **Error capture** — `ErrorInstrumentation` intercepts uncaught exceptions, coroutine failures, and RxJava errors and triggers an immediate flush before the process is killed.
3. **Predictive** — `PredictiveExportPolicy` fires a pre-emptive flush when on-device risk scores breach configured thresholds (crash risk ≥ 0.7, network loss risk ≥ 0.7).
4. **Memory pressure** — Android `ComponentCallbacks2` signals are mapped to flush triggers.
5. **App recovery** — On next launch, a persisted crash or ANR marker causes a flush of the last N minutes from the disk buffer.
6. **Manual** — `MobileOtel.forceFlush()` or `forceFlush(windowMinutes = 5)`.
7. **Periodic** — Timer-based flushes in CONTINUOUS and HYBRID modes.

---

### Predictive Export

`PredictiveExportPolicy` runs a lightweight on-device model (`OnDevicePredictor`) that continuously scores device health signals — memory pressure, thermal state, battery discharge rate, network RSSI, and recent error frequency — into two risk scores: crash risk and network loss risk. Both scores are emitted as OTel gauge metrics via `HealthMetricsCollector`.

When either score reaches the configured threshold (default 0.7), the policy triggers a pre-emptive `flush_window` before the predicted event occurs. This is the only mechanism that can preserve telemetry from sessions that end abruptly. All model inference runs on-device; no raw signal data is exported.

---

### Integration with the OTel SDK Pipeline

The policy engine hooks into the processor pipeline immediately after the standard `BatchLogRecordProcessor`. `MobileLogRecordProcessor` wraps it and routes every log record through `PolicyEvaluator.evaluate()` before the record is handed to the exporter. Traces and metrics are handled by their respective providers but share the same flush path: any trigger calls into a single `BufferFlusher` that coordinates all three signal types.

The two-tier ring buffer sits beneath the processor:

```
App event
  → MobileLogRecordProcessor
      → PolicyEvaluator (DSL match?)
          → if trigger: BufferFlusher.flushWindow(minutes)
                → RAM buffer (ConcurrentLinkedQueue, 5000 events)
                → Disk buffer (Room/SQLite, 50 MB, 24 h TTL)
                → RetryableExporter → OTLP/gRPC
```

The disk buffer survives process death, enabling crash-recovery export on next launch.

---

## Open Questions

1. **DSL standardization.** Should the trigger condition DSL become a formal OTel specification, or remain a vendor extension point? A standard schema would allow cross-SDK portability (iOS, Flutter, React Native) and interoperable policy authoring tools.

2. **CONDITIONAL mode in the Android spec.** The `opentelemetry-android` specification currently says nothing about export scheduling. Should CONDITIONAL mode — with its implication that a compliant SDK may hold data indefinitely until a trigger fires — be an explicit opt-in contract, or always a deviation from the base spec?

3. **`flushWindow` semantics.** The current implementation of `DiskLogBuffer.toLogRecordData()` is a stub (throws `NotImplementedError`), so `flushWindow` only retrieves RAM-buffered events. Before upstreaming, the disk deserialization path must be completed and the time-window semantics formalized in the spec.

4. **Risk score thresholds.** The 0.7 crash/network-loss threshold is configurable but currently hardcoded as a default. Should the OTel spec prescribe a range, or leave it entirely to operators?

5. **Policy distribution protocol.** Devices currently poll a gateway endpoint every 60 s for updated policy JSON. Is a polling model appropriate for the spec, or should push (e.g., SSE or gRPC streaming) be considered?

---

## Alternatives Considered

**Always-on periodic export (existing OTel batch processor).** Simple to implement and reason about, but imposes constant battery and bandwidth cost on mobile. Discarded as the default for production mobile use.

**Server-side filtering only.** Export everything, filter at the collector. Eliminates on-device policy complexity but does not address battery drain or data loss before export. Incompatible with the zero-bandwidth-when-idle goal.

**Sampling at the span level.** Head-based or tail-based sampling reduces data volume but operates on individual spans, not on session windows. It cannot reconstruct the user journey leading up to a problem. Sampling is complementary, not a replacement.

**Device-local SQLite as the only buffer.** Reliable for crash recovery but SQLite writes on the hot path add latency and drain battery. The dual-tier approach (RAM first, overflow to disk) gives crash durability without per-event disk I/O.

---

## References

- Implementation: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt`
- Implementation: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/predictive/PredictiveExportPolicy.kt`
- Implementation: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/MobileLogRecordProcessor.kt`
- Related design: `DESIGN.md` — Core Concepts, Export Modes, Flush Triggers
- Related doc: `docs/EXPORT_MODES.md` — Configuration reference and battery impact estimates
