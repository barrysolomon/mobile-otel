# Build System — How `installDebug` Works

## Overview

The Mobile OTel SDK is a multi-module Android project: a core framework, 10 independent instrumentation modules (tap, scroll, screen, text-input, back-press, freeze, lifecycle, errors, network, vitals), and a top-level SDK library that bundles them all. The demo app at `examples/demo-app/` references these modules — which live at the repo root — via path overrides in `settings.gradle.kts`, creating a single Gradle build with 13 projects.

Running `./gradlew installDebug` from the demo app directory triggers a full bottom-up build: compile the core framework, compile all instrumentation modules in parallel, compile the SDK library (including Room/KSP code generation for the SQLite disk buffer), generate the Dash0 collector config from `.env`, package everything into a debug APK, and push it to every connected emulator or device via ADB.

This document walks through the build at two levels — a high-level flow for orientation, and a phase-by-phase breakdown of what Gradle, AGP, KSP, and ADB are each doing under the hood.

## Quick Answer

```bash
cd examples/demo-app
./gradlew installDebug
```

This single command builds the entire SDK (core + 10 instrumentation modules), packages them with the demo app into a debug APK, and installs it on every connected emulator or device.

---

## High-Level Flow

```
./gradlew installDebug
        │
        ▼
┌─────────────────────┐
│  Resolve dependency  │  Gradle reads settings.gradle.kts, discovers 13 projects
│  graph               │  spread across the repo, computes build order
└────────┬────────────┘
         ▼
┌─────────────────────┐
│  Compile bottom-up   │  Core → Instrumentation modules (parallel) → SDK → Demo app
│                      │  KSP generates Room DAO/schema for disk buffer
└────────┬────────────┘
         ▼
┌─────────────────────┐
│  Generate config     │  Reads .env, injects Dash0 endpoint/token/dataset
│  (otel-config.json)  │  into otel-config.json from template
└────────┬────────────┘
         ▼
┌─────────────────────┐
│  Package debug APK   │  Merge classes, resources, assets; sign with
│                      │  auto-generated debug keystore
└────────┬────────────┘
         ▼
┌─────────────────────┐
│  adb install         │  Push APK to every device returned by `adb devices`
│                      │  (reinstalls if already present)
└─────────────────────┘
```

---

## The Dependency Graph

The demo app lives in `examples/demo-app/`, but the SDK and instrumentation modules live at the repo root. [settings.gradle.kts](../examples/demo-app/settings.gradle.kts) stitches them into a single Gradle build using `projectDir` overrides:

```
:android  (demo app — produces the APK)
│
└─ implementation(":otel-android-mobile")          ← the SDK library
     │
     ├─ api(":otel-android-mobile-core")           ← core interfaces & framework
     │     WindowEventHub, InstrumentationContext,
     │     MobileInstrumentation, InstrumentationRegistry
     │
     ├─ api(":instrumentation-tap")                ← ui.tap, ui.long_press, ui.swipe
     ├─ api(":instrumentation-scroll")             ← ui.scroll (RecyclerView)
     ├─ api(":instrumentation-screen")             ← ui.screen_view + page spans
     ├─ api(":instrumentation-text-input")         ← ui.text_input
     ├─ api(":instrumentation-back-press")         ← ui.back_press
     ├─ api(":instrumentation-freeze")             ← ui.freeze, app.anr
     ├─ api(":instrumentation-lifecycle")          ← app.start, foreground, background
     ├─ api(":instrumentation-errors")             ← uncaught exceptions
     ├─ api(":instrumentation-network")            ← HTTP spans (OkHttp interceptor)
     └─ api(":instrumentation-vitals")             ← memory, battery, jank metrics
```

### Where the Code Lives

| Gradle Module | Directory | Type |
|---------------|-----------|------|
| `:android` | `examples/demo-app/android/` | Application (APK) |
| `:otel-android-mobile` | `otel-android-mobile/` | Android Library |
| `:otel-android-mobile-core` | `otel-android-mobile-core/` | Android Library |
| `:instrumentation-tap` | `instrumentation/tap/` | Android Library |
| `:instrumentation-scroll` | `instrumentation/scroll/` | Android Library |
| `:instrumentation-screen` | `instrumentation/screen/` | Android Library |
| `:instrumentation-text-input` | `instrumentation/text-input/` | Android Library |
| `:instrumentation-back-press` | `instrumentation/back-press/` | Android Library |
| `:instrumentation-freeze` | `instrumentation/freeze/` | Android Library |
| `:instrumentation-lifecycle` | `instrumentation/lifecycle/` | Android Library |
| `:instrumentation-errors` | `instrumentation/errors/` | Android Library |
| `:instrumentation-network` | `instrumentation/network/` | Android Library |
| `:instrumentation-vitals` | `instrumentation/vitals/` | Android Library |

All instrumentation modules live under `instrumentation/<name>/` at the repo root, not inside the demo app. The demo app references them via relative paths in `settings.gradle.kts`:

```kotlin
include(":instrumentation-tap")
project(":instrumentation-tap").projectDir = file("../../instrumentation/tap")
```

---

## Low-Level: What Each Phase Does

### Phase 1 — Dependency Resolution

Gradle reads `settings.gradle.kts` and discovers all 13 projects. It builds a directed acyclic graph (DAG) of dependencies:

```
:otel-android-mobile-core       ← no project dependencies (leaf node)
     ▲
     │
:instrumentation-tap             ← depends on core
:instrumentation-scroll          ← depends on core
:instrumentation-screen          ← depends on core
  ... (all 10 modules)           ← depends on core, can build in parallel
     ▲
     │
:otel-android-mobile             ← depends on core + all 10 modules
     ▲
     │
:android                         ← depends on SDK library
```

Gradle determines that core must build first, then all instrumentation modules can build in parallel, then the SDK library, then the demo app.

### Phase 2 — Compilation

**Core module** (`otel-android-mobile-core/`) compiles first. This produces the framework interfaces:
- `MobileInstrumentation` — base interface for all modules
- `WindowEventHub` — fan-out dispatcher for touch/key events
- `WindowEventListener` — listener interface
- `WindowEventHubInstaller` — wraps Activity Window.Callback
- `InstrumentationContext` — shared context (OTel SDK, session, hub)
- `InstrumentationRegistry` — manages module lifecycle
- `OTelMobileBuilder` — fluent builder with SPI discovery

**Instrumentation modules** compile next (in parallel). Each is a standalone Android library that implements `MobileInstrumentation`. Each also registers itself via Java SPI in `META-INF/services/`.

**SDK library** (`otel-android-mobile/`) compiles after all modules. This is where:
- `MobileOtel` facade lives (initialize, identify, sendEvent, forceFlush)
- `MobileLogRecordProcessor` implements the dual-tier ring buffer
- `PolicyEvaluator` implements the DSL engine
- `DiskLogBuffer` uses Room/SQLite for crash-survivable storage
- **KSP runs here** — Room annotation processor generates DAO implementations and database migration code from `@Entity` and `@Dao` annotations

**Demo app** (`examples/demo-app/android/`) compiles last.

### Phase 3 — Config Generation

Before the demo app's assets are merged, a custom Gradle task `generateOtelConfig` runs:

```kotlin
// android/build.gradle.kts
tasks.register("generateOtelConfig") { ... }
afterEvaluate {
    tasks.named("mergeDebugAssets") { dependsOn("generateOtelConfig") }
}
```

This reads `examples/demo-app/.env` and substitutes placeholders in `otel-config.json.template`:

```
YOUR_COLLECTOR_ENDPOINT  →  value from DASH0_ENDPOINT
YOUR_AUTH_TOKEN           →  value from DASH0_AUTH_TOKEN
YOUR_DATASET_NAME         →  value from DASH0_DATASET
```

The generated `otel-config.json` is gitignored — credentials never enter version control.

### Phase 4 — APK Packaging

The Android Gradle Plugin merges everything into a debug APK:

1. **DEX compilation** — All compiled `.class` files (demo app + SDK + all modules + dependencies) are compiled to Dalvik bytecode (`.dex` files)
2. **Resource merging** — Android resources from all modules are merged (layouts, drawables, strings)
3. **Asset merging** — The generated `otel-config.json` is included in the APK's `assets/` directory
4. **Manifest merging** — Each module's `AndroidManifest.xml` is merged into the final manifest
5. **Signing** — The APK is signed with the auto-generated `~/.android/debug.keystore` (no setup needed)
6. **Core library desugaring** — Java 8+ APIs (used by OTel SDK) are backported for Android API 26+ via `com.android.tools:desugar_jdk_libs`

Output: `examples/demo-app/android/build/outputs/apk/debug/android-debug.apk`

### Phase 5 — Install via ADB

Gradle calls Android Debug Bridge to install on all connected devices:

```bash
adb install -r android-debug.apk    # -r = reinstall, keeping data
```

If multiple devices are connected, Gradle installs on all of them. You can verify with:

```bash
adb devices
# emulator-5554   device
# emulator-5556   device
```

---

## Key Design Decisions

### `api` vs `implementation` Dependencies

The SDK declares all instrumentation modules as `api` (not `implementation`):

```kotlin
// otel-android-mobile/build.gradle.kts
api(project(":instrumentation-tap"))
api(project(":instrumentation-scroll"))
// ... all 10 modules
```

**Why `api`**: Consumers of the SDK (like the demo app) can directly reference instrumentation classes — for example, `TapConfig` or `FreezeConfig` — without adding each module as a separate dependency. One dependency on `:otel-android-mobile` pulls in everything.

### JDK Versions

| Component | JDK Target | Why |
|-----------|-----------|-----|
| SDK library (`otel-android-mobile/`) | JDK 17 | Room 2.8+, KSP, and modern OTel SDK require JDK 17 |
| Demo app (`android/`) | JDK 1.8 | Broader device compatibility; core library desugaring bridges the gap |
| Instrumentation modules | JDK 17 | Must match SDK library |

Both enable `isCoreLibraryDesugaringEnabled = true` so OTel's use of `java.time`, `CompletableFuture`, and other JDK 8+ APIs works on Android API 26 (Android 8.0).

### Modular Architecture

Each instrumentation module is a separate Gradle project and Android library. This means:

- **Independent compilation** — Modules build in parallel
- **Independent testing** — Each module has its own test suite (`./gradlew :instrumentation-tap:test`)
- **SPI discovery** — Modules register via `META-INF/services/`, so new modules are picked up automatically without changing the SDK's code
- **Consumer choice** — A future published SDK could let consumers include only the modules they need (though currently all are bundled)

---

## Common Build Commands

```bash
cd examples/demo-app

# Full build + install on all devices
./gradlew installDebug

# Build APK only (no install)
./gradlew assembleDebug

# Build + test everything
./gradlew build

# Test a single module
./gradlew :instrumentation-tap:testDebugUnitTest

# Test the SDK library
./gradlew :otel-android-mobile:testDebugUnitTest

# Test all modules
./gradlew :otel-android-mobile:testDebugUnitTest \
  :otel-android-mobile-core:testDebugUnitTest \
  :instrumentation-tap:testDebugUnitTest \
  :instrumentation-freeze:testDebugUnitTest \
  :instrumentation-back-press:testDebugUnitTest \
  :instrumentation-vitals:testDebugUnitTest

# Run instrumented tests on emulators
./gradlew :otel-android-mobile:connectedDebugAndroidTest

# Clean build
./gradlew clean assembleDebug
```
