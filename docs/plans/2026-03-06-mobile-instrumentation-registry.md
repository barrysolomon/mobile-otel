# Mobile Instrumentation Registry Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Refactor the monolithic `AutoCaptureManager` into an OTel contrib-aligned, per-Gradle-module instrumentation registry with a pluggable session provider, `WindowEventHub`, builder API, and SPI discovery.

**Architecture:** A new `otel-android-mobile-core` module owns all interfaces (`MobileInstrumentation`, `MobileSessionProvider`, `InstrumentationContext`, `WindowEventHub`, `InstrumentationRegistry`, `OTelMobileBuilder`, `OTelMobileHandle`, `MobileSemconv`). Ten `instrumentation/` submodules each implement `MobileInstrumentation` and ship an SPI descriptor. The existing `otel-android-mobile` becomes a convenience aggregator that depends on all submodules and preserves the `OTelMobile.start()` drop-in API.

**Tech Stack:** Kotlin 1.9, Android API 26+, AGP 9.0, Gradle 8.9 (Kotlin DSL), OpenTelemetry SDK 1.58.0, JUnit4 + Robolectric + Mockk, ServiceLoader SPI.

**Build command (run from project root's demo-app dir):**
```bash
cd examples/demo-app && ./gradlew :otel-android-mobile-core:test
cd examples/demo-app && ./gradlew :otel-android-mobile:test
```

---

## Task 1: Create `otel-android-mobile-core` Gradle module

**Files:**
- Create: `otel-android-mobile-core/build.gradle.kts`
- Create: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/.gitkeep`
- Modify: `examples/demo-app/settings.gradle.kts`

**Step 1: Create the module directory and build file**

```bash
mkdir -p otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation
mkdir -p otel-android-mobile-core/src/test/java/io/opentelemetry/android/mobile/instrumentation
touch otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/.gitkeep
```

Create `otel-android-mobile-core/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.library")
    id("maven-publish")
}

android {
    namespace = "io.opentelemetry.android.mobile.core"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    testOptions { targetSdk = 36 }
    lint { targetSdk = 36 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    api("io.opentelemetry:opentelemetry-api:1.58.0")
    api("io.opentelemetry:opentelemetry-sdk:1.58.0")
    api("io.opentelemetry:opentelemetry-sdk-logs:1.58.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.58.0")
}
```

**Step 2: Add include to settings.gradle.kts**

In `examples/demo-app/settings.gradle.kts` add after the existing includes:
```kotlin
include(":otel-android-mobile-core")
project(":otel-android-mobile-core").projectDir = file("../../otel-android-mobile-core")
```

**Step 3: Verify Gradle sync**
```bash
cd examples/demo-app && ./gradlew :otel-android-mobile-core:tasks --quiet
```
Expected: task list prints without error.

**Step 4: Commit**
```bash
git add otel-android-mobile-core/ examples/demo-app/settings.gradle.kts
git commit -m "build: add otel-android-mobile-core Gradle module skeleton"
```

---

## Task 2: `MobileSemconv` — centralized attribute key constants

**Files:**
- Create: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/MobileSemconv.kt`
- Create: `otel-android-mobile-core/src/test/java/io/opentelemetry/android/mobile/instrumentation/MobileSemconvTest.kt`

**Step 1: Write the failing test**

```kotlin
// MobileSemconvTest.kt
package io.opentelemetry.android.mobile.instrumentation

import org.junit.Test
import kotlin.test.assertEquals

class MobileSemconvTest {
    @Test fun `event name constants are correct`() {
        assertEquals("ui.tap",         MobileSemconv.UI_TAP)
        assertEquals("ui.scroll",      MobileSemconv.UI_SCROLL)
        assertEquals("ui.screen_view", MobileSemconv.UI_SCREEN_VIEW)
        assertEquals("app.start",      MobileSemconv.APP_START)
    }

    @Test fun `attribute key names are correct`() {
        assertEquals("session.id",            MobileSemconv.SESSION_ID.key)
        assertEquals("screen.name",           MobileSemconv.SCREEN_NAME.key)
        assertEquals("ui.element.resource_id", MobileSemconv.UI_ELEMENT_ID.key)
        assertEquals("ui.swipe.direction",    MobileSemconv.SWIPE_DIRECTION.key)
    }
}
```

**Step 2: Run — expect FAIL**
```bash
cd examples/demo-app && ./gradlew :otel-android-mobile-core:test 2>&1 | tail -20
```
Expected: compilation error — `MobileSemconv` not found.

**Step 3: Implement**

```kotlin
// MobileSemconv.kt
package io.opentelemetry.android.mobile.instrumentation

import io.opentelemetry.api.common.AttributeKey

object MobileSemconv {
    // Event names
    const val UI_TAP         = "ui.tap"
    const val UI_LONG_PRESS  = "ui.long_press"
    const val UI_SWIPE       = "ui.swipe"
    const val UI_SCROLL      = "ui.scroll"
    const val UI_TEXT_INPUT  = "ui.text_input"
    const val UI_BACK_PRESS  = "ui.back_press"
    const val UI_SCREEN_VIEW = "ui.screen_view"
    const val APP_START      = "app.start"
    const val APP_FOREGROUND = "app.foreground"
    const val APP_BACKGROUND = "app.background"
    const val SCREEN_RENDER  = "screen.render"
    const val APP_STARTUP    = "app.startup"

    // Attribute keys
    val SESSION_ID     = AttributeKey.stringKey("session.id")
    val VIEW_ID        = AttributeKey.stringKey("view.id")
    val SCREEN_NAME    = AttributeKey.stringKey("screen.name")
    val UI_ELEMENT_ID  = AttributeKey.stringKey("ui.element.resource_id")
    val SWIPE_DIRECTION = AttributeKey.stringKey("ui.swipe.direction")
    val RECOVERY_TYPE  = AttributeKey.stringKey("recovery_type")
    val SESSION_RENEWED = AttributeKey.booleanKey("session.renewed")
    val BACKGROUND_DURATION_MS = AttributeKey.longKey("background_duration_ms")
}
```

**Step 4: Run — expect PASS**
```bash
cd examples/demo-app && ./gradlew :otel-android-mobile-core:test 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`.

**Step 5: Commit**
```bash
git add otel-android-mobile-core/
git commit -m "feat(core): add MobileSemconv attribute key and event name constants"
```

---

## Task 3: `MobileSessionProvider` interface + `DefaultMobileSessionProvider`

**Files:**
- Create: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/MobileSessionProvider.kt`
- Create: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/DefaultMobileSessionProvider.kt`
- Create: `otel-android-mobile-core/src/test/java/io/opentelemetry/android/mobile/instrumentation/DefaultMobileSessionProviderTest.kt`

**Step 1: Write the failing test**

```kotlin
// DefaultMobileSessionProviderTest.kt
package io.opentelemetry.android.mobile.instrumentation

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DefaultMobileSessionProviderTest {

    @Test fun `getSessionId returns non-empty string`() {
        val provider = DefaultMobileSessionProvider(renewalMs = 30 * 60 * 1000L)
        assertNotNull(provider.getSessionId())
        assertTrue(provider.getSessionId().isNotEmpty())
    }

    @Test fun `getViewId increments on each screen view`() {
        val provider = DefaultMobileSessionProvider()
        provider.onScreenView("ScreenA")
        val v1 = provider.getViewId()
        provider.onScreenView("ScreenB")
        val v2 = provider.getViewId()
        assertNotEquals(v1, v2)
    }

    @Test fun `onAppForeground returns false when within renewal window`() {
        val provider = DefaultMobileSessionProvider(renewalMs = 60 * 60 * 1000L)
        provider.onAppBackground(System.currentTimeMillis() - 1000)
        val renewed = provider.onAppForeground(System.currentTimeMillis())
        assertEquals(false, renewed)
    }

    @Test fun `onAppForeground returns true when outside renewal window`() {
        val provider = DefaultMobileSessionProvider(renewalMs = 1L) // 1ms renewal
        provider.onAppBackground(System.currentTimeMillis() - 10_000)
        val renewed = provider.onAppForeground(System.currentTimeMillis())
        assertEquals(true, renewed)
        // new session should have a different id
        assertTrue(provider.getSessionId().isNotEmpty())
    }
}
```

**Step 2: Run — expect FAIL**
```bash
cd examples/demo-app && ./gradlew :otel-android-mobile-core:test --tests "*.DefaultMobileSessionProviderTest" 2>&1 | tail -20
```
Expected: compile error — types not found.

**Step 3: Implement the interface**

```kotlin
// MobileSessionProvider.kt
package io.opentelemetry.android.mobile.instrumentation

interface MobileSessionProvider {
    fun getSessionId(): String
    fun getViewId(): String
    fun getCurrentScreenName(): String?
    fun onScreenView(screenName: String)
    fun onAppForeground(timestampMs: Long): Boolean  // true = session renewed
    fun onAppBackground(timestampMs: Long)
}
```

**Step 4: Implement the default**

```kotlin
// DefaultMobileSessionProvider.kt
package io.opentelemetry.android.mobile.instrumentation

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class DefaultMobileSessionProvider(
    private val renewalMs: Long = 30 * 60 * 1000L
) : MobileSessionProvider {

    private val sessionId = AtomicReference(UUID.randomUUID().toString())
    private val viewCounter = AtomicLong(0)
    private val currentViewId = AtomicReference("view-0")
    private val currentScreen = AtomicReference<String?>(null)
    private var lastBackgroundAtMs: Long = 0

    override fun getSessionId(): String = sessionId.get()
    override fun getViewId(): String = currentViewId.get()
    override fun getCurrentScreenName(): String? = currentScreen.get()

    override fun onScreenView(screenName: String) {
        currentScreen.set(screenName)
        val count = viewCounter.incrementAndGet()
        currentViewId.set("view-$count")
    }

    override fun onAppForeground(timestampMs: Long): Boolean {
        val elapsed = if (lastBackgroundAtMs > 0) timestampMs - lastBackgroundAtMs else 0
        val renewed = elapsed > renewalMs
        if (renewed) sessionId.set(UUID.randomUUID().toString())
        return renewed
    }

    override fun onAppBackground(timestampMs: Long) {
        lastBackgroundAtMs = timestampMs
    }
}
```

**Step 5: Run — expect PASS**
```bash
cd examples/demo-app && ./gradlew :otel-android-mobile-core:test --tests "*.DefaultMobileSessionProviderTest" 2>&1 | tail -10
```

**Step 6: Commit**
```bash
git add otel-android-mobile-core/
git commit -m "feat(core): add MobileSessionProvider interface and DefaultMobileSessionProvider"
```

---

## Task 4: `WindowEventHub` and `WindowEventListener`

**Files:**
- Create: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/WindowEventListener.kt`
- Create: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/WindowEventHub.kt`
- Create: `otel-android-mobile-core/src/test/java/io/opentelemetry/android/mobile/instrumentation/WindowEventHubTest.kt`

**Step 1: Write the failing test**

```kotlin
// WindowEventHubTest.kt
package io.opentelemetry.android.mobile.instrumentation

import android.view.MotionEvent
import android.view.Window
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class WindowEventHubTest {

    @Test fun `addListener receives dispatched touch events`() {
        val hub = WindowEventHub()
        val listener = mockk<WindowEventListener>(relaxed = true)
        val event = mockk<MotionEvent>()
        val window = mockk<Window>()

        hub.addListener(listener)
        hub.dispatchTouchEvent(event, window)

        verify { listener.onTouchEvent(event, window) }
    }

    @Test fun `removeListener no longer receives events`() {
        val hub = WindowEventHub()
        val listener = mockk<WindowEventListener>(relaxed = true)
        val event = mockk<MotionEvent>()
        val window = mockk<Window>()

        hub.addListener(listener)
        hub.removeListener(listener)
        hub.dispatchTouchEvent(event, window)

        verify(exactly = 0) { listener.onTouchEvent(any(), any()) }
    }

    @Test fun `multiple listeners all receive events`() {
        val hub = WindowEventHub()
        val l1 = mockk<WindowEventListener>(relaxed = true)
        val l2 = mockk<WindowEventListener>(relaxed = true)
        val event = mockk<MotionEvent>()
        val window = mockk<Window>()

        hub.addListener(l1)
        hub.addListener(l2)
        hub.dispatchTouchEvent(event, window)

        verify { l1.onTouchEvent(event, window) }
        verify { l2.onTouchEvent(event, window) }
    }
}
```

**Step 2: Run — expect FAIL**
```bash
cd examples/demo-app && ./gradlew :otel-android-mobile-core:test --tests "*.WindowEventHubTest" 2>&1 | tail -20
```

**Step 3: Implement**

```kotlin
// WindowEventListener.kt
package io.opentelemetry.android.mobile.instrumentation

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window

interface WindowEventListener {
    fun onTouchEvent(event: MotionEvent, window: Window) {}
    fun onKeyEvent(event: KeyEvent, window: Window) {}
}
```

```kotlin
// WindowEventHub.kt
package io.opentelemetry.android.mobile.instrumentation

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import java.util.concurrent.CopyOnWriteArrayList

class WindowEventHub {
    private val listeners = CopyOnWriteArrayList<WindowEventListener>()

    fun addListener(listener: WindowEventListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: WindowEventListener) {
        listeners.remove(listener)
    }

    fun dispatchTouchEvent(event: MotionEvent, window: Window) {
        listeners.forEach { it.onTouchEvent(event, window) }
    }

    fun dispatchKeyEvent(event: KeyEvent, window: Window) {
        listeners.forEach { it.onKeyEvent(event, window) }
    }
}
```

**Step 4: Run — expect PASS**
```bash
cd examples/demo-app && ./gradlew :otel-android-mobile-core:test --tests "*.WindowEventHubTest" 2>&1 | tail -10
```

**Step 5: Commit**
```bash
git add otel-android-mobile-core/
git commit -m "feat(core): add WindowEventHub with CopyOnWriteArrayList listener dispatch"
```

---

## Task 5: `MobileInstrumentation` interface and `InstrumentationContext`

**Files:**
- Create: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/MobileInstrumentation.kt`
- Create: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/InstrumentationContext.kt`
- Create: `otel-android-mobile-core/src/test/java/io/opentelemetry/android/mobile/instrumentation/InstrumentationContextTest.kt`

**Step 1: Write the failing test**

```kotlin
// InstrumentationContextTest.kt
package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.mockk.mockk
import io.opentelemetry.api.OpenTelemetry
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals

class InstrumentationContextTest {

    @Test fun `tracer delegates to openTelemetry`() {
        val otel = mockk<OpenTelemetry>(relaxed = true)
        val sessionProvider = DefaultMobileSessionProvider()
        val hub = WindowEventHub()
        val app = mockk<Application>()

        val ctx = InstrumentationContext(otel, sessionProvider, hub, app)

        assertNotNull(ctx.tracer("test"))
        assertNotNull(ctx.logger("test"))
        assertNotNull(ctx.meter("test"))
    }

    @Test fun `sessionProvider is accessible`() {
        val otel = mockk<OpenTelemetry>(relaxed = true)
        val sessionProvider = DefaultMobileSessionProvider()
        val hub = WindowEventHub()
        val app = mockk<Application>()

        val ctx = InstrumentationContext(otel, sessionProvider, hub, app)
        assertEquals(sessionProvider, ctx.sessionProvider)
    }
}
```

**Step 2: Run — expect FAIL**
```bash
cd examples/demo-app && ./gradlew :otel-android-mobile-core:test --tests "*.InstrumentationContextTest" 2>&1 | tail -20
```

**Step 3: Implement**

```kotlin
// MobileInstrumentation.kt
package io.opentelemetry.android.mobile.instrumentation

import android.app.Application

interface MobileInstrumentation {
    val instrumentationName: String
    val instrumentationVersion: String get() = "1.0.0"

    fun install(application: Application, context: InstrumentationContext)
    fun uninstall() {}
}
```

```kotlin
// InstrumentationContext.kt
package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer

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

**Step 4: Run — expect PASS**
```bash
cd examples/demo-app && ./gradlew :otel-android-mobile-core:test 2>&1 | tail -10
```

**Step 5: Commit**
```bash
git add otel-android-mobile-core/
git commit -m "feat(core): add MobileInstrumentation interface and InstrumentationContext"
```

---

## Task 6: `InstrumentationRegistry`

**Files:**
- Create: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/InstrumentationRegistry.kt`
- Create: `otel-android-mobile-core/src/test/java/io/opentelemetry/android/mobile/instrumentation/InstrumentationRegistryTest.kt`

**Step 1: Write the failing test**

```kotlin
// InstrumentationRegistryTest.kt
package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.mockk.*
import org.junit.Test
import kotlin.test.assertTrue

class InstrumentationRegistryTest {

    private fun makeContext(): InstrumentationContext {
        return InstrumentationContext(
            mockk(relaxed = true),
            DefaultMobileSessionProvider(),
            WindowEventHub(),
            mockk()
        )
    }

    @Test fun `install calls install on every instrumentation`() {
        val i1 = mockk<MobileInstrumentation>(relaxed = true)
        val i2 = mockk<MobileInstrumentation>(relaxed = true)
        val app = mockk<Application>(relaxed = true)
        val ctx = makeContext()

        val registry = InstrumentationRegistry(listOf(i1, i2))
        registry.install(app, ctx)

        verify { i1.install(app, ctx) }
        verify { i2.install(app, ctx) }
    }

    @Test fun `uninstall calls uninstall on every instrumentation`() {
        val i1 = mockk<MobileInstrumentation>(relaxed = true)
        val i2 = mockk<MobileInstrumentation>(relaxed = true)
        val app = mockk<Application>(relaxed = true)
        val ctx = makeContext()

        val registry = InstrumentationRegistry(listOf(i1, i2))
        registry.install(app, ctx)
        registry.uninstall()

        verify { i1.uninstall() }
        verify { i2.uninstall() }
    }

    @Test fun `install with empty list succeeds`() {
        val app = mockk<Application>(relaxed = true)
        val ctx = makeContext()
        val registry = InstrumentationRegistry(emptyList())
        registry.install(app, ctx)  // must not throw
        assertTrue(true)
    }
}
```

**Step 2: Run — expect FAIL**
```bash
cd examples/demo-app && ./gradlew :otel-android-mobile-core:test --tests "*.InstrumentationRegistryTest" 2>&1 | tail -20
```

**Step 3: Implement**

```kotlin
// InstrumentationRegistry.kt
package io.opentelemetry.android.mobile.instrumentation

import android.app.Application

class InstrumentationRegistry(
    private val instrumentations: List<MobileInstrumentation>
) {
    fun install(application: Application, context: InstrumentationContext) {
        instrumentations.forEach { it.install(application, context) }
    }

    fun uninstall() {
        instrumentations.forEach { it.uninstall() }
    }
}
```

**Step 4: Run — expect PASS**
```bash
cd examples/demo-app && ./gradlew :otel-android-mobile-core:test 2>&1 | tail -10
```

**Step 5: Commit**
```bash
git add otel-android-mobile-core/
git commit -m "feat(core): add InstrumentationRegistry"
```

---

## Task 7: `OTelMobileHandle` and `OTelMobileBuilder`

**Files:**
- Create: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/OTelMobileHandle.kt`
- Create: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/OTelMobileBuilder.kt`
- Create: `otel-android-mobile-core/src/test/java/io/opentelemetry/android/mobile/instrumentation/OTelMobileBuilderTest.kt`

**Step 1: Write the failing test**

```kotlin
// OTelMobileBuilderTest.kt
package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.mockk.*
import io.opentelemetry.api.OpenTelemetry
import org.junit.Test
import kotlin.test.assertNotNull

class OTelMobileBuilderTest {

    @Test fun `build installs all added instrumentations`() {
        val app = mockk<Application>(relaxed = true)
        val otel = mockk<OpenTelemetry>(relaxed = true)
        val i1 = mockk<MobileInstrumentation>(relaxed = true)
        val i2 = mockk<MobileInstrumentation>(relaxed = true)

        val handle = OTelMobileBuilder(app, otel)
            .addInstrumentation(i1)
            .addInstrumentation(i2)
            .build()

        assertNotNull(handle)
        verify { i1.install(app, any()) }
        verify { i2.install(app, any()) }
    }

    @Test fun `build with custom session provider passes it to context`() {
        val app = mockk<Application>(relaxed = true)
        val otel = mockk<OpenTelemetry>(relaxed = true)
        val sessionProvider = mockk<MobileSessionProvider>(relaxed = true)
        val instrumentation = mockk<MobileInstrumentation>(relaxed = true)

        every { instrumentation.instrumentationName } returns "test"
        val contextSlot = slot<InstrumentationContext>()
        every { instrumentation.install(any(), capture(contextSlot)) } just runs

        OTelMobileBuilder(app, otel)
            .setSessionProvider(sessionProvider)
            .addInstrumentation(instrumentation)
            .build()

        assert(contextSlot.captured.sessionProvider === sessionProvider)
    }

    @Test fun `stop calls uninstall on registry`() {
        val app = mockk<Application>(relaxed = true)
        val otel = mockk<OpenTelemetry>(relaxed = true)
        val instrumentation = mockk<MobileInstrumentation>(relaxed = true)

        val handle = OTelMobileBuilder(app, otel)
            .addInstrumentation(instrumentation)
            .build()

        handle.stop()
        verify { instrumentation.uninstall() }
    }
}
```

**Step 2: Run — expect FAIL**
```bash
cd examples/demo-app && ./gradlew :otel-android-mobile-core:test --tests "*.OTelMobileBuilderTest" 2>&1 | tail -20
```

**Step 3: Implement `OTelMobileHandle`**

```kotlin
// OTelMobileHandle.kt
package io.opentelemetry.android.mobile.instrumentation

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer

class OTelMobileHandle internal constructor(
    private val openTelemetry: OpenTelemetry,
    private val registry: InstrumentationRegistry
) {
    fun getTracer(scope: String): Tracer = openTelemetry.getTracer(scope)
    fun getLogger(scope: String): Logger = openTelemetry.logsBridge.get(scope)
    fun getMeter(scope: String): Meter   = openTelemetry.getMeter(scope)

    fun stop(timeoutSeconds: Long = 30) {
        registry.uninstall()
    }
}
```

**Step 4: Implement `OTelMobileBuilder`**

```kotlin
// OTelMobileBuilder.kt
package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.opentelemetry.api.OpenTelemetry
import java.util.ServiceLoader

class OTelMobileBuilder(
    private val application: Application,
    private val openTelemetry: OpenTelemetry
) {
    private val instrumentations = mutableListOf<MobileInstrumentation>()
    private var sessionProvider: MobileSessionProvider = DefaultMobileSessionProvider()

    fun setSessionProvider(provider: MobileSessionProvider): OTelMobileBuilder = apply {
        sessionProvider = provider
    }

    fun addInstrumentation(instrumentation: MobileInstrumentation): OTelMobileBuilder = apply {
        instrumentations.add(instrumentation)
    }

    fun discoverInstrumentations(): OTelMobileBuilder = apply {
        ServiceLoader.load(
            MobileInstrumentation::class.java,
            MobileInstrumentation::class.java.classLoader
        ).forEach { instrumentations.add(it) }
    }

    fun build(): OTelMobileHandle {
        val hub = WindowEventHub()
        val context = InstrumentationContext(openTelemetry, sessionProvider, hub, application)
        val registry = InstrumentationRegistry(instrumentations.toList())
        registry.install(application, context)
        return OTelMobileHandle(openTelemetry, registry)
    }
}
```

**Step 5: Run — expect PASS**
```bash
cd examples/demo-app && ./gradlew :otel-android-mobile-core:test 2>&1 | tail -10
```

**Step 6: Commit**
```bash
git add otel-android-mobile-core/
git commit -m "feat(core): add OTelMobileHandle and OTelMobileBuilder"
```

---

## Task 8: Set up `instrumentation/` Gradle submodules

Create the Gradle modules for all 10 instrumentation submodules. Each shares the same `build.gradle.kts` template — it depends on `:otel-android-mobile-core` and `:otel-android-mobile`.

**Files to create for each module** (repeat for: `lifecycle`, `screen`, `tap`, `scroll`, `text-input`, `back-press`, `freeze`, `errors`, `network`, `vitals`):
- `instrumentation/<name>/build.gradle.kts`
- `instrumentation/<name>/src/main/java/io/opentelemetry/android/mobile/instrumentation/<Name>/.gitkeep`
- `instrumentation/<name>/src/main/resources/META-INF/services/io.opentelemetry.android.mobile.instrumentation.MobileInstrumentation`

**Step 1: Create all module directories**

```bash
for mod in lifecycle screen tap scroll text-input back-press freeze errors network vitals; do
  mkdir -p instrumentation/$mod/src/main/java/io/opentelemetry/android/mobile/instrumentation
  mkdir -p instrumentation/$mod/src/test/java/io/opentelemetry/android/mobile/instrumentation
  mkdir -p instrumentation/$mod/src/main/resources/META-INF/services
  touch instrumentation/$mod/src/main/resources/META-INF/services/io.opentelemetry.android.mobile.instrumentation.MobileInstrumentation
done
```

**Step 2: Create template `build.gradle.kts`** (shown for `lifecycle` — repeat for all):

```kotlin
// instrumentation/lifecycle/build.gradle.kts
plugins { id("com.android.library") }

android {
    namespace = "io.opentelemetry.android.mobile.instrumentation.lifecycle"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    testOptions { targetSdk = 36 }
    lint { targetSdk = 36 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    testOptions { unitTests { isIncludeAndroidResources = true; isReturnDefaultValues = true } }
}

dependencies {
    api(project(":otel-android-mobile-core"))
    api(project(":otel-android-mobile"))  // for MobileLoggerProvider, MobileConfig
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.58.0")
}
```

Note: `tap`, `scroll`, `text-input`, `back-press` additionally need `implementation("androidx.fragment:fragment:1.8.7")` if not already transitive.

**Step 3: Add all includes to `settings.gradle.kts`**

Append to `examples/demo-app/settings.gradle.kts`:
```kotlin
listOf("lifecycle","screen","tap","scroll","text-input","back-press","freeze","errors","network","vitals")
    .forEach { mod ->
        include(":instrumentation-$mod")
        project(":instrumentation-$mod").projectDir = file("../../instrumentation/$mod")
    }
```

**Step 4: Verify sync**
```bash
cd examples/demo-app && ./gradlew :instrumentation-lifecycle:tasks --quiet 2>&1 | tail -5
```

**Step 5: Commit**
```bash
git add instrumentation/ examples/demo-app/settings.gradle.kts
git commit -m "build: add instrumentation/ Gradle submodule skeletons (10 modules)"
```

---

## Task 9: `LifecycleInstrumentation`

Extracts `onActivityCreated/Started/Stopped` logic from `AutoCaptureManager` into a self-contained instrumentation.

**Files:**
- Create: `instrumentation/lifecycle/src/main/java/io/opentelemetry/android/mobile/instrumentation/LifecycleInstrumentation.kt`
- Create: `instrumentation/lifecycle/src/test/java/io/opentelemetry/android/mobile/instrumentation/LifecycleInstrumentationTest.kt`
- Update: `instrumentation/lifecycle/src/main/resources/META-INF/services/...MobileInstrumentation` — add class name

**Step 1: Write the failing test**

```kotlin
// LifecycleInstrumentationTest.kt
package io.opentelemetry.android.mobile.instrumentation

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.mockk.*
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LifecycleInstrumentationTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private fun makeContext(app: Application): InstrumentationContext =
        InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), WindowEventHub(), app)

    @Test fun `instrumentationName is correct`() {
        assertEquals("io.opentelemetry.android.mobile.lifecycle", LifecycleInstrumentation().instrumentationName)
    }

    @Test fun `app start log emitted on first activity created`() {
        val app = mockk<Application>(relaxed = true)
        val activity = mockk<Activity>(relaxed = true)
        val inst = LifecycleInstrumentation()

        val callbackSlot = slot<Application.ActivityLifecycleCallbacks>()
        every { app.registerActivityLifecycleCallbacks(capture(callbackSlot)) } just runs

        inst.install(app, makeContext(app))
        callbackSlot.captured.onActivityCreated(activity, null)

        val logs = otelRule.logRecords
        assertTrue(logs.any { it.body.asString() == MobileSemconv.APP_START })
    }

    @Test fun `uninstall unregisters lifecycle callbacks`() {
        val app = mockk<Application>(relaxed = true)
        val inst = LifecycleInstrumentation()
        inst.install(app, makeContext(app))
        inst.uninstall()
        verify { app.unregisterActivityLifecycleCallbacks(any()) }
    }
}
```

**Step 2: Run — expect FAIL**
```bash
cd examples/demo-app && ./gradlew :instrumentation-lifecycle:test 2>&1 | tail -20
```

**Step 3: Implement**

```kotlin
// LifecycleInstrumentation.kt
package io.opentelemetry.android.mobile.instrumentation

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity

class LifecycleInstrumentation : MobileInstrumentation {
    override val instrumentationName = "io.opentelemetry.android.mobile.lifecycle"

    private var logger: Logger? = null
    private var sessionProvider: MobileSessionProvider? = null
    private var application: Application? = null
    private var callbacks: Application.ActivityLifecycleCallbacks? = null

    private var firstStartLogged = false
    private var activeActivities = 0
    private var lastBackgroundAtMs = 0L

    override fun install(application: Application, context: InstrumentationContext) {
        this.application = application
        this.logger = context.logger(instrumentationName)
        this.sessionProvider = context.sessionProvider

        val cb = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                if (!firstStartLogged) {
                    firstStartLogged = true
                    emitLog(MobileSemconv.APP_START, Severity.INFO)
                }
            }
            override fun onActivityStarted(a: Activity) {
                activeActivities++
                if (activeActivities == 1) {
                    val now = System.currentTimeMillis()
                    val bg = if (lastBackgroundAtMs > 0) now - lastBackgroundAtMs else 0L
                    val renewed = context.sessionProvider.onAppForeground(now)
                    emitLog(MobileSemconv.APP_FOREGROUND, Severity.INFO,
                        Attributes.builder()
                            .put(MobileSemconv.SESSION_RENEWED, renewed)
                            .put(MobileSemconv.BACKGROUND_DURATION_MS, bg)
                            .build()
                    )
                }
            }
            override fun onActivityStopped(a: Activity) {
                activeActivities--
                if (activeActivities == 0) {
                    lastBackgroundAtMs = System.currentTimeMillis()
                    context.sessionProvider.onAppBackground(lastBackgroundAtMs)
                    emitLog(MobileSemconv.APP_BACKGROUND, Severity.INFO)
                }
            }
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        callbacks = cb
        application.registerActivityLifecycleCallbacks(cb)
    }

    override fun uninstall() {
        callbacks?.let { application?.unregisterActivityLifecycleCallbacks(it) }
        callbacks = null
        application = null
    }

    private fun emitLog(name: String, severity: Severity, extra: Attributes = Attributes.empty()) {
        val sp = sessionProvider ?: return
        logger?.logRecordBuilder()
            ?.setBody(name)
            ?.setSeverity(severity)
            ?.setAllAttributes(
                Attributes.builder()
                    .put(MobileSemconv.SESSION_ID, sp.getSessionId())
                    .put(MobileSemconv.VIEW_ID, sp.getViewId())
                    .putAll(extra)
                    .build()
            )
            ?.emit()
    }
}
```

**Step 4: Update SPI file**
```
# instrumentation/lifecycle/src/main/resources/META-INF/services/io.opentelemetry.android.mobile.instrumentation.MobileInstrumentation
io.opentelemetry.android.mobile.instrumentation.LifecycleInstrumentation
```

**Step 5: Run — expect PASS**
```bash
cd examples/demo-app && ./gradlew :instrumentation-lifecycle:test 2>&1 | tail -10
```

**Step 6: Commit**
```bash
git add instrumentation/lifecycle/
git commit -m "feat(lifecycle): add LifecycleInstrumentation with app.start/foreground/background"
```

---

## Task 10: `ScreenViewInstrumentation`

Extracts `onActivityResumed` screen view logging, fragment tracking, and page span management from `AutoCaptureManager`.

**Files:**
- Create: `instrumentation/screen/src/main/java/io/opentelemetry/android/mobile/instrumentation/ScreenViewInstrumentation.kt`
- Create: `instrumentation/screen/src/test/java/.../ScreenViewInstrumentationTest.kt`
- Update: SPI file

**Step 1: Write the failing test**

```kotlin
class ScreenViewInstrumentationTest {
    @get:Rule val otelRule = OpenTelemetryRule.create()

    @Test fun `instrumentationName is correct`() {
        assertEquals("io.opentelemetry.android.mobile.screen", ScreenViewInstrumentation().instrumentationName)
    }

    @Test fun `screen view log emitted on activity resumed`() {
        val app = mockk<Application>(relaxed = true)
        val activity = mockk<Activity>(relaxed = true)
        every { activity.javaClass.simpleName } returns "MainActivity"

        val callbackSlot = slot<Application.ActivityLifecycleCallbacks>()
        every { app.registerActivityLifecycleCallbacks(capture(callbackSlot)) } just runs

        val inst = ScreenViewInstrumentation()
        inst.install(app, InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), WindowEventHub(), app))
        callbackSlot.captured.onActivityResumed(activity)

        val logs = otelRule.logRecords
        assertTrue(logs.any { it.body.asString() == MobileSemconv.UI_SCREEN_VIEW })
    }
}
```

**Step 2: Run — expect FAIL**
```bash
cd examples/demo-app && ./gradlew :instrumentation-screen:test 2>&1 | tail -20
```

**Step 3: Implement** — mirrors `logScreenView`, `startPageSpan`, `endPageSpan`, `startScreenRenderSpan`, `FragmentCallbacks` from `AutoCaptureManager` but self-contained. Page span management lives here (not in a shared global).

Key structure:
```kotlin
class ScreenViewInstrumentation : MobileInstrumentation {
    override val instrumentationName = "io.opentelemetry.android.mobile.screen"
    private var pageSpan: Span? = null
    private var pageScope: Scope? = null
    // install: register ActivityLifecycleCallbacks + FragmentLifecycleCallbacks
    // onActivityResumed: logScreenView + startPageSpan + startScreenRenderSpan
    // onFragmentResumed: logScreenView + startPageSpan (replaces old page span)
    // onFragmentPaused: endPageSpan
}
```

**Step 4: Run — expect PASS**
```bash
cd examples/demo-app && ./gradlew :instrumentation-screen:test 2>&1 | tail -10
```

**Step 5: Commit**
```bash
git add instrumentation/screen/
git commit -m "feat(screen): add ScreenViewInstrumentation with page spans and fragment tracking"
```

---

## Task 11: `TapInstrumentation`

Wraps the existing `TapCapture` class as a `MobileInstrumentation` + `WindowEventListener`. Owns the `WindowCallbackWrapper` setup by registering with `WindowEventHub`.

**Files:**
- Create: `instrumentation/tap/src/main/java/.../TapInstrumentation.kt`
- Create: `instrumentation/tap/src/main/java/.../TapConfig.kt`
- Move: copy `TapCapture.kt`, `CoordinateBucketer.kt`, `ViewHitTester.kt`, `PrivacyUtils.kt`, `PrivacyMode.kt` from `otel-android-mobile/` to `instrumentation/tap/` (keep originals temporarily with `@Deprecated`)
- Create: test file
- Update: SPI file

**Step 1: Write the failing test**

```kotlin
class TapInstrumentationTest {
    @get:Rule val otelRule = OpenTelemetryRule.create()

    @Test fun `instrumentationName is correct`() {
        assertEquals("io.opentelemetry.android.mobile.tap", TapInstrumentation().instrumentationName)
    }

    @Test fun `registers as WindowEventListener on install`() {
        val app = mockk<Application>(relaxed = true)
        every { app.registerActivityLifecycleCallbacks(any()) } just runs
        val hub = WindowEventHub()
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        TapInstrumentation().install(app, ctx)

        // dispatch a touch event — should not throw
        val event = mockk<MotionEvent>()
        every { event.actionMasked } returns MotionEvent.ACTION_DOWN
        every { event.rawX } returns 100f
        every { event.rawY } returns 200f
        hub.dispatchTouchEvent(event, mockk(relaxed = true))
    }

    @Test fun `uninstall removes WindowEventListener`() {
        val app = mockk<Application>(relaxed = true)
        every { app.registerActivityLifecycleCallbacks(any()) } just runs
        every { app.unregisterActivityLifecycleCallbacks(any()) } just runs
        val hub = spyk(WindowEventHub())
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        val inst = TapInstrumentation()
        inst.install(app, ctx)
        inst.uninstall()

        verify { hub.removeListener(any()) }
    }
}
```

**Step 2–4: Implement `TapConfig` + `TapInstrumentation`**

`TapConfig` replaces the tap/swipe/longpress fields from `AutoCaptureOptions`:
```kotlin
data class TapConfig(
    val captureTaps: Boolean = true,
    val captureLongPress: Boolean = true,
    val captureSwipe: Boolean = true,
    val swipeMinDistancePx: Float = 50f,
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

`TapInstrumentation` implements both `MobileInstrumentation` and `WindowEventListener`, delegates to `TapCapture` internally.

**Step 5: Run — expect PASS**
```bash
cd examples/demo-app && ./gradlew :instrumentation-tap:test 2>&1 | tail -10
```

**Step 6: Commit**
```bash
git add instrumentation/tap/
git commit -m "feat(tap): add TapInstrumentation with TapConfig wrapping existing TapCapture"
```

---

## Task 12: `ScrollInstrumentation`, `TextInputInstrumentation`, `BackPressInstrumentation`

Same pattern as Task 11 — each wraps the existing capture class (`ScrollCapture`, `TextInputCapture`, `BackPressCapture`) as a `WindowEventListener`. Follow the exact same test → implement → SPI → commit pattern.

For each:
- `ScrollConfig(throttleMs: Long = 500)`
- `TextInputInstrumentation` — uses `ViewTreeObserver.OnGlobalFocusChangeListener`, attaches on `onActivityResumed`
- `BackPressInstrumentation` — registers as `WindowEventListener` for key events

Run all three tests:
```bash
cd examples/demo-app && ./gradlew :instrumentation-scroll:test :instrumentation-text-input:test :instrumentation-back-press:test 2>&1 | tail -20
```

Commit each separately:
```bash
git commit -m "feat(scroll): add ScrollInstrumentation"
git commit -m "feat(text-input): add TextInputInstrumentation"
git commit -m "feat(back-press): add BackPressInstrumentation"
```

---

## Task 13: `FreezeInstrumentation`

Wraps `FreezeDetector` + `RecoveryTracker`.

```kotlin
data class FreezeConfig(
    val freezeThresholdMs: Long = 2000,
    val cooldownMs: Long = 30_000,
    val anrThresholdMs: Long = 5000
)
```

Follow test → implement → SPI → commit pattern. Key test: verify `FreezeDetector.start()` is called in `install()` and `stop()` in `uninstall()`.

```bash
cd examples/demo-app && ./gradlew :instrumentation-freeze:test 2>&1 | tail -10
git commit -m "feat(freeze): add FreezeInstrumentation wrapping FreezeDetector and RecoveryTracker"
```

---

## Task 14: `ErrorsInstrumentation`, `NetworkInstrumentation`, `VitalsInstrumentation`

These refactor existing `ErrorInstrumentation`, `OTelNetworkInterceptor`, and `VitalsCollector` classes into `MobileInstrumentation` wrappers. Each is already mostly self-contained.

Pattern for each:
1. Create `XxxInstrumentation` that calls existing `Xxx.initialize()` in `install()` and `Xxx.getInstance()?.shutdown()` in `uninstall()`
2. Write test verifying `instrumentationName` and that `install()` does not throw with a real OTel SDK
3. Add SPI file
4. Commit

```bash
cd examples/demo-app && ./gradlew :instrumentation-errors:test :instrumentation-network:test :instrumentation-vitals:test 2>&1 | tail -20
```

---

## Task 15: Update `otel-android-mobile` to be the aggregator

The existing `otel-android-mobile` library becomes a convenience aggregator. Its `build.gradle.kts` gains `api` dependencies on all instrumentation modules and `:otel-android-mobile-core`. The existing classes remain (they are still the implementations the instrumentation modules delegate to). The `AutoCaptureManager` is deprecated.

**Files:**
- Modify: `otel-android-mobile/build.gradle.kts` — add `api(project(":otel-android-mobile-core"))` and `api` for each instrumentation module
- Modify: `otel-android-mobile/src/main/java/.../OTelMobile.kt` — refactor to factory with no state
- Modify: `otel-android-mobile/src/main/java/.../MobileOtel.kt` — delegate to `OTelMobileHandle`
- Modify: `otel-android-mobile/src/main/java/.../autocapture/AutoCaptureManager.kt` — mark `@Deprecated`
- Modify: `otel-android-mobile/src/main/java/.../autocapture/AutoCaptureOptions.kt` — mark `@Deprecated`

**Step 1: Update `OTelMobile.kt`**

```kotlin
// OTelMobile.kt — factory only, no state
object OTelMobile {
    // Convenience accessor — apps that call start() can still use OTelMobile.getTracer()
    @Volatile private var handle: OTelMobileHandle? = null

    fun start(application: Application, config: MobileConfig): OTelMobileHandle {
        val otelSdk = MobileOtel.initialize(application, config).getOpenTelemetrySdk()
        return builder(application, otelSdk)
            .discoverInstrumentations()
            .build()
            .also { handle = it }
    }

    fun builder(application: Application, openTelemetry: OpenTelemetry) =
        OTelMobileBuilder(application, openTelemetry)

    fun getTracer(scope: String) = handle?.getTracer(scope)
        ?: error("OTelMobile.start() must be called first")
    fun getLogger(scope: String) = handle?.getLogger(scope)
        ?: error("OTelMobile.start() must be called first")
    fun getMeter(scope: String) = handle?.getMeter(scope)
        ?: error("OTelMobile.start() must be called first")

    fun stop(timeoutSeconds: Long = 30) {
        handle?.stop(timeoutSeconds)
        handle = null
        MobileOtel.shutdown()
    }
}
```

**Step 2: Verify existing tests still pass**
```bash
cd examples/demo-app && ./gradlew :otel-android-mobile:test 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`.

**Step 3: Commit**
```bash
git add otel-android-mobile/
git commit -m "refactor: make otel-android-mobile the aggregator, OTelMobile becomes factory"
```

---

## Task 16: Update demo app to use new API

**Files:**
- Modify: `examples/demo-app/android/src/main/java/io/opentelemetry/android/demo/DemoApp.kt`

The demo app currently calls `OTelMobile.start(application, config, options)`. Update it to use the builder with explicit instrumentation or keep `start()` for the zero-config path.

```kotlin
// DemoApp.kt — in onCreate()
handle = OTelMobile.start(this, config)
// or explicit:
handle = OTelMobile.builder(this, otelSdk)
    .addInstrumentation(LifecycleInstrumentation())
    .addInstrumentation(ScreenViewInstrumentation())
    .addInstrumentation(TapInstrumentation(TapConfig(captureSwipe = true)))
    .addInstrumentation(ScrollInstrumentation())
    .addInstrumentation(FreezeInstrumentation())
    .build()
```

Store `handle` in `DemoApp` companion, expose via `DemoApp.handle`.

**Step: Run demo app build**
```bash
cd examples/demo-app && ./gradlew :android:assembleDebug 2>&1 | tail -30
```

**Commit**
```bash
git add examples/demo-app/android/
git commit -m "feat(demo): update DemoApp to use OTelMobileBuilder API"
```

---

## Task 17: Add `@Incubating` annotations to all new public APIs

OTel policy: all new public APIs are `@Incubating` until stabilized. This is required for any OTel contrib PR review.

**Step 1: Add annotation to `otel-android-mobile-core`**

In `otel-android-mobile-core/build.gradle.kts` add:
```kotlin
implementation("io.opentelemetry:opentelemetry-api:1.58.0")
```
(already there via `api`)

Create annotation in core:
```kotlin
// Incubating.kt
package io.opentelemetry.android.mobile.instrumentation

@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "This API is incubating and may change in future releases without notice."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class Incubating
```

Apply `@Incubating` to: `MobileInstrumentation`, `MobileSessionProvider`, `OTelMobileBuilder`, `OTelMobileHandle`, `InstrumentationContext`, `InstrumentationRegistry`, `WindowEventHub`.

**Step 2: Commit**
```bash
git add otel-android-mobile-core/
git commit -m "feat(core): add @Incubating annotation to all new public APIs"
```

---

## Task 18: Run all tests and fix any failures

```bash
cd examples/demo-app && ./gradlew \
  :otel-android-mobile-core:test \
  :instrumentation-lifecycle:test \
  :instrumentation-screen:test \
  :instrumentation-tap:test \
  :instrumentation-scroll:test \
  :instrumentation-text-input:test \
  :instrumentation-back-press:test \
  :instrumentation-freeze:test \
  :instrumentation-errors:test \
  :instrumentation-network:test \
  :instrumentation-vitals:test \
  :otel-android-mobile:test \
  2>&1 | grep -E "^(BUILD|FAIL|> Task|Tests run)"
```

Expected: `BUILD SUCCESSFUL` for all. Fix any failures before proceeding.

**Commit after all fixes:**
```bash
git commit -m "test: all instrumentation modules passing"
```

---

## Task 19: Final build verification

```bash
cd examples/demo-app && ./gradlew :android:assembleDebug 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, APK generated.

```bash
git add -A
git commit -m "feat: complete OTel contrib-aligned instrumentation registry refactor

- otel-android-mobile-core: MobileInstrumentation, MobileSessionProvider,
  InstrumentationContext, WindowEventHub, InstrumentationRegistry,
  OTelMobileBuilder, OTelMobileHandle, MobileSemconv, @Incubating
- 10 instrumentation modules: lifecycle, screen, tap, scroll, text-input,
  back-press, freeze, errors, network, vitals
- SPI discovery via META-INF/services
- otel-android-mobile becomes convenience aggregator
- OTelMobile.start() preserved for zero-config backward compat
- AutoCaptureManager and AutoCaptureOptions deprecated"
```
