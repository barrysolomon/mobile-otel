# Android OpenTelemetry Quickstart: From Zero to Dash0

This tutorial walks you through adding OpenTelemetry auto-instrumentation to an existing Android app. You start with the **Schedulr** demo app — a medical scheduling app with zero OTel code — and end with live traces, logs, and metrics flowing into Dash0 from a few lines of setup code.

**Time to complete:** ~20 minutes
**What you'll need:** Android Studio, a connected emulator or device (API 26+), a Dash0 account with an auth token

---

## Setting Up an Emulator

Skip this section if you already have a running emulator (`adb devices` shows a device with status `device`).

### Android Studio AVD Manager (recommended for first-timers)

1. Open **Tools → Device Manager**
2. Click **+** → **Create Virtual Device**
3. Choose **Pixel 7** → **API 36 (Google APIs)**
4. Click **Finish**, then press the **▶ Play** button
5. Wait for the home screen to appear before proceeding

### Command line (headless / CI)

```bash
# See available AVDs
emulator -list-avds
# Typical output for this project:
#   Medium_Phone_API_36.1
#   Pixel_3a
#   Pixel_7
```

**With a visible window (normal development):**

```bash
emulator -avd Pixel_7 &
```

The emulator window opens. Wait for the Android home screen.

**Headless (CI or low-RAM machines):**

```bash
nohup emulator -avd Pixel_3a -no-window -no-audio -no-snapshot-save \
    > /tmp/emulator.log 2>&1 &
```

Either way, poll until fully booted before installing:

```bash
until adb shell "getprop dev.bootcomplete" 2>/dev/null | grep -q 1; do sleep 5; done
until adb shell "getprop sys.boot_completed" 2>/dev/null | grep -q 1; do sleep 5; done
echo "Emulator ready"
```

> **Pixel_7 (API 36) note:** Takes ~4 minutes to fully boot after `adb` first connects. The two-prop poll above handles this. Don't install APKs until both return `1`.

### Create a new AVD from scratch

```bash
# Install a system image
sdkmanager "system-images;android-36;google_apis;x86_64"

# Create the AVD
avdmanager create avd \
    --name Pixel_7 \
    --package "system-images;android-36;google_apis;x86_64" \
    --device "pixel_7"
```

### Verify readiness

```bash
adb devices
# "device" = ready to use; "offline" = still booting
#   emulator-5554   device
```

---

## What You'll Get (Before Writing a Single Line of Manual Tracing)

By the end of Step 3, Dash0 will automatically receive:

| Signal | What you'll see |
| ------ | --------------- |
| **Page spans** | Every screen the user visits, with duration |
| **UI interactions** | Every tap, scroll, swipe — as child spans of the page span |
| **App lifecycle** | App start time (cold/warm), foreground/background |
| **Errors** | Uncaught exceptions with stack traces and 5 minutes of prior context |
| **HTTP errors** | Failed API calls with URL, status code, and 5 minutes of prior context |
| **UI freezes** | Main-thread blocks ≥ 2s with 2 minutes of prior context |
| **Performance vitals** | Jank frames, app start latency as OTel metrics |
| **Predictive flush** | Pre-emptive export when crash or network loss risk is high |
| **Ring buffer metrics** | `buffer.ram.events`, `buffer.disk.events` as gauges |

All of this comes from `OTelMobile.start()`. No manual instrumentation required.

---

## Step 1: Open the Starter App

Clone the repo and open the starter module in Android Studio:

```bash
git clone https://github.com/barrysolomon/mobile-otel.git
cd mobile-otel
```

Open **`examples/demo-app-starter/`** in Android Studio (File → Open → navigate to that directory).

The starter is a complete Schedulr app — appointments, booking, calendar, feed, directions — with no OTel code anywhere. Build and run it to verify it works:

```bash
cd examples/demo-app-starter
./gradlew :android:assembleDebug
```

You should see the app launch on your emulator. Booking works, navigation works, there's no telemetry.

---

## Step 2: Add the SDK Dependency

Open `android/build.gradle.kts` and add the SDK dependency:

```kotlin
dependencies {
    // ... existing deps ...

    // OpenTelemetry Mobile SDK
    implementation("io.opentelemetry.android:mobile:0.3.0-alpha")
}
```

Also add desugaring support (required for Java 8+ API usage on older Android versions):

```kotlin
android {
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // ... other deps ...
    implementation("io.opentelemetry.android:mobile:0.3.0-alpha")
}
```

Sync the project (File → Sync Project with Gradle Files).

---

## Step 3: Initialize OTel in Your Application Class

Open `DemoApp.kt`. Right now it's a minimal Application subclass:

```kotlin
class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Nothing here yet
    }
}
```

Replace it with:

```kotlin
package io.opentelemetry.android.demo

import android.app.Application
import io.opentelemetry.android.mobile.OTelMobile
import io.opentelemetry.android.mobile.config.MobileConfig

class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()

        OTelMobile.start(
            this,
            MobileConfig(
                serviceName = "schedulr-android",
                serviceVersion = "1.0.0",
                collectorEndpoint = "https://your-collector-endpoint:4317",
                headers = mapOf(
                    "Authorization" to "Bearer YOUR_DASH0_TOKEN",
                    "Dash0-Dataset" to "default"
                )
            )
        )
    }
}
```

Replace `YOUR_DASH0_TOKEN` with your Dash0 auth token. You can find it in Dash0 under Settings → Auth Tokens.

That's it. **Build and run the app.** Navigate around, tap a few things, try booking an appointment.

Within 30 seconds you'll see data flowing into Dash0:

- Traces with page spans for each screen
- Child spans for every tap and scroll
- App start latency metric
- Ring buffer gauge metrics

---

## Step 4: See the Trace Waterfall

Open Dash0 and go to Traces. Filter by `service.name = "schedulr-android"`.

Click into any trace. You'll see a waterfall like:

```text
page.BookFragment                     [400ms]
├── ui.tap                            [0ms]  ← "Next appointment" button
├── ui.tap                            [0ms]  ← date picker
├── ui.swipe                          [0ms]
├── ui.tap                            [0ms]  ← "Book" button
└── ui.text_input                     [0ms]  ← notes field
```

Every page span is always sampled. All interactions on that page become child spans in the same trace, giving you a complete user journey waterfall without any manual tracing code.

**Why `page.*` spans are always sampled:** `DynamicSampler` force-samples any span whose name starts with `page.`. This ensures that interaction child spans (created by `TapCapture` and `ScrollCapture`) always have a valid sampled parent — otherwise they'd fall back to disconnected log records.

---

## Step 5: Trigger an Auto-Flush

Navigate to the booking screen and submit a booking that fails (the demo API returns 503 for certain test dates). Watch Dash0 within a few seconds:

A new trace will appear tagged with `http.error`, and it will include the **last 5 minutes** of user activity before the error — all the taps, navigations, and network calls that led up to the failure. This is tail sampling in action: the ring buffer held everything, and the `http-error-detector` policy exported the relevant window when the error arrived.

You can also force a crash to see `crash-recovery` in action:

1. In Android Studio, use the debug toolbar in the app (swipe right from the left edge on the scheduling screen) and tap "Crash"
2. The app will crash and restart
3. On restart, Dash0 will receive the last 5 minutes of activity before the crash

---

## Step 6 (Optional): Add Network Tracing

To get HTTP spans with full request/response attributes, add `OTelNetworkInterceptor` to your OkHttp client.

Open `data/api/ApiService.kt` (or wherever you create your `OkHttpClient`) and add:

```kotlin
import io.opentelemetry.android.mobile.network.OTelNetworkInterceptor
import io.opentelemetry.android.mobile.network.NetworkConfig
import io.opentelemetry.android.mobile.OTelMobile

// In your OkHttpClient builder:
val client = OkHttpClient.Builder()
    .addInterceptor(
        OTelNetworkInterceptor.create(
            context = applicationContext,
            config = NetworkConfig.production(),    // scrubs query params + sensitive headers
            tracer = OTelMobile.getTracer("network")!!,
            propagator = OTelMobile.getPropagators()!!.textMapPropagator
        )
    )
    .build()
```

`NetworkConfig.production()` applies safe defaults:

- URL query parameters stripped
- `Authorization` and `Cookie` headers scrubbed
- Request/response bodies not captured

Now HTTP calls appear as child spans inside the page span waterfall:

```text
page.BookFragment                     [1200ms]
├── ui.tap                            [0ms]
└── ui.tap                            [0ms]  ← "Book" button
    └── POST /api/appointments        [850ms]  ← OkHttp child span
        ├── http.method: POST
        ├── http.url: https://api.schedulr.example.com/appointments
        ├── http.status_code: 201
        └── http.duration_ms: 847
```

---

## Step 7 (Optional): Add a Manual Span

Auto-instrumentation captures interactions, but you can add richer business context with a manual span. Here's an example in `BookFragment` around the booking submission:

```kotlin
import io.opentelemetry.android.mobile.OTelMobile
import io.opentelemetry.api.trace.StatusCode

// In your booking submit handler:
private fun submitBooking(date: String, notes: String) {
    val tracer = OTelMobile.getTracer("booking") ?: return submitBookingPlain(date, notes)
    val span = tracer.spanBuilder("booking.submit")
        .setAttribute("booking.date", date)
        .setAttribute("booking.notes_provided", notes.isNotBlank())
        .startSpan()

    val scope = span.makeCurrent()
    try {
        val result = appointmentRepository.createAppointment(date, notes)
        span.setAttribute("booking.appointment_id", result.id)
        span.setStatus(StatusCode.OK)
        showSuccess(result)
    } catch (e: Exception) {
        span.setStatus(StatusCode.ERROR, e.message ?: "Unknown error")
        span.recordException(e)
        showError(e)
    } finally {
        scope.close()
        span.end()
    }
}
```

This span becomes a child of the active `page.BookFragment` span (because `makeCurrent()` installs it into the OTel context). Any OkHttp call made inside the try block will also become a child of `booking.submit`, giving you a three-level waterfall:

```text
page.BookFragment
└── booking.submit           ← your manual span
    └── POST /api/appointments  ← OkHttp auto-span
```

---

## What's Happening Under the Hood

### Two-Tier Ring Buffer

Every event is written to an in-memory `ConcurrentLinkedQueue` (5,000 events, RAM tier). When the RAM tier fills, the oldest events overflow to a SQLite database (50 MB, 24h TTL, disk tier). The disk tier survives crashes and process kills.

```text
Event → RAM buffer (5000 events) → overflow → Disk buffer (50MB SQLite)
                                                     ↕
                                            survives crashes
```

### Tail Sampling via Export Policies

Events are not exported in real time. Instead, three built-in policies watch the event stream:

| Policy | Trigger | Exports |
| ------ | ------- | ------- |
| `crash-recovery` | `app.crash` event | Last 5 minutes |
| `http-error-detector` | `http.error` event | Last 5 minutes |
| `ui-freeze-detector` | `ui.freeze` event | Last 2 minutes |

When a trigger fires, `flushWindow(N)` scans both buffer tiers for events in the last N minutes and exports them via OTLP/gRPC. Events before the window stay in the buffer.

This is why you see full context around errors in Dash0, not just the error itself — the buffer held everything that happened before.

### Head Sampling for Spans

`DynamicSampler` makes sampling decisions for traces (spans):

- `page.*` spans: always sampled (100%)
- `app.startup`: always sampled (100%)
- Everything else: 10% baseline (configurable)

The 100% force-sample on `page.*` ensures the trace waterfall is always intact.

---

## Troubleshooting

**No data in Dash0 after 60 seconds:**

- Verify your auth token is correct (Settings → Auth Tokens in Dash0)
- Check the endpoint URL: `https://your-collector-endpoint:4317` (adjust region if needed)
- Check logcat for `MobileLogRecordProcessor` or `RetryableExporter` errors

**Data appears but traces are flat (no waterfall):**

- Make sure you're calling `OTelMobile.start()` in `Application.onCreate()`, not in an Activity
- The `page.*` spans require the SDK to be initialized before any Fragment resumes

**Missing HTTP spans:**

- `OTelNetworkInterceptor` must be added to every `OkHttpClient` instance in your app
- The interceptor must be added with `addInterceptor()`, not `addNetworkInterceptor()`

**App crashes on startup:**

- Verify `isCoreLibraryDesugaringEnabled = true` is set in `compileOptions`
- Verify `coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")` is in `dependencies`

---

## Next Steps

- **[docs/AUTO_INSTRUMENTATION.md](../AUTO_INSTRUMENTATION.md)** — Complete reference for all auto-captured signals
- **[docs/BUFFERING_AND_TAIL_SAMPLING.md](../BUFFERING_AND_TAIL_SAMPLING.md)** — How the ring buffer and tail sampling work internally
- **[docs/EXPORT_MODES.md](../EXPORT_MODES.md)** — Switch between CONDITIONAL (battery-efficient), CONTINUOUS, and HYBRID export
- **[docs/GEO_DEVICE_POLICY_EXTENSION.md](../GEO_DEVICE_POLICY_EXTENSION.md)** — Write custom export policies targeting specific countries, devices, or network types
- **[BACKLOG.md](../../BACKLOG.md)** — What's being built next
