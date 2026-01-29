# Metrics Capture and Ring Buffer Proposal

This document describes the current metrics capture system and proposes an enhancement to use a ring buffer for metrics, similar to the log system.

## Current Metrics Architecture

```mermaid
graph TB
    subgraph "Current Implementation"
        A[App Events] --> B[Meter Provider]
        B --> C[Metrics Collection]

        C --> D[PeriodicMetricReader]

        D --> E{Export Mode}
        E -->|CONDITIONAL<br/>3600s interval| F[No Export<br/>Only on flush]
        E -->|CONTINUOUS<br/>60s interval| G[Auto Export]
        E -->|HYBRID<br/>120s interval| H[Auto Export]

        F --> I[Manual Flush Only]
        G --> J[OTLP Export]
        H --> J

        I --> K[forceFlush<br/>triggered]
        K --> J
    end

    J --> L[OTEL Collector]

    style E fill:#FFD700
    style J fill:#90EE90
```

**Location**: [MobileLoggerProvider.kt:121-142](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/MobileLoggerProvider.kt#L121-L142)

### How Current System Works

#### Metric Collection

```mermaid
sequenceDiagram
    participant App
    participant Meter
    participant Accumulator as Metric Accumulator
    participant Reader as PeriodicMetricReader

    App->>Meter: recordValue(metric, value)
    Meter->>Accumulator: Accumulate
    Accumulator->>Accumulator: Store in memory

    Note over Reader: Timer fires (60s, 120s, or 3600s)

    Reader->>Accumulator: collect()
    Accumulator-->>Reader: Snapshot of all metrics
    Reader->>Reader: Export snapshot
```

#### Current Limitations

1. **No Ring Buffer**: Metrics accumulate indefinitely until export
2. **Memory Growth**: High-cardinality metrics can consume significant memory
3. **All-or-Nothing**: Export sends all metrics (no selective export)
4. **No Historical Context**: Can't flush "last X minutes" of metrics
5. **Loss on Crash**: Accumulated metrics lost if app crashes before export

## Proposed: Metrics Ring Buffer System

### Architecture Overview

```mermaid
graph TB
    subgraph "Proposed Enhancement"
        A[App Events] --> B[Device Metrics Collector]
        B --> C[Metric Snapshots<br/>Every X seconds]

        C --> D[Metrics Ring Buffer]

        subgraph "Two-Tier Buffer"
            D --> E[RAM Buffer<br/>100 snapshots]
            E -->|Overflow| F[Disk Buffer<br/>10 MB]
        end

        G[Flush Triggers] -.-> H[Flush Controller]
        H --> E
        H --> F

        E --> I[Batch Export]
        F --> I

        I --> J[OTLP Exporter]
    end

    J --> K[OTEL Collector]

    style D fill:#FFD700
    style I fill:#90EE90
```

### Metrics Snapshot Structure

```mermaid
classDiagram
    class MetricSnapshot {
        +timestamp: Long
        +captureReason: CaptureReason
        +metrics: Map~String, MetricValue~
        +duration: Long
        +toJson(): String
    }

    class MetricValue {
        +type: MetricType
        +value: Double
        +unit: String
        +attributes: Map~String, Any~
    }

    class MetricType {
        <<enumeration>>
        COUNTER
        GAUGE
        HISTOGRAM
        SUMMARY
    }

    MetricSnapshot --> MetricValue
    MetricValue --> MetricType
```

### Capture Strategy

```mermaid
graph TB
    A[Capture Trigger] --> B{Trigger Type}

    B -->|Periodic<br/>Every X seconds| C[Scheduled Capture]
    B -->|Low Memory| D[Immediate Capture]
    B -->|Error/Crash| E[Immediate Capture]
    B -->|Manual Flush| F[Immediate Capture]
    B -->|Policy Match| G[Immediate Capture]

    C --> H[Create Snapshot]
    D --> H
    E --> H
    F --> H
    G --> H

    H --> I[Add to Ring Buffer]

    I --> J{RAM Buffer Full?}
    J -->|Yes| K[Move oldest to disk]
    J -->|No| L[Keep in RAM]

    K --> M[Ring Buffer]
    L --> M

    style H fill:#FFD700
    style M fill:#90EE90
```

### Implementation Design

#### MetricsRingBuffer Class

```kotlin
class MetricsRingBuffer(
    private val ramBufferSize: Int = 100,  // 100 snapshots
    private val diskBufferMb: Int = 10,    // 10 MB
    private val diskBufferTtlHours: Int = 24,
    private val captureIntervalSeconds: Long = 60
) {
    private val ramBuffer = ConcurrentLinkedQueue<MetricSnapshot>()
    private val diskBuffer = DiskMetricBuffer(diskBufferMb, diskBufferTtlHours)
    private var ramBufferCount = AtomicInteger(0)

    // Capture metrics snapshot
    fun captureSnapshot(reason: CaptureReason) {
        val snapshot = collectCurrentMetrics(reason)
        ramBuffer.add(snapshot)
        ramBufferCount.incrementAndGet()

        checkOverflow()
    }

    // Flush all or window
    fun flush(windowMinutes: Int? = null): List<MetricSnapshot> {
        val ramEvents = ramBuffer.toList()
        val diskEvents = diskBuffer.loadAll()

        val allSnapshots = ramEvents + diskEvents

        return if (windowMinutes != null) {
            val threshold = System.currentTimeMillis() - (windowMinutes * 60 * 1000)
            allSnapshots.filter { it.timestamp >= threshold }
        } else {
            allSnapshots
        }
    }

    // Periodic overflow check
    private fun checkOverflow() {
        if (ramBufferCount.get() > ramBufferSize) {
            val evicted = ramBuffer.poll()
            if (evicted != null) {
                diskBuffer.persist(evicted)
                ramBufferCount.decrementAndGet()
            }
        }
    }
}
```

### Capture Frequency Configuration

```mermaid
graph LR
    A[Export Mode] --> B{Mode}

    B -->|CONDITIONAL| C[Capture every 60s<br/>Export on trigger only]
    B -->|CONTINUOUS| D[Capture every 30s<br/>Export every 60s]
    B -->|HYBRID| E[Capture every 45s<br/>Export every 120s]

    C --> F[Ring Buffer]
    D --> F
    E --> F

    F --> G[Flush on demand]

    style F fill:#FFD700
    style G fill:#90EE90
```

**Key Point**: Metrics are **captured periodically** into ring buffer, but **exported** based on mode and triggers.

## Comparison: Current vs Proposed

| Aspect | Current System | Proposed Ring Buffer |
|--------|---------------|---------------------|
| **Storage** | Accumulator (memory) | Two-tier ring buffer (RAM + Disk) |
| **Crash Resilience** | ❌ Lost on crash | ✅ Disk buffer survives |
| **Historical Context** | ❌ Only current snapshot | ✅ Last 100 snapshots |
| **Selective Export** | ❌ All or nothing | ✅ Window-based export |
| **Memory Growth** | ⚠️ Unbounded | ✅ Bounded (100 snapshots) |
| **Low Memory Handling** | ❌ No special handling | ✅ Immediate capture + flush |
| **Periodic Capture** | ❌ Not supported | ✅ Every X seconds |

## Flush Integration

### Unified Flush Controller

```mermaid
sequenceDiagram
    participant Trigger
    participant Controller as Flush Controller
    participant LogBuffer as Log Ring Buffer
    participant SpanQueue as Span Queue
    participant MetricBuffer as Metric Ring Buffer

    Trigger->>Controller: flush(windowMinutes=2)

    par Flush All Signal Types
        Controller->>LogBuffer: flush(window=2)
        Controller->>SpanQueue: flush()
        Controller->>MetricBuffer: flush(window=2)
    end

    LogBuffer-->>Controller: Last 2 min logs
    SpanQueue-->>Controller: All spans
    MetricBuffer-->>Controller: Last 2 min metrics

    Controller->>Controller: Combine results
    Controller->>Controller: Export to collector
```

### Example: Low Memory Trigger

```mermaid
sequenceDiagram
    participant System as Android System
    participant Tracker as RecoveryTracker
    participant Collector as DeviceMetricsCollector
    participant Buffer as Metrics Ring Buffer
    participant Controller as Flush Controller

    System->>Tracker: onLowMemory()

    Tracker->>Collector: captureImmediately()
    Collector->>Buffer: captureSnapshot(LOW_MEMORY)

    Note over Buffer: Snapshot includes:<br/>- Memory state<br/>- Battery level<br/>- CPU usage<br/>- Network state

    Tracker->>Controller: forceFlush()
    Controller->>Buffer: flush(all)
    Buffer-->>Controller: All snapshots
    Controller->>Controller: Export
```

## Benefits of Ring Buffer Approach

### 1. Crash Resilience

```mermaid
graph TB
    A[Metrics Captured<br/>Every 60s] --> B[RAM Buffer]
    B --> C[Disk Buffer]

    D[App Crashes] -.-> B
    D -.-> C

    E[App Restarts] --> F[Load from Disk]
    F --> G[Recover Last<br/>100 snapshots]

    G --> H[Include in Recovery Flush]

    style B fill:#FFB6C1
    style C fill:#90EE90
    style H fill:#FFD700
```

**Before**: Metrics accumulated since last export are lost
**After**: Last 100 snapshots (up to ~100 minutes) preserved

### 2. Historical Context

```mermaid
timeline
    title Metrics Timeline with Ring Buffer
    section Normal Operation
        T-10min : Snapshot 1 : CPU: 30%<br/>Memory: 500MB
        T-9min  : Snapshot 2 : CPU: 32%<br/>Memory: 510MB
        T-8min  : Snapshot 3 : CPU: 35%<br/>Memory: 520MB
    section Problem Detected
        T-2min  : Snapshot 9 : CPU: 85%<br/>Memory: 1200MB
        T-1min  : Snapshot 10 : CPU: 95%<br/>Memory: 1500MB
        T-0min  : Crash : Export Last 10 Minutes
```

**Use Case**: When a crash occurs, automatically export last 10 minutes of metrics showing gradual memory/CPU increase leading to crash.

### 3. Selective Export

```mermaid
graph TB
    A[100 Snapshots in Buffer] --> B{Policy Match}

    B -->|UI Freeze<br/>2 min window| C[Export Last 2 Minutes]
    B -->|HTTP Error<br/>5 min window| D[Export Last 5 Minutes]
    B -->|Crash<br/>Full history| E[Export All 100 Snapshots]

    C --> F[2 snapshots]
    D --> G[5 snapshots]
    E --> H[100 snapshots]

    F --> I[OTLP Exporter]
    G --> I
    H --> I

    style I fill:#90EE90
```

**Benefit**: Reduced bandwidth and cost - only export relevant context.

### 4. Low Memory Optimization

```mermaid
sequenceDiagram
    participant System
    participant Buffer as Metrics Ring Buffer
    participant Disk

    System->>Buffer: onLowMemory()

    Buffer->>Buffer: Capture final snapshot
    Buffer->>Disk: Flush RAM to disk
    Buffer->>Buffer: Clear RAM buffer

    Note over Buffer: Freed: ~100 snapshots × 2KB = ~200KB

    Buffer-->>System: Memory freed
```

**Benefit**: Under memory pressure, flush metrics to disk and free RAM.

## Performance Characteristics

### Memory Usage

```mermaid
graph LR
    A[Metric Snapshot] --> B[~2 KB per snapshot]
    B --> C[RAM Buffer<br/>100 snapshots]
    C --> D[~200 KB total]

    E[Disk Buffer] --> F[10 MB capacity]
    F --> G[~5000 snapshots]

    style D fill:#FFD700
    style G fill:#90EE90
```

**RAM**: 200 KB (fixed, bounded)
**Disk**: Up to 10 MB (configurable)

### Capture Performance

| Operation | Time | Impact |
|-----------|------|--------|
| Capture snapshot | 5-10ms | Negligible |
| Add to RAM buffer | <1ms | Negligible |
| Overflow to disk | 10-20ms | Background thread |
| Flush (100 snapshots) | 100-200ms | Acceptable |

### Export Volume Reduction

**Before (no ring buffer)**:
- Export all accumulated metrics on every flush
- No control over volume

**After (with ring buffer)**:
- Export only relevant time window
- Example: UI freeze → export last 2 minutes (2 snapshots ~4KB)
- Reduction: 98% less data for focused events

## Configuration

```kotlin
MobileConfig(
    // Metrics ring buffer settings
    metricsRamBufferSize = 100,              // 100 snapshots
    metricsDiskBufferMb = 10,                // 10 MB
    metricsDiskBufferTtlHours = 24,          // 24 hours
    metricsCaptureIntervalSeconds = 60,      // Capture every 60s

    // Export settings (unchanged)
    exportMode = ExportMode.CONDITIONAL,
    metricExportIntervalSeconds = 60,        // Export interval (if CONTINUOUS)

    // Device metrics
    captureDeviceMetrics = true,
    deviceMetricsConfig = DeviceMetricsConfig(
        captureMemory = true,
        captureBattery = true,
        captureCpu = true,
        captureNetwork = true,
        captureReasons = setOf(
            CaptureReason.SCHEDULED_FLUSH,
            CaptureReason.LOW_MEMORY,
            CaptureReason.ERROR,
            CaptureReason.CRASH,
            CaptureReason.MANUAL_FLUSH
        )
    )
)
```

## Implementation Phases

### Phase 1: Core Ring Buffer (2-3 days)
- [ ] Implement `MetricsRingBuffer` class
- [ ] Implement `MetricSnapshot` data structure
- [ ] Implement RAM buffer (ConcurrentLinkedQueue)
- [ ] Implement overflow logic

### Phase 2: Disk Persistence (2-3 days)
- [ ] Implement `DiskMetricBuffer` with Room
- [ ] JSON serialization for `MetricSnapshot`
- [ ] TTL cleanup task
- [ ] Size-based eviction

### Phase 3: Integration (2-3 days)
- [ ] Integrate with `DeviceMetricsCollector`
- [ ] Integrate with `MobileLoggerProvider.forceFlush()`
- [ ] Integrate with `PolicyEvaluator` for window flush
- [ ] Update `PeriodicMetricReader` to use ring buffer

### Phase 4: Testing (2-3 days)
- [ ] Unit tests for `MetricsRingBuffer`
- [ ] Unit tests for `DiskMetricBuffer`
- [ ] Integration tests with flush triggers
- [ ] Crash recovery tests

### Phase 5: Demo Integration (1 day)
- [ ] Update demo app scenarios
- [ ] Add metrics visualization
- [ ] Document new features

**Total Estimate**: 9-13 days

## Migration Strategy

### Backward Compatibility

```kotlin
// Old API (still works)
provider.forceFlush()  // Exports current metrics + all logs/spans

// New API (enhanced)
provider.forceFlush(windowMinutes = 2)  // Exports last 2 minutes

// Configuration flag
MobileConfig(
    useMetricsRingBuffer = true  // Enable new behavior
)
```

### Gradual Rollout
1. Implement ring buffer as optional feature (flag: `useMetricsRingBuffer`)
2. Test in demo app and internal apps
3. Enable by default in next major version
4. Deprecate old accumulator-only mode

## Related Documentation
- [02-ring-buffer-architecture.md](02-ring-buffer-architecture.md) - Log ring buffer implementation
- [03-flush-behavior.md](03-flush-behavior.md) - Flush behavior and triggers
- [04-low-memory-handling.md](04-low-memory-handling.md) - Low memory scenarios
