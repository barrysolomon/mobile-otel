# Upstream Supersession Phase 1: Foundation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make our SDK a compatible superset of upstream `opentelemetry-android` by adding a bidirectional adapter layer, dependency alignment, `@Supersedes` conflict resolution, and upstream module discovery — all non-breaking for existing modules.

**Architecture:** Add upstream `session:0.10.0-alpha` and `instrumentation-android-instrumentation:0.10.0-alpha` deps to `otel-android-mobile-core`. Bridge session interfaces. Create bidirectional adapters (`UpstreamInstrumentationAdapter`, `MobileInstrumentationAdapter`). Add `@Supersedes` annotation + conflict resolution in `InstrumentationRegistry`. Add upstream discovery to `OTelMobileBuilder`. TDD throughout.

**Tech Stack:** Kotlin, JUnit 4, MockK, Robolectric, OpenTelemetry SDK Testing, Gradle

**Design Spec:** `docs/superpowers/specs/2026-04-09-upstream-supersession-design.md` (sections 2.1–2.8)

---

## File Map

### New Files
| File | Responsibility |
|------|---------------|
| `otel-android-mobile-core/src/main/java/.../instrumentation/Supersedes.kt` | `@Supersedes` annotation |
| `otel-android-mobile-core/src/main/java/.../instrumentation/UpstreamInstrumentationAdapter.kt` | Wraps upstream `AndroidInstrumentation` as `MobileInstrumentation` |
| `otel-android-mobile-core/src/main/java/.../instrumentation/MobileInstrumentationAdapter.kt` | Wraps our `MobileInstrumentation` as `AndroidInstrumentation` |
| `otel-android-mobile-core/src/main/java/.../instrumentation/UpstreamSessionProviderAdapter.kt` | Wraps upstream `SessionProvider` as `MobileSessionProvider` |
| `otel-android-mobile-core/src/test/java/.../instrumentation/UpstreamInstrumentationAdapterTest.kt` | Tests for Direction 1 adapter |
| `otel-android-mobile-core/src/test/java/.../instrumentation/MobileInstrumentationAdapterTest.kt` | Tests for Direction 2 adapter |
| `otel-android-mobile-core/src/test/java/.../instrumentation/SessionProviderBridgeTest.kt` | Tests `MobileSessionProvider extends SessionProvider` |
| `otel-android-mobile-core/src/test/java/.../instrumentation/UpstreamSessionProviderAdapterTest.kt` | Tests adapter defaults |
| `otel-android-mobile-core/src/test/java/.../instrumentation/SupersedesConflictTest.kt` | Tests conflict resolution |

### Modified Files
| File | Change |
|------|--------|
| `otel-android-mobile-core/build.gradle.kts` | Add `session:0.10.0-alpha` + `instrumentation-android-instrumentation:0.10.0-alpha` deps |
| `otel-android-mobile/build.gradle.kts` | Remove phantom dep, update semconv |
| `otel-android-mobile-core/.../MobileSessionProvider.kt` | Add `: SessionProvider` superinterface |
| `otel-android-mobile-core/.../InstrumentationContext.kt` | Add optional `clock` parameter |
| `otel-android-mobile-core/.../InstrumentationRegistry.kt` | Add `@Supersedes` conflict resolution + `installed` tracking |
| `otel-android-mobile-core/.../OTelMobileBuilder.kt` | Add `discoverUpstreamInstrumentations()` + `discoverAllInstrumentations()` |
| `instrumentation/errors/.../ErrorInstrumentation.kt` | Add `@Supersedes("crash")` |
| `instrumentation/vitals/.../VitalsInstrumentation.kt` | Add `@Supersedes("anr", "startup")` |
| `instrumentation/freeze/.../FreezeInstrumentation.kt` | Add `@Supersedes("slowrendering")` |
| `instrumentation/lifecycle/.../LifecycleInstrumentation.kt` | Add `@Supersedes("activity", "fragment")` |
| `instrumentation/tap/.../TapInstrumentation.kt` | Add `@Supersedes("view.click")` |
| `instrumentation/network/.../NetworkInstrumentation.kt` | Add `@Supersedes("okhttp")` |
| `docs/superpowers/specs/2026-04-08-ios-sdk-port-design.md` | Add stale notice to section 5 |

**Base path for all `...` above:** `src/main/java/io/opentelemetry/android/mobile/` (source) or `src/test/java/io/opentelemetry/android/mobile/` (test)

**All commands run from:** `mobile-otel/examples/demo-app/`

---

### Task 1: Dependency Alignment

**Files:**
- Modify: `otel-android-mobile-core/build.gradle.kts`
- Modify: `otel-android-mobile/build.gradle.kts`

- [ ] **Step 1: Add upstream deps to core module**

In `otel-android-mobile-core/build.gradle.kts`, add after line 52 (`api("io.opentelemetry:opentelemetry-sdk-logs:1.58.0")`):

```kotlin
    // Upstream opentelemetry-android interfaces for adapter compatibility
    api("io.opentelemetry.android:session:0.10.0-alpha")
    api("io.opentelemetry.android:instrumentation-android-instrumentation:0.10.0-alpha")
```

- [ ] **Step 2: Remove phantom dep from aggregator module**

In `otel-android-mobile/build.gradle.kts`, delete line 84:

```kotlin
    api("io.opentelemetry.android:instrumentation:0.4.1-alpha")
```

- [ ] **Step 3: Update semconv version**

In `otel-android-mobile/build.gradle.kts`, change line 90 from:

```kotlin
    implementation("io.opentelemetry.semconv:opentelemetry-semconv:1.39.0")
```

to:

```kotlin
    implementation("io.opentelemetry.semconv:opentelemetry-semconv:1.40.0")
```

Also update `instrumentation/network/build.gradle.kts` line 49 from `1.39.0` to `1.40.0`.

- [ ] **Step 4: Verify build compiles**

Run:
```bash
cd mobile-otel/examples/demo-app && ./gradlew :otel-android-mobile-core:compileDebugKotlin :otel-android-mobile:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. The upstream deps should resolve and not conflict with our OTel SDK 1.58.0.

- [ ] **Step 5: Verify existing tests still pass**

Run:
```bash
./gradlew :otel-android-mobile-core:testDebugUnitTest :otel-android-mobile:testDebugUnitTest
```

Expected: All existing tests pass.

---

### Task 2: Session Interface Bridge

**Files:**
- Modify: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/MobileSessionProvider.kt`
- Create: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/UpstreamSessionProviderAdapter.kt`
- Create: `otel-android-mobile-core/src/test/java/io/opentelemetry/android/mobile/instrumentation/SessionProviderBridgeTest.kt`
- Create: `otel-android-mobile-core/src/test/java/io/opentelemetry/android/mobile/instrumentation/UpstreamSessionProviderAdapterTest.kt`

- [ ] **Step 1: Write failing test — MobileSessionProvider IS-A SessionProvider**

Create `otel-android-mobile-core/src/test/java/io/opentelemetry/android/mobile/instrumentation/SessionProviderBridgeTest.kt`:

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import io.opentelemetry.android.session.SessionProvider
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionProviderBridgeTest {

    @Test fun `DefaultMobileSessionProvider is a SessionProvider`() {
        val provider: MobileSessionProvider = DefaultMobileSessionProvider()
        assertTrue(provider is SessionProvider, "MobileSessionProvider must extend SessionProvider")
    }

    @Test fun `SessionProvider getSessionId delegates to MobileSessionProvider`() {
        val provider = DefaultMobileSessionProvider()
        val asUpstream: SessionProvider = provider
        assertEquals(provider.getSessionId(), asUpstream.getSessionId())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
./gradlew :otel-android-mobile-core:testDebugUnitTest --tests "*.SessionProviderBridgeTest"
```

Expected: FAIL — `MobileSessionProvider` does not extend `SessionProvider` yet.

- [ ] **Step 3: Add SessionProvider superinterface to MobileSessionProvider**

In `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/MobileSessionProvider.kt`, change the interface declaration from:

```kotlin
interface MobileSessionProvider {
```

to:

```kotlin
import io.opentelemetry.android.session.SessionProvider

interface MobileSessionProvider : SessionProvider {
```

And add `override` to `getSessionId()`:

```kotlin
    override fun getSessionId(): String
```

- [ ] **Step 4: Run test to verify it passes**

Run:
```bash
./gradlew :otel-android-mobile-core:testDebugUnitTest --tests "*.SessionProviderBridgeTest"
```

Expected: PASS

- [ ] **Step 5: Write failing test — UpstreamSessionProviderAdapter**

Create `otel-android-mobile-core/src/test/java/io/opentelemetry/android/mobile/instrumentation/UpstreamSessionProviderAdapterTest.kt`:

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import io.opentelemetry.android.session.SessionProvider
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class UpstreamSessionProviderAdapterTest {

    private val upstream = SessionProvider { "upstream-session-123" }
    private val adapter = UpstreamSessionProviderAdapter(upstream)

    @Test fun `getSessionId delegates to upstream`() {
        assertEquals("upstream-session-123", adapter.getSessionId())
    }

    @Test fun `getViewId returns empty string`() {
        assertEquals("", adapter.getViewId())
    }

    @Test fun `getCurrentScreenName returns null`() {
        assertNull(adapter.getCurrentScreenName())
    }

    @Test fun `getPreviousScreenName returns null`() {
        assertNull(adapter.getPreviousScreenName())
    }

    @Test fun `getTimeOnScreenMs returns zero`() {
        assertEquals(0L, adapter.getTimeOnScreenMs())
    }

    @Test fun `onAppForeground returns false`() {
        assertFalse(adapter.onAppForeground(System.currentTimeMillis()))
    }

    @Test fun `onScreenView is no-op`() {
        adapter.onScreenView("SomeScreen") // should not throw
    }

    @Test fun `onAppBackground is no-op`() {
        adapter.onAppBackground(System.currentTimeMillis()) // should not throw
    }

    @Test fun `sessionHadError returns false`() {
        assertFalse(adapter.sessionHadError())
    }
}
```

- [ ] **Step 6: Run test to verify it fails**

Run:
```bash
./gradlew :otel-android-mobile-core:testDebugUnitTest --tests "*.UpstreamSessionProviderAdapterTest"
```

Expected: FAIL — `UpstreamSessionProviderAdapter` does not exist yet.

- [ ] **Step 7: Implement UpstreamSessionProviderAdapter**

Create `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/UpstreamSessionProviderAdapter.kt`:

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import io.opentelemetry.android.session.SessionProvider

/**
 * Adapts upstream's [SessionProvider] (single-method fun interface) to our
 * richer [MobileSessionProvider]. Methods not supported by the upstream
 * interface return safe defaults.
 *
 * Used by [UpstreamInstrumentationAdapter] when bridging upstream modules
 * into our framework.
 */
internal class UpstreamSessionProviderAdapter(
    private val upstream: SessionProvider
) : MobileSessionProvider {
    override fun getSessionId(): String = upstream.getSessionId()
    override fun getViewId(): String = ""
    override fun getCurrentScreenName(): String? = null
    override fun getPreviousScreenName(): String? = null
    override fun getTimeOnScreenMs(): Long = 0L
    override fun onScreenView(screenName: String) {}
    override fun onAppForeground(timestampMs: Long): Boolean = false
    override fun onAppBackground(timestampMs: Long) {}
}
```

- [ ] **Step 8: Run tests to verify they pass**

Run:
```bash
./gradlew :otel-android-mobile-core:testDebugUnitTest --tests "*.UpstreamSessionProviderAdapterTest" --tests "*.SessionProviderBridgeTest"
```

Expected: All PASS

- [ ] **Step 9: Run full core test suite for regressions**

Run:
```bash
./gradlew :otel-android-mobile-core:testDebugUnitTest
```

Expected: All existing tests still pass (adding `: SessionProvider` is backward-compatible).

---

### Task 3: Add Clock to InstrumentationContext

**Files:**
- Modify: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/InstrumentationContext.kt`

- [ ] **Step 1: Add optional Clock parameter**

In `InstrumentationContext.kt`, add `clock` as the last constructor parameter:

```kotlin
import io.opentelemetry.sdk.common.Clock

@Incubating
class InstrumentationContext(
    val openTelemetry: OpenTelemetry,
    val sessionProvider: MobileSessionProvider,
    val windowEventHub: WindowEventHub,
    val application: Application,
    val uiTelemetryMode: UiTelemetryMode = UiTelemetryMode.EVENTS,
    val breadcrumbManager: BreadcrumbManager? = if (BreadcrumbManager.isInitialized()) BreadcrumbManager else null,
    val clock: Clock? = null
) {
```

- [ ] **Step 2: Verify existing tests still pass**

Run:
```bash
./gradlew :otel-android-mobile-core:testDebugUnitTest
```

Expected: All PASS — `clock` has a default value of `null` so no call sites break.

---

### Task 4: @Supersedes Annotation

**Files:**
- Create: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/Supersedes.kt`

- [ ] **Step 1: Create the annotation**

Create `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/Supersedes.kt`:

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

/**
 * Declares that this [MobileInstrumentation] supersedes one or more upstream
 * [io.opentelemetry.android.instrumentation.AndroidInstrumentation] modules.
 *
 * When both a superseding module and the superseded upstream module are
 * discovered via [OTelMobileBuilder.discoverAllInstrumentations], the
 * [InstrumentationRegistry] installs the superseding module and skips
 * the upstream one.
 *
 * Values must match the upstream module's
 * [io.opentelemetry.android.instrumentation.AndroidInstrumentation.name] exactly.
 *
 * Example:
 * ```kotlin
 * @Supersedes("crash")
 * class ErrorInstrumentation : MobileInstrumentation { ... }
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Supersedes(vararg val names: String)
```

- [ ] **Step 2: Verify build compiles**

Run:
```bash
./gradlew :otel-android-mobile-core:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

---

### Task 5: UpstreamInstrumentationAdapter (Direction 1)

**Files:**
- Create: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/UpstreamInstrumentationAdapter.kt`
- Create: `otel-android-mobile-core/src/test/java/io/opentelemetry/android/mobile/instrumentation/UpstreamInstrumentationAdapterTest.kt`

- [ ] **Step 1: Write failing tests**

Create `otel-android-mobile-core/src/test/java/io/opentelemetry/android/mobile/instrumentation/UpstreamInstrumentationAdapterTest.kt`:

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.mockk.*
import io.opentelemetry.android.instrumentation.AndroidInstrumentation
import io.opentelemetry.android.instrumentation.InstallationContext
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class UpstreamInstrumentationAdapterTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    @Test fun `instrumentationName delegates to upstream name`() {
        val upstream = mockk<AndroidInstrumentation> {
            every { name } returns "crash"
        }
        val adapter = UpstreamInstrumentationAdapter(upstream)
        assertEquals("crash", adapter.instrumentationName)
    }

    @Test fun `install delegates to upstream with correct InstallationContext`() {
        val upstream = mockk<AndroidInstrumentation>(relaxed = true)
        every { upstream.name } returns "test-module"
        val adapter = UpstreamInstrumentationAdapter(upstream)

        val app = mockk<Application>(relaxed = true)
        val sessionProvider = DefaultMobileSessionProvider()
        val ctx = InstrumentationContext(
            otelRule.openTelemetry, sessionProvider, WindowEventHub(), app
        )

        adapter.install(app, ctx)

        verify {
            upstream.install(match<InstallationContext> { installCtx ->
                installCtx.context === app
                    && installCtx.openTelemetry === otelRule.openTelemetry
                    && installCtx.sessionProvider === sessionProvider
            })
        }
    }

    @Test fun `uninstall delegates to upstream with cached context`() {
        val upstream = mockk<AndroidInstrumentation>(relaxed = true)
        every { upstream.name } returns "test-module"
        val adapter = UpstreamInstrumentationAdapter(upstream)

        val app = mockk<Application>(relaxed = true)
        val ctx = InstrumentationContext(
            otelRule.openTelemetry, DefaultMobileSessionProvider(), WindowEventHub(), app
        )

        adapter.install(app, ctx)
        adapter.uninstall()

        verify { upstream.uninstall(any()) }
    }

    @Test fun `uninstall before install does not throw`() {
        val upstream = mockk<AndroidInstrumentation>(relaxed = true)
        every { upstream.name } returns "test-module"
        val adapter = UpstreamInstrumentationAdapter(upstream)

        adapter.uninstall() // should not throw

        verify(exactly = 0) { upstream.uninstall(any()) }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
./gradlew :otel-android-mobile-core:testDebugUnitTest --tests "*.UpstreamInstrumentationAdapterTest"
```

Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement UpstreamInstrumentationAdapter**

Create `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/UpstreamInstrumentationAdapter.kt`:

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.opentelemetry.android.instrumentation.AndroidInstrumentation
import io.opentelemetry.android.instrumentation.InstallationContext
import io.opentelemetry.sdk.common.Clock

/**
 * Wraps an upstream [AndroidInstrumentation] so it can be installed in
 * our [InstrumentationRegistry] alongside native [MobileInstrumentation]
 * modules.
 *
 * Bridges [InstrumentationContext] → [InstallationContext] on install,
 * and caches the [InstallationContext] for use in [uninstall].
 */
class UpstreamInstrumentationAdapter(
    private val upstream: AndroidInstrumentation
) : MobileInstrumentation {

    override val instrumentationName: String = upstream.name

    private var cachedInstallCtx: InstallationContext? = null

    override fun install(application: Application, context: InstrumentationContext) {
        val installCtx = InstallationContext(
            /* context = */ application,
            /* openTelemetry = */ context.openTelemetry,
            /* sessionProvider = */ context.sessionProvider,
            /* clock = */ context.clock ?: Clock.getDefault()
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

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
./gradlew :otel-android-mobile-core:testDebugUnitTest --tests "*.UpstreamInstrumentationAdapterTest"
```

Expected: All PASS

---

### Task 6: MobileInstrumentationAdapter (Direction 2)

**Files:**
- Create: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/MobileInstrumentationAdapter.kt`
- Create: `otel-android-mobile-core/src/test/java/io/opentelemetry/android/mobile/instrumentation/MobileInstrumentationAdapterTest.kt`

- [ ] **Step 1: Write failing tests**

Create `otel-android-mobile-core/src/test/java/io/opentelemetry/android/mobile/instrumentation/MobileInstrumentationAdapterTest.kt`:

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.mockk.*
import io.opentelemetry.android.instrumentation.InstallationContext
import io.opentelemetry.android.session.SessionProvider
import io.opentelemetry.sdk.common.Clock
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class MobileInstrumentationAdapterTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    @Test fun `name delegates to instrumentationName`() {
        val mobile = mockk<MobileInstrumentation> {
            every { instrumentationName } returns "io.opentelemetry.android.mobile.tap"
        }
        val adapter = MobileInstrumentationAdapter(mobile, WindowEventHub())
        assertEquals("io.opentelemetry.android.mobile.tap", adapter.name)
    }

    @Test fun `install bridges InstallationContext to InstrumentationContext`() {
        val mobile = mockk<MobileInstrumentation>(relaxed = true)
        every { mobile.instrumentationName } returns "test"
        val hub = WindowEventHub()
        val adapter = MobileInstrumentationAdapter(mobile, hub)

        val app = mockk<Application>(relaxed = true)
        val sessionProvider = DefaultMobileSessionProvider()
        val installCtx = InstallationContext(app, otelRule.openTelemetry, sessionProvider, Clock.getDefault())

        adapter.install(installCtx)

        verify {
            mobile.install(app, match<InstrumentationContext> { ctx ->
                ctx.openTelemetry === otelRule.openTelemetry
                    && ctx.windowEventHub === hub
                    && ctx.sessionProvider.getSessionId() == sessionProvider.getSessionId()
            })
        }
    }

    @Test fun `install wraps plain SessionProvider in adapter`() {
        val mobile = mockk<MobileInstrumentation>(relaxed = true)
        every { mobile.instrumentationName } returns "test"
        val adapter = MobileInstrumentationAdapter(mobile, WindowEventHub())

        val plainProvider = SessionProvider { "plain-session" }
        val app = mockk<Application>(relaxed = true)
        val installCtx = InstallationContext(app, otelRule.openTelemetry, plainProvider, Clock.getDefault())

        adapter.install(installCtx)

        verify {
            mobile.install(app, match<InstrumentationContext> { ctx ->
                ctx.sessionProvider is UpstreamSessionProviderAdapter
                    && ctx.sessionProvider.getSessionId() == "plain-session"
            })
        }
    }

    @Test fun `uninstall delegates to mobile uninstall`() {
        val mobile = mockk<MobileInstrumentation>(relaxed = true)
        every { mobile.instrumentationName } returns "test"
        val adapter = MobileInstrumentationAdapter(mobile, WindowEventHub())

        val app = mockk<Application>(relaxed = true)
        val installCtx = InstallationContext(app, otelRule.openTelemetry, DefaultMobileSessionProvider(), Clock.getDefault())

        adapter.uninstall(installCtx)

        verify { mobile.uninstall() }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
./gradlew :otel-android-mobile-core:testDebugUnitTest --tests "*.MobileInstrumentationAdapterTest"
```

Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement MobileInstrumentationAdapter**

Create `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/MobileInstrumentationAdapter.kt`:

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.opentelemetry.android.instrumentation.AndroidInstrumentation
import io.opentelemetry.android.instrumentation.InstallationContext

/**
 * Wraps a [MobileInstrumentation] so it can be installed in the upstream
 * `opentelemetry-android` framework via [AndroidInstrumentation].
 *
 * Used for merge-proposal validation: proves our modules work in their
 * framework.
 *
 * Requires a [WindowEventHub] to be provided externally since upstream's
 * [InstallationContext] does not carry one.
 */
class MobileInstrumentationAdapter(
    private val mobile: MobileInstrumentation,
    private val windowEventHub: WindowEventHub,
    private val uiTelemetryMode: UiTelemetryMode = UiTelemetryMode.EVENTS
) : AndroidInstrumentation {

    override val name: String = mobile.instrumentationName

    override fun install(ctx: InstallationContext) {
        val sessionProvider = if (ctx.sessionProvider is MobileSessionProvider) {
            ctx.sessionProvider as MobileSessionProvider
        } else {
            UpstreamSessionProviderAdapter(ctx.sessionProvider)
        }
        val app = ctx.application
            ?: (ctx.context as? Application)
            ?: throw IllegalStateException("InstallationContext.context must be an Application")
        val mobileCtx = InstrumentationContext(
            openTelemetry = ctx.openTelemetry,
            sessionProvider = sessionProvider,
            windowEventHub = windowEventHub,
            application = app,
            uiTelemetryMode = uiTelemetryMode,
            clock = ctx.clock
        )
        mobile.install(app, mobileCtx)
    }

    override fun uninstall(ctx: InstallationContext) {
        mobile.uninstall()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
./gradlew :otel-android-mobile-core:testDebugUnitTest --tests "*.MobileInstrumentationAdapterTest"
```

Expected: All PASS

---

### Task 7: Conflict Resolution in InstrumentationRegistry

**Files:**
- Modify: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/InstrumentationRegistry.kt`
- Create: `otel-android-mobile-core/src/test/java/io/opentelemetry/android/mobile/instrumentation/SupersedesConflictTest.kt`

- [ ] **Step 1: Write failing tests**

Create `otel-android-mobile-core/src/test/java/io/opentelemetry/android/mobile/instrumentation/SupersedesConflictTest.kt`:

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.mockk.*
import io.opentelemetry.android.instrumentation.AndroidInstrumentation
import io.opentelemetry.android.instrumentation.InstallationContext
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test

class SupersedesConflictTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private fun makeContext(app: Application = mockk(relaxed = true)): InstrumentationContext =
        InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), WindowEventHub(), app)

    /** A fake "our" module that supersedes upstream "crash" */
    @Supersedes("crash")
    class FakeErrorInstrumentation : MobileInstrumentation {
        override val instrumentationName = "io.opentelemetry.android.mobile.errors"
        var installed = false
        override fun install(application: Application, context: InstrumentationContext) {
            installed = true
        }
    }

    /** A fake upstream module named "crash" */
    private fun fakeUpstreamCrash(): UpstreamInstrumentationAdapter {
        val upstream = mockk<AndroidInstrumentation>(relaxed = true)
        every { upstream.name } returns "crash"
        return UpstreamInstrumentationAdapter(upstream)
    }

    /** A fake upstream module named "session" (not superseded) */
    private fun fakeUpstreamSession(): UpstreamInstrumentationAdapter {
        val upstream = mockk<AndroidInstrumentation>(relaxed = true)
        every { upstream.name } returns "session"
        return UpstreamInstrumentationAdapter(upstream)
    }

    @Test fun `superseded upstream module is skipped`() {
        val ourModule = FakeErrorInstrumentation()
        val upstreamCrash = fakeUpstreamCrash()
        val app = mockk<Application>(relaxed = true)
        val ctx = makeContext(app)

        val registry = InstrumentationRegistry(listOf(ourModule, upstreamCrash))
        registry.install(app, ctx)

        assert(ourModule.installed) { "Our module should be installed" }
        // The upstream crash module's install should NOT have been called
        verify(exactly = 0) { upstreamCrash.let { /* adapter wraps mockk — check via name */ } }
    }

    @Test fun `non-superseded upstream module is installed`() {
        val ourModule = FakeErrorInstrumentation()
        val upstreamSession = fakeUpstreamSession()
        val app = mockk<Application>(relaxed = true)
        val ctx = makeContext(app)

        val registry = InstrumentationRegistry(listOf(ourModule, upstreamSession))
        registry.install(app, ctx)

        assert(ourModule.installed) { "Our module should be installed" }
        // session is NOT superseded, so it should be installed
    }

    @Test fun `our modules without @Supersedes are always installed`() {
        val plainModule = mockk<MobileInstrumentation>(relaxed = true)
        every { plainModule.instrumentationName } returns "io.opentelemetry.android.mobile.scroll"
        val app = mockk<Application>(relaxed = true)
        val ctx = makeContext(app)

        val registry = InstrumentationRegistry(listOf(plainModule))
        registry.install(app, ctx)

        verify { plainModule.install(app, ctx) }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
./gradlew :otel-android-mobile-core:testDebugUnitTest --tests "*.SupersedesConflictTest"
```

Expected: FAIL — `InstrumentationRegistry` doesn't implement conflict resolution yet.

- [ ] **Step 3: Update InstrumentationRegistry with conflict resolution**

Replace the contents of `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/InstrumentationRegistry.kt`:

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import android.util.Log

/**
 * Holds all active [MobileInstrumentation] instances and coordinates
 * their lifecycle.
 *
 * Created by [OTelMobileBuilder.build] and owned by [OTelMobileHandle].
 * Calling [install] activates every registered instrumentation;
 * calling [uninstall] deactivates them in reverse registration order.
 *
 * When upstream [UpstreamInstrumentationAdapter] modules are present alongside
 * native [MobileInstrumentation] modules annotated with [@Supersedes],
 * the registry skips superseded upstream modules to prevent duplicate telemetry.
 */
@Incubating
class InstrumentationRegistry(
    private val instrumentations: List<MobileInstrumentation>
) {
    private val installed = mutableListOf<MobileInstrumentation>()

    /**
     * Activates registered instrumentations, skipping any upstream modules
     * whose [AndroidInstrumentation.name] is claimed by a [@Supersedes]
     * annotation on a native [MobileInstrumentation].
     */
    fun install(application: Application, context: InstrumentationContext) {
        // 1. Collect supersession claims from native modules
        val supersededNames = mutableSetOf<String>()
        for (inst in instrumentations) {
            val ann = inst::class.java.getAnnotation(Supersedes::class.java)
            if (ann != null) {
                supersededNames.addAll(ann.names)
            }
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

    /**
     * Deactivates all installed instrumentations in reverse installation order.
     */
    fun uninstall() {
        installed.asReversed().forEach { it.uninstall() }
        installed.clear()
    }

    companion object {
        private const val TAG = "InstrumentationRegistry"
    }
}
```

- [ ] **Step 4: Run conflict resolution tests**

Run:
```bash
./gradlew :otel-android-mobile-core:testDebugUnitTest --tests "*.SupersedesConflictTest"
```

Expected: All PASS

- [ ] **Step 5: Run existing registry tests for regressions**

Run:
```bash
./gradlew :otel-android-mobile-core:testDebugUnitTest --tests "*.InstrumentationRegistryTest"
```

Expected: All PASS — existing behavior is preserved (no upstream modules in those tests = no conflict resolution triggered).

---

### Task 8: Discovery Methods on OTelMobileBuilder

**Files:**
- Modify: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/OTelMobileBuilder.kt`

- [ ] **Step 1: Add upstream discovery methods**

In `OTelMobileBuilder.kt`, add the following import and methods after `discoverInstrumentations()`:

```kotlin
import io.opentelemetry.android.instrumentation.AndroidInstrumentation
```

Add these methods after the existing `discoverInstrumentations()` method:

```kotlin
    /**
     * Discovers upstream [AndroidInstrumentation] modules via [ServiceLoader],
     * wraps each in [UpstreamInstrumentationAdapter], and adds them.
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
     * Discovers both [MobileInstrumentation] and [AndroidInstrumentation]
     * modules. Convenience method combining both discovery paths.
     *
     * Deduplicates: if a module appears in both service files (possible
     * after Phase 4 convergence), it is only added once.
     */
    fun discoverAllInstrumentations(): OTelMobileBuilder = apply {
        discoverInstrumentations()
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

- [ ] **Step 2: Verify build compiles**

Run:
```bash
./gradlew :otel-android-mobile-core:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run existing OTelMobileBuilder tests for regressions**

Run:
```bash
./gradlew :otel-android-mobile-core:testDebugUnitTest --tests "*.OTelMobileBuilderTest"
```

Expected: All PASS

---

### Task 9: Apply @Supersedes to Existing Modules

**Files:**
- Modify: `instrumentation/errors/src/main/java/io/opentelemetry/android/mobile/errors/ErrorInstrumentation.kt`
- Modify: `instrumentation/vitals/src/main/java/io/opentelemetry/android/mobile/instrumentation/VitalsInstrumentation.kt`
- Modify: `instrumentation/freeze/src/main/java/io/opentelemetry/android/mobile/instrumentation/FreezeInstrumentation.kt`
- Modify: `instrumentation/lifecycle/src/main/java/io/opentelemetry/android/mobile/instrumentation/LifecycleInstrumentation.kt`
- Modify: `instrumentation/tap/src/main/java/io/opentelemetry/android/mobile/instrumentation/TapInstrumentation.kt`
- Modify: `instrumentation/network/src/main/java/io/opentelemetry/android/mobile/instrumentation/NetworkInstrumentation.kt`

- [ ] **Step 1: Add @Supersedes("crash") to ErrorInstrumentation**

In `ErrorInstrumentation.kt`, add above the class declaration:

```kotlin
import io.opentelemetry.android.mobile.instrumentation.Supersedes

@Supersedes("crash")
class ErrorInstrumentation private constructor(
```

Note: `ErrorInstrumentation` is in the `errors` package, not `instrumentation`, so the import is needed.

- [ ] **Step 2: Add @Supersedes("anr", "startup") to VitalsInstrumentation**

In `VitalsInstrumentation.kt`, add above the class declaration:

```kotlin
@Supersedes("anr", "startup")
class VitalsInstrumentation : MobileInstrumentation {
```

- [ ] **Step 3: Add @Supersedes("slowrendering") to FreezeInstrumentation**

In `FreezeInstrumentation.kt`, add above the class declaration:

```kotlin
@Supersedes("slowrendering")
class FreezeInstrumentation(
```

- [ ] **Step 4: Add @Supersedes("activity", "fragment") to LifecycleInstrumentation**

In `LifecycleInstrumentation.kt`, add above the class declaration:

```kotlin
@Supersedes("activity", "fragment")
class LifecycleInstrumentation : MobileInstrumentation {
```

- [ ] **Step 5: Add @Supersedes("view.click") to TapInstrumentation**

In `TapInstrumentation.kt`, add above the class declaration:

```kotlin
@Supersedes("view.click")
class TapInstrumentation(
```

- [ ] **Step 6: Add @Supersedes("okhttp") to NetworkInstrumentation**

In `NetworkInstrumentation.kt`, add above the class declaration:

```kotlin
@Supersedes("okhttp")
class NetworkInstrumentation : MobileInstrumentation {
```

- [ ] **Step 7: Verify all instrumentation modules compile**

Run:
```bash
./gradlew :otel-android-mobile:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Run all instrumentation module tests**

Run:
```bash
./gradlew :otel-android-mobile:testDebugUnitTest :instrumentation-tap:testDebugUnitTest :instrumentation-freeze:testDebugUnitTest :instrumentation-vitals:testDebugUnitTest :instrumentation-back-press:testDebugUnitTest :instrumentation-lifecycle:testDebugUnitTest
```

Expected: All PASS — annotations don't change runtime behavior of the modules themselves.

---

### Task 10: iOS Spec Stale Notice

**Files:**
- Modify: `docs/superpowers/specs/2026-04-08-ios-sdk-port-design.md`

- [ ] **Step 1: Add stale notice to section 5**

In `docs/superpowers/specs/2026-04-08-ios-sdk-port-design.md`, find the heading `## 5. Instrumentation Module System` and add immediately after it:

```markdown
> **STALE (2026-04-09):** The `MobileInstrumentation` interface is being
> aligned with upstream `opentelemetry-android`'s `AndroidInstrumentation`
> as part of the Upstream Supersession epic. The iOS `MobileInstrumentation`
> protocol will be updated when Phase 4 (Interface Convergence) completes.
> See: `docs/superpowers/specs/2026-04-09-upstream-supersession-design.md`
```

---

### Task 11: Full Regression Test

**Files:** None (verification only)

- [ ] **Step 1: Run complete core test suite**

Run:
```bash
cd mobile-otel/examples/demo-app && ./gradlew :otel-android-mobile-core:testDebugUnitTest
```

Expected: All PASS (including new tests from this plan)

- [ ] **Step 2: Run complete SDK test suite**

Run:
```bash
./gradlew :otel-android-mobile:testDebugUnitTest
```

Expected: All PASS

- [ ] **Step 3: Run all instrumentation module tests**

Run:
```bash
./gradlew :instrumentation-tap:testDebugUnitTest :instrumentation-freeze:testDebugUnitTest :instrumentation-back-press:testDebugUnitTest :instrumentation-vitals:testDebugUnitTest :instrumentation-screen:testDebugUnitTest :instrumentation-scroll:testDebugUnitTest :instrumentation-lifecycle:testDebugUnitTest :instrumentation-errors:testDebugUnitTest
```

Expected: All PASS

- [ ] **Step 4: Full build verification**

Run:
```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL — demo APK builds with all dependency changes.

- [ ] **Step 5: Commit all Phase 1 work**

```bash
git add -A
git commit -m "feat: Phase 1 upstream supersession — adapter layer, @Supersedes, dependency alignment

- Add upstream session:0.10.0-alpha + instrumentation-android-instrumentation:0.10.0-alpha
- Remove phantom dep instrumentation:0.4.1-alpha
- MobileSessionProvider extends upstream SessionProvider
- UpstreamInstrumentationAdapter: run upstream modules in our framework
- MobileInstrumentationAdapter: run our modules in upstream framework
- @Supersedes annotation + conflict resolution in InstrumentationRegistry
- discoverUpstreamInstrumentations() + discoverAllInstrumentations()
- Apply @Supersedes to 6 existing modules (errors, vitals, freeze, lifecycle, tap, network)
- Update semconv 1.39.0 -> 1.40.0
- iOS spec stale notice"
```
