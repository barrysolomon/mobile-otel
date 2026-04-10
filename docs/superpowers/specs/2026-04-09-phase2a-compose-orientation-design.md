# Phase 2a: Compose Click & Screen Orientation — Design Specification

**Date:** 2026-04-09
**Status:** Approved
**Scope:** Two new instrumentation modules filling the highest-value gaps identified in the Upstream Supersession epic.
**Parent Epic:** `docs/epics/UPSTREAM_SUPERSESSION_EPIC.md` (Phase 2a, US-014 through US-018)

---

## 1. ComposeClickInstrumentation

### Module Structure

**Gradle module:** `instrumentation/compose-click/`
**Package:** `io.opentelemetry.android.mobile.instrumentation`
**Class:** `ComposeClickInstrumentation`

### Dependencies

```kotlin
// instrumentation/compose-click/build.gradle.kts
compileOnly(platform("androidx.compose:compose-bom:2024.12.01"))
compileOnly("androidx.compose.ui:ui")

testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
testImplementation("androidx.compose.ui:ui-test-junit4")
testImplementation("androidx.compose.material3:material3")
```

The Compose dependency is `compileOnly` so apps without Compose are not forced to pull it in. At runtime, a reflection guard prevents `NoClassDefFoundError`.

### Class Loading Safety

All Compose-referencing code lives in an internal class (`ComposeClickDetector`) that is only loaded after the reflection guard passes:

```kotlin
override fun install(application: Application, context: InstrumentationContext) {
    try {
        Class.forName("androidx.compose.ui.platform.AndroidComposeView")
        // Safe to reference Compose classes now
        ComposeClickDetector(config, context).install(application)
    } catch (e: ClassNotFoundException) {
        Log.i(TAG, "Compose not on classpath -- skipping compose click instrumentation")
    }
}
```

### Touch Detection Flow

1. `ComposeClickDetector` registers `ActivityLifecycleCallbacks` on the application
2. In `onActivityResumed` (not `onActivityCreated` — must wrap AFTER `WindowEventHubInstaller` which wraps in `onActivityCreated`, so ComposeClickDetector's wrapper is outermost and intercepts touches first), finds `AndroidComposeView` instances in the activity's view hierarchy
3. Wraps the activity's `Window.Callback` to intercept `dispatchTouchEvent`. The wrapping order ensures Compose resolves and sets `ComposeTapFlag` before `HubDispatcher` fans the event to `TapInstrumentation`
4. On `ACTION_UP`:
   a. Locate the `AndroidComposeView` that contains the touch coordinates
   b. Access `semanticsOwner` (internal Compose API) to get the semantics tree root
   c. Hit-test the tree at (x, y) to find the tapped `SemanticsNode`
   d. Extract: `testTag`, `contentDescription`, `role`, `onClick` presence
   e. If a clickable Compose target is found:
      - Set `ComposeTapFlag.markHandled()`
      - Emit `ui.tap` log event (and span if `UiTelemetryMode` includes spans)
   f. If no Compose target found at coordinates, do nothing (let `TapInstrumentation` handle it as a View tap)

### Fallback Path

If `semanticsOwner` access fails (internal API changed in a future Compose version):
- Emit `ui.tap` with `ui.element.framework=compose` but WITHOUT composable identity attributes
- Log a warning once: "Compose semantics API changed -- composable identity not available"
- The tap is still captured, just with less detail

### Attributes Emitted

```
ui.element.framework     = "compose"
ui.element.test_tag      = "checkoutButton"      (if testTag modifier present)
ui.element.content_description = "Checkout"       (if semantics contentDescription set)
ui.element.role          = "Button"               (if Role set: Button, Checkbox, Tab, etc.)
ui.element.has_click_action = true                (if onClick modifier present)
session.id               = <from sessionProvider>
view.id                  = <from sessionProvider>
screen.name              = <from sessionProvider>
```

### Dedup with TapInstrumentation

**Mechanism:** Shared `ComposeTapFlag` object in `otel-android-mobile-core` (avoids circular dependency between modules).

```kotlin
// In otel-android-mobile-core/.../instrumentation/ComposeTapFlag.kt
object ComposeTapFlag {
    /** Maximum age of a Compose-handled flag before it's considered stale. Default 500ms. */
    @JvmField var expiryNanos: Long = 500_000_000L

    @JvmField var handledAtNanos: Long = 0L

    fun markHandled() {
        handledAtNanos = System.nanoTime()
    }

    fun wasHandledRecently(): Boolean {
        val elapsed = System.nanoTime() - handledAtNanos
        return elapsed in 1..expiryNanos
    }
}
```

**ComposeClickInstrumentation** calls `ComposeTapFlag.markHandled()` when it successfully resolves and emits a Compose tap.

**TapInstrumentation** checks `ComposeTapFlag.wasHandledRecently()` before emitting on ACTION_UP. If true, skips emission.

**Why timestamp-based expiry:**
- If ACTION_DOWN never fires to reset a boolean flag (touch sequence interrupted, activity transition), a bare boolean would silently swallow all subsequent taps forever
- Timestamp auto-expires: stale flags from prior touch sequences are always older than 500ms
- Both handlers fire on the same ACTION_UP dispatch frame (~16ms), so 500ms is generous
- Configurable via `ComposeTapFlag.expiryNanos` for edge cases

**Thread safety:** Both modules run exclusively on the main thread (Android UI thread). No synchronization needed.

### Configuration

```kotlin
data class ComposeClickConfig(
    val enabled: Boolean = true,
    val captureTestTag: Boolean = true,
    val captureContentDescription: Boolean = true,
    val captureRole: Boolean = true
)
```

### Annotation

```kotlin
@Incubating
@Supersedes("compose.click")
class ComposeClickInstrumentation(
    private val config: ComposeClickConfig = ComposeClickConfig()
) : MobileInstrumentation { ... }
```

---

## 2. ScreenOrientationInstrumentation

### Module Structure

**Gradle module:** `instrumentation/screen-orientation/`
**Package:** `io.opentelemetry.android.mobile.instrumentation`
**Class:** `ScreenOrientationInstrumentation`

### Implementation

Uses `ComponentCallbacks2.onConfigurationChanged()` registered on the application context. Detects `Configuration.ORIENTATION_PORTRAIT` vs `ORIENTATION_LANDSCAPE` transitions.

```kotlin
@Incubating
@Supersedes("screen_orientation")
class ScreenOrientationInstrumentation : MobileInstrumentation {
    override val instrumentationName = "io.opentelemetry.android.mobile.screen-orientation"

    private var application: Application? = null
    private var callback: ComponentCallbacks2? = null
    private var lastOrientation: Int = Configuration.ORIENTATION_UNDEFINED

    override fun install(application: Application, context: InstrumentationContext) {
        this.application = application
        lastOrientation = application.resources.configuration.orientation
        val logger = context.logger(instrumentationName)
        val sp = context.sessionProvider

        callback = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) {
                val newOrientation = newConfig.orientation
                if (newOrientation != lastOrientation
                    && newOrientation != Configuration.ORIENTATION_UNDEFINED
                ) {
                    val prev = orientationName(lastOrientation)
                    val curr = orientationName(newOrientation)
                    lastOrientation = newOrientation

                    logger.logRecordBuilder()
                        .setBody("device.orientation")
                        .setSeverity(Severity.INFO)
                        .setAllAttributes(Attributes.builder()
                            .put("device.orientation", curr)
                            .put("device.orientation.previous", prev)
                            .put(MobileSemconv.SESSION_ID, sp.getSessionId())
                            .put(MobileSemconv.VIEW_ID, sp.getViewId())
                            .apply {
                                sp.getCurrentScreenName()?.let {
                                    put(MobileSemconv.SCREEN_NAME, it)
                                }
                            }
                            .build())
                        .emit()

                    context.addBreadcrumb(
                        JourneyBreadcrumb("orientation", mapOf("to" to curr))
                    )
                }
            }
            override fun onLowMemory() {}
            override fun onTrimMemory(level: Int) {}
        }
        application.registerComponentCallbacks(callback)
    }

    override fun uninstall() {
        callback?.let { application?.unregisterComponentCallbacks(it) }
        callback = null
        application = null
    }

    private fun orientationName(orientation: Int): String = when (orientation) {
        Configuration.ORIENTATION_PORTRAIT -> "portrait"
        Configuration.ORIENTATION_LANDSCAPE -> "landscape"
        else -> "unknown"
    }
}
```

No special dependencies beyond what `otel-android-mobile-core` already provides.

---

## 3. Shared: ComposeTapFlag

**Location:** `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/ComposeTapFlag.kt`

Lives in core so both `compose-click` and `tap` modules can reference it without depending on each other.

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

/**
 * Coordination flag between [ComposeClickInstrumentation] and [TapInstrumentation]
 * to prevent duplicate tap events when both are active on a screen that mixes
 * Compose and View-based UI.
 *
 * When the Compose module resolves a tap target, it calls [markHandled].
 * The Tap module checks [wasHandledRecently] before emitting -- if the Compose
 * module already handled this tap, the Tap module skips emission.
 *
 * Uses timestamp-based expiry as a failsafe: if the flag is not reset
 * (e.g., touch sequence interrupted), it auto-expires after [expiryNanos].
 *
 * Thread safety: both modules run on the main thread only. No synchronization needed.
 */
object ComposeTapFlag {
    /** Maximum age in nanoseconds before the flag is considered stale. Default 500ms. */
    @JvmField var expiryNanos: Long = 500_000_000L

    @JvmField var handledAtNanos: Long = 0L

    fun markHandled() {
        handledAtNanos = System.nanoTime()
    }

    fun wasHandledRecently(): Boolean {
        val elapsed = System.nanoTime() - handledAtNanos
        return elapsed in 1..expiryNanos
    }
}
```

---

## 4. TapInstrumentation Modification

One change to `instrumentation/tap/src/main/java/.../TapInstrumentation.kt`:

In `handleActionUp()`, add one line after the `longPressEmitted` check and before the `captureTaps` check (around line 155):

```kotlin
// Existing:
if (longPressEmitted) {
    longPressEmitted = false
    return
}

// ADD THIS LINE:
if (ComposeTapFlag.wasHandledRecently()) return

// Existing:
if (!config.captureTaps) return
```

This is a single line addition. If the Compose module handled this tap in the same dispatch frame, TapInstrumentation skips. No ACTION_DOWN reset needed — timestamp-based expiry handles stale flags automatically.

---

## 5. Build Integration

### New Gradle modules in examples/demo-app/settings.gradle.kts

```kotlin
include(":instrumentation-compose-click")
project(":instrumentation-compose-click").projectDir = file("../../instrumentation/compose-click")

include(":instrumentation-screen-orientation")
project(":instrumentation-screen-orientation").projectDir = file("../../instrumentation/screen-orientation")
```

### Wire into otel-android-mobile/build.gradle.kts

```kotlin
api(project(":instrumentation-compose-click"))
api(project(":instrumentation-screen-orientation"))
```

### META-INF/services SPI registration

Each new module gets:
`META-INF/services/io.opentelemetry.android.mobile.instrumentation.MobileInstrumentation`

containing the fully-qualified class name for ServiceLoader discovery.

---

## 6. Testing Strategy

| Module | Test Class | Approach |
|--------|-----------|----------|
| ComposeTapFlag | `ComposeTapFlagTest` | JUnit 4: verify `markHandled()` + `wasHandledRecently()`, verify expiry, verify configurable timeout |
| ScreenOrientation | `ScreenOrientationInstrumentationTest` | Robolectric: change configuration, verify log emitted with correct attributes, verify no emit on same orientation, verify uninstall |
| ComposeClick | `ComposeClickInstrumentationTest` | Robolectric + Compose test APIs: render Composable with testTag, simulate tap, verify attributes emitted. Also test: no-Compose fallback (reflection guard), semantics failure fallback |
| TapInstrumentation dedup | Add to existing `TapInstrumentationTest` | Set `ComposeTapFlag.markHandled()`, trigger ACTION_UP, verify no tap emitted |

---

## 7. What's NOT in Phase 2a

- Upstream demo app clone (Phase 5)
- Kotlin DSL config for new modules (Phase 3)
- Wiring into existing View-based demo app (no Compose there)
- WebSocket / Android Log modules (Phase 2b)
