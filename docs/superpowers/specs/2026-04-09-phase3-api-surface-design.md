# Phase 3: API Surface Parity — Design Specification

**Date:** 2026-04-09
**Status:** Approved
**Scope:** Kotlin DSL configuration, exporter customizer chain, `OpenTelemetryMobile` return type — unified entry point matching upstream's API pattern.
**Parent Epic:** `docs/epics/UPSTREAM_SUPERSESSION_EPIC.md` (Phase 3, US-022 through US-025)

---

## 1. Unified Entry Point: `MobileOtel.initialize(context) { }`

### The API

```kotlin
// New primary entry point (matches upstream's OpenTelemetryRumInitializer.initialize pattern)
val otel: OpenTelemetryMobile = MobileOtel.initialize(context) {
    service {
        name = "my-app"
        version = "1.0.0"
    }
    export {
        endpoint = "https://collector.dash0.com:4317"
        mode = ExportMode.CONDITIONAL
        headers = mapOf("Authorization" to "Bearer token")
        timeoutSeconds = 30
        maxRetries = 3
    }
    buffering {
        ramSize = 5000
        diskMb = 50
        ttlHours = 24
    }
    session {
        renewalMinutes = 30
    }
    exportCustomizers {
        log { exporter -> RedactingExporter(exporter) }
        span { exporter -> FilteringExporter(exporter) }
        metric { exporter -> SamplingExporter(exporter) }
    }
    instrumentations {
        discoverAll()
        // Or explicit:
        // add(TapInstrumentation(TapConfig(swipeMinDistancePx = 50f)))
        // screenshot { enabled = true; quality = 0.7f }
        // wireframe { enabled = true }
    }
}

// Existing entry point continues to work (non-breaking)
val config = MobileConfig(serviceName = "my-app", ...)
MobileOtel.initialize(context, config)
```

### DSL Overload on MobileOtel

```kotlin
// In MobileOtel.kt — new overload
fun initialize(
    context: Context,
    block: MobileOtelDsl.() -> Unit
): OpenTelemetryMobile {
    val dsl = MobileOtelDsl().apply(block)
    val config = dsl.buildConfig()
    val customizers = dsl.buildCustomizers()

    // 1. Initialize core SDK (existing path)
    val loggerProvider = initialize(context, config, customizers)

    // 2. Build instrumentation registry (OTelMobileBuilder path)
    val app = context.applicationContext as Application
    val builder = OTelMobileBuilder(app, loggerProvider.getOpenTelemetrySdk())
        .setUiTelemetryMode(config.uiTelemetryMode)
    dsl.applyInstrumentationsTo(builder)
    val handle = builder.build()

    // 3. Create and store the return type
    val mobile = OpenTelemetryMobile(
        openTelemetry = loggerProvider.getOpenTelemetrySdk(),
        handle = handle,
        sessionProvider = handle.sessionProvider
            ?: error("sessionProvider not set — OTelMobileBuilder.build() must call registry.install() first"),
        loggerProvider = loggerProvider
    )
    openTelemetryMobile = mobile
    return mobile
}
```

**Note:** `MobileLoggerProvider` is a singleton (SR-003 in backlog). If `initialize()` is called twice, the second call reuses the existing singleton and customizers from the second call are ignored. This is a pre-existing limitation — the DSL does not make it worse but does not fix it either. SR-003 tracks the fix.

The existing `initialize(context, config)` also gets an optional `customizers` parameter:

```kotlin
// Existing overload — add optional customizers, non-breaking
fun initialize(
    context: Context,
    config: MobileConfig,
    customizers: ExporterCustomizers = ExporterCustomizers()
): MobileLoggerProvider
```

---

## 2. DSL Classes

### MobileOtelDsl (top-level)

```kotlin
@MobileOtelDslMarker
class MobileOtelDsl {
    private val serviceConfig = ServiceDsl()
    private val exportConfig = ExportDsl()
    private val bufferingConfig = BufferingDsl()
    private val sessionConfig = SessionDsl()
    private val customizerConfig = ExporterCustomizersDsl()
    private val instrumentationsConfig = InstrumentationsDsl()

    // Top-level settings exposed directly
    var uiTelemetryMode: UiTelemetryMode = UiTelemetryMode.EVENTS
    private var screenshotConfig = ScreenshotConfig(enabled = false)
    private var wireframeConfig = WireframeConfig(enabled = false)
    private var networkConfig = NetworkConfig.default()
    private var errorConfig = ErrorConfig.default()
    private var vitalsConfig = VitalsConfig.default()
    private var breadcrumbConfig = BreadcrumbConfig.default()

    fun service(block: ServiceDsl.() -> Unit) { serviceConfig.apply(block) }
    fun export(block: ExportDsl.() -> Unit) { exportConfig.apply(block) }
    fun buffering(block: BufferingDsl.() -> Unit) { bufferingConfig.apply(block) }
    fun session(block: SessionDsl.() -> Unit) { sessionConfig.apply(block) }
    fun exportCustomizers(block: ExporterCustomizersDsl.() -> Unit) { customizerConfig.apply(block) }
    fun instrumentations(block: InstrumentationsDsl.() -> Unit) { instrumentationsConfig.apply(block) }

    internal fun buildConfig(): MobileConfig = MobileConfig(
        serviceName = serviceConfig.name ?: error("service { name = ... } is required"),
        serviceVersion = serviceConfig.version ?: error("service { version = ... } is required"),
        collectorEndpoint = exportConfig.endpoint ?: error("export { endpoint = ... } is required"),
        exportMode = exportConfig.mode,
        uiTelemetryMode = uiTelemetryMode,
        traceExportIntervalSeconds = exportConfig.traceIntervalSeconds,
        metricExportIntervalSeconds = exportConfig.metricIntervalSeconds,
        ramBufferSize = bufferingConfig.ramSize,
        diskBufferMb = bufferingConfig.diskMb,
        diskBufferTtlHours = bufferingConfig.ttlHours,
        exportTimeoutSeconds = exportConfig.timeoutSeconds,
        maxExportRetries = exportConfig.maxRetries,
        headers = exportConfig.headers,
        sessionConfig = SessionConfig(renewalMs = sessionConfig.renewalMinutes * 60_000L),
        screenshotConfig = screenshotConfig,
        wireframeConfig = wireframeConfig,
        networkConfig = networkConfig,
        errorConfig = errorConfig,
        vitalsConfig = vitalsConfig,
        breadcrumbConfig = breadcrumbConfig
    )

    internal fun buildCustomizers(): ExporterCustomizers = customizerConfig.build()

    internal fun applyInstrumentationsTo(builder: OTelMobileBuilder) {
        instrumentationsConfig.applyTo(builder)
    }
}
```

### Sub-DSL Classes

```kotlin
@MobileOtelDslMarker
class ServiceDsl {
    var name: String? = null
    var version: String? = null
}

@MobileOtelDslMarker
class ExportDsl {
    var endpoint: String? = null
    var mode: ExportMode = ExportMode.CONDITIONAL
    var headers: Map<String, String>? = null
    var timeoutSeconds: Long = 30
    var maxRetries: Int = 3
    var traceIntervalSeconds: Long = 30
    var metricIntervalSeconds: Long = 60
}

@MobileOtelDslMarker
class BufferingDsl {
    var ramSize: Int = 5000
    var diskMb: Int = 50
    var ttlHours: Int = 24
}

@MobileOtelDslMarker
class SessionDsl {
    var renewalMinutes: Long = 30
}

@MobileOtelDslMarker
class ExporterCustomizersDsl {
    private val logCustomizers = mutableListOf<(LogRecordExporter) -> LogRecordExporter>()
    private val spanCustomizers = mutableListOf<(SpanExporter) -> SpanExporter>()
    private val metricCustomizers = mutableListOf<(MetricExporter) -> MetricExporter>()

    fun log(customizer: (LogRecordExporter) -> LogRecordExporter) { logCustomizers.add(customizer) }
    fun span(customizer: (SpanExporter) -> SpanExporter) { spanCustomizers.add(customizer) }
    fun metric(customizer: (MetricExporter) -> MetricExporter) { metricCustomizers.add(customizer) }

    internal fun build() = ExporterCustomizers(
        log = logCustomizers.toList(),
        span = spanCustomizers.toList(),
        metric = metricCustomizers.toList()
    )
}

@MobileOtelDslMarker
class InstrumentationsDsl {
    private var discover: DiscoverMode = DiscoverMode.NONE
    private val explicit = mutableListOf<MobileInstrumentation>()

    /** Discover all MobileInstrumentation + AndroidInstrumentation via ServiceLoader. */
    fun discoverAll() { discover = DiscoverMode.ALL }

    /** Discover only MobileInstrumentation (skip upstream). */
    fun discoverOwn() { discover = DiscoverMode.OWN_ONLY }

    /** Add a specific instrumentation instance. */
    fun add(instrumentation: MobileInstrumentation) { explicit.add(instrumentation) }

    internal fun applyTo(builder: OTelMobileBuilder) {
        explicit.forEach { builder.addInstrumentation(it) }
        when (discover) {
            DiscoverMode.ALL -> builder.discoverAllInstrumentations()
            DiscoverMode.OWN_ONLY -> builder.discoverInstrumentations()
            DiscoverMode.NONE -> {} // only explicit additions
        }
    }

    private enum class DiscoverMode { NONE, OWN_ONLY, ALL }
}

@DslMarker
annotation class MobileOtelDslMarker
```

---

## 3. ExporterCustomizers (separate from MobileConfig)

```kotlin
// In config/ package
class ExporterCustomizers(
    val log: List<(LogRecordExporter) -> LogRecordExporter> = emptyList(),
    val span: List<(SpanExporter) -> SpanExporter> = emptyList(),
    val metric: List<(MetricExporter) -> MetricExporter> = emptyList()
) {
    companion object {
        val EMPTY = ExporterCustomizers()
    }
}
```

NOT stored on `MobileConfig` (data class). Passed separately to `MobileOtel.initialize()` and forwarded to `MobileLoggerProvider.getInstance()`.

### Threading customizers through the singleton

`MobileLoggerProvider.getInstance()` must accept customizers:

```kotlin
// Updated singleton factory
fun getInstance(
    context: Context,
    config: MobileConfig,
    customizers: ExporterCustomizers = ExporterCustomizers()
): MobileLoggerProvider {
    return instance ?: synchronized(this) {
        instance ?: MobileLoggerProvider(context.applicationContext, config, customizers)
            .also { instance = it }
    }
}
```

The `MobileLoggerProvider` constructor adds `customizers` as a parameter. These are applied in the `init {}` block where exporters are constructed.

### Application in MobileLoggerProvider init block

After constructing each OTLP exporter, apply customizers:

```kotlin
// Log exporter
var logExporter: LogRecordExporter = OtlpGrpcLogRecordExporter.builder()...build()
for (customizer in customizers.log) {
    logExporter = customizer(logExporter)
}
// Then wrap with EnrichingLogRecordExporter as before

// Span exporter
var spanExporter: SpanExporter = OtlpGrpcSpanExporter.builder()...build()
for (customizer in customizers.span) {
    spanExporter = customizer(spanExporter)
}

// Metric exporter
var metricExporter: MetricExporter = OtlpGrpcMetricExporter.builder()...build()
for (customizer in customizers.metric) {
    metricExporter = customizer(metricExporter)
}
```

Chain order: first registered customizer wraps innermost (closest to OTLP base). This matches upstream's convention.

---

## 4. OpenTelemetryMobile (return type)

```kotlin
// In otel-android-mobile/
class OpenTelemetryMobile internal constructor(
    /** The configured OpenTelemetry SDK instance. */
    val openTelemetry: OpenTelemetry,
    private val handle: OTelMobileHandle,
    private val sessionProvider: MobileSessionProvider,
    private val loggerProvider: MobileLoggerProvider
) {
    /** Current session ID. */
    val sessionId: String get() = sessionProvider.getSessionId()

    /** Convenience — returns a Tracer scoped to [scope]. */
    fun getTracer(scope: String): Tracer = openTelemetry.getTracer(scope)

    /** Convenience — returns a Logger scoped to [scope]. */
    fun getLogger(scope: String): Logger = openTelemetry.logsBridge.get(scope)

    /** Convenience — returns a Meter scoped to [scope]. */
    fun getMeter(scope: String): Meter = openTelemetry.getMeter(scope)

    /** Force flush all buffered telemetry. */
    fun forceFlush(timeoutSeconds: Long = 30): CompletableResultCode {
        return loggerProvider.forceFlush(timeoutSeconds)
    }

    /** Force flush a time window of buffered telemetry. */
    fun flushWindow(minutes: Int): CompletableResultCode {
        return loggerProvider.getMobileProcessor().flushWindow(minutes)
    }

    /** Stop all instrumentation and flush pending telemetry. */
    fun shutdown(timeoutSeconds: Long = 30) {
        handle.stop(timeoutSeconds)
    }
}
```

This is NOT a shim or compat layer — it's our **primary public type**. It's what `OpenTelemetryRum` would be if we designed the upstream. When we merge, this interface becomes the standard.

### MobileOtel stores it

```kotlin
object MobileOtel {
    /** The active OpenTelemetryMobile instance, available after initialize(). */
    var openTelemetryMobile: OpenTelemetryMobile? = null
        private set

    // ... existing methods delegate to this when available
}
```

---

## 5. OTelMobileHandle — expose sessionProvider

`OpenTelemetryMobile` needs access to `sessionProvider` from `OTelMobileHandle`, which currently has it as `private`. Add a public getter:

```kotlin
// In OTelMobileHandle.kt — change private to internal
class OTelMobileHandle internal constructor(
    internal val openTelemetry: OpenTelemetry,   // was private
    private val registry: InstrumentationRegistry,
    private val hubInstaller: WindowEventHubInstaller? = null
) {
    internal val sessionProvider: MobileSessionProvider?
        get() = registry.sessionProvider  // need to add sessionProvider to registry
}
```

`InstrumentationRegistry` needs to expose the `sessionProvider` from the `InstrumentationContext` it received during `install()`:

```kotlin
class InstrumentationRegistry(...) {
    var sessionProvider: MobileSessionProvider? = null
        private set

    fun install(application: Application, context: InstrumentationContext) {
        sessionProvider = context.sessionProvider
        // ... existing conflict resolution + install logic
    }
}
```

---

## 6. MobileConfig.Builder — add customizer methods

For users of the existing builder pattern (not the DSL), add convenience methods:

```kotlin
class Builder {
    // ... existing fields
    private val exporterCustomizers = ExporterCustomizers.Builder()

    fun addLogExporterCustomizer(customizer: (LogRecordExporter) -> LogRecordExporter) = apply {
        exporterCustomizers.addLog(customizer)
    }
    fun addSpanExporterCustomizer(customizer: (SpanExporter) -> SpanExporter) = apply {
        exporterCustomizers.addSpan(customizer)
    }
    fun addMetricExporterCustomizer(customizer: (MetricExporter) -> MetricExporter) = apply {
        exporterCustomizers.addMetric(customizer)
    }

    fun build(): MobileConfig { ... }
    fun buildWithCustomizers(): Pair<MobileConfig, ExporterCustomizers> {
        return Pair(build(), exporterCustomizers.build())
    }
}
```

`ExporterCustomizers` gets a nested Builder:

```kotlin
class ExporterCustomizers(...) {
    class Builder {
        private val log = mutableListOf<(LogRecordExporter) -> LogRecordExporter>()
        private val span = mutableListOf<(SpanExporter) -> SpanExporter>()
        private val metric = mutableListOf<(MetricExporter) -> MetricExporter>()

        fun addLog(c: (LogRecordExporter) -> LogRecordExporter) { log.add(c) }
        fun addSpan(c: (SpanExporter) -> SpanExporter) { span.add(c) }
        fun addMetric(c: (MetricExporter) -> MetricExporter) { metric.add(c) }

        fun build() = ExporterCustomizers(log.toList(), span.toList(), metric.toList())
    }
}
```

---

## 7. Testing Strategy

| Component | Test Class | Approach |
|-----------|-----------|----------|
| DSL config building | `MobileOtelDslTest` | Build config via DSL, verify all fields map correctly to `MobileConfig` |
| DSL required fields | `MobileOtelDslTest` | Verify error thrown when `service.name`, `service.version`, `export.endpoint` missing |
| DSL instrumentations | `MobileOtelDslTest` | Verify `discoverAll()` calls correct builder methods |
| ExporterCustomizers | `ExporterCustomizersTest` | Chain 3 customizers, verify wrapping order |
| Customizer application | `MobileLoggerProviderTest` (modify existing) | Verify customizers are applied to OTLP exporters |
| OpenTelemetryMobile | `OpenTelemetryMobileTest` | Verify accessors delegate correctly |
| Builder customizer methods | `MobileConfigTest` (modify existing) | Verify `buildWithCustomizers()` returns both |

---

## 8. Files to Create/Modify

### New Files
| File | Purpose |
|------|---------|
| `otel-android-mobile/src/main/java/.../config/MobileOtelDsl.kt` | Top-level DSL + `@MobileOtelDslMarker` |
| `otel-android-mobile/src/main/java/.../config/ServiceDsl.kt` | Service name/version |
| `otel-android-mobile/src/main/java/.../config/ExportDsl.kt` | Endpoint, mode, headers, timeouts |
| `otel-android-mobile/src/main/java/.../config/BufferingDsl.kt` | RAM/disk buffer config |
| `otel-android-mobile/src/main/java/.../config/SessionDsl.kt` | Session renewal |
| `otel-android-mobile/src/main/java/.../config/ExporterCustomizersDsl.kt` | Customizer registration |
| `otel-android-mobile/src/main/java/.../config/InstrumentationsDsl.kt` | Discovery + explicit add |
| `otel-android-mobile/src/main/java/.../config/ExporterCustomizers.kt` | Customizer container (not on data class) |
| `otel-android-mobile/src/main/java/.../OpenTelemetryMobile.kt` | Primary return type |
| `otel-android-mobile/src/test/java/.../config/MobileOtelDslTest.kt` | DSL tests |
| `otel-android-mobile/src/test/java/.../config/ExporterCustomizersTest.kt` | Customizer chain tests |
| `otel-android-mobile/src/test/java/.../OpenTelemetryMobileTest.kt` | Return type tests |

### Modified Files
| File | Change |
|------|--------|
| `otel-android-mobile/src/main/java/.../MobileOtel.kt` | Add DSL overload of `initialize()`, store `openTelemetryMobile` |
| `otel-android-mobile/src/main/java/.../MobileLoggerProvider.kt` | Accept + apply `ExporterCustomizers` |
| `otel-android-mobile/src/main/java/.../config/MobileConfig.kt` | Add customizer methods to Builder |
| `otel-android-mobile-core/src/main/java/.../InstrumentationRegistry.kt` | Expose `sessionProvider` |
| `otel-android-mobile-core/src/main/java/.../OTelMobileHandle.kt` | Change `openTelemetry` to `internal`, add `sessionProvider` accessor |

---

## 9. What's NOT in Phase 3

- DSL for individual instrumentation module configs (tap { swipeMinDistancePx = 50f }) — Phase 4 polish
- Full `OpenTelemetryRum` interface implementation (requires `core:0.10.0-alpha` dep) — Phase 4
- Java-friendly API wrappers — Java is unsupported per upstream's decision
