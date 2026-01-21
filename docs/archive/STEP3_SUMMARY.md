# Step 3 Complete: Android App Implementation

## Overview

Kotlin-based Android app with local buffering, workflow execution, and selective data flushing to the Go gateway.

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                   Android App                         │
│                                                       │
│  ┌──────────────┐      ┌──────────────────────┐    │
│  │ Event        │─────▶│  RAM Buffer          │    │
│  │ Capture      │      │  (ConcurrentQueue)   │    │
│  └──────────────┘      │  Max: 5000 events    │    │
│                         └──────┬───────────────┘    │
│                                │                     │
│                         ┌──────▼───────────────┐    │
│  ┌──────────────┐      │  Disk Buffer         │    │
│  │ Workflow     │◀────▶│  (Room/SQLite)       │    │
│  │ Evaluator    │      │  Max: 50MB           │    │
│  │ (DSL)        │      │  Retention: 24h      │    │
│  └──────┬───────┘      └──────┬───────────────┘    │
│         │                     │                     │
│         │ Trigger             │ Flush               │
│         └──────────┬──────────┘                     │
│                    │                                 │
│             ┌──────▼────────┐                       │
│             │ Gateway Client│                       │
│             │ (OkHttp)      │                       │
│             └──────┬────────┘                       │
└────────────────────┼──────────────────────────────┘
                     │ JSON/HTTP
              ┌──────▼────────┐
              │   Gateway     │
              │   (Go)        │
              └───────────────┘
```

## Files Created

### Core SDK
```
android-app/src/main/java/com/mobile/observability/demo/
├── ObservabilitySDK.kt                # Main SDK orchestrator
│   - Event capture API
│   - Workflow execution
│   - Crash marker detection
│   - Heartbeat sender
│   - Config fetcher
│
├── buffer/
│   └── RingBufferManager.kt           # RAM + Disk buffer management
│       - ConcurrentLinkedQueue for RAM
│       - Room database for disk
│       - Automatic eviction (oldest-first)
│       - Size and retention enforcement
│
├── workflow/
│   ├── DSLModels.kt                   # Data structures for DSL
│   │   - DSLConfig, Workflow, Trigger, Action
│   │   - Predicate operators
│   │
│   └── WorkflowEvaluator.kt           # DSL execution engine
│       - Trigger evaluation (any/all)
│       - Predicate operators (==, !=, >, <, contains, regex)
│       - Action dispatch
│
├── network/
│   └── GatewayClient.kt               # HTTP client for gateway
│       - GET /config
│       - POST /ingest
│       - POST /status
│
└── data/
    ├── EventEntity.kt                 # Room entity for events
    ├── EventDao.kt                    # Database queries
    ├── CrashMarkerEntity.kt           # Crash detection entity
    └── ObservabilityDatabase.kt       # Room database setup
```

### Demo UI
```
android-app/src/main/
├── java/.../MainActivity.kt            # Demo scenarios UI
│   - Scenario A: UI Freeze/Jank buttons
│   - Scenario B: Crash marker button
│   - Scenario C: Network error button
│   - Normal traffic generator
│
└── res/layout/
    └── activity_main.xml               # Demo UI layout
```

### Configuration
```
android-app/
├── build.gradle.kts                    # Dependencies & build config
├── settings.gradle.kts                 # Project setup
├── build.gradle.kts.root               # Root build file
├── src/main/AndroidManifest.xml        # App manifest
└── src/main/res/values/strings.xml     # String resources
```

## Key Features Implemented

### 1. Ring Buffer (RAM → Disk)

**RAM Buffer:**
* `ConcurrentLinkedQueue` for thread-safe, lock-free operations
* Bounded at 5,000 events
* Automatic flush to disk when full
* Never blocks UI thread

**Disk Buffer:**
* Room (SQLite) for persistence
* 50MB size limit with automatic eviction
* 24-hour retention window
* Indexed queries for fast lookups

**Eviction Strategy:**
* Oldest-first (FIFO)
* Enforced on insert
* Separate cleanup for flushed vs unflushed events

### 2. Workflow Evaluator

**DSL Execution:**
* Parses JSON workflows from gateway
* Evaluates triggers on every event
* Supports `any` (OR) and `all` (AND) logic
* Executes actions when triggered

**Predicate Operators:**
* `==`, `!=` - Equality comparison
* `>`, `>=`, `<`, `<=` - Numeric comparison
* `contains` - String substring (case-insensitive)
* `regex` - Regular expression matching

**Actions:**
* `annotate_trigger` - Add metadata
* `flush_window` - Flush last N minutes (session or device scope)
* `set_sampling` - Adjust sampling rate

### 3. Selective Flushing

**Window-Based:**
* Flush last N minutes based on timestamp
* Session-scoped or device-scoped
* Only unflushed events sent

**Triggered By:**
* Workflow actions
* Explicit SDK calls
* Crash recovery on startup

### 4. Crash Recovery

**Detection:**
* Write crash marker before crash
* On next launch, check for unprocessed markers
* Emit `crash_marker` event to trigger workflow

**Action:**
* Default workflow flushes last 5 minutes
* Includes crash marker metadata
* Marks marker as processed

### 5. Gateway Integration

**Config Fetch:**
* Downloads DSL JSON on startup
* Uses version number for change detection
* Falls back to default config if unreachable

**Event Ingestion:**
* Batches events for efficiency
* JSON format compatible with gateway
* Converts to OTEL Logs on gateway side

**Heartbeat:**
* Every 30 seconds
* Reports buffer usage, last triggers, config version
* Visible in control plane UI (Step 4)

## Demo Scenarios Implemented

### Scenario A: UI Freeze/Jank

**Button:** "Trigger UI Freeze" or "Trigger UI Jank (>2000ms)"

**Workflow:**
```json
{
  "trigger": {
    "any": [
      { "event": "ui.freeze" },
      { "event": "ui.jank", "where": [{"attr": "duration_ms", "op": ">", "value": 2000}] }
    ]
  },
  "actions": [
    { "type": "flush_window", "minutes": 2, "scope": "session" }
  ]
}
```

**Result:** Flushes last 2 minutes to gateway → OTEL collector

### Scenario B: Crash Recovery

**Button:** "Write Crash Marker (Restart App)"

**Flow:**
1. Click button → writes crash marker to DB
2. Force close app
3. Restart app → SDK detects marker
4. Emits `crash_marker` event
5. Workflow flushes last 5 minutes

### Scenario C: Network Error Spike

**Button:** "Trigger Network Errors (503 /appointments)"

**Workflow:**
```json
{
  "trigger": {
    "all": [
      { "event": "http.response", "where": [{"attr": "status", "op": ">=", "value": 500}] },
      { "event": "http.response", "where": [{"attr": "route", "op": "contains", "value": "/appointments"}] }
    ]
  },
  "actions": [
    { "type": "flush_window", "minutes": 10, "scope": "session" },
    { "type": "set_sampling", "rate": 1.0, "duration_minutes": 10 }
  ]
}
```

**Result:** Targeted flush + sampling rate adjustment

## Dependencies (Verified)

```kotlin
// Core Android
androidx.core:core-ktx:1.12.0
androidx.appcompat:appcompat:1.6.1
com.google.android.material:material:1.11.0

// Room (SQLite)
androidx.room:room-runtime:2.6.1
androidx.room:room-ktx:2.6.1
androidx.room:room-compiler:2.6.1 (KSP)

// Networking
com.squareup.okhttp3:okhttp:4.12.0
com.squareup.okhttp3:logging-interceptor:4.12.0

// JSON
com.google.code.gson:gson:2.10.1

// Coroutines
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3

// WorkManager
androidx.work:work-runtime-ktx:2.9.0
```

## Build Requirements

* Android Studio Hedgehog (2023.1.1) or later
* Gradle 8.2+
* Kotlin 1.9.20
* KSP 1.9.20-1.0.14
* Android SDK 26+ (minSdk)
* Android SDK 34 (targetSdk/compileSdk)
* Java 17

## Configuration

### Gateway URL

Update in `build.gradle.kts`:

```kotlin
buildConfigField("String", "GATEWAY_URL", "\"http://10.0.2.2:8080\"")
```

* **Emulator:** `http://10.0.2.2:8080` (maps to host localhost)
* **Device:** Use your machine's local IP
* **k3s:** Port-forward gateway first

### Database

Location: `/data/data/com.mobile.observability.demo/databases/observability_database`

### Logs

Logcat tags:
* `ObservabilitySDK` - Main SDK logs
* `RingBufferManager` - Buffer operations
* `WorkflowEvaluator` - Trigger evaluation
* `GatewayClient` - Network requests

## Testing

### Build

```bash
cd android-app
./gradlew build
```

### Run

```bash
./gradlew installDebug
adb shell am start -n com.mobile.observability.demo/.MainActivity
```

### Test Flow

1. Open app in emulator/device
2. Click "Generate Normal Traffic" (creates 5 events)
3. Click "Trigger UI Freeze"
4. Check gateway logs: `kubectl logs -n otel-demo -l app=otel-gateway`
5. Check collector logs: `kubectl logs -n otel-demo -l app=otel-collector`
6. Verify OTEL logs appear with event details

## Data Flow Example

```
1. User Action
   └─▶ sdk.captureEvent("ui.freeze", attrs)

2. Event Capture
   └─▶ RAM Buffer (add event)
   └─▶ Workflow Evaluator (check triggers)

3. Trigger Match
   └─▶ WorkflowResult("ui-freeze", actions=[flush_window])

4. Action Execution
   └─▶ bufferManager.getEventsForFlush(minutes=2, scope="session")
   └─▶ gatewayClient.ingestEvents(events)

5. Gateway Processing
   └─▶ Convert to OTEL Logs
   └─▶ Export to Collector via OTLP/gRPC

6. Collector Output
   └─▶ Debug/logging exporter
   └─▶ Visible in kubectl logs
```

## Repository Structure Consideration

This Android module is designed to be extractable as a standalone library:

**Publishable Module:**
```
mobile-observability-android/
├── src/main/java/.../
│   ├── ObservabilitySDK.kt
│   ├── buffer/
│   ├── workflow/
│   ├── network/
│   └── data/
└── build.gradle.kts
```

**Demo App:**
```
demo-app/
├── MainActivity.kt
├── res/layout/
└── AndroidManifest.xml
```

This separation allows the core SDK to be published to Maven Central while keeping the demo app separate.

## Next Steps

**STOP HERE**

Say **"continue"** for Step 4: React Control Plane UI
* Visual workflow builder with React Flow
* Workflow publishing/rollback
* Device monitoring dashboard
