# Android SDK Integration Guide

Complete guide for integrating the `otel-android-mobile` SDK into your Android app.

## Table of Contents

1. [Overview](#overview)
2. [Installation](#installation)
3. [Initialization](#initialization)
4. [Auto-Instrumentation](#auto-instrumentation)
5. [Custom Events & Error Reporting](#custom-events--error-reporting)
6. [Network Instrumentation](#network-instrumentation)
7. [Export Modes & Flush Control](#export-modes--flush-control)
8. [Privacy & Data Control](#privacy--data-control)
9. [ProGuard / R8](#proguard--r8)
10. [Troubleshooting](#troubleshooting)

## Overview

The SDK captures telemetry into a two-tier ring buffer (RAM + SQLite), evaluates export policies on-device, and selectively flushes relevant event windows to an OTEL Collector. This keeps bandwidth near-zero during normal operation and provides full context around problems.

```
Your App
   │
   ├─ OTelMobile.start() ──► ErrorInstrumentation (uncaught, coroutine, RxJava)
   │                     ├──► VitalsCollector (app start, jank, memory, thermal)
   │                     ├──► PredictiveExportPolicy (crash/network risk)
   │                     └──► AutoCaptureManager (taps, scrolls, freezes, ANR)
   │
   │  All events ──► MobileLogRecordProcessor ──► RAM buffer (5000 events)
   │                                          └──► DiskLogBuffer (SQLite, 50MB, 24h)
   │
   └─ Flush trigger ──► PolicyEvaluator ──► OTLP/gRPC ──► OTEL Collector
```

**Requirements:** Android API 26+ (Android 8.0), JDK 17, Kotlin 2.0+

## Installation

The SDK is not yet published to Maven Central. Include it as a local Gradle module:

```kotlin
// settings.gradle.kts
include(":otel-android-mobile")
project(":otel-android-mobile").projectDir = file("path/to/otel-android-mobile")

// app/build.gradle.kts
dependencies {
    implementation(project(":otel-android-mobile"))
}
```

Key transitive dependencies pulled in automatically:

| Dependency | Version | Purpose |
| ---------- | ------- | ------- |
| OpenTelemetry SDK | 1.58.0 | OTel API + SDK |
| Room | 2.8.4 | SQLite disk buffer |
| OkHttp | 4.12.0 | HTTP export |
| Kotlinx Coroutines | 1.10.2 | Async processing |
| Kotlin Serialization | 1.6.0 | Policy DSL parsing |

## Initialization

### Demo App Credential Setup

The demo app reads its collector endpoint and auth token from `examples/demo-app/android/src/debug/assets/otel-config.json`. This file is excluded from git to prevent credential leakage. To configure it locally:

```bash
cp examples/demo-app/android/src/debug/assets/otel-config.json.template \
   examples/demo-app/android/src/debug/assets/otel-config.json
# Then edit otel-config.json and replace the placeholder values:
#   YOUR_COLLECTOR_ENDPOINT → e.g. ingress.us-west-2.aws.dash0.com:4317
#   YOUR_AUTH_TOKEN         → your Dash0 (or other backend) auth token
#   YOUR_DATASET_NAME       → e.g. otel-mobile
```

### Minimal Setup

```kotlin
// MyApplication.kt
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OTelMobile.start(this, MobileConfig(
            serviceName       = "my-app",
            serviceVersion    = BuildConfig.VERSION_NAME,
            collectorEndpoint = "https://collector.example.com:4317"
        ))
    }
}
```

Register in `AndroidManifest.xml`:

```xml
<application android:name=".MyApplication" ...>
```

### Full MobileConfig Options

```kotlin
MobileConfig(
    serviceName            = "my-app",
    serviceVersion         = "1.0.0",
    collectorEndpoint      = "https://collector.example.com:4317",
    exportMode             = ExportMode.CONDITIONAL,   // CONDITIONAL | CONTINUOUS | HYBRID
    attachContextAttributes = true,                    // device/session attrs on every log
    policyEndpoint         = "https://gateway.example.com/config",  // remote policy fetch
    flushWindowMinutes     = 5                         // default window for selective flush
)
```

### Two Entry Points

| Entry Point | Use When |
| ----------- | -------- |
| `OTelMobile.start()` | Drop-in auto-instrumentation (taps, scrolls, freezes, ANR, errors, vitals) |
| `MobileOtel.initialize()` | Manual control; auto-capture not started, you wire modules yourself |

`OTelMobile.start()` calls `MobileOtel.initialize()` then starts `AutoCaptureManager`. Use `MobileOtel.initialize()` alone if you only want buffering + policy evaluation without UI auto-capture.

## Auto-Instrumentation

When you call `OTelMobile.start()`, the following modules are active with no further code:

### Error Instrumentation

Catches and exports:

- Uncaught exceptions via `Thread.setDefaultUncaughtExceptionHandler`
- Kotlin coroutine exceptions via `CoroutineExceptionHandler`
- RxJava errors via `RxJavaPlugins.setErrorHandler`

Each error emits an OTel log with body `app.error`, deduplication within a 5-minute window, and rate limiting at 10 errors/minute. Errors also trigger an immediate buffer flush.

### Vitals

- **App start time** — cold/warm/hot start via `AppStartInstrumentation`
- **Jank frames** — via `JankDetector` on Choreographer callbacks
- **Memory / thermal** — periodic `VitalsCollector` gauges

All exported as OTel Meter gauges (not logs).

### Predictive Export

`PredictiveExportPolicy` monitors `DeviceHealthMonitor` metrics. When:

- Crash risk >= 0.7 → pre-emptive flush (`prediction.crash_risk`)
- Network loss risk >= 0.7 → pre-emptive flush (`prediction.network_loss`)

Prediction events are emitted as OTel logs.

### Page-Level Span Model

`AutoCaptureManager` automatically creates a `page.<ScreenName>` parent span when any Fragment resumes and closes it when the fragment pauses. All auto-captured UI events and network calls on that screen become child spans, producing a full trace waterfall per user interaction.

```text
page.BookFragment              ← starts on onFragmentResumed
  ├── ui.tap (auto-captured)
  ├── form.provider_selected   ← Span.current().addEvent()
  ├── form.submitted
  ├── POST /posts              ← OTelNetworkInterceptor child span
  └── result.appointment_id   ← Span.current().setAttribute()
                               ← ends when fragment pauses or restartPageSpan() is called
page.BookFragment              ← fresh span starts for the next booking
```

After an API call completes (booking confirmed, route fetched), call `OTelMobile.restartPageSpan(screenName)` to end the current page span and open a fresh one — the next user action starts a clean trace.

```kotlin
// In any fragment, after completing an API call:
OTelMobile.restartPageSpan("BookFragment")
```

Within your fragment code, use `Span.current()` to annotate the active page span:

```kotlin
// Add events (shown as timeline points in the trace waterfall)
Span.current().addEvent("form.submitted", Attributes.of(
    AttributeKey.stringKey("provider"), provider,
    AttributeKey.stringKey("time_slot"), timeSlot
))

// Set attributes on the page span
Span.current().setAttribute(AttributeKey.stringKey("result.appointment_id"), appt.id)
Span.current().setStatus(StatusCode.OK)
```

### UI Auto-Capture

`AutoCaptureManager` registers a `WindowCallbackWrapper` on each Activity:

| Signal | Event name | Notes |
| ------ | ---------- | ----- |
| Tap | `ui.tap` | Bucketed to 3x3 grid by default; long-press detected by down→up duration |
| Long press | `ui.long_press` | |
| Scroll | `ui.scroll` | RecyclerView only; throttled to 500ms |
| Back press | `ui.back_press` | |
| UI freeze | `ui.freeze` | Threshold: 2000ms, cooldown: 30s |
| ANR | `app.anr` | Threshold: 5000ms |
| Lifecycle | `app.lifecycle.*` | start, stop, foreground, background |
| Screen | `ui.screen_view` | Per-fragment on resume |

> **Note:** Scroll capture attaches to `RecyclerView` instances found at activity resume. Views created dynamically inside fragments may not be picked up until the next activity resume.

Control via `AutoCaptureOptions` in `MobileConfig`:

```kotlin
MobileConfig(
    ...
    autoCaptureOptions = AutoCaptureOptions(
        captureTaps        = true,
        captureScroll      = false,  // disable scroll events
        freezeThresholdMs  = 3000,   // raise freeze threshold
        privacyMode        = PrivacyMode.STRICT  // default
    )
)
```

### Crash / ANR / OOM Recovery

`RecoveryTracker` writes SharedPreferences markers before each failure mode. On next launch it detects which markers are set and emits:

| Marker | Events emitted | `recovery_type` attribute |
| --- | --- | --- |
| crash | `app.crash` + `app.recovery` | `crash` |
| anr | `app.anr` + `app.recovery` | `anr_force_kill` |
| low_memory | `app.recovery` | `low_memory_kill` |
| session_active (no other marker) | `app.recovery` | `system_force_kill` |

## Custom Events & Error Reporting

```kotlin
// Custom event
MobileOtel.sendEvent("checkout.completed", mapOf(
    "item_count"  to 3,
    "total_cents" to 4299,
    "currency"    to "USD"
))

// Caught exception — triggers dedup + rate limiting + flush
try {
    riskyOperation()
} catch (e: Exception) {
    MobileOtel.reportError(e, mapOf("context" to "checkout"))
}

// Coroutine scope with OTel error handler
val scope = CoroutineScope(
    Dispatchers.IO + MobileOtel.getCoroutineExceptionHandler()!!
)

// User identity — attached to subsequent logs as resource attributes
MobileOtel.identify(UserIdentity(
    userId    = "user-123",
    userEmail = "alice@example.com"   // hashed in STRICT privacy mode
))
```

## Network Instrumentation

The `OTelNetworkInterceptor` is user-wired — add it to your OkHttpClient:

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(OTelNetworkInterceptor.create(
        context    = applicationContext,
        config     = NetworkConfig.production(),
        tracer     = OTelMobile.getTracer("network"),
        propagator = openTelemetry.propagators.textMapPropagator
    ))
    .build()
```

### NetworkConfig Presets

| Preset | What's captured |
| ------ | --------------- |
| `NetworkConfig.default()` | method, status, duration, host |
| `NetworkConfig.minimal()` | status, duration only |
| `NetworkConfig.debug()` | full headers + body (dev only) |
| `NetworkConfig.production()` | method, status, duration, sanitized URL |

Request/response bodies are never captured in non-debug presets. URLs are sanitized to remove query parameters containing credentials.

### OTel Context in Coroutines

`withContext(Dispatchers.IO)` may resume on a different thread, losing the thread-local OTel context. HTTP calls made inside `withContext` would appear as root spans instead of children of the active page span. Fix: capture the context before switching, then restore it inside:

```kotlin
suspend fun fetchData(): Result {
    val otelCtx = io.opentelemetry.context.Context.current()  // capture on caller thread

    return withContext(Dispatchers.IO) {
        val scope = otelCtx.makeCurrent()                      // restore on IO thread
        try {
            httpClient.get(url)                                // HTTP span parented correctly
        } finally {
            scope.close()
        }
    }
}
```

This pattern is used throughout `AppointmentRepository` and is required whenever `OTelNetworkInterceptor` spans need to nest under an active page span.

## Live Sampling Rate Adjustment

Call `MobileOtel.getProvider().setSamplingRate(rate)` to update the sampling rate immediately for all new spans — no restart required:

```kotlin
// Bump to 100% temporarily (e.g. while debugging)
MobileOtel.getProvider().setSamplingRate(1.0)

// Drop to 10% for production
MobileOtel.getProvider().setSamplingRate(0.1)
```

The change takes effect for the next span created; in-flight spans are not affected. The rate is also persisted via `ConfigManager.saveSamplingRate()` so it survives process death.

The demo app exposes this as a live slider in **Profile → OTel Config**. Moving the slider calls `setSamplingRate()` directly — the next booking or route search immediately reflects the new rate.

## Export Modes & Flush Control

### Export Modes

| Mode | Behavior | Battery |
| ---- | --------- | ------- |
| `CONDITIONAL` | Export only when a policy trigger fires | <0.5% |
| `CONTINUOUS` | Periodic export (30s traces, 60s metrics) | 3-5% |
| `HYBRID` | Both periodic and trigger-based | 1-2% |

### Flush Triggers (all modes)

1. **Policy match** — DSL condition met in `PolicyEvaluator`
2. **Error capture** — immediate on uncaught exception / coroutine / RxJava error
3. **Predictive** — crash or network loss risk >= 0.7
4. **Low memory** — `ComponentCallbacks2.onTrimMemory` at critical level
5. **App recovery** — crash/ANR marker detected on launch
6. **Manual** — explicit API call

### Manual Flush

```kotlin
MobileOtel.forceFlush()                   // flush everything
MobileOtel.forceFlush(windowMinutes = 5)  // last 5 minutes only (selective)
```

Selective flush (`windowMinutes`) is the key feature — export only the event window around a problem rather than all buffered data.

## Privacy & Data Control

### Privacy Modes

| Mode | Behavior |
| ---- | -------- |
| `PrivacyMode.STRICT` | No PII; view labels hashed; no coordinates |
| `PrivacyMode.BALANCED` | Hashed user IDs; sanitized URLs |
| `PrivacyMode.PERMISSIVE` | Full data (use only with user consent) |

### Allowlists / Denylists

Fine-grained control over which views are instrumented:

```kotlin
AutoCaptureOptions(
    privacyMode              = PrivacyMode.STRICT,
    allowlistedResourceIds   = setOf("btn_checkout", "btn_submit"),
    denylistedResourceIds    = setOf("et_password", "et_credit_card"),
    denylistedViewClasses    = setOf("PasswordEditText")
)
```

## ProGuard / R8

Add to `proguard-rules.pro`:

```proguard
# OpenTelemetry
-keep class io.opentelemetry.** { *; }
-dontwarn io.opentelemetry.**

# otel-android-mobile
-keep class io.opentelemetry.android.mobile.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep class kotlinx.serialization.** { *; }
```

## Troubleshooting

### No telemetry in collector

```bash
adb logcat | grep "OTelMobile\|MobileOtel\|PolicyEvaluator"
```

Check:

1. `collectorEndpoint` is reachable from the device (use `10.0.2.2:4317` for emulator loopback)
2. `ExportMode` — in `CONDITIONAL` mode, nothing exports until a policy trigger fires
3. Call `MobileOtel.forceFlush()` manually to verify the pipeline works

### OOM / crash markers not detected on restart

`RecoveryTracker` reads markers in `Application.onCreate()` before any Activity. If your `Application` subclass crashes before `OTelMobile.start()` is called, markers won't be read. Ensure `OTelMobile.start()` is the first statement after `super.onCreate()`.

### Policy evaluation not triggering

The SDK fetches policy config from `policyEndpoint` on startup. If no config is fetched (no network, or endpoint not set), `PolicyEvaluator.evaluate()` returns `null` and no policy-based flush occurs. You'll still get error/predictive/recovery flushes.

### Unit testing

Use `TestDemoApplication` pattern to skip `OTelMobile.start()` in Robolectric tests:

```kotlin
class TestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Do NOT call OTelMobile.start() — avoids gRPC init in tests
    }
}

@Config(sdk = [28], application = TestApplication::class)
class MyTest { ... }
```

For testing that telemetry is emitted, inject a `SdkLoggerProvider` with `MockLogRecordExporter` and `SimpleLogRecordProcessor`. See `RecoveryTrackerTest` for the pattern.

## Related Documentation

- [Quick Start](QUICK_START.md) — 5-minute integration walkthrough
- [Export Modes](EXPORT_MODES.md) — detailed mode comparison
- [Device Metrics](DEVICE_METRICS.md) — health metric gauges reference
- [Geo/Device Policy Extension](GEO_DEVICE_POLICY_EXTENSION.md) — country/region/device-aware policies
- [Sampling](SAMPLING.md) — dynamic sampling configuration
- [API Reference](API_REFERENCE.md) — gateway API
