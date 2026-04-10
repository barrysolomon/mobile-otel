# Phase 8: Configuration Documentation + Runtime Config — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship user-facing config guide, technical API reference, fix validated tests via runtime SharedPreferences override, and seed a config CLI tool in the control plane repo.

**Architecture:** Four independent deliverables — two documentation files (CONFIGURATION_GUIDE.md, API_REFERENCE.md rewrite), one script fix (run-validated-tests.sh swap asset-swap for adb SharedPreferences), one new CLI tool (otel-device.sh). No SDK code changes needed — ConfigManager already reads SharedPreferences as highest-priority config source.

**Tech Stack:** Markdown (docs), Bash (scripts), adb shell commands (SharedPreferences XML write via `run-as`)

---

## File Structure

### New Files (mobile-otel/)
| File | Responsibility |
|------|---------------|
| `docs/CONFIGURATION_GUIDE.md` | User-facing config guide: quick start, config methods, otel-config.json reference, export modes, buffer tuning, module config, runtime override, sampling |

### Modified Files (mobile-otel/)
| File | Change |
|------|--------|
| `docs/API_REFERENCE.md` | Complete rewrite: MobileOtel, OpenTelemetryMobile, MobileConfig, Builder, DSL, ExporterCustomizers, MobileInstrumentation, InstrumentationContext, all 18 modules |
| `scripts/test/run-validated-tests.sh` | Replace lines 79-133 (asset swap) with adb SharedPreferences write/restore |

### New Files (mobile-otel-control-plane/)
| File | Responsibility |
|------|---------------|
| `scripts/device/otel-device.sh` | Config CLI: `config set`, `config show`, `config reset`, `list` — wraps adb SharedPreferences |

---

## Task 1: User-Facing Configuration Guide

**Files:**
- Create: `docs/CONFIGURATION_GUIDE.md`
- Reference (read-only): `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/MobileConfig.kt`
- Reference (read-only): `docs/CONFIGURATION.md` (existing, partial — this new file supersedes it)
- Reference (read-only): `examples/demo-app/android/src/debug/assets/otel-config.json.template`

- [ ] **Step 1: Read reference files for accuracy**

Read these files to extract exact field names, types, defaults, and ranges:
- `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/MobileConfig.kt` (lines 99-271 — data class + builder)
- `otel-android-mobile/config/MobileOtelDsl.kt` (DSL classes)
- `examples/demo-app/android/src/main/java/io/opentelemetry/android/demo/ConfigManager.kt` (SharedPreferences keys, lines 203-314)
- `docs/CONFIGURATION.md` (existing content to incorporate)
- `examples/demo-app/android/src/debug/assets/otel-config.json.template`

- [ ] **Step 2: Write CONFIGURATION_GUIDE.md**

Create `docs/CONFIGURATION_GUIDE.md` with this structure. The content must be accurate to the actual code — cross-reference MobileConfig fields exactly.

```markdown
# Configuration Guide

How to configure the Dash0 Mobile Observability SDK for your Android app.

## Quick Start

Two paths to get started:

**Option A — Kotlin DSL (recommended)**

```kotlin
val otel = MobileOtel.initialize(application) {
    service {
        name = "my-app"
        version = "1.0.0"
    }
    export {
        endpoint = "https://ingress.dash0.com:4317"
        mode = ExportMode.HYBRID
        headers = mapOf(
            "Authorization" to "Bearer $token",
            "Dash0-Dataset" to "mobile"
        )
    }
}
```

**Option B — JSON config file**

Copy the template and fill in your credentials:

```bash
cp examples/demo-app/android/src/debug/assets/otel-config.json.template \
   examples/demo-app/android/src/debug/assets/otel-config.json
```

Then edit `otel-config.json` — replace `YOUR_COLLECTOR_ENDPOINT`, `YOUR_AUTH_TOKEN`, and `YOUR_DATASET_NAME`.

## Configuration Methods (priority order)

The SDK loads configuration from multiple sources. Higher-priority sources override lower ones:

| Priority | Source | When to use |
|----------|--------|-------------|
| 1 (highest) | **SharedPreferences** runtime override | Testing, debugging, switching endpoints without rebuild |
| 2 | **Kotlin DSL** (`MobileOtel.initialize(context) { }`) | Production apps — type-safe, IDE completion |
| 3 | **MobileConfig builder** (`MobileConfig.Builder()`) | Programmatic alternative to DSL |
| 4 (lowest) | **otel-config.json** (bundled asset) | Demo apps, quick prototyping |

## otel-config.json Reference

Bundle this file at `assets/otel-config.json` in your APK.

### Required Fields

| Field | Type | Description |
|-------|------|-------------|
| `serviceName` | String | OTel `service.name` resource attribute |
| `serviceVersion` | String | OTel `service.version` resource attribute |
| `collectorEndpoint` | String | OTLP/gRPC endpoint (e.g., `https://ingress.dash0.com:4317`) |

### Optional Fields

| Field | Type | Default | Valid Range | Description |
|-------|------|---------|-------------|-------------|
| `exportMode` | String | `"CONDITIONAL"` | `CONDITIONAL`, `CONTINUOUS`, `HYBRID` | Export strategy |
| `traceExportIntervalSeconds` | Long | `30` | >= 1 | Periodic trace export interval (CONTINUOUS mode) |
| `metricExportIntervalSeconds` | Long | `60` | >= 1 | Periodic metric export interval |
| `exportTimeoutSeconds` | Long | `30` | >= 1 | Per-export timeout |
| `maxExportRetries` | Int | `3` | 0-10 | Retry count on export failure |
| `ramBufferSize` | Int | `5000` | 1-100,000 | Max events in RAM ring buffer |
| `diskBufferMb` | Int | `50` | 1-500 | Max SQLite disk buffer size (MB) |
| `diskBufferTtlHours` | Int | `24` | 1-168 | TTL for disk-buffered events |
| `headers` | Object | `null` | — | Auth headers (e.g., `{"Authorization": "Bearer TOKEN"}`) |

### Example Configs

**Dash0 Cloud:**
```json
{
  "serviceName": "my-app",
  "serviceVersion": "1.0.0",
  "collectorEndpoint": "https://ingress.dash0.com:4317",
  "exportMode": "HYBRID",
  "headers": {
    "Authorization": "Bearer YOUR_AUTH_TOKEN",
    "Dash0-Dataset": "otel-mobile"
  }
}
```

**Local Collector (development):**
```json
{
  "serviceName": "my-app-dev",
  "serviceVersion": "0.0.1",
  "collectorEndpoint": "http://10.0.2.2:4317",
  "exportMode": "CONTINUOUS",
  "traceExportIntervalSeconds": 5
}
```

**Battery-efficient production:**
```json
{
  "serviceName": "my-app",
  "serviceVersion": "2.1.0",
  "collectorEndpoint": "https://ingress.dash0.com:4317",
  "exportMode": "CONDITIONAL",
  "ramBufferSize": 10000,
  "diskBufferMb": 100,
  "diskBufferTtlHours": 48,
  "headers": {
    "Authorization": "Bearer YOUR_AUTH_TOKEN",
    "Dash0-Dataset": "otel-mobile"
  }
}
```

## Kotlin DSL Reference

The DSL provides type-safe configuration with IDE completion:

```kotlin
val otel = MobileOtel.initialize(application) {
    service {
        name = "my-app"
        version = "1.0.0"
    }

    export {
        endpoint = "https://ingress.dash0.com:4317"
        mode = ExportMode.HYBRID
        headers = mapOf("Authorization" to "Bearer $token")
        timeoutSeconds = 30
        maxRetries = 3
        traceIntervalSeconds = 30
        metricIntervalSeconds = 60
    }

    buffering {
        ramSize = 5000
        diskMb = 50
        ttlHours = 24
    }

    session {
        renewalMinutes = 15
    }

    uiTelemetryMode = UiTelemetryMode.BOTH

    exportCustomizers {
        span { exporter ->
            // Wrap the span exporter (e.g., add filtering)
            MyFilteringSpanExporter(exporter)
        }
        log { exporter ->
            // Wrap the log exporter
            MyEnrichingLogExporter(exporter)
        }
    }

    instrumentations {
        discoverAll()  // Auto-discover our + upstream modules
    }
}
```

### DSL Blocks

| Block | Purpose | Key Properties |
|-------|---------|---------------|
| `service { }` | Service identity | `name`, `version` |
| `export { }` | Export configuration | `endpoint`, `mode`, `headers`, `timeoutSeconds`, `maxRetries`, `traceIntervalSeconds`, `metricIntervalSeconds` |
| `buffering { }` | Buffer tuning | `ramSize`, `diskMb`, `ttlHours` |
| `session { }` | Session management | `renewalMinutes` |
| `exportCustomizers { }` | Exporter wrapping | `log { }`, `span { }`, `metric { }` |
| `instrumentations { }` | Module discovery | `discoverAll()`, `discoverOwn()`, `add(module)` |

## Export Modes

### CONDITIONAL (default) — Battery efficient

Events accumulate silently in the buffer. Nothing is exported until a policy trigger fires (e.g., crash, UI freeze, HTTP error). When triggered, `flushWindow(N)` exports only the last N minutes of events.

**Best for:** Production apps where battery life matters. You only pay the network cost when something interesting happens.

### CONTINUOUS — Full visibility

A periodic scheduler calls `forceFlush()` every `traceExportIntervalSeconds`. Policy triggers also fire immediate flushes. Device metrics export on their own interval.

**Best for:** Development, debugging, or apps where you need real-time visibility.

### HYBRID — Balanced

Device health metrics (battery, memory, CPU, thermal) export periodically at 2x the metric interval. Event data uses CONDITIONAL — buffered until a policy trigger fires.

**Best for:** Production apps that need continuous device health monitoring but battery-efficient event export.

| Mode | Periodic Export | Policy Flush | Device Metrics | Battery Impact |
|------|----------------|-------------|----------------|----------------|
| CONDITIONAL | No | Yes | No | Lowest |
| CONTINUOUS | Yes | Yes | Yes | Highest |
| HYBRID | No | Yes | Yes (2x interval) | Medium |

## Buffer Tuning

The SDK uses a dual-tier buffer: fast RAM ring buffer backed by persistent SQLite disk storage.

### RAM Buffer (`ramBufferSize`)

| Setting | Default | Range | Description |
|---------|---------|-------|-------------|
| `ramBufferSize` | 5000 | 1-100,000 | Max events in ConcurrentLinkedQueue |

**When to increase:** High-throughput apps generating >5000 events between flushes. More RAM buffer = fewer disk writes = lower I/O overhead.

**When to decrease:** Memory-constrained devices. Each buffered event is ~200-500 bytes in RAM.

### Disk Buffer (`diskBufferMb`, `diskBufferTtlHours`)

| Setting | Default | Range | Description |
|---------|---------|-------|-------------|
| `diskBufferMb` | 50 | 1-500 | Max SQLite database size (MB) |
| `diskBufferTtlHours` | 24 | 1-168 (7 days) | Time-to-live for stored events |

When the RAM buffer overflows, events spill to SQLite. The disk buffer survives process death — this is what makes crash-recovery telemetry possible.

**When to increase `diskBufferMb`:** Apps with long sessions or CONDITIONAL mode where events accumulate for hours before flush. 50 MB holds ~100K-500K events depending on payload size.

**When to increase `diskBufferTtlHours`:** If you need crash context from events older than 24 hours (e.g., background apps that crash days after the triggering event).

## Instrumentation Module Configuration

### Screenshot (`@Incubating`)

```kotlin
screenshotConfig = ScreenshotConfig(
    enabled = true,
    quality = 50,           // JPEG quality (1-100)
    maxWidth = 360,         // Max resolution width (px)
    maxHeight = 640,        // Max resolution height (px)
    redactText = true,      // Replace text with blocks
    maxPayloadBytes = 50_000  // Cap per screenshot
)
```

### Wireframe (`@Incubating`)

```kotlin
wireframeConfig = WireframeConfig(
    enabled = true,
    captureIntervalMs = 1000  // Minimum interval between captures
)
```

### Network

Privacy presets control what network data is captured:

| Preset | Headers | Body | URL Params | Trace Context |
|--------|---------|------|------------|---------------|
| `default()` | No | No | Scrubbed | Yes |
| `minimal()` | No | No | Scrubbed | No |
| `debug()` | Yes | Yes | Full | Yes |
| `production()` | No | No | Scrubbed | Yes |

### Errors

```kotlin
errorConfig = ErrorConfig(
    captureUncaughtExceptions = true,
    captureCoroutineExceptions = true,
    scrubStackTraces = false,
    deduplicateWindowMs = 300_000,  // 5 min dedup
    rateLimit = 10                  // Max 10 errors/min
)
```

### Vitals

Presets: `VitalsConfig.default()`, `minimal()`, `aggressive()`, `batteryFriendly()`

```kotlin
vitalsConfig = VitalsConfig(
    measureAppStart = true,
    detectJank = true,
    trackInputLatency = true,
    monitorAnrRisk = true,
    monitorMemoryPressure = true,
    jankThresholdMs = 16.0,         // 60fps frame budget
    anrRiskThresholdMs = 3000L      // 3s main thread block
)
```

## Runtime Config Override

Change SDK configuration on a running app without rebuilding. Useful for testing, debugging, or switching between endpoints.

### How it works

`ConfigManager` reads SharedPreferences with the highest priority. Write values to `otel_config` SharedPreferences, force-stop the app, and relaunch — the new config takes effect immediately.

### Manual adb command

```bash
# Write config override
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

# Force-stop so app picks up new config on next launch
adb shell am force-stop io.opentelemetry.android.demo

# Reset to bundled defaults
adb shell "run-as io.opentelemetry.android.demo rm -f shared_prefs/otel_config.xml"
```

### Config CLI tool

The `otel-device` CLI (in `mobile-otel-control-plane/scripts/device/`) wraps these adb commands:

```bash
otel-device config set --endpoint http://10.0.2.2:14317 --mode CONTINUOUS
otel-device config show
otel-device config reset
```

See the control plane repo for full CLI documentation.

### SharedPreferences key reference

| CLI Flag | SharedPreferences Key | Values |
|----------|----------------------|--------|
| `--endpoint` | `collector_endpoint` | URL string |
| `--auth-token` | `auth_token` | Bearer token string |
| `--dataset` | `dataset` | Dataset name string |
| `--mode` | `export_mode` | `CONDITIONAL`, `CONTINUOUS`, `HYBRID` |
| `--service-name` | `service_name` | String |
| `--service-version` | `service_version` | String |

## Sampling Configuration

### Dynamic Sampling (default)

```kotlin
samplingConfig = SamplingConfig.dynamic(
    normalRate = 0.1,        // 10% of normal traces
    highPriorityRate = 1.0   // 100% of error/crash traces
)
```

### Presets

| Preset | Rate | Use Case |
|--------|------|----------|
| `alwaysOn()` | 100% | Development |
| `production(0.1)` | 10% trace-ID ratio | Cost-conscious production |
| `dynamic(0.05, 1.0)` | 5% normal, 100% errors | Balanced production |
```

- [ ] **Step 3: Commit**

```bash
git add docs/CONFIGURATION_GUIDE.md
git commit -m "docs: add user-facing configuration guide (US-045)

Comprehensive guide covering all config methods (DSL, builder, JSON, SharedPreferences),
export modes, buffer tuning, module config, runtime override, and sampling."
```

---

## Task 2: Technical API Reference

**Files:**
- Modify: `docs/API_REFERENCE.md` (complete rewrite — current content is 69 lines, just policy DSL)
- Reference (read-only): `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/MobileOtel.kt`
- Reference (read-only): `otel-android-mobile/OpenTelemetryMobile.kt`
- Reference (read-only): `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/MobileConfig.kt`
- Reference (read-only): `otel-android-mobile/config/MobileOtelDsl.kt`
- Reference (read-only): `otel-android-mobile/config/ExporterCustomizers.kt`
- Reference (read-only): `otel-android-mobile-core/instrumentation/MobileInstrumentation.kt`
- Reference (read-only): `otel-android-mobile-core/instrumentation/InstrumentationContext.kt`

- [ ] **Step 1: Read all public API source files**

Read each file listed above to extract exact method signatures, parameter types, return types, and doc comments.

- [ ] **Step 2: Write API_REFERENCE.md**

Replace the entire content of `docs/API_REFERENCE.md` with the comprehensive reference below. Every method signature must match the actual source code — verify against the files read in step 1.

```markdown
# API Reference

Technical reference for all public classes and methods in the Dash0 Mobile Observability SDK.

For a user-facing guide on configuration, see [Configuration Guide](./CONFIGURATION_GUIDE.md).

## MobileOtel

**Package:** `io.opentelemetry.android.mobile`
**Type:** `object` (Kotlin singleton)

The primary entry point for initializing and interacting with the SDK.

### Initialization

| Method | Returns | Description |
|--------|---------|-------------|
| `initialize(context: Context, config: MobileConfig)` | `MobileLoggerProvider` | Initialize with explicit config |
| `initialize(context: Context, config: MobileConfig, customizers: ExporterCustomizers)` | `MobileLoggerProvider` | Initialize with config + exporter customizers |
| `initialize(context: Context, block: MobileOtelDsl.() -> Unit)` | `OpenTelemetryMobile` | Initialize with Kotlin DSL |

### Identity & Sessions

| Method | Returns | Description |
|--------|---------|-------------|
| `identify(user: String)` | `Unit` | Set user identity for session |
| `clearIdentity()` | `Unit` | Clear user identity |
| `terminateSession(reason: String?)` | `Unit` | End current session, start new one |

### Telemetry

| Method | Returns | Description |
|--------|---------|-------------|
| `sendEvent(name: String, attributes: Map<String, String>, severity: Severity?)` | `Unit` | Emit a custom event |
| `reportError(throwable: Throwable, context: String?)` | `Unit` | Report an error manually |
| `getCoroutineExceptionHandler()` | `CoroutineExceptionHandler` | Handler for coroutine errors |

### Buffer & Export

| Method | Returns | Description |
|--------|---------|-------------|
| `forceFlush(windowMinutes: Int?, timeoutSeconds: Long?)` | `Unit` | Force export, optionally limited to time window |
| `getBufferStats()` | `BufferStats` | RAM + disk buffer usage |

### Lifecycle

| Method | Returns | Description |
|--------|---------|-------------|
| `shutdown()` | `Unit` | Flush and shut down the SDK |
| `getProvider()` | `MobileLoggerProvider` | Access the logger provider |

### Global Attributes

| Method | Returns | Description |
|--------|---------|-------------|
| `addGlobalAttribute(key: String, value: String)` | `Unit` | Add attribute to all telemetry |
| `removeGlobalAttribute(key: String)` | `Unit` | Remove a global attribute |
| `clearGlobalAttributes()` | `Unit` | Remove all global attributes |

---

## OpenTelemetryMobile

**Package:** `io.opentelemetry.android.mobile`
**Type:** `class`

Returned by the DSL initializer. Provides access to OTel SDK instances and flush controls.

| Property/Method | Type/Returns | Description |
|----------------|-------------|-------------|
| `openTelemetry` | `OpenTelemetry` | The configured OTel SDK instance |
| `sessionId` | `String` | Current session ID |
| `getTracer(scope: String)` | `Tracer` | Get an OTel Tracer |
| `getLogger(scope: String)` | `Logger` | Get an OTel Logger |
| `getMeter(scope: String)` | `Meter` | Get an OTel Meter |
| `forceFlush(timeoutSeconds: Long?)` | `Unit` | Force export all buffered data |
| `flushWindow(minutes: Int)` | `Unit` | Export events from last N minutes |
| `shutdown(timeoutSeconds: Long?)` | `Unit` | Flush and shut down |

---

## OTelMobile

**Package:** `io.opentelemetry.android.mobile`
**Type:** `object` (Kotlin singleton)

Auto-instrumentation entry point. Installs all instrumentation modules automatically.

| Method | Returns | Description |
|--------|---------|-------------|
| `start(application: Application, config: MobileConfig)` | `Unit` | Initialize SDK + install all instrumentation |
| `stop(timeoutSeconds: Long?)` | `Unit` | Stop instrumentation, flush, shut down |
| `getLoggerProvider()` | `MobileLoggerProvider` | Access the logger provider |
| `getLogger(scope: String)` | `Logger` | Get OTel Logger |
| `getTracer(scope: String, version: String?)` | `Tracer` | Get OTel Tracer |
| `getMeter(scope: String)` | `Meter` | Get OTel Meter |
| `startJourney(name: String)` | `Span` | Start a journey span (parent for page spans) |
| `builder(application: Application, openTelemetry: OpenTelemetry)` | `OTelMobileBuilder` | Get builder for fine-grained config |
| `getLastRecoveryType()` | `RecoveryType?` | Crash/ANR/low-memory recovery from last start |

---

## MobileConfig

**Package:** `io.opentelemetry.android.mobile.config`
**Type:** `data class`

All SDK configuration fields. Validated on construction — invalid values throw `IllegalArgumentException`.

### Required Fields

| Field | Type | Description |
|-------|------|-------------|
| `serviceName` | `String` | OTel `service.name` |
| `serviceVersion` | `String` | OTel `service.version` |
| `collectorEndpoint` | `String` | OTLP/gRPC endpoint URL |

### Export Fields

| Field | Type | Default | Range | Description |
|-------|------|---------|-------|-------------|
| `exportMode` | `ExportMode` | `CONDITIONAL` | — | `CONDITIONAL`, `CONTINUOUS`, `HYBRID` |
| `traceExportIntervalSeconds` | `Long` | `30` | >= 1 | Trace export interval |
| `metricExportIntervalSeconds` | `Long` | `60` | >= 1 | Metric export interval |
| `exportTimeoutSeconds` | `Long` | `30` | >= 1 | Per-export timeout |
| `maxExportRetries` | `Int` | `3` | 0-10 | Retries on failure |
| `headers` | `Map<String, String>?` | `null` | — | Auth/routing headers |

### Buffer Fields

| Field | Type | Default | Range | Description |
|-------|------|---------|-------|-------------|
| `ramBufferSize` | `Int` | `5000` | 1-100,000 | RAM ring buffer capacity |
| `diskBufferMb` | `Int` | `50` | 1-500 | Disk buffer max (MB) |
| `diskBufferTtlHours` | `Int` | `24` | 1-168 | Disk event TTL (hours) |

### Sub-Configuration Fields

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `uiTelemetryMode` | `UiTelemetryMode` | `EVENTS` | `EVENTS`, `SPANS`, `BOTH` |
| `samplingConfig` | `SamplingConfig` | `dynamic(0.1, 1.0)` | Sampling strategy |
| `sessionConfig` | `SessionConfig` | `SessionConfig()` | Session management |
| `vitalsConfig` | `VitalsConfig` | `default()` | Device vitals collection |
| `networkConfig` | `NetworkConfig` | `default()` | Network capture |
| `errorConfig` | `ErrorConfig` | `default()` | Error handling |
| `breadcrumbConfig` | `BreadcrumbConfig` | `default()` | Journey breadcrumbs |
| `deviceMetricsConfig` | `DeviceMetricsConfig` | `default()` | Device metrics |
| `screenshotConfig` | `ScreenshotConfig` | `disabled` | `@Incubating` Screenshot capture |
| `wireframeConfig` | `WireframeConfig` | `disabled` | `@Incubating` Wireframe capture |

### MobileConfig.Builder

Fluent builder alternative to data class constructor:

```kotlin
val (config, customizers) = MobileConfig.Builder()
    .serviceName("my-app")
    .serviceVersion("1.0.0")
    .collectorEndpoint("https://ingress.dash0.com:4317")
    .exportMode(ExportMode.HYBRID)
    .headers(mapOf("Authorization" to "Bearer $token"))
    .ramBufferSize(10000)
    .addLogExporterCustomizer { exporter -> MyLogExporter(exporter) }
    .addSpanExporterCustomizer { exporter -> MySpanExporter(exporter) }
    .buildWithCustomizers()
```

---

## Kotlin DSL

**Top-level function:** `MobileOtel.initialize(context) { block }`

### DSL Blocks

```
mobileOtel {
    service { name, version }
    export { endpoint, mode, headers, timeoutSeconds, maxRetries, traceIntervalSeconds, metricIntervalSeconds }
    buffering { ramSize, diskMb, ttlHours }
    session { renewalMinutes }
    exportCustomizers { log {}, span {}, metric {} }
    instrumentations { discoverAll(), discoverOwn(), add() }
    uiTelemetryMode
    screenshotConfig, wireframeConfig, networkConfig, errorConfig, vitalsConfig, breadcrumbConfig
}
```

---

## ExporterCustomizers

**Package:** `io.opentelemetry.android.mobile.config`

Container for exporter wrapping functions. Customizers chain in registration order (first registered = innermost wrapper).

```kotlin
class ExporterCustomizers(
    val log: List<(LogRecordExporter) -> LogRecordExporter>,
    val span: List<(SpanExporter) -> SpanExporter>,
    val metric: List<(MetricExporter) -> MetricExporter>
)
```

**Builder:**
```kotlin
ExporterCustomizers.Builder()
    .addLog { exporter -> /* wrap */ }
    .addSpan { exporter -> /* wrap */ }
    .addMetric { exporter -> /* wrap */ }
    .build()
```

**Constant:** `ExporterCustomizers.EMPTY` — no customizers.

---

## MobileInstrumentation

**Package:** `io.opentelemetry.android.mobile.instrumentation`
**Type:** `interface`

Contract for all instrumentation modules.

```kotlin
interface MobileInstrumentation {
    val instrumentationName: String
    val instrumentationVersion: String  // default "1.0.0"
    fun install(application: Application, context: InstrumentationContext)
    fun uninstall()  // default no-op
}
```

### @Supersedes Annotation

Declares that a MobileInstrumentation replaces upstream modules by name:

```kotlin
@Supersedes(names = ["CrashInstrumentation", "AnrInstrumentation"])
class ErrorInstrumentation : MobileInstrumentation { ... }
```

When `InstrumentationRegistry.install()` runs, any upstream `AndroidInstrumentation` whose name appears in a `@Supersedes` annotation is skipped.

---

## InstrumentationContext

**Package:** `io.opentelemetry.android.mobile.instrumentation`

Shared state passed to each module at install time.

```kotlin
class InstrumentationContext(
    val openTelemetry: OpenTelemetry,
    val sessionProvider: MobileSessionProvider,
    val windowEventHub: WindowEventHub,
    val application: Application,
    val uiTelemetryMode: UiTelemetryMode = EVENTS,
    val breadcrumbManager: BreadcrumbManager? = null,
    val clock: Clock? = null
)
```

**Convenience methods:**
- `tracer(scope: String): Tracer`
- `logger(scope: String): Logger`
- `meter(scope: String): Meter`
- `addBreadcrumb(breadcrumb: Breadcrumb)`

---

## Instrumentation Modules

| Module | Directory | `instrumentationName` | Signal | Key Config |
|--------|-----------|----------------------|--------|-----------|
| LifecycleInstrumentation | `lifecycle/` | `lifecycle` | Spans | — |
| ScreenViewInstrumentation | `screen/` | `screen-view` | Log + Span | — |
| TapInstrumentation | `tap/` | `tap` | Log + Span | `TapConfig` (swipeMinDistancePx, addSpanEvents) |
| ScrollInstrumentation | `scroll/` | `scroll` | Span | Throttled |
| TextInputInstrumentation | `text-input/` | `text-input` | Span | `TextInputConfig` |
| BackPressInstrumentation | `back-press/` | `back-press` | Span | — |
| FreezeInstrumentation | `freeze/` | `freeze` | Log | Jank threshold |
| ErrorInstrumentation | `errors/` | `errors` | Log | `ErrorConfig` (dedup, rate limit, scrub) |
| VitalsInstrumentation | `vitals/` | `vitals` | Metric | `VitalsConfig` (thresholds, presets) |
| NetworkInstrumentation | `network/` | `network` | Span | `NetworkConfig` (privacy presets) |
| ScreenshotInstrumentation | `screenshot/` | `screenshot` | Log | `ScreenshotConfig` `@Incubating` |
| WireframeInstrumentation | `wireframe/` | `wireframe` | Log | `WireframeConfig` `@Incubating` |
| ComposeClickInstrumentation | `compose-click/` | `compose-click` | Log + Span | `ComposeClickConfig` |
| ScreenOrientationInstrumentation | `screen-orientation/` | `screen-orientation` | Log | — |
| DatabaseInstrumentation | `database/` | `database` | Span | — |
| FileIOInstrumentation | `file-io/` | `file-io` | Span | — |
| SystemEventsInstrumentation | `system-events/` | `system-events` | Log | — |
| TimberInstrumentation | `timber/` | `timber` | Log | — |

---

## Related Documentation

- [Configuration Guide](./CONFIGURATION_GUIDE.md) — User-facing how-to
- [Architecture](./reference/ARCHITECTURE.md) — Internal architecture deep dive
- [Testing Strategy](./guides/TESTING_STRATEGY.md) — Testing approach
```

- [ ] **Step 3: Commit**

```bash
git add docs/API_REFERENCE.md
git commit -m "docs: rewrite API reference with full public API (US-046)

Complete reference for MobileOtel, OpenTelemetryMobile, OTelMobile, MobileConfig,
Builder, DSL, ExporterCustomizers, MobileInstrumentation, InstrumentationContext,
and all 18 instrumentation modules. Replaces the old minimal policy-DSL-only reference."
```

---

## Task 3: Fix Validated Tests — Runtime Config via adb

**Files:**
- Modify: `scripts/test/run-validated-tests.sh` (replace lines 79-133)
- Reference (read-only): `examples/demo-app/android/src/main/java/io/opentelemetry/android/demo/ConfigManager.kt` (SharedPreferences keys)

- [ ] **Step 1: Read the current script and ConfigManager**

Read `scripts/test/run-validated-tests.sh` (full file) and `ConfigManager.kt` (lines 200-320) to confirm:
- The exact section to replace (lines 79-133: asset swap block)
- The SharedPreferences file name (`otel_config`)
- The exact key names (`collector_endpoint`, `export_mode`, `service_name`, `service_version`, `config_loaded_from_bundle`)
- The demo app package name (`io.opentelemetry.android.demo`)

- [ ] **Step 2: Replace the asset swap with SharedPreferences override**

In `scripts/test/run-validated-tests.sh`, replace lines 79-133 (from `# ── 3. Configure demo app` through `rm -f "$local_config"` / end of `fi`) with the adb SharedPreferences approach. The new section:

```bash
# ── 3. Build and install demo app ──────────────────────────────────────────

if [ "$SKIP_SCENARIOS" = false ]; then
  log "Starting demo backend"
  if ! curl -sf http://localhost:3001/health > /dev/null 2>&1; then
    cd "$REPO_ROOT/examples/demo-backend"
    npm run dev > /tmp/demo-backend.log 2>&1 &
    sleep 3
  fi
  ok "Backend running"

  log "Building and installing demo app (normal build, no config swap)"
  cd "$DEMO_APP"
  ./gradlew installDebug --quiet
  ok "Installed"

  # ── 4. Write SharedPreferences override → local collector ────────────────

  log "Writing SharedPreferences override → localhost:14317"
  PACKAGE="io.opentelemetry.android.demo"

  for serial in $(adb devices | grep "emulator" | awk '{print $1}'); do
    adb -s "$serial" shell "run-as $PACKAGE sh -c '
      mkdir -p shared_prefs
      cat > shared_prefs/otel_config.xml << PREFS_EOF
<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>
<map>
  <string name=\"collector_endpoint\">http://10.0.2.2:14317</string>
  <string name=\"export_mode\">CONTINUOUS</string>
  <string name=\"service_name\">validated-test</string>
  <string name=\"service_version\">1.0.0</string>
  <string name=\"config_loaded_from_bundle\">true</string>
</map>
PREFS_EOF
    '"
    # Force-stop so app picks up new config on relaunch
    adb -s "$serial" shell am force-stop "$PACKAGE"
    ok "Configured $serial → localhost:14317"
  done

  # ── 5. Run scenario tests ───────────────────────────────────────────────

  log "Running scenario tests → local collector"
  ./gradlew :android:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=io.opentelemetry.android.demo.scenarios.UserJourneyScenarios
  ok "Scenarios complete"

  # Wait for collector to flush
  log "Waiting for collector to flush (5s)"
  sleep 5

  # ── 6. Restore device config ────────────────────────────────────────────

  log "Restoring device config (removing SharedPreferences override)"
  for serial in $(adb devices | grep "emulator" | awk '{print $1}'); do
    adb -s "$serial" shell "run-as $PACKAGE rm -f shared_prefs/otel_config.xml"
    ok "Restored $serial"
  done
fi
```

Note: The section numbering in the rest of the script shifts — the old "5. Validate" becomes the next section after this block, and "6. Stop collector" follows that. Update the comment headers accordingly.

- [ ] **Step 3: Update the remaining section numbers**

The validate and stop sections (currently numbered 5 and 6) become 7 and 8:

Old line 136: `# ── 5. Validate received telemetry` → `# ── 7. Validate received telemetry`
Old line 142: `# ── 6. Stop collector` → `# ── 8. Stop collector`

- [ ] **Step 4: Test the script (manual verification)**

Run the script with `--skip-scenarios` to verify it starts/stops the collector without errors:

```bash
cd /Users/barrysolomon/Projects/Dash0/Mobile\ Observability/mobile-otel
bash scripts/test/run-validated-tests.sh --skip-scenarios
```

Expected: Collector starts, validation runs (may fail if no prior data — that's OK), collector stops.

If an emulator is running, test the SharedPreferences write:

```bash
PACKAGE="io.opentelemetry.android.demo"
adb shell "run-as $PACKAGE sh -c 'mkdir -p shared_prefs && cat > shared_prefs/otel_config.xml << EOF
<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>
<map>
  <string name=\"collector_endpoint\">http://10.0.2.2:14317</string>
</map>
EOF
'"
# Verify it was written
adb shell "run-as $PACKAGE cat shared_prefs/otel_config.xml"
# Clean up
adb shell "run-as $PACKAGE rm -f shared_prefs/otel_config.xml"
```

- [ ] **Step 5: Commit**

```bash
git add scripts/test/run-validated-tests.sh
git commit -m "fix: replace broken asset swap with SharedPreferences runtime override (US-048)

The old approach swapped otel-config.json before building, but Gradle cached the APK
and ignored the asset change. Now we write to SharedPreferences via adb after install,
which ConfigManager reads as highest-priority config. No rebuild needed."
```

---

## Task 4: Config CLI Tool (otel-device.sh)

**Files:**
- Create: `mobile-otel-control-plane/scripts/device/otel-device.sh`
- Reference (read-only): `examples/demo-app/android/src/main/java/io/opentelemetry/android/demo/ConfigManager.kt` (SharedPreferences key names)

- [ ] **Step 1: Create the scripts/device/ directory**

```bash
mkdir -p /Users/barrysolomon/Projects/Dash0/Mobile\ Observability/mobile-otel-control-plane/scripts/device
```

- [ ] **Step 2: Write otel-device.sh**

Create `mobile-otel-control-plane/scripts/device/otel-device.sh`:

```bash
#!/usr/bin/env bash
# otel-device — CLI for managing OTel SDK configuration on Android devices.
#
# Seed for a full control plane CLI. Currently wraps adb SharedPreferences
# operations for runtime config override.
#
# Future: will support gateway API calls for remote config management.
#
# Usage:
#   otel-device config set --endpoint URL [--mode MODE] [--auth-token TOKEN] ...
#   otel-device config show
#   otel-device config reset
#   otel-device list
#   otel-device -s SERIAL config show     # target specific device
#   otel-device --package com.my.app config show  # custom package
#
# Environment:
#   OTEL_DEVICE_PACKAGE  — override default package name
set -euo pipefail

# ── Defaults ───────────────────────────────────────────────────────────────

PKG="${OTEL_DEVICE_PACKAGE:-io.opentelemetry.android.demo}"
PREFS_FILE="shared_prefs/otel_config.xml"
SERIAL=""

# ── Helpers ────────────────────────────────────────────────────────────────

usage() {
  cat <<'USAGE'
otel-device — CLI for managing OTel SDK configuration on Android devices

COMMANDS
  config set   Set configuration values on a connected device
  config show  Show current SharedPreferences config override
  config reset Remove config override (restore bundled defaults)
  list         List connected devices/emulators

OPTIONS
  -s SERIAL        Target a specific device (from `adb devices`)
  --package PKG    Override package name (default: io.opentelemetry.android.demo)
                   Also settable via OTEL_DEVICE_PACKAGE env var

CONFIG SET FLAGS
  --endpoint URL           Collector endpoint (e.g., http://10.0.2.2:14317)
  --auth-token TOKEN       Authorization bearer token
  --dataset NAME           Dash0 dataset name
  --mode MODE              Export mode: CONDITIONAL, CONTINUOUS, or HYBRID
  --service-name NAME      OTel service.name
  --service-version VER    OTel service.version

EXAMPLES
  otel-device config set --endpoint http://10.0.2.2:14317 --mode CONTINUOUS
  otel-device config show
  otel-device config reset
  otel-device -s emulator-5554 config set --endpoint https://ingress.dash0.com:4317
  otel-device --package com.myapp list
USAGE
  exit 0
}

die() { echo "error: $*" >&2; exit 1; }

adb_cmd() {
  if [ -n "$SERIAL" ]; then
    adb -s "$SERIAL" "$@"
  else
    adb "$@"
  fi
}

# ── Parse global options ───────────────────────────────────────────────────

while [ $# -gt 0 ]; do
  case "$1" in
    -s)        SERIAL="$2"; shift 2 ;;
    --package) PKG="$2"; shift 2 ;;
    -h|--help) usage ;;
    *)         break ;;
  esac
done

[ $# -eq 0 ] && usage

COMMAND="$1"; shift

# ── list ───────────────────────────────────────────────────────────────────

cmd_list() {
  echo "Connected devices:"
  adb devices -l | tail -n +2 | grep -v "^$" | while IFS= read -r line; do
    echo "  $line"
  done
}

# ── config show ────────────────────────────────────────────────────────────

cmd_config_show() {
  echo "Package: $PKG"
  echo "Device:  ${SERIAL:-<default>}"
  echo ""

  local raw
  raw=$(adb_cmd shell "run-as $PKG cat $PREFS_FILE 2>/dev/null" || true)

  if [ -z "$raw" ]; then
    echo "No SharedPreferences override active. Using bundled defaults."
    return
  fi

  echo "Active SharedPreferences override:"
  echo "$raw" | while IFS= read -r line; do
    # Extract key="..." from <string name="key">value</string>
    case "$line" in
      *'<string name="'*)
        key=$(echo "$line" | sed 's/.*name="\([^"]*\)".*/\1/')
        val=$(echo "$line" | sed 's/.*">\(.*\)<\/string>.*/\1/')
        printf "  %-25s %s\n" "$key" "$val"
        ;;
    esac
  done
}

# ── config reset ───────────────────────────────────────────────────────────

cmd_config_reset() {
  adb_cmd shell "run-as $PKG rm -f $PREFS_FILE"
  adb_cmd shell "am force-stop $PKG"
  echo "Config override removed. App will use bundled defaults on next launch."
}

# ── config set ─────────────────────────────────────────────────────────────

cmd_config_set() {
  local endpoint="" auth_token="" dataset="" mode="" svc_name="" svc_version=""

  while [ $# -gt 0 ]; do
    case "$1" in
      --endpoint)        endpoint="$2"; shift 2 ;;
      --auth-token)      auth_token="$2"; shift 2 ;;
      --dataset)         dataset="$2"; shift 2 ;;
      --mode)            mode="$2"; shift 2 ;;
      --service-name)    svc_name="$2"; shift 2 ;;
      --service-version) svc_version="$2"; shift 2 ;;
      *) die "Unknown flag: $1" ;;
    esac
  done

  # Validate mode if provided
  if [ -n "$mode" ]; then
    case "$mode" in
      CONDITIONAL|CONTINUOUS|HYBRID) ;;
      *) die "Invalid mode: $mode (must be CONDITIONAL, CONTINUOUS, or HYBRID)" ;;
    esac
  fi

  # At least one value must be provided
  if [ -z "$endpoint" ] && [ -z "$auth_token" ] && [ -z "$dataset" ] && \
     [ -z "$mode" ] && [ -z "$svc_name" ] && [ -z "$svc_version" ]; then
    die "No values provided. Use --endpoint, --mode, --auth-token, --dataset, --service-name, or --service-version"
  fi

  # Build the XML entries
  local entries=""
  [ -n "$endpoint" ]    && entries="$entries  <string name=\"collector_endpoint\">$endpoint</string>\n"
  [ -n "$auth_token" ]  && entries="$entries  <string name=\"auth_token\">$auth_token</string>\n"
  [ -n "$dataset" ]     && entries="$entries  <string name=\"dataset\">$dataset</string>\n"
  [ -n "$mode" ]        && entries="$entries  <string name=\"export_mode\">$mode</string>\n"
  [ -n "$svc_name" ]    && entries="$entries  <string name=\"service_name\">$svc_name</string>\n"
  [ -n "$svc_version" ] && entries="$entries  <string name=\"service_version\">$svc_version</string>\n"
  # Always mark as loaded so ConfigManager treats prefs as authoritative
  entries="$entries  <string name=\"config_loaded_from_bundle\">true</string>\n"

  # Write to device
  adb_cmd shell "run-as $PKG sh -c '
    mkdir -p shared_prefs
    cat > $PREFS_FILE << PREFS_EOF
<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>
<map>
$(echo -e "$entries")</map>
PREFS_EOF
  '"

  # Force-stop so app picks up new config on relaunch
  adb_cmd shell "am force-stop $PKG"

  echo "Config set. App will use new values on next launch."
  echo ""
  cmd_config_show
}

# ── Dispatch ───────────────────────────────────────────────────────────────

case "$COMMAND" in
  list) cmd_list ;;
  config)
    [ $# -eq 0 ] && die "Missing subcommand: set, show, or reset"
    SUB="$1"; shift
    case "$SUB" in
      set)   cmd_config_set "$@" ;;
      show)  cmd_config_show ;;
      reset) cmd_config_reset ;;
      *)     die "Unknown config subcommand: $SUB (use set, show, or reset)" ;;
    esac
    ;;
  *) die "Unknown command: $COMMAND (use config or list)" ;;
esac
```

- [ ] **Step 3: Make executable**

```bash
chmod +x /Users/barrysolomon/Projects/Dash0/Mobile\ Observability/mobile-otel-control-plane/scripts/device/otel-device.sh
```

- [ ] **Step 4: Test CLI (manual, requires emulator)**

```bash
cd /Users/barrysolomon/Projects/Dash0/Mobile\ Observability/mobile-otel-control-plane

# List devices
scripts/device/otel-device.sh list

# Set endpoint
scripts/device/otel-device.sh config set --endpoint http://10.0.2.2:14317 --mode CONTINUOUS

# Show config
scripts/device/otel-device.sh config show

# Reset
scripts/device/otel-device.sh config reset

# Show after reset (should say "no override")
scripts/device/otel-device.sh config show

# Help
scripts/device/otel-device.sh --help
```

- [ ] **Step 5: Commit**

```bash
cd /Users/barrysolomon/Projects/Dash0/Mobile\ Observability/mobile-otel-control-plane
git add scripts/device/otel-device.sh
git commit -m "feat: add otel-device CLI for runtime config management (US-047)

Seed CLI tool that wraps adb SharedPreferences operations for managing
OTel SDK configuration on connected Android devices. Supports config set,
show, and reset. Designed to evolve into the full control plane CLI."
```

---

## Task 5: Update Documentation Index + Epic

**Files:**
- Modify: `docs/README.md` (add links to new docs)
- Modify: `docs/epics/UPSTREAM_SUPERSESSION_EPIC.md` (mark US-045 through US-048 as done)

- [ ] **Step 1: Read docs/README.md**

Read `docs/README.md` to find where to add the new doc links.

- [ ] **Step 2: Add links to new docs in README.md**

Add entries for `CONFIGURATION_GUIDE.md` and the updated `API_REFERENCE.md` in the appropriate section of the docs index.

- [ ] **Step 3: Mark Phase 8 items complete in the epic**

In `docs/epics/UPSTREAM_SUPERSESSION_EPIC.md`, change US-045 through US-048 status from `[ ]` to `[x]`:

```
| US-045 | User-facing configuration guide | [x] |
| US-046 | Technical docs: MobileConfig field reference | [x] |
| US-047 | Runtime config override mechanism | [x] |
| US-048 | Fix validated tests: use runtime config override | [x] |
```

- [ ] **Step 4: Commit**

```bash
cd /Users/barrysolomon/Projects/Dash0/Mobile\ Observability/mobile-otel
git add docs/README.md docs/epics/UPSTREAM_SUPERSESSION_EPIC.md
git commit -m "docs: mark Phase 8 complete, update doc index"
```
