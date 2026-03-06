# Mobile Instrumentation Registry — Design

**Date:** 2026-03-06
**Status:** Approved, pending implementation
**Goal:** Refactor the monolithic `AutoCaptureManager` into an OTel contrib-aligned instrumentation registry with per-capability Gradle modules, a pluggable session provider, and SPI-based discovery — suitable for acceptance into `opentelemetry-android` or the OTel contrib ecosystem.

---

## 1. Motivation

The existing `otel-android-mobile` SDK bundles all capture logic (taps, scrolls, lifecycle, errors, network, vitals, freeze detection) into a single `AutoCaptureManager` configured via a flat `AutoCaptureOptions` data class. This design:

- Cannot be adopted piecemeal — users get everything or nothing
- Is untestable in isolation — modules cannot be unit-tested independently
- Does not follow OTel contrib conventions (`AndroidInstrumentation` interface, SPI, builder API)
- Cannot accept third-party instrumentation extensions
- Will not be accepted by the OTel community as-is

This design replaces that structure with a per-module, interface-driven registry that matches the patterns established by `opentelemetry-android` and `opentelemetry-java-instrumentation`.

---

## 2. Module Structure

```
otel-android-mobile-core/          Core interfaces and registry. Zero instrumentation logic.
                                    Required by all instrumentation modules.

instrumentation/
  lifecycle/                        app.start, app.foreground, app.background logs
  screen/                           ui.screen_view logs + page span + fragment tracking
  tap/                              ui.tap, ui.long_press, ui.swipe (owns WindowCallback wrapping)
  scroll/                           ui.scroll events
  text-input/                       ui.text_input (EditText focus-leave)
  back-press/                       ui.back_press
  freeze/                           freeze + ANR detection + recovery tracking
  errors/                           uncaught exceptions, coroutines, RxJava (refactors existing)
  network/                          OkHttp interceptor (refactors existing)
  vitals/                           jank, app-start timing, memory pressure (refactors existing)

otel-android-mobile/                Convenience aggregator. Depends on core + all modules.
                                    Preserves OTelMobile.start() for zero-config drop-in.
                                    Backward-compatible API surface.
```

The `tap/`, `scroll/`, `text-input/`, and `back-press/` modules all require `Window.Callback` access. A `WindowEventHub` in `core` coordinates this: the `InstrumentationRegistry` creates one `WindowCallbackWrapper` per window and fans events to registered `WindowEventListener` instances. No instrumentation module directly wraps a window.

---

## 3. Core Interfaces

### 3.1 `MobileInstrumentation`

The contract every instrumentation module implements. Mirrors `AndroidInstrumentation` from `opentelemetry-android`, extended with identity fields and an `InstrumentationContext` carrier.

```kotlin
// package: io.opentelemetry.android.mobile.instrumentation

interface MobileInstrumentation {
    val instrumentationName: String       // e.g. "io.opentelemetry.android.mobile.tap"
    val instrumentationVersion: String    // e.g. "1.0.0"

    fun install(application: Application, context: InstrumentationContext)
    fun uninstall() {}
}
```

### 3.2 `MobileSessionProvider`

Swappable session management. Default implementation matches current `SessionTracker` behavior. Apps with existing session infrastructure provide their own.

```kotlin
interface MobileSessionProvider {
    fun getSessionId(): String
    fun getViewId(): String
    fun onScreenView(screenName: String)
    fun onAppForeground(timestampMs: Long): Boolean   // returns true if session renewed
    fun onAppBackground(timestampMs: Long)
}
```

Follows the OTel "provider" pattern (`IdGenerator`, `Sampler`, `TextMapPropagator` are all swappable interfaces). Aligns with the in-progress OTel session semantic conventions spec.

### 3.3 `InstrumentationContext`

Passed to every instrumentation at `install()` time. Carries all shared state. Instrumentations must not hold strong references beyond `uninstall()`.

```kotlin
class InstrumentationContext(
    val openTelemetry: OpenTelemetry,
    val sessionProvider: MobileSessionProvider,
    val windowEventHub: WindowEventHub,
    val application: Application
) {
    fun tracer(scope: String): Tracer = openTelemetry.getTracer(scope)
    fun logger(scope: String): Logger = openTelemetry.logsBridge.get(scope)
    fun meter(scope: String): Meter  = openTelemetry.getMeter(scope)
}
```

### 3.4 `WindowEventHub`

Coordinates window callback access. One `WindowCallbackWrapper` per window; all UI instrumentations register as `WindowEventListener` instances.

```kotlin
interface WindowEventListener {
    fun onTouchEvent(event: MotionEvent, window: Window) {}
    fun onKeyEvent(event: KeyEvent, window: Window) {}
}

class WindowEventHub {
    fun addListener(listener: WindowEventListener)
    fun removeListener(listener: WindowEventListener)
    // internal: InstrumentationRegistry calls onWindowAvailable(window) on activity resume
}
```

### 3.5 `InstrumentationRegistry`

Owned by `OTelMobileHandle`. Holds all installed instrumentations. Registers one `Application.ActivityLifecycleCallbacks` that calls `windowEventHub.onWindowAvailable(window)` on each `onActivityResumed`.

```kotlin
class InstrumentationRegistry(
    private val instrumentations: List<MobileInstrumentation>
) {
    fun install(application: Application, context: InstrumentationContext)
    fun uninstall()
}
```

---

## 4. Builder API

### 4.1 `OTelMobile` (factory — no instance state)

```kotlin
object OTelMobile {
    // Zero-config: SPI discovers all instrumentation modules on classpath
    fun start(application: Application, config: MobileConfig): OTelMobileHandle

    // Explicit: add only what you need
    fun builder(application: Application, config: MobileConfig): OTelMobileBuilder
}
```

`OTelMobile` holds **no state**. `OTelMobileHandle` owns everything. Apps that need global access store the handle themselves (or in their `Application` subclass).

### 4.2 `OTelMobileBuilder`

```kotlin
class OTelMobileBuilder(application: Application, config: MobileConfig) {
    fun setSessionProvider(provider: MobileSessionProvider): OTelMobileBuilder
    fun addInstrumentation(i: MobileInstrumentation): OTelMobileBuilder
    fun discoverInstrumentations(): OTelMobileBuilder   // ServiceLoader SPI
    fun build(): OTelMobileHandle
}
```

`discoverInstrumentations()` loads via `ServiceLoader.load(MobileInstrumentation::class.java)`. Each instrumentation module ships a `META-INF/services/io.opentelemetry.android.mobile.instrumentation.MobileInstrumentation` file.

### 4.3 `OTelMobileHandle`

```kotlin
class OTelMobileHandle internal constructor(...) {
    fun getTracer(scope: String): Tracer
    fun getLogger(scope: String): Logger
    fun getMeter(scope: String): Meter
    fun stop(timeoutSeconds: Long = 30)
}
```

Replaces the static fields currently in `OTelMobile` and `MobileOtel`.

---

## 5. Per-Instrumentation Config

Each module ships a typed config data class — no more flat `AutoCaptureOptions`.

```kotlin
// instrumentation/tap
data class TapConfig(
    val captureLongPress: Boolean = true,
    val captureSwipe: Boolean = true,
    val swipeMinDistancePx: Float = 50f,
    val coalesceWindowMs: Long = 800,
    val privacyMode: PrivacyMode = PrivacyMode.STRICT,
    val allowlistedResourceIds: Set<String> = emptySet(),
    val denylistedResourceIds: Set<String> = emptySet()
)
TapInstrumentation(TapConfig(...))

// instrumentation/scroll
data class ScrollConfig(val throttleMs: Long = 500)
ScrollInstrumentation(ScrollConfig())

// instrumentation/freeze
data class FreezeConfig(
    val freezeThresholdMs: Long = 2000,
    val cooldownMs: Long = 30_000,
    val anrThresholdMs: Long = 5000
)
FreezeInstrumentation(FreezeConfig())
```

---

## 6. Lifecycle and Data Flow

```
Application.onCreate()
    ▼
OTelMobile.builder(app, config)
    .setSessionProvider(DefaultMobileSessionProvider())
    .addInstrumentation(LifecycleInstrumentation())
    .addInstrumentation(ScreenViewInstrumentation())
    .addInstrumentation(TapInstrumentation(TapConfig(...)))
    .addInstrumentation(ScrollInstrumentation())
    .addInstrumentation(FreezeInstrumentation())
    .discoverInstrumentations()   // SPI picks up remaining modules
    .build()
    ▼
OTelMobileBuilder.build()
    1. Initializes OpenTelemetry SDK (OTLP exporter, ring buffer processor)
    2. Creates MobileSessionProvider (default or supplied)
    3. Creates WindowEventHub
    4. Creates InstrumentationContext
    5. Creates InstrumentationRegistry
    6. registry.install(app, context)
       └─ each instrumentation.install(app, context)
          └─ UI instrumentations register as WindowEventListeners
    7. Returns OTelMobileHandle
    ▼
Activity.onResume → InstrumentationRegistry → windowEventHub.onWindowAvailable(window)
    └─ wraps Window.Callback once per window
    └─ dispatches MotionEvent/KeyEvent to all WindowEventListeners
    ▼
handle.stop()
    └─ registry.uninstall() → each instrumentation.uninstall()
    └─ SDK flush + shutdown
```

---

## 7. Semantic Conventions

A `semconv` package in `core` centralizes all OTel attribute keys and event name strings as constants. Required for OTel PR review. Names align with the OTel Mobile Semconv SIG working group output.

```kotlin
object MobileSemconv {
    val UI_TAP           = "ui.tap"
    val UI_LONG_PRESS    = "ui.long_press"
    val UI_SWIPE         = "ui.swipe"
    val UI_SCROLL        = "ui.scroll"
    val UI_TEXT_INPUT    = "ui.text_input"
    val UI_BACK_PRESS    = "ui.back_press"
    val UI_SCREEN_VIEW   = "ui.screen_view"
    val APP_START        = "app.start"
    val APP_FOREGROUND   = "app.foreground"
    val APP_BACKGROUND   = "app.background"

    val SCREEN_NAME      = AttributeKey.stringKey("screen.name")
    val SESSION_ID       = AttributeKey.stringKey("session.id")
    val VIEW_ID          = AttributeKey.stringKey("view.id")
    val UI_ELEMENT_ID    = AttributeKey.stringKey("ui.element.resource_id")
    val SWIPE_DIRECTION  = AttributeKey.stringKey("ui.swipe.direction")
}
```

---

## 8. Backward Compatibility

- `OTelMobile.start(application, config)` continues to work, calling `builder(...).discoverInstrumentations().build()` internally. Stores handle in a companion property for `getTracer()`/`getLogger()` convenience accessors.
- `MobileOtel.sendEvent()` and `MobileOtel.reportError()` delegate to the handle.
- `AutoCaptureOptions` is deprecated (not deleted) with migration notes pointing to per-instrumentation configs.
- Existing demo app (`BookFragment`, `AppointmentsFragment`) requires no changes.

---

## 9. OTel Contrib Acceptance Criteria

| Criterion | Status |
|-----------|--------|
| `MobileInstrumentation` interface matches `AndroidInstrumentation` pattern | ✓ design |
| No global state in SDK — all state in `OTelMobileHandle` | ✓ design |
| SPI via `META-INF/services` for zero-code discovery | ✓ design |
| Typed per-instrumentation config (no flat blob) | ✓ design |
| `MobileSessionProvider` swappable (OTel provider pattern) | ✓ design |
| Semconv constants package | ✓ design |
| Each module independently unit-testable | ✓ design |
| `@Incubating` annotations on new public API | to-do in impl |
| Gradle module per capability | ✓ design |
| Java interop (interfaces usable from Java) | ✓ Kotlin interfaces compile to Java interfaces |

---

## 10. Files Changed / Created (summary for implementation)

**New modules (new directories + `build.gradle.kts`):**
- `otel-android-mobile-core/`
- `instrumentation/lifecycle/`, `screen/`, `tap/`, `scroll/`, `text-input/`, `back-press/`, `freeze/`, `errors/`, `network/`, `vitals/`

**Modified:**
- `examples/demo-app/settings.gradle.kts` — include all new modules
- `otel-android-mobile/` — becomes aggregator, all internal classes moved to appropriate modules
- `OTelMobile.kt` — factory only, delegates to builder
- `MobileOtel.kt` — delegates to handle

**Deleted (or deprecated):**
- `AutoCaptureManager.kt` — replaced by `InstrumentationRegistry` + individual instrumentation classes
- `AutoCaptureOptions.kt` — replaced by per-instrumentation config classes
