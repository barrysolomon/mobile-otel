# Ring Buffer Architecture

This document details the two-tier ring buffer system used for log event buffering, providing crash-resilient storage and efficient memory management.

## Two-Tier Ring Buffer System

```mermaid
graph TB
    subgraph "Event Generation"
        A[App Events] --> B[LogRecordData]
    end

    subgraph "RAM Buffer - Tier 1"
        B --> C[ConcurrentLinkedQueue]
        C --> D{Size > 5000?}
        D -->|Yes| E[Poll Oldest Events]
        D -->|No| F[Stay in RAM]
    end

    subgraph "Disk Buffer - Tier 2"
        E --> G[Room Database]
        G --> H[SQLite Storage]
        H --> I{Size > 50MB?}
        I -->|Yes| J[Evict Oldest]
        I -->|No| K[Persist]

        L[TTL Cleanup<br/>Every 1 hour] -.-> H
        M{Age > 24h?} -.-> J
    end

    subgraph "Export"
        N[Flush Trigger] --> O[Load from Disk]
        F --> O
        O --> P[Batch 100 events]
        P --> Q[OTLP Exporter]
    end

    style C fill:#90EE90
    style G fill:#FFD700
    style Q fill:#87CEEB
```

## Buffer Specifications

### RAM Buffer (Tier 1)
- **Type**: `ConcurrentLinkedQueue<LogRecordData>`
- **Default Capacity**: 5,000 events
- **Storage**: Volatile (lost on crash)
- **Performance**: Very fast (in-memory)
- **Thread Safety**: Concurrent access supported
- **Overflow Strategy**: Move oldest to Tier 2

### Disk Buffer (Tier 2)
- **Type**: Room database (SQLite)
- **Default Capacity**: 50 MB
- **Storage**: Persistent (survives crashes)
- **Performance**: Slower than RAM (disk I/O)
- **Thread Safety**: Room handles synchronization
- **Overflow Strategy**: Evict oldest events (FIFO)
- **TTL**: 24 hours (configurable)

## Buffer State Machine

```mermaid
stateDiagram-v2
    [*] --> RAMBuffer: Event arrives

    RAMBuffer --> RAMBuffer: Size < 5000
    RAMBuffer --> DiskBuffer: Size >= 5000

    DiskBuffer --> DiskBuffer: Size < 50MB
    DiskBuffer --> Eviction: Size >= 50MB

    Eviction --> DiskBuffer: Oldest removed

    RAMBuffer --> Export: Flush trigger
    DiskBuffer --> Export: Flush trigger

    Export --> [*]: Sent to collector
```

## Overflow Management

### RAM Buffer Overflow

```mermaid
sequenceDiagram
    participant App
    participant RAM as RAM Buffer
    participant Checker as Overflow Checker
    participant Disk as Disk Buffer

    App->>RAM: Add event (5001st)
    RAM->>RAM: Enqueue event

    Note over Checker: Runs every 5 seconds

    Checker->>RAM: Check size
    RAM-->>Checker: Size = 5001
    Checker->>RAM: Poll 1 event
    RAM-->>Checker: Oldest event
    Checker->>Disk: Persist event
    Disk-->>Checker: Success
    Checker->>RAM: Decrement count
```

### Disk Buffer Overflow

```mermaid
sequenceDiagram
    participant Checker as Disk Overflow
    participant Disk as Disk Buffer
    participant DB as Room Database

    Note over Checker: Runs every 5 seconds

    Checker->>Disk: Check size
    Disk->>DB: Query total bytes
    DB-->>Disk: 52 MB

    alt Size > 50 MB
        Disk->>DB: Query oldest events
        DB-->>Disk: List of events
        Disk->>DB: Delete oldest
        DB-->>Disk: Deleted
    end
```

## TTL Cleanup

```mermaid
sequenceDiagram
    participant Timer as Cleanup Timer
    participant Disk as Disk Buffer
    participant DB as Room Database

    Note over Timer: Runs every 1 hour

    Timer->>Disk: Start cleanup
    Disk->>DB: Query events
    DB-->>Disk: All events
    loop For each event
        Disk->>Disk: Check age
        alt Age > 24h
            Disk->>DB: Delete event
        end
    end
    Disk-->>Timer: Cleanup complete
```

## Serialization

### LogRecordData to JSON

```mermaid
graph LR
    A[LogRecordData] --> B[JSON Serializer]

    B --> C{Attribute Type}
    C -->|String| D[Direct value]
    C -->|Long| E[Numeric value]
    C -->|Double| F[Numeric value]
    C -->|Boolean| G[Boolean value]
    C -->|Array| H[JSON Array]

    D --> I[JSON String]
    E --> I
    F --> I
    G --> I
    H --> I

    I --> J[Room Entity]
    J --> K[SQLite BLOB]

    style A fill:#90EE90
    style K fill:#FFD700
```

### Fields Preserved
- Timestamp (nanoseconds)
- Severity
- Body (log message)
- Attributes (all types)
- Resource attributes
- Instrumentation scope
- Trace context (trace ID, span ID)

## Configuration

### Default Configuration
```kotlin
MobileConfig(
    ramBufferSize = 5000,           // RAM capacity
    diskBufferMb = 50,              // Disk capacity
    diskBufferTtlHours = 24,        // Event expiration
    // ... other settings
)
```

### Tuning Guidelines

| Scenario | RAM Size | Disk Size | TTL |
|----------|----------|-----------|-----|
| **Low traffic app** | 1,000 | 10 MB | 24h |
| **Standard app** | 5,000 | 50 MB | 24h |
| **High traffic app** | 10,000 | 100 MB | 12h |
| **Debug/testing** | 10,000 | 200 MB | 72h |

## Memory Usage Estimates

### RAM Buffer
- **Per event**: ~500 bytes (average)
- **5,000 events**: ~2.5 MB
- **10,000 events**: ~5 MB

### Disk Buffer
- **Per event**: ~600 bytes (with JSON overhead)
- **50 MB**: ~85,000 events
- **100 MB**: ~170,000 events

## Crash Recovery

```mermaid
sequenceDiagram
    participant App1 as App (Before Crash)
    participant RAM1 as RAM Buffer
    participant Disk1 as Disk Buffer

    App1->>RAM1: Events 1-100
    RAM1->>Disk1: Events 1-50 (overflow)

    Note over App1: App crashes

    participant App2 as App (After Restart)
    participant RAM2 as RAM Buffer (Empty)
    participant Disk2 as Disk Buffer (Intact)

    App2->>App2: Detect crash
    App2->>Disk2: Query events
    Disk2-->>App2: Events 1-50
    App2->>App2: Log recovery event
    App2->>App2: Flush last 5 minutes
```

**Note**: RAM buffer events (51-100) are lost, but disk buffer (1-50) survives.

## Performance Characteristics

| Operation | RAM Buffer | Disk Buffer |
|-----------|-----------|-------------|
| **Write** | <1ms | 5-10ms |
| **Read** | <1ms | 10-20ms |
| **Overflow check** | <1ms | 100-200ms |
| **TTL cleanup** | N/A | 500ms-2s |
| **Flush (100 events)** | <5ms | 50-100ms |

## Thread Safety

```mermaid
graph TB
    subgraph "Concurrent Operations"
        A[Main Thread<br/>Add events] --> B[RAM Buffer]
        C[Background Thread<br/>Overflow check] --> B
        D[Flush Thread<br/>Export] --> B

        B --> E[ConcurrentLinkedQueue<br/>Thread-safe]

        F[Background Thread<br/>Disk operations] --> G[Room Database]
        D --> G

        G --> H[SQLite<br/>Transaction isolation]
    end

    style E fill:#90EE90
    style H fill:#FFD700
```

## Related Documentation
- [03-flush-behavior.md](03-flush-behavior.md) - How flush triggers work with ring buffer
- [04-low-memory-handling.md](04-low-memory-handling.md) - Buffer behavior under memory pressure
