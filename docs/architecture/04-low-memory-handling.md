# Low Memory Handling Architecture

This document details how the system detects, responds to, and recovers from low memory conditions on Android.

## Low Memory Detection Flow

```mermaid
graph TB
    subgraph "Android System"
        A[Memory Manager] --> B{Memory Pressure}
        B -->|Critical| C[onTrimMemory<br/>TRIM_MEMORY_COMPLETE]
        B -->|Running Critical| D[onTrimMemory<br/>TRIM_MEMORY_RUNNING_CRITICAL]
        B -->|System Wide| E[onLowMemory]
    end

    C --> F[RecoveryTracker]
    D --> F
    E --> F

    subgraph "Mobile OTel SDK"
        F --> G[Set low_memory_marker<br/>SharedPreferences]

        G --> H{Android Decision}
        H -->|Keep Alive| I[App Continues]
        H -->|Kill Process| J[App Killed]

        J --> K[App Restart Later]
        K --> L[Check Markers]
        L --> M[Detect low_memory_marker]

        M --> N[Capture Metrics]
        M --> O[Log Recovery Event]
        M --> P[Force Flush]

        N --> Q[device.memory.*]
        O --> R[app.recovery]
        P --> S[Export All Signals]
    end

    style F fill:#FFE1E1
    style G fill:#FFD700
    style S fill:#90EE90
```

## Android Memory Callbacks

### ComponentCallbacks2 Integration

```mermaid
classDiagram
    class ComponentCallbacks2 {
        <<interface>>
        +onTrimMemory(level: Int)
        +onLowMemory()
    }

    class RecoveryTracker {
        -prefs: SharedPreferences
        -provider: MobileLoggerProvider
        +onTrimMemory(level: Int)
        +onLowMemory()
        +checkRecovery()
    }

    class MobileLoggerProvider {
        +forceFlush(timeout: Long)
        +logEvent(name: String, attrs: Map)
    }

    ComponentCallbacks2 <|.. RecoveryTracker
    RecoveryTracker --> MobileLoggerProvider
```

**Location**: [RecoveryTracker.kt:66-78](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/autocapture/RecoveryTracker.kt#L66-L78)

### Memory Trim Levels

```mermaid
graph TB
    A[Memory Trim Level] --> B{Level Check}

    B -->|Level >= 80<br/>TRIM_MEMORY_COMPLETE| C[Critical Memory State]
    B -->|Level == 15<br/>TRIM_MEMORY_RUNNING_CRITICAL| D[App Running Critically]
    B -->|Other Levels| E[Informational Only]

    C --> F[Set low_memory_marker=true]
    D --> F
    E --> G[No Action]

    style C fill:#FF6B6B
    style D fill:#FFB6C1
    style E fill:#90EE90
```

**Android Trim Levels**:
- `TRIM_MEMORY_COMPLETE` (80): System critically low, app in background
- `TRIM_MEMORY_RUNNING_CRITICAL` (15): System critically low, app in foreground
- Other levels: Informational hints for memory optimization

## Recovery Detection Sequence

```mermaid
sequenceDiagram
    participant System as Android System
    participant App1 as App (Session 1)
    participant Tracker as RecoveryTracker
    participant Prefs as SharedPreferences

    Note over System: Memory pressure increases

    System->>Tracker: onLowMemory()
    Tracker->>Prefs: Set low_memory_marker=true
    Prefs-->>Tracker: Saved

    System->>App1: Kill process
    Note over App1: App terminated

    Note over System: User restarts app later

    participant App2 as App (Session 2)
    participant Provider as MobileLoggerProvider

    App2->>Tracker: checkRecovery()
    Tracker->>Prefs: Get low_memory_marker
    Prefs-->>Tracker: true

    Tracker->>Tracker: Detect low memory kill
    Tracker->>Provider: Capture metrics
    Tracker->>Provider: Log recovery event
    Tracker->>Provider: forceFlush(30s)

    Tracker->>Prefs: Clear markers
    Prefs-->>Tracker: Cleared
```

## Memory Metrics Capture

### Device Memory Metrics

```mermaid
graph TB
    subgraph "Memory Metrics Collection"
        A[ActivityManager.MemoryInfo] --> B[Get Memory Stats]

        B --> C[device.memory.used_mb]
        B --> D[device.memory.available_mb]
        B --> E[device.memory.total_mb]
        B --> F[device.memory.threshold_mb]
        B --> G[device.memory.low_memory]

        C --> H[Current Usage]
        D --> I[Available to App]
        E --> J[Total System RAM]
        F --> K[Low Memory Threshold]
        G --> L[Boolean Flag<br/>0 or 1]
    end

    H --> M[OTEL Metrics]
    I --> M
    J --> M
    K --> M
    L --> M

    style G fill:#FFE1E1
    style M fill:#90EE90
```

**Location**: [DeviceMetricsCollector.kt:92-117](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/metrics/DeviceMetricsCollector.kt#L92-L117)

### Metric Definitions

| Metric Name | Type | Description | Unit |
|------------|------|-------------|------|
| `device.memory.used_mb` | Counter | Memory currently used by system | MB |
| `device.memory.available_mb` | Counter | Memory available to app | MB |
| `device.memory.total_mb` | Counter | Total system RAM | MB |
| `device.memory.threshold_mb` | Counter | Low memory warning threshold | MB |
| `device.memory.low_memory` | Counter | Low memory state detected (0/1) | boolean |

## Low Memory Event Log

```mermaid
graph LR
    A[Recovery Detection] --> B[Create Log Event]

    B --> C[Event Name<br/>app.recovery]
    B --> D[Severity<br/>WARN]
    B --> E[Attributes]

    E --> F[recovery_type:<br/>low_memory_kill]
    E --> G[device.memory.used_mb]
    E --> H[device.memory.available_mb]
    E --> I[device.memory.low_memory: 1]
    E --> J[thread.name: main]
    E --> K[code.namespace]
    E --> L[code.function]

    C --> M[OTLP Export]
    D --> M
    F --> M
    G --> M
    H --> M
    I --> M
    J --> M
    K --> M
    L --> M

    style C fill:#FFD700
    style M fill:#90EE90
```

### Example Log Event

```json
{
  "timestamp": 1706123456789000000,
  "severity": "WARN",
  "body": "app.recovery",
  "attributes": {
    "recovery_type": "low_memory_kill",
    "device.memory.used_mb": 3456,
    "device.memory.available_mb": 234,
    "device.memory.low_memory": 1,
    "device.memory.threshold_mb": 512,
    "thread.name": "main",
    "code.namespace": "io.opentelemetry.android.mobile.autocapture",
    "code.function": "checkRecovery"
  },
  "resource": {
    "service.name": "my-app",
    "service.version": "1.0.0",
    "device.manufacturer": "Samsung",
    "device.model": "SM-G998B"
  }
}
```

## Flush Behavior on Low Memory

### Immediate Flush Trigger

```mermaid
sequenceDiagram
    participant Tracker as RecoveryTracker
    participant Provider as MobileLoggerProvider
    participant LogProc as Log Processor
    participant SpanProc as Span Processor
    participant MetricReader as Metric Reader
    participant Exporter as OTLP Exporter

    Tracker->>Provider: forceFlush(timeout=30s)

    par Flush All Signal Types
        Provider->>LogProc: forceFlush()
        Provider->>SpanProc: forceFlush()
        Provider->>MetricReader: forceFlush()
    end

    LogProc->>Exporter: Export all logs
    SpanProc->>Exporter: Export all spans
    MetricReader->>Exporter: Export all metrics

    Note over Exporter: Batches sent to collector

    Exporter-->>Provider: CompletableResultCode
    Provider-->>Tracker: Flush complete
```

### Data Captured on Low Memory Recovery

```mermaid
graph TB
    A[Low Memory Recovery] --> B[Historical Data]
    A --> C[Current State]
    A --> D[Recovery Event]

    B --> E[Disk Buffer Events<br/>Last 5 minutes]
    B --> F[RAM Buffer Events<br/>Current session]

    C --> G[Memory Metrics<br/>At recovery time]
    C --> H[Device State<br/>Battery, CPU, etc.]

    D --> I[Recovery Log Event<br/>app.recovery]
    D --> J[Correlation ID<br/>demo_run_id]

    E --> K[Export All]
    F --> K
    G --> K
    H --> K
    I --> K
    J --> K

    style K fill:#90EE90
```

## Memory Pressure Response Strategy

```mermaid
stateDiagram-v2
    [*] --> Normal: App Running

    Normal --> LowMemoryWarning: onTrimMemory(MODERATE)
    LowMemoryWarning --> Normal: Memory recovered

    LowMemoryWarning --> Critical: onTrimMemory(COMPLETE)
    LowMemoryWarning --> Critical: onLowMemory()

    Critical --> MarkerSet: Set low_memory_marker
    MarkerSet --> Killed: Android kills process

    Killed --> Restart: User reopens app
    Restart --> Recovery: Check markers

    Recovery --> CaptureMetrics: Detect low_memory_marker
    CaptureMetrics --> LogEvent: Log recovery event
    LogEvent --> Flush: forceFlush()
    Flush --> ClearMarkers: Clear markers

    ClearMarkers --> Normal: Recovery complete

    style Critical fill:#FF6B6B
    style MarkerSet fill:#FFD700
    style Flush fill:#90EE90
```

## Prevention and Mitigation

### Buffer Size Adjustment

Under memory pressure, the system can dynamically adjust buffer sizes:

```mermaid
graph TB
    A[Memory Pressure Detected] --> B{Current RAM Buffer}
    B -->|5000 events| C[Reduce to 1000]
    B -->|Already reduced| D[No change]

    C --> E[Move excess to disk]
    E --> F[Free RAM]

    F --> G[Continue operation]
    D --> G

    style A fill:#FFE1E1
    style F fill:#90EE90
```

**Future Enhancement**: Dynamic buffer resizing based on available memory.

## Testing Low Memory Scenarios

### Simulated Low Memory

```kotlin
// In demo app - Low Memory scenario
fun triggerLowMemory() {
    // Set marker
    prefs.edit().putBoolean("low_memory_marker", true).apply()

    // Log pre-kill event
    logger.logEvent("app.low_memory", mapOf(
        "error.type" to "memory.exhaustion",
        "trigger" to "manual_test"
    ))

    // Simulate memory allocation
    val memoryHog = mutableListOf<ByteArray>()
    repeat(100) {
        memoryHog.add(ByteArray(1024 * 1024 * 100)) // 100 MB chunks
    }
}
```

**Location**: [MainActivity.kt](../../examples/demo-app/android/src/main/java/io/opentelemetry/android/demo/MainActivity.kt) - Low Memory button

### Recovery Testing

```kotlin
// On app restart
fun testRecovery() {
    val tracker = RecoveryTracker(context, provider)
    tracker.checkRecovery()

    // Should detect:
    // 1. low_memory_marker = true
    // 2. Log recovery event
    // 3. Capture memory metrics
    // 4. Trigger flush
}
```

## Performance Impact

### Memory Overhead

| Component | Normal | Low Memory Mode |
|-----------|--------|-----------------|
| RecoveryTracker | ~1 KB | ~1 KB |
| SharedPreferences | ~500 bytes | ~500 bytes |
| Memory metrics | ~2 KB | ~2 KB |
| **Total** | **~3.5 KB** | **~3.5 KB** |

**Negligible impact** - designed to work under memory pressure.

### CPU Overhead

- Memory metrics collection: <5ms
- Marker check on startup: <10ms
- Recovery event logging: <20ms
- Total overhead: **<35ms on app start**

## Configuration

```kotlin
MobileConfig(
    // Memory-related settings
    ramBufferSize = 5000,           // Reduce if memory constrained
    diskBufferMb = 50,              // Disk fallback
    exportTimeoutSeconds = 30,      // Flush timeout

    // Enable low memory tracking
    captureDeviceMetrics = true,
    deviceMetricsConfig = DeviceMetricsConfig(
        captureMemory = true,
        captureReasons = setOf(
            CaptureReason.LOW_MEMORY,
            CaptureReason.APP_START
        )
    )
)
```

## Related Documentation
- [02-ring-buffer-architecture.md](02-ring-buffer-architecture.md) - Buffer survival across kills
- [03-flush-behavior.md](03-flush-behavior.md) - Flush trigger details
- [05-metrics-capture.md](05-metrics-capture.md) - Metrics collection system
