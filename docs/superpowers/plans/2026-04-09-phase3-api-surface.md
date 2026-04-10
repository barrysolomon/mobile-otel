# Phase 3: API Surface Parity — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Kotlin DSL entry point (`MobileOtel.initialize(context) { }`), exporter customizer chain, and `OpenTelemetryMobile` return type — matching upstream's API pattern while exposing our richer capabilities.

**Architecture:** `ExporterCustomizers` container passes through `MobileOtel.initialize()` → `MobileLoggerProvider.getInstance()` where customizers wrap the OTLP exporters. DSL classes build `MobileConfig` + `ExporterCustomizers` from a type-safe Kotlin DSL. The DSL overload on `MobileOtel` combines the existing config-init path with `OTelMobileBuilder` instrumentation wiring, returning `OpenTelemetryMobile` as the primary public type.

**Tech Stack:** Kotlin, JUnit 4, MockK, Robolectric, OpenTelemetry SDK Testing, Gradle

**Design Spec:** `docs/superpowers/specs/2026-04-09-phase3-api-surface-design.md`

---

## File Map

### New Files
| File | Responsibility |
|------|---------------|
| `.../config/ExporterCustomizers.kt` | Container for exporter customizer lambdas + nested Builder |
| `.../config/MobileOtelDsl.kt` | Top-level DSL class + `@MobileOtelDslMarker` annotation |
| `.../config/ServiceDsl.kt` | Service name/version DSL |
| `.../config/ExportDsl.kt` | Endpoint, mode, headers, timeouts DSL |
| `.../config/BufferingDsl.kt` | RAM/disk buffer DSL |
| `.../config/SessionDsl.kt` | Session renewal DSL |
| `.../config/ExporterCustomizersDsl.kt` | Customizer registration DSL |
| `.../config/InstrumentationsDsl.kt` | Discovery + explicit add DSL |
| `.../OpenTelemetryMobile.kt` | Primary return type |
| `.../config/ExporterCustomizersTest.kt` | Customizer chain tests |
| `.../config/MobileOtelDslTest.kt` | DSL config-building tests |
| `.../OpenTelemetryMobileTest.kt` | Return type tests |

### Modified Files
| File | Change |
|------|--------|
| `.../MobileLoggerProvider.kt` | Accept `ExporterCustomizers` in constructor + `getInstance()`, apply in `init {}` |
| `.../MobileOtel.kt` | Add DSL overload, store `openTelemetryMobile`, accept customizers in existing overload |
| `.../config/MobileConfig.kt` | Add customizer methods to Builder |
| `otel-android-mobile-core/.../InstrumentationRegistry.kt` | Expose `sessionProvider` |
| `otel-android-mobile-core/.../OTelMobileHandle.kt` | Change `openTelemetry` to internal, add `sessionProvider` accessor |

**Base path for `...`:** `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/` (source) or `otel-android-mobile/src/test/java/io/opentelemetry/android/mobile/` (test)

**All commands run from:** `mobile-otel/examples/demo-app/`

---

### Task 1: ExporterCustomizers container

**Files:**
- Create: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/ExporterCustomizers.kt`
- Create: `otel-android-mobile/src/test/java/io/opentelemetry/android/mobile/config/ExporterCustomizersTest.kt`

- [ ] **Step 1: Write failing tests**

Create `otel-android-mobile/src/test/java/io/opentelemetry/android/mobile/config/ExporterCustomizersTest.kt`:

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.config

import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExporterCustomizersTest {

    @Test fun `empty customizers have empty lists`() {
        val c = ExporterCustomizers()
        assertTrue(c.log.isEmpty())
        assertTrue(c.span.isEmpty())
        assertTrue(c.metric.isEmpty())
    }

    @Test fun `EMPTY companion is empty`() {
        assertSame(ExporterCustomizers.EMPTY.log, emptyList<Any>())
    }

    @Test fun `builder adds log customizers in order`() {
        val c1: (LogRecordExporter) -> LogRecordExporter = { it }
        val c2: (LogRecordExporter) -> LogRecordExporter = { it }
        val result = ExporterCustomizers.Builder()
            .addLog(c1)
            .addLog(c2)
            .build()
        assertEquals(2, result.log.size)
        assertSame(c1, result.log[0])
        assertSame(c2, result.log[1])
    }

    @Test fun `builder adds span customizers`() {
        val c1: (SpanExporter) -> SpanExporter = { it }
        val result = ExporterCustomizers.Builder()
            .addSpan(c1)
            .build()
        assertEquals(1, result.span.size)
    }

    @Test fun `builder adds metric customizers`() {
        val c1: (MetricExporter) -> MetricExporter = { it }
        val result = ExporterCustomizers.Builder()
            .addMetric(c1)
            .build()
        assertEquals(1, result.metric.size)
    }

    @Test fun `log customizers chain in registration order`() {
        val calls = mutableListOf<String>()
        val base = mockk<LogRecordExporter>(relaxed = true)
        val c1: (LogRecordExporter) -> LogRecordExporter = { calls.add("c1"); it }
        val c2: (LogRecordExporter) -> LogRecordExporter = { calls.add("c2"); it }
        val customizers = ExporterCustomizers(log = listOf(c1, c2))

        var exporter: LogRecordExporter = base
        for (c in customizers.log) {
            exporter = c(exporter)
        }
        assertEquals(listOf("c1", "c2"), calls)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel/examples/demo-app" && ./gradlew :otel-android-mobile:testDebugUnitTest --tests "*.ExporterCustomizersTest"
```

Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement ExporterCustomizers**

Create `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/ExporterCustomizers.kt`:

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.config

import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.trace.export.SpanExporter

/**
 * Container for exporter customizer functions.
 *
 * Customizers are applied to the base OTLP exporters in registration order
 * (first registered wraps innermost, closest to the OTLP base exporter).
 *
 * Kept separate from [MobileConfig] (a data class) to avoid lambda equality
 * issues with data class `equals()`/`hashCode()`.
 */
class ExporterCustomizers(
    val log: List<(LogRecordExporter) -> LogRecordExporter> = emptyList(),
    val span: List<(SpanExporter) -> SpanExporter> = emptyList(),
    val metric: List<(MetricExporter) -> MetricExporter> = emptyList()
) {
    companion object {
        val EMPTY = ExporterCustomizers()
    }

    class Builder {
        private val log = mutableListOf<(LogRecordExporter) -> LogRecordExporter>()
        private val span = mutableListOf<(SpanExporter) -> SpanExporter>()
        private val metric = mutableListOf<(MetricExporter) -> MetricExporter>()

        fun addLog(customizer: (LogRecordExporter) -> LogRecordExporter) = apply { log.add(customizer) }
        fun addSpan(customizer: (SpanExporter) -> SpanExporter) = apply { span.add(customizer) }
        fun addMetric(customizer: (MetricExporter) -> MetricExporter) = apply { metric.add(customizer) }

        fun build() = ExporterCustomizers(log.toList(), span.toList(), metric.toList())
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel/examples/demo-app" && ./gradlew :otel-android-mobile:testDebugUnitTest --tests "*.ExporterCustomizersTest"
```

Expected: 6 tests PASS

---

### Task 2: MobileLoggerProvider — accept and apply customizers

**Files:**
- Modify: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/MobileLoggerProvider.kt`

- [ ] **Step 1: Add `customizers` parameter to constructor and `getInstance()`**

In `MobileLoggerProvider.kt`:

1. Change the private constructor (line 65) from:
```kotlin
class MobileLoggerProvider private constructor(
    private val context: Context,
    private val config: MobileConfig
) : LoggerProvider {
```
to:
```kotlin
class MobileLoggerProvider private constructor(
    private val context: Context,
    private val config: MobileConfig,
    private val customizers: ExporterCustomizers = ExporterCustomizers()
) : LoggerProvider {
```

2. Add the import at the top:
```kotlin
import io.opentelemetry.android.mobile.config.ExporterCustomizers
```

3. Change `getInstance` (around line 285) from:
```kotlin
fun getInstance(context: Context, config: MobileConfig): MobileLoggerProvider {
    return instance ?: synchronized(this) {
        instance ?: MobileLoggerProvider(
            context.applicationContext,
            config
        ).also { instance = it }
    }
}
```
to:
```kotlin
fun getInstance(
    context: Context,
    config: MobileConfig,
    customizers: ExporterCustomizers = ExporterCustomizers()
): MobileLoggerProvider {
    return instance ?: synchronized(this) {
        instance ?: MobileLoggerProvider(
            context.applicationContext,
            config,
            customizers
        ).also { instance = it }
    }
}
```

- [ ] **Step 2: Apply customizers to log exporter**

In the `init {}` block, after building the OTLP log exporter (around line 104, after `.build()`), apply customizers before wrapping with LoggingHttpExporter:

```kotlin
        // Create OTLP gRPC exporter with headers
        var baseLogExporter: LogRecordExporter = OtlpGrpcLogRecordExporter.builder()
            .setEndpoint(config.collectorEndpoint)
            .setTimeout(config.exportTimeoutSeconds, TimeUnit.SECONDS)
            .apply {
                config.headers?.forEach { (key, value) ->
                    android.util.Log.d("MobileLoggerProvider", "Adding header: $key = $value")
                    addHeader(key, value)
                }
            }
            .build()

        // Apply log exporter customizers (first registered = innermost)
        for (customizer in customizers.log) {
            baseLogExporter = customizer(baseLogExporter)
        }

        // Wrap with logging for debugging
        val loggingExporter = io.opentelemetry.android.mobile.export.LoggingHttpExporter(
            delegate = baseLogExporter,
            endpoint = config.collectorEndpoint
        )
```

Note: rename `otlpExporter` to `baseLogExporter` (was `val`, now `var` since we reassign in the loop).

- [ ] **Step 3: Apply customizers to span exporter**

After building the trace exporter (around line 162), apply customizers:

```kotlin
        // Create OTLP trace exporter
        var baseSpanExporter: SpanExporter = OtlpGrpcSpanExporter.builder()
            .setEndpoint(config.collectorEndpoint)
            .setTimeout(config.exportTimeoutSeconds, TimeUnit.SECONDS)
            .apply {
                config.headers?.forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()

        // Apply span exporter customizers
        for (customizer in customizers.span) {
            baseSpanExporter = customizer(baseSpanExporter)
        }
```

Note: rename `traceExporter` to `baseSpanExporter`.

- [ ] **Step 4: Apply customizers to metric exporter**

After building the metric exporter (around line 127), apply customizers:

```kotlin
        // Create OTLP metric exporter
        var baseMetricExporter: MetricExporter = OtlpGrpcMetricExporter.builder()
            .setEndpoint(config.collectorEndpoint)
            .setTimeout(config.exportTimeoutSeconds, TimeUnit.SECONDS)
            .apply {
                config.headers?.forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()

        // Apply metric exporter customizers
        for (customizer in customizers.metric) {
            baseMetricExporter = customizer(baseMetricExporter)
        }
```

Note: rename `metricExporter` to `baseMetricExporter`. Update all downstream references to use the new variable name.

- [ ] **Step 5: Verify existing tests still pass**

```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel/examples/demo-app" && ./gradlew :otel-android-mobile:testDebugUnitTest
```

Expected: All existing tests PASS — `customizers` defaults to empty, so no behavioral change for existing callers.

---

### Task 3: Expose sessionProvider on InstrumentationRegistry and OTelMobileHandle

**Files:**
- Modify: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/InstrumentationRegistry.kt`
- Modify: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/OTelMobileHandle.kt`

- [ ] **Step 1: Add sessionProvider to InstrumentationRegistry**

In `InstrumentationRegistry.kt`, add a `sessionProvider` property and set it in `install()`:

```kotlin
@Incubating
class InstrumentationRegistry(
    private val instrumentations: List<MobileInstrumentation>
) {
    private val installed = mutableListOf<MobileInstrumentation>()

    /** The session provider from the InstrumentationContext, available after install(). */
    var sessionProvider: MobileSessionProvider? = null
        private set

    fun install(application: Application, context: InstrumentationContext) {
        sessionProvider = context.sessionProvider
        // ... rest of existing conflict resolution + install logic unchanged
```

- [ ] **Step 2: Add sessionProvider accessor to OTelMobileHandle**

In `OTelMobileHandle.kt`, change `private val openTelemetry` to `internal val openTelemetry` and add a `sessionProvider` accessor:

```kotlin
@Incubating
class OTelMobileHandle internal constructor(
    internal val openTelemetry: OpenTelemetry,   // changed from private to internal
    private val registry: InstrumentationRegistry,
    private val hubInstaller: WindowEventHubInstaller? = null
) {
    /** Session provider, available after registry.install() has been called. */
    internal val sessionProvider: MobileSessionProvider?
        get() = registry.sessionProvider

    // ... rest unchanged
```

- [ ] **Step 3: Verify core tests pass**

```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel/examples/demo-app" && ./gradlew :otel-android-mobile-core:testDebugUnitTest
```

Expected: All PASS — these are additive, non-breaking changes.

---

### Task 4: DSL classes

**Files:**
- Create: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/MobileOtelDsl.kt`
- Create: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/ServiceDsl.kt`
- Create: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/ExportDsl.kt`
- Create: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/BufferingDsl.kt`
- Create: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/SessionDsl.kt`
- Create: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/ExporterCustomizersDsl.kt`
- Create: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/InstrumentationsDsl.kt`
- Create: `otel-android-mobile/src/test/java/io/opentelemetry/android/mobile/config/MobileOtelDslTest.kt`

This is the largest task. The DSL classes are thin — mostly property holders. The test verifies config mapping.

- [ ] **Step 1: Write failing tests**

Create `otel-android-mobile/src/test/java/io/opentelemetry/android/mobile/config/MobileOtelDslTest.kt`:

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.config

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MobileOtelDslTest {

    @Test fun `buildConfig maps service fields`() {
        val dsl = MobileOtelDsl().apply {
            service { name = "test-app"; version = "2.0.0" }
            export { endpoint = "https://example.com:4317" }
        }
        val config = dsl.buildConfig()
        assertEquals("test-app", config.serviceName)
        assertEquals("2.0.0", config.serviceVersion)
    }

    @Test fun `buildConfig maps export fields`() {
        val dsl = MobileOtelDsl().apply {
            service { name = "app"; version = "1.0" }
            export {
                endpoint = "https://example.com:4317"
                mode = ExportMode.HYBRID
                headers = mapOf("key" to "val")
                timeoutSeconds = 60
                maxRetries = 5
                traceIntervalSeconds = 15
                metricIntervalSeconds = 45
            }
        }
        val config = dsl.buildConfig()
        assertEquals("https://example.com:4317", config.collectorEndpoint)
        assertEquals(ExportMode.HYBRID, config.exportMode)
        assertEquals(mapOf("key" to "val"), config.headers)
        assertEquals(60, config.exportTimeoutSeconds)
        assertEquals(5, config.maxExportRetries)
        assertEquals(15, config.traceExportIntervalSeconds)
        assertEquals(45, config.metricExportIntervalSeconds)
    }

    @Test fun `buildConfig maps buffering fields`() {
        val dsl = MobileOtelDsl().apply {
            service { name = "app"; version = "1.0" }
            export { endpoint = "https://example.com:4317" }
            buffering { ramSize = 3000; diskMb = 25; ttlHours = 12 }
        }
        val config = dsl.buildConfig()
        assertEquals(3000, config.ramBufferSize)
        assertEquals(25, config.diskBufferMb)
        assertEquals(12, config.diskBufferTtlHours)
    }

    @Test fun `buildConfig uses defaults when blocks not called`() {
        val dsl = MobileOtelDsl().apply {
            service { name = "app"; version = "1.0" }
            export { endpoint = "https://example.com:4317" }
        }
        val config = dsl.buildConfig()
        assertEquals(5000, config.ramBufferSize)
        assertEquals(50, config.diskBufferMb)
        assertEquals(24, config.diskBufferTtlHours)
        assertEquals(ExportMode.CONDITIONAL, config.exportMode)
    }

    @Test fun `buildConfig errors when service name missing`() {
        val dsl = MobileOtelDsl().apply {
            service { version = "1.0" }
            export { endpoint = "https://example.com:4317" }
        }
        assertFailsWith<IllegalStateException> { dsl.buildConfig() }
    }

    @Test fun `buildConfig errors when service version missing`() {
        val dsl = MobileOtelDsl().apply {
            service { name = "app" }
            export { endpoint = "https://example.com:4317" }
        }
        assertFailsWith<IllegalStateException> { dsl.buildConfig() }
    }

    @Test fun `buildConfig errors when export endpoint missing`() {
        val dsl = MobileOtelDsl().apply {
            service { name = "app"; version = "1.0" }
        }
        assertFailsWith<IllegalStateException> { dsl.buildConfig() }
    }

    @Test fun `buildCustomizers returns customizers from DSL`() {
        val dsl = MobileOtelDsl().apply {
            service { name = "app"; version = "1.0" }
            export { endpoint = "https://example.com:4317" }
            exportCustomizers {
                log { it }
                span { it }
            }
        }
        val customizers = dsl.buildCustomizers()
        assertEquals(1, customizers.log.size)
        assertEquals(1, customizers.span.size)
        assertTrue(customizers.metric.isEmpty())
    }

    @Test fun `uiTelemetryMode is configurable`() {
        val dsl = MobileOtelDsl().apply {
            service { name = "app"; version = "1.0" }
            export { endpoint = "https://example.com:4317" }
            uiTelemetryMode = UiTelemetryMode.SPANS
        }
        val config = dsl.buildConfig()
        assertEquals(UiTelemetryMode.SPANS, config.uiTelemetryMode)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel/examples/demo-app" && ./gradlew :otel-android-mobile:testDebugUnitTest --tests "*.MobileOtelDslTest"
```

Expected: FAIL — DSL classes do not exist.

- [ ] **Step 3: Create all DSL classes**

Create each DSL class exactly as specified in the design spec section 2. All files go under `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/`.

**`MobileOtelDsl.kt`** — contains `MobileOtelDsl` class AND the `@MobileOtelDslMarker` annotation. Copy the code from the spec section 2, `MobileOtelDsl` class and the `@DslMarker annotation class MobileOtelDslMarker`.

**`ServiceDsl.kt`** — `@MobileOtelDslMarker class ServiceDsl { var name: String? = null; var version: String? = null }`

**`ExportDsl.kt`** — `@MobileOtelDslMarker class ExportDsl { var endpoint: String? = null; var mode: ExportMode = ExportMode.CONDITIONAL; ... }`

**`BufferingDsl.kt`** — `@MobileOtelDslMarker class BufferingDsl { var ramSize: Int = 5000; var diskMb: Int = 50; var ttlHours: Int = 24 }`

**`SessionDsl.kt`** — `@MobileOtelDslMarker class SessionDsl { var renewalMinutes: Long = 30 }`

**`ExporterCustomizersDsl.kt`** — `@MobileOtelDslMarker class ExporterCustomizersDsl { ... fun log(...), fun span(...), fun metric(...), internal fun build() }`

**`InstrumentationsDsl.kt`** — `@MobileOtelDslMarker class InstrumentationsDsl { fun discoverAll(), fun discoverOwn(), fun add(...), internal fun applyTo(builder) }`

Copy each class from the spec section 2, word for word. Each file needs the copyright header and package declaration `io.opentelemetry.android.mobile.config`. The `InstrumentationsDsl` needs imports for `MobileInstrumentation` and `OTelMobileBuilder` from the `instrumentation` package.

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel/examples/demo-app" && ./gradlew :otel-android-mobile:testDebugUnitTest --tests "*.MobileOtelDslTest"
```

Expected: 9 tests PASS

---

### Task 5: OpenTelemetryMobile return type

**Files:**
- Create: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/OpenTelemetryMobile.kt`
- Create: `otel-android-mobile/src/test/java/io/opentelemetry/android/mobile/OpenTelemetryMobileTest.kt`

- [ ] **Step 1: Write failing tests**

Create `otel-android-mobile/src/test/java/io/opentelemetry/android/mobile/OpenTelemetryMobileTest.kt`:

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.android.mobile.instrumentation.DefaultMobileSessionProvider
import io.opentelemetry.android.mobile.instrumentation.MobileSessionProvider
import io.opentelemetry.android.mobile.instrumentation.OTelMobileHandle
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OpenTelemetryMobileTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    @Test fun `openTelemetry returns the SDK instance`() {
        val mobile = createMobile()
        assertNotNull(mobile.openTelemetry)
    }

    @Test fun `sessionId delegates to sessionProvider`() {
        val sp = mockk<MobileSessionProvider> {
            every { getSessionId() } returns "test-session-123"
        }
        val mobile = createMobile(sessionProvider = sp)
        assertEquals("test-session-123", mobile.sessionId)
    }

    @Test fun `getTracer returns a tracer`() {
        val mobile = createMobile()
        assertNotNull(mobile.getTracer("test-scope"))
    }

    @Test fun `getLogger returns a logger`() {
        val mobile = createMobile()
        assertNotNull(mobile.getLogger("test-scope"))
    }

    @Test fun `getMeter returns a meter`() {
        val mobile = createMobile()
        assertNotNull(mobile.getMeter("test-scope"))
    }

    @Test fun `shutdown delegates to handle stop`() {
        val handle = mockk<OTelMobileHandle>(relaxed = true)
        val mobile = createMobile(handle = handle)
        mobile.shutdown(10)
        verify { handle.stop(10) }
    }

    private fun createMobile(
        handle: OTelMobileHandle = mockk(relaxed = true),
        sessionProvider: MobileSessionProvider = DefaultMobileSessionProvider(),
        loggerProvider: MobileLoggerProvider = mockk(relaxed = true)
    ) = OpenTelemetryMobile(
        openTelemetry = otelRule.openTelemetry,
        handle = handle,
        sessionProvider = sessionProvider,
        loggerProvider = loggerProvider
    )
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel/examples/demo-app" && ./gradlew :otel-android-mobile:testDebugUnitTest --tests "*.OpenTelemetryMobileTest"
```

Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement OpenTelemetryMobile**

Create `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/OpenTelemetryMobile.kt`:

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile

import io.opentelemetry.android.mobile.instrumentation.Incubating
import io.opentelemetry.android.mobile.instrumentation.MobileSessionProvider
import io.opentelemetry.android.mobile.instrumentation.OTelMobileHandle
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.common.CompletableResultCode

/**
 * Primary public type for the Dash0 Mobile Observability SDK.
 *
 * Returned by [MobileOtel.initialize] (DSL overload). Provides access to the
 * configured [OpenTelemetry] SDK instance, the current session, and convenience
 * methods for tracers/loggers/meters.
 *
 * This is what `OpenTelemetryRum` would be if we designed the upstream.
 * When we merge, this interface becomes the standard.
 */
@Incubating
class OpenTelemetryMobile internal constructor(
    /** The configured OpenTelemetry SDK instance. */
    val openTelemetry: OpenTelemetry,
    private val handle: OTelMobileHandle,
    private val sessionProvider: MobileSessionProvider,
    private val loggerProvider: MobileLoggerProvider
) {
    /** Current session ID. */
    val sessionId: String get() = sessionProvider.getSessionId()

    /** Convenience — returns a [Tracer] scoped to [scope]. */
    fun getTracer(scope: String): Tracer = openTelemetry.getTracer(scope)

    /** Convenience — returns a [Logger] scoped to [scope]. */
    fun getLogger(scope: String): Logger = openTelemetry.logsBridge.get(scope)

    /** Convenience — returns a [Meter] scoped to [scope]. */
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

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel/examples/demo-app" && ./gradlew :otel-android-mobile:testDebugUnitTest --tests "*.OpenTelemetryMobileTest"
```

Expected: 6 tests PASS

---

### Task 6: MobileOtel DSL overload + MobileConfig.Builder customizer methods

**Files:**
- Modify: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/MobileOtel.kt`
- Modify: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/MobileConfig.kt`

- [ ] **Step 1: Add customizers parameter to existing `MobileOtel.initialize()`**

In `MobileOtel.kt`, change the existing `initialize` signature (around line 90) from:

```kotlin
fun initialize(context: Context, config: MobileConfig): MobileLoggerProvider {
```

to:

```kotlin
fun initialize(
    context: Context,
    config: MobileConfig,
    customizers: ExporterCustomizers = ExporterCustomizers()
): MobileLoggerProvider {
```

Add import: `import io.opentelemetry.android.mobile.config.ExporterCustomizers`

Then change the `MobileLoggerProvider.getInstance` call inside to pass customizers:

```kotlin
val loggerProvider = MobileLoggerProvider.getInstance(appContext, config, customizers)
```

- [ ] **Step 2: Add `openTelemetryMobile` storage field**

In `MobileOtel.kt`, add after the existing field declarations (around line 70):

```kotlin
    /** The active OpenTelemetryMobile instance, available after initialize(context) { } DSL. */
    var openTelemetryMobile: OpenTelemetryMobile? = null
        private set
```

- [ ] **Step 3: Add DSL overload**

In `MobileOtel.kt`, add the new DSL overload after the existing `initialize` method:

```kotlin
    /**
     * Initialize the Mobile OpenTelemetry SDK using a Kotlin DSL.
     *
     * This is the primary entry point, matching upstream's
     * `OpenTelemetryRumInitializer.initialize(context) { }` pattern.
     *
     * Combines config initialization with instrumentation module discovery,
     * returning an [OpenTelemetryMobile] instance for accessing the SDK.
     *
     * ```kotlin
     * val otel = MobileOtel.initialize(context) {
     *     service { name = "my-app"; version = "1.0.0" }
     *     export { endpoint = "https://collector:4317" }
     *     instrumentations { discoverAll() }
     * }
     * ```
     */
    fun initialize(
        context: Context,
        block: io.opentelemetry.android.mobile.config.MobileOtelDsl.() -> Unit
    ): OpenTelemetryMobile {
        val dsl = io.opentelemetry.android.mobile.config.MobileOtelDsl().apply(block)
        val config = dsl.buildConfig()
        val customizers = dsl.buildCustomizers()

        // 1. Initialize core SDK (existing path)
        val loggerProvider = initialize(context, config, customizers)

        // 2. Build instrumentation registry
        val app = context.applicationContext as android.app.Application
        val builder = io.opentelemetry.android.mobile.instrumentation.OTelMobileBuilder(
            app, loggerProvider.getOpenTelemetrySdk()
        ).setUiTelemetryMode(
            io.opentelemetry.android.mobile.instrumentation.UiTelemetryMode.valueOf(
                config.uiTelemetryMode.name
            )
        )
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

Note: The `UiTelemetryMode` in `MobileConfig` is from `config` package, while `OTelMobileBuilder.setUiTelemetryMode()` takes the one from `instrumentation` package. They're the same enum values — use `valueOf(config.uiTelemetryMode.name)` to bridge. If they're actually the same class (check at implementation time), just pass directly.

- [ ] **Step 4: Add customizer methods to MobileConfig.Builder**

In `MobileConfig.kt`, in the `Builder` class, add:

1. Add import at top of file:
```kotlin
import io.opentelemetry.android.mobile.config.ExporterCustomizers
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.sdk.metrics.export.MetricExporter
```

2. Add field in Builder:
```kotlin
        private val exporterCustomizers = ExporterCustomizers.Builder()
```

3. Add methods after existing setters:
```kotlin
        fun addLogExporterCustomizer(customizer: (LogRecordExporter) -> LogRecordExporter) = apply {
            exporterCustomizers.addLog(customizer)
        }
        fun addSpanExporterCustomizer(customizer: (SpanExporter) -> SpanExporter) = apply {
            exporterCustomizers.addSpan(customizer)
        }
        fun addMetricExporterCustomizer(customizer: (MetricExporter) -> MetricExporter) = apply {
            exporterCustomizers.addMetric(customizer)
        }
```

4. Add `buildWithCustomizers()` method after `build()`:
```kotlin
        fun buildWithCustomizers(): Pair<MobileConfig, ExporterCustomizers> {
            return Pair(build(), exporterCustomizers.build())
        }
```

- [ ] **Step 5: Verify build compiles**

```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel/examples/demo-app" && ./gradlew :otel-android-mobile:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Verify all existing tests still pass**

```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel/examples/demo-app" && ./gradlew :otel-android-mobile:testDebugUnitTest
```

Expected: All PASS — all changes are additive with default parameters.

---

### Task 7: Full Regression + Commit

- [ ] **Step 1: Run core + SDK tests**

```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel/examples/demo-app" && ./gradlew :otel-android-mobile-core:testDebugUnitTest :otel-android-mobile:testDebugUnitTest
```

Expected: All PASS

- [ ] **Step 2: Run full APK build**

```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel/examples/demo-app" && ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel"
git add -A
git commit -m "feat: Phase 3 — Kotlin DSL, exporter customizers, OpenTelemetryMobile

Unified entry point matching upstream's API pattern:

MobileOtel.initialize(context) { }:
- Kotlin DSL with type-safe blocks: service {}, export {}, buffering {},
  session {}, exportCustomizers {}, instrumentations {}
- Returns OpenTelemetryMobile (our primary public type)
- Combines config init + instrumentation wiring in one call
- @MobileOtelDslMarker prevents scope leakage

ExporterCustomizers:
- Separate from MobileConfig (avoids data class lambda equality issues)
- Applied to OTLP exporters in registration order (first = innermost)
- log {}, span {}, metric {} customizer registration
- MobileLoggerProvider.getInstance() accepts customizers
- MobileConfig.Builder gets addLogExporterCustomizer() etc.

OpenTelemetryMobile:
- Primary return type with openTelemetry, sessionId, getTracer/Logger/Meter
- forceFlush(), flushWindow(), shutdown() convenience methods
- Stored on MobileOtel.openTelemetryMobile after DSL init

Infrastructure:
- InstrumentationRegistry exposes sessionProvider
- OTelMobileHandle.openTelemetry changed to internal"
```
