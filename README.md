# Mobile OTel SDK

OpenTelemetry-native Android observability SDK with intelligent buffering, on-device export policies, and predictive telemetry.

## What It Does

The SDK captures telemetry locally in a two-tier ring buffer (RAM + SQLite), evaluates export policies on-device, and selectively flushes only relevant event windows. This dramatically reduces data egress while preserving full context around problems.

**Key capabilities:**
- **Auto-instrumentation** — Errors, vitals, predictive health, UI interactions all wired automatically
- **Two-tier buffering** — RAM (5000 events) → disk (50MB, 24h TTL), survives crashes and offline
- **Conditional export** — Zero bandwidth when nothing goes wrong (CONDITIONAL mode)
- **Selective flush** — Export last N minutes around a problem, not everything
- **Predictive flush** — Pre-emptive export when crash risk or network loss risk is high
- **Visual policy builder** — Non-technical users author export policies via drag-and-drop UI

## Quick Start

### Android SDK Integration

```kotlin
// In Application.onCreate()
OTelMobile.start(this, MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "https://collector.example.com:4317"
))

// That's it. All auto-instrumentation is now active:
// - Error capture (uncaught, coroutine, RxJava) → auto flush
// - Vitals (app start, jank, memory, thermal) → OTel metrics
// - Predictive export (crash/network risk → pre-emptive flush)
// - Auto-capture (taps, scrolls, freezes, ANR, lifecycle)
// - Ring buffer + policy evaluation
```

### Optional: Custom Events & Error Reporting

```kotlin
// Send custom events
MobileOtel.sendEvent("checkout.completed", mapOf(
    "item_count" to 3,
    "total" to 42.99
))

// Report caught exceptions
try { riskyOperation() } catch (e: Exception) {
    MobileOtel.reportError(e, mapOf("context" to "checkout"))
}

// Coroutine error handling
val scope = CoroutineScope(
    Dispatchers.IO + MobileOtel.getCoroutineExceptionHandler()!!
)

// User identity
MobileOtel.identify(UserIdentity(userId = "user123"))

// Manual flush (all or windowed)
MobileOtel.forceFlush()              // Flush everything
MobileOtel.forceFlush(windowMinutes = 5)  // Last 5 minutes only
```

### Optional: Network Instrumentation

```kotlin
// Add OTel interceptor to your OkHttpClient
val client = OkHttpClient.Builder()
    .addInterceptor(OTelNetworkInterceptor.create(
        context = applicationContext,
        config = NetworkConfig.production(),
        tracer = OTelMobile.getTracer("network"),
        propagator = openTelemetry.propagators.textMapPropagator
    ))
    .build()
```

## Project Structure

```
mobile-otel/
├── otel-android-mobile/          # Android SDK library (Kotlin)
│   └── src/main/java/.../mobile/
│       ├── MobileOtel.kt         # Facade — wires all modules, public API
│       ├── OTelMobile.kt         # Auto-capture entry point (delegates to MobileOtel)
│       ├── MobileLoggerProvider.kt  # OTel LoggerProvider + processor
│       ├── autocapture/          # Tap, scroll, freeze, ANR, lifecycle
│       ├── breadcrumb/           # Journey breadcrumbs (circular buffer)
│       ├── buffering/            # Two-tier ring buffer (RAM + SQLite)
│       ├── config/               # MobileConfig, NetworkConfig, etc.
│       ├── core/                 # SessionManager, PiiScrubber
│       ├── errors/               # ErrorInstrumentation (uncaught, coroutine, RxJava)
│       ├── export/               # EnrichingLogRecordExporter, RetryableExporter
│       ├── network/              # OTelNetworkInterceptor (OkHttp)
│       ├── policy/               # PolicyEvaluator (DSL engine)
│       ├── predictive/           # PredictiveExportPolicy, DeviceHealthMonitor
│       ├── sampling/             # Dynamic sampling
│       └── vitals/               # VitalsCollector, JankDetector, AppStart
│
├── gateway/                      # Go HTTP server
│   ├── main.go                   # Routes: /ingest, /config, /health, /admin/*
│   └── internal/                 # OTEL export, SQLite, config versioning
│
├── control-plane-ui/             # React visual policy builder
│   └── src/
│       ├── components/WorkflowBuilder.tsx  # React Flow canvas (8 node types)
│       └── utils/graphToDSL.ts            # Graph → JSON DSL compiler
│
├── collector-processor/          # Custom OTEL Collector processor (Go)
│   └── mobilepolicyprocessor/
│
├── examples/demo-app/            # Android demo app
│   └── android/                  # Demo scenarios (freeze, crash, network error)
│
├── k8s/                          # Kubernetes manifests for OTEL Collector
├── DESIGN.md                     # Architecture & design document
├── BACKLOG.md                    # Prioritized remaining work
└── CLAUDE.md                     # AI assistant guidance
```

## Architecture

```
┌────────────────────┐      ┌─────────────┐      ┌────────────────┐
│ Android SDK        │      │ Gateway     │      │ OTEL Collector │
│                    │      │ (Go, :8080) │      │ :4317, :4318   │
│ OTelMobile.start() │      │             │      │                │
│  ├─ Errors    ──┐  │      │ /ingest     │      │ + mobilepolicy │
│  ├─ Vitals    ──┤  │─────►│ /config     │─────►│   processor    │──► Backends
│  ├─ Predictive──┤  │ OTLP │ /admin/*    │ OTLP │                │
│  ├─ AutoCapture─┤  │      │             │      │                │
│  └─ RingBuffer──┘  │      └──────┬──────┘      └────────────────┘
│                    │             ▲
│  PolicyEvaluator   │             │ /api proxy
│  (DSL triggers)    │      ┌──────┴──────┐
│                    │      │Control Plane│
│  GET /config ──────│─────►│React + Vite │
│  (poll 60s)        │      │:3000        │
└────────────────────┘      └─────────────┘
```

## Export Modes

| Mode | Behavior | Battery Impact |
|------|----------|----------------|
| **CONDITIONAL** | Export only when policy triggers match | <0.5% |
| **CONTINUOUS** | Periodic export (traces 30s, metrics 60s) | 3-5% |
| **HYBRID** | Periodic + trigger-based | 1-2% |

## Flush Triggers

1. **Policy match** — DSL conditions met (ui.freeze, crash, http 5xx cascade)
2. **Error capture** — Uncaught exception / coroutine / RxJava error → immediate flush
3. **Predictive** — Crash risk ≥ 0.7 or network loss risk ≥ 0.7 → pre-emptive flush
4. **Low memory** — Android ComponentCallbacks2 memory pressure signal
5. **App recovery** — Crash/ANR/force-quit marker detected on next launch
6. **Manual** — `MobileOtel.forceFlush()` or `forceFlush(windowMinutes = 5)`
7. **Periodic** — CONTINUOUS/HYBRID mode timers

## Technology Stack

| Component | Key Dependencies |
|-----------|-----------------|
| Android SDK | Kotlin, OpenTelemetry SDK 1.58.0, Room 2.8.4, OkHttp 4.12.0, Coroutines 1.10.2 |
| Gateway | Go 1.24, OTEL SDK 1.39.0, gRPC 1.77.0, SQLite3 |
| Control Plane | React 18, React Flow 11.10.4, TypeScript 5.3, Vite 5 |
| Infrastructure | Kubernetes, OpenTelemetry Collector |

## Building

```bash
# Android SDK (via demo app)
cd examples/demo-app && ./gradlew :otel-android-mobile:build

# Gateway
cd gateway && go build ./...

# Control Plane UI
cd control-plane-ui && npm install && npm run build

# Run all tests
./run-tests.sh
```

## Documentation

- **[DESIGN.md](DESIGN.md)** — Architecture, core concepts, export policy DSL, privacy defaults, OTel compliance
- **[BACKLOG.md](BACKLOG.md)** — Prioritized remaining work across 5 tracks
- **[CLAUDE.md](CLAUDE.md)** — AI assistant context and build commands

## License

Apache 2.0
