# Buffering and Tail Sampling

This document explains the two-tier ring buffer architecture, how export policies enable tail sampling, and how the selective flush mechanism ties them together. It is intended as a technical reference for contributors and for anyone building on or extending the SDK.

---

## Table of Contents

- [The Core Idea: Why Tail Sampling on Mobile?](#the-core-idea-why-tail-sampling-on-mobile)
- [Two-Tier Ring Buffer Architecture](#two-tier-ring-buffer-architecture)
  - [RAM Tier](#ram-tier)
  - [Disk Tier](#disk-tier)
  - [Overflow: RAM → Disk](#overflow-ram--disk)
  - [Capacity, TTL, and Eviction](#capacity-ttl-and-eviction)
- [Selective Flush: The Tail Sampling Mechanism](#selective-flush-the-tail-sampling-mechanism)
  - [flushWindow(minutes): How It Works](#flushwindowminutes-how-it-works)
  - [Thread Safety in flushWindow](#thread-safety-in-flushwindow)
- [Export Policies as Tail Sampling Rules](#export-policies-as-tail-sampling-rules)
  - [Policy DSL Structure](#policy-dsl-structure)
  - [Built-In Default Policies](#built-in-default-policies)
  - [Evaluation Pipeline](#evaluation-pipeline)
  - [Attribute Conditions](#attribute-conditions)
  - [Geo and Device Context Matching](#geo-and-device-context-matching)
  - [Writing a Custom Policy](#writing-a-custom-policy)
- [Export Modes and the Buffer](#export-modes-and-the-buffer)
- [Retry and Durability](#retry-and-durability)
  - [RetryableExporter: Exponential Backoff](#retryableexporter-exponential-backoff)
  - [Crash Recovery](#crash-recovery)
- [Head Sampling vs Tail Sampling: Comparison](#head-sampling-vs-tail-sampling-comparison)
- [Auto-Instrumentation and the Buffer](#auto-instrumentation-and-the-buffer)
- [Observability of the Buffer Itself](#observability-of-the-buffer-itself)
- [End-to-End Walkthrough](#end-to-end-walkthrough)

---

## The Core Idea: Why Tail Sampling on Mobile?

Standard OpenTelemetry SDKs use **head sampling**: the decision to record a trace is made at the moment the root span starts, before any data exists. If the trace is dropped, nothing is recorded. This is efficient, but it means you can never recover data for a trace that turned out to be interesting after the fact.

On mobile devices, this is a poor fit for two reasons:

1. **Crashes and errors are rare but high-value.** If you drop 90% of traces at the head, you will almost certainly drop the trace that preceded the crash.
2. **Network is unreliable.** A device may capture important events while offline and need to export them later.

This SDK uses a different model: **buffer everything, export selectively**. Every event is written to the ring buffer. Export policies evaluate events as they arrive and, when a trigger fires, export a retroactive time window from the buffer. This is tail sampling — the export decision is made after the data exists.

```
                    time →
 ────────────────────────────────────────────────────────
 [t-5min] [t-4min] [t-3min] [t-2min] [t-1min] [t=now]
                                        ↑
                              http.error arrives
                              policy fires:
                              "flush last 5 minutes"
 ──────────────────────────────────────────────────────
 ←──────────── exported to OTLP ──────────────────────►
```

All the events leading up to the error — taps, navigations, network calls, vitals — are already in the buffer and are included in the flush.

---

## Two-Tier Ring Buffer Architecture

The buffer is implemented in [MobileLogRecordProcessor.kt](../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/MobileLogRecordProcessor.kt) and [DiskLogBuffer.kt](../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/DiskLogBuffer.kt).

```
Event arrives via onEmit()
        │
        ▼
┌───────────────────────────────────┐
│  RAM Tier                         │
│  ConcurrentLinkedQueue<LogRecord> │  ← 5000 events max (default)
│  Lock-free, O(1) insert           │  ← volatile (lost on process kill)
└───────────────┬───────────────────┘
                │ overflow (oldest events)
                ▼
┌───────────────────────────────────┐
│  Disk Tier                        │
│  Room / SQLite                    │  ← 50 MB max, 24h TTL (default)
│  Indexed by timestampMs           │  ← survives crashes and restarts
└───────────────────────────────────┘
```

### RAM Tier

The RAM tier is a `ConcurrentLinkedQueue<LogRecordData>`. Every `onEmit()` call appends to this queue on the calling thread — the operation is lock-free and completes in nanoseconds.

```kotlin
override fun onEmit(context: OtelContext, logRecord: ReadWriteLogRecord) {
    val logRecordData = logRecord.toLogRecordData()
    ramBuffer.offer(logRecordData)              // O(1), lock-free
    val count = ramBufferCount.incrementAndGet()

    if (count > ramBufferSize) {
        executor.submit { overflowToDisk() }   // async, never blocks onEmit
    }

    if (config.exportMode == CONDITIONAL || config.exportMode == HYBRID) {
        executor.submit { evaluatePolicies(logRecordData) }  // async policy check
    }
}
```

Key properties:
- **Non-blocking**: `onEmit()` always returns immediately. Overflow and policy evaluation run on a background executor pool.
- **Bounded**: Default capacity is 5,000 events. Adjust via `setRamBufferSize()`.
- **Volatile**: Contents are lost if the process is killed without a shutdown flush. Events that overflow to disk first are durable.

### Disk Tier

The disk tier is a Room/SQLite database (`otel_log_buffer.db`). The schema is a single table:

```sql
CREATE TABLE log_records (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    timestampMs     INTEGER NOT NULL,        -- indexed for window queries
    severityText    TEXT,
    body            TEXT NOT NULL,
    attributes      TEXT NOT NULL,           -- JSON-encoded
    resource        TEXT NOT NULL,           -- JSON-encoded
    instrumentationScopeName    TEXT,
    instrumentationScopeVersion TEXT
);
CREATE INDEX idx_log_records_timestampMs ON log_records (timestampMs);
```

Serialization uses JSON for `attributes` and `resource`. All attribute values are stored as strings — type information is lost in the round-trip. Numeric attributes on RAM-tier events retain their original types; disk-tier events restored after a crash will have string-typed attributes.

Key properties:
- **Durable**: survives process kills, OOM kills, and normal crashes.
- **Indexed**: `timestampMs` index makes `flushWindow()` a fast range scan.
- **Singleton**: `DiskLogBuffer.getInstance()` returns a single shared instance. Tests call `DiskLogBuffer.resetForTesting()` to get a clean state.

### Overflow: RAM → Disk

When the RAM buffer exceeds its capacity limit, `overflowToDisk()` removes the oldest events (FIFO) and hands them to `DiskLogBuffer.persistEvents()`:

```kotlin
private fun overflowToDisk() {
    val overflowCount = ramBufferCount.get() - ramBufferSize
    val eventsToMove = mutableListOf<LogRecordData>()

    repeat(overflowCount) {
        ramBuffer.poll()?.let { eventsToMove.add(it) }  // poll() = remove head (oldest)
    }

    if (eventsToMove.isNotEmpty()) {
        diskBuffer.persistEvents(eventsToMove)
        ramBufferCount.addAndGet(-eventsToMove.size)
    }
}
```

This is scheduled every 5 seconds and also triggered immediately (on the executor) whenever `onEmit()` detects `count > ramBufferSize`. The 5-second schedule is a safety net for the case where events trickle in slowly and never hit the threshold in a single burst.

### Serialization: LogRecordData → SQLite Row

`persistEvents()` calls `toEntity()` on each record, which converts the in-memory OTel object into a flat SQLite-friendly struct:

```
LogRecordData (in-memory OTel object)
    │
    ▼ toEntity()
LogRecordEntity {
    id:                        Long    ← AUTOINCREMENT (not from OTel)
    timestampMs:               Long    ← timestampEpochNanos / 1_000_000 (indexed)
    severityText:              String? ← "ERROR", "INFO", null
    body:                      String  ← logRecord.body.asString()
    attributes:                String  ← JSON: {"event.name":"ui.tap","view.id":"btn_book"}
    resource:                  String  ← JSON: {"service.name":"schedulr","session.id":"xyz"}
    instrumentationScopeName:  String?
    instrumentationScopeVersion: String?
}
    │
    ▼ Room DAO (INSERT INTO log_records ...)
    SQLite row
```

**Important: type information is lost.** Attributes are serialized as `JSONObject.put(key, value.toString())` — every value becomes a string. An event that enters via the RAM tier with a numeric attribute `http.duration_ms = 847L` will be read back after a crash as `http.duration_ms = "847"`. This only affects disk-recovered events; RAM-tier events always retain their original types.

The conversion back — `LogRecordEntity.toLogRecordData()` — reconstructs a concrete anonymous `LogRecordData` implementation with all string-typed attributes and an invalid `SpanContext` (trace/span ID is not persisted).

### Complete Data Flow Diagram

```
App thread emits event
    │
    ▼ onEmit() — synchronous, O(1), microseconds
┌─────────────────────────────────────────────────────────────┐
│  RAM Buffer: ConcurrentLinkedQueue<LogRecordData>           │
│  • 5,000 events max (configurable)                          │
│  • Volatile — contents lost on process kill without flush   │
│  • FIFO — oldest at head, newest at tail                    │
│  • Lock-free — no blocking at insert time                   │
└─────────────────────────┬───────────────────────────────────┘
                          │ two triggers for overflow:
                          │  1. immediate: onEmit() sees count > 5000
                          │     → executor.submit { overflowToDisk() }
                          │  2. scheduled: every 5 seconds as safety net
                          ▼
                    overflowToDisk()
                    poll() N oldest events from RAM head
                    call persistEvents(eventsToMove)
                          │
                          ▼ toEntity() per event (JSON-serialize attributes)
┌─────────────────────────────────────────────────────────────┐
│  Disk Buffer: SQLite via Room  (otel_log_buffer.db)         │
│  • 50 MB max (configurable)                                 │
│  • 24h TTL — hourly cleanup job deletes expired rows        │
│  • Indexed on timestampMs for fast flushWindow() scans      │
│  • Durable — survives process kill, OOM kill, crash         │
│  • Size enforcement: deletes oldest rows + VACUUM if > 50MB │
└─────────────────────────────────────────────────────────────┘
```

### What Never Reaches Disk

Not every event goes through overflow. Three scenarios where disk is bypassed entirely:

**1. CONTINUOUS mode with short flush interval.** The periodic flush exports all RAM events every 30 seconds (default). On a typical app with a few hundred events per 30s, the RAM buffer never approaches 5,000. Events are exported from RAM and removed — they never overflow.

**2. Policy fires before RAM fills.** In CONDITIONAL mode on a low-traffic screen, a single `http.error` event may trigger `flushWindow(5)` while the RAM buffer is still at, say, 200 events. All 200 are exported from RAM and removed. No overflow occurs.

**3. Events in the export window.** `flushWindow()` exports events that are still in RAM. Only events **outside** the flush window, and **older than the flush window**, get evicted to disk next time overflow runs. Events that have already been exported are removed from RAM by the identity-set removal, reducing pressure on the overflow path.

Disk is mostly used in these situations:
- **CONDITIONAL mode on a high-traffic screen** — taps, scrolls, and text-input events accumulate quickly. A 60-second session with active interaction can easily produce 500–2,000 events, pushing the RAM buffer toward overflow before any policy fires.
- **Offline sessions** — exports fail, RAM fills, older events push to disk. When connectivity returns, `flushWindow()` scans both tiers.
- **Post-crash recovery** — on `shutdown()`, RAM is flushed but disk is intentionally left intact so the next process start can recover the events around the crash.

### Capacity, TTL, and Eviction

| Parameter | Default | Set via |
|---|---|---|
| RAM buffer capacity | 5,000 events | `Builder.setRamBufferSize(n)` |
| Disk buffer max size | 50 MB | `Builder.setDiskBufferMb(n)` |
| Disk buffer TTL | 24 hours | `Builder.setDiskBufferTtlHours(n)` |

**TTL cleanup** runs every hour on the background executor. Events older than `ttlHours` are deleted:

```kotlin
val expiryTimeMs = System.currentTimeMillis() - (ttlHours * 60 * 60 * 1000L)
logDao.deleteOlderThan(expiryTimeMs)
```

**Size enforcement** runs after every disk insert. If the database file exceeds `maxSizeMb`, the oldest rows are deleted proportionally, then `VACUUM` is run to reclaim space:

```kotlin
val excessRatio = (currentSizeMb - maxSizeMb) / currentSizeMb
val deleteCount = (totalCount * excessRatio).toInt() + 100
logDao.deleteOldest(deleteCount)
database.openHelper.writableDatabase.execSQL("VACUUM")
```

**Configuring the builder:**

```kotlin
val processor = MobileLogRecordProcessor.builder(context)
    .setExporter(retryableExporter)
    .setConfig(mobileConfig)
    .setMeter(meter)
    .setRamBufferSize(8000)         // larger RAM buffer for high-traffic screens
    .setDiskBufferMb(100)           // 100 MB disk, useful for long offline periods
    .setDiskBufferTtlHours(48)      // keep events 48h for post-incident analysis
    .build()
```

---

## Selective Flush: The Tail Sampling Mechanism

### flushWindow(minutes): How It Works

`flushWindow(windowMinutes)` exports all events from the last N minutes from both tiers, then removes exactly those events from the buffers.

```kotlin
fun flushWindow(windowMinutes: Int): CompletableResultCode {
    val windowStartMs = System.currentTimeMillis() - (windowMinutes * 60 * 1000L)

    // 1. Collect RAM events in window (by timestamp)
    val ramEventsToFlush = mutableListOf<LogRecordData>()
    ramBuffer.forEach { logRecord ->
        if (logRecord.timestampEpochNanos / 1_000_000 >= windowStartMs) {
            ramEventsToFlush.add(logRecord)
        }
    }

    // 2. Collect disk events in window (indexed range scan)
    val diskEventsToFlush = diskBuffer.getEventsInWindow(windowStartMs)
    val allEventsToFlush = ramEventsToFlush + diskEventsToFlush

    // 3. Export in batches of 100
    val results = allEventsToFlush.chunked(100).map { batch -> exporter.export(batch) }
    val result = CompletableResultCode.ofAll(results)

    // 4. On success: remove exactly the exported events
    result.whenComplete {
        if (result.isSuccess) {
            // RAM: use identity set — safe against concurrent inserts during export
            val exportedIds = Collections.newSetFromMap(IdentityHashMap<LogRecordData, Boolean>())
            exportedIds.addAll(ramEventsToFlush)
            var removed = 0
            ramBuffer.removeIf { event ->
                exportedIds.contains(event).also { matched -> if (matched) removed++ }
            }
            ramBufferCount.addAndGet(-removed)

            // Disk: Room transaction handles atomicity
            diskBuffer.deleteEventsInWindow(windowStartMs)
        }
        // On failure: events stay in buffer — RetryableExporter will retry
    }
    return result
}
```

The key design choice: events are only removed **after confirmed export success**. If the network is unavailable, events remain in the buffer and will be exported by the next triggered flush.

### Thread Safety in flushWindow

A subtle race exists: new events can arrive during the export window. The snapshot approach handles this correctly:

```
t=0ms   flushWindow called, windowStartMs computed
t=1ms   RAM snapshot taken (ramEventsToFlush = [A, B, C])
t=2ms   new event D arrives, added to ramBuffer
t=50ms  export of [A, B, C] completes
t=51ms  identity set removal: removes A, B, C from ramBuffer
        D is NOT in the identity set → stays in buffer ✓
```

Using a plain `clear()` + `addAll()` at step t=51 would silently drop D. The `IdentityHashMap`-based removal is what prevents that.

---

## Export Policies as Tail Sampling Rules

### Policy DSL Structure

Policies are defined in JSON and loaded from the gateway's `/config` endpoint (polled every 5 minutes by default). While a config fetch is pending, the built-in default policies are used.

```json
{
  "id": "http-errors-on-cellular",
  "enabled": true,
  "match": {
    "logical_operator": "and",
    "attributes": {
      "event.name": { "equals": "http.error" },
      "http.status_code": { "gte": 500 }
    },
    "device": {
      "network": ["cellular"]
    }
  },
  "actions": {
    "flush_window_minutes": 5
  }
}
```

This policy fires when an HTTP 5xx error is emitted on a cellular connection, and exports the preceding 5 minutes of events. On Wi-Fi the policy is silent — cellular users get better incident coverage because they're more likely to be in a constrained environment.

### Built-In Default Policies

Three policies are hardcoded in `PolicyEvaluator` and are always active as a fallback:

| Policy ID | Trigger attribute | `event.name` value | Flush window |
|---|---|---|---|
| `ui-freeze-detector` | `event.name` equals | `ui.freeze` | 2 minutes |
| `crash-recovery` | `event.name` equals | `app.crash` | 5 minutes |
| `http-error-detector` | `event.name` equals | `http.error` | 5 minutes |

These cover the three most common situations where you want retroactive context: a UI freeze/ANR, an app crash, and an HTTP error. They fire even if the gateway is unreachable.

### Evaluation Pipeline

Every event in CONDITIONAL or HYBRID mode is evaluated against all active policies asynchronously (off the main thread):

```
onEmit(logRecord)
    └─► executor.submit { evaluatePolicies(logRecord) }
                │
                ▼
        PolicyEvaluator.evaluate(logRecord)
                │
                ├─► policyConfig.get() ?? defaultPolicies   ← atomic read
                │
                └─► for each policy:
                        1. check policy.enabled
                        2. match attributes (equals/gt/lt/gte/lte/contains/regex)
                        3. match geo  (country/region/timezone/locale)
                        4. match device (network/battery/os/appVersion/deviceClass)
                        5. combine with logical_operator (and | or)
                        │
                        └─► match? → return PolicyMatchResult(flushWindowMinutes)
                                │
                                ▼
                    captureDeviceMetrics(reason)    ← snapshot health at trigger time
                    flushWindow(flushWindowMinutes)  ← tail sample: export N-minute window
```

### Attribute Conditions

The following condition operators are supported on any log attribute:

| Operator | Type | Example |
|---|---|---|
| `equals` | string | `{ "equals": "ui.freeze" }` |
| `contains` | string | `{ "contains": "ERROR" }` |
| `regex` | string | `{ "regex": "^http\\.5\\d\\d$" }` |
| `gt` | numeric | `{ "gt": 2000 }` — greater than |
| `lt` | numeric | `{ "lt": 100 }` — less than |
| `gte` | numeric | `{ "gte": 500 }` — greater than or equal |
| `lte` | numeric | `{ "lte": 30 }` — less than or equal |

The special key `event.name` maps to `logRecord.body.asString()`. All other keys are looked up in `logRecord.attributes`.

Multiple attribute conditions within one policy are always combined with AND (all must match), regardless of the top-level `logical_operator`. The `logical_operator` applies across the three match dimensions: attributes, geo, and device.

### Geo and Device Context Matching

`ContextSnapshotProvider.getSnapshot()` reads non-PII device context at evaluation time:

**Geo fields** (read from `Locale` and `TimeZone`):

| Field | Source | Example values |
|---|---|---|
| `country` | `Locale.getDefault().country` | `"US"`, `"DE"` |
| `region` | best-effort from locale | `"CA"`, `"NY"` |
| `timezone` | `TimeZone.getDefault().id` | `"America/Los_Angeles"` |
| `locale` | `Locale.getDefault().toLanguageTag()` | `"en-US"`, `"de-DE"` |

**Device fields** (read from system services):

| Field | Source | Example values |
|---|---|---|
| `network` | `ConnectivityManager` | `"wifi"`, `"cellular"`, `"offline"` |
| `battery` | `BatteryManager` | `"charging"`, `"low"`, `"normal"` |
| `deviceClass` | screen density/size heuristic | `"phone"`, `"tablet"` |
| `buildChannel` | `BuildConfig.BUILD_TYPE` | `"prod"`, `"beta"`, `"internal"` |
| `osVersionMin/Max` | `Build.VERSION.SDK_INT` | `26`, `34` |
| `appVersion` | `PackageInfo.versionName` | `"1.2.3"` |

Timezone matching supports glob patterns via the `wildcard` suffix:

```json
"timezone": ["America/wildcard"]   // matches America/Los_Angeles, America/New_York, etc.
"timezone": ["US/wildcard"]         // matches US/Pacific, US/Eastern, etc.
"timezone": ["Europe/Berlin"]       // exact match only
```

### Writing a Custom Policy

Custom policies are served from the gateway's `/config` endpoint. Here is an example that fires on a slow booking request from a beta user on a low-battery phone:

```json
{
  "id": "slow-booking-beta",
  "enabled": true,
  "match": {
    "logical_operator": "and",
    "attributes": {
      "event.name":        { "equals": "booking.submit" },
      "http.duration_ms":  { "gt": 3000 }
    },
    "device": {
      "battery":       ["low"],
      "buildChannel":  ["beta", "internal"],
      "deviceClass":   ["phone"]
    }
  },
  "actions": {
    "flush_window_minutes": 3
  }
}
```

To use this, POST the policy array to the gateway's `/admin/policies` endpoint (or edit the SQLite `policies` table directly for local testing). The SDK fetches the updated config within `configPollIntervalSeconds` (default: 300s).

---

## Export Modes and the Buffer

The buffer always captures every event. The export mode only affects **when events leave the buffer**:

| Mode | Policy evaluation | Periodic flush | Behavior |
|---|---|---|---|
| `CONDITIONAL` | Yes | No | Events exported only when a policy fires. Battery-optimal. |
| `CONTINUOUS` | No | Yes (every `traceExportIntervalSeconds`, default 30s) | All buffered events exported on schedule. No policy evaluation. |
| `HYBRID` | Yes | Yes | Both: periodic flush on schedule, plus policy-triggered flush windows. |

In CONTINUOUS mode, `onEmit()` skips the `evaluatePolicies()` submit. The periodic flush calls `forceFlush()` which exports everything in both tiers. This is equivalent to a conventional SDK but with crash-recovery durability from the disk tier.

```kotlin
// CONTINUOUS: periodic export every traceExportIntervalSeconds
executor.scheduleAtFixedRate(
    {
        if (ramBufferCount.get() > 0) forceFlush()
    },
    flushIntervalSeconds, flushIntervalSeconds, TimeUnit.SECONDS
)
```

In CONDITIONAL mode, events can accumulate in the buffer indefinitely until a policy fires. The disk tier prevents data loss when the RAM buffer fills:

```
Quiet session (no trigger fires):
  t=0min:   user opens app, page spans + taps buffered in RAM
  t=10min:  RAM buffer full (5000 events) → oldest overflow to disk
  t=20min:  user books appointment, POST /api/appointments returns 500
            → http.error event emitted
            → http-error-detector policy fires
            → flushWindow(5) exports t=15min to t=20min slice
  t=20min:  exported: last 5 minutes of taps, navigations, network calls
  t=20min:  events before t=15min remain in disk buffer (not exported)
  t=24h:    TTL cleanup deletes all remaining disk events
```

---

## Retry and Durability

### RetryableExporter: Exponential Backoff

[RetryableExporter.kt](../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/RetryableExporter.kt) wraps the OTLP exporter and retries failed exports with exponential backoff:

| Attempt | Delay before retry |
|---|---|
| 1st retry (attempt 1) | 1s |
| 2nd retry (attempt 2) | 2s |
| 3rd retry (attempt 3) | 4s |
| After 3 retries: | fail — events stay in buffer |

```kotlin
private fun calculateBackoff(attempt: Int): Long {
    val exponentialDelay = initialDelayMs * (2.0.pow(attempt.toDouble())).toLong()
    return min(exponentialDelay, maxDelayMs)   // capped at 60s
}
```

On failure after all retries, `flushWindow()` receives a failed `CompletableResultCode` and skips the buffer removal step. The events remain available for the next flush attempt.

### Crash Recovery

On `shutdown()`, RAM events are flushed to the OTLP exporter, but disk events are **intentionally left in the database**:

```kotlin
override fun shutdown(): CompletableResultCode {
    // Flush RAM events before shutdown
    val ramEvents = ramBuffer.toList()
    ramBuffer.clear()
    // ... export ramEvents ...

    // Disk events are NOT cleared here — they survive for crash recovery.
    // The next process start will find them and can re-export.
}
```

On the next app start, `crash-recovery` policy may fire (if a `app.crash` log was emitted before the crash), which calls `flushWindow(5)`. This reaches `diskBuffer.getEventsInWindow()` and picks up the surviving disk events, providing a full 5-minute tail of the crash context.

---

## Head Sampling vs Tail Sampling: Comparison

| Aspect | Standard OTel head sampling | This SDK (tail sampling) |
|---|---|---|
| Decision timing | At root span start — before any data | After event is buffered — data always exists |
| Dropped traces | Dropped permanently, no recovery | Never dropped; only export scope changes |
| Crash context | Lost if trace was dropped | Full context always available in buffer |
| Battery impact | Low (fewer events captured) | Slightly higher (all events buffered) |
| Bandwidth | Controlled by sample rate | Controlled by policy frequency |
| Per-trace resolution | No — decision is all-or-nothing at start | Yes — export policies can target specific event types |
| Offline support | Export fails silently if offline | Disk tier buffers until network returns |

Our `DynamicSampler` **does** use head sampling for traces (spans). The tail sampling applies to **logs** — which is where all auto-capture events, errors, crashes, and UI interactions are emitted. Spans (traces/network calls) use a conventional `TraceIdRatioBased` sampler with force-sample rules for `page.*` and `app.startup` spans.

---

## Auto-Instrumentation and the Buffer

Every auto-captured signal described in [AUTO_INSTRUMENTATION.md](AUTO_INSTRUMENTATION.md) routes through `MobileLogRecordProcessor`. The wiring is:

```
AutoCaptureManager registers MotionEvent callbacks
    └─► TapCapture.onActionUp()
            ├── path A (valid sampled parent): span.end()
            │       └─► MobileLogRecordProcessor.onEmit()
            │                   └─► RAM buffer
            └── path B (no parent): logger.emit(logRecord)
                        └─► MobileLogRecordProcessor.onEmit()
                                    └─► RAM buffer
```

`ErrorInstrumentation` catches uncaught exceptions and calls:

```kotlin
MobileOtel.reportError(throwable, attributes)
    └─► logger.emit("app.crash", ...)
            └─► MobileLogRecordProcessor.onEmit()
                    ├─► ramBuffer.offer(logRecord)
                    └─► evaluatePolicies(logRecord)   ← crash-recovery policy fires here
                                └─► flushWindow(5)    ← exports last 5 minutes
```

This means crash context is flushed synchronously before the process exits (within the `flushWindow` export timeout). The disk tier provides a fallback if the process is killed before the flush completes.

---

## Observability of the Buffer Itself

Three OTel async gauges expose buffer pressure in real time:

```kotlin
meter.gaugeBuilder("buffer.ram.events")
    .buildWithCallback { obs -> obs.record(ramBufferCount.get().toLong()) }

meter.gaugeBuilder("buffer.ram.capacity")
    .buildWithCallback { obs -> obs.record(ramBufferSize.toLong()) }

meter.gaugeBuilder("buffer.disk.events")
    .buildWithCallback { obs -> obs.record(diskBuffer.getEventCount().toLong()) }
```

These appear in Dash0 as gauge metrics. A `buffer.ram.events` value close to `buffer.ram.capacity` indicates the app is generating events faster than policies are flushing them — either increase RAM buffer size, tune the policy frequency, or switch to HYBRID mode.

You can also call `processor.getBufferStats()` programmatically:

```kotlin
val stats = processor.getBufferStats()
Log.d("Buffer", "RAM: ${stats.ramBufferSize}/${stats.ramBufferCapacity}, Disk: ${stats.diskBufferSize} events")
```

---

## End-to-End Walkthrough

The following traces through a complete user session that ends in an HTTP error on a cellular connection using the custom policy from the example above.

```
t=0s    OTelMobile.start() called
        MobileLogRecordProcessor created (RAM=5000, Disk=50MB, TTL=24h)
        PolicyEvaluator init: fetches /config, falls back to defaultPolicies
        AutoCaptureManager registered

t=1s    User taps "Book" tab
        AutoCaptureManager fires TapCapture
        → onEmit(ui.tap @ t=1s)      RAM: [tap@1s]

t=2s    Fragment.onResume("BookFragment")
        → onEmit(screen.view @ t=2s)  RAM: [tap@1s, screen@2s]
        → startPageSpan("page.BookFragment") installed in OTel Context

t=3s–8s User fills in form fields
        → onEmit(ui.text_input × 3)   RAM: [tap, screen, input×3]
        → onEmit(ui.tap × 4)          RAM: [tap, screen, input×3, tap×4]

t=9s    User taps "Submit"
        → booking.submit span started as child of page.BookFragment span
        → OTelNetworkInterceptor: POST /api/appointments starts
        → onEmit(http.request @ t=9s) RAM: [... http.request@9s]

t=12s   POST /api/appointments returns 503
        → OTelNetworkInterceptor: http.error event emitted
        → onEmit(http.error @ t=12s)  RAM: [... http.request@9s, http.error@12s]
        → PolicyEvaluator runs (async):
            policy "http-errors-on-cellular":
              event.name == "http.error"  ✓
              device.network == "cellular" ✓ (checked via ContextSnapshotProvider)
              → PolicyMatchResult(flushWindowMinutes=5)
        → captureDeviceMetrics(WORKFLOW_TRIGGER)
        → flushWindow(5):
            windowStartMs = t=7s (now minus 5 minutes)
            RAM events in window: [ui.tap, ui.text_input×3, ui.tap×4, http.request, http.error]
            Disk events in window: [] (no overflow yet)
            → export 10 events in 1 batch via RetryableExporter → OTLP/gRPC
            → on success: remove those 10 events from RAM buffer

t=12s   Dash0 receives:
            - All user interactions on the BookFragment since t=7s
            - The failing HTTP request and its attributes
            - Device context snapshot (cellular network, battery normal, etc.)
            - The booking.submit span with full trace waterfall
```

Events before `t=7s` (the initial tap to navigate, any earlier screens) were not exported — they remain in the buffer until the next policy fires or they exceed the TTL.
