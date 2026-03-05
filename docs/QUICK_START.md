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

The demo app ships with realistic fault scenarios (UI freeze, crash, ANR, OOM, network errors) wired to the full observability pipeline.

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

### Step 3: Build and Install the Demo App

```bash
cd examples/demo-app
./gradlew installDebug
```

Point the app at the OTEL Collector (direct) or the gateway (if deployed):
```kotlin
// Collector-only — direct OTLP/gRPC (emulator)
collectorEndpoint = "http://10.0.2.2:4317"

// With gateway (emulator)
collectorEndpoint = "http://10.0.2.2:8080"
```

### Step 5: Trigger Fault Scenarios

Launch the demo app and tap the fault scenario buttons:

| Button           | What it triggers                    | Telemetry emitted                          |
| ---------------- | ----------------------------------- | ------------------------------------------ |
| UI Freeze        | Random 2-11s ANR simulation         | `ui.freeze` log, freeze duration attribute |
| Real Crash       | Throws RuntimeException             | `app.crash` log on next launch             |
| Network Errors   | HTTP requests returning 4xx/5xx     | `http.error` logs                          |
| Low Memory Kill  | Allocates until OOM                 | `app.recovery` with `low_memory_kill`      |
| True ANR         | 30-second main thread block         | `app.anr` log on next launch               |

### Step 6: Verify Telemetry

```bash
# Collector logs (look for your events)
kubectl logs -n mobile-observability -l app=otel-collector -f

# Gateway received events
kubectl logs -n mobile-observability -l app=otel-gateway --tail=50
```

Expected in collector output:
```
Body: Str(ui.freeze)
Attributes:
  -> session_id: Str(...)
  -> freeze_duration_ms: Int(2340)
  -> event.name: Str(ui.freeze)
```

---

## What's Next

- **[Android SDK Guide](ANDROID_SDK_GUIDE.md)** — Full API reference and integration patterns
- **[Export Modes](EXPORT_MODES.md)** — CONDITIONAL, CONTINUOUS, HYBRID explained
- **[Geo/Device Policy Extension](GEO_DEVICE_POLICY_EXTENSION.md)** — Country/region/device-aware export policies
- **[Device Metrics](DEVICE_METRICS.md)** — Health metrics exposed as OTel gauges
- **[User Guide](USER_GUIDE.md)** — Using the Control Plane UI to author export policies
- **[Operations Guide](OPERATIONS_GUIDE.md)** — Production deployment
