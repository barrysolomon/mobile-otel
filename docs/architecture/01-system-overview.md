# Mobile OTel System Architecture Overview

This document provides a high-level overview of the mobile OpenTelemetry system architecture, focusing on the data flow from capture through buffering to transmission.

## System Overview

```mermaid
graph TB
    subgraph "Mobile App"
        A[App Events] --> B[OpenTelemetry SDK]
        B --> C[Signal Generators]

        subgraph "Signal Types"
            C --> D[Logs]
            C --> E[Spans]
            C --> F[Metrics]
        end

        D --> G[MobileLogRecordProcessor]
        E --> H[BatchSpanProcessor]
        F --> I[PeriodicMetricReader]

        G --> J[Ring Buffer System]
        H --> K[Span Queue]
        I --> L[Metric Accumulator]

        J --> M[Flush Controller]
        K --> M
        L --> M

        M --> N[OTLP Exporter]
        N --> O[Retry Logic]
    end

    subgraph "External"
        O --> P[OTEL Collector]
        P --> Q[Backend]
    end

    subgraph "Triggers"
        R[Low Memory] -.-> M
        S[User Action] -.-> M
        T[Policy Match] -.-> M
        U[Periodic] -.-> M
    end

    style A fill:#e1f5ff
    style M fill:#ffe1e1
    style P fill:#e1ffe1
```

## Key Components

### 1. Signal Capture
- **Logs**: Captured via `MobileLogRecordProcessor` with two-tier ring buffer
- **Spans**: Captured via `BatchSpanProcessor` with queue-based buffering
- **Metrics**: Captured via `PeriodicMetricReader` with accumulation

### 2. Buffering Strategy
- **Logs**: RAM Buffer (5,000 events) → Disk Buffer (50 MB, 24h TTL)
- **Spans**: In-memory queue (10,000 spans in CONDITIONAL mode)
- **Metrics**: Accumulation until export or flush

### 3. Export Modes
- **CONDITIONAL**: Export only on triggers (errors, low memory, manual flush)
- **CONTINUOUS**: Periodic export (spans: 30s, metrics: 60s)
- **HYBRID**: Balanced (2x intervals + trigger-based)

### 4. Flush Triggers
- **Low Memory Detection**: Immediate capture and flush
- **Policy Match**: Workflow-triggered flush (UI freeze, errors, etc.)
- **Manual**: User-initiated via `forceFlush()`
- **Periodic**: Scheduled exports (CONTINUOUS/HYBRID modes)

## Default Batch Sizes

### On Flush (Default Limits)

| Signal Type | Default Batch Size | Source |
|-------------|-------------------|--------|
| **Logs** | Up to 5,000 events (RAM) + disk buffer (50 MB) | MobileLogRecordProcessor |
| **Spans** | Up to 10,000 spans (CONDITIONAL mode) or 2,048 (SDK default) | BatchSpanProcessor |
| **Metrics** | All accumulated metrics since last export | PeriodicMetricReader |

### Export Batching
- Logs exported in **batches of 100 events** at a time
- Spans exported per SDK configuration
- Metrics exported as complete snapshot

## Data Flow Sequence

```mermaid
sequenceDiagram
    participant App
    participant SDK
    participant Processor
    participant Buffer
    participant Exporter
    participant Collector

    App->>SDK: Generate telemetry
    SDK->>Processor: Process event
    Processor->>Buffer: Store in RAM buffer

    alt RAM Buffer Full
        Buffer->>Buffer: Move oldest to disk
    end

    alt Trigger Event (Low Memory, Error, etc.)
        Processor->>Buffer: Flush requested
        Buffer->>Exporter: Export batches (100 events)
        Exporter->>Collector: OTLP/gRPC

        alt Export Fails
            Exporter->>Exporter: Retry (3 attempts, exponential backoff)
        end
    end
```

## Configuration Hierarchy

```mermaid
graph LR
    A[Runtime Config<br/>SharedPreferences] --> D[Active Config]
    B[Bundled Config<br/>assets/otel-config.json] --> D
    C[Default Values<br/>MobileConfig] --> D

    D --> E[MobileLoggerProvider]

    style A fill:#90EE90
    style B fill:#FFD700
    style C fill:#D3D3D3
```

**Priority**: Runtime > Bundled > Defaults

## Next Steps

For detailed views of specific subsystems, see:
- [02-ring-buffer-architecture.md](02-ring-buffer-architecture.md) - Ring buffer implementation
- [03-flush-behavior.md](03-flush-behavior.md) - Flush triggers and behavior
- [04-low-memory-handling.md](04-low-memory-handling.md) - Low memory detection and recovery
- [05-metrics-capture.md](05-metrics-capture.md) - Metrics capture and ring buffer proposal
