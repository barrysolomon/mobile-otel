# OTEP: Mobile Dual-Tier Buffering Pattern for OpenTelemetry

**Status:** Draft
**Authors:** OpenTelemetry Android Contributors
**Created:** 2026-03-09
**Target:** OpenTelemetry Mobile SIG / opentelemetry-android

---

## Motivation

Mobile applications operate in an environment that Web and server-side SDKs were not designed
for. Three constraints make a direct port of standard `BatchLogRecordProcessor` or
`SimpleLogRecordProcessor` impractical:

1. **Intermittent connectivity.** A device may be offline for seconds or hours. Standard
   `BatchSpanProcessor` drops data or backs up indefinitely when the export endpoint is
   unreachable. For mobile, lost connectivity must be a first-class buffering condition.

2. **Process death without warning.** On Android, the OS can kill a background process at any
   time. Events held only in heap memory are silently lost. On iOS, background-time limits impose
   the same constraint. A durable tier that survives process termination is required.

3. **Battery and bandwidth budget.** Continuous export — the default for server SDKs — burns
   battery and consumes cellular data. The export decision must be policy-driven, not timer-driven
   by default.

4. **Bounded on-device storage.** A mobile device's available storage is shared with the user's
   media, applications, and OS. The buffer must enforce a hard size cap and age out stale events
   automatically.

The standard OTel SDK offers no solution for any of these. This OTEP proposes a portable buffering
pattern that addresses all four, grounded in a working implementation in `otel-android-mobile`.

---

## Proposed Solution

### Dual-Tier Ring Buffer

Events are written to a fast in-memory queue (Tier 1). When Tier 1 reaches capacity, the oldest
events overflow to a durable persistent store (Tier 2). Both tiers are bounded and evict oldest-
first. The tiers are transparent to the caller — writes always go to Tier 1.

```
onEmit() ──► Tier 1: RAM ring buffer (ConcurrentLinkedQueue, 5,000 events)
                         │
                         │  overflow (oldest-first)
                         ▼
             Tier 2: Disk ring buffer (Room/SQLite, 50 MB, 24 h TTL)
                         │
                         │  flush trigger or periodic schedule
                         ▼
             OTLP/gRPC exporter  ──► Collector
```

#### Tier 1 — RAM Buffer

- **Implementation:** `java.util.concurrent.ConcurrentLinkedQueue<LogRecordData>`
- **Capacity:** 5,000 events (configurable)
- **Write path:** `onEmit()` calls `offer()` — non-blocking, returns immediately. Never blocks
  the logging thread.
- **Overflow:** A background executor checks the queue depth every 5 seconds. When depth exceeds
  capacity, the oldest N events are polled off and handed to Tier 2.
- **Durability:** None — RAM buffer contents are lost on process kill. By design: Tier 2 is the
  durability layer.
- **OTel gauges:** `buffer.ram.events` and `buffer.ram.capacity` exposed as OTel async gauges
  for observability of the buffer itself.

#### Tier 2 — Disk Buffer

- **Implementation:** Android Room (SQLite) with schema `log_records(id, timestampMs, body,
  attributes, resource, traceId, spanId, ...)`
- **Capacity:** 50 MB hard cap (configurable via `setDiskBufferMb`)
- **TTL:** 24 hours (configurable via `setDiskBufferTtlHours`); expired rows deleted hourly
- **Size enforcement:** After each batch insert, the database file size is checked. If the cap is
  exceeded, oldest rows are deleted and SQLite `VACUUM` is run to reclaim space.
- **Indices:** `timestampMs` and `traceId` are indexed for efficient window and trace-scoped
  queries.
- **Durability:** Survives process kill, app restart, and device reboot. On next process start,
  events are available for crash-recovery export.
- **Known limitation (current impl):** Full round-trip deserialization of all attribute types is
  implemented. The `toLogRecordData()` conversion reconstructs typed `AttributeKey<T>` values
  from JSON using a stored type map (`attributeTypes` column).

#### Integration with OTel SDK

`MobileLogRecordProcessor` implements the standard `io.opentelemetry.sdk.logs.LogRecordProcessor`
interface. It slots into the SDK's processor chain without modification to the SDK itself:

```kotlin
val sdkLoggerProvider = SdkLoggerProvider.builder()
    .addLogRecordProcessor(
        MobileLogRecordProcessor.builder(context)
            .setExporter(otlpGrpcExporter)
            .setConfig(mobileConfig)
            .setMeter(meter)
            .setRamBufferSize(5000)
            .setDiskBufferMb(50)
            .setDiskBufferTtlHours(24)
            .build()
    )
    .build()
```

No changes to upstream OTel SDK interfaces are required. The processor is a drop-in.

### Selective Flush (`flushWindow`)

Rather than exporting all buffered events when a trigger fires, the SDK exports a time-windowed
slice. This is the key mechanism that makes CONDITIONAL mode practical: only the events
surrounding a detected problem are sent, not the full buffer.

```kotlin
fun flushWindow(windowMinutes: Int): CompletableResultCode
```

**Algorithm:**
1. Compute `windowStartMs = now - (windowMinutes * 60_000)`.
2. Optionally extend the window backward to the start of the current screen view (bounded to 30
   minutes), so the export captures the full user journey on the current page.
3. Collect all RAM events with `timestampEpochNanos / 1_000_000 >= windowStartMs`.
4. Query Tier 2 with `SELECT ... WHERE timestampMs >= windowStartMs`.
5. Export both sets in 100-event batches via the configured `LogRecordExporter`.
6. On success: remove the exact exported RAM objects by identity (using `IdentityHashMap` to
   avoid a race with concurrent `onEmit()` calls), then delete the disk window via a Room
   transaction.
7. A 10-second cooldown suppresses duplicate flushes when overlapping policies fire in rapid
   succession.

**Trace-scoped variant:** `flushByTraceId(traceId)` exports events by OTel trace ID rather than
time window, enabling precise export of a specific user interaction. Falls back to
`flushWindow(fallbackWindowMinutes)` when fewer than two events match the trace.

### Export Policies

Export behavior is governed by a JSON DSL evaluated on-device by `PolicyEvaluator`. Policies are
authored visually in a control-plane UI, compiled to the DSL, and delivered to devices via a
60-second config poll.

Three export modes are supported:

| Mode | Export triggers | Battery impact |
|------|----------------|----------------|
| **CONDITIONAL** | Policy match only — zero export when nothing matches | <0.5% |
| **CONTINUOUS** | Periodic timer (traces every N seconds, metrics every M seconds) | 3–5% |
| **HYBRID** | Periodic timer + policy match | 1–2% |

In CONDITIONAL and HYBRID modes, every `onEmit()` call asynchronously invokes
`PolicyEvaluator.evaluate()`. When a policy matches, the result specifies the flush action
(e.g., `flush_window: {minutes: 2, scope: session}`). In CONTINUOUS mode, policy evaluation is
skipped to avoid spurious out-of-schedule exports.

**Example DSL trigger (compiled from visual graph):**

```json
{
  "trigger": {
    "any": [
      { "event": "ui.freeze" },
      { "event": "http.error", "where": [{"field": "http.status_code", "op": ">=", "value": 500}] }
    ]
  },
  "actions": [
    { "flush_window": { "minutes": 2, "scope": "session" } },
    { "annotate": { "trigger_id": "ui-freeze-handler" } }
  ]
}
```

Additional flush triggers beyond policy match: uncaught exception (via `ErrorInstrumentation`),
crash-risk score ≥ 0.7 (via `PredictiveExportPolicy`), Android low-memory callback
(`ComponentCallbacks2`), and crash marker detected on restart.

### Shutdown Behavior

On `shutdown()`, the RAM buffer is exported and cleared. The disk buffer is intentionally left
intact. This means if the process is killed mid-export, events remain on disk and are available
for crash-recovery export on the next launch.

---

## Trade-offs and Open Questions

### What should be standardized

1. **The `LogRecordProcessor` interface is sufficient.** No new SDK interface is needed; the
   dual-tier behavior is entirely internal to the processor implementation.

2. **A standard `flushWindow(minutes)` method** (or equivalent) on mobile processors would allow
   policy engines from different vendors to drive selective export without coupling to a specific
   implementation. This could be defined as an optional interface alongside `LogRecordProcessor`.

3. **Buffer capacity attributes** (`buffer.ram.events`, `buffer.ram.capacity`,
   `buffer.disk.events`) should follow a standard naming convention if multiple mobile SDKs
   expose them, to allow unified dashboards.

### What should remain implementation-specific

- The choice of persistence backend (Room/SQLite, SQLite directly, Core Data on iOS, LevelDB)
  is platform-specific and should not be standardized.
- The policy DSL schema is application-specific. The standardization surface is the flush
  trigger interface, not the policy language itself.
- Overflow timing (the 5-second background check) is a tuning parameter, not a protocol detail.

### Open Questions

1. **Cross-platform alignment.** Should `flushWindow` be defined in a platform-neutral spec
   (e.g., in `opentelemetry-specification`) or only in platform-specific SDK guidance? A
   cross-platform mobile SIG would be the right venue.

2. **Disk deserialization fidelity.** Serializing `LogRecordData` to JSON and back loses some
   type fidelity for complex attribute types (lists, nested maps). Should mobile processors be
   required to round-trip all OTel attribute types, or is a lossy-but-useful subset acceptable?

3. **Config delivery.** This implementation uses a gateway-polled JSON config. Alternative
   delivery mechanisms (OpAMP, Firebase Remote Config, push) may be preferable in different
   deployments. The buffering pattern is independent of config delivery; that separation should
   be explicit in any standard.

4. **Disk encryption.** On Android, Room databases on internal storage are protected by the
   device's file system encryption, but explicit column-level encryption (e.g., SQLCipher) may
   be required in high-compliance environments. A standard should clarify the expected security
   posture.

---

## Alternatives Considered

### 1. Retain standard `BatchLogRecordProcessor` with retry

Adding retry logic to `BatchLogRecordProcessor` (as some SDKs do) addresses the offline case
but not process death, bounded storage, or selective flush. It also requires modifying upstream
SDK code rather than composing via the processor interface.

### 2. Write all events directly to disk

Eliminating the RAM tier avoids the volatile-on-crash problem entirely, but adds disk I/O latency
to every `onEmit()` call. On Android, disk writes on the main thread cause `StrictMode` violations
and jank. The dual-tier design keeps `onEmit()` non-blocking while still providing durability.

### 3. Use a WAL-mode SQLite with memory-mapped writes

SQLite in WAL mode with `mmap_size > 0` can approximate a combined RAM+disk buffer, but
requires raw SQLite access rather than the Room ORM, complicates the entity schema, and provides
no meaningful latency advantage over the two-queue approach for typical event sizes.

### 4. Export on every event (no buffering)

Zero-buffer immediate export is appropriate for server SDKs with reliable network access. For
mobile it is not viable: it burns battery on cellular, fails silently when offline, and cannot
support selective flush of a time window.

---

## References

- `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/MobileLogRecordProcessor.kt`
- `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/DiskLogBuffer.kt`
- [DESIGN.md](../../DESIGN.md) — System architecture and export mode definitions
- [docs/reference/ARCHITECTURE.md](../reference/ARCHITECTURE.md) — Component detail and data flow
- [OpenTelemetry Log SDK specification](https://opentelemetry.io/docs/specs/otel/logs/sdk/)
- [opentelemetry-android repository](https://github.com/open-telemetry/opentelemetry-android)
