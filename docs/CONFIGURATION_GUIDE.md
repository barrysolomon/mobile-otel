# Dash0 Mobile Observability SDK — Configuration Guide

This guide covers every configuration option in the Dash0 Mobile Observability SDK for Android. All field names, types, defaults, and valid ranges are verified against the source code.

---

## Table of Contents

1. [Quick Start](#1-quick-start)
2. [Configuration Methods](#2-configuration-methods)
3. [otel-config.json Reference](#3-otel-configjson-reference)
4. [Kotlin DSL Reference](#4-kotlin-dsl-reference)
5. [Export Modes](#5-export-modes)
6. [Buffer Tuning](#6-buffer-tuning)
7. [Instrumentation Module Configuration](#7-instrumentation-module-configuration)
8. [Runtime Config Override](#8-runtime-config-override)
9. [Sampling Configuration](#9-sampling-configuration)

---

## 1. Quick Start

### Path A — Kotlin DSL (recommended)

Use the DSL when you want type-safe, IDE-assisted configuration compiled into your app:

```kotlin
import io.opentelemetry.android.mobile.config.mobileOtel
import io.opentelemetry.android.mobile.config.ExportMode

OTelMobile.start(application) {
    service {
        name = "my-app"
        version = "2.1.0"
    }
    export {
        endpoint = "https://ingress.us1.dash0.com:4317"
        mode = ExportMode.CONDITIONAL
        headers = mapOf(
            "Authorization" to "Bearer $authToken",
            "Dash0-Dataset" to "mobile"
        )
    }
    buffering {
        ramSize = 5000
        diskMb = 50
        ttlHours = 24
    }
    instrumentations {
        discoverAll()
    }
}
```

### Path B — JSON config file

Bundle `otel-config.json` in `src/main/assets/` (or a build-flavor assets folder). The SDK reads it automatically at startup via `ConfigManager.loadConfig(context)`:

```json
{
  "serviceName": "my-app",
  "serviceVersion": "2.1.0",
  "collectorEndpoint": "https://ingress.us1.dash0.com:4317",
  "exportMode": "CONDITIONAL",
  "headers": {
    "Authorization": "Bearer YOUR_AUTH_TOKEN",
    "Dash0-Dataset": "mobile"
  }
}
```

The template at `examples/demo-app/android/src/debug/assets/otel-config.json.template` shows every supported field. Copy it and fill in your credentials — do not commit the filled-in file (it is excluded by `.gitignore`).

---

## 2. Configuration Methods

There are two independent initialization strategies:

### Strategy A — Programmatic (DSL or Builder)

Use `MobileOtel.initialize(context) { DSL }` or `MobileConfig.Builder()` to configure the SDK entirely in code. The config is built at compile time and passed directly to the SDK. `ConfigManager` is not involved.

### Strategy B — File-based (ConfigManager)

The demo app uses `ConfigManager.loadConfig(context)`, which resolves configuration from three sources in priority order:

| Priority | Source | When to use |
|----------|--------|-------------|
| 1 (highest) | **SharedPreferences** (`otel_config` store) | Runtime overrides via `adb shell` or the `otel-device` CLI — no rebuild needed |
| 2 | **Bundled JSON** (`assets/otel-config.json`) | Ship credentials per build flavor without recompiling |
| 3 (lowest) | **Hardcoded defaults** | Fallback when no other source provides a value |

SharedPreferences are checked first; if no runtime override has been saved, the bundled JSON is loaded (and promoted to SharedPreferences for future reads); hardcoded defaults apply if neither exists.

Most production apps use Strategy A. The demo app uses Strategy B so that configuration can be changed at runtime (useful for testing and demos).

---

## 3. otel-config.json Reference

### Top-level fields

| Field | Type | Default | Valid range / notes |
|-------|------|---------|---------------------|
| `serviceName` | String | — | **Required.** Must not be blank. Maps to OTel `service.name`. |
| `serviceVersion` | String | — | **Required.** Must not be blank. Maps to OTel `service.version`. |
| `collectorEndpoint` | String | — | **Required.** OTLP/gRPC endpoint (e.g., `https://host:4317`). Must not be blank. HTTP (non-localhost) emits a warning. |
| `exportMode` | String | `"CONDITIONAL"` | `"CONDITIONAL"`, `"CONTINUOUS"`, `"HYBRID"` |
| `headers` | Object | `null` | Key-value map of HTTP headers added to every export request. Use for auth tokens and dataset routing. |
| `traceExportIntervalSeconds` | Long | `30` | Must be > 0. Interval between periodic trace exports (CONTINUOUS/HYBRID modes). |
| `metricExportIntervalSeconds` | Long | `60` | Must be > 0. Interval between periodic metric exports. |
| `ramBufferSize` | Int | `5000` | 1–100,000. Maximum events held in the in-memory ring buffer. |
| `diskBufferMb` | Int | `50` | 1–500. Maximum size of the SQLite disk buffer in megabytes. |
| `diskBufferTtlHours` | Int | `24` | 1–168 (7 days). Events older than this are dropped from the disk buffer. |
| `exportTimeoutSeconds` | Long | `30` | Must be > 0. Per-export request timeout. |
| `configPollIntervalSeconds` | Long | `300` | Must be > 0. How often the SDK polls the gateway for updated export policies. |
| `maxExportRetries` | Int | `3` | 0–10. Retry count on export failure. |
| `attachContextAttributes` | Boolean | `false` | Attach additional context attributes (e.g., thread, activity) to every log record. |
| `buildChannel` | String | `null` | Arbitrary label (e.g., `"debug"`, `"nightly"`) added as a resource attribute. |
| `samplingRate` | Float | `1.0` | 0.0–1.0. Shorthand for `SamplingConfig.dynamic(normalRate = value, highPriorityRate = 1.0)`. |

### Sub-object: `sessionConfig`

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `enabled` | Boolean | `true` | Enable session tracking and session ID generation. |
| `inactivityTimeoutMs` | Long | `900000` (15 min) | Background inactivity time after which a new session starts. |
| `flushOnTermination` | Boolean | `true` | Flush buffered telemetry when the session ends. |
| `persistSession` | Boolean | `true` | Persist session ID across app restarts. |

### Sub-object: `vitalsConfig`

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `enabled` | Boolean | `true` | Master switch for all vitals monitoring. |
| `measureAppStart` | Boolean | `true` | Measure cold and warm start times. |
| `measureTtid` | Boolean | `true` | Measure time to initial display. |
| `detectJank` | Boolean | `true` | Detect dropped frames. |
| `trackInputLatency` | Boolean | `true` | Track touch-to-response latency. |
| `monitorAnrRisk` | Boolean | `true` | Detect main-thread blocks that risk ANRs. |
| `monitorMemoryPressure` | Boolean | `true` | Emit events when available memory is critically low. |
| `monitorThermalState` | Boolean | `false` | Monitor device thermal throttling (API 29+). |
| `jankThresholdMs` | Double | `16.0` | Must be > 0. Frame time threshold for jank (60fps = 16 ms). |
| `severeJankThresholdMs` | Double | `100.0` | Must be > `jankThresholdMs`. |
| `inputLatencyThresholdMs` | Double | `50.0` | Must be > 0. |
| `anrRiskThresholdMs` | Long | `3000` | Must be > 0 ms. |
| `coldStartThresholdMs` | Long | `5000` | Must be > 0 ms. |
| `warmStartThresholdMs` | Long | `2000` | Must be > 0 ms. |
| `ttidThresholdMs` | Long | `3000` | Must be > 0 ms. |
| `memoryPressureCriticalMb` | Int | `50` | Must be > 0 MB. |
| `samplingRate` | Double | `1.0` | 0.0–1.0. |
| `reportingIntervalMs` | Long | `60000` | Must be > 0 ms. |

### Sub-object: `networkConfig`

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `enabled` | Boolean | `true` | Master switch for network instrumentation. |
| `propagateTraceContext` | Boolean | `true` | Inject W3C trace context headers into outgoing requests. |
| `captureRequestHeaders` | Array | `[]` | Allowlist of request header names to capture. |
| `captureResponseHeaders` | Array | `["Content-Type"]` | Allowlist of response header names to capture. |
| `captureRequestBody` | Boolean | `false` | Capture request bodies (privacy risk — off by default). |
| `captureResponseBody` | Boolean | `false` | Capture response bodies (privacy risk — off by default). |
| `maxBodyCaptureBytes` | Int | `1024` | Must be > 0. Truncation limit for body capture. |
| `scrubUrls` | Boolean | `true` | Remove query parameters and sensitive path segments from URLs. |
| `scrubHeaders` | Boolean | `true` | Redact sensitive headers (Authorization, Cookie, X-API-Key, etc.). |
| `detectNetworkType` | Boolean | `true` | Report WiFi/Cellular/etc. |
| `reportNetworkSpeed` | Boolean | `false` | Report estimated connection speed. |
| `bucketSizes` | Boolean | `true` | Group request/response sizes into buckets (`<1KB`, `1-10KB`, etc.). |
| `minDurationMs` | Long | `0` | Must be >= 0. Only trace requests slower than this threshold (0 = trace all). |
| `errorStatusThreshold` | Int | `400` | 100–599. HTTP status codes >= this value are marked as errors. |
| `allowedHosts` | Array | `[]` | If non-empty, only instrument requests to these hosts. |
| `blockedHosts` | Array | `[]` | Never instrument requests to these hosts. |

### Sub-object: `errorConfig`

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `enabled` | Boolean | `true` | Master switch for error instrumentation. |
| `captureUncaughtExceptions` | Boolean | `true` | Install `Thread.UncaughtExceptionHandler`. |
| `captureCoroutineExceptions` | Boolean | `true` | Hook `CoroutineExceptionHandler`. |
| `captureRxJavaExceptions` | Boolean | `false` | Hook RxJava error handlers (opt-in). |
| `deduplicateWindowMs` | Long | `300000` (5 min) | Must be > 0. Same-fingerprint errors within this window are deduplicated. |
| `rateLimit` | Int | `10` | Must be > 0. Maximum errors reported per minute. |
| `maxStackTraceDepth` | Int | `50` | Must be > 0. Stack frames captured per error. |
| `scrubStackTraces` | Boolean | `true` | Remove PII from stack traces. |
| `attachBreadcrumbs` | Boolean | `true` | Attach user journey breadcrumbs to error events. |
| `attachVitals` | Boolean | `true` | Attach current device vitals snapshot to error events. |
| `proguardMappingFile` | String | `null` | Path to ProGuard mapping for deobfuscation. |
| `filterExceptions` | Array | `[]` | Class names to silently ignore (prefix match). |
| `captureExceptionMessages` | Boolean | `true` | Include `exception.message` (may contain PII). |
| `captureCauses` | Boolean | `true` | Include chained exception causes. |
| `flushOnError` | Boolean | `true` | Trigger a buffer flush when an error is captured. |

### Sub-object: `breadcrumbConfig`

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `enabled` | Boolean | `true` | Enable breadcrumb collection. |
| `maxSize` | Int | `50` | Must be > 0. Circular buffer capacity. |
| `captureNavigation` | Boolean | `true` | Capture Activity/Fragment/Compose navigation events. |
| `captureUserInput` | Boolean | `true` | Capture tap and gesture events. |
| `captureNetwork` | Boolean | `true` | Capture network request events. |
| `captureErrors` | Boolean | `true` | Capture error and exception events. |
| `scrubElementIds` | Boolean | `true` | Remove element IDs that may contain PII. |
| `scrubNetworkUrls` | Boolean | `true` | Remove query parameters from captured URLs. |
| `allowedScreens` | Array | `[]` | If non-empty, only capture breadcrumbs on these screens. |

### Sub-object: `telemetrySettings`

This object controls device metrics collection. It maps to `DeviceMetricsConfig` at the source level.

```json
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
    "thermal": false,
    "display": true,
    "system": true,
    "app": true,
    "location": false
  }
}
```

`thermal` and `location` are `false` by default. `location` captures only coarse data (country + timezone) — no GPS coordinates.

---

### Example configs

#### Dash0 cloud (production)

```json
{
  "serviceName": "my-app",
  "serviceVersion": "1.0.0",
  "collectorEndpoint": "https://ingress.us1.dash0.com:4317",
  "exportMode": "CONDITIONAL",
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
  "configPollIntervalSeconds": 300,
  "maxExportRetries": 3,
  "attachContextAttributes": false,
  "buildChannel": "production"
}
```

#### Local collector (development)

```json
{
  "serviceName": "my-app-dev",
  "serviceVersion": "0.0.1-dev",
  "collectorEndpoint": "http://10.0.2.2:4317",
  "exportMode": "CONTINUOUS",
  "traceExportIntervalSeconds": 10,
  "metricExportIntervalSeconds": 30,
  "ramBufferSize": 1000,
  "diskBufferMb": 20,
  "diskBufferTtlHours": 4,
  "exportTimeoutSeconds": 15,
  "maxExportRetries": 1,
  "attachContextAttributes": true,
  "buildChannel": "debug"
}
```

Note: `10.0.2.2` is the Android emulator alias for the host machine's localhost. The SDK does not warn about missing HTTPS when the host is `localhost`, `127.0.0.1`, or `10.0.2.2`.

#### Battery-efficient production

```json
{
  "serviceName": "my-app",
  "serviceVersion": "1.0.0",
  "collectorEndpoint": "https://ingress.us1.dash0.com:4317",
  "exportMode": "CONDITIONAL",
  "headers": {
    "Authorization": "Bearer YOUR_AUTH_TOKEN",
    "Dash0-Dataset": "mobile"
  },
  "ramBufferSize": 5000,
  "diskBufferMb": 100,
  "diskBufferTtlHours": 48,
  "configPollIntervalSeconds": 600,
  "vitalsConfig": {
    "enabled": true,
    "detectJank": true,
    "monitorThermalState": false,
    "samplingRate": 0.1,
    "reportingIntervalMs": 300000
  },
  "networkConfig": {
    "enabled": true,
    "scrubUrls": true,
    "scrubHeaders": true,
    "minDurationMs": 500,
    "reportNetworkSpeed": false
  }
}
```

---

## 4. Kotlin DSL Reference

### Full example

```kotlin
OTelMobile.start(application) {
    service {
        name = "my-app"
        version = "2.1.0"
    }

    export {
        endpoint = "https://ingress.us1.dash0.com:4317"
        mode = ExportMode.CONDITIONAL          // CONDITIONAL | CONTINUOUS | HYBRID
        headers = mapOf(
            "Authorization" to "Bearer $authToken",
            "Dash0-Dataset" to "mobile"
        )
        traceIntervalSeconds = 30              // Long, default 30
        metricIntervalSeconds = 60             // Long, default 60
        timeoutSeconds = 30                    // Long, default 30
        maxRetries = 3                         // Int, default 3
    }

    buffering {
        ramSize = 5000                         // Int, 1–100,000; default 5000
        diskMb = 50                            // Int, 1–500; default 50
        ttlHours = 24                          // Int, 1–168; default 24
    }

    session {
        renewalMinutes = 30L                   // Long, default 30
    }

    uiTelemetryMode = UiTelemetryMode.EVENTS   // EVENTS | SPANS | BOTH

    instrumentations {
        discoverAll()                          // Auto-discover all instrumentation modules
        // OR: discoverOwn()                  // Mobile-only, skip upstream
        // OR: add(TapInstrumentation(...))   // Add specific modules explicitly
    }

    exportCustomizers {
        addLogExporterCustomizer { exporter ->
            // Wrap or replace the log exporter
            exporter
        }
    }
}
```

### DSL block reference

| Block | Property | Type | Default | Description |
|-------|----------|------|---------|-------------|
| `service { }` | `name` | String? | — | Required. OTel `service.name`. |
| `service { }` | `version` | String? | — | Required. OTel `service.version`. |
| `export { }` | `endpoint` | String? | — | Required. OTLP/gRPC endpoint URL. |
| `export { }` | `mode` | ExportMode | `CONDITIONAL` | Export trigger strategy. |
| `export { }` | `headers` | Map<String,String>? | `null` | HTTP headers for auth/routing. |
| `export { }` | `traceIntervalSeconds` | Long | `30` | Periodic trace export interval. |
| `export { }` | `metricIntervalSeconds` | Long | `60` | Periodic metric export interval. |
| `export { }` | `timeoutSeconds` | Long | `30` | Per-export timeout. |
| `export { }` | `maxRetries` | Int | `3` | Retry count on failure. |
| `buffering { }` | `ramSize` | Int | `5000` | RAM ring buffer capacity (events). |
| `buffering { }` | `diskMb` | Int | `50` | SQLite disk buffer max size (MB). |
| `buffering { }` | `ttlHours` | Int | `24` | Disk event time-to-live (hours). |
| `session { }` | `renewalMinutes` | Long | `30` | Inactivity timeout before new session. |
| top-level | `uiTelemetryMode` | UiTelemetryMode | `EVENTS` | How UI interactions are emitted. |
| `instrumentations { }` | `discoverAll()` | — | — | Load all `MobileInstrumentation` + `AndroidInstrumentation` via ServiceLoader. |
| `instrumentations { }` | `discoverOwn()` | — | — | Load only `MobileInstrumentation` (skip upstream). |
| `instrumentations { }` | `add(inst)` | — | — | Add a specific instrumentation instance. |
| `exportCustomizers { }` | `addLogExporterCustomizer` | Function | — | Wrap the `LogRecordExporter`. |
| `exportCustomizers { }` | `addSpanExporterCustomizer` | Function | — | Wrap the `SpanExporter`. |
| `exportCustomizers { }` | `addMetricExporterCustomizer` | Function | — | Wrap the `MetricExporter`. |

Note: `ScreenshotConfig`, `WireframeConfig`, `NetworkConfig`, `ErrorConfig`, `VitalsConfig`, and `BreadcrumbConfig` are not exposed as DSL sub-blocks. Set them directly on `MobileOtelDsl` instance properties or pass them to `MobileConfig` via the data class constructor or Builder.

---

## 5. Export Modes

### CONDITIONAL (default)

Events accumulate silently in the dual-tier buffer (RAM + SQLite). Nothing is exported until an export policy trigger fires. When a policy matches (e.g., a crash event arrives), the SDK calls `flushWindow(N)` to export the last N minutes of buffered events.

**When to use:** Most production apps. Users experience no background network traffic except when a significant event occurs.

**Battery impact:** Lowest. Radio only wakes on policy triggers.

### CONTINUOUS

A periodic scheduler exports buffered events at `traceExportIntervalSeconds` regardless of policy triggers. Policies also run; matching policies trigger immediate `flushWindow()` on top of the scheduled exports.

**When to use:** Development, debugging, CI pipelines, or SLAs that require guaranteed telemetry delivery even during uneventful sessions.

**Battery impact:** Higher. Regular network activity.

### HYBRID

Device health metrics (battery, memory, CPU, thermal) export periodically at 2× the `metricExportIntervalSeconds`. Event data (logs, traces) uses the CONDITIONAL model — buffered until a policy trigger fires.

**When to use:** Production apps that need continuous device health visibility but want battery-efficient event export.

**Battery impact:** Medium. Metrics flow continuously; event export is policy-driven.

### Comparison

| | CONDITIONAL | CONTINUOUS | HYBRID |
|---|---|---|---|
| Periodic trace export | No | Yes | No |
| Periodic metric export | No | Yes | Yes (2x interval) |
| Policy-triggered flush | Yes | Yes | Yes |
| Background network traffic | None | Regular | Metrics only |
| Battery impact | Lowest | Highest | Medium |
| Best for | Production | Development | Production + health |

---

## 6. Buffer Tuning

The SDK uses a two-tier buffer: events land in a RAM ring buffer first, then overflow to a SQLite disk buffer.

### RAM buffer

| Setting | Key | Default | Range | Notes |
|---------|-----|---------|-------|-------|
| `ramBufferSize` | `MobileConfig.ramBufferSize` | `5000` | 1–100,000 | Maximum events before overflow to disk. |

**Increase when:** Your app generates high event volume (dense tracing, screenshots enabled) and you want more events available for time-window exports.

**Decrease when:** Memory is constrained or you want older events to reach SQLite faster for crash-safety.

### Disk buffer

| Setting | Key | Default | Range | Notes |
|---------|-----|---------|-------|-------|
| `diskBufferMb` | `MobileConfig.diskBufferMb` | `50` | 1–500 | SQLite database max size in MB. |
| `diskBufferTtlHours` | `MobileConfig.diskBufferTtlHours` | `24` | 1–168 | Events older than this are evicted. |

**Increase `diskBufferMb` when:** Crash recovery windows need to cover many hours of activity, or event volume is high (e.g., wireframe enabled).

**Decrease `diskBufferMb` when:** Device storage is limited or the app targets low-end hardware.

**Increase `diskBufferTtlHours` when:** Users may not open the app daily but you want crash context from previous sessions. Maximum is 168 hours (7 days).

**Decrease `diskBufferTtlHours` when:** Privacy requirements mandate short data retention, or disk space is limited.

### How overflow works

1. Incoming events fill the RAM ring buffer (`ConcurrentLinkedQueue`).
2. When RAM is full, the oldest events are moved to `DiskLogBuffer` (Room/SQLite).
3. Each `BufferedEvent` carries a monotonic `seqId`. During flush, events present in both RAM and disk are deduplicated by `seqId` to prevent double-export.
4. `flushWindow(minutes)` exports all events (RAM + disk) within the requested time window, then clears them.

---

## 7. Instrumentation Module Configuration

### Screenshot (Incubating)

Screenshot capture is **disabled by default** (`ScreenshotConfig(enabled = false)` in `MobileConfig`). It is not part of the OTel specification.

```kotlin
val config = MobileConfig(
    ...
    screenshotConfig = ScreenshotConfig(
        enabled = true,
        maxWidthPx = 480,          // Int, 1–4096; default 480
        maxHeightPx = 960,         // Int, 1–4096; default 960
        quality = 50,              // Int, 0–100; default 50 (JPEG only)
        format = ScreenshotFormat.JPEG,   // JPEG | PNG; default JPEG
        maxPayloadKb = 200,        // Int, 1–4096; default 200
        redactTextViews = true,    // Mask all TextView bounds; default true
        captureOnError = true,     // Auto-capture on uncaught exception; default true
        captureOnScreenView = false,  // Auto-capture on screen transition; default false
        screenViewDelayMs = 500,   // Long, 0–5000 ms; default 500
        maxCapturesPerMinute = 5   // Int, 1–60; default 5
    )
)
```

Screenshots are emitted as `data:image/jpeg;base64,...` in the `mobile.screenshot.data_url` log attribute.

**Approximate payload sizes:**

| Resolution | JPEG q50 | PNG |
|------------|----------|-----|
| 480×960 | 20–67 KB | 67–267 KB |
| 320×640 | 10–35 KB | 30–120 KB |

### Wireframe (Incubating)

Wireframe capture is **disabled by default** (`WireframeConfig(enabled = false)` in `MobileConfig`). It is not part of the OTel specification.

```kotlin
val config = MobileConfig(
    ...
    wireframeConfig = WireframeConfig(
        enabled = true,
        captureOnScreenView = true,   // Capture on screen transitions; default true
        captureOnTap = false,         // Capture after each tap; default false
        captureOnError = true,        // Capture on uncaught exception; default true
        maxDepth = 30,                // Int, 1–100; default 30
        includeResourceIds = true,    // Include Android resource IDs; default true
        includeTextHints = false,     // Include field hint/placeholder text; default false
        includeContentDescription = false, // Include accessibility labels; default false
        includeClickableState = true, // Include clickable/enabled flags; default true
        maxCapturesPerMinute = 30     // Int, 1–120; default 30
    )
)
```

Wireframes are compact JSON view-hierarchy trees (~1–5 KB per frame), emitted as `ui.wireframe` log records.

### Network

The `OTelNetworkInterceptor` is user-wired to your `OkHttpClient`. Configure it via `NetworkConfig`:

```kotlin
val config = MobileConfig(
    ...
    networkConfig = NetworkConfig(
        enabled = true,
        propagateTraceContext = true,
        scrubUrls = true,
        scrubHeaders = true,
        errorStatusThreshold = 400,
        minDurationMs = 0
    )
)
```

**Privacy presets:**

| Preset | scrubUrls | scrubHeaders | captureRequestBody | captureResponseBody | minDurationMs | Use when |
|--------|-----------|--------------|-------------------|---------------------|---------------|----------|
| `default()` | true | true | false | false | 0 | Production — privacy first |
| `minimal()` | true | true | false | false | 0 | Smallest footprint |
| `debug()` | true | true | true | true | 0 | Local debugging — captures headers and bodies |
| `production()` | true | true | false | false | 100 | Production — skips fast requests |

The `debug()` preset captures request/response bodies up to 4096 bytes and expands the captured header allowlist to `Content-Type`, `User-Agent`, `Accept`.

Headers always scrubbed when `scrubHeaders = true`: `Authorization`, `Cookie`, `Set-Cookie`, `X-API-Key`, `X-Auth-Token`, `Proxy-Authorization`, `WWW-Authenticate`.

### Errors

```kotlin
val config = MobileConfig(
    ...
    errorConfig = ErrorConfig(
        enabled = true,
        captureUncaughtExceptions = true,
        captureCoroutineExceptions = true,
        captureRxJavaExceptions = false,  // Opt-in
        deduplicateWindowMs = 300_000L,   // 5 min; must be > 0
        rateLimit = 10,                   // Per minute; must be > 0
        maxStackTraceDepth = 50,          // Must be > 0
        scrubStackTraces = true,
        flushOnError = true
    )
)
```

**Presets:**

| Preset | captureCoroutineExceptions | captureRxJava | rateLimit | scrubStackTraces | Dedup window |
|--------|--------------------------|----------------|-----------|-----------------|--------------|
| `default()` | true | false | 10/min | true | 5 min |
| `minimal()` | false | false | 10/min | true | 5 min |
| `debug()` | true | true | 50/min | **false** | 1 min |
| `production()` | true | false | 10/min | true | 5 min |

The `production()` preset also filters `java.util.concurrent.CancellationException` and `kotlinx.coroutines.CancellationException` by default.

### Vitals

```kotlin
val config = MobileConfig(
    ...
    vitalsConfig = VitalsConfig(
        enabled = true,
        measureAppStart = true,
        measureTtid = true,
        detectJank = true,
        trackInputLatency = true,
        monitorAnrRisk = true,
        monitorMemoryPressure = true,
        monitorThermalState = false,    // API 29+ only
        jankThresholdMs = 16.0,         // 60fps frame budget
        anrRiskThresholdMs = 3000L,
        samplingRate = 1.0,
        reportingIntervalMs = 60000L
    )
)
```

**Presets:**

| Preset | measureTtid | detectJank | trackInputLatency | monitorThermalState | samplingRate | reportingInterval |
|--------|-------------|------------|-------------------|--------------------|--------------|--------------------|
| `default()` | true | true | true | false | 1.0 | 60s |
| `minimal()` | false | false | false | false | 1.0 | 60s |
| `aggressive()` | true | true | true | **true** | 1.0 | 60s |
| `batteryFriendly()` | true | true | false | false | **0.1** | **300s** |

---

## 8. Runtime Config Override

### How it works

`ConfigManager` persists all `MobileConfig` fields to SharedPreferences under the `otel_config` store name. Any field written here takes priority over the DSL or bundled JSON for the lifetime of that installation, until the preference is cleared.

This mechanism is primarily useful for:
- On-device settings screens in the demo app
- Toggling features at test time via `adb`
- The `otel-device` CLI tool for remote device configuration

### Manual `adb` example

Write a SharedPreferences XML file directly to the app's data directory via `run-as` (works on debug builds without root):

```bash
adb shell "run-as io.opentelemetry.android.demo sh -c '
  mkdir -p shared_prefs
  cat > shared_prefs/otel_config.xml << EOF
<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>
<map>
  <string name=\"collector_endpoint\">http://10.0.2.2:14317</string>
  <string name=\"export_mode\">CONTINUOUS</string>
</map>
EOF
'"

# Force-stop so the app picks up new config on next launch
adb shell am force-stop io.opentelemetry.android.demo
```

To reset back to bundled defaults:

```bash
adb shell "run-as io.opentelemetry.android.demo rm -f shared_prefs/otel_config.xml"
```

### SharedPreferences key reference

All keys live in the `otel_config` shared preferences file.

| Preference key | Type | Default | Corresponding MobileConfig field |
|----------------|------|---------|----------------------------------|
| `service_name` | String | `"otel-mobile-demo"` | `serviceName` |
| `service_version` | String | `"1.0.0"` | `serviceVersion` |
| `collector_endpoint` | String | `"http://10.0.2.2:4317"` | `collectorEndpoint` |
| `export_mode` | String | `"CONTINUOUS"` | `exportMode` |
| `trace_export_interval_seconds` | Long | `30` | `traceExportIntervalSeconds` |
| `metric_export_interval_seconds` | Long | `60` | `metricExportIntervalSeconds` |
| `prediction_interval_seconds` | Long | `30` | `predictionIntervalSeconds` |
| `auth_token` | String | `""` | Assembled into `headers["Authorization"]` |
| `dataset` | String | `""` | Assembled into `headers["Dash0-Dataset"]` |
| `ram_buffer_size` | Int | `5000` | `ramBufferSize` |
| `disk_buffer_mb` | Int | `50` | `diskBufferMb` |
| `disk_buffer_ttl_hours` | Int | `24` | `diskBufferTtlHours` |
| `export_timeout_seconds` | Long | `30` | `exportTimeoutSeconds` |
| `config_poll_interval_seconds` | Long | `300` | `configPollIntervalSeconds` |
| `max_export_retries` | Int | `3` | `maxExportRetries` |
| `attach_context_attributes` | Boolean | `false` | `attachContextAttributes` |
| `build_channel` | String | `"debug"` | `buildChannel` |
| `sampling_rate` | Float | `1.0` | `samplingConfig` (normalRate) |
| `ui_telemetry_mode` | String | `"EVENTS"` | `uiTelemetryMode` |
| `text_capture_char_count` | Boolean | `true` | `textInputConfig.captureCharCount` |
| `text_capture_is_set` | Boolean | `true` | `textInputConfig.captureIsSet` |
| `text_capture_content` | Boolean | `false` | `textInputConfig.captureTextContent` |
| `session_enabled` | Boolean | `true` | `sessionConfig.enabled` |
| `session_inactivity_timeout_minutes` | Int | `15` | `sessionConfig.inactivityTimeoutMs` (converted) |
| `session_flush_on_termination` | Boolean | `true` | `sessionConfig.flushOnTermination` |
| `session_persist` | Boolean | `true` | `sessionConfig.persistSession` |
| `vitals_enabled` | Boolean | `true` | `vitalsConfig.enabled` |
| `vitals_detect_jank` | Boolean | `true` | `vitalsConfig.detectJank` |
| `vitals_monitor_thermal` | Boolean | `false` | `vitalsConfig.monitorThermalState` |
| `vitals_anr_threshold_ms` | Long | `3000` | `vitalsConfig.anrRiskThresholdMs` |
| `network_scrub_urls` | Boolean | `true` | `networkConfig.scrubUrls` |
| `network_scrub_headers` | Boolean | `true` | `networkConfig.scrubHeaders` |
| `network_error_threshold` | Int | `400` | `networkConfig.errorStatusThreshold` |
| `network_min_duration_ms` | Long | `0` | `networkConfig.minDurationMs` |
| `error_capture_uncaught` | Boolean | `true` | `errorConfig.captureUncaughtExceptions` |
| `error_capture_coroutines` | Boolean | `true` | `errorConfig.captureCoroutineExceptions` |
| `error_scrub_stack_traces` | Boolean | `true` | `errorConfig.scrubStackTraces` |
| `error_flush_on_error` | Boolean | `true` | `errorConfig.flushOnError` |
| `error_rate_limit` | Int | `10` | `errorConfig.rateLimit` |
| `error_dedupe_window_minutes` | Int | `5` | `errorConfig.deduplicateWindowMs` (converted) |
| `screenshot_enabled` | Boolean | `false` | `screenshotConfig.enabled` |
| `screenshot_on_screen_view` | Boolean | `false` | `screenshotConfig.captureOnScreenView` |
| `wireframe_enabled` | Boolean | `false` | `wireframeConfig.enabled` |
| `protocol` | String | `"grpc"` | Demo app only: transport protocol (`grpc` or `http`) |
| `backend_url` | String | `"http://10.0.2.2:3001"` | Demo app only: demo backend URL |
| `capture_lifecycle` | Boolean | `true` | Toggle lifecycle instrumentation |
| `capture_screens` | Boolean | `true` | Toggle screen view instrumentation |
| `capture_taps` | Boolean | `true` | Toggle tap instrumentation |
| `capture_long_press` | Boolean | `true` | Toggle long press detection |
| `capture_swipe` | Boolean | `true` | Toggle swipe detection |
| `capture_scroll` | Boolean | `true` | Toggle scroll instrumentation |
| `capture_text_input` | Boolean | `true` | Toggle text input instrumentation |
| `capture_back_press` | Boolean | `true` | Toggle back press instrumentation |
| `capture_fragments` | Boolean | `true` | Toggle fragment lifecycle tracking |

Device metrics keys live in a separate `telemetry_settings` preferences file:

| Preference key | Type | Default |
|----------------|------|---------|
| `metric_memory` | Boolean | `true` |
| `metric_battery` | Boolean | `true` |
| `metric_cpu` | Boolean | `true` |
| `metric_network` | Boolean | `true` |
| `metric_storage` | Boolean | `true` |
| `metric_thermal` | Boolean | `false` |
| `metric_display` | Boolean | `true` |
| `metric_system` | Boolean | `true` |
| `metric_app` | Boolean | `true` |
| `metric_location` | Boolean | `false` |

---

## 9. Sampling Configuration

Sampling controls what fraction of traces and spans the SDK records and exports. It does not affect log events (those are buffered and exported based on export policies, not sampling).

### Dynamic sampling (default)

```kotlin
SamplingConfig.dynamic(normalRate = 0.1, highPriorityRate = 1.0)
```

Normal spans are sampled at 10%. High-priority spans (errors, crashes, explicit markings) are always sampled at 100%.

### Presets

| Preset | Strategy | `samplingRate` | `highPrioritySamplingRate` | Use when |
|--------|----------|----------------|---------------------------|----------|
| `alwaysOn()` | `ALWAYS_ON` | `1.0` | `1.0` | Development — capture everything |
| `alwaysOff()` | `ALWAYS_OFF` | `0.0` | — | Temporarily disable tracing |
| `production(rate)` | `TRACE_ID_RATIO` | `0.1` (default) | — | Production — deterministic 10% sampling |
| `dynamic(normalRate, highPriorityRate)` | `DYNAMIC` | `0.05` (default) | `1.0` | Adaptive — low normal, full error coverage |
| `parentBased(rootRate)` | `PARENT_BASED` | — | — | Distributed tracing — inherit parent decision |

### Field reference for `SamplingConfig`

| Field | Type | Default | Valid range | Notes |
|-------|------|---------|-------------|-------|
| `strategy` | SamplingStrategy | `TRACE_ID_RATIO` | See presets | Sampling algorithm. |
| `samplingRate` | Double | `0.1` | 0.0–1.0 | Base sampling rate for `TRACE_ID_RATIO` and `DYNAMIC`. |
| `highPrioritySamplingRate` | Double | `1.0` | 0.0–1.0 | Rate for high-priority traces in `DYNAMIC` strategy. |
| `parentBasedRoot` | SamplingStrategy | `TRACE_ID_RATIO` | — | Root span strategy for `PARENT_BASED`. |
| `parentBasedRootSamplingRate` | Double | `0.1` | 0.0–1.0 | Root span sampling rate for `PARENT_BASED`. |

### Setting via JSON

The `samplingRate` top-level field in `otel-config.json` is a shorthand that creates a `DYNAMIC` config:

```json
{ "samplingRate": 0.05 }
```

is equivalent to:

```kotlin
SamplingConfig.dynamic(normalRate = 0.05, highPriorityRate = 1.0)
```

To use a different strategy, configure `SamplingConfig` programmatically via the DSL or Builder and pass it to `MobileConfig.samplingConfig`.
