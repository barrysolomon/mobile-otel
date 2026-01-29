# Flush Behavior and Triggers

This document details how flush operations work across different signal types (logs, spans, metrics) and the various triggers that initiate a flush.

## Flush Architecture

```mermaid
graph TB
    subgraph "Flush Triggers"
        A[Low Memory Detection]
        B[Policy Match]
        C[Manual User Action]
        D[Periodic Export]
        E[App Recovery]
    end

    A --> F[Flush Controller]
    B --> F
    C --> F
    D --> F
    E --> F

    subgraph "MobileLoggerProvider"
        F --> G[forceFlush]

        G --> H[Log Flush]
        G --> I[Trace Flush]
        G --> J[Metric Flush]

        H --> K[MobileLogRecordProcessor]
        I --> L[BatchSpanProcessor]
        J --> M[PeriodicMetricReader]
    end

    K --> N[Export Logs]
    L --> O[Export Spans]
    M --> P[Export Metrics]

    N --> Q[OTLP Exporter]
    O --> Q
    P --> Q

    Q --> R[OTEL Collector]

    style F fill:#FFE1E1
    style G fill:#FFD700
    style Q fill:#90EE90
```

## Flush Implementation

### Master Flush Method

```mermaid
sequenceDiagram
    participant Trigger
    participant Provider as MobileLoggerProvider
    participant LogProc as Log Processor
    participant SpanProc as Span Processor
    participant MetricReader as Metric Reader

    Trigger->>Provider: forceFlush(timeout=30s)

    par Flush All Signal Types
        Provider->>LogProc: forceFlush()
        Provider->>SpanProc: forceFlush()
        Provider->>MetricReader: forceFlush()
    end

    LogProc-->>Provider: CompletableResultCode
    SpanProc-->>Provider: CompletableResultCode
    MetricReader-->>Provider: CompletableResultCode

    Provider->>Provider: Combine results
    Provider-->>Trigger: Success/Failure
```

**Location**: [MobileLoggerProvider.kt:288-326](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/MobileLoggerProvider.kt#L288-L326)

### Log Flush Process

```mermaid
sequenceDiagram
    participant Controller
    participant Processor as MobileLogRecordProcessor
    participant RAM as RAM Buffer
    participant Disk as Disk Buffer
    participant Exporter as OTLP Exporter

    Controller->>Processor: forceFlush()

    Processor->>RAM: Get all events
    RAM-->>Processor: RAM events

    Processor->>Disk: Load all events
    Disk-->>Processor: Disk events

    Processor->>Processor: Combine RAM + Disk
    Processor->>Processor: Chunk into batches of 100

    loop For each batch
        Processor->>Exporter: export(batch)
        Exporter-->>Processor: Result
    end

    Processor->>RAM: Clear buffer
    Processor->>Disk: Clear buffer

    Processor-->>Controller: CompletableResultCode
```

**Location**: [MobileLogRecordProcessor.kt:318-366](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/MobileLogRecordProcessor.kt#L318-L366)

## Flush Types

### 1. Full Flush (forceFlush)

Exports **ALL** buffered events from RAM and disk.

```mermaid
graph LR
    A[RAM Buffer<br/>5000 events] --> C[Combine]
    B[Disk Buffer<br/>85000 events] --> C
    C --> D[Total: 90000 events]
    D --> E[Batch in 100s<br/>900 batches]
    E --> F[Export All]
```

**Use Cases**:
- Manual flush button
- App shutdown
- Low memory warning
- Critical error detection

### 2. Window Flush (flushWindow)

Exports only events from a **specific time window** (e.g., last 2 minutes).

```mermaid
sequenceDiagram
    participant Policy
    participant Processor as MobileLogRecordProcessor
    participant Buffer
    participant Exporter

    Policy->>Processor: flushWindow(minutes=2)

    Processor->>Processor: Calculate time threshold
    Note over Processor: threshold = now - 2 minutes

    Processor->>Buffer: Load all events
    Buffer-->>Processor: All events

    Processor->>Processor: Filter by timestamp
    Note over Processor: Keep only events >= threshold

    Processor->>Processor: Chunk into batches of 100

    loop For each batch
        Processor->>Exporter: export(batch)
    end

    Processor-->>Policy: Result
```

**Location**: [MobileLogRecordProcessor.kt:208-273](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/MobileLogRecordProcessor.kt#L208-L273)

**Use Cases**:
- UI freeze detection (flush last 2 minutes)
- Error context capture
- Policy-based selective export

## Flush Triggers

### 1. Low Memory Detection

```mermaid
sequenceDiagram
    participant System as Android System
    participant Tracker as RecoveryTracker
    participant Provider as MobileLoggerProvider
    participant Metrics as DeviceMetricsCollector

    System->>Tracker: onLowMemory()
    Tracker->>Tracker: Set low_memory_marker
    Note over Tracker: SharedPreferences

    alt App Killed
        System->>System: Kill app
        Note over System: App restarts later

        Tracker->>Tracker: Check markers
        Tracker->>Tracker: Detect low_memory_marker
        Tracker->>Metrics: Capture memory metrics
        Tracker->>Provider: Log recovery event
        Tracker->>Provider: forceFlush(30s)
        Provider->>Provider: Export all signals
    end
```

**Location**: [RecoveryTracker.kt:66-150](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/autocapture/RecoveryTracker.kt#L66-L150)

**Metrics Captured**:
- `device.memory.used_mb`
- `device.memory.available_mb`
- `device.memory.low_memory` (0 or 1)

### 2. Policy Match (Workflow Trigger)

```mermaid
sequenceDiagram
    participant App
    participant Processor as MobileLogRecordProcessor
    participant Evaluator as PolicyEvaluator
    participant Provider as MobileLoggerProvider

    App->>Processor: onEmit(logRecord)

    Processor->>Evaluator: evaluate(logRecord, policies)
    Evaluator->>Evaluator: Check conditions

    alt Policy Matches
        Evaluator-->>Processor: Match found
        Processor->>Processor: Parse action

        alt Action: flush_window
            Processor->>Processor: flushWindow(minutes=2)
        else Action: flush_all
            Processor->>Provider: forceFlush()
        end
    end
```

**Example Policy**:
```yaml
policies:
  - id: ui-freeze
    match:
      attributes:
        event.name: {equals: "ui.freeze"}
        duration_ms: {gt: 2000}
    actions:
      - type: flush_window
        parameters: {window_minutes: 2}
```

### 3. Manual User Action

```mermaid
sequenceDiagram
    participant User
    participant UI as MainActivity
    participant Provider as MobileLoggerProvider
    participant Exporter

    User->>UI: Click "Force Flush"
    UI->>Provider: forceFlush(30)

    Provider->>Provider: Flush logs + spans + metrics
    Provider->>Exporter: Export all

    alt Success
        Exporter-->>Provider: Success
        Provider-->>UI: Show toast "Flushed"
    else Failure
        Exporter-->>Provider: Failure
        Provider-->>UI: Show toast "Failed"
    end
```

**Location**: [MainActivity.kt](../../examples/demo-app/android/src/main/java/io/opentelemetry/android/demo/MainActivity.kt) - Force Flush button

### 4. Periodic Export (CONTINUOUS/HYBRID modes)

```mermaid
graph TB
    subgraph "Export Mode Configuration"
        A[ExportMode.CONDITIONAL] -->|Export disabled<br/>3600s interval| B[Only flush on trigger]
        C[ExportMode.CONTINUOUS] -->|Export enabled<br/>30s spans, 60s metrics| D[Regular periodic export]
        E[ExportMode.HYBRID] -->|Export enabled<br/>60s spans, 120s metrics| F[Balanced periodic + triggers]
    end

    B --> G[Manual flush required]
    D --> H[Auto export every interval]
    F --> H

    style A fill:#FFD700
    style C fill:#90EE90
    style E fill:#87CEEB
```

**Location**: [MobileLoggerProvider.kt:121-167](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/MobileLoggerProvider.kt#L121-L167)

### 5. App Recovery (Crash/Force Quit)

```mermaid
sequenceDiagram
    participant App as App (Restart)
    participant Provider as MobileLoggerProvider
    participant Tracker as RecoveryTracker
    participant Disk as Disk Buffer

    App->>Provider: initialize()
    Provider->>Tracker: Check recovery markers

    alt Crash Detected
        Tracker-->>Provider: recovery_type: "crash"
        Provider->>Provider: Log recovery event
        Provider->>Disk: Query last 5 minutes
        Disk-->>Provider: Historical events
        Provider->>Provider: forceFlush()
    else Force Quit Detected
        Tracker-->>Provider: recovery_type: "manual_force_quit"
        Provider->>Provider: Log recovery event
        Provider->>Provider: forceFlush()
    else ANR Kill Detected
        Tracker-->>Provider: recovery_type: "anr_force_kill"
        Provider->>Provider: Log recovery event
        Provider->>Provider: forceFlush()
    end
```

**Recovery Types**:
1. `crash` - Uncaught exception
2. `manual_force_quit` - User clicked Force Quit button
3. `anr_force_kill` - User closed during ANR dialog
4. `low_memory_kill` - Android killed due to memory
5. `system_force_kill` - Swipe to kill
6. `clean_start` - Normal launch

## Default Batch Sizes

### Logs
- **RAM Buffer**: 5,000 events
- **Disk Buffer**: 50 MB (~85,000 events)
- **Export Batch Size**: 100 events per batch
- **Total on Full Flush**: Up to 90,000 events

### Spans
- **CONDITIONAL Mode**: 10,000 spans max
- **CONTINUOUS/HYBRID Mode**: 2,048 spans (SDK default)
- **Export**: Per SDK configuration

### Metrics
- **Accumulation**: All metrics since last export
- **Export**: Complete snapshot
- **No batching**: Sent as single payload

## Flush Performance

```mermaid
graph LR
    A[Flush Request] --> B{Size Check}
    B -->|Small<br/><1000 events| C[Fast<br/>50-100ms]
    B -->|Medium<br/>1000-10000 events| D[Moderate<br/>500ms-2s]
    B -->|Large<br/>>10000 events| E[Slow<br/>5-10s]

    C --> F[Success]
    D --> F
    E --> F

    style C fill:#90EE90
    style D fill:#FFD700
    style E fill:#FFB6C1
```

### Optimization Tips
1. Use **window flush** instead of full flush when possible
2. Tune buffer sizes for expected traffic
3. Use **CONDITIONAL mode** for production (battery-friendly)
4. Monitor flush duration via metrics

## Retry Logic

```mermaid
sequenceDiagram
    participant Processor
    participant Exporter as RetryableExporter
    participant Collector

    Processor->>Exporter: export(batch)

    Exporter->>Collector: Attempt 1
    Collector-->>Exporter: Network error

    Note over Exporter: Wait 1s (exponential backoff)

    Exporter->>Collector: Attempt 2
    Collector-->>Exporter: Timeout

    Note over Exporter: Wait 2s

    Exporter->>Collector: Attempt 3
    Collector-->>Exporter: Success

    Exporter-->>Processor: Success
```

**Configuration**:
- **Max Retries**: 3 (configurable)
- **Backoff**: 1s → 2s → 4s → 8s (capped at 60s)
- **Timeout**: 30s per attempt (configurable)

**Location**: [RetryableExporter.kt](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/RetryableExporter.kt)

## Related Documentation
- [02-ring-buffer-architecture.md](02-ring-buffer-architecture.md) - Buffer implementation details
- [04-low-memory-handling.md](04-low-memory-handling.md) - Low memory trigger specifics
- [05-metrics-capture.md](05-metrics-capture.md) - Metrics flush behavior
