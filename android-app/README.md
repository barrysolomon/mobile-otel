# Mobile Observability Android Demo

Kotlin-based Android app demonstrating OTEL-inspired mobile observability with local buffering and selective data flushing.

## Architecture

```
Event Capture → RAM Buffer (5000 events)
                    ↓
              Disk Buffer (Room/SQLite, 50MB)
                    ↓
            Workflow Evaluator (DSL)
                    ↓
         Selective Flush → Gateway → OTEL Collector
```

## Features

* **Ring Buffer**: RAM → Disk with automatic eviction (oldest-first)
* **Workflow Engine**: Executes compiled DSL JSON workflows
* **Selective Flushing**: Only sends data when triggers fire
* **Crash Recovery**: Detects crash on next launch and flushes relevant data
* **Heartbeat**: Reports status to gateway every 30s

## Project Structure

```
android-app/
├── src/main/java/com/mobile/observability/demo/
│   ├── ObservabilitySDK.kt           # Main SDK entry point
│   ├── MainActivity.kt                # Demo UI with scenario buttons
│   ├── data/
│   │   ├── EventEntity.kt             # Room entity for events
│   │   ├── EventDao.kt                # Database access
│   │   ├── CrashMarkerEntity.kt       # Crash detection
│   │   └── ObservabilityDatabase.kt   # Room database
│   ├── buffer/
│   │   └── RingBufferManager.kt       # RAM + Disk buffer logic
│   ├── workflow/
│   │   ├── DSLModels.kt               # Workflow data structures
│   │   └── WorkflowEvaluator.kt       # DSL execution engine
│   └── network/
│       └── GatewayClient.kt           # HTTP client for gateway
├── src/main/res/
│   └── layout/
│       └── activity_main.xml          # Demo UI layout
└── build.gradle.kts                   # Dependencies
```

## Dependencies

```kotlin
// Room (SQLite ORM)
androidx.room:room-runtime:2.6.1
androidx.room:room-ktx:2.6.1

// Networking
com.squareup.okhttp3:okhttp:4.12.0

// JSON
com.google.code.gson:gson:2.10.1

// Coroutines
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3
```

## Build & Run

### Prerequisites

* Android Studio Hedgehog (2023.1.1) or later
* Android SDK 26+ (Android 8.0+)
* Java 17

### Setup

1. Open `android-app` directory in Android Studio
2. Update gateway URL in `build.gradle.kts`:
   ```kotlin
   buildConfigField("String", "GATEWAY_URL", "\"http://YOUR_GATEWAY_IP:8080\"")
   ```
3. Sync Gradle
4. Run on emulator or device

### Gateway URL Configuration

**For Android Emulator:**
```kotlin
// 10.0.2.2 maps to host machine's localhost
buildConfigField("String", "GATEWAY_URL", "\"http://10.0.2.2:8080\"")
```

**For Physical Device:**
```kotlin
// Use your machine's local IP
buildConfigField("String", "GATEWAY_URL", "\"http://192.168.1.X:8080\"")
```

**For k3s Cluster:**
```bash
# Port-forward gateway to host machine
kubectl port-forward -n otel-demo svc/otel-gateway 8080:8080

# Then use 10.0.2.2:8080 for emulator or your IP for device
```

## Demo Scenarios

### A) UI Freeze / Jank

**Trigger:**
* Event `ui.freeze`, OR
* Event `ui.jank` where `duration_ms > 2000`

**Action:**
* Flush last 2 minutes of logs for that session

**Demo:**
1. Click "Generate Normal Traffic" to populate buffer
2. Click "Trigger UI Freeze" or "Trigger UI Jank (>2000ms)"
3. Check gateway logs for flushed events
4. Check collector logs for received OTEL logs

### B) Crash on Startup

**Trigger:**
* Crash marker exists from previous session

**Action:**
* Flush last 5 minutes
* Include crash marker record

**Demo:**
1. Click "Generate Normal Traffic" to populate buffer
2. Click "Write Crash Marker (Restart App)"
3. Force close app (swipe from recent apps)
4. Reopen app
5. SDK detects crash marker and flushes data

### C) Network Error Spike

**Trigger:**
* `http.status >= 500`
* AND route contains `/appointments`

**Action:**
* Targeted flush (matching session only)
* Set sampling to 1.0 for 10 minutes

**Demo:**
1. Click "Generate Normal Traffic"
2. Click "Trigger Network Errors (503 /appointments)"
3. Check gateway logs for 3x 503 error events
4. Verify flush was triggered

## SDK Usage

### Initialization

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        ObservabilitySDK.initialize(
            context = this,
            appId = "my-app",
            gatewayUrl = "http://gateway-url:8080"
        )
    }
}
```

### Capture Events

```kotlin
val sdk = ObservabilitySDK.getInstance()

// Simple event
sdk.captureEvent("button.click")

// Event with attributes
sdk.captureEvent(
    eventName = "http.response",
    attributes = mapOf(
        "status" to 200,
        "route" to "/api/users",
        "duration_ms" to 145
    )
)
```

**Note:** All events automatically include a `demo_run_id` attribute for correlation tracking. This ID is generated once per session and allows you to trace events end-to-end from Android → Gateway → Collector.

### Crash Detection

```kotlin
// In uncaught exception handler
Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
    ObservabilitySDK.getInstance().writeCrashMarker()
    // ... your existing crash handling
}
```

## Ring Buffer Configuration

Configured via workflow DSL from gateway:

```json
{
  "limits": {
    "diskMb": 50,
    "ramEvents": 5000,
    "retentionHours": 24
  }
}
```

### Buffer Behavior

* **RAM Buffer**: ConcurrentLinkedQueue, bounded at 5000 events
* **Disk Buffer**: Room (SQLite), 50MB limit
* **Eviction**: Oldest-first when limits exceeded
* **Retention**: Events older than 24h are deleted
* **Backpressure**: Drop oldest, never block main thread

## Workflow DSL Execution

The app downloads compiled DSL JSON from the gateway and evaluates it locally.

### Example DSL

```json
{
  "version": 1,
  "workflows": [
    {
      "id": "ui-freeze",
      "enabled": true,
      "trigger": {
        "any": [
          { "event": "ui.freeze" },
          {
            "event": "ui.jank",
            "where": [
              { "attr": "duration_ms", "op": ">", "value": 2000 }
            ]
          }
        ]
      },
      "actions": [
        {
          "type": "annotate_trigger",
          "trigger_id": "ui-freeze",
          "reason": "ui freeze or jank"
        },
        {
          "type": "flush_window",
          "minutes": 2,
          "scope": "session"
        }
      ]
    }
  ]
}
```

### Supported Operators

* `==`, `!=` - Equality
* `>`, `>=`, `<`, `<=` - Numeric comparison
* `contains` - String contains (case-insensitive)
* `regex` - Regular expression match

### Supported Actions

* `annotate_trigger` - Add metadata to flushed events
* `flush_window` - Flush last N minutes (scope: session or device)
* `set_sampling` - Adjust sampling rate for duration

## Database Schema

### events table

```sql
CREATE TABLE events (
    id INTEGER PRIMARY KEY,
    event_name TEXT,
    session_id TEXT,
    device_id TEXT,
    trigger_id TEXT,
    config_version INTEGER,
    timestamp INTEGER,
    attributes_json TEXT,
    flushed BOOLEAN,
    created_at INTEGER
);
```

### crash_markers table

```sql
CREATE TABLE crash_markers (
    id INTEGER PRIMARY KEY,
    session_id TEXT,
    timestamp INTEGER,
    processed BOOLEAN
);
```

## Networking

### Gateway Endpoints Used

* `GET /config?app_id=X&device_id=Y` - Fetch workflow config
* `POST /ingest` - Send event batches
* `POST /status` - Send heartbeat (every 30s)

### Request Format (Ingest)

```json
{
  "events": [
    {
      "eventName": "ui.freeze",
      "sessionId": "sess-123",
      "deviceId": "dev-456",
      "triggerId": "ui-freeze",
      "configVersion": 1,
      "timestamp": 1704067200000,
      "attributes": {
        "duration_ms": 3500,
        "screen": "MainActivity"
      }
    }
  ]
}
```

## Testing

### Unit Tests

```bash
./gradlew test
```

### Instrumented Tests

```bash
./gradlew connectedAndroidTest
```

### Manual Testing Checklist

- [ ] Install app on emulator
- [ ] Verify SDK initializes (check Logcat)
- [ ] Generate normal traffic
- [ ] Trigger UI freeze - verify flush
- [ ] Write crash marker - restart - verify recovery
- [ ] Trigger network errors - verify flush
- [ ] Check gateway logs for received events
- [ ] Check collector logs for OTEL logs

## Debugging

### Logcat Tags

```bash
# SDK logs
adb logcat ObservabilitySDK:D *:S

# Ring buffer logs
adb logcat RingBufferManager:D *:S

# Workflow evaluation
adb logcat WorkflowEvaluator:D *:S

# Network logs
adb logcat GatewayClient:D *:S
```

### Common Issues

**Gateway connection fails**
* Check GATEWAY_URL in BuildConfig
* Verify gateway is running and accessible
* Check network permissions in AndroidManifest.xml

**Events not flushing**
* Check if workflows are enabled
* Verify trigger conditions match
* Check Logcat for WorkflowEvaluator output

**Database errors**
* Clear app data
* Verify Room dependencies
* Check for migration issues

## Production Considerations

**NOT included in this MVP:**

* Authentication/authorization
* Data encryption at rest
* Certificate pinning
* Retry logic with exponential backoff
* Offline queue persistence
* Battery optimization
* Proguard rules
* CI/CD configuration

## Next Steps

* Step 4: React control plane UI (workflow builder)

## License

Apache 2.0 (for demo purposes)
