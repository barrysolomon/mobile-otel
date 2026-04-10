# Phase 2a: Compose Click + Screen Orientation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two new instrumentation modules (Compose click detection + screen orientation tracking) and a shared dedup flag, filling the two highest-value gaps identified in the Upstream Supersession epic.

**Architecture:** Create `ComposeTapFlag` in core for dedup coordination, build `ScreenOrientationInstrumentation` as a simple `ComponentCallbacks2` listener, build `ComposeClickInstrumentation` with a reflection-guarded `ComposeClickDetector` that walks the Compose semantics tree. Add one-line dedup check to existing `TapInstrumentation`. TDD throughout.

**Tech Stack:** Kotlin, JUnit 4, MockK, Robolectric, OpenTelemetry SDK Testing, Gradle, Jetpack Compose (compileOnly)

**Design Spec:** `docs/superpowers/specs/2026-04-09-phase2a-compose-orientation-design.md`

---

## File Map

### New Files

| File | Responsibility |
|------|---------------|
| `otel-android-mobile-core/src/main/java/.../instrumentation/ComposeTapFlag.kt` | Timestamp-based dedup flag shared between Compose click and Tap modules |
| `otel-android-mobile-core/src/test/java/.../instrumentation/ComposeTapFlagTest.kt` | Tests for flag expiry, configurable timeout |
| `instrumentation/screen-orientation/build.gradle.kts` | Gradle build for screen orientation module |
| `instrumentation/screen-orientation/src/main/java/.../instrumentation/ScreenOrientationInstrumentation.kt` | Orientation change detection via ComponentCallbacks2 |
| `instrumentation/screen-orientation/src/main/resources/META-INF/services/io.opentelemetry.android.mobile.instrumentation.MobileInstrumentation` | SPI registration |
| `instrumentation/screen-orientation/src/test/java/.../instrumentation/ScreenOrientationInstrumentationTest.kt` | Tests for orientation detection |
| `instrumentation/compose-click/build.gradle.kts` | Gradle build with compileOnly Compose deps |
| `instrumentation/compose-click/src/main/java/.../instrumentation/ComposeClickInstrumentation.kt` | Entry point with reflection guard |
| `instrumentation/compose-click/src/main/java/.../instrumentation/ComposeClickDetector.kt` | Compose semantics tree walker + Window.Callback wrapper |
| `instrumentation/compose-click/src/main/java/.../instrumentation/ComposeClickConfig.kt` | Configuration data class |
| `instrumentation/compose-click/src/main/resources/META-INF/services/io.opentelemetry.android.mobile.instrumentation.MobileInstrumentation` | SPI registration |
| `instrumentation/compose-click/src/test/java/.../instrumentation/ComposeClickInstrumentationTest.kt` | Tests for reflection guard, Compose detection |

### Modified Files

| File | Change |
|------|--------|
| `examples/demo-app/settings.gradle.kts` | Add `:instrumentation-compose-click` and `:instrumentation-screen-orientation` |
| `otel-android-mobile/build.gradle.kts` | Add `api(project(...))` for both new modules |
| `instrumentation/tap/src/main/java/.../TapInstrumentation.kt:155` | Add `ComposeTapFlag.wasHandledRecently()` check |

**Base path for `...`:** `io/opentelemetry/android/mobile/` (under `src/main/java/` or `src/test/java/`)

**All commands run from:** `mobile-otel/examples/demo-app/`

---

### Task 1: ComposeTapFlag (shared dedup coordination)

**Files:**
- Create: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/ComposeTapFlag.kt`
- Create: `otel-android-mobile-core/src/test/java/io/opentelemetry/android/mobile/instrumentation/ComposeTapFlagTest.kt`

- [ ] **Step 1: Write failing tests**

Create `otel-android-mobile-core/src/test/java/io/opentelemetry/android/mobile/instrumentation/ComposeTapFlagTest.kt`:

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import org.junit.After
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposeTapFlagTest {

    @After fun resetFlag() {
        ComposeTapFlag.handledAtNanos = 0L
        ComposeTapFlag.expiryNanos = 500_000_000L
    }

    @Test fun `wasHandledRecently returns false when never marked`() {
        assertFalse(ComposeTapFlag.wasHandledRecently())
    }

    @Test fun `wasHandledRecently returns true immediately after markHandled`() {
        ComposeTapFlag.markHandled()
        assertTrue(ComposeTapFlag.wasHandledRecently())
    }

    @Test fun `wasHandledRecently returns false after expiry`() {
        ComposeTapFlag.expiryNanos = 1L // 1 nanosecond expiry
        ComposeTapFlag.markHandled()
        // Busy-wait past the 1ns expiry
        Thread.sleep(1)
        assertFalse(ComposeTapFlag.wasHandledRecently())
    }

    @Test fun `expiryNanos is configurable`() {
        ComposeTapFlag.expiryNanos = 10_000_000_000L // 10 seconds
        ComposeTapFlag.markHandled()
        Thread.sleep(50) // 50ms — well within 10s window
        assertTrue(ComposeTapFlag.wasHandledRecently())
    }

    @Test fun `default expiryNanos is 500ms`() {
        kotlin.test.assertEquals(500_000_000L, ComposeTapFlag.expiryNanos)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel/examples/demo-app" && ./gradlew :otel-android-mobile-core:testDebugUnitTest --tests "*.ComposeTapFlagTest"
```

Expected: FAIL — `ComposeTapFlag` does not exist yet.

- [ ] **Step 3: Implement ComposeTapFlag**

Create `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/ComposeTapFlag.kt`:

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

/**
 * Coordination flag between ComposeClickInstrumentation and TapInstrumentation
 * to prevent duplicate tap events when both are active on a screen that mixes
 * Compose and View-based UI.
 *
 * When the Compose module resolves a tap target, it calls [markHandled].
 * The Tap module checks [wasHandledRecently] before emitting — if the Compose
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

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel/examples/demo-app" && ./gradlew :otel-android-mobile-core:testDebugUnitTest --tests "*.ComposeTapFlagTest"
```

Expected: 5 tests PASS

---

### Task 2: TapInstrumentation dedup check

**Files:**
- Modify: `instrumentation/tap/src/main/java/io/opentelemetry/android/mobile/instrumentation/TapInstrumentation.kt:155`

- [ ] **Step 1: Add ComposeTapFlag check to handleActionUp**

In `TapInstrumentation.kt`, in the `handleActionUp()` method, add one line after the `longPressEmitted` block (after line 154) and before `if (!config.captureTaps)` (line 156):

```kotlin
        // Compose module already handled this tap — skip to avoid duplicate.
        if (ComposeTapFlag.wasHandledRecently()) return
```

The surrounding context should look like:

```kotlin
        // Long-press was already emitted by GestureDetector — don't also emit a tap.
        if (longPressEmitted) {
            longPressEmitted = false
            return
        }

        // Compose module already handled this tap — skip to avoid duplicate.
        if (ComposeTapFlag.wasHandledRecently()) return

        if (!config.captureTaps) return
```

- [ ] **Step 2: Verify existing tap tests still pass**

Run:
```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel/examples/demo-app" && ./gradlew :instrumentation-tap:testDebugUnitTest
```

Expected: All existing tests PASS — `ComposeTapFlag.wasHandledRecently()` returns `false` when never marked, so existing behavior is unchanged.

- [ ] **Step 3: Verify core tests still pass**

Run:
```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel/examples/demo-app" && ./gradlew :otel-android-mobile-core:testDebugUnitTest
```

Expected: All PASS (including new ComposeTapFlagTest from Task 1).

---

### Task 3: ScreenOrientationInstrumentation module

**Files:**
- Create: `instrumentation/screen-orientation/build.gradle.kts`
- Create: `instrumentation/screen-orientation/src/main/java/io/opentelemetry/android/mobile/instrumentation/ScreenOrientationInstrumentation.kt`
- Create: `instrumentation/screen-orientation/src/main/resources/META-INF/services/io.opentelemetry.android.mobile.instrumentation.MobileInstrumentation`
- Create: `instrumentation/screen-orientation/src/test/java/io/opentelemetry/android/mobile/instrumentation/ScreenOrientationInstrumentationTest.kt`
- Modify: `examples/demo-app/settings.gradle.kts`

- [ ] **Step 1: Create build.gradle.kts**

Create `instrumentation/screen-orientation/build.gradle.kts`:

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

plugins {
    id("com.android.library")
}

android {
    namespace = "io.opentelemetry.android.mobile.instrumentation.screenorientation"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        targetSdk = 36
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint { targetSdk = 36 }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    api(project(":otel-android-mobile-core"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.20")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.58.0")
}
```

- [ ] **Step 2: Register in settings.gradle.kts**

In `examples/demo-app/settings.gradle.kts`, add after the last `instrumentation-wireframe` entry:

```kotlin
include(":instrumentation-screen-orientation")
project(":instrumentation-screen-orientation").projectDir = file("../../instrumentation/screen-orientation")
```

- [ ] **Step 3: Create SPI registration file**

Create `instrumentation/screen-orientation/src/main/resources/META-INF/services/io.opentelemetry.android.mobile.instrumentation.MobileInstrumentation`:

```
io.opentelemetry.android.mobile.instrumentation.ScreenOrientationInstrumentation
```

- [ ] **Step 4: Write failing test**

Create `instrumentation/screen-orientation/src/test/java/io/opentelemetry/android/mobile/instrumentation/ScreenOrientationInstrumentationTest.kt`:

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import android.content.res.Configuration
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class ScreenOrientationInstrumentationTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private fun makeContext(app: Application): InstrumentationContext =
        InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), WindowEventHub(), app)

    @Test fun `instrumentationName is correct`() {
        val inst = ScreenOrientationInstrumentation()
        assertEquals("io.opentelemetry.android.mobile.screen-orientation", inst.instrumentationName)
    }

    @Test fun `install succeeds without throwing`() {
        val app = RuntimeEnvironment.getApplication()
        val inst = ScreenOrientationInstrumentation()
        inst.install(app, makeContext(app))
        // Should not throw
    }

    @Test fun `uninstall after install succeeds without throwing`() {
        val app = RuntimeEnvironment.getApplication()
        val inst = ScreenOrientationInstrumentation()
        inst.install(app, makeContext(app))
        inst.uninstall()
        // Should not throw
    }

    @Test fun `uninstall before install does not throw`() {
        val inst = ScreenOrientationInstrumentation()
        inst.uninstall()
        // Should not throw
    }
}
```

- [ ] **Step 5: Run test to verify it fails**

Run:
```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel/examples/demo-app" && ./gradlew :instrumentation-screen-orientation:testDebugUnitTest
```

Expected: FAIL — `ScreenOrientationInstrumentation` does not exist yet.

- [ ] **Step 6: Implement ScreenOrientationInstrumentation**

Create `instrumentation/screen-orientation/src/main/java/io/opentelemetry/android/mobile/instrumentation/ScreenOrientationInstrumentation.kt`:

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import io.opentelemetry.android.mobile.breadcrumb.JourneyBreadcrumb
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity

/**
 * Captures Android screen orientation changes as OTel log events.
 *
 * Registers a [ComponentCallbacks2] on the application context to detect
 * [Configuration.ORIENTATION_PORTRAIT] vs [Configuration.ORIENTATION_LANDSCAPE]
 * transitions. Each change emits a `device.orientation` log record with the
 * current and previous orientation, plus session context.
 */
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
                        .setAllAttributes(
                            Attributes.builder()
                                .put("device.orientation", curr)
                                .put("device.orientation.previous", prev)
                                .put(MobileSemconv.SESSION_ID, sp.getSessionId())
                                .put(MobileSemconv.VIEW_ID, sp.getViewId())
                                .apply {
                                    sp.getCurrentScreenName()?.let {
                                        put(MobileSemconv.SCREEN_NAME, it)
                                    }
                                }
                                .build()
                        )
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

- [ ] **Step 7: Run tests to verify they pass**

Run:
```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel/examples/demo-app" && ./gradlew :instrumentation-screen-orientation:testDebugUnitTest
```

Expected: 4 tests PASS

---

### Task 4: ComposeClickInstrumentation module scaffold

**Files:**
- Create: `instrumentation/compose-click/build.gradle.kts`
- Create: `instrumentation/compose-click/src/main/java/io/opentelemetry/android/mobile/instrumentation/ComposeClickConfig.kt`
- Create: `instrumentation/compose-click/src/main/java/io/opentelemetry/android/mobile/instrumentation/ComposeClickInstrumentation.kt`
- Create: `instrumentation/compose-click/src/main/java/io/opentelemetry/android/mobile/instrumentation/ComposeClickDetector.kt`
- Create: `instrumentation/compose-click/src/main/resources/META-INF/services/io.opentelemetry.android.mobile.instrumentation.MobileInstrumentation`
- Modify: `examples/demo-app/settings.gradle.kts`

- [ ] **Step 1: Create build.gradle.kts with compileOnly Compose**

Create `instrumentation/compose-click/build.gradle.kts`:

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

plugins {
    id("com.android.library")
}

android {
    namespace = "io.opentelemetry.android.mobile.instrumentation.composeclick"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        targetSdk = 36
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint { targetSdk = 36 }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    api(project(":otel-android-mobile-core"))

    // Compose — compileOnly so apps without Compose don't pull it in
    compileOnly(platform("androidx.compose:compose-bom:2024.12.01"))
    compileOnly("androidx.compose.ui:ui")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.20")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.58.0")

    // Compose test deps — full implementation for tests
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.material3:material3")
}
```

- [ ] **Step 2: Register in settings.gradle.kts**

In `examples/demo-app/settings.gradle.kts`, add after the screen-orientation entry:

```kotlin
include(":instrumentation-compose-click")
project(":instrumentation-compose-click").projectDir = file("../../instrumentation/compose-click")
```

- [ ] **Step 3: Create SPI registration file**

Create `instrumentation/compose-click/src/main/resources/META-INF/services/io.opentelemetry.android.mobile.instrumentation.MobileInstrumentation`:

```
io.opentelemetry.android.mobile.instrumentation.ComposeClickInstrumentation
```

- [ ] **Step 4: Create ComposeClickConfig**

Create `instrumentation/compose-click/src/main/java/io/opentelemetry/android/mobile/instrumentation/ComposeClickConfig.kt`:

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

/**
 * Configuration for [ComposeClickInstrumentation].
 *
 * @property enabled Whether to capture Compose click events. Default true.
 * @property captureTestTag Whether to include `testTag` in emitted attributes. Default true.
 * @property captureContentDescription Whether to include `contentDescription`. Default true.
 * @property captureRole Whether to include the Compose `Role` (Button, Checkbox, etc.). Default true.
 */
data class ComposeClickConfig(
    val enabled: Boolean = true,
    val captureTestTag: Boolean = true,
    val captureContentDescription: Boolean = true,
    val captureRole: Boolean = true
)
```

- [ ] **Step 5: Create ComposeClickInstrumentation (entry point with reflection guard)**

Create `instrumentation/compose-click/src/main/java/io/opentelemetry/android/mobile/instrumentation/ComposeClickInstrumentation.kt`:

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import android.util.Log

/**
 * Captures Jetpack Compose click events as OTel log events.
 *
 * Uses a reflection guard to check for Compose on the classpath before
 * loading any Compose-dependent code. If Compose is not available,
 * [install] is a no-op.
 *
 * When active, wraps each activity's [android.view.Window.Callback] to
 * intercept touch events. On ACTION_UP, walks the Compose semantics tree
 * to identify the tapped composable and emits a `ui.tap` event with
 * Compose-specific attributes (`ui.element.framework=compose`,
 * `ui.element.test_tag`, etc.).
 *
 * Coordinates with [TapInstrumentation] via [ComposeTapFlag] to prevent
 * duplicate tap events on screens that mix Compose and View-based UI.
 */
@Incubating
@Supersedes("compose.click")
class ComposeClickInstrumentation(
    private val config: ComposeClickConfig = ComposeClickConfig()
) : MobileInstrumentation {

    override val instrumentationName = "io.opentelemetry.android.mobile.compose.click"

    private var detector: ComposeClickDetector? = null

    override fun install(application: Application, context: InstrumentationContext) {
        if (!config.enabled) return
        try {
            Class.forName("androidx.compose.ui.platform.AndroidComposeView")
            val det = ComposeClickDetector(config, context)
            det.install(application)
            detector = det
        } catch (e: ClassNotFoundException) {
            Log.i(TAG, "Compose not on classpath -- skipping compose click instrumentation")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install compose click instrumentation", e)
        }
    }

    override fun uninstall() {
        detector?.uninstall()
        detector = null
    }

    companion object {
        private const val TAG = "ComposeClickInstr"
    }
}
```

- [ ] **Step 6: Create ComposeClickDetector (Compose semantics tree walker)**

Create `instrumentation/compose-click/src/main/java/io/opentelemetry/android/mobile/instrumentation/ComposeClickDetector.kt`:

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.compose.ui.platform.AndroidComposeView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity

/**
 * Internal Compose-dependent implementation of click detection.
 *
 * This class directly references Compose types and must only be loaded
 * after verifying Compose is on the classpath (see [ComposeClickInstrumentation]).
 *
 * Registers [Application.ActivityLifecycleCallbacks] in [onActivityResumed]
 * (not [onActivityCreated]) to ensure the [Window.Callback] wrapper is outermost
 * — after [WindowEventHubInstaller]'s [HubDispatcher] which wraps in [onActivityCreated].
 * This ordering ensures Compose resolves and sets [ComposeTapFlag] before
 * [TapInstrumentation] receives the event via the hub.
 */
internal class ComposeClickDetector(
    private val config: ComposeClickConfig,
    private val context: InstrumentationContext
) {
    private val logger: Logger = context.logger("io.opentelemetry.android.mobile.compose.click")
    private val sessionProvider = context.sessionProvider
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var application: Application? = null
    private var semanticsWarningLogged = false

    fun install(application: Application) {
        this.application = application
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                wrapWindowCallback(activity)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        lifecycleCallbacks = callbacks
        application.registerActivityLifecycleCallbacks(callbacks)
    }

    fun uninstall() {
        lifecycleCallbacks?.let { application?.unregisterActivityLifecycleCallbacks(it) }
        lifecycleCallbacks = null
        application = null
    }

    private fun wrapWindowCallback(activity: Activity) {
        val window = activity.window ?: return
        val currentCallback = window.callback ?: return

        // Don't double-wrap if already wrapped by us
        if (currentCallback is ComposeClickCallbackWrapper) return

        window.callback = ComposeClickCallbackWrapper(currentCallback, window)
    }

    /**
     * Window.Callback wrapper that intercepts dispatchTouchEvent to detect
     * Compose clicks before the event reaches [WindowEventHubInstaller]'s
     * [HubDispatcher].
     */
    private inner class ComposeClickCallbackWrapper(
        private val delegate: Window.Callback,
        private val window: Window
    ) : Window.Callback by delegate {

        override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
            if (event != null && event.actionMasked == MotionEvent.ACTION_UP) {
                handleComposeClick(event, window)
            }
            return delegate.dispatchTouchEvent(event)
        }
    }

    private fun handleComposeClick(event: MotionEvent, window: Window) {
        val composeView = findComposeView(window) ?: return
        val x = event.rawX
        val y = event.rawY

        try {
            // Access the semantics owner via reflection on AndroidComposeView
            val semanticsOwnerField = AndroidComposeView::class.java
                .getDeclaredField("semanticsOwner")
            semanticsOwnerField.isAccessible = true
            val semanticsOwner = semanticsOwnerField.get(composeView) ?: return

            // Get the root semantics node
            val getRootMethod = semanticsOwner::class.java.getMethod("getUnmergedRootSemanticsNode")
            val rootNode = getRootMethod.invoke(semanticsOwner) ?: return

            // Find the node at the tap coordinates
            val node = findNodeAtPosition(rootNode, x, y) ?: return

            // Extract semantics properties
            val semanticsConfig = node::class.java.getMethod("getConfig").invoke(node) ?: return
            val hasClickAction = hasAction(semanticsConfig, SemanticsActions.OnClick)

            if (!hasClickAction) return // Not a clickable composable

            // Build attributes
            val attrs = Attributes.builder()
                .put("ui.element.framework", "compose")
                .put("ui.element.has_click_action", true)
                .put(MobileSemconv.SESSION_ID, sessionProvider.getSessionId())
                .put(MobileSemconv.VIEW_ID, sessionProvider.getViewId())

            if (config.captureTestTag) {
                getStringProperty(semanticsConfig, SemanticsProperties.TestTag)?.let {
                    attrs.put("ui.element.test_tag", it)
                }
            }
            if (config.captureContentDescription) {
                getStringListProperty(semanticsConfig, SemanticsProperties.ContentDescription)
                    ?.firstOrNull()?.let {
                        attrs.put("ui.element.content_description", it)
                    }
            }
            if (config.captureRole) {
                getRoleProperty(semanticsConfig)?.let {
                    attrs.put("ui.element.role", it)
                }
            }

            sessionProvider.getCurrentScreenName()?.let {
                attrs.put(MobileSemconv.SCREEN_NAME, it)
            }

            // Mark as handled BEFORE emitting — TapInstrumentation checks this flag
            ComposeTapFlag.markHandled()

            logger.logRecordBuilder()
                .setBody("ui.tap")
                .setSeverity(Severity.INFO)
                .setAllAttributes(attrs.build())
                .emit()

        } catch (e: Exception) {
            // Fallback: emit tap without composable identity
            if (!semanticsWarningLogged) {
                Log.w(TAG, "Compose semantics API changed -- composable identity not available", e)
                semanticsWarningLogged = true
            }
            ComposeTapFlag.markHandled()
            logger.logRecordBuilder()
                .setBody("ui.tap")
                .setSeverity(Severity.INFO)
                .setAllAttributes(
                    Attributes.builder()
                        .put("ui.element.framework", "compose")
                        .put(MobileSemconv.SESSION_ID, sessionProvider.getSessionId())
                        .put(MobileSemconv.VIEW_ID, sessionProvider.getViewId())
                        .apply {
                            sessionProvider.getCurrentScreenName()?.let {
                                put(MobileSemconv.SCREEN_NAME, it)
                            }
                        }
                        .build()
                )
                .emit()
        }
    }

    private fun findComposeView(window: Window): AndroidComposeView? {
        val rootView = window.decorView ?: return null
        return findComposeViewInHierarchy(rootView)
    }

    private fun findComposeViewInHierarchy(view: View): AndroidComposeView? {
        if (view is AndroidComposeView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findComposeViewInHierarchy(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun findNodeAtPosition(rootNode: Any, x: Float, y: Float): Any? {
        // Use reflection to traverse the semantics tree
        try {
            val childrenMethod = rootNode::class.java.getMethod("getChildren")
            val children = childrenMethod.invoke(rootNode) as? Iterable<*> ?: return null

            // DFS — find the deepest clickable node containing (x, y)
            var best: Any? = null
            for (child in children) {
                if (child == null) continue
                val nodeAtPos = findNodeAtPosition(child, x, y)
                if (nodeAtPos != null) best = nodeAtPos
            }
            if (best != null) return best

            // Check if this node itself contains the position and is clickable
            if (nodeContainsPosition(rootNode, x, y)) return rootNode
        } catch (e: Exception) {
            // Reflection failure — return null to trigger fallback
        }
        return null
    }

    private fun nodeContainsPosition(node: Any, x: Float, y: Float): Boolean {
        return try {
            val boundsMethod = node::class.java.getMethod("getBoundsInWindow")
            val bounds = boundsMethod.invoke(node) ?: return false
            val left = bounds::class.java.getMethod("getLeft").invoke(bounds) as Float
            val top = bounds::class.java.getMethod("getTop").invoke(bounds) as Float
            val right = bounds::class.java.getMethod("getRight").invoke(bounds) as Float
            val bottom = bounds::class.java.getMethod("getBottom").invoke(bounds) as Float
            x in left..right && y in top..bottom
        } catch (e: Exception) {
            false
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun hasAction(config: Any, action: Any): Boolean {
        return try {
            val getOrNullMethod = config::class.java.getMethod("getOrNull", Any::class.java)
            getOrNullMethod.invoke(config, action) != null
        } catch (e: Exception) {
            false
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getStringProperty(config: Any, key: Any): String? {
        return try {
            val getOrNullMethod = config::class.java.getMethod("getOrNull", Any::class.java)
            getOrNullMethod.invoke(config, key) as? String
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getStringListProperty(config: Any, key: Any): List<String>? {
        return try {
            val getOrNullMethod = config::class.java.getMethod("getOrNull", Any::class.java)
            getOrNullMethod.invoke(config, key) as? List<String>
        } catch (e: Exception) {
            null
        }
    }

    private fun getRoleProperty(config: Any): String? {
        return try {
            val getOrNullMethod = config::class.java.getMethod("getOrNull", Any::class.java)
            val role = getOrNullMethod.invoke(config, SemanticsProperties.Role) ?: return null
            role.toString()
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "ComposeClickDetector"
    }
}
```

- [ ] **Step 7: Write test for reflection guard (no-Compose scenario)**

Create `instrumentation/compose-click/src/test/java/io/opentelemetry/android/mobile/instrumentation/ComposeClickInstrumentationTest.kt`:

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class ComposeClickInstrumentationTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private fun makeContext(app: Application): InstrumentationContext =
        InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), WindowEventHub(), app)

    @Test fun `instrumentationName is correct`() {
        val inst = ComposeClickInstrumentation()
        assertEquals("io.opentelemetry.android.mobile.compose.click", inst.instrumentationName)
    }

    @Test fun `install succeeds when Compose is on classpath`() {
        val app = RuntimeEnvironment.getApplication()
        val inst = ComposeClickInstrumentation()
        inst.install(app, makeContext(app))
        // With Compose on test classpath, should install detector
    }

    @Test fun `install with enabled=false is no-op`() {
        val app = RuntimeEnvironment.getApplication()
        val inst = ComposeClickInstrumentation(ComposeClickConfig(enabled = false))
        inst.install(app, makeContext(app))
        // Should not throw, should be a no-op
    }

    @Test fun `uninstall after install succeeds`() {
        val app = RuntimeEnvironment.getApplication()
        val inst = ComposeClickInstrumentation()
        inst.install(app, makeContext(app))
        inst.uninstall()
    }

    @Test fun `uninstall before install does not throw`() {
        val inst = ComposeClickInstrumentation()
        inst.uninstall()
    }
}
```

- [ ] **Step 8: Verify compose-click module compiles and tests pass**

Run:
```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel/examples/demo-app" && ./gradlew :instrumentation-compose-click:testDebugUnitTest
```

Expected: 5 tests PASS. If Compose build features cause issues with AGP, adjust the `build.gradle.kts` (may need `kotlin("plugin.compose")` plugin or Compose compiler config).

---

### Task 5: Wire into aggregator module

**Files:**
- Modify: `otel-android-mobile/build.gradle.kts`

- [ ] **Step 1: Add both new modules as API dependencies**

In `otel-android-mobile/build.gradle.kts`, in the dependencies block, add after the existing instrumentation module entries (after `api(project(":instrumentation-wireframe"))`):

```kotlin
    api(project(":instrumentation-compose-click"))
    api(project(":instrumentation-screen-orientation"))
```

- [ ] **Step 2: Verify full build compiles**

Run:
```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel/examples/demo-app" && ./gradlew :otel-android-mobile:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

---

### Task 6: Full Regression Test

**Files:** None (verification only)

- [ ] **Step 1: Run core tests**

Run:
```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel/examples/demo-app" && ./gradlew :otel-android-mobile-core:testDebugUnitTest
```

Expected: All PASS (including ComposeTapFlagTest)

- [ ] **Step 2: Run all instrumentation module tests**

Run:
```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel/examples/demo-app" && ./gradlew :otel-android-mobile:testDebugUnitTest :instrumentation-tap:testDebugUnitTest :instrumentation-screen-orientation:testDebugUnitTest :instrumentation-compose-click:testDebugUnitTest
```

Expected: All PASS

- [ ] **Step 3: Full APK build**

Run:
```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel/examples/demo-app" && ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit all Phase 2a work**

```bash
cd "/Users/barrysolomon/Projects/Dash0/Mobile Observability/mobile-otel"
git add -A
git commit -m "feat: Phase 2a — ComposeClickInstrumentation + ScreenOrientationInstrumentation

- ComposeTapFlag: timestamp-based dedup coordination in core (500ms configurable expiry)
- ComposeClickInstrumentation: reflection-guarded Compose semantics tree walker
  - compileOnly Compose deps, no-op when Compose absent
  - Emits ui.tap with ui.element.framework=compose, test_tag, role, content_description
  - Window.Callback wrapping in onActivityResumed (outermost, before HubDispatcher)
  - Fallback: emits without composable identity if semantics API changes
  - @Supersedes(\"compose.click\")
- ScreenOrientationInstrumentation: ComponentCallbacks2 listener
  - Emits device.orientation log with portrait/landscape + previous + session context
  - Breadcrumb on orientation change
  - @Supersedes(\"screen_orientation\")
- TapInstrumentation: one-line ComposeTapFlag.wasHandledRecently() dedup check
- Both modules wired into aggregator, registered via META-INF/services SPI"
```
