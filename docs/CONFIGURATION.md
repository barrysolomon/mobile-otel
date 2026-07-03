# Configuration Guide

This document describes how to configure the OpenTelemetry Android Mobile SDK.

## Configuration Methods

The SDK supports two configuration paths:

1. **Programmatic** — via `MobileConfig` builder in Kotlin code
2. **File-based** — via `otel-config.json` bundled in the app's assets

Both methods configure the same underlying `MobileConfig` data class. The demo app uses file-based configuration; production apps typically use programmatic configuration.

## Programmatic Configuration

```kotlin
val config = MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "https://collector.example.com:4317",
    exportMode = ExportMode.HYBRID,
    headers = mapOf(
        "Authorization" to "Bearer $token",
        "Dash0-Dataset" to "mobile"
    )
)

OTelMobile.start(application, config)
```

### MobileConfig Fields

#### Required

| Field | Type | Description |
|-------|------|-------------|
| `serviceName` | String | OTel `service.name` resource attribute |
| `serviceVersion` | String | OTel `service.version` resource attribute |
| `collectorEndpoint` | String | OTLP endpoint. With the default `HTTP_PROTOBUF` protocol the SDK POSTs to `<endpoint>/v1/{logs,traces,metrics}` (e.g., `https://ingress.us-west-2.aws.dash0.com`); with `GRPC` it is a single gRPC endpoint (e.g., `https://host:4317`) |

#### Export Behavior

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `protocol` | OtlpProtocol | `HTTP_PROTOBUF` | `HTTP_PROTOBUF` (default, matches iOS, traverses HTTPS proxies/PaaS ingress) or `GRPC` (single endpoint, typically `:4317`) |
| `exportMode` | ExportMode | `HYBRID` | `CONTINUOUS`, `CONDITIONAL`, or `HYBRID` |
| `traceExportIntervalSeconds` | Long | `30` | Periodic trace export interval (CONTINUOUS mode) |
| `metricExportIntervalSeconds` | Long | `60` | Periodic metric export interval |
| `exportTimeoutSeconds` | Long | `30` | Per-export timeout |
| `maxExportRetries` | Int | `3` | Retry count on export failure |
| `crashLoopThreshold` | Int | `3` | Crash-loop self-disable (SDK_SAFETY): after this many consecutive launches that follow a crash, the SDK refuses to initialize for that launch (no instrumentation, no export). Self-clears on the first clean launch. `0` turns the guard off. iOS parity: `crashLoopThreshold` |
| `remoteConfigEnabled` | Boolean | `true` | Poll the control-plane `/config` endpoint for policy DSL + remote kill switch / global sampling. Set `false` when `collectorEndpoint` is a plain OTLP ingest endpoint that does not serve config |
| `gatewayEndpoint` | String? | `null` | Base URL of the mobile-otel gateway serving `/config?dsl_version=2`. When set, config polling targets it instead of `collectorEndpoint` — use when the collector endpoint is plain OTLP ingest (e.g. Dash0 ingress) with no `/config` route. iOS parity: `gatewayEndpoint` |
| `headers` | Map? | `null` | Extra HTTP headers (auth tokens, dataset) |

#### Buffering

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `ramBufferSize` | Int | `5000` | Max events held in RAM ring buffer (count cap) |
| `ramBufferMaxTotalBytes` | Long | `10485760` (10 MB) | Total-byte budget for the RAM ring buffer; oldest events overflow to disk when exceeded (caps RAM independently of `ramBufferSize`) |
| `ramBufferMaxEventBytes` | Int | `262144` (256 KB) | Per-event byte cap; a single oversize event is dropped and counted (`buffer.ram.dropped_oversize`) rather than buffered |
| `diskBufferMb` | Int | `50` | Max SQLite disk buffer size in MB |
| `diskBufferTtlHours` | Int | `24` | Time-to-live for disk-buffered events |
| `encryptDiskBufferAtRest` | Boolean | `true` | Encrypt the on-disk buffer at rest (SQLCipher + Android Keystore) — parity with iOS `NSFileProtection`. Crash-safe: degrades to cleartext rather than failing if SQLCipher/Keystore are unavailable. Set `false` to keep the buffer cleartext |

When the RAM buffer is full, oldest events overflow to the SQLite disk buffer. On `flushWindow(minutes)`, both RAM and disk events within the time window are exported.

#### UI Telemetry

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `uiTelemetryMode` | UiTelemetryMode | `EVENTS` | `EVENTS` (log records), `SPANS` (child spans), or `BOTH` |

#### Sampling

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `samplingConfig` | SamplingConfig | Dynamic 10% | Sampling strategy and rate |

Presets:
- `SamplingConfig.alwaysOn()` — 100% sampling (development)
- `SamplingConfig.production(rate = 0.1)` — 10% trace-ID ratio
- `SamplingConfig.dynamic(normalRate = 0.05, highPriorityRate = 1.0)` — adaptive

## Export Modes

| Mode | Periodic Export | Policy-Triggered Flush | Device Metrics | Use Case |
|------|----------------|------------------------|----------------|----------|
| **CONDITIONAL** | No | Yes | No | Battery-efficient; events only export when a policy matches |
| **CONTINUOUS** | Yes | Yes | Yes | Full visibility; periodic bulk export + policy triggers |
| **HYBRID** | No | Yes | Yes (2x interval) | Balanced; device health metrics flow continuously, events are policy-driven |

### HYBRID (default)

The shipped default (v0.2.0-alpha). Device-health metrics and periodic heartbeats export on a schedule, while bulk event data stays buffered and exports only on a policy trigger. See [EXPORT_MODES.md](./EXPORT_MODES.md) for the full breakdown.

### CONDITIONAL

Events accumulate in the buffer. When a policy trigger fires (e.g., crash, UI freeze), `flushWindow(N)` exports only the last N minutes of events. Nothing is exported unless a policy matches.

### CONTINUOUS

A periodic scheduler calls `forceFlush()` every `traceExportIntervalSeconds`. Policy evaluation also runs; matched policies trigger immediate `flushWindow()` calls. Device metrics export on their own interval.

### HYBRID

Device metrics (battery, memory, CPU, thermal) export periodically at 2x the metric interval. Event data uses the CONDITIONAL model — buffered until a policy trigger fires.

## Sub-Configuration Classes

Each instrumentation subsystem has its own config with sensible defaults and named presets.

### SessionConfig

```kotlin
SessionConfig(
    enabled = true,                     // Enable session tracking
    inactivityTimeoutMs = 900_000L,     // 15 min inactivity → new session
    flushOnTermination = true,          // Flush buffer when app terminates
    persistSession = true               // Survive app restarts
)
```

### VitalsConfig

```kotlin
VitalsConfig(
    measureAppStart = true,             // Cold/warm start timing
    measureTtid = true,                 // Time to initial display
    detectJank = true,                  // Frame drop detection
    trackInputLatency = true,           // Touch → response timing
    monitorAnrRisk = true,              // Main thread block detection
    monitorMemoryPressure = true,       // Low memory alerts
    monitorThermalState = false,        // Thermal throttling (API 29+)
    jankThresholdMs = 16.0,             // 60fps frame budget
    anrRiskThresholdMs = 3000L,         // 3s main thread block
    coldStartThresholdMs = 5000L,       // Slow cold start threshold
    warmStartThresholdMs = 2000L,       // Slow warm start threshold
    ttidThresholdMs = 3000L             // Slow TTID threshold
)
```

Presets: `VitalsConfig.default()`, `minimal()`, `aggressive()`, `batteryFriendly()`

### NetworkConfig

```kotlin
NetworkConfig(
    propagateTraceContext = true,        // W3C Trace Context injection
    captureRequestHeaders = false,       // Privacy: off by default
    captureResponseHeaders = false,
    captureRequestBody = false,
    captureResponseBody = false,
    scrubUrls = true,                   // Redact query params
    scrubHeaders = true,                // Redact auth headers
    errorStatusThreshold = 400          // HTTP status >= 400 → error
)
```

Presets: `NetworkConfig.default()`, `minimal()`, `debug()`, `production()`

### ErrorConfig

```kotlin
ErrorConfig(
    captureUncaughtExceptions = true,    // Thread.UncaughtExceptionHandler
    captureCoroutineExceptions = true,   // CoroutineExceptionHandler
    captureExceptionMessages = true,     // Include exception.message
    captureStackTraces = true,           // Include exception.stacktrace
    captureCauses = true,               // Include chained causes
    scrubStackTraces = false,           // PII redaction in stack traces
    attachBreadcrumbs = true,           // Attach user journey breadcrumbs
    attachVitals = true,                // Attach device vitals snapshot
    deduplicateWindowMs = 300_000L,     // 5 min dedup window
    rateLimit = 10                       // Max 10 errors/min
)
```

Presets: `ErrorConfig.default()`, `minimal()`, `debug()`, `production()`

### DeviceMetricsConfig

```kotlin
DeviceMetricsConfig(
    captureMemory = true,
    captureBattery = true,
    captureCpu = true,
    captureNetwork = true,
    captureStorage = true,
    captureThermal = true,
    captureDisplay = true,
    captureSystem = true,
    captureApp = true,
    captureLocation = true              // Coarse only: country + timezone
)
```

Presets: `DeviceMetricsConfig.default()`, `minimal()`, `performance()`, `network()`, `privacyFocused()`, `disabled()`

### BreadcrumbConfig

```kotlin
BreadcrumbConfig(
    maxBreadcrumbs = 50,                // Ring buffer size
    captureScreenViews = true,
    captureTaps = true,
    captureNetwork = true,
    captureErrors = true
)
```

## File-Based Configuration (otel-config.json)

For apps that prefer runtime configuration, bundle a JSON file in `assets/otel-config.json`:

```json
{
  "serviceName": "my-app",
  "serviceVersion": "1.0.0",
  "collectorEndpoint": "https://collector.example.com:4317",
  "exportMode": "HYBRID",
  "headers": {
    "Authorization": "Bearer YOUR_AUTH_TOKEN",
    "Dash0-Dataset": "mobile"
  },
  "traceExportIntervalSeconds": 30,
  "metricExportIntervalSeconds": 60,
  "ramBufferSize": 5000,
  "diskBufferMb": 50,
  "diskBufferTtlHours": 24,
  "exportTimeoutSeconds": 30,
  "samplingRate": 1.0,
  "telemetrySettings": {
    "dataCollection": {
      "logs": true,
      "traces": true,
      "metrics": true,
      "deviceMetrics": true
    },
    "deviceMetricCategories": {
      "memory": true,
      "battery": true,
      "cpu": true,
      "network": true,
      "storage": true,
      "thermal": true,
      "display": true,
      "system": true,
      "app": true,
      "location": true
    }
  },
  "workflows": []
}
```

The demo app loads this file via `ConfigManager.loadConfig(context)` with a 3-tier priority:

1. **Runtime overrides** (SharedPreferences) — highest
2. **Bundled config** (assets/otel-config.json) — fallback
3. **Hardcoded defaults** — last resort

## Export Policy DSL

Policies define when and what to flush from the buffer. They are evaluated in real-time as events arrive.

### Policy Structure

```json
{
  "id": "crash-recovery",
  "enabled": true,
  "match": {
    "logical_operator": "and",
    "attributes": {
      "event.name": { "equals": "app.crash" }
    }
  },
  "actions": {
    "flush_window_minutes": 5
  }
}
```

### Match Conditions

**Attribute matchers:**

| Operator | Example | Description |
|----------|---------|-------------|
| `equals` | `{"equals": "app.crash"}` | Exact string match |
| `contains` | `{"contains": "error"}` | Substring match |
| `regex` | `{"regex": "^ui\\..*"}` | Regex match |
| `gte` | `{"gte": 2000}` | Numeric >= |
| `lte` | `{"lte": 100}` | Numeric <= |
| `gt` | `{"gt": 0}` | Numeric > |
| `lt` | `{"lt": 50}` | Numeric < |
| `in` | `{"in": ["a","b"]}` | Set membership |

**Geo/device context matchers:**

```json
{
  "match": {
    "geo": {
      "country": ["US", "DE"],
      "timezone": ["America/*"]
    },
    "device": {
      "network": ["cellular"],
      "battery": ["low", "critical"]
    }
  }
}
```

**Logical operators:**

- `"and"` — all conditions must match (default)
- `"or"` — any condition must match

### Actions

| Action | Description |
|--------|-------------|
| `flush_window_minutes` | Export events from the last N minutes |

### Built-in Fallback Policies

These are compiled into the SDK and active when no remote policies are configured:

| Policy | Trigger | Action |
|--------|---------|--------|
| `ui-freeze-detector` | `event.name == "ui.freeze"` | Flush last 2 minutes |
| `crash-recovery` | `event.name == "app.crash"` | Flush last 5 minutes |
| `http-error-detector` | `event.name == "http.error"` | Flush last 5 minutes |

### Remote Policy Polling, Kill Switch & Global Sampling

When remote config is enabled (`remoteConfigEnabled = true`, the default; iOS `enablePolicyPolling`, default `true`), the SDK polls `GET /config?dsl_version=2` at `configPollIntervalSeconds` (default: 300s) to fetch updated policies. Remote policies override the built-in fallback set.

The same payload carries two control-plane overrides honored on all platforms (and transitively on React Native):

- **`sdk.enabled`** — a remote **kill switch**. When the control plane returns `sdk.enabled = false`, the SDK stops emitting/exporting telemetry without an app update.
- **`sdk.sample_rate`** — a **global sampling override** applied on top of the local `samplingConfig`.

To defend the kill switch against MITM / OTA tampering, the payload can be HMAC-signed; see [docs/design/remote-kill-switch.md](./design/remote-kill-switch.md). Transport security is configured on `MobileConfig` on **both platforms** (parity as of 0.2.1-alpha): HTTPS enforcement (cleartext rejected unless `allowInsecureTransport`), optional cert/public-key pinning, and an HMAC `configSigningKey` that verifies the remote-config signature before applying it. The pinning field is named `pinning` on iOS and `pinningConfig` on Android; `allowInsecureTransport` and `configSigningKey` are identical on both.

## Credential Configuration

For the demo app, copy the template and fill in real values:

```bash
cp examples/demo-app/android/src/debug/assets/otel-config.json.template \
   examples/demo-app/android/src/debug/assets/otel-config.json
```

The template uses placeholder values (`YOUR_COLLECTOR_ENDPOINT`, `YOUR_AUTH_TOKEN`). `ConfigManager.isDash0Configured()` detects these placeholders and returns false, causing tests to skip gracefully via `Assume.assumeTrue()`.

**Never commit `otel-config.json`** — it contains real credentials. The `.gitignore` excludes it.
