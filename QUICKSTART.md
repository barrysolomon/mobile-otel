# Quick Start Guide

**Get up and running with OpenTelemetry Mobile Observability in 15 minutes**

This guide assumes you're starting from scratch and walks through everything step-by-step.

**✨ New: Visual Control Plane UI** - We now include a web-based management console for configuring workflows, managing collectors, and setting up Dash0 integration. See **Step 10** below!

---

## 📋 Quick Navigation

**Get Started (Steps 1-5)**: Clone → Setup → Run Demo
**Try Features (Steps 6-9)**: Test scenarios → View data → Integrate into your app
**⭐ Control Plane UI (Step 10)**: Visual workflow editor + Dash0 setup ← **Recommended!**
**Advanced (Steps 11-12)**: Offline testing → Production deployment

**⏱️ Time Estimates:**
- Minimal setup (demo only): 10 minutes
- With Control Plane UI: 15 minutes
- Full integration in your app: 30 minutes

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
├── control-plane-ui/           # Web UI for visual configuration (NEW!)
├── collector-processor/        # Backend processor (optional)
└── docs/                       # Documentation
```

### The Complete Stack

```
┌─────────────────────────────────────────┐
│      Control Plane UI (Web)            │  ← Configure workflows visually
│      http://localhost:3000              │     Manage Dash0 integration
└─────────────────────────────────────────┘
                    ↓ exports config JSON
┌─────────────────────────────────────────┐
│      Your Android App                   │  ← Uses bundled config
│      + otel-android-mobile library      │     Sends telemetry
└─────────────────────────────────────────┘
                    ↓ OTLP/gRPC
┌─────────────────────────────────────────┐
│      OTEL Collector                     │  ← Routes telemetry
│      localhost:4317 or Dash0           │     Processes data
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│      Backend (Dash0, Jaeger, etc.)     │  ← Visualize & analyze
└─────────────────────────────────────────┘
```

For this quick start, we'll focus on the **demo app** and **Control Plane UI**.

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

The demo app can work without a collector, but to see data flow in Jaeger, let's set one up.

### Option A: Using Docker (Recommended)

If you have Docker installed:

```bash
cd mobile-otel/k8s
docker-compose up -d
```

This starts two services:
- **OTEL Collector** on `http://localhost:4317` (gRPC) and `http://localhost:4318` (HTTP)
- **Jaeger UI** on `http://localhost:16686` (for viewing traces and logs)

**Verify it's running:**

```bash
docker-compose ps
```

You should see both `otel-collector` and `jaeger` with status "Up".

**View collector logs:**

```bash
docker logs -f otel-collector
```

**Stop the services when done:**

```bash
docker-compose down
```

### Option B: Skip the Collector

You can run the demo without a collector. Events will be buffered locally and you can see them in the app logs. However, you won't be able to visualize traces in Jaeger UI.

---

### Important: Network Configuration for Android

**Android Emulator**: Use `http://10.0.2.2:4317` as your collector endpoint
- The emulator maps `10.0.2.2` to your host machine's `localhost`

**Real Android Device**: Use `http://YOUR_MACHINE_IP:4317`
- Find your machine's IP: `ipconfig getifaddr en0` (macOS) or `ipconfig` (Windows)
- Example: `http://192.168.1.100:4317`
- Make sure your device and computer are on the same network

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
2. In the **Service** dropdown, select your app (e.g., `demo-app` or the service name from your config)
3. Click **Find Traces**
4. You'll see traces from your demo scenarios
5. Click on a trace to see detailed spans and timing information

**Tip**: It may take 10-30 seconds for data to appear in Jaeger after you trigger an event in the app.

### In Collector Logs (real-time)

```bash
docker logs -f otel-collector
```

You'll see detailed output as the collector receives and processes your telemetry data.

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
            exportMode = ExportMode.CONDITIONAL,  // Battery-efficient default
            ramBufferSize = 5000,
            diskBufferMb = 50,
            diskBufferTtlHours = 24,
            traceExportIntervalSeconds = 30,  // For CONTINUOUS mode
            metricExportIntervalSeconds = 60   // For CONTINUOUS mode
        )

        // Initialize the logger provider
        MobileLoggerProvider.initialize(config)
    }
}
```

**Export Modes:**
- `ExportMode.CONDITIONAL` (default) - Only exports on triggers or manual flush (battery-efficient)
- `ExportMode.CONTINUOUS` - Regular scheduled exports (development/debug)
- `ExportMode.HYBRID` - Balanced approach (2x intervals + triggers)

See [docs/EXPORT_MODES.md](docs/EXPORT_MODES.md) for details.

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

### 9.4 Use Bundled Configuration (Recommended)

**💡 Tip: Use the Control Plane UI** (Step 10) to visually create your config instead of manually editing JSON. The UI validates your configuration and exports production-ready JSON.

**Option A: Bundled Config (Offline-first)**

Create `assets/otel-config.json` that ships with your app (or export from Control Plane UI):

```json
{
  "serviceName": "my-awesome-app",
  "serviceVersion": "1.0.0",
  "collectorEndpoint": "http://10.0.2.2:4317",
  "exportMode": "CONDITIONAL",
  "ramBufferSize": 5000,
  "diskBufferMb": 50,
  "diskBufferTtlHours": 24,
  "workflows": [
    {
      "id": "crash-detection",
      "name": "Crash Detection",
      "enabled": true,
      "trigger": {
        "all": [
          {
            "event": "app.crash",
            "where": []
          }
        ]
      },
      "actions": [
        {"type": "flush_window", "minutes": 10, "scope": "device"}
      ]
    },
    {
      "id": "http-errors",
      "name": "HTTP Error Handler",
      "enabled": true,
      "trigger": {
        "all": [
          {
            "event": "http.error",
            "where": [{"attr": "http.status_code", "op": ">=", "value": 500}]
          }
        ]
      },
      "actions": [
        {"type": "flush_window", "minutes": 5, "scope": "session"}
      ]
    }
  ]
}
```

Then load it using ConfigManager:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Loads bundled config on first launch, then uses runtime config
        val config = ConfigManager.loadConfig(this)
        MobileLoggerProvider.initialize(config)
    }
}
```

**Benefits:**
- Works immediately without network connectivity
- Configuration persists across app restarts
- Can be updated dynamically via Control Plane
- Environment-specific configs via build variants

See [docs/BUNDLED_CONFIG.md](docs/BUNDLED_CONFIG.md) for details.

**Option B: Programmatic Configuration**

If you prefer code-based config:

```kotlin
val config = MobileConfig(
    serviceName = "my-awesome-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "http://your-collector:4317",
    exportMode = ExportMode.CONDITIONAL
)
```

---

## Step 10: Use the Control Plane UI (⭐ Recommended)

> **Why use the Control Plane UI?**
> - 🎨 **Visual workflow builder** - No JSON editing required
> - ✅ **Real-time validation** - Catch errors before deployment
> - 🚀 **Dash0 one-click setup** - Automatic header configuration
> - 📦 **Template library** - Pre-built workflows for common scenarios
> - 🔄 **Multi-environment** - Separate configs for dev/staging/prod
> - 📤 **Export to JSON** - Generate bundled config for your app

The **Control Plane UI** is a web-based management console that lets you visually configure workflows, manage collector endpoints, and monitor your mobile observability setup.

### 10.1 Start the Control Plane UI

From the project root:

```bash
cd control-plane-ui
npm install        # First time only
npm run dev
```

The UI will start on **`http://localhost:3000`**

**System Requirements:**
- Node.js 18+
- npm or yarn

### 10.2 Navigate the Interface

**UI Preview:**
```
┌─────────────────────────────────────────────────────────┐
│  Mobile OTEL Control Plane            [User] [Settings] │
├──────────┬──────────────────────────────────────────────┤
│ 📊 Dash  │  System Overview                            │
│          │  ┌──────────┐  ┌──────────┐  ┌──────────┐   │
│ 🔄 Work  │  │  Active  │  │ Collect- │  │  Recent  │   │
│  flows   │  │Workflows │  │   ors    │  │  Events  │   │
│          │  │    12    │  │     2    │  │   1,234  │   │
│ 🔗 Coll  │  └──────────┘  └──────────┘  └──────────┘   │
│  ectors  │                                              │
│          │  [Visual Workflow Canvas]                    │
│ ⚙️ Sett  │  ┌────────┐     ┌────────┐     ┌────────┐   │
│  ings    │  │Trigger │ --> │ Action │ --> │Metrics │   │
│          │  └────────┘     └────────┘     └────────┘   │
└──────────┴──────────────────────────────────────────────┘
```

The Control Plane has 4 main sections:

#### 📊 Dashboard (Home)
- Overview of your observability setup
- Active workflows count
- Collector endpoints status
- Recent configuration changes

#### 🔄 Workflows Tab
- **Visual workflow editor** with drag-and-drop node builder
- 25+ node types (triggers, conditions, actions)
- Real-time validation
- Export to JSON for bundled config

#### 🔗 Collectors Tab
- Manage OTEL collector endpoints
- Configure Dash0 integration
- Test connectivity
- Environment-specific endpoints (dev/staging/prod)

#### ⚙️ Settings Tab
- Global configuration
- Export modes (CONDITIONAL/CONTINUOUS/HYBRID)
- Buffer settings (RAM/disk)
- Sampling rates

### 10.3 Create Your First Workflow (Visual Editor)

Let's create a workflow that captures device metrics on API errors:

1. **Open the Workflows tab**
   - Click **"Workflows"** in the left sidebar
   - Click **"+ New Workflow"** button

2. **Add a Trigger Node**
   - Drag **"Event Trigger"** from the palette
   - Set event name: `http.error`
   - Click **"Add Condition"**
   - Set: `http.status_code >= 500`

3. **Add Action Nodes**
   - Drag **"Flush Window"** node
     - Set minutes: `10`
   - Drag **"Capture Device Metrics"** node
   - Drag **"Set Sampling Rate"** node
     - Set rate: `1.0` (100%)
     - Set duration: `10` minutes

4. **Connect the Nodes**
   - Click and drag from trigger output to action inputs
   - The editor validates connections automatically

5. **Save & Export**
   - Click **"Save Workflow"**
   - Give it a name: "API Error Handler"
   - Click **"Export"** to download JSON
   - Place exported JSON in `assets/otel-config.json`

**Visual representation of your workflow:**
```
┌───────────────────────────────────────────────────────┐
│  Workflow: API Error Handler                         │
├───────────────────────────────────────────────────────┤
│                                                       │
│  [Event Trigger: http.error]                         │
│         └─ Condition: status_code >= 500             │
│                   │                                   │
│                   ├─> [Flush Window: 10 min]         │
│                   │                                   │
│                   ├─> [Capture Device Metrics]       │
│                   │                                   │
│                   └─> [Set Sampling: 100% for 10min] │
│                                                       │
└───────────────────────────────────────────────────────┘
```

**Your workflow is now ready to use!**

**What happens when triggered:**
1. ⚡ User's app encounters HTTP 500+ error
2. 🔍 Workflow evaluates: `status_code >= 500` → TRUE
3. 📤 Flushes last 10 minutes of events
4. 📊 Captures complete device metrics snapshot
5. 🎯 Increases trace sampling to 100% for next 10 minutes
6. ✅ All data sent to Dash0 for analysis

### 10.4 Configure Dash0 Integration

If you're using **Dash0** as your backend:

1. **Go to Collectors tab**
   - Click **"Collectors"** in sidebar

2. **Add Dash0 Endpoint**
   - Click **"+ Add Collector"**
   - Select **"Dash0"** from provider dropdown
   - Enter your details:
     - **Ingress URL**: `https://ingress.us.dash0.com:4317`
     - **Auth Token**: Your Dash0 authorization token
     - **Dataset**: `mobile-prod` (or your dataset name)
   - Click **"Test Connection"** to verify
   - Click **"Save"**

3. **Set as Default**
   - Click the **star icon** next to your Dash0 collector
   - This sets it as the default endpoint for your app

4. **Export Configuration**
   - Click **"Export Config"**
   - Save to `assets/otel-config.json` in your app
   - Rebuild your app

**Headers are automatically configured:**
```json
{
  "collectorEndpoint": "https://ingress.us.dash0.com:4317",
  "headers": {
    "Authorization": "Bearer YOUR_DASH0_TOKEN",
    "Dash0-Dataset": "mobile-prod"
  }
}
```

### 10.5 Test Your Configuration

1. **Verify in the UI**
   - Dashboard should show "Connected" status
   - Workflows show as "Active"

2. **Test in Demo App**
   - Run the demo app
   - Trigger an API error (Scenario C)
   - Check Dash0 dashboard for incoming data

3. **Monitor in Real-Time**
   - Control Plane UI shows recent events
   - Dash0 shows traces and logs
   - Collector logs show processing

### 10.6 Visual Workflow Examples

The Control Plane includes **pre-built workflow templates**:

**Template: Crash Detection & Recovery**
```
[app.crash event]
  → [Flush 10 minutes]
  → [Capture device metrics]
  → [Increase sampling to 100% for 30 min]
```

**Template: Memory Pressure**
```
[memory.available_mb < 50]
  → [Log tail: last 20 logs]
  → [Capture metrics]
  → [Flush 5 minutes]
```

**Template: Force Close Investigation**
```
[app.force_close event]
  → [Flush 15 minutes]
  → [Log tail: last 50 logs]
  → [Capture all device metrics]
```

**Template: API Error Cascade**
```
[3+ http errors in 10 logs]
  → [Flush 10 minutes]
  → [Capture network metrics]
  → [Set sampling: 100% for 10 min]
```

**To use a template:**
1. Click **"Templates"** in Workflows tab
2. Select a template
3. Click **"Load Template"**
4. Customize as needed
5. Export to your app

### 10.7 Workflow Validation

The editor validates your workflows in real-time:

✅ **Valid workflow indicators:**
- Green checkmarks on all nodes
- Connections show as solid lines
- "Valid" badge in top-right

⚠️ **Common issues:**
- **Red X on node**: Missing required fields
- **Dashed lines**: Incompatible connection types
- **Orange warning**: Valid but suboptimal (e.g., too frequent)

### 10.8 Environment-Specific Configs

Manage different configs per environment:

1. **Create environments** in Settings:
   - Development: `http://10.0.2.2:4317` (local)
   - Staging: `https://staging-collector:4317`
   - Production: Dash0 ingress URL

2. **Export per environment**:
   - Select environment dropdown
   - Click "Export Config"
   - Save to build variant assets:
     - `assets/debug/otel-config.json` (dev)
     - `assets/release/otel-config.json` (prod)

3. **Android loads correct config** based on build variant automatically

### 10.9 Control Plane Features Summary

| Feature | Description | Benefit |
|---------|-------------|---------|
| **Visual Workflow Builder** | Drag-and-drop node editor with 25+ types | No JSON editing needed |
| **Real-time Validation** | Instant feedback on configuration | Catch errors before deployment |
| **Dash0 Integration** | One-click setup for Dash0 backend | Seamless observability platform |
| **Multi-environment** | Separate configs for dev/staging/prod | Safe testing before production |
| **Template Library** | Pre-built workflows for common scenarios | Quick setup, best practices |
| **Export to JSON** | Download bundled config for app | Offline-first configuration |
| **Collector Management** | Add/remove/test OTEL collectors | Centralized endpoint management |
| **Live Preview** | See workflow execution flow | Understand behavior before deploy |

### 10.10 Complete Example: Production Setup with Dash0

Here's a complete end-to-end setup using the Control Plane UI:

**Step 1: Configure Dash0 Collector** (2 minutes)
1. Open Control Plane UI → Collectors tab
2. Click "+ Add Collector"
3. Enter:
   - Name: "Dash0 Production"
   - Endpoint: `https://ingress.us.dash0.com:4317`
   - Auth Token: `YOUR_DASH0_TOKEN`
   - Dataset: `mobile-prod`
4. Click "Test Connection" → Should show ✅ Connected
5. Click "Save" and set as default (star icon)

**Step 2: Create Production Workflows** (5 minutes)
1. Go to Workflows tab
2. Load template: "API Error Cascade"
3. Customize:
   - Trigger: `http.error` with `status_code >= 500`
   - Actions: Flush 10 min + Capture metrics + Set sampling 100%
4. Save as "Production API Errors"
5. Repeat for "Crash Detection" and "Force Close Investigation"

**Step 3: Export Configuration** (1 minute)
1. Click "Export Config" button (top-right)
2. Select environment: "Production"
3. Download `otel-config.json`
4. Place in your app: `app/src/main/assets/otel-config.json`

**Step 4: Update Your App** (2 minutes)
```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Loads bundled config (includes Dash0 + workflows)
        val config = ConfigManager.loadConfig(this)
        MobileLoggerProvider.initialize(config)

        Log.i("MyApp", "Connected to Dash0: ${config.collectorEndpoint}")
    }
}
```

**Step 5: Deploy & Monitor** (Ongoing)
1. Build release APK
2. Deploy to production
3. Monitor in Dash0 dashboard:
   - View real-time traces
   - Analyze device metrics
   - Set up alerts for crashes/errors

**Total setup time: ~10 minutes** ⏱️

Your production mobile observability is now fully configured!

### 10.11 Control Plane Documentation

For more details, see:
- **[control-plane-ui/README.md](control-plane-ui/README.md)** - Getting started guide
- **[control-plane-ui/README_WORKFLOWS.md](control-plane-ui/README_WORKFLOWS.md)** - Complete workflow documentation (25 node types)
- **[control-plane-ui/README_COLLECTOR.md](control-plane-ui/README_COLLECTOR.md)** - Collector management & Dash0 setup

---

## Step 11: Test Offline Scenarios

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

## Step 12: View Data in Production

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

### Issue: "Control Plane UI won't start"
**Solution:**
1. Check Node.js version: `node --version` (need 18+)
2. Clear npm cache: `npm cache clean --force`
3. Delete `node_modules` and reinstall: `rm -rf node_modules && npm install`
4. Check port 3000 isn't already in use: `lsof -i :3000`

### Issue: "Dash0 connection test fails"
**Solution:**
1. Verify your Dash0 token is correct
2. Check ingress URL format: `https://ingress.us.dash0.com:4317` (include `:4317`)
3. Test with curl:
   ```bash
   curl -H "Authorization: Bearer YOUR_TOKEN" \
        -H "Dash0-Dataset: mobile-prod" \
        https://ingress.us.dash0.com:4317
   ```
4. Ensure your Dash0 account has mobile observability enabled

### Issue: "Workflow export doesn't work in app"
**Solution:**
1. Make sure JSON is saved in correct location: `app/src/main/assets/otel-config.json`
2. Rebuild the app (assets aren't hot-reloaded)
3. Verify JSON is valid: Copy to [jsonlint.com](https://jsonlint.com)
4. Check ConfigManager is loading bundled config: `ConfigManager.loadConfig(this)`

---

## Next Steps

Now that you've got the basics working, explore:

### Essential Documentation
- **[INTRODUCTION.md](INTRODUCTION.md)** - Detailed project overview with FAQ
- **[docs/guides/OFFLINE_RESILIENCE.md](docs/guides/OFFLINE_RESILIENCE.md)** - Deep dive into crash recovery and network loss handling
- **[docs/BUNDLED_CONFIG.md](docs/BUNDLED_CONFIG.md)** - Pre-configured settings shipped with app
- **[WHY_NOT_A_FORK.md](WHY_NOT_A_FORK.md)** - Understanding the OTEL-native approach

### Advanced Features
- **[docs/EXPORT_MODES.md](docs/EXPORT_MODES.md)** - CONDITIONAL vs CONTINUOUS vs HYBRID modes
- **[docs/WORKFLOW_SYSTEM.md](docs/WORKFLOW_SYSTEM.md)** - Complete workflow architecture
- **[control-plane-ui/README_WORKFLOWS.md](control-plane-ui/README_WORKFLOWS.md)** - Visual workflow editor (25 node types)
- **[control-plane-ui/README_COLLECTOR.md](control-plane-ui/README_COLLECTOR.md)** - Manage collector endpoints & Dash0 integration

### Technical References
- **[docs/reference/ARCHITECTURE.md](docs/reference/ARCHITECTURE.md)** - System design and implementation details
- **[docs/oteps/OTEP-PREDICTIVE-TELEMETRY.md](docs/oteps/OTEP-PREDICTIVE-TELEMETRY.md)** - ML-based predictive telemetry (experimental)

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
- ✅ Use the Control Plane UI for visual workflow configuration
- ✅ Configure Dash0 integration with one-click setup
- ✅ Create workflows using the drag-and-drop editor
- ✅ Understand the two-tier buffering system
- ✅ Test offline resilience scenarios
- ✅ Integrate the library into your own app
- ✅ Configure export policies (visually or via JSON)
- ✅ View telemetry data in collectors and Dash0

**You're ready to build production-grade mobile observability!**

---

## Quick Reference

### Start Control Plane UI
```bash
cd control-plane-ui && npm install && npm run dev
# Open http://localhost:3000
```

### Dash0 Configuration
```json
{
  "collectorEndpoint": "https://ingress.us.dash0.com:4317",
  "headers": {
    "Authorization": "Bearer YOUR_TOKEN",
    "Dash0-Dataset": "mobile-prod"
  }
}
```

### Demo App Scenarios
- **UI Freeze**: Logs `ui.freeze` event, flushes 2 minutes
- **Crash**: Sets marker, flushes on next start
- **API Error**: Logs `http.error`, triggers sampling increase
- **Force Flush**: Manual export of all buffered events

### Control Plane Shortcuts
- **Workflows**: Visual editor with 25+ node types
- **Collectors**: Manage endpoints, test connections
- **Templates**: Pre-built workflows for common scenarios
- **Export**: Download JSON for bundled config
