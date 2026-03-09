# Wire OTelMobileBuilder Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace `AutoCaptureManager` + `AutoCaptureOptions` with the already-built `OTelMobileBuilder` / `InstrumentationRegistry` architecture, making `OTelMobile.start()` route through the new per-module instrumentations.

**Architecture:** The `otel-android-mobile-core` module and all 10 `instrumentation/*` modules are already fully coded and tested. The aggregator (`otel-android-mobile`) still uses the old `AutoCaptureManager`. This plan wires the new layer in and removes the old one.

**Tech Stack:** Kotlin, Android Gradle Plugin 9.0, `otel-android-mobile-core` (core interfaces), `instrumentation/*` (per-capability modules), Room/SQLite, OTel SDK 1.58.0

**Do NOT commit** unless explicitly instructed.

---

## Current State

- ✅ `otel-android-mobile-core/` — Complete: `MobileInstrumentation`, `OTelMobileBuilder`, `OTelMobileHandle`, `InstrumentationRegistry`, `InstrumentationContext`, `WindowEventHub`, `MobileSessionProvider`, `DefaultMobileSessionProvider`, `MobileSemconv`, `@Incubating`. All tests pass.
- ✅ `instrumentation/lifecycle/` — Complete: `LifecycleInstrumentation`
- ✅ `instrumentation/screen/` — Complete: `ScreenViewInstrumentation`
- ✅ `instrumentation/tap/` — Complete: `TapInstrumentation`, `TapConfig` — **BLOCKED**: `TapConfig` imports `PrivacyMode` from `io.opentelemetry.android.mobile.autocapture`, forcing a circular dep
- ✅ `instrumentation/scroll/` — Complete: `ScrollInstrumentation`
- ✅ `instrumentation/text-input/` — Complete: `TextInputInstrumentation`
- ✅ `instrumentation/back-press/` — Complete: `BackPressInstrumentation`
- ✅ `instrumentation/freeze/` — Complete: `FreezeInstrumentation`, `FreezeConfig`
- ✅ `instrumentation/errors/` — Complete: `ErrorsInstrumentation` (wraps existing `ErrorInstrumentation`)
- ✅ `instrumentation/network/` — Complete: `NetworkInstrumentation` (wraps existing `OTelNetworkInterceptor`)
- ✅ `instrumentation/vitals/` — Complete: `VitalsInstrumentation` (wraps existing `VitalsCollector`)
- ❌ `otel-android-mobile/build.gradle.kts` — Missing deps on `instrumentation-tap/errors/network/vitals` (blocked by circular dep)
- ❌ `OTelMobile.start()` — Still creates `AutoCaptureManager` directly
- ❌ `DemoApp.kt` — Still uses `AutoCaptureOptions` constructor
- ❌ `AutoCaptureManager.kt` / `AutoCaptureOptions.kt` — Still exist; need to be deleted

---

## Task 1: Move PrivacyMode to otel-android-mobile-core

**Problem:** `instrumentation/tap/src/.../TapConfig.kt` imports `PrivacyMode` from
`io.opentelemetry.android.mobile.autocapture`. This forces `instrumentation-tap` to depend on
`otel-android-mobile` (the aggregator). But the aggregator must depend on `instrumentation-tap`
for it to be included. Circular.

**Files:**
- Create: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/PrivacyMode.kt`
- Modify: `instrumentation/tap/src/main/java/io/opentelemetry/android/mobile/instrumentation/TapConfig.kt`
- Modify: `instrumentation/tap/build.gradle.kts` (remove `api(project(":otel-android-mobile"))`)
- Keep: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/autocapture/PrivacyMode.kt` (leave it for now; it's used by tests in that package)

**Step 1: Create PrivacyMode in core**

File: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/PrivacyMode.kt`

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

/**
 * Privacy modes for auto-capture payloads.
 */
enum class PrivacyMode {
    /** Hash UI text/labels and bucket coordinates. Safe default. */
    STRICT,
    /** Allow raw UI text/labels. Not recommended for production. */
    RELAXED
}
```

**Step 2: Update TapConfig import**

In `instrumentation/tap/src/main/java/io/opentelemetry/android/mobile/instrumentation/TapConfig.kt`,
replace:
```kotlin
import io.opentelemetry.android.mobile.autocapture.PrivacyMode
```
with:
```kotlin
// PrivacyMode is now in otel-android-mobile-core (same package — no import needed)
```
(No import needed — `PrivacyMode` and `TapConfig` are in the same package `io.opentelemetry.android.mobile.instrumentation`.)

**Step 3: Remove otel-android-mobile dep from tap module**

In `instrumentation/tap/build.gradle.kts`, remove:
```kotlin
api(project(":otel-android-mobile"))
```
Keep only:
```kotlin
api(project(":otel-android-mobile-core"))
```

**Step 4: Verify tap module compiles**

```bash
cd examples/demo-app
./gradlew :instrumentation-tap:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

---

## Task 2: Add tap/errors/network/vitals to the aggregator

Now that the circular dep is broken, add all four modules to `otel-android-mobile/build.gradle.kts`.

**Files:**
- Modify: `otel-android-mobile/build.gradle.kts`

**Step 1: Add deps**

In `otel-android-mobile/build.gradle.kts`, in the `dependencies` block, add after the existing instrumentation deps:

```kotlin
api(project(":instrumentation-tap"))
api(project(":instrumentation-errors"))
api(project(":instrumentation-network"))
api(project(":instrumentation-vitals"))
```

**Step 2: Verify aggregator compiles**

```bash
cd examples/demo-app
./gradlew :otel-android-mobile:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

---

## Task 3: Rewrite OTelMobile to use the builder

**Files:**
- Modify: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/OTelMobile.kt`

**Step 1: Replace AutoCaptureManager with OTelMobileHandle**

Replace the entire `OTelMobile.kt` content. The key changes:
- Remove `import AutoCaptureManager`, `import AutoCaptureOptions`
- Replace `@Volatile private var autoCaptureManager: AutoCaptureManager?` with `@Volatile private var handle: OTelMobileHandle?`
- Rewrite `start()` to use `OTelMobileBuilder` (no `options: AutoCaptureOptions` param)
- Rewrite `stop()` to call `handle?.stop()`
- Keep `markCrashForNextStart()`, `markLowMemoryForNextStart()`, `markAnrForNextStart()`, `getLastRecoveryType()` by delegating to a standalone `RecoveryTracker` instance
- Keep `restartPageSpan()` as a call into a stored `ScreenViewInstrumentation` reference (or remove since ScreenViewInstrumentation manages this internally)
- Keep `startJourney()` as a simple tracer call

New `start()` signature: `fun start(application: Application, config: MobileConfig)` — no `AutoCaptureOptions`.

```kotlin
object OTelMobile {
    @Volatile private var provider: MobileLoggerProvider? = null
    @Volatile private var handle: OTelMobileHandle? = null
    @Volatile private var recoveryTracker: RecoveryTracker? = null

    fun start(application: Application, config: MobileConfig) {
        synchronized(this) {
            if (provider == null) {
                val instance = MobileOtel.initialize(application, config)
                provider = instance

                val rt = RecoveryTracker(application, instance.get("recovery"), instance, SessionTracker(AutoCaptureOptions()))
                recoveryTracker = rt
                rt.start()

                handle = OTelMobileBuilder(application, instance.getOpenTelemetrySdk())
                    .addInstrumentation(LifecycleInstrumentation())
                    .addInstrumentation(ScreenViewInstrumentation())
                    .addInstrumentation(TapInstrumentation())
                    .addInstrumentation(ScrollInstrumentation())
                    .addInstrumentation(TextInputInstrumentation())
                    .addInstrumentation(BackPressInstrumentation())
                    .addInstrumentation(FreezeInstrumentation())
                    .addInstrumentation(ErrorsInstrumentation())
                    .addInstrumentation(VitalsInstrumentation())
                    .build()
            }
        }
    }
    ...
}
```

**Handling RecoveryTracker:** `RecoveryTracker` currently needs `SessionTracker` and `AutoCaptureOptions`. Since these are being deleted, and `RecoveryTracker`'s job (detect prior crash/ANR/low-memory on startup) is simple, **inline the recovery logic into a lightweight `RecoveryHelper`** or just keep `RecoveryTracker` as-is but construct it without `AutoCaptureOptions` — pass a minimal shim.

**Simpler approach for recovery:** Since never released, remove the `SessionTracker` and `AutoCaptureOptions` dependencies from `RecoveryTracker` by constructor-injecting only what it actually needs:
- `application: Application`
- `logger: Logger`
- `provider: MobileLoggerProvider`

Read `RecoveryTracker.kt` to confirm its constructor signature before implementing.

**Step 2: Verify compilation**

```bash
cd examples/demo-app
./gradlew :otel-android-mobile:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

---

## Task 4: Delete AutoCaptureManager and AutoCaptureOptions

These are the two files we were deprecating. With the new builder in place, they can be deleted.

**Step 1: Read all remaining usages before deleting**

```bash
grep -rn "AutoCaptureManager\|AutoCaptureOptions" \
  /Users/barrysolomon/Projects/Dash0/mobile-otel/otel-android-mobile/src \
  --include="*.kt"
```

Fix any remaining usages (there should be none after Task 3).

**Step 2: Delete the files**

```bash
rm otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/autocapture/AutoCaptureManager.kt
rm otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/autocapture/AutoCaptureOptions.kt
```

**Step 3: Verify compilation**

```bash
cd examples/demo-app
./gradlew :otel-android-mobile:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL (no references to deleted classes)

---

## Task 5: Update DemoApp.kt

**Files:**
- Modify: `examples/demo-app/android/src/main/java/io/opentelemetry/android/demo/DemoApp.kt`

**Current code:**
```kotlin
import io.opentelemetry.android.mobile.autocapture.AutoCaptureOptions
...
val captureOptions = AutoCaptureOptions(...)
OTelMobile.start(application = this, config = config, options = captureOptions)
```

**New code:** `AutoCaptureOptions` is gone. `start()` takes only `application` and `config`.

```kotlin
class DemoApp : Application() {
    companion object {
        var handle: io.opentelemetry.android.mobile.instrumentation.OTelMobileHandle? = null
    }

    override fun onCreate() {
        super.onCreate()
        getSharedPreferences("otel_config", android.content.Context.MODE_PRIVATE)
            .edit().putString("export_mode", "CONTINUOUS").apply()
        val config = ConfigManager.loadConfig(this)
        OTelMobile.start(application = this, config = config)
        Log.i("OTELDemoApp", "OTelMobile started")
    }
}
```

The per-feature toggles (captureTaps, captureLifecycle, etc.) that were in `ConfigManager.loadCaptureOptions()` are no longer needed at this level. Per-module config can be passed to individual instrumentation constructors when that feature is needed — for now all instrumentations use their defaults.

**Step 1: Update DemoApp.kt as above**

**Step 2: Check ConfigManager.loadCaptureOptions usage**

```bash
grep -rn "loadCaptureOptions\|captureKey" \
  examples/demo-app/android/src/main/java --include="*.kt"
```

If `loadCaptureOptions` is only called from `DemoApp.kt`, leave `ConfigManager` intact (it may still be used for other settings). Just remove the call site.

**Step 3: Verify demo app compiles**

```bash
cd examples/demo-app
./gradlew :android:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

---

## Task 6: Run all module tests

Run tests for each new module to verify they all pass.

**Step 1: Run core module tests**

```bash
cd examples/demo-app
./gradlew :otel-android-mobile-core:testDebugUnitTest --stacktrace
```
Expected: All tests pass (OTelMobileBuilderTest, InstrumentationRegistryTest, WindowEventHubTest, DefaultMobileSessionProviderTest, MobileSemconvTest, InstrumentationContextTest)

**Step 2: Run instrumentation module tests**

```bash
cd examples/demo-app
./gradlew \
  :instrumentation-lifecycle:testDebugUnitTest \
  :instrumentation-screen:testDebugUnitTest \
  :instrumentation-tap:testDebugUnitTest \
  :instrumentation-scroll:testDebugUnitTest \
  :instrumentation-text-input:testDebugUnitTest \
  :instrumentation-back-press:testDebugUnitTest \
  :instrumentation-freeze:testDebugUnitTest \
  :instrumentation-errors:testDebugUnitTest \
  :instrumentation-network:testDebugUnitTest \
  :instrumentation-vitals:testDebugUnitTest \
  --stacktrace
```
Expected: All tests pass

**Step 3: Run the full aggregator test suite**

```bash
cd examples/demo-app
./gradlew :otel-android-mobile:testDebugUnitTest --stacktrace
```
Expected: All existing tests pass (DiskLogBufferTest: 26 tests, MobileLogRecordProcessorTest, PolicyEvaluatorTest, etc.)

---

## Task 7: Clean up old autocapture classes (optional, separate pass)

The following files in `otel-android-mobile/autocapture/` are now dead code — their logic lives in the corresponding `instrumentation/*` modules:

| Old file | Replaced by |
|---|---|
| `SessionTracker.kt` | `DefaultMobileSessionProvider.kt` in core |
| `TapCapture.kt` | `TapInstrumentation.kt` |
| `ScrollCapture.kt` | `ScrollInstrumentation.kt` |
| `BackPressCapture.kt` | `BackPressInstrumentation.kt` |
| `TextInputCapture.kt` | `TextInputInstrumentation.kt` |
| `FreezeDetector.kt` | `FreezeInstrumentation.kt` |
| `WindowCallbackWrapper.kt` | Internal to `WindowEventHub` in core |
| `ViewHitTester.kt` | Not yet ported (reserved for TapConfig privacy features) |
| `CoordinateBucketer.kt` | Not yet ported (reserved for TapConfig privacy features) |
| `PrivacyUtils.kt` | Not yet ported (reserved for TapConfig privacy features) |

Before deleting each, run `grep -rn "ClassName" otel-android-mobile/src --include="*.kt"` to confirm no remaining usages (especially in tests).

Do **not** delete `RecoveryTracker.kt` — it's kept as a standalone helper for crash/ANR/low-memory tracking on next app start.
Do **not** delete `PrivacyMode.kt` from `autocapture/` — tests in that package reference it. It can be aliased or removed once `PrivacyUtils` tests are updated to use the new core location.

---

## Verification Checklist

After all tasks complete:

- [ ] `./gradlew :otel-android-mobile-core:testDebugUnitTest` — all pass
- [ ] `./gradlew :instrumentation-tap:testDebugUnitTest` — all pass
- [ ] `./gradlew :otel-android-mobile:testDebugUnitTest` — all pass (≥26 DiskLogBufferTest, all processor/policy tests)
- [ ] `./gradlew :android:assembleDebug` — demo app builds
- [ ] No imports of `AutoCaptureManager` or `AutoCaptureOptions` anywhere in the repo (verify with grep)
- [ ] `OTelMobile.start(app, config)` — no `options` param required
