# Design Document: Mobile OTel SDK

## Vision

Build an **OpenTelemetry-native Android observability SDK** that captures events locally, evaluates export policies on-device, and selectively flushes buffered data to minimize bandwidth while preserving full context around problems. The SDK is designed for upstream contribution to `opentelemetry-android` / `opentelemetry-collector-contrib`.

**Key differentiators vs web SDKs:**
- Two-tier buffering (RAM + SQLite) survives crashes and offline periods
- CONDITIONAL export mode: zero bandwidth when nothing goes wrong
- On-device policy engine: selective flush based on DSL-defined triggers
- Predictive telemetry: pre-emptive flush before crashes/network loss
- Visual control plane: non-technical users author export policies

## System Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│ ANDROID SDK (otel-android-mobile/)                               │
│                                                                   │
│  Entry Points                                                    │
│    OTelMobile.start()  → delegates to MobileOtel.initialize()   │
│    MobileOtel.initialize() → wires all modules automatically    │
│                                                                   │
│  MobileOtel Facade                                               │
│    ├─ identify() / clearIdentity() / terminateSession()          │
│    ├─ sendEvent() / reportError()                                │
│    ├─ addGlobalAttribute() / removeGlobalAttribute()             │
│    ├─ forceFlush(windowMinutes?) / getBufferStats()              │
│    ├─ getCurrentPrediction() / getErrorStatistics()              │
│    └─ getCoroutineExceptionHandler()                             │
│                                                                   │
│  Auto-Initialized Modules (all wired by initialize())           │
│    ├─ ErrorInstrumentation (uncaught, coroutine, RxJava → flush)│
│    ├─ VitalsCollector (cold/warm start, jank, ANR, memory)       │
│    ├─ PredictiveExportPolicy (crash/network risk → flush)        │
│    ├─ HealthMetricsCollector (device health → OTel metrics)      │
│    └─ AutoCaptureManager (tap, scroll, freeze, ANR detection)    │
│                                                                   │
│  User-Wired Modules                                              │
│    ├─ NavigationInstrumentation (Activity, deep links)           │
│    └─ OTelNetworkInterceptor (OkHttp, trace propagation)         │
│                                                                   │
│  Core Pipeline                                                    │
│    SessionManager → BreadcrumbBuffer → MobileLogRecordProcessor  │
│    (enrich all)    (circular, N=50)   (RAM 5K → Disk 50MB)      │
│         │                                   │                     │
│         └── PolicyEvaluator ────────────────┘                     │
│              (DSL trigger matching → selective flush)              │
│                                                                   │
│  Export: RetryableExporter → OTLP/gRPC :4317                    │
└──────────────────────────────────────────────────────────────────┘
         │ OTLP/gRPC                    GET /config (60s poll)
         ▼                                   │
┌──────────────────┐                ┌────────┴─────────┐
│ OTEL Collector    │                │ Gateway (Go)     │
│ + mobilepolicy    │◄───────────────│ :8080            │
│   processor       │  OTLP/gRPC    │ /ingest, /config │
│ :4317, :4318      │                │ /admin/*         │
└────────┬─────────┘                └────────┬─────────┘
         │                                   ▲
         ▼                                   │ /api proxy
    Backends                         ┌───────┴─────────┐
    (Loki, Dash0, etc.)              │ Control Plane UI │
                                     │ React + Vite     │
                                     │ :3000            │
                                     └─────────────────┘
```

## Core Concepts

### Two-Tier Ring Buffer
Events flow: **RAM buffer** (ConcurrentLinkedQueue, 5000 events, lock-free) → overflow to **Disk buffer** (Room/SQLite, 50MB, 24h TTL). Oldest-first eviction, never blocks UI thread. Disk buffer survives crashes.

### Export Modes
| Mode | Behavior | Battery |
|------|----------|---------|
| **CONDITIONAL** | Export only when policy triggers match | <0.5% |
| **CONTINUOUS** | Periodic export (traces 30s, metrics 60s) | 3-5% |
| **HYBRID** | Periodic + trigger-based | 1-2% |

### Export Policy DSL
Policies are authored visually in the Control Plane UI (React Flow, 8 node types), compiled to JSON DSL, published to gateway, polled by devices every 60s. Android evaluates the compiled DSL deterministically.

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

**Operators:** equals, gt, lt, gte, lte, contains, regex
**Actions:** flush_window, annotate_trigger, set_sampling, flush_all, capture_device_metrics

### Flush Triggers
1. **Policy match** — DSL conditions met (ui.freeze, crash, http 5xx cascade)
2. **Error capture** — Uncaught exception, coroutine error, or RxJava error triggers immediate flush via ErrorInstrumentation callback
3. **Predictive** — Crash risk ≥ 0.7 or network loss risk ≥ 0.7 triggers pre-emptive flush via PredictiveExportPolicy
4. **Low memory** — Android ComponentCallbacks2 signals memory pressure
5. **App recovery** — Crash marker, ANR marker, or force-quit detected on restart
6. **Manual** — Developer calls `MobileOtel.forceFlush()` or `forceFlush(windowMinutes = 5)`
7. **Periodic** — CONTINUOUS/HYBRID mode timers

### Session & Identity
- Session ID (UUID, persisted) with 15-min inactivity timeout
- Optional user identity (`identify()` / `clearIdentity()`) with SHA-256 email hashing
- Global attributes attached to all telemetry
- All stored in EncryptedSharedPreferences

### Journey Breadcrumbs
Circular buffer (50 entries) of navigation, tap, scroll, network, and error breadcrumbs. Attached to critical events (crashes, errors, freezes) to provide user journey context for debugging.

## SDK Modules

### Auto-Initialized Modules
These modules are automatically wired by `MobileOtel.initialize()` / `OTelMobile.start()`. No manual setup needed.

| Module | Key Signals | Privacy | Wired Via |
|--------|-------------|---------|-----------|
| **Errors** | uncaught exceptions, coroutine, RxJava | Stack trace scrubbing, 5-min dedupe, 10/min rate limit | `ErrorInstrumentation.initialize()` |
| **Vitals** | cold/warm start, TTID, jank, ANR risk, memory, thermal | Aggregated stats only | `VitalsCollector.initialize()` |
| **Predictive** | crash risk, network loss, performance, battery drain | On-device only, no raw data exported | `PredictiveExportPolicy.builder()` |
| **Health Metrics** | device memory, battery, storage, thermal as OTel metrics | Device-level aggregates | `HealthMetricsCollector.builder()` |
| **AutoCapture** | tap, scroll, freeze, ANR, lifecycle, recovery | Coordinate bucketing, privacy modes | `AutoCaptureManager` (via OTelMobile) |

### User-Wired Modules
These require manual integration because they depend on user's specific HTTP client or navigation setup.

| Module | Key Signals | Privacy | Integration |
|--------|-------------|---------|-------------|
| **Network** | OkHttp spans, timing, status codes, size buckets | URL scrubbing, header allowlist | Add `OTelNetworkInterceptor` to OkHttpClient |
| **Navigation** | screen transitions, deep links, back presses | URL scrubbing, path ID replacement | (Planned: Compose NavHost, Fragment lifecycle) |

## Privacy Defaults (always-on)
- Email hashed (SHA-256)
- URL query params scrubbed
- Path UUIDs/IDs replaced with placeholders
- Stack traces scrubbed (user-specific paths removed)
- PII regex detection (emails, phones, credit cards, SSNs)
- Element IDs scrubbed by default

## OTel Compliance Constraints
- Use official OTEL SDK interfaces (LoggerProvider, TracerProvider, MeterProvider)
- Export via OTLP/gRPC only (no custom protocols)
- Follow OTEL semantic conventions for all attributes
- No forking of OTEL SDK, no proprietary backends
- The SDK library must be vendor-neutral and Apache-2.0

## Repository Split Plan
1. **`otel-android-mobile/`** — Publishable Android library (upstream target: `opentelemetry-android`)
2. **`collector-processor/`** — OTEL Collector processor (upstream target: `opentelemetry-collector-contrib`)
3. **Everything else** (gateway, control-plane-ui, examples, k8s) — Demo/reference implementation, stays external

## Demo Scenarios
These three scenarios must always work end-to-end:

**A) UI Freeze** — `ui.freeze` or `ui.jank` with `duration_ms > 2000` → flush last 2 min of session
**B) Crash Recovery** — Crash marker written before crash → next launch flushes last 5 min
**C) Network Error Spike** — HTTP 500+ on `/appointments` → targeted session flush + 100% sampling for 10 min
