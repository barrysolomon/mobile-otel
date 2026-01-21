# Quick Start Guide

**Get up and running with OpenTelemetry Mobile Observability in 15 minutes**

This guide assumes you're starting from scratch and walks through everything step-by-step.

---

## Prerequisites

Before you begin, make sure you have:

- **Android Studio** installed (latest stable version)
- **Java Development Kit (JDK)** 11 or higher
- **Git** installed
- An Android device or emulator (API level 24+)
- Basic familiarity with Android development (helpful but not required)

---

## Step 1: Clone the Repository

Open your terminal and run:

```bash
git clone https://github.com/dash0/mobile-otel.git
cd mobile-otel
```

---

## Step 2: Understand the Project Structure

Take a quick look at what's in the project:

```
mobile-otel/
├── otel-android-mobile/        # The Android library (what you'll use in your apps)
├── examples/demo-app/          # Demo app showing how it works
├── collector-processor/        # Backend processor (optional)
└── docs/                       # Documentation
```

For this quick start, we'll focus on the **demo app**.

---

## Step 3: Open the Demo App in Android Studio

1. Launch **Android Studio**
2. Click **Open** (or **File → Open**)
3. Navigate to `mobile-otel/examples/demo-app/` (the parent folder, NOT the android subfolder)
4. Click **OK**
5. Wait for Gradle to sync (this may take a few minutes the first time)

If you see any SDK or dependency warnings, click **Install** or **Update** to resolve them.

**Important:** Open the `demo-app` folder, not the `android` subfolder. The project needs the root-level Gradle configuration files.

---

## Step 4: Set Up a Local OTEL Collector (Optional)

The demo app can work without a collector, but to see data flow, let's set one up.

### Option A: Using Docker (Easiest)

If you have Docker installed:

```bash
cd mobile-otel/k8s
docker-compose up -d
```

This starts:
- OTEL Collector on `http://localhost:4317`
- Jaeger UI on `http://localhost:16686` (for viewing traces)

### Option B: Skip the Collector

You can run the demo without a collector. Events will be buffered locally and you can see them in the app logs.

---

## Step 5: Run the Demo App

1. In Android Studio, select a device or emulator from the device dropdown
2. Click the **Run** button (green play icon) or press `Shift + F10`
3. Wait for the app to build and launch

You should see the demo app with several buttons.

---

## Step 6: Try the Demo Scenarios

The demo app has 4 interactive scenarios to demonstrate the library:

### Scenario A: UI Freeze Detection
1. Tap **"Simulate UI Freeze"**
2. The app will freeze for 2.5 seconds (simulating an ANR)
3. This triggers an export policy that flushes the last 2 minutes of events
4. Check the logs: Look for `ui.freeze` event

### Scenario B: Crash Recovery
1. Tap **"Simulate Crash"**
2. The app sets a crash marker and closes
3. Relaunch the app
4. The app detects the "crash" and logs a `app.crash_recovery` event
5. It automatically flushes buffered data from disk

### Scenario C: Network Error
1. Tap **"Simulate Network Error"**
2. This logs an HTTP 500 error event
3. Triggers an export policy (flush + increased sampling)
4. Check logs for `http.error` event

### Force Flush
1. Tap **"Force Flush All Events"**
2. This manually exports all buffered events to the collector
3. You'll see a success/failure message
4. Great for testing connectivity

---

## Step 7: View the Telemetry Data

### In Android Studio Logcat

1. Open **Logcat** (bottom panel in Android Studio)
2. Filter by `OTEL` or `MobileLogger`
3. You'll see events like:
   ```
   [OTEL] Event logged: app.start
   [OTEL] Event logged: user.action
   [OTEL] Event logged: ui.freeze (duration: 2500ms)
   [OTEL] Flushing buffer (triggered by policy: ui-freeze)
   [OTEL] Export successful (42 events)
   ```

### In Jaeger UI (if using Docker collector)

1. Open browser to `http://localhost:16686`
2. Select service: **demo-app**
3. Click **Find Traces**
4. You'll see the traces from your demo scenarios

---

## Step 8: Understand What Just Happened

Here's what the library did behind the scenes:

1. **Event Collection**: When you tapped buttons, events were logged
2. **RAM Buffering**: Events were stored in a fast in-memory ring buffer (5000 events)
3. **Disk Buffering**: When RAM fills up, events move to disk (Room database)
4. **Policy Evaluation**: Each event is checked against export policies
5. **Conditional Export**: When a policy matches (e.g., UI freeze), relevant events are flushed
6. **Retry Logic**: If export fails, it retries with exponential backoff
7. **Crash Recovery**: On restart after "crash", it detected unclean shutdown and recovered buffered data

All of this happened automatically, following OpenTelemetry standards.

---

## Step 9: Integrate into Your Own App

Now let's add this to your own Android app.

### 9.1 Add the Dependency

In your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":otel-android-mobile"))

    // Required dependencies
    implementation("io.opentelemetry:opentelemetry-api:1.32.0")
    implementation("io.opentelemetry:opentelemetry-sdk:1.32.0")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.32.0")

    // For disk buffering
    implementation("androidx.room:room-runtime:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
}
```

### 9.2 Initialize in Your Application Class

Create or update your `Application` class:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Configure the mobile OTEL library
        val config = MobileConfig(
            context = this,
            serviceName = "my-awesome-app",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://your-collector:4317",
            ramBufferSize = 5000,
            diskBufferMb = 50,
            diskBufferTtlHours = 24
        )

        // Initialize the logger provider
        MobileLoggerProvider.initialize(config)
    }
}
```

Don't forget to register it in `AndroidManifest.xml`:

```xml
<application
    android:name=".MyApp"
    ...>
```

### 9.3 Log Events in Your Activities

```kotlin
class MainActivity : AppCompatActivity() {
    private val logger = MobileLoggerProvider.getLogger("MainActivity")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Log app start
        logger.logEvent("app.started", mapOf(
            "screen" to "main",
            "user_id" to "12345"
        ))

        // Log user actions
        findViewById<Button>(R.id.myButton).setOnClickListener {
            logger.logEvent("button.clicked", mapOf(
                "button_id" to "myButton",
                "timestamp" to System.currentTimeMillis()
            ))
        }
    }
}
```

### 9.4 Configure Export Policies

Create `policies.yaml` in your assets folder:

```yaml
policies:
  - id: crash-detection
    match:
      attributes:
        event.name: {equals: "app.crash"}
    actions:
      - type: flush_all

  - id: error-threshold
    match:
      attributes:
        severity: {equals: "ERROR"}
    actions:
      - type: flush_window
        parameters: {window_minutes: 5}

  - id: wifi-only-bulk-export
    match:
      network: {type: "wifi"}
    actions:
      - type: scheduled_flush
        parameters: {interval_minutes: 30}
```

Load policies in your config:

```kotlin
val policies = loadPoliciesFromAssets("policies.yaml")
val config = MobileConfig(
    // ... other config
    exportPolicies = policies
)
```

---

## Step 10: Test Offline Scenarios

Let's test the offline resilience features:

### Test 1: Network Loss
1. Run your app
2. Generate some events (tap buttons, navigate screens)
3. Turn off WiFi and mobile data on your device
4. Generate more events
5. Turn network back on
6. Events should automatically export when connectivity returns

### Test 2: App Crash
1. Force close your app (swipe away from recent apps)
2. Relaunch the app
3. Check logs for `app.crash_recovery` event
4. Buffered events from before the crash should be flushed

### Test 3: Storage Limits
1. Generate lots of events (e.g., log in a loop)
2. Check that buffer respects size limits (50MB default)
3. Oldest events are evicted when full

---

## Step 11: View Data in Production

For production deployments, you'll want to:

1. **Deploy an OTEL Collector** in your infrastructure
2. **Configure backend** (Jaeger, Tempo, or custom)
3. **Set up dashboards** to visualize mobile telemetry
4. **Define alerts** for critical events (crashes, errors)

See [docs/guides/DEPLOYMENT_GUIDE.md](docs/guides/DEPLOYMENT_GUIDE.md) for detailed production setup.

---

## Common Issues & Solutions

### Issue: "Plugin [id: 'com.android.application'] was not found"
**Solution:** This means you opened the wrong folder. Close the project and reopen the `demo-app` folder (not the `android` subfolder). The project needs the root-level `build.gradle.kts` and `settings.gradle.kts` files.

### Issue: "Cannot resolve symbol 'MobileLoggerProvider'"
**Solution:** Make sure you've added the dependency and synced Gradle. Check that the `otel-android-mobile` module is included in your project.

### Issue: "Events not exporting"
**Solution:**
1. Check that a policy is configured to trigger export (nothing exports automatically without a policy or manual flush)
2. Verify collector endpoint is reachable
3. Check logs for export errors
4. Try manual flush: `MobileLoggerProvider.forceFlush()`

### Issue: "App crashes on startup"
**Solution:** Make sure you've initialized `MobileLoggerProvider` in your `Application.onCreate()` before using it.

### Issue: "You need to use a Theme.AppCompat theme (or descendant) with this activity"
**Solution:** If your MainActivity extends `AppCompatActivity`, make sure your AndroidManifest.xml uses an AppCompat theme:
```xml
<application
    android:theme="@style/Theme.AppCompat.Light"
    ...>
```

### Issue: "Force flush failed" or "Export failed after 4 attempts"
**Solution:** This is **expected** when no OTEL Collector is running. The events are safely buffered in RAM and disk, and will be exported when a collector becomes available. This demonstrates the offline resilience feature working correctly.

### Issue: Build errors with AGP 9.0 / Kotlin compilation errors
**Solution:**
- Make sure you're using compatible versions (see `build.gradle.kts`)
- AGP 9.0 includes Kotlin natively - don't apply the Kotlin plugin separately
- Ensure `targetSdk` is in `testOptions` and `lint` blocks, not `defaultConfig` (for libraries)

### Issue: "Gradle sync fails"
**Solution:**
1. Check your internet connection
2. Invalidate caches: **File → Invalidate Caches / Restart**
3. Update Android Studio to the latest version

---

## Next Steps

Now that you've got the basics working, explore:

- **[INTRODUCTION.md](INTRODUCTION.md)** - Detailed project overview with FAQ
- **[docs/guides/OFFLINE_RESILIENCE.md](docs/guides/OFFLINE_RESILIENCE.md)** - Deep dive into crash recovery and network loss handling
- **[docs/reference/ARCHITECTURE.md](docs/reference/ARCHITECTURE.md)** - System design and implementation details
- **[WHY_NOT_A_FORK.md](WHY_NOT_A_FORK.md)** - Understanding the OTEL-native approach

---

## Getting Help

- **Issues:** Check [Known Issues](INTRODUCTION.md#-known-issues) in INTRODUCTION.md
- **Questions:** See [FAQ](INTRODUCTION.md#-frequently-asked-questions)
- **Contributing:** Read [CONTRIBUTING.md](CONTRIBUTING.md)
- **Bug Reports:** Open an issue on GitHub

---

## What You've Learned

You now know how to:
- ✅ Set up and run the demo app
- ✅ Understand the two-tier buffering system
- ✅ Test offline resilience scenarios
- ✅ Integrate the library into your own app
- ✅ Configure export policies
- ✅ View telemetry data in collectors

**You're ready to build production-grade mobile observability!**
