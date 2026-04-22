# RN Gate 4: Span Disk-Persist

**Status:** Draft — ready for review
**Date:** 2026-04-22
**Owner:** Barry Solomon
**Scope:** `otel-ios-mobile/` native SDK — extends the disk-persist
guarantee from logs to spans. Android parity is a follow-up.

---

## Summary

Commit `1a69c7e` gave **logs** an offline-drain guarantee: events
generated while the export endpoint is unreachable persist to disk,
survive process death, and re-export on next launch with an
`app.recovery_start` marker showing the backlog.

**Spans have none of this.** The span pipeline runs upstream's
`BatchSpanProcessor` → `OtlpHttpTraceExporter`, and BSP **silently
drops spans** on export `.failure` (verified in
`opentelemetry-swift-core/Sources/OpenTelemetrySdk/Trace/SpanProcessors/BatchSpanProcessor.swift:249`
— `if result == .success` with no `else` branch).

RN iOS is the platform where this hurts most: under
`autoCaptureOptions: .none`, primary telemetry flows as spans
(fetch/XHR shim + ShopTelemetry instrumentation). Gate 4 of the
2026-04-22 validation sweep failed on exactly this — a 30-second
offline window produced zero disk rows and zero recovered spans.

This design adds the same three-layer guarantee for spans: a
persisting exporter decorator, a disk-backed span buffer, and a
recovery drain at `OTelMobile.start()`.

## Goal

Make this Gate 4 procedure green on RN iOS (AstronomyShopRN):

1. Swap `otel-config.json` endpoint to `*.invalid:4318`, rebuild.
2. Launch app, drive UI for ~30s (produces HTTP spans via
   pokeBackend + ShopTelemetry spans via tap navigation).
3. Terminate.
4. Inspect disk: `sqlite3 buffer.db "SELECT COUNT(*) FROM buffered_spans"`
   → expect N > 0.
5. Swap endpoint back, rebuild, relaunch.
6. Query Dash0:
   - `service.name is otel-rn-astronomy-shop and event.name is app.recovery_start`
     → 1 record with `dash0.recovery.span_count = N`
   - `service.name is otel-rn-astronomy-shop` span query → the N
     spans from the offline window, with their original timestamps.
7. Post-drain disk check: `SELECT COUNT(*)` → 0.

## Architecture

```text
App code / auto-instrumentation
        ↓
OTelMobile.tracer  (upstream TracerProviderSdk)
        ↓
BatchSpanProcessor (upstream, unchanged)
        ↓
RetryableExporter  (existing class, now also wraps traces)
        ↓
PersistingSpanExporter  (NEW — decorator)
    ├─ delegate.export(spans) succeeds → return .success
    └─ delegate returns .failure → await diskBuffer.persist(spans)
       then return .failure (so upstream retry still fires on next tick)
        ↓
OtlpHttpTraceExporter  (upstream, unchanged)

On next launch:
OTelMobile.start() — Task.detached recovery block
    ├─ await spanDiskBuffer.stats() → { count, bytes }
    ├─ if count == 0: return
    ├─ logger.emit("app.recovery_start",
    │     { dash0.recovery.event_count: logBacklog,        // existing
    │       dash0.recovery.bytes_pending: logBytes,        // existing
    │       dash0.recovery.span_count: spanCount,          // NEW
    │       dash0.recovery.span_bytes_pending: spanBytes }) // NEW
    ├─ await spanDiskBuffer.pop(limit: 2048) → [SpanData]
    └─ otlpTraceExporter.export(spans, explicitTimeout: 10s)
```

## Key decisions (verified against existing pipeline)

### Recovery is `Task.detached`, not synchronous

The log-side recovery at `OTelMobile.swift:458` runs inside a
`Task.detached` so the first SwiftUI render is not delayed. This
design mirrors that pattern exactly. No `DispatchSemaphore` bridging.
The comment "runs on a detached Task so the first SwiftUI render is
not delayed" applies equally to spans.

Ordering note: spans recovered from disk carry their original
offline-window timestamps, so Dash0 orders them correctly against
live spans by timestamp regardless of export-order interleaving.

### BatchSpanProcessor does NOT retry on failure

Verified in upstream source. `exportAction` calls
`spanExporter.export(spansToExport, ...)` and only increments a
success counter on `.success`; on `.failure` the spans leave BSP's
queue and are effectively dropped from its perspective.

Implication: **our `PersistingSpanExporter` is the only path that
can save a span after export failure**. The disk write must be
reliable; there is no retry net below us.

### Wrap with `RetryableExporter` for defense-in-depth

Logs currently wrap `SynchronousLogRecordExporter` with
`RetryableExporter` (OTelMobile.swift:220). Spans today have no
such wrapping. This design adds the same wrapping for spans:
`RetryableExporter(delegate: PersistingSpanExporter(delegate:
otlpTraceExporter))`.

- Sub-1s network blips get absorbed by RetryableExporter's
  exponential backoff (1s → 2s → 4s over 3 attempts) before any
  disk write happens.
- Genuine offline windows still reach the persisting decorator.
- The "overlapping retry" concern (RetryableExporter attempts
  overlap with BSP's next-tick export) is theoretical: Retryable's
  three attempts finish within 7 seconds; BSP's default
  `scheduleDelay` is 2 seconds but attempts don't cascade because
  RetryableExporter returns a single final result.

### Separate 50 MB budget per buffer

Log buffer and span buffer each get their own 50 MB cap and 24 h
retention TTL. Simple accounting; on modern iOS devices 100 MB
total is trivial (iPhones have 64 GB+ storage). No coordination
required between the two buffers.

### Single `app.recovery_start` marker, additive schema

One marker covers both signals. Attribute schema is additive — no
breaking change to existing log-recovery queries:

| Attribute | Semantics | Status |
| --- | --- | --- |
| `dash0.recovery.event_count` | log record count (unchanged) | existing |
| `dash0.recovery.bytes_pending` | log bytes (unchanged) | existing |
| `dash0.recovery.span_count` | recovered span count | **new** |
| `dash0.recovery.span_bytes_pending` | span bytes | **new** |

Existing log-recovery filters keep working. Span-aware queries use
the new attributes. Marker is emitted whenever either buffer has
`count > 0`.

### No `enableSpanDiskBuffer` config flag

Log side uses `if diskBuffer != nil` to gate recovery and passes an
optional `DiskLogBuffer` through `MobileConfig`. We mirror this:
`MobileConfig.spanDiskBuffer: DiskSpanBuffer?` (default `nil`).
Demo apps pass one in; silent no-op for apps that don't.

## Components

### New files — `otel-ios-mobile/Sources/OTelMobileSDK/Buffering/`

**`BufferedSpan.swift`** — mirrors `BufferedEvent`:

```swift
public struct BufferedSpan: Sendable {
    public let spanKey: String            // traceId.hexString + spanId.hexString
    public let startTimeUnixNano: UInt64
    public let sessionId: String
    public let record: SpanData?          // upstream SpanData is Codable
    public let recordData: Data           // JSON-encoded SpanData for disk
    public let sizeBytes: Int
    public let createdAt: Date
}
```

Dedup key is `traceId+spanId` (both hex). Not for BSP retry
protection (BSP doesn't retry) but for crash-safety mid-persist: if
the app dies after `DiskSpanBuffer.persist(spans)` writes row 3 of
5 and restarts, a subsequent re-presentation of the same span list
won't double-persist.

**`DiskSpanBuffer.swift`** — actor, mirrors `DiskLogBuffer`:

- Schema:

  ```sql
  CREATE TABLE buffered_spans (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    span_key        TEXT NOT NULL UNIQUE,
    start_time_ns   INTEGER NOT NULL,
    session_id      TEXT NOT NULL,
    record_json     BLOB NOT NULL,
    size_bytes      INTEGER NOT NULL,
    created_at      INTEGER NOT NULL
  );
  CREATE INDEX idx_start_time ON buffered_spans(start_time_ns);
  ```

- Pragmas: `journal_mode=WAL`, `synchronous=NORMAL`,
  `temp_store=MEMORY` — same as log buffer.
- Public API: `persist(_ spans: [SpanData]) async`,
  `pop(limit: Int) async -> [SpanData]`, `stats() async -> (count:
  Int, bytes: Int)`, `pruneByTTL() async`, `pruneBySize() async`.
- `INSERT OR IGNORE ON CONFLICT(span_key)` for dedup-on-write.
- All sqlite error paths fail soft per `docs/SDK_SAFETY.md` — log
  via `NSLog`, return empty/no-op.

**`DiskSpanBufferTestSupport.swift`** — mirrors
`DiskLogBufferTestSupport.swift` for the CLT `_Testing_Foundation`
workaround pattern documented in `otel-ios-mobile/CLAUDE.md`.

### New file — `otel-ios-mobile/Sources/OTelMobileSDK/Export/`

**`PersistingSpanExporter.swift`** — implements `SpanExporter`:

```swift
public final class PersistingSpanExporter: SpanExporter {
    private let delegate: SpanExporter
    private let diskBuffer: DiskSpanBuffer?

    public init(delegate: SpanExporter, diskBuffer: DiskSpanBuffer?) {
        self.delegate = delegate
        self.diskBuffer = diskBuffer
    }

    public func export(
        spans: [SpanData],
        explicitTimeout: TimeInterval?
    ) -> SpanExporterResultCode {
        let result = delegate.export(spans: spans, explicitTimeout: explicitTimeout)
        if result == .failure, let buffer = diskBuffer, !spans.isEmpty {
            // Fire-and-forget persist — actor isolation serializes
            // writes; we don't block the export call on disk I/O.
            Task { await buffer.persist(spans) }
        }
        return result
    }

    public func flush(explicitTimeout: TimeInterval?) -> SpanExporterResultCode {
        delegate.flush(explicitTimeout: explicitTimeout)
    }

    public func shutdown(explicitTimeout: TimeInterval?) {
        delegate.shutdown(explicitTimeout: explicitTimeout)
    }
}
```

### Modified — `otel-ios-mobile/Sources/OTelMobileSDK/OTelMobile.swift`

Two changes:

1. **Compose the span exporter stack** (around line 245, where
   `otlpTraceExporter` is built). Wrap with `PersistingSpanExporter`
   then `RetryableExporter` in the same order logs use:

   ```swift
   let baseTraceExporter = try OTLPExporterFactory.makeHttpTraceExporter(...)
   let persistingSpanExporter = PersistingSpanExporter(
       delegate: baseTraceExporter,
       diskBuffer: config.spanDiskBuffer
   )
   let otlpTraceExporter = RetryableExporter(delegate: persistingSpanExporter)
   ```

2. **Extend the recovery Task.detached** (around line 458). Check
   both log and span disk buffers; emit a single combined
   `app.recovery_start` marker with the new attributes; drain the
   span buffer after the marker.

### Modified — `otel-ios-mobile/Sources/OTelMobileSDK/Config/MobileConfig.swift`

Add `public let spanDiskBuffer: DiskSpanBuffer?` (defaults nil).

### Updated — `examples/upstream-demo-app-rn/AstronomyShopRN/ios/AstronomyShopRN/ShopBootstrap.swift` or equivalent

Wire through a `DiskSpanBuffer` instance alongside the existing
`DiskLogBuffer`. (Exact location depends on how the RN iOS demo
initializes the SDK — need to confirm during implementation.)

## Error handling

- **`DiskSpanBuffer.open` failure** — log via `NSLog`, return nil
  from factory; SDK runs without span disk persist (just like the
  log side).
- **`JSONEncoder.encode(SpanData)` failure** — drop that span, log
  via `NSLog`. Per `docs/SDK_SAFETY.md`.
- **sqlite I/O error during persist** — log, return; the span is
  already out of BSP's queue, so it's lost. Accepted failure mode
  matching log-side behavior for the same scenario.
- **`SpanExporterResultCode.failure` from `RetryableExporter`
  after 3 attempts** — triggers `PersistingSpanExporter` persist
  path.
- **Recovery-time export failure** — spans stay on disk, retried
  on next launch. Marker is still emitted so operators see the
  backlog.
- **Recovery export succeeds, disk delete fails** — worst case,
  spans re-export on a later launch. `span_key` dedup at ingestion
  time is a Dash0-side concern; we emit with the same
  `traceId+spanId` so backend-side dedup is deterministic.

## Testing

### Unit tests

**`DiskSpanBufferTests`** — 8 tests:

- `open()` creates table + indexes + pragmas
- `persist(spans)` writes each span once; idempotent on dedup key
- `pop(limit:)` returns oldest-first, removes popped rows
- `stats()` returns count and byte totals
- `pruneByTTL()` removes rows older than 24 h
- `pruneBySize()` removes oldest rows when cumulative bytes > 50 MB
- `persist([])` is a no-op
- `pop(limit: 0)` returns empty
- Decode failure on corrupt `record_json` skips the row and
  continues (fail-soft)

**`PersistingSpanExporterTests`** — 5 tests:

- `delegate.export(.success)` → no disk write, return `.success`
- `delegate.export(.failure)` → disk write, return `.failure`
- `nil diskBuffer` → never writes, just passes through results
- `empty spans list` → never calls disk even on failure
- `flush()` and `shutdown()` pass through to delegate

**`OTelMobileRecoveryTests` extensions** — 4 new tests:

- start with N log rows + 0 span rows → existing log marker attrs
  only (`event_count=N`); no `span_count` attribute
- start with 0 log rows + M span rows → `span_count=M`,
  `event_count` attribute omitted entirely (not emitted as 0; we
  differentiate "nothing pending" from "pending but empty")
- start with N log rows + M span rows → single marker with both
  attribute sets
- start with 0 rows on both → no marker emitted

### Device validation (Gate 4 re-run)

Per the procedure in "Goal" section above. Evidence captured in:

- Dash0 query JSON output for span count + marker record
- sqlite `SELECT COUNT(*)` pre and post recovery
- Simulator log capture showing recovery Task.detached firing

### Jest (RN side)

No RN-side code changes required — all native. Existing RN tests
remain green.

## Rollback

Per-file revert. `PersistingSpanExporter` is a pure decorator; the
only irreversible step is the `buffered_spans` table creation on
first run, which is forward-compatible (the table just becomes
inert if the SDK stops using it).

## Non-goals

- **Android.** `DiskSpanBuffer` Kotlin parity lands when we run
  Gate 4 on RN Android. The iOS fix is independently useful (the
  iOS SDK is shipped standalone as well as via RN).
- **In-process reconnect drain.** Log side has no such thing either
  (`forceFlush` only drains via OS-lifecycle notifications). Spans
  match. Follow-up if Innovapptive needs faster recovery.
- **Crash-during-offline-window span recovery.** Spans in BSP's
  RAM queue at crash time never reached our decorator. Matches log
  side's "events in RAM that never reached `emit`" gap. Out of scope.
- **Policy DSL evaluation on spans at persist time.** Logs have
  policy-driven selective flush; spans don't need it today.
- **`flushWindow(minutes:)` for spans.** Not part of Gate 4 contract.
- **Span-specific config flag.** Enable when `spanDiskBuffer` is
  provided; no new boolean.

## Risks

**Risk 1: upstream BSP's `maxQueueSize=2048` is hit during a long
offline window.** Spans get dropped at BSP's queue ceiling before
they ever reach our exporter. No fix here — spans that never
arrive can't be persisted. Accepted limitation; the 2048 default
provides ~5 minutes of headroom at typical mobile telemetry rates.

**Risk 2: Dash0 ingestion rejects late-arrival spans.** If offline
window exceeds Dash0's acceptance window (typically ~1 hour for
spans), recovered spans may be silently dropped at ingest. This is
a backend concern, not an SDK concern. Worth verifying during
device validation — if we see recovery reports `span_count=N` but
Dash0 shows fewer spans, check ingest-side retention policy. If
Dash0 rejects stale spans, we document and move on — the SDK did
its job.

**Risk 3: `Task { await buffer.persist(spans) }` detached from
export return** means export can return `.failure` before the disk
write completes. If the process dies in that window, spans are
lost. Mitigation: on iOS, the `didEnterBackgroundNotification` /
`willTerminateNotification` observers already fire `forceFlush()`
which awaits the disk buffer. The detached Task will have
completed by then for most sessions. For extreme cases (crash
immediately after export failure) we accept the loss.

## Estimate

5-6 hours, revised from original 3-4:

- `DiskSpanBuffer` actor + tests: ~2.5 hours (mostly sqlite binding
  boilerplate, mirrored from DiskLogBuffer but still line-by-line
  work).
- `PersistingSpanExporter` + tests: ~1 hour.
- `OTelMobile.start` recovery extension + `MobileConfig` field + tests:
  ~1 hour.
- Demo-app wiring + device validation: ~1 hour.
- Spec edits + commit cleanup: ~0.5 hour.
