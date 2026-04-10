# Android SDK — Technical API Reference

> **For user-facing configuration guidance** (export modes, policy DSL, sub-configs), see
> [docs/CONFIGURATION.md](./CONFIGURATION.md).
>
> **Gateway API** lives in the
> [mobile-otel-control-plane](https://github.com/barrysolomon/mobile-otel-control-plane) repository.

All public classes live in `io.opentelemetry.android.mobile` unless otherwise noted.
Every class and method marked `@Incubating` may change without a deprecation cycle — opt in with
`@OptIn(Incubating::class)` or suppress the warning at the call-site.

---

## Table of Contents

1. [MobileOtel](#1-mobileotel)
2. [OpenTelemetryMobile](#2-opentelemetrymobile)
3. [OTelMobile](#3-otelmobile)
4. [MobileConfig](#4-mobileconfig)
5. [Kotlin DSL](#5-kotlin-dsl)
6. [ExporterCustomizers](#6-exportercustomizers)
7. [MobileInstrumentation](#7-mobileinstrumentation)
8. [@Supersedes](#8-supersedes)
9. [InstrumentationContext](#9-instrumentationcontext)
10. [OTelMobileBuilder](#10-otelmobilebuilder)
11. [OTelMobileHandle](#11-otelmobilehandle)
12. [@Incubating](#12-incubating)
13. [Instrumentation Modules](#13-instrumentation-modules)

---

## 1. MobileOtel

`@Incubating object MobileOtel`

Package: `io.opentelemetry.android.mobile`

Main facade for the Mobile OpenTelemetry SDK. Use this when you want programmatic control
(explicit config object or DSL). All instrumentation modules are automatically wired on
initialization: error capture, vitals monitoring, predictive export, and health metrics.

### Properties

```kotlin
var openTelemetryMobile: OpenTelemetryMobile?   // read-only externally; set by DSL initialize()
```

### Initialization

```kotlin
fun initialize(
    context: Context,
    config: MobileConfig,
    customizers: ExporterCustomizers = ExporterCustomizers()
): MobileLoggerProvider
```

Initialize with an explicit `MobileConfig`. Must be called before any other `MobileOtel`
methods, typically from `Application.onCreate()`. Automatically wires:
- `ErrorInstrumentation` — uncaught exceptions, coroutines, RxJava errors → auto flush
- `VitalsCollector` — app start, jank, memory, thermal → OTel metrics
- `PredictiveExportPolicy` — crash/network-loss risk → pre-emptive flush
- `HealthMetricsCollector` — device health → OTel metrics

```kotlin
fun initialize(
    context: Context,
    block: MobileOtelDsl.() -> Unit
): OpenTelemetryMobile
```

Initialize using the Kotlin DSL. Builds `MobileConfig` and `ExporterCustomizers` from the
DSL block, calls `OTelMobileBuilder` to install instrumentation modules, stores the result
in `MobileOtel.openTelemetryMobile`, and returns it.

### Provider Access

```kotlin
fun getProvider(): MobileLoggerProvider
```

Returns the active `MobileLoggerProvider`. Throws `IllegalStateException` if called before
`initialize()`.

### Session Management

```kotlin
fun identify(user: UserIdentity)
```
Attach a user identity to all subsequent telemetry. User ID is stored on `SessionManager`.

```kotlin
fun clearIdentity()
```
Remove user identity — future telemetry is anonymous.

```kotlin
fun terminateSession(reason: String = "manual")
```
End the current session. A new session starts on the next app use.

```kotlin
fun setSessionEnabled(enabled: Boolean)
```
Enable or disable session tracking at runtime.

### Global Attributes

```kotlin
fun addGlobalAttribute(key: String, value: Any)
```
Attach `key`/`value` to every telemetry record. Accepted value types: `String`, `Long`,
`Int`, `Double`, `Float`, `Boolean`. Other types are coerced via `toString()`.

```kotlin
fun removeGlobalAttribute(key: String)
```
Remove a previously added global attribute.

```kotlin
fun clearGlobalAttributes()
```
Remove all global attributes.

### Custom Events

```kotlin
fun sendEvent(
    name: String,
    attributes: Map<String, Any> = emptyMap(),
    severity: Severity = Severity.INFO
)
```
Emit a custom event through the OTel pipeline. Events enter the ring buffer and are subject
to the same policy-based export as auto-captured events. `name` becomes the log record body.

### Error Reporting

```kotlin
fun reportError(throwable: Throwable, context: Map<String, String> = emptyMap())
```
Manually report an exception. Routes through `ErrorInstrumentation` (deduplication,
rate limiting, stack trace scrubbing, breadcrumb attachment) when initialized; falls back
to a direct log record otherwise.

```kotlin
fun getCoroutineExceptionHandler(): kotlinx.coroutines.CoroutineExceptionHandler?
```
Returns a `CoroutineExceptionHandler` that captures exceptions through `ErrorInstrumentation`,
or `null` if error instrumentation is not initialized.

Usage:
```kotlin
val scope = CoroutineScope(Dispatchers.IO + MobileOtel.getCoroutineExceptionHandler()!!)
```

### Flush Control

```kotlin
fun forceFlush(
    windowMinutes: Int? = null,
    timeoutSeconds: Long = 30
): CompletableResultCode
```
Flush buffered telemetry to the collector. Pass `windowMinutes` for a selective flush
(last N minutes only); omit for a full flush of all buffered events.

### Predictive Intelligence

```kotlin
fun getCurrentPrediction(): DeviceHealthPrediction?
```
Current device health prediction with risk scores (0.0–1.0) for crash, network loss,
performance degradation, and battery drain. Returns `null` if the predictive policy
is not initialized.

### Statistics

```kotlin
fun getErrorStatistics(): ErrorStatistics?
```
Returns unique error counts and rate-limit status. `null` if error instrumentation is off.

```kotlin
fun getBufferStats(): BufferStats?
```
Returns RAM and disk buffer usage. `null` before `initialize()`.

### Shutdown

```kotlin
fun shutdown()
```
Flush all pending telemetry, shut down all instrumentation modules, and release all
resources. After this call `initialize()` may be called again.

---

## 2. OpenTelemetryMobile

`@Incubating class OpenTelemetryMobile`

Package: `io.opentelemetry.android.mobile`

Return type of the DSL `MobileOtel.initialize { }` overload. Provides direct access to the
underlying OTel SDK and flush control. Constructed internally — do not instantiate directly.

### Constructor (internal)

```kotlin
internal constructor(
    val openTelemetry: OpenTelemetry,
    private val handle: OTelMobileHandle,
    private val sessionProvider: MobileSessionProvider,
    private val loggerProvider: MobileLoggerProvider
)
```

### Properties

```kotlin
val openTelemetry: OpenTelemetry
val sessionId: String              // current session ID; delegates to MobileSessionProvider
```

### Signal Providers

```kotlin
fun getTracer(scope: String): Tracer
fun getLogger(scope: String): Logger
fun getMeter(scope: String): Meter
```

### Flush and Lifecycle

```kotlin
fun forceFlush(timeoutSeconds: Long = 30): CompletableResultCode
```
Flush all buffered telemetry. Delegates to `MobileLoggerProvider.forceFlush()`.

```kotlin
fun flushWindow(minutes: Int): CompletableResultCode
```
Selective flush: export only events captured in the last `minutes` minutes. Delegates
to `MobileLoggerProvider.getMobileProcessor().flushWindow(minutes)`.

```kotlin
fun shutdown(timeoutSeconds: Long = 30)
```
Stop all instrumentation (delegates to `OTelMobileHandle.stop()`), perform a final flush,
and release resources.

---

## 3. OTelMobile

`@Incubating object OTelMobile`

Package: `io.opentelemetry.android.mobile`

Auto-instrumentation entry point. `OTelMobile.start()` bundles the default set of
instrumentation modules and starts them in a single call. Use `MobileOtel.initialize { }`
when you need DSL-level control; use `OTelMobile.start()` for the simplest integration.

### Initialization

```kotlin
fun start(application: Application, config: MobileConfig)
```
Initialize the SDK and start all auto-instrumentation. **Idempotent** — subsequent calls
while the SDK is running are no-ops. Typically called from `Application.onCreate()`.

Default instrumentation registered by `start()`:
- `LifecycleInstrumentation`
- `ScreenViewInstrumentation`
- `TapInstrumentation`
- `ScrollInstrumentation`
- `TextInputInstrumentation` (with `config.textInputConfig`)
- `BackPressInstrumentation`
- `FreezeInstrumentation`
- `ErrorsInstrumentation`
- `VitalsInstrumentation`
- `ScreenshotInstrumentation` — only if `config.screenshotConfig.enabled == true`
- `WireframeInstrumentation` — only if `config.wireframeConfig.enabled == true`

### Lifecycle

```kotlin
fun stop(timeoutSeconds: Long = 30)
```
Stop all instrumentation and perform a final flush. After this call, `start()` may be
called again to reinitialize.

### Signal Providers

```kotlin
fun getLoggerProvider(): MobileLoggerProvider   // throws IllegalStateException if start() not called
fun getLogger(scope: String): Logger
fun getTracer(scope: String, version: String? = null): Tracer
fun getMeter(scope: String): Meter
```

### Recovery Tracking

```kotlin
fun getLastRecoveryType(): String?
```
Returns the recovery type detected at the previous app start: `"crash"`, `"anr"`,
`"low_memory"`, or `null` for a normal start.

```kotlin
fun markCrashForNextStart()
fun markLowMemoryForNextStart()
fun markAnrForNextStart()
```
Persist a flag so the next app start can emit the appropriate recovery event via
`RecoveryTracker`.

### Journey Spans

```kotlin
@Incubating
fun startJourney(name: String): Span
```
Start a journey span that becomes the parent for all subsequent page and interaction spans.
Make the returned span current on the main thread with `Span.makeCurrent()` so that
`ScreenViewInstrumentation` nests page spans under it automatically. Caller is responsible
for calling `Span.end()`.

```kotlin
// Example
InstrumentationRegistry.getInstrumentation().runOnMainSync {
    val span = OTelMobile.startJourney("checkout")
    val scope = span.makeCurrent()
    // ... navigate through screens ...
    scope.close()
    span.end()
}
```

### Builder Access

```kotlin
fun builder(application: Application, openTelemetry: OpenTelemetry): OTelMobileBuilder
```
Returns a new `OTelMobileBuilder` for fine-grained instrumentation selection. Use this
instead of `start()` when you want explicit control over which modules are active.

### Deprecated

```kotlin
fun restartPageSpan(screenName: String)
```
No-op kept for API compatibility. Page spans are managed by `ScreenViewInstrumentation`.
Will be removed in a future cleanup pass.

---

## 4. MobileConfig

`@Incubating data class MobileConfig`

Package: `io.opentelemetry.android.mobile.config`

All SDK behaviour is controlled by `MobileConfig`. It can be constructed directly
(data class syntax), through the `MobileConfig.builder()` fluent API, or via the Kotlin DSL.

### Constructor

```kotlin
data class MobileConfig(
    // ── Required ──────────────────────────────────────────────────────────────
    val serviceName: String,
    val serviceVersion: String,
    val collectorEndpoint: String,              // must start with https:// in production

    // ── Export ────────────────────────────────────────────────────────────────
    val exportMode: ExportMode = ExportMode.CONDITIONAL,
    val uiTelemetryMode: UiTelemetryMode = UiTelemetryMode.EVENTS,
    val traceExportIntervalSeconds: Long = 30,
    val metricExportIntervalSeconds: Long = 60,
    val predictionIntervalSeconds: Long = 30,
    val exportTimeoutSeconds: Long = 30,
    val configPollIntervalSeconds: Long = 300,
    val maxExportRetries: Int = 3,              // range 0–10
    val headers: Map<String, String>? = null,
    val attachContextAttributes: Boolean = false,
    val buildChannel: String? = null,

    // ── Buffer ────────────────────────────────────────────────────────────────
    val ramBufferSize: Int = 5000,              // range 1–100,000
    val diskBufferMb: Int = 50,                 // range 1–500
    val diskBufferTtlHours: Int = 24,           // range 1–168

    // ── Sub-configs ───────────────────────────────────────────────────────────
    val textInputConfig: TextInputConfig = TextInputConfig(),
    val samplingConfig: SamplingConfig = SamplingConfig.dynamic(normalRate = 0.1, highPriorityRate = 1.0),
    val deviceMetricsConfig: DeviceMetricsConfig = DeviceMetricsConfig.default(),
    val sessionConfig: SessionConfig = SessionConfig(),
    val breadcrumbConfig: BreadcrumbConfig = BreadcrumbConfig.default(),
    val vitalsConfig: VitalsConfig = VitalsConfig.default(),
    val networkConfig: NetworkConfig = NetworkConfig.default(),
    val errorConfig: ErrorConfig = ErrorConfig.default(),

    // ── Incubating ────────────────────────────────────────────────────────────
    @Incubating val screenshotConfig: ScreenshotConfig = ScreenshotConfig(enabled = false),
    @Incubating val wireframeConfig: WireframeConfig = WireframeConfig(enabled = false)
)
```

**Validation rules** (enforced in `init`):
- `serviceName`, `serviceVersion`, `collectorEndpoint` must not be blank
- `traceExportIntervalSeconds`, `metricExportIntervalSeconds`, `predictionIntervalSeconds`, `exportTimeoutSeconds`, `configPollIntervalSeconds` must be positive
- `ramBufferSize` in `1..100_000`
- `diskBufferMb` in `1..500`
- `diskBufferTtlHours` in `1..168`
- `maxExportRetries` in `0..10`
- Non-HTTPS endpoint logs a warning unless the host is `localhost`, `127.0.0.1`, or `10.0.2.2`

### Enums

#### ExportMode

| Value | Behaviour |
|-------|-----------|
| `CONDITIONAL` | Flush only when a policy trigger fires (most battery-efficient) |
| `CONTINUOUS` | Flush on a fixed schedule regardless of conditions |
| `HYBRID` | Regular lightweight exports plus conditional full dumps; prediction cycle driven by heartbeat |

#### UiTelemetryMode

| Value | Behaviour |
|-------|-----------|
| `EVENTS` | Emit UI interactions as OTel log records (default) |
| `SPANS` | Emit UI interactions as zero-duration child spans under the active page span |
| `BOTH` | Emit as both log records and child spans |

### Companion

```kotlin
companion object {
    fun builder(): Builder
}
```

### MobileConfig.Builder

`MobileConfig.builder()` returns a `MobileConfig.Builder` with the following fluent setters.
Each setter returns `Builder` for chaining; `build()` validates required fields.

```kotlin
fun setServiceName(serviceName: String): Builder
fun setServiceVersion(serviceVersion: String): Builder
fun setCollectorEndpoint(collectorEndpoint: String): Builder
fun setExportMode(exportMode: ExportMode): Builder
fun setUiTelemetryMode(mode: UiTelemetryMode): Builder
fun setTextInputConfig(config: TextInputConfig): Builder
fun setTraceExportIntervalSeconds(interval: Long): Builder
fun setMetricExportIntervalSeconds(interval: Long): Builder
fun setPredictionIntervalSeconds(seconds: Long): Builder
fun setRamBufferSize(ramBufferSize: Int): Builder
fun setDiskBufferMb(diskBufferMb: Int): Builder
fun setDiskBufferTtlHours(diskBufferTtlHours: Int): Builder
fun setExportTimeoutSeconds(exportTimeoutSeconds: Long): Builder
fun setConfigPollIntervalSeconds(configPollIntervalSeconds: Long): Builder
fun setMaxExportRetries(maxExportRetries: Int): Builder
fun setHeaders(headers: Map<String, String>): Builder
fun setAttachContextAttributes(enabled: Boolean): Builder
fun setBuildChannel(channel: String): Builder
fun setSamplingConfig(config: SamplingConfig): Builder
fun setDeviceMetricsConfig(config: DeviceMetricsConfig): Builder
fun setSessionConfig(config: SessionConfig): Builder
fun setBreadcrumbConfig(config: BreadcrumbConfig): Builder
fun setVitalsConfig(config: VitalsConfig): Builder
fun setNetworkConfig(config: NetworkConfig): Builder
fun setErrorConfig(config: ErrorConfig): Builder
fun setScreenshotConfig(config: ScreenshotConfig): Builder
fun setWireframeConfig(config: WireframeConfig): Builder

// Exporter customizer chains
fun addLogExporterCustomizer(customizer: (LogRecordExporter) -> LogRecordExporter): Builder
fun addSpanExporterCustomizer(customizer: (SpanExporter) -> SpanExporter): Builder
fun addMetricExporterCustomizer(customizer: (MetricExporter) -> MetricExporter): Builder

fun build(): MobileConfig
fun buildWithCustomizers(): Pair<MobileConfig, ExporterCustomizers>
```

Builder example:
```kotlin
val config = MobileConfig.builder()
    .setServiceName("my-app")
    .setServiceVersion("2.1.0")
    .setCollectorEndpoint("https://collector.example.com:4317")
    .setExportMode(ExportMode.HYBRID)
    .setRamBufferSize(8000)
    .setHeaders(mapOf("Authorization" to "Bearer $token"))
    .build()
```

---

## 5. Kotlin DSL

The DSL provides a structured, type-safe alternative to constructing `MobileConfig` directly.
It mirrors the upstream `OpenTelemetryRumInitializer.initialize(context) { }` pattern.

```kotlin
val mobile: OpenTelemetryMobile = MobileOtel.initialize(context) {
    service {
        name = "my-app"                                        // required
        version = "1.0.0"                                      // required
    }
    export {
        endpoint = "https://collector.example.com:4317"        // required
        mode = ExportMode.HYBRID
        headers = mapOf("Authorization" to "Bearer <token>")
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
        renewalMinutes = 30L
    }
    exportCustomizers {
        // addLog / addSpan / addMetric
    }
    instrumentations {
        discoverAll()           // SPI discover MobileInstrumentation + AndroidInstrumentation
        // or:
        discoverOwn()           // SPI discover MobileInstrumentation only
        // or:
        add(TapInstrumentation())   // explicit addition
    }
    uiTelemetryMode = UiTelemetryMode.SPANS
}
```

### DSL Classes

All DSL classes are annotated `@MobileOtelDslMarker` to prevent scope leakage.

| Class | Package | Top-level block |
|-------|---------|-----------------|
| `MobileOtelDsl` | `config` | root lambda |
| `ServiceDsl` | `config` | `service { }` |
| `ExportDsl` | `config` | `export { }` |
| `BufferingDsl` | `config` | `buffering { }` |
| `SessionDsl` | `config` | `session { }` |
| `ExporterCustomizersDsl` | `config` | `exportCustomizers { }` |
| `InstrumentationsDsl` | `config` | `instrumentations { }` |

### MobileOtelDsl Top-Level Properties

```kotlin
var uiTelemetryMode: UiTelemetryMode = UiTelemetryMode.EVENTS
```

Top-level DSL functions:
```kotlin
fun service(block: ServiceDsl.() -> Unit)
fun export(block: ExportDsl.() -> Unit)
fun buffering(block: BufferingDsl.() -> Unit)
fun session(block: SessionDsl.() -> Unit)
fun exportCustomizers(block: ExporterCustomizersDsl.() -> Unit)
fun instrumentations(block: InstrumentationsDsl.() -> Unit)
```

---

## 6. ExporterCustomizers

`class ExporterCustomizers`

Package: `io.opentelemetry.android.mobile.config`

Holds ordered chains of customizer functions applied to each exporter before it is wired
into the OTel SDK. Used to add headers, wrap exporters, or inject test doubles without
subclassing.

### Constructor

```kotlin
ExporterCustomizers(
    val log: List<(LogRecordExporter) -> LogRecordExporter> = emptyList(),
    val span: List<(SpanExporter) -> SpanExporter> = emptyList(),
    val metric: List<(MetricExporter) -> MetricExporter> = emptyList()
)
```

### Companion

```kotlin
companion object {
    val EMPTY: ExporterCustomizers     // no-op instance with all empty lists
}
```

### Builder

```kotlin
class Builder {
    fun addLog(customizer: (LogRecordExporter) -> LogRecordExporter): Builder
    fun addSpan(customizer: (SpanExporter) -> SpanExporter): Builder
    fun addMetric(customizer: (MetricExporter) -> MetricExporter): Builder
    fun build(): ExporterCustomizers
}
```

---

## 7. MobileInstrumentation

`@Incubating interface MobileInstrumentation`

Package: `io.opentelemetry.android.mobile.instrumentation`

Contract for a single unit of mobile auto-instrumentation. Each capability ships as a
separate Gradle module and is discoverable via the Java `ServiceLoader` SPI
(`META-INF/services/io.opentelemetry.android.mobile.instrumentation.MobileInstrumentation`).

```kotlin
interface MobileInstrumentation {
    /** Unique name, e.g. "io.opentelemetry.android.mobile.tap". */
    val instrumentationName: String

    /** Version of this instrumentation. Default: "1.0.0". */
    val instrumentationVersion: String get() = "1.0.0"

    /**
     * Called by InstrumentationRegistry to activate this instrumentation.
     * Register callbacks/observers here. Retain only weak references to [application].
     */
    fun install(application: Application, context: InstrumentationContext)

    /**
     * Called by InstrumentationRegistry to deactivate and clean up.
     * Must unregister all callbacks registered in install().
     */
    fun uninstall() {}
}
```

To create a custom instrumentation:
1. Implement `MobileInstrumentation`.
2. Register via `OTelMobileBuilder.addInstrumentation(myInstrumentation)`, or publish via
   SPI for automatic discovery by `discoverInstrumentations()`.

---

## 8. @Supersedes

`annotation class Supersedes(vararg val names: String)`

Package: `io.opentelemetry.android.mobile.instrumentation`

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Supersedes(vararg val names: String)
```

Declares that a `MobileInstrumentation` supersedes one or more upstream
`AndroidInstrumentation` modules. When both the superseding module and the upstream module
are discovered via `OTelMobileBuilder.discoverAllInstrumentations()`, the
`InstrumentationRegistry` installs the superseding module and skips the upstream one.

`names` must match the upstream module's `AndroidInstrumentation.name` exactly.

Example:
```kotlin
@Supersedes("io.opentelemetry.android.tap")
class TapInstrumentation : MobileInstrumentation { ... }
```

---

## 9. InstrumentationContext

`@Incubating class InstrumentationContext`

Package: `io.opentelemetry.android.mobile.instrumentation`

Carries all shared state passed to every `MobileInstrumentation` at install time.
Implementations must not hold strong references to mutable state beyond their
`uninstall()` call.

### Constructor

```kotlin
class InstrumentationContext(
    val openTelemetry: OpenTelemetry,
    val sessionProvider: MobileSessionProvider,
    val windowEventHub: WindowEventHub,
    val application: Application,
    val uiTelemetryMode: UiTelemetryMode = UiTelemetryMode.EVENTS,
    val breadcrumbManager: BreadcrumbManager? = ...,   // null if BreadcrumbManager not initialized
    val clock: Clock? = null
)
```

### Convenience Methods

```kotlin
fun addBreadcrumb(breadcrumb: JourneyBreadcrumb)
// Safe no-op when breadcrumbManager is null.

fun tracer(scope: String): Tracer
// Equivalent to openTelemetry.getTracer(scope).

fun logger(scope: String): Logger
// Equivalent to openTelemetry.logsBridge.get(scope).

fun meter(scope: String): Meter
// Equivalent to openTelemetry.getMeter(scope).
```

---

## 10. OTelMobileBuilder

`@Incubating class OTelMobileBuilder`

Package: `io.opentelemetry.android.mobile.instrumentation`

Fluent builder for the Mobile OTel SDK. Accepts an `OpenTelemetry` instance (already
configured with exporters and resources), builds the `InstrumentationRegistry`, installs all
instrumentations, and returns an `OTelMobileHandle`.

### Constructor

```kotlin
OTelMobileBuilder(
    application: Application,
    openTelemetry: OpenTelemetry
)
```

### Builder Methods

```kotlin
fun setSessionProvider(provider: MobileSessionProvider): OTelMobileBuilder
// Replace the default DefaultMobileSessionProvider.

fun setUiTelemetryMode(mode: UiTelemetryMode): OTelMobileBuilder
// Controls whether interactions emit log events, spans, or both.

fun addInstrumentation(instrumentation: MobileInstrumentation): OTelMobileBuilder
// Register a specific instrumentation to be installed on build().

fun discoverInstrumentations(): OTelMobileBuilder
// SPI-discover all MobileInstrumentation implementations on the classpath.

fun discoverUpstreamInstrumentations(): OTelMobileBuilder
// SPI-discover all AndroidInstrumentation, wrapping each in UpstreamInstrumentationAdapter.

fun discoverAllInstrumentations(): OTelMobileBuilder
// Discover both MobileInstrumentation and upstream AndroidInstrumentation.
// Upstream modules whose class names collide with already-discovered mobile module names
// are skipped. @Supersedes conflict resolution happens in InstrumentationRegistry.install().

fun build(): OTelMobileHandle
// Creates InstrumentationRegistry, installs all instrumentations, wires WindowEventHub
// via WindowEventHubInstaller, and returns the active handle.
```

Usage — explicit instrumentation selection:
```kotlin
val handle = OTelMobileBuilder(app, openTelemetry)
    .addInstrumentation(LifecycleInstrumentation())
    .addInstrumentation(TapInstrumentation())
    .build()
```

Usage — SPI discovery:
```kotlin
val handle = OTelMobileBuilder(app, openTelemetry)
    .discoverInstrumentations()
    .build()
```

---

## 11. OTelMobileHandle

`@Incubating class OTelMobileHandle`

Package: `io.opentelemetry.android.mobile.instrumentation`

Live handle to the running Mobile OTel SDK instance. Returned by `OTelMobileBuilder.build()`.
Owns the `InstrumentationRegistry` and the `OpenTelemetry` SDK instance.

### Constructor (internal)

```kotlin
internal constructor(
    internal val openTelemetry: OpenTelemetry,
    private val registry: InstrumentationRegistry,
    private val hubInstaller: WindowEventHubInstaller? = null
)
```

### Properties

```kotlin
val sessionProvider: MobileSessionProvider?    // delegates to registry.sessionProvider
```

### Methods

```kotlin
fun getTracer(scope: String): Tracer
fun getLogger(scope: String): Logger
fun getMeter(scope: String): Meter

fun stop(timeoutSeconds: Long = 30)
// Uninstalls WindowEventHubInstaller, calls registry.uninstall(), then shuts down the
// OpenTelemetrySdk (flush + shutdown with timeout). After this call the handle must not be used.
```

---

## 12. @Incubating

```kotlin
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "This API is incubating and may change in future releases without notice."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class Incubating
```

Package: `io.opentelemetry.android.mobile.instrumentation`

Marks an API as experimental. It may change or be removed in future releases without a
deprecation cycle. Following OpenTelemetry contrib convention for experimental APIs.

Opt in globally with `@OptIn(Incubating::class)` in your `Application` class, or suppress
per call-site.

---

## 13. Instrumentation Modules

Each module lives under `instrumentation/<dir>/` and publishes a `MobileInstrumentation`
implementation. Modules marked `@Incubating` are opt-in and disabled by default in
`MobileConfig`.

| Module dir | Class | `instrumentationName` | Signal | Key config class |
|------------|-------|-----------------------|--------|-----------------|
| `back-press` | `BackPressInstrumentation` | `io.opentelemetry.android.mobile.back_press` | Logs / Spans | — |
| `compose-click` | `ComposeClickInstrumentation` | `io.opentelemetry.android.mobile.compose.click` | Logs / Spans | `ComposeClickConfig` |
| `database` | `DatabaseInstrumentation` | `io.opentelemetry.android.mobile.database` | Spans | — |
| `errors` | `ErrorsInstrumentation` | `io.opentelemetry.android.mobile.errors` | Logs | `ErrorConfig` |
| `file-io` | `FileIOInstrumentation` | `io.opentelemetry.android.mobile.file-io` | Spans | — |
| `freeze` | `FreezeInstrumentation` | `io.opentelemetry.android.mobile.freeze` | Logs | `FreezeConfig` |
| `lifecycle` | `LifecycleInstrumentation` | `io.opentelemetry.android.mobile.lifecycle` | Logs | — |
| `network` | `NetworkInstrumentation` | `io.opentelemetry.android.mobile.network` | Logs / Spans | `NetworkConfig` |
| `screen` | `ScreenViewInstrumentation` | `io.opentelemetry.android.mobile.screen` | Logs + Spans | — |
| `screen-orientation` | `ScreenOrientationInstrumentation` | `io.opentelemetry.android.mobile.screen-orientation` | Logs | — |
| `screenshot` `@Incubating` | `ScreenshotInstrumentation` | `io.opentelemetry.android.mobile.screenshot` | Logs | `ScreenshotConfig` |
| `scroll` | `ScrollInstrumentation` | `io.opentelemetry.android.mobile.scroll` | Logs / Spans | — |
| `system-events` | `SystemEventsInstrumentation` | `io.opentelemetry.android.mobile.system-events` | Logs | — |
| `tap` | `TapInstrumentation` | `io.opentelemetry.android.mobile.tap` | Logs / Spans | `TapConfig` |
| `text-input` | `TextInputInstrumentation` | `io.opentelemetry.android.mobile.text_input` | Logs / Spans | `TextInputConfig` |
| `timber` | `TimberInstrumentation` | `io.opentelemetry.android.mobile.timber` | Logs | — |
| `vitals` | `VitalsInstrumentation` | `io.opentelemetry.android.mobile.vitals` | Metrics | `VitalsConfig` |
| `wireframe` `@Incubating` | `WireframeInstrumentation` | `io.opentelemetry.android.mobile.wireframe` | Logs | `WireframeConfig` |

### Module Config Summaries

#### TapConfig

Package: `io.opentelemetry.android.mobile.instrumentation`

```kotlin
@Incubating
data class TapConfig(
    val captureTaps: Boolean = true,
    val captureLongPress: Boolean = true,
    val captureSwipe: Boolean = true,
    val swipeMinDistancePx: Float = 50f,          // active
    // Reserved for future implementation (no effect currently):
    val coalesceWindowMs: Long = 800,
    val bucketGridSize: Int = 3,
    val maxHitTestDepth: Int = 12,
    val privacyMode: PrivacyMode = PrivacyMode.STRICT,
    val hashSalt: String? = null,
    val allowlistedResourceIds: Set<String> = emptySet(),
    val denylistedResourceIds: Set<String> = emptySet(),
    val allowlistedViewClasses: Set<String> = emptySet(),
    val denylistedViewClasses: Set<String> = emptySet()
)
```

Output mode (events vs. spans) is controlled globally by `MobileConfig.uiTelemetryMode`.

#### TextInputConfig

Package: `io.opentelemetry.android.mobile.instrumentation`

```kotlin
@Incubating
data class TextInputConfig(
    val captureCharCount: Boolean = true,         // length of text on focus-leave (safe)
    val captureIsSet: Boolean = true,             // true if field was non-empty on focus-leave
    val captureTextContent: Boolean = false,      // opt-in: raw text (PII risk)
    val textContentAllowlist: Set<String> = emptySet()  // resource IDs allowed for raw text
)
```

Raw text is **never** captured unless `captureTextContent = true` AND the field's resource ID
is in `textContentAllowlist`.

#### ScreenshotConfig

Package: `io.opentelemetry.android.mobile.instrumentation`

```kotlin
@Incubating
data class ScreenshotConfig(
    val enabled: Boolean = true,                  // NOTE: MobileConfig passes enabled=false by default
    val maxWidthPx: Int = 480,                    // range 1–4096
    val maxHeightPx: Int = 960,                   // range 1–4096
    val quality: Int = 50,                        // range 0–100; JPEG only
    val format: ScreenshotFormat = ScreenshotFormat.JPEG,
    val maxPayloadKb: Int = 200,                  // range 1–4096; captures exceeding limit are dropped
    val redactTextViews: Boolean = true,          // overlay rectangles over TextView bounds
    val captureOnError: Boolean = true,           // auto-capture on uncaught exception
    val captureOnScreenView: Boolean = false,     // auto-capture on activity resume
    val screenViewDelayMs: Long = 500,            // range 0–5000; delay after resume before capture
    val maxCapturesPerMinute: Int = 5             // range 1–60; rate limit
)
```

Screenshot data is emitted as a data URL in the `mobile.screenshot.data_url` log attribute.
`MobileConfig` initializes this with `ScreenshotConfig(enabled = false)` — set
`config.screenshotConfig.enabled = true` to activate.

#### WireframeConfig

Package: `io.opentelemetry.android.mobile.instrumentation`

```kotlin
@Incubating
data class WireframeConfig(
    val enabled: Boolean = true,                  // NOTE: MobileConfig passes enabled=false by default
    val captureOnScreenView: Boolean = true,      // capture on every screen transition
    val captureOnTap: Boolean = false,            // capture after each tap event
    val captureOnError: Boolean = true,           // capture on uncaught exception
    val maxDepth: Int = 30,                       // range 1–100; deeper trees are truncated
    val includeResourceIds: Boolean = true,       // include Android resource IDs
    val includeTextHints: Boolean = false,        // include placeholder/hint text
    val includeContentDescription: Boolean = false, // include accessibility contentDescription
    val includeClickableState: Boolean = true,    // include clickable/enabled flags
    val maxCapturesPerMinute: Int = 30            // range 1–120; rate limit
)
```

Wireframes are compact JSON view-hierarchy trees (~1–5 KB per frame) emitted as log records.
`MobileConfig` initializes this with `WireframeConfig(enabled = false)`.

#### NetworkConfig

Package: `io.opentelemetry.android.mobile.network`

```kotlin
@Incubating
data class NetworkConfig(
    val enabled: Boolean = true,
    val propagateTraceContext: Boolean = true,    // inject W3C trace context headers
    val captureRequestHeaders: List<String> = emptyList(),
    val captureResponseHeaders: List<String> = listOf("Content-Type"),
    val captureRequestBody: Boolean = false,      // privacy risk — disabled by default
    val captureResponseBody: Boolean = false,     // privacy risk — disabled by default
    val maxBodyCaptureBytes: Int = 1024,
    val scrubUrls: Boolean = true,                // remove query params and sensitive segments
    val scrubHeaders: Boolean = true,             // remove Authorization and similar headers
    val detectNetworkType: Boolean = true,        // report WiFi/Cellular/etc.
    val reportNetworkSpeed: Boolean = false,
    val bucketSizes: Boolean = true,              // group request/response sizes into buckets
    val minDurationMs: Long = 0,                  // only trace requests slower than this
    val errorStatusThreshold: Int = 400,          // range 100–599
    val allowedHosts: List<String> = emptyList(), // empty = all hosts
    val blockedHosts: List<String> = emptyList(),
    val propagationHosts: List<String> = emptyList() // empty = propagate to all instrumented hosts
)
```

Factory methods:
```kotlin
NetworkConfig.default()      // privacy-first defaults (above)
NetworkConfig.minimal()      // basic tracing only
NetworkConfig.debug()        // capture headers + bodies (not for production)
NetworkConfig.production()   // balanced; minDurationMs=100, no body capture
```

`NetworkInstrumentation` creates an `OTelNetworkInterceptor` during `install()`. Since
OkHttp interceptors must be manually added by the application, wire it via
`NetworkInstrumentation.getInterceptor()`:

```kotlin
val networkInstr = NetworkInstrumentation(networkConfig)
// register with OTelMobileBuilder, then:
val client = OkHttpClient.Builder()
    .addInterceptor(networkInstr.getInterceptor())
    .build()
```
