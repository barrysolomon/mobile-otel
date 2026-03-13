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
| `collectorEndpoint` | String | OTLP/gRPC endpoint (e.g., `https://host:4317`) |

#### Export Behavior

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `exportMode` | ExportMode | `CONDITIONAL` | `CONTINUOUS`, `CONDITIONAL`, or `HYBRID` |
| `traceExportIntervalSeconds` | Long | `30` | Periodic trace export interval (CONTINUOUS mode) |
| `metricExportIntervalSeconds` | Long | `60` | Periodic metric export interval |
| `exportTimeoutSeconds` | Long | `30` | Per-export timeout |
| `maxExportRetries` | Int | `3` | Retry count on export failure |
| `headers` | Map? | `null` | Extra HTTP headers (auth tokens, dataset) |

#### Buffering

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `ramBufferSize` | Int | `5000` | Max events held in RAM ring buffer |
| `diskBufferMb` | Int | `50` | Max SQLite disk buffer size in MB |
| `diskBufferTtlHours` | Int | `24` | Time-to-live for disk-buffered events |

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

### CONDITIONAL (default)

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

### Remote Policy Polling

When a gateway endpoint is configured, the SDK polls `GET /config` at `configPollIntervalSeconds` (default: 300s) to fetch updated policies. Remote policies override the built-in fallback set.

## Credential Configuration

For the demo app, copy the template and fill in real values:

```bash
cp examples/demo-app/android/src/debug/assets/otel-config.json.template \
   examples/demo-app/android/src/debug/assets/otel-config.json
```

The template uses placeholder values (`YOUR_COLLECTOR_ENDPOINT`, `YOUR_AUTH_TOKEN`). `ConfigManager.isDash0Configured()` detects these placeholders and returns false, causing tests to skip gracefully via `Assume.assumeTrue()`.

**Never commit `otel-config.json`** — it contains real credentials. The `.gitignore` excludes it.
