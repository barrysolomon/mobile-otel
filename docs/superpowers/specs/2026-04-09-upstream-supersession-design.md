# Upstream Supersession — Compatible Superset of `opentelemetry-android`

**Date:** 2026-04-09
**Status:** Approved
**Scope:** Make Dash0's mobile-otel SDK a strict, compatible superset of the upstream `opentelemetry-android` project, positioning for eventual merge as the next-generation official SDK.

---

## Table of Contents

1. [Overview & Strategy](#1-overview--strategy)
2. [Phase 1: Foundation — Interface Adapter & Dependency Alignment](#2-phase-1-foundation)
3. [Phase 2a: New Modules — Compose Click & Screen Orientation](#3-phase-2a-new-modules)
4. [Phase 2b: Reimplemented Modules — WebSocket & Android Log](#4-phase-2b-reimplemented-modules)
5. [Phase 3: API Surface Parity](#5-phase-3-api-surface-parity)
6. [Phase 4: Interface Convergence & iOS Update](#6-phase-4-interface-convergence)
7. [Module Inventory & Supersession Map](#7-module-inventory--supersession-map)
8. [Backlog & Roadmap Integration](#8-backlog--roadmap-integration)
9. [Success Criteria](#9-success-criteria)
10. [Risks & Mitigations](#10-risks--mitigations)

---

## 1. Overview & Strategy

### Goal

Make Dash0's mobile-otel Android SDK a **strict superset** of the upstream `opentelemetry-android` (v0.10.0-alpha core and instrumentations). Every upstream module runs unmodified in our framework via an adapter. Our modules offer richer telemetry (policy engine, dual-tier buffering, selective flush, UI gesture tracking, wireframe replay) that upstream lacks. When the time comes to propose a merge, there is zero technical argument against it.

### Strategy: Compatible Superset leading to Eventual Merge

```
Phase 1    Phase 2a/2b    Phase 3    Phase 4
  |            |             |          |
  v            v             v          v
Adapter -> Fill gaps --> API parity -> Converge interfaces
  |                                      |
  +-- Upstream modules run in our -------+-- Our modules run in upstream
      framework via adapter                  framework natively
```

**Phase 1 (Adapter):** Wrap upstream's `AndroidInstrumentation` so their modules plug into our registry. Non-breaking for our existing 16 modules.

**Phase 2a/2b (Fill gaps):** Write new modules for capabilities we lack (Compose click, screen orientation, WebSocket). Reimplement rather than absorb because the two key modules (Compose, screen-orientation) are not published to Maven Central, and the ByteBuddy-dependent modules (`android-log`, `httpurlconnection`, `okhttp3`, `okhttp3-websocket`) require a Gradle build plugin we choose not to adopt.

**Phase 3 (API parity):** Adopt upstream patterns (Kotlin DSL config, exporter customizer chain, `OpenTelemetryRum` compatibility shim) so developers switching from upstream feel at home.

**Phase 4 (Converge):** Refactor our interfaces to directly extend upstream's, eliminating the adapter layer. Update all modules. Update the iOS port spec. Prepare merge proposal.

### Key Decision: No ByteBuddy

The upstream uses ByteBuddy build-time bytecode weaving for 4 modules (`android-log`, `httpurlconnection`, `okhttp3`, `okhttp3-websocket`). We do NOT adopt ByteBuddy because:
- It requires a Gradle plugin that changes the build system for all consumers
- Our runtime approach (interceptors, callbacks, reflection) is lighter and more debuggable
- We already have superior OkHttp instrumentation via `OTelNetworkInterceptor`
- The two highest-value missing modules (Compose click, screen orientation) don't use ByteBuddy

Modules that upstream instruments via ByteBuddy are either already covered by our runtime approach (OkHttp) or deprioritized (HttpURLConnection is legacy, android.util.Log is covered by our Timber bridge).

### Upstream Artifact Coordinates (Maven Central)

| Artifact | Group | Version | Published |
|----------|-------|---------|-----------|
| `session` | `io.opentelemetry.android` | `0.10.0-alpha` | Yes |
| `instrumentation-android-instrumentation` | `io.opentelemetry.android` | `0.10.0-alpha` | Yes |
| `core` | `io.opentelemetry.android` | `0.10.0-alpha` | Yes |
| `instrumentation-crash` | `io.opentelemetry.android` | `0.10.0-alpha` | Yes |
| `instrumentation-anr` | `io.opentelemetry.android` | `0.10.0-alpha` | Yes |
| `instrumentation-activity` | `io.opentelemetry.android` | `0.10.0-alpha` | Yes |
| `instrumentation-fragment` | `io.opentelemetry.android` | `0.10.0-alpha` | Yes |
| `instrumentation-slowrendering` | `io.opentelemetry.android` | `0.10.0-alpha` | Yes |
| `instrumentation-startup` | `io.opentelemetry.android` | `0.10.0-alpha` | Yes |
| `instrumentation-sessions` | `io.opentelemetry.android` | `0.10.0-alpha` | Yes |
| `instrumentation-network` | `io.opentelemetry.android` | `0.10.0-alpha` | Yes |
| Compose click | -- | -- | **Not published** |
| Screen orientation | -- | -- | **Not published** |

---

## 2. Phase 1: Foundation

### 2.1 Dependency Alignment

**Current state:** We pin OTel SDK at `1.58.0` across all modules. We declare a phantom dependency on `io.opentelemetry.android:instrumentation:0.4.0-alpha` (unused, stale artifact ID).

**Target state:**
- Keep OTel SDK at `1.58.0` (our version is newer than upstream's BOM-resolved version; Gradle picks the higher version, and OTel API is backward-compatible within 1.x)
- Remove phantom dep `io.opentelemetry.android:instrumentation:0.4.0-alpha`
- Add real dependencies in `otel-android-mobile-core/build.gradle.kts`:
  - `api("io.opentelemetry.android:session:0.10.0-alpha")`
  - `api("io.opentelemetry.android:instrumentation-android-instrumentation:0.10.0-alpha")`
- Update semconv from `1.39.0` to `1.40.0` to match upstream

### 2.2 Session Interface Bridge

Upstream's `SessionProvider` is a `fun interface` with a single method:
```kotlin
// io.opentelemetry.android.session.SessionProvider
fun interface SessionProvider {
    fun getSessionId(): String
}
```

Our `MobileSessionProvider` is a strict superset. The fix is trivial -- add the superinterface:

```kotlin
// Before:
interface MobileSessionProvider {
    fun getSessionId(): String
    fun getViewId(): String
    // ... 8 more methods
}

// After:
import io.opentelemetry.android.session.SessionProvider

interface MobileSessionProvider : SessionProvider {
    override fun getSessionId(): String  // satisfies both interfaces
    fun getViewId(): String
    // ... 8 more methods (unchanged)
}
```

For the reverse direction (passing upstream's `SessionProvider` into our context when adapting upstream modules), create a minimal adapter:

```kotlin
internal class UpstreamSessionProviderAdapter(
    private val upstream: SessionProvider
) : MobileSessionProvider {
    override fun getSessionId(): String = upstream.getSessionId()
    override fun getViewId(): String = ""  // upstream doesn't track views
    override fun getCurrentScreenName(): String? = null
    override fun getPreviousScreenName(): String? = null
    override fun getTimeOnScreenMs(): Long = 0L
    override fun onScreenView(screenName: String) {}
    override fun onAppForeground(timestampMs: Long): Boolean = false
    override fun onAppBackground(timestampMs: Long) {}
}
```

### 2.3 Bidirectional Adapter

**Direction 1: Run upstream `AndroidInstrumentation` in our framework**

```kotlin
class UpstreamInstrumentationAdapter(
    private val upstream: AndroidInstrumentation
) : MobileInstrumentation {

    override val instrumentationName: String = upstream.name

    private var cachedInstallCtx: InstallationContext? = null

    override fun install(application: Application, context: InstrumentationContext) {
        val installCtx = InstallationContext(
            context = application,
            openTelemetry = context.openTelemetry,
            sessionProvider = context.sessionProvider,  // MobileSessionProvider IS-A SessionProvider
            clock = context.clock
                ?: io.opentelemetry.sdk.common.Clock.getDefault()  // from OTel SDK, no extra dep
        )
        cachedInstallCtx = installCtx
        upstream.install(installCtx)
    }

    override fun uninstall() {
        cachedInstallCtx?.let { upstream.uninstall(it) }
        cachedInstallCtx = null
    }
}
```

**Direction 2: Run our `MobileInstrumentation` in upstream framework** (for merge proposal validation)

```kotlin
class MobileInstrumentationAdapter(
    private val mobile: MobileInstrumentation,
    private val windowEventHub: WindowEventHub,
    private val uiTelemetryMode: UiTelemetryMode = UiTelemetryMode.EVENTS
) : AndroidInstrumentation {

    override val name: String = mobile.instrumentationName

    override fun install(ctx: InstallationContext) {
        val mobileCtx = InstrumentationContext(
            openTelemetry = ctx.openTelemetry,
            sessionProvider = if (ctx.sessionProvider is MobileSessionProvider) {
                ctx.sessionProvider as MobileSessionProvider
            } else {
                UpstreamSessionProviderAdapter(ctx.sessionProvider)
            },
            windowEventHub = windowEventHub,
            application = ctx.application
                ?: (ctx.context as? Application)
                ?: throw IllegalStateException(
                    "InstallationContext.context must be an Application"
                ),
            uiTelemetryMode = uiTelemetryMode,
            clock = ctx.clock
        )
        mobile.install(mobileCtx.application, mobileCtx)
    }

    override fun uninstall(ctx: InstallationContext) {
        mobile.uninstall()
    }
}
```

### 2.4 InstrumentationContext -- Add Optional Clock

```kotlin
class InstrumentationContext(
    val openTelemetry: OpenTelemetry,
    val sessionProvider: MobileSessionProvider,
    val windowEventHub: WindowEventHub,
    val application: Application,
    val uiTelemetryMode: UiTelemetryMode = UiTelemetryMode.EVENTS,
    val breadcrumbManager: BreadcrumbManager? = ...,
    val clock: io.opentelemetry.sdk.common.Clock? = null  // NEW -- for upstream compat
) {
    // Existing convenience methods unchanged
}
```

### 2.5 @Supersedes Annotation & Conflict Resolution

```kotlin
/**
 * Declares that this MobileInstrumentation supersedes one or more upstream
 * AndroidInstrumentation modules. When both are discovered, this module
 * takes priority and the upstream module is skipped.
 *
 * Values must match the upstream module's AndroidInstrumentation.name exactly.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Supersedes(vararg val names: String)
```

Apply to existing modules:

| Our Module | @Supersedes(...) | Reason |
|------------|------------------|--------|
| `ErrorInstrumentation` | `"crash"` | Ours adds coroutine errors, dedup, rate limiting, auto-flush |
| `VitalsInstrumentation` | `"anr"`, `"startup"` | Ours combines ANR, jank, memory, battery, cold/warm start |
| `FreezeInstrumentation` | `"slowrendering"` | Ours detects full freezes, not just slow frames |
| `LifecycleInstrumentation` | `"activity"`, `"fragment"` | Ours handles both in one module |
| `TapInstrumentation` | `"view.click"` | Ours adds long-press, swipe, gesture detection |
| `NetworkInstrumentation` | `"okhttp"` | Ours uses interceptor (no ByteBuddy needed) |

Modules that do NOT supersede (additive):
- `ScreenViewInstrumentation` -- different from upstream's activity/fragment lifecycle
- `ScrollInstrumentation`, `TextInputInstrumentation`, `BackPressInstrumentation` -- no upstream equivalent
- `ScreenshotInstrumentation`, `WireframeInstrumentation` -- no upstream equivalent
- `DatabaseInstrumentation`, `FileIOInstrumentation`, `SystemEventsInstrumentation`, `TimberInstrumentation` -- no upstream equivalent

**Conflict resolution in InstrumentationRegistry.install():**

```kotlin
fun install(application: Application, context: InstrumentationContext) {
    // 1. Collect supersession claims from our modules
    val supersededNames = mutableSetOf<String>()
    for (inst in instrumentations) {
        val ann = inst::class.java.getAnnotation(Supersedes::class.java)
        if (ann != null) supersededNames.addAll(ann.names)
    }

    // 2. Install each, skipping superseded upstream modules
    for (inst in instrumentations) {
        if (inst is UpstreamInstrumentationAdapter
            && inst.instrumentationName in supersededNames
        ) {
            Log.i(TAG, "Skipping ${inst.instrumentationName}"
                + " -- superseded by a MobileInstrumentation module")
            continue
        }
        inst.install(application, context)
        installed.add(inst)
    }
}
```

### 2.6 Discovery Enhancement on OTelMobileBuilder

```kotlin
/**
 * Discovers upstream AndroidInstrumentation modules via ServiceLoader,
 * wraps each in UpstreamInstrumentationAdapter, and adds them.
 */
fun discoverUpstreamInstrumentations(): OTelMobileBuilder = apply {
    ServiceLoader.load(
        AndroidInstrumentation::class.java,
        AndroidInstrumentation::class.java.classLoader
    ).forEach { upstream ->
        instrumentations.add(UpstreamInstrumentationAdapter(upstream))
    }
}

/**
 * Discovers both MobileInstrumentation and AndroidInstrumentation
 * modules. Convenience method combining both discovery paths.
 */
fun discoverAllInstrumentations(): OTelMobileBuilder = apply {
    discoverInstrumentations()
    // Discover upstream modules, but skip any that are already registered
    // as MobileInstrumentation (avoids double-add after Phase 4 convergence
    // when modules appear in both service files).
    val existingNames = instrumentations.map { it.instrumentationName }.toSet()
    ServiceLoader.load(
        AndroidInstrumentation::class.java,
        AndroidInstrumentation::class.java.classLoader
    ).forEach { upstream ->
        if (upstream.name !in existingNames) {
            instrumentations.add(UpstreamInstrumentationAdapter(upstream))
        }
    }
}
```

### 2.7 iOS Spec Stale Notice

Add to `docs/superpowers/specs/2026-04-08-ios-sdk-port-design.md` section 5 heading:

> **STALE (2026-04-09):** The `MobileInstrumentation` interface is being
> aligned with upstream `opentelemetry-android`'s `AndroidInstrumentation`
> as part of the Upstream Supersession epic. The iOS `MobileInstrumentation`
> protocol will be updated when Phase 4 (Interface Convergence) completes.
> See: `docs/superpowers/specs/2026-04-09-upstream-supersession-design.md`

### 2.8 Phase 1 Tests

- `UpstreamInstrumentationAdapterTest` -- verifies install/uninstall delegation, context mapping
- `MobileInstrumentationAdapterTest` -- verifies reverse direction
- `SessionProviderBridgeTest` -- `MobileSessionProvider` satisfies `SessionProvider` contract
- `UpstreamSessionProviderAdapterTest` -- default values for missing methods
- `SupersedesConflictTest` -- registry skips superseded modules, installs non-superseded
- `DiscoverUpstreamTest` -- ServiceLoader finds upstream modules, wraps correctly
- `DiscoverAllTest` -- combined discovery, conflict resolution, no duplicates

---

## 3. Phase 2a: New Modules

### 3.1 ComposeClickInstrumentation

**Why:** Jetpack Compose is Google's recommended UI toolkit. Our `TapInstrumentation` intercepts `Window.Callback.dispatchTouchEvent()` which gives raw coordinates but cannot identify which Composable was tapped. We need Compose-aware tap detection.

**Design:** Inspired by upstream's `ComposeClickInstrumentation` (not published to Maven Central, so we reimplement) but integrated with our richer telemetry patterns.

```kotlin
@Incubating
@Supersedes("compose.click")  // supersede upstream's version if ever published
class ComposeClickInstrumentation(
    private val config: ComposeClickConfig = ComposeClickConfig()
) : MobileInstrumentation {

    override val instrumentationName = "io.opentelemetry.android.mobile.compose.click"

    override fun install(application: Application, context: InstrumentationContext) {
        // Register ActivityLifecycleCallbacks to detect Compose content
        // For each activity with ComposeView:
        //   1. Wrap Window.Callback to intercept touch events (like upstream)
        //   2. On ACTION_UP, walk the Compose semantics tree to find the
        //      tapped composable (using LayoutCoordinates hit-testing)
        //   3. Extract: testTag, contentDescription, role, onClick presence
        //   4. Emit ui.tap with compose-specific attributes
    }
}

data class ComposeClickConfig(
    val enabled: Boolean = true,
    val captureTestTag: Boolean = true,
    val captureContentDescription: Boolean = true,
    val captureRole: Boolean = true
)
```

**Emitted attributes (superset of upstream's):**
- All standard `ui.tap` attributes from `TapInstrumentation`
- `ui.element.framework = "compose"` -- distinguishes from View-based taps
- `ui.element.test_tag` -- Compose `testTag` modifier value
- `ui.element.content_description` -- accessibility label
- `ui.element.role` -- Compose `Role` (Button, Checkbox, etc.)

**Interaction with existing TapInstrumentation:** Both modules intercept `Window.Callback.dispatchTouchEvent()`, so a tap on a Compose element could fire both. To prevent duplicate events:

- `ComposeClickInstrumentation` runs first (registered via `ActivityLifecycleCallbacks` with higher priority) and sets a thread-local flag when it successfully resolves a Compose target.
- `TapInstrumentation` checks this flag in its `onTouchEvent` handler. If set, it skips emission for that touch sequence (the Compose module already handled it).
- For View-based taps where no Compose target is found, `TapInstrumentation` fires normally.
- The `ui.element.framework` attribute (`"compose"` vs `"view"`) distinguishes the source in telemetry.
- In mixed Compose+View screens, each tap fires exactly one event from the appropriate module.

**Dependencies:** `compileOnly` on `androidx.compose.ui:ui` to avoid forcing Compose on non-Compose apps. Runtime presence check before registering callbacks.

### 3.2 ScreenOrientationInstrumentation

**Design:** Simple `ComponentCallbacks` listener, inspired by upstream's implementation.

```kotlin
@Incubating
@Supersedes("screen_orientation")
class ScreenOrientationInstrumentation : MobileInstrumentation {

    override val instrumentationName = "io.opentelemetry.android.mobile.screen-orientation"

    override fun install(application: Application, context: InstrumentationContext) {
        // Register ComponentCallbacks on application context
        // On onConfigurationChanged: detect orientation change
        // Emit log event:
        //   body: "device.orientation"
        //   attributes:
        //     - device.orientation: "portrait" | "landscape"
        //     - device.orientation.previous: "landscape" | "portrait"
        //     - session.id, view.id, screen.name (from sessionProvider)
        // Also add breadcrumb
    }
}
```

**Module structure:** New Gradle module at `instrumentation/screen-orientation/`.

---

## 4. Phase 2b: Reimplemented Modules

### 4.1 WebSocketInstrumentation (P2)

OkHttp WebSocket listener wrapper. Runtime, no ByteBuddy.

```kotlin
@Incubating
@Supersedes("okhttp-websocket")
class WebSocketInstrumentation : MobileInstrumentation {
    override val instrumentationName = "io.opentelemetry.android.mobile.websocket"

    // Provides OTelWebSocketListener -- a wrapper around WebSocketListener
    // that creates spans for: connection open, message sent, message received,
    // connection closed, failure.
    //
    // User-wired (like NetworkInstrumentation):
    //   val listener = OTelWebSocketListener(myListener, otelMobile)
    //   okHttpClient.newWebSocket(request, listener)
}
```

### 4.2 AndroidLogInstrumentation (P2, Optional)

Intercepts `android.util.Log` calls without ByteBuddy. Limited approach -- cannot intercept at bytecode level, so uses a Logcat reader thread.

```kotlin
@Incubating
class AndroidLogInstrumentation(
    private val minPriority: Int = Log.WARN  // only WARN+ by default to limit noise
) : MobileInstrumentation {
    override val instrumentationName = "io.opentelemetry.android.mobile.android-log"

    // Approach: Start a background thread that reads from logcat process
    // via Runtime.exec("logcat -v threadtime") and parses log lines.
    // Emits OTel log records for lines at >= minPriority from the app's PID.
    //
    // Limitations vs upstream's ByteBuddy approach:
    // - Slight delay (logcat buffering)
    // - Cannot capture structured arguments
    // - Requires READ_LOGS permission on some devices
    //
    // Alternative recommendation: Use TimberInstrumentation + Timber bridge
    // for most apps. This module is for apps that use android.util.Log directly.
}
```

### 4.3 Modules Explicitly Skipped

| Upstream Module | Decision | Reason |
|---|---|---|
| `httpurlconnection` | **Skip** | Legacy API, decreasing usage. Apps can use OkUrlFactory wrapper + our interceptor. Not worth the effort. |
| `okhttp3` (ByteBuddy agent) | **Already covered** | Our `NetworkInstrumentation` + `OTelNetworkInterceptor` provides equivalent functionality via runtime interceptor. `@Supersedes("okhttp")` already set. |

---

## 5. Phase 3: API Surface Parity

### 5.1 Kotlin DSL Configuration

Wrapper over existing `MobileConfig` + `OTelMobileBuilder`:

```kotlin
/**
 * Kotlin DSL entry point for SDK initialization.
 * Matches upstream's configuration style while exposing our richer options.
 */
fun mobileOtel(
    context: Context,
    block: MobileOtelConfiguration.() -> Unit
): OTelMobileHandle {
    val config = MobileOtelConfiguration(context).apply(block)
    return config.build()
}

@MobileOtelDsl
class MobileOtelConfiguration(private val context: Context) {
    private var serviceName: String = ""
    private var serviceVersion: String = ""
    private var exportConfig = ExportConfiguration()
    private var bufferConfig = BufferConfiguration()
    private var instrumentationConfig = InstrumentationConfiguration()

    fun service(block: ServiceConfiguration.() -> Unit) { ... }

    fun export(block: ExportConfiguration.() -> Unit) {
        exportConfig.apply(block)
    }

    fun buffering(block: BufferConfiguration.() -> Unit) {
        bufferConfig.apply(block)
    }

    fun instrumentations(block: InstrumentationConfiguration.() -> Unit) {
        instrumentationConfig.apply(block)
    }

    internal fun build(): OTelMobileHandle {
        // Converts DSL config to MobileConfig + OTelMobileBuilder
        // Delegates to existing infrastructure
    }
}

@MobileOtelDsl
class ExportConfiguration {
    var endpoint: String = ""
    var mode: ExportMode = ExportMode.CONDITIONAL
    var headers: Map<String, String> = emptyMap()
    var traceIntervalSeconds: Long = 30
    var metricIntervalSeconds: Long = 60
}

@MobileOtelDsl
class BufferConfiguration {
    var ramSize: Int = 5000
    var diskMb: Int = 50
    var ttlHours: Int = 24
}

@MobileOtelDsl
class InstrumentationConfiguration {
    fun tap(block: TapConfig.() -> Unit = {}) { ... }
    fun screenshot(block: ScreenshotConfig.() -> Unit = {}) { ... }
    fun wireframe(block: WireframeConfig.() -> Unit = {}) { ... }
    fun compose(block: ComposeClickConfig.() -> Unit = {}) { ... }
    // etc.
}

@DslMarker
annotation class MobileOtelDsl
```

**Usage:**
```kotlin
// New Kotlin DSL style (matches upstream's pattern)
val handle = mobileOtel(this) {
    service {
        name = "my-app"
        version = "1.0.0"
    }
    export {
        endpoint = "https://collector.dash0.com:4317"
        mode = ExportMode.CONDITIONAL
        headers = mapOf("Authorization" to "Bearer token")
    }
    buffering {
        ramSize = 5000
        diskMb = 50
    }
    instrumentations {
        tap { swipeMinDistancePx = 50f }
        screenshot { enabled = true }
    }
}

// Existing entry points continue to work (non-breaking)
val config = MobileConfig(serviceName = "my-app", ...)
MobileOtel.initialize(context, config)  // returns MobileLoggerProvider

// The DSL is a NEW entry point that returns OTelMobileHandle (richer type).
// Internally it calls OTelMobileBuilder.build() which wires instrumentation
// modules. MobileOtel.initialize() remains the simpler path for users who
// don't need the instrumentation registry (just buffering + export).
```

### 5.2 Exporter Customizer Chain

Add to `MobileConfig.Builder` and DSL:

```kotlin
// On MobileConfig.Builder:
fun addLogExporterCustomizer(
    customizer: (LogRecordExporter) -> LogRecordExporter
): Builder

fun addSpanExporterCustomizer(
    customizer: (SpanExporter) -> SpanExporter
): Builder

fun addMetricExporterCustomizer(
    customizer: (MetricExporter) -> MetricExporter
): Builder
```

Customizers are chained in registration order (first registered is innermost). Applied in `MobileLoggerProvider` when constructing the exporter pipeline, between the base OTLP exporter and our `EnrichingLogRecordExporter`.

```
User customizer C -> User customizer B -> User customizer A -> EnrichingExporter -> OTLP exporter
```

**Use cases:** PII redaction, attribute filtering, sampling at export boundary, logging/debugging.

### 5.3 OpenTelemetryRum Compatibility Shim

For developers migrating from upstream:

```kotlin
/**
 * Compatibility shim that mimics the API shape of upstream's
 * OpenTelemetryRum. Allows code patterns from the upstream SDK to
 * work with our SDK with minimal changes.
 *
 * Note: To implement upstream's actual OpenTelemetryRum interface
 * (not just mimic the shape), add `io.opentelemetry.android:core:0.10.0-alpha`.
 * For now, this is a standalone class with matching method signatures.
 */
class OpenTelemetryRumCompat private constructor(
    private val openTelemetry: OpenTelemetry,
    private val sessionProvider: MobileSessionProvider
) {
    val otel: OpenTelemetry get() = openTelemetry
    val rumSessionId: String get() = sessionProvider.getSessionId()

    companion object {
        /**
         * Creates a compatibility wrapper.
         *
         * Note: OTelMobileHandle currently has private fields. This factory
         * takes the OTel instance and session provider directly. Phase 3
         * implementation should either:
         * (a) Add public accessors to OTelMobileHandle, or
         * (b) Create this shim inside OTelMobileBuilder.build() where
         *     the fields are still accessible.
         */
        fun create(
            openTelemetry: OpenTelemetry,
            sessionProvider: MobileSessionProvider
        ): OpenTelemetryRumCompat =
            OpenTelemetryRumCompat(openTelemetry, sessionProvider)
    }
}
```

This is intentionally thin. Full `OpenTelemetryRum` interface compatibility (if the upstream interface has more methods) can be expanded as needed.

---

## 6. Phase 4: Interface Convergence & iOS Update

### 6.1 Converge InstrumentationContext

```kotlin
// InstrumentationContext embeds InstallationContext as its core.
// Secondary constructor preserves backward compatibility with Phase 1-3 call sites.
class InstrumentationContext(
    val installationContext: InstallationContext,
    // Our extensions beyond upstream
    val windowEventHub: WindowEventHub,
    val uiTelemetryMode: UiTelemetryMode = UiTelemetryMode.EVENTS,
    val breadcrumbManager: BreadcrumbManager? = null
) {
    /** Backward-compatible constructor — creates InstallationContext internally. */
    constructor(
        openTelemetry: OpenTelemetry,
        sessionProvider: MobileSessionProvider,
        windowEventHub: WindowEventHub,
        application: Application,
        uiTelemetryMode: UiTelemetryMode = UiTelemetryMode.EVENTS,
        breadcrumbManager: BreadcrumbManager? = null,
        clock: Clock? = null
    ) : this(
        installationContext = InstallationContext(
            context = application,
            openTelemetry = openTelemetry,
            sessionProvider = sessionProvider,
            clock = clock ?: Clock.getDefault()
        ),
        windowEventHub = windowEventHub,
        uiTelemetryMode = uiTelemetryMode,
        breadcrumbManager = breadcrumbManager
    )
    // Delegated accessors for upstream fields
    val openTelemetry: OpenTelemetry
        get() = installationContext.openTelemetry
    val application: Application
        get() = installationContext.application
            ?: throw IllegalStateException(
                "InstallationContext.context must be an Application"
            )
    val sessionProvider: MobileSessionProvider
        get() = installationContext.sessionProvider as? MobileSessionProvider
            ?: UpstreamSessionProviderAdapter(installationContext.sessionProvider)
    val clock: Clock
        get() = installationContext.clock

    // Convenience accessors (unchanged)
    fun tracer(scope: String): Tracer = openTelemetry.getTracer(scope)
    fun logger(scope: String): Logger = openTelemetry.logsBridge.get(scope)
    fun meter(scope: String): Meter = openTelemetry.getMeter(scope)
    fun addBreadcrumb(breadcrumb: JourneyBreadcrumb) {
        breadcrumbManager?.add(breadcrumb)
    }
}
```

### 6.2 Converge MobileInstrumentation

```kotlin
interface MobileInstrumentation : AndroidInstrumentation {
    /** Our richer name -- delegates to upstream's name property. */
    val instrumentationName: String
    override val name: String get() = instrumentationName

    /** Our version field -- not present in upstream. */
    val instrumentationVersion: String get() = "1.0.0"

    /** Our install signature -- takes Application + InstrumentationContext. */
    fun install(application: Application, context: InstrumentationContext)

    /** Our no-arg uninstall. */
    fun uninstall() {}

    // Bridge to upstream interface -- default implementations
    override fun install(ctx: InstallationContext) {
        // Create a minimal InstrumentationContext from upstream's context.
        // Modules that need WindowEventHub (tap, scroll, etc.) should check
        // for a no-op hub and gracefully skip gesture detection.
        val app = ctx.application
            ?: (ctx.context as? Application)
            ?: return  // Cannot install without Application context
        val mobileCtx = InstrumentationContext(
            installationContext = ctx,
            windowEventHub = WindowEventHub(),  // no-op hub (no HubInstaller wired)
        )
        install(app, mobileCtx)
    }

    override fun uninstall(ctx: InstallationContext) = uninstall()
}
```

### 6.3 Module Updates

All 20+ existing modules: change `install(application: Application, context: InstrumentationContext)` to use the converged `InstrumentationContext`. The backward-compatible secondary constructor ensures existing call sites in `OTelMobileBuilder`, adapters, and tests continue to compile without changes. Only the primary constructor signature changes — all field access remains identical via delegated properties.

### 6.3.1 ServiceLoader Registration

After convergence, our modules implement both `MobileInstrumentation` and `AndroidInstrumentation` (via inheritance). Each module must register in BOTH service files for full discoverability:
- `META-INF/services/io.opentelemetry.android.mobile.instrumentation.MobileInstrumentation` (for our `discoverInstrumentations()`)
- `META-INF/services/io.opentelemetry.android.instrumentation.AndroidInstrumentation` (for upstream's loader and our `discoverUpstreamInstrumentations()`)

Alternatively, `discoverAllInstrumentations()` can be updated to deduplicate: if a module appears in both service files, only add it once. Prefer registering in both files for maximum compatibility.

### 6.4 Remove Adapters

With `MobileInstrumentation extends AndroidInstrumentation`:
- `MobileInstrumentationAdapter` is no longer needed (our modules ARE `AndroidInstrumentation`)
- `UpstreamInstrumentationAdapter` still needed for pure upstream modules that don't extend `MobileInstrumentation`

### 6.5 iOS Port Spec Update

Update `docs/superpowers/specs/2026-04-08-ios-sdk-port-design.md` section 5:
- `MobileInstrumentation` protocol gains `@Supersedes` equivalent
- `InstrumentationContext` carries `Clock`
- Document that iOS modules follow the same supersession pattern (future: when `opentelemetry-swift` has an equivalent of `AndroidInstrumentation`, the iOS SDK mirrors this adapter approach)
- Add Compose equivalent note: SwiftUI instrumentation follows similar pattern to Android Compose instrumentation

---

## 7. Module Inventory & Supersession Map

### Complete Module Map After All Phases

| Module | Source | @Supersedes | Signal Type |
|--------|--------|-------------|-------------|
| **TapInstrumentation** | Ours | `view.click` | Logs/Spans |
| **ComposeClickInstrumentation** | New (Phase 2a) | `compose.click` | Logs/Spans |
| **ScrollInstrumentation** | Ours | -- | Spans |
| **TextInputInstrumentation** | Ours | -- | Spans |
| **BackPressInstrumentation** | Ours | -- | Spans |
| **ScreenViewInstrumentation** | Ours | -- | Logs/Spans |
| **LifecycleInstrumentation** | Ours | `activity`, `fragment` | Logs |
| **ErrorInstrumentation** | Ours | `crash` | Logs/Spans |
| **FreezeInstrumentation** | Ours | `slowrendering` | Logs |
| **VitalsInstrumentation** | Ours | `anr`, `startup` | Metrics |
| **NetworkInstrumentation** | Ours | `okhttp` | Spans |
| **ScreenOrientationInstrumentation** | New (Phase 2a) | `screen_orientation` | Logs |
| **WebSocketInstrumentation** | New (Phase 2b) | `okhttp-websocket` | Spans |
| **AndroidLogInstrumentation** | New (Phase 2b, P2) | -- | Logs |
| **ScreenshotInstrumentation** | Ours (incubating) | -- | Logs |
| **WireframeInstrumentation** | Ours (incubating) | -- | Logs |
| **DatabaseInstrumentation** | Ours | -- | Spans |
| **FileIOInstrumentation** | Ours | -- | Spans |
| **SystemEventsInstrumentation** | Ours | -- | Logs |
| **TimberInstrumentation** | Ours | -- | Logs |
| **Upstream session** | Via adapter | -- | Events |
| **Upstream network** (connectivity) | Via adapter | -- | Events |

**Total: 20 owned modules + 2 upstream pass-through = 22 instrumentation modules**

**Overlap decisions for pass-through modules:**

- **Upstream `session`** (name: `"session"`): Pass through — emits session lifecycle events. Our `SessionManager` handles session state internally but doesn't emit the same events. Additive, no conflict.
- **Upstream `network`** (name: `"network"`): Pass through — emits connectivity change events (wifi/cellular/none). Our `SystemEventsInstrumentation` covers battery/power/storage but NOT connectivity changes. Additive, no conflict. If we later add connectivity to `SystemEventsInstrumentation`, add `@Supersedes("network")` at that time.

### Capabilities We Have That Upstream Lacks

- Policy DSL engine with 21 matchers and 10 actions
- Dual-tier buffering (RAM + SQLite) with crash recovery
- Conditional/Hybrid export modes (battery-efficient)
- Selective time-window flush
- UiTelemetryMode (EVENTS/SPANS/BOTH)
- WindowEventHub centralized event dispatch
- Scroll, text-input, back-press, freeze detection
- Screenshot + wireframe capture (session replay)
- Database + file I/O + system events instrumentation
- Breadcrumb/journey span hierarchy
- Predictive export (device health monitoring leading to pre-emptive flush)
- Visual control plane (sister repo)

---

## 8. Backlog & Roadmap Integration

### New Track in BACKLOG.md

Add as Track 7:

```
## Track 7: Upstream Supersession

Epic: [UPSTREAM_SUPERSESSION_EPIC.md](docs/epics/UPSTREAM_SUPERSESSION_EPIC.md)

### Phase 1 -- Foundation (P0)
- [ ] Remove phantom dep `instrumentation:0.4.0-alpha`
- [ ] Add `session:0.10.0-alpha` + `instrumentation-android-instrumentation:0.10.0-alpha`
- [ ] Update semconv to 1.40.0
- [ ] `MobileSessionProvider extends SessionProvider`
- [ ] `UpstreamSessionProviderAdapter`
- [ ] `UpstreamInstrumentationAdapter`
- [ ] `MobileInstrumentationAdapter`
- [ ] `@Supersedes` annotation + conflict resolution in `InstrumentationRegistry`
- [ ] `discoverUpstreamInstrumentations()` + `discoverAllInstrumentations()` on builder
- [ ] Optional `Clock` field on `InstrumentationContext`
- [ ] Phase 1 test suite (7 test classes)
- [ ] iOS spec stale notice on section 5

### Phase 2a -- New Modules (P1)
- [ ] `ComposeClickInstrumentation` + `ComposeClickConfig`
- [ ] `ScreenOrientationInstrumentation`
- [ ] Tests for both modules
- [ ] Add to `otel-android-mobile/build.gradle.kts` dependencies
- [ ] Add to demo app configuration

### Phase 2b -- Reimplemented Modules (P2)
- [ ] `WebSocketInstrumentation` + `OTelWebSocketListener`
- [ ] `AndroidLogInstrumentation` (optional, P2)
- [ ] Tests for WebSocket module

### Phase 3 -- API Surface Parity (P1)
- [ ] Kotlin DSL configuration (`mobileOtel { }` entry point)
- [ ] `@MobileOtelDsl` marker + DSL classes
- [ ] Exporter customizer chain (log, span, metric)
- [ ] `OpenTelemetryRumCompat` shim
- [ ] Tests for DSL, customizers, compat shim

### Phase 4 -- Interface Convergence (P1, after Phases 1-3)
- [ ] Converge `InstrumentationContext` to embed `InstallationContext`
- [ ] Converge `MobileInstrumentation extends AndroidInstrumentation`
- [ ] Update all 20+ modules to converged interface
- [ ] Remove `MobileInstrumentationAdapter` (no longer needed)
- [ ] Update iOS port spec section 5
- [ ] Full regression test pass
```

### Dependencies & Ordering

```
Phase 1 (Foundation)
    |
    +---> Phase 2a (Compose + Screen Orientation)  --+
    +---> Phase 2b (WebSocket + Android Log)         +---> Phase 4 (Convergence)
    +---> Phase 3 (DSL + Customizers + Compat)     --+
```

Phases 2a, 2b, and 3 can run in parallel after Phase 1 completes.
Phase 4 requires all prior phases to be complete.

---

## 9. Success Criteria

| Criterion | How to Verify |
|-----------|--------------|
| Upstream's published instrumentation modules run unmodified in our framework | Integration test: load `instrumentation-crash`, `instrumentation-anr` etc. via adapter, verify telemetry emitted |
| `@Supersedes` correctly prevents duplicate telemetry | Test: both `ErrorInstrumentation` and upstream `crash` on classpath, only `ErrorInstrumentation` installs |
| Our modules pass upstream's interface contract | Test: wrap each module in `MobileInstrumentationAdapter`, call `install(InstallationContext)` successfully |
| Compose clicks produce `ui.tap` events with composable identity | Espresso/Compose test: tap a Compose Button, verify `ui.element.framework=compose` + `ui.element.test_tag` |
| Kotlin DSL produces identical runtime behavior to builder | Test: configure via DSL and builder with same values, verify identical `MobileConfig` |
| Exporter customizers are called in correct order | Test: register 3 customizers, verify wrapping order |
| Zero regressions in existing 194+ behavioral config tests | `./gradlew :otel-android-mobile:testDebugUnitTest` passes |
| iOS spec updated with converged interface (Phase 4) | iOS spec section 5 reflects `MobileInstrumentation : AndroidInstrumentation` |

---

## 10. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| Upstream changes `AndroidInstrumentation` interface in a future release | Adapter breaks | Pin upstream deps with exact version. Monitor upstream releases. Adapter is thin enough to update quickly. |
| OTel SDK version conflict between our `1.58.0` and upstream's BOM-managed version | Compile errors or runtime class mismatches | Gradle resolves to highest version (ours). OTel API is backward-compatible within 1.x. CI tests catch issues. |
| ByteBuddy-instrumented upstream modules (okhttp3 agent, httpurlconnection agent) pulled transitively | Unwanted build plugin dependency | We only depend on `instrumentation-android-instrumentation` (the interface), not on agent modules. Users must explicitly add agent modules if they want ByteBuddy instrumentation. |
| Compose UI detection fails on obfuscated/minified builds | `testTag` not available in release builds | Fall back to `contentDescription` and `Role`. Document that `testTag` requires `debugImplementation` or `isMinifyEnabled = false` for that module. |
| Phase 4 convergence breaks all 20+ modules simultaneously | Large blast radius | Phase 4 is a compile-time-only change (field access via delegation). Automated refactor, full test suite as safety net. Can be done module-by-module if needed. |
| Upstream community rejects merge proposal | Wasted alignment effort | Alignment effort is not wasted -- compatible superset means our SDK works with their ecosystem regardless. The adapter and `@Supersedes` pattern have standalone value. |
