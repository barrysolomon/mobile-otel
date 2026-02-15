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
│  MobileOtel Facade                                               │
│    ├─ identify() / clearIdentity() / terminateSession()          │
│    ├─ sendEvent() / reportError()                                │
│    ├─ addGlobalAttribute() / removeGlobalAttribute()             │
│    └─ forceFlush() / setModuleEnabled()                          │
│                                                                   │
│  Instrumentation Modules (opt-in)                                │
│    ├─ VitalsCollector (cold/warm start, jank, ANR, memory)       │
│    ├─ NavigationInstrumentation (Activity, deep links)           │
│    ├─ OTelNetworkInterceptor (OkHttp, trace propagation)         │
│    ├─ ErrorInstrumentation (uncaught, coroutine, RxJava)         │
│    └─ AutoCaptureManager (tap, scroll, freeze detection)         │
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
2. **Low memory** — Android ComponentCallbacks2 signals memory pressure
3. **App recovery** — Crash marker or force-quit detected on restart
4. **Manual** — User/developer calls `forceFlush()`
5. **Periodic** — CONTINUOUS/HYBRID mode timers

### Session & Identity
- Session ID (UUID, persisted) with 15-min inactivity timeout
- Optional user identity (`identify()` / `clearIdentity()`) with SHA-256 email hashing
- Global attributes attached to all telemetry
- All stored in EncryptedSharedPreferences

### Journey Breadcrumbs
Circular buffer (50 entries) of navigation, tap, scroll, network, and error breadcrumbs. Attached to critical events (crashes, errors, freezes) to provide user journey context for debugging.

## SDK Modules

All modules are opt-in via `MobileConfig`. Each uses the `mobile.*` reserved event namespace.

| Module | Key Signals | Privacy |
|--------|-------------|---------|
| **Vitals** | cold/warm start, TTID, jank, ANR risk, memory pressure, thermal | Aggregated stats only |
| **Navigation** | screen transitions, deep links, back presses | URL scrubbing, path ID replacement |
| **Network** | OkHttp spans, timing, status codes, size buckets | URL scrubbing, header allowlist |
| **Errors** | uncaught exceptions, coroutine, RxJava | Stack trace scrubbing, 5-min dedupe, 10/min rate limit |
| **AutoCapture** | tap, scroll, freeze, ANR detection | Coordinate bucketing, privacy modes |

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
