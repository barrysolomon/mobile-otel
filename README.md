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

```text
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
│       ├── sampling/             # DynamicSampler (page spans force sampling.priority=high)
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
├── examples/demo-app/            # Android demo app — Schedulr (medical scheduling)
│   └── android/
│       ├── ConfigActivity.kt         # OTel SDK settings (buffering, export, sampling)
│       ├── Dash0ConfigActivity.kt    # Dash0 backend connection (endpoint, auth, dataset)
│       └── SchedulingActivity.kt     # Main activity with debug toolbar + fault injection
│
├── k8s/                          # Kubernetes manifests for OTEL Collector
├── DESIGN.md                     # Architecture & design document
├── BACKLOG.md                    # Prioritized remaining work
└── CLAUDE.md                     # AI assistant guidance
```

## Architecture

```text
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
| --- | --- | --- |
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
| --- | --- |
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

# Run unit tests (Android + Go)
./run-tests.sh

# Run Dash0 telemetry scenario tests (requires connected emulator/device)
./run-dash0-scenarios.sh --all                        # All 4 suites
./run-dash0-scenarios.sh --journeys --faults          # Specific suites
./run-dash0-scenarios.sh --stress --test batteryDrain # Single test
./run-dash0-scenarios.sh --all --run-id "sprint42"    # Tag telemetry with run ID
```

## Documentation

### Getting Started

- **[docs/QUICK_START.md](docs/QUICK_START.md)** — SDK integration in 5 minutes, or run the full demo end-to-end
- **[docs/ANDROID_SDK_GUIDE.md](docs/ANDROID_SDK_GUIDE.md)** — Complete Android integration guide (auto-instrumentation, network, privacy, flush control)

### SDK Reference

- **[docs/AUTO_INSTRUMENTATION.md](docs/AUTO_INSTRUMENTATION.md)** — All auto-captured signals, trace hierarchy, privacy controls
- **[docs/EXPORT_MODES.md](docs/EXPORT_MODES.md)** — CONDITIONAL, CONTINUOUS, HYBRID modes explained
- **[docs/DEVICE_METRICS.md](docs/DEVICE_METRICS.md)** — Health metric gauges (memory, battery, thermal, storage, predictions)
- **[docs/GEO_DEVICE_POLICY_EXTENSION.md](docs/GEO_DEVICE_POLICY_EXTENSION.md)** — Country/region/device-class export policy DSL
- **[docs/SAMPLING.md](docs/SAMPLING.md)** — Dynamic sampling configuration
- **[docs/LOG_TAILING.md](docs/LOG_TAILING.md)** — Log tailing and streaming
- **[docs/BUNDLED_CONFIG.md](docs/BUNDLED_CONFIG.md)** — Offline/bundled policy configuration

### Control Plane

- **[docs/USER_GUIDE.md](docs/USER_GUIDE.md)** — Authoring export policies in the React UI
- **[docs/API_REFERENCE.md](docs/API_REFERENCE.md)** — Gateway REST API reference
- **[docs/guides/AUTHENTICATION.md](docs/guides/AUTHENTICATION.md)** — Authentication setup

### Operations & Development

- **[docs/DEVELOPER_GUIDE.md](docs/DEVELOPER_GUIDE.md)** — Extending the system (gateway, UI nodes, SDK)
- **[docs/OPERATIONS_GUIDE.md](docs/OPERATIONS_GUIDE.md)** — Production deployment
- **[docs/TROUBLESHOOTING_GUIDE.md](docs/TROUBLESHOOTING_GUIDE.md)** — Common issues
- **[docs/guides/DEPLOYMENT_GUIDE.md](docs/guides/DEPLOYMENT_GUIDE.md)** — Deployment guide
- **[docs/guides/TESTING_STRATEGY.md](docs/guides/TESTING_STRATEGY.md)** — Testing approach
- **[docs/reference/TESTING_IMPLEMENTATION.md](docs/reference/TESTING_IMPLEMENTATION.md)** — Test inventory, run scripts, CI/CD

### Architecture & Design

- **[DESIGN.md](DESIGN.md)** — Vision, system architecture, core concepts, DSL, privacy, OTel compliance
- **[BACKLOG.md](BACKLOG.md)** — Prioritized remaining work across 5 tracks
- **[docs/reference/ARCHITECTURE.md](docs/reference/ARCHITECTURE.md)** — Architecture deep dive
- **[docs/OTEPs/](docs/OTEPs/)** — OpenTelemetry Enhancement Proposals

## License

Apache 2.0
