# Quick Start

Two paths depending on your goal:

- **[Integrate the SDK](#integrate-the-sdk)** — Add mobile observability to your Android app
- **[Run the demo](#run-the-demo)** — Evaluate the full system end-to-end

---

## Integrate the SDK

### Prerequisites

- Android Studio with JDK 17
- Android min SDK 26 (Android 8.0)
- OkHttp (if you want network instrumentation)

### Step 1: Add the SDK

The SDK is not yet on Maven Central. Include it as a local module or clone this repo and add:

```kotlin
// settings.gradle.kts
include(":otel-android-mobile")
project(":otel-android-mobile").projectDir = file("path/to/otel-android-mobile")

// app/build.gradle.kts
dependencies {
    implementation(project(":otel-android-mobile"))
}
```

### Step 2: Initialize in Application.onCreate()

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OTelMobile.start(this, MobileConfig(
            serviceName    = "my-app",
            serviceVersion = "1.0.0",
            collectorEndpoint = "https://collector.example.com:4317"
        ))
    }
}
```

That's it. Auto-instrumentation is now active:

- Uncaught exceptions, coroutine errors, RxJava errors → immediate flush
- App start time, jank frames, memory/thermal vitals → OTel metrics
- Crash / ANR / OOM markers → recovery events on next launch
- UI interactions (taps, scrolls, freezes, ANR detection) → OTel logs
- Predictive flush when crash risk >= 70% or network loss risk >= 70%

### Step 3: (Optional) Network Instrumentation

Add the OkHttp interceptor to your client:

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(OTelNetworkInterceptor.create(
        context  = applicationContext,
        config   = NetworkConfig.production(),
        tracer   = OTelMobile.getTracer("network"),
        propagator = openTelemetry.propagators.textMapPropagator
    ))
    .build()
```

### Step 4: (Optional) Custom Events & Manual Flush

```kotlin
// Custom events
MobileOtel.sendEvent("checkout.completed", mapOf(
    "item_count" to 3,
    "total_cents" to 4299
))

// Report caught exceptions
try {
    riskyOperation()
} catch (e: Exception) {
    MobileOtel.reportError(e, mapOf("context" to "checkout"))
}

// User identity
MobileOtel.identify(UserIdentity(userId = "user123"))

// Manual flush
MobileOtel.forceFlush()                   // flush everything
MobileOtel.forceFlush(windowMinutes = 5)  // last 5 minutes only
```

---

## Run the Demo

The demo app is **Schedulr**, a medical appointment scheduling app. Fault scenarios are triggered through realistic app interactions rather than test buttons, so the telemetry reflects what you'd see in production.

### Prerequisites

- Docker / Kubernetes (k3s works fine)
- kubectl
- Node.js 18+
- Android emulator or physical device (API 26+)

### Step 1: Deploy OTEL Collector

```bash
kubectl apply -f k8s/otel-collector.yaml
kubectl wait --for=condition=ready pod -l app=otel-collector -n mobile-observability --timeout=120s
kubectl get pods -n mobile-observability
```

Expected:
```
NAME                              READY   STATUS
otel-collector-xxxxxxxxxx-xxxxx   1/1     Running
```

> **Do you need the gateway?** For evaluating telemetry collection, the collector alone
> is sufficient. The SDK uses built-in default export policies (ui.freeze → 2-min flush,
> app.crash → 5-min flush) when no remote config is available. Add the gateway only if
> you need dynamic policy management via the Control Plane UI. See the
> [Deployment Guide](guides/DEPLOYMENT_GUIDE.md#gateway-vs-collector-only-which-do-you-need)
> for details.

### Step 2: (Optional) Deploy Gateway and Control Plane UI

Only needed for dynamic policy management:

```bash
# The gateway image must be built first — see the Deployment Guide
kubectl apply -f k8s/otel-gateway.yaml
kubectl wait --for=condition=ready pod -l app=otel-gateway -n mobile-observability --timeout=60s

kubectl port-forward -n mobile-observability svc/otel-gateway 8080:8080 &
curl http://localhost:8080/health  # {"status":"healthy"}

cd control-plane-ui && npm install && npm run dev
# Open http://localhost:3000
```

### Step 3: Start an Emulator

#### Already have AVDs installed? Start one now

```bash
# See what you have
emulator -list-avds
# This project's standard AVDs:
#   Medium_Phone_API_36.1
#   Pixel_3a
#   Pixel_7
```

**With a visible window (normal development):**

```bash
emulator -avd Pixel_7 &
```

The emulator window opens. Wait for the Android home screen before proceeding.

**Headless (CI or low-RAM machines):**

```bash
nohup emulator -avd Pixel_3a -no-window -no-audio -no-snapshot-save \
    > /tmp/emulator.log 2>&1 &
```

Either way, poll until fully booted before installing:

```bash
until adb shell "getprop dev.bootcomplete" 2>/dev/null | grep -q 1; do sleep 5; done
until adb shell "getprop sys.boot_completed" 2>/dev/null | grep -q 1; do sleep 5; done
echo "Ready — proceed to Step 4"
```

> **Pixel_7 (API 36) note:** Takes ~4 minutes to fully boot after `adb` connects. Do **not** install the APK until both props return `1`.

Check it's ready:

```bash
adb devices
#   emulator-5554   device    ← "device" = ready; "offline" = still booting
```

#### Don't have AVDs installed yet? Use Android Studio AVD Manager

Open **Tools → Device Manager**, click **+** → **Create Virtual Device**, choose **Pixel 7 / API 36**, click Finish and press the play button.

Or from the command line:

```bash
sdkmanager "system-images;android-36;google_apis;x86_64"
avdmanager create avd \
    --name Pixel_7 \
    --package "system-images;android-36;google_apis;x86_64" \
    --device "pixel_7"
```

Then start it with the headless command above.

### Step 4: Build, Install, and Launch the Demo App

```bash
cd examples/demo-app
./gradlew installDebug && adb shell am start -n io.opentelemetry.android.demo/.SchedulingActivity
```

To build the APK without installing (useful for CI or sending to a colleague):

```bash
./gradlew assembleDebug
# Output: android/build/outputs/apk/debug/android-debug.apk
```

For a **physical device**: enable **Developer Options → USB Debugging**, connect via USB, and verify with `adb devices` before running `installDebug`.

Point the app at the OTEL Collector (direct) or the gateway (if deployed). Open **Profile → Dash0 Connection** in the running app to update the endpoint at runtime:

```text
Collector-only (emulator):  http://10.0.2.2:4317
With gateway (emulator):    http://10.0.2.2:8080
Dash0 cloud (direct):       https://ingress.<region>.aws.dash0.com:4317
```

Or set it at build time in `assets/otel-config.json`:

```json
{
  "collectorEndpoint": "http://10.0.2.2:4317",
  "serviceName": "schedulr",
  "serviceVersion": "1.1.0"
}
```

### Step 4: Explore the App and Generate Telemetry

Schedulr has five tabs. Fault scenarios emerge from normal app usage:

| Tab / Action | What happens | Telemetry emitted (Severity) |
| --- | --- | --- |
| **Appointments** → pull to refresh | 30% chance of API failure | `appointment.fetch_failed` log **(ERROR)**, `http.status_code` attribute |
| **Book** → tap Book | 25% chance of booking failure | `appointment.booking_failed` log **(ERROR)**, full `page.BookFragment` trace |
| **Book** → duplicate booking | Same provider/day/slot already booked | `appointment.duplicate` span event **(WARN)**, no HTTP call made |
| **Book** → successful booking | Appointment confirmed | `appointment.booked` log (INFO), `page.BookFragment` trace with `form.*` events and `POST /posts` child span |
| **Directions** → Search | Calls Nominatim + OSRM routing APIs | `page.DirectionsFragment` trace with geocode + route HTTP child spans |
| **Calendar** → menu → Load Full History | Allocates 500 appointments | `calendar.history_loaded` log, memory pressure → `app.recovery` on next launch |
| **Profile** → OTel SDK Configuration | Opens OTel SDK settings (buffering, export, sampling, capture toggles) | Sampling rate slider takes effect immediately — no restart needed |
| **Profile** → Dash0 Connection | Opens backend connection settings (endpoint, protocol, auth token, dataset) | Saved; requires restart to reconnect |
| **Tab navigation** | Each tab switch | New `page.<TabName>` span starts on fragment resume |
| **Debug toolbar** (tap bar at top to expand) | | |
| → HTTP 500 | Forces next refresh to fail | `appointment.fetch_failed` **(ERROR)** |
| → Crash | Throws RuntimeException | `app.crash` log **(ERROR)** on next launch |
| → ANR | Blocks main thread 6s | `ui.freeze` log **(ERROR)** with `freeze.duration_ms`, then `app.anr` |
| → Memory | Allocates 100MB | `app.recovery` **(ERROR)** on next launch |
| → Jank | 200ms busy-wait on UI thread | `ui.freeze` log **(ERROR)** with `freeze.duration_ms` |

All error-class events (`ui.freeze`, `app.crash`, `app.anr`, `app.recovery`, HTTP 5xx) emit at **ERROR** severity.

**Page-level span model** — `AutoCaptureManager` opens a `page.<ScreenName>` parent span when any Fragment resumes, and closes it on pause or when an API call completes. All auto-captured taps, scrolls, and network calls on that screen become child spans, giving you a full trace waterfall for every user interaction. Page spans are always forced to sample (`sampling.priority=high`) so taps and scrolls always appear as child spans in the waterfall rather than flat logs. After a booking or route search completes, `OTelMobile.restartPageSpan()` ends the current page span and opens a fresh one so the next action begins a clean trace.

### Step 6: Verify Telemetry

```bash
# Collector logs (look for your events)
kubectl logs -n mobile-observability -l app=otel-collector -f

# Gateway received events
kubectl logs -n mobile-observability -l app=otel-gateway --tail=50
```

Expected in collector output (UI freeze from ANR trigger):
```
SeverityText: ERROR
Body: Str(ui.freeze)
Attributes:
  -> mobile.session.id: Str(...)
  -> freeze.duration_ms: Int(6230)
  -> mobile.screen.name: Str(SchedulingActivity)

SeverityText: ERROR
Body: Str(appointment.fetch_failed)
Attributes:
  -> http.status_code: Int(503)
  -> exception.message: Str(Service Unavailable — scheduling API is down)
```

To watch events in real-time on the device:

```bash
adb logcat | grep -E "OTel|appointment|ui\.freeze|app\.(crash|anr|recovery)"
```

---

## What's Next

- **[Android SDK Guide](ANDROID_SDK_GUIDE.md)** — Full API reference and integration patterns
- **[Export Modes](EXPORT_MODES.md)** — CONDITIONAL, CONTINUOUS, HYBRID explained
- **[Geo/Device Policy Extension](GEO_DEVICE_POLICY_EXTENSION.md)** — Country/region/device-aware export policies
- **[Device Metrics](DEVICE_METRICS.md)** — Health metrics exposed as OTel gauges
- **[User Guide](USER_GUIDE.md)** — Using the Control Plane UI to author export policies
- **[Operations Guide](OPERATIONS_GUIDE.md)** — Production deployment
