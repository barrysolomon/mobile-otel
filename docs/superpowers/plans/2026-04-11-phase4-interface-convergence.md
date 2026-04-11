# Phase 4: Interface Convergence — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bump upstream opentelemetry-android from 0.10.0-alpha to 1.2.0-alpha and converge `MobileInstrumentation` with `AndroidInstrumentation`.

**Architecture:** Single atomic commit: bump deps + converge interface + simplify adapter + delete dead code + update tests. All 14 build-wired instrumentation modules require zero changes — the interface change is backward-compatible via default method implementations.

**Tech Stack:** Kotlin (JDK 17), Gradle, MockK, Robolectric, OpenTelemetry SDK 1.58.0

**Spec:** `docs/superpowers/specs/2026-04-11-phase4-interface-convergence-design.md`

**CRITICAL — Build sequencing:** All changes in this plan MUST be applied atomically (single commit). The dependency bump removes `SessionManager` from the classpath, which breaks files that import it. Those files must be updated/deleted in the same commit.

---

## File Map

### Modified

| File | Change |
| --- | --- |
| `otel-android-mobile-core/build.gradle.kts` | Bump deps, new artifact coordinates |
| `otel-android-mobile-core/.../MobileInstrumentation.kt` | Extend `AndroidInstrumentation` |
| `otel-android-mobile-core/.../InstrumentationContext.kt` | Add `fromInstallationContext()` factory |
| `otel-android-mobile-core/.../UpstreamInstrumentationAdapter.kt` | Simplify: 1-arg constructor, no session bridge |
| `otel-android-mobile-core/.../InstrumentationRegistry.kt` | Name-based conflict resolution |
| `otel-android-mobile-core/.../OTelMobileBuilder.kt` | `!is MobileInstrumentation` guard |
| `otel-android-mobile-core/.../test/.../SupersedesConflictTest.kt` | Update `fakeUpstream()` helper |
| `otel-android-mobile-core/.../test/.../UpstreamInstrumentationAdapterTest.kt` | Update for simplified adapter |

### Deleted

| File | Reason |
| --- | --- |
| `otel-android-mobile-core/.../MobileInstrumentationAdapter.kt` | Our modules ARE AndroidInstrumentation now |
| `otel-android-mobile-core/.../test/.../MobileInstrumentationAdapterTest.kt` | Tests for deleted adapter |

### New

| File | Purpose |
| --- | --- |
| `otel-android-mobile-core/.../test/.../InterfaceConvergenceTest.kt` | Verify interface hierarchy + bridge behavior |

All paths relative to `mobile-otel/`. Full base: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/` for source, `otel-android-mobile-core/src/test/java/io/opentelemetry/android/mobile/instrumentation/` for tests.

---

### Task 1: Delete Dead Adapter Files

These files import `SessionManager` which won't exist after the dependency bump. Delete them first.

**Files:**
- Delete: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/MobileInstrumentationAdapter.kt`
- Delete: `otel-android-mobile-core/src/test/java/io/opentelemetry/android/mobile/instrumentation/MobileInstrumentationAdapterTest.kt`

- [ ] **Step 1: Delete both files**

```bash
cd mobile-otel
rm otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/MobileInstrumentationAdapter.kt
rm otel-android-mobile-core/src/test/java/io/opentelemetry/android/mobile/instrumentation/MobileInstrumentationAdapterTest.kt
```

- [ ] **Step 2: Verify no other files reference MobileInstrumentationAdapter**

```bash
grep -r "MobileInstrumentationAdapter" otel-android-mobile-core/src/ --include="*.kt"
```

Expected: no output (no remaining references)

---

### Task 2: Bump Upstream Dependencies

**Files:**
- Modify: `otel-android-mobile-core/build.gradle.kts:55-60`

- [ ] **Step 1: Update dependency coordinates and versions**

Replace lines 55-60 in `otel-android-mobile-core/build.gradle.kts`:

```kotlin
// Before:
    api("io.opentelemetry.android:session:0.10.0-alpha") {
        exclude(group = "io.opentelemetry", module = "opentelemetry-api-incubator")
    }
    api("io.opentelemetry.android:instrumentation-android-instrumentation:0.10.0-alpha") {
        exclude(group = "io.opentelemetry", module = "opentelemetry-api-incubator")
    }

// After:
    api("io.opentelemetry.android:session:1.2.0-alpha") {
        exclude(group = "io.opentelemetry", module = "opentelemetry-api-incubator")
    }
    api("io.opentelemetry.android.instrumentation:android-instrumentation:1.2.0-alpha") {
        exclude(group = "io.opentelemetry", module = "opentelemetry-api-incubator")
    }
```

Note: The instrumentation artifact group changed from `io.opentelemetry.android` to `io.opentelemetry.android.instrumentation`.

- [ ] **Step 2: Verify Gradle can resolve the new dependencies (will NOT compile yet — other files still reference old API)**

```bash
cd examples/demo-app
./gradlew :otel-android-mobile-core:dependencies --configuration releaseRuntimeClasspath --quiet 2>&1 | grep "android.instrumentation\|android:session"
```

Expected: should show `1.2.0-alpha` versions resolved

---

### Task 3: Converge MobileInstrumentation Interface

**Files:**
- Modify: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/MobileInstrumentation.kt`

- [ ] **Step 1: Replace the entire file content**

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.opentelemetry.android.instrumentation.AndroidInstrumentation
import io.opentelemetry.android.instrumentation.InstallationContext

/**
 * Contract for a single unit of mobile auto-instrumentation.
 *
 * Extends upstream [AndroidInstrumentation] with identity fields and
 * an [InstrumentationContext] carrier that provides shared state (session,
 * window events, OpenTelemetry instance).
 *
 * Each capability (taps, scrolls, lifecycle, errors, …) ships as a separate
 * implementation in its own Gradle module and is discoverable via the Java
 * [java.util.ServiceLoader] SPI.
 *
 * Upstream callers using the [AndroidInstrumentation] interface can install
 * these modules via [install(InstallationContext)][AndroidInstrumentation.install],
 * which bridges to [install(Application, InstrumentationContext)].
 */
@Incubating
interface MobileInstrumentation : AndroidInstrumentation {
    /** Unique name identifying this instrumentation, e.g. "io.opentelemetry.android.mobile.tap". */
    val instrumentationName: String

    /** Version of this instrumentation. */
    val instrumentationVersion: String get() = "1.0.0"

    /** Bridge to upstream — [name] delegates to [instrumentationName]. */
    override val name: String get() = instrumentationName

    /**
     * Called by [InstrumentationRegistry] to activate this instrumentation.
     * Implementations should register any callbacks or observers they need
     * and retain only weak references to [application] beyond this call.
     */
    fun install(application: Application, context: InstrumentationContext)

    /**
     * Upstream bridge — builds an [InstrumentationContext] from [ctx] and
     * delegates to [install(Application, InstrumentationContext)].
     */
    override fun install(ctx: InstallationContext) {
        val app = ctx.application
            ?: throw IllegalStateException(
                "MobileInstrumentation requires Application context, got ${ctx.context.javaClass.name}"
            )
        install(app, InstrumentationContext.fromInstallationContext(ctx))
    }

    /** Upstream bridge — delegates to [uninstall()]. */
    override fun uninstall(ctx: InstallationContext) {
        uninstall()
    }

    /**
     * Called by [InstrumentationRegistry] to deactivate and clean up.
     * Implementations must unregister all callbacks registered in [install].
     */
    fun uninstall() {}
}
```

---

### Task 4: Add InstrumentationContext Factory

**Files:**
- Modify: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/InstrumentationContext.kt`

- [ ] **Step 1: Add imports and companion factory**

Add these imports at the top (after existing imports):

```kotlin
import io.opentelemetry.android.instrumentation.InstallationContext
import io.opentelemetry.sdk.common.Clock
```

Add the companion object inside the class body, before the existing `addBreadcrumb` method:

```kotlin
    companion object {
        /**
         * Build from upstream's [InstallationContext].
         * Used by the default [MobileInstrumentation.install] bridge.
         */
        fun fromInstallationContext(ctx: InstallationContext): InstrumentationContext {
            val app = ctx.application
                ?: throw IllegalStateException("Application context required")
            val hub = WindowEventHub()
            WindowEventHubInstaller(app, hub).install()
            return InstrumentationContext(
                openTelemetry = ctx.openTelemetry,
                sessionProvider = UpstreamSessionProviderAdapter(ctx.sessionProvider),
                windowEventHub = hub,
                application = app,
                clock = ctx.clock
            )
        }
    }
```

---

### Task 5: Simplify UpstreamInstrumentationAdapter

**Files:**
- Modify: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/UpstreamInstrumentationAdapter.kt`

- [ ] **Step 1: Replace the entire file content**

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.opentelemetry.android.instrumentation.AndroidInstrumentation
import io.opentelemetry.android.instrumentation.InstallationContext
import io.opentelemetry.sdk.common.Clock

/**
 * Wraps an upstream [AndroidInstrumentation] as a [MobileInstrumentation],
 * allowing upstream modules discovered via ServiceLoader to be installed
 * alongside native mobile modules through [InstrumentationRegistry].
 *
 * Uses [upstream]'s [name][AndroidInstrumentation.name] property directly.
 */
class UpstreamInstrumentationAdapter(
    private val upstream: AndroidInstrumentation
) : MobileInstrumentation {

    override val instrumentationName: String = upstream.name

    override fun install(application: Application, context: InstrumentationContext) {
        upstream.install(InstallationContext(
            application,
            context.openTelemetry,
            context.sessionProvider,  // MobileSessionProvider IS SessionProvider
            context.clock ?: Clock.getDefault()
        ))
    }
}
```

---

### Task 6: Update InstrumentationRegistry Conflict Resolution

**Files:**
- Modify: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/InstrumentationRegistry.kt`

- [ ] **Step 1: Replace the install() method body**

Replace the `install` function (lines 38-58) with:

```kotlin
    fun install(application: Application, context: InstrumentationContext) {
        sessionProvider = context.sessionProvider

        // Collect names that native modules supersede
        val supersededNames = mutableSetOf<String>()
        for (inst in instrumentations) {
            val ann = inst::class.java.getAnnotation(Supersedes::class.java)
            if (ann != null) {
                supersededNames.addAll(ann.names)
            }
        }

        for (inst in instrumentations) {
            // Skip modules whose name is superseded, unless they're the superseder
            // (modules WITH @Supersedes are our native replacements — they go through)
            if (inst.instrumentationName in supersededNames
                && inst::class.java.getAnnotation(Supersedes::class.java) == null
            ) {
                Log.i(TAG, "Skipping ${inst.instrumentationName} -- superseded")
                continue
            }
            inst.install(application, context)
            installed.add(inst)
        }
    }
```

---

### Task 7: Update OTelMobileBuilder Discovery

**Files:**
- Modify: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/OTelMobileBuilder.kt:75-104`

- [ ] **Step 1: Replace discoverUpstreamInstrumentations()**

Replace lines 75-82:

```kotlin
    fun discoverUpstreamInstrumentations(): OTelMobileBuilder = apply {
        ServiceLoader.load(
            AndroidInstrumentation::class.java,
            AndroidInstrumentation::class.java.classLoader
        ).forEach { upstream ->
            // Our modules extend both interfaces — skip them to avoid double-registration
            if (upstream !is MobileInstrumentation) {
                instrumentations.add(UpstreamInstrumentationAdapter(upstream))
            }
        }
    }
```

- [ ] **Step 2: Replace discoverAllInstrumentations()**

Replace lines 92-104:

```kotlin
    fun discoverAllInstrumentations(): OTelMobileBuilder = apply {
        discoverInstrumentations()
        val existingNames = instrumentations.map { it.instrumentationName }.toSet()
        ServiceLoader.load(
            AndroidInstrumentation::class.java,
            AndroidInstrumentation::class.java.classLoader
        ).forEach { upstream ->
            if (upstream !is MobileInstrumentation && upstream.name !in existingNames) {
                instrumentations.add(UpstreamInstrumentationAdapter(upstream))
            }
        }
    }
```

---

### Task 8: Update SupersedesConflictTest

**Files:**
- Modify: `otel-android-mobile-core/src/test/java/io/opentelemetry/android/mobile/instrumentation/SupersedesConflictTest.kt`

- [ ] **Step 1: Update fakeUpstream() helper**

Replace the `fakeUpstream` function (lines 42-45):

```kotlin
    private fun fakeUpstream(name: String): UpstreamInstrumentationAdapter {
        val upstream = mockk<AndroidInstrumentation>(relaxed = true)
        every { upstream.name } returns name
        return UpstreamInstrumentationAdapter(upstream)
    }
```

- [ ] **Step 2: Run the test**

```bash
cd examples/demo-app
./gradlew :otel-android-mobile-core:test --tests "*.SupersedesConflictTest" -q
```

Expected: 4 tests pass

---

### Task 9: Update UpstreamInstrumentationAdapterTest

**Files:**
- Modify: `otel-android-mobile-core/src/test/java/io/opentelemetry/android/mobile/instrumentation/UpstreamInstrumentationAdapterTest.kt`

- [ ] **Step 1: Replace the entire file content**

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

    private val app = mockk<Application>(relaxed = true)

    private fun makeContext(): InstrumentationContext =
        InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), WindowEventHub(), app)

    @Test fun `instrumentationName returns upstream name`() {
        val upstream = mockk<AndroidInstrumentation>(relaxed = true)
        every { upstream.name } returns "upstream.crash"
        val adapter = UpstreamInstrumentationAdapter(upstream)
        assertEquals("upstream.crash", adapter.instrumentationName)
    }

    @Test fun `install creates InstallationContext and calls upstream install`() {
        val upstream = mockk<AndroidInstrumentation>(relaxed = true)
        every { upstream.name } returns "upstream.session"
        val adapter = UpstreamInstrumentationAdapter(upstream)
        val ctx = makeContext()

        adapter.install(app, ctx)

        verify { upstream.install(match<InstallationContext> {
            it.application == app && it.openTelemetry === ctx.openTelemetry
        }) }
    }

    @Test fun `install passes SessionProvider that delegates to context sessionProvider`() {
        val upstream = mockk<AndroidInstrumentation>(relaxed = true)
        every { upstream.name } returns "upstream.session"
        val sessionProvider = DefaultMobileSessionProvider()
        val ctx = InstrumentationContext(otelRule.openTelemetry, sessionProvider, WindowEventHub(), app)
        val adapter = UpstreamInstrumentationAdapter(upstream)

        adapter.install(app, ctx)

        verify { upstream.install(match<InstallationContext> {
            it.sessionProvider.getSessionId() == sessionProvider.getSessionId()
        }) }
    }

    @Test fun `uninstall is a no-op and does not throw`() {
        val upstream = mockk<AndroidInstrumentation>(relaxed = true)
        every { upstream.name } returns "upstream.session"
        val adapter = UpstreamInstrumentationAdapter(upstream)

        adapter.uninstall()
    }
}
```

- [ ] **Step 2: Run the test**

```bash
cd examples/demo-app
./gradlew :otel-android-mobile-core:test --tests "*.UpstreamInstrumentationAdapterTest" -q
```

Expected: 4 tests pass

---

### Task 10: Write InterfaceConvergenceTest

**Files:**
- Create: `otel-android-mobile-core/src/test/java/io/opentelemetry/android/mobile/instrumentation/InterfaceConvergenceTest.kt`

- [ ] **Step 1: Create the test file**

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.android.instrumentation.AndroidInstrumentation
import io.opentelemetry.android.instrumentation.InstallationContext
import io.opentelemetry.android.session.SessionProvider
import io.opentelemetry.sdk.common.Clock
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class InterfaceConvergenceTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    /** Minimal concrete implementation for testing the interface bridge. */
    class TestModule : MobileInstrumentation {
        override val instrumentationName = "test.module"
        var installCalled = false
        var uninstallCalled = false
        var lastApp: Application? = null
        var lastCtx: InstrumentationContext? = null

        override fun install(application: Application, context: InstrumentationContext) {
            installCalled = true
            lastApp = application
            lastCtx = context
        }

        override fun uninstall() {
            uninstallCalled = true
        }
    }

    @Test fun `MobileInstrumentation is AndroidInstrumentation`() {
        val module = TestModule()
        assertIs<AndroidInstrumentation>(module)
    }

    @Test fun `name delegates to instrumentationName`() {
        val module = TestModule()
        assertEquals("test.module", module.name)
        assertEquals(module.instrumentationName, module.name)
    }

    @Test fun `install(InstallationContext) bridges to install(Application, InstrumentationContext)`() {
        val module = TestModule()
        val app = RuntimeEnvironment.getApplication()
        val sessionProvider = mockk<SessionProvider>(relaxed = true)
        val clock = Clock.getDefault()
        val installCtx = InstallationContext(app, otelRule.openTelemetry, sessionProvider, clock)

        // Call the upstream install method
        module.install(installCtx)

        assertTrue(module.installCalled, "install(Application, InstrumentationContext) should be called")
        assertEquals(app, module.lastApp)
        assertEquals(otelRule.openTelemetry, module.lastCtx?.openTelemetry)
        assertEquals(clock, module.lastCtx?.clock)
    }

    @Test fun `uninstall(InstallationContext) bridges to uninstall()`() {
        val module = TestModule()
        val app = RuntimeEnvironment.getApplication()
        val installCtx = InstallationContext(app, otelRule.openTelemetry, mockk(relaxed = true), Clock.getDefault())

        module.uninstall(installCtx)

        assertTrue(module.uninstallCalled, "uninstall() should be called")
    }

    @Test fun `instrumentationVersion has default value`() {
        val module = TestModule()
        assertEquals("1.0.0", module.instrumentationVersion)
    }
}
```

- [ ] **Step 2: Run the test**

```bash
cd examples/demo-app
./gradlew :otel-android-mobile-core:test --tests "*.InterfaceConvergenceTest" -q
```

Expected: 5 tests pass

---

### Task 11: Full Build Verification

**Files:** None (verification only)

- [ ] **Step 1: Run all core tests**

```bash
cd examples/demo-app
./gradlew :otel-android-mobile-core:testDebugUnitTest -q
```

Expected: all tests pass (SupersedesConflictTest, UpstreamInstrumentationAdapterTest, InterfaceConvergenceTest, SessionProviderBridgeTest, etc.)

- [ ] **Step 2: Run all SDK tests (14 modules)**

```bash
cd examples/demo-app
./gradlew :otel-android-mobile:testDebugUnitTest -q
```

Expected: all 194+ tests pass with zero changes to modules

- [ ] **Step 3: Run all instrumentation module tests**

```bash
cd examples/demo-app
./gradlew :otel-android-mobile-core:testDebugUnitTest \
  :instrumentation-tap:testDebugUnitTest \
  :instrumentation-freeze:testDebugUnitTest \
  :instrumentation-back-press:testDebugUnitTest \
  :instrumentation-vitals:testDebugUnitTest \
  :instrumentation-screenshot:testDebugUnitTest \
  :instrumentation-wireframe:testDebugUnitTest \
  -q
```

Expected: all pass

- [ ] **Step 4: Full build**

```bash
cd examples/demo-app
./gradlew assembleDebug -q
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
cd mobile-otel
git add -A
git commit -m "feat: Phase 4 — converge MobileInstrumentation with AndroidInstrumentation

Bump upstream opentelemetry-android from 0.10.0-alpha to 1.2.0-alpha.
MobileInstrumentation now extends AndroidInstrumentation with default
bridge methods. UpstreamInstrumentationAdapter simplified (uses
upstream.name, no SessionManager bridge). MobileInstrumentationAdapter
and SessionProviderAsSessionManager deleted.

Zero changes to 14 instrumentation modules — backward-compatible
via default method implementations.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>"
```
