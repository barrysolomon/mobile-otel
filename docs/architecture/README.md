# Architecture Documentation

This directory contains comprehensive architecture diagrams and documentation for the Mobile OpenTelemetry system.

## Overview

The Mobile OTel system provides OpenTelemetry-native observability for Android applications with a focus on:
- **Crash resilience**: Two-tier ring buffers survive app crashes
- **Offline operation**: Disk-backed storage for network outages
- **Low memory handling**: Intelligent detection and recovery
- **Flexible export**: CONDITIONAL, CONTINUOUS, and HYBRID modes
- **Metrics capture**: Proposed ring buffer enhancement

## Documentation Structure

### 1. [System Overview](01-system-overview.md)
High-level architecture showing the complete data flow from event generation through buffering to transmission.

**Topics Covered**:
- Signal types (logs, spans, metrics)
- Buffering strategy
- Export modes (CONDITIONAL, CONTINUOUS, HYBRID)
- Flush triggers
- Default batch sizes
- Configuration hierarchy

**Start here** if you're new to the system.

### 2. [Ring Buffer Architecture](02-ring-buffer-architecture.md)
Detailed design of the two-tier ring buffer system for log events.

**Topics Covered**:
- RAM buffer (Tier 1): 5,000 events, volatile
- Disk buffer (Tier 2): 50 MB, persistent
- Overflow management
- TTL cleanup
- Serialization (JSON)
- Crash recovery
- Performance characteristics

**Read this** to understand buffering and persistence.

### 3. [Flush Behavior](03-flush-behavior.md)
Comprehensive guide to flush operations and triggers.

**Topics Covered**:
- Master flush implementation (`forceFlush()`)
- Full flush vs window flush
- Flush triggers (5 types)
- Low memory flush
- Policy-based flush
- Manual flush
- Periodic export
- App recovery flush
- Retry logic with exponential backoff

**Read this** to understand when and how data is exported.

### 4. [Low Memory Handling](04-low-memory-handling.md)
Android low memory detection, response, and recovery.

**Topics Covered**:
- ComponentCallbacks2 integration
- Memory trim levels (TRIM_MEMORY_COMPLETE, etc.)
- Recovery detection on app restart
- Memory metrics capture (5 metrics)
- Flush behavior on low memory
- Memory pressure response strategy
- Prevention and mitigation
- Testing low memory scenarios

**Read this** to understand memory pressure handling.

### 5. [Metrics Capture and Ring Buffer Proposal](05-metrics-capture.md)
Current metrics system and proposed enhancement with ring buffer.

**Topics Covered**:
- Current PeriodicMetricReader architecture
- Current limitations (no ring buffer, memory growth, loss on crash)
- Proposed two-tier metrics ring buffer
- Capture strategy (every X seconds)
- Comparison: current vs proposed
- Benefits (crash resilience, historical context, selective export)
- Implementation phases (5 phases, 9-13 days)
- Configuration and migration strategy

**Read this** to understand the metrics capture roadmap.

## Quick Reference

### Default Configuration

| Setting | Default Value | Description |
|---------|---------------|-------------|
| **RAM Buffer Size** | 5,000 events | Log events in memory |
| **Disk Buffer Size** | 50 MB | Persistent log storage |
| **Disk Buffer TTL** | 24 hours | Event expiration |
| **Span Queue Size** | 10,000 (CONDITIONAL) | Span buffering |
| **Export Mode** | CONDITIONAL | Battery-friendly mode |
| **Export Timeout** | 30 seconds | Network timeout |
| **Max Retries** | 3 | Exponential backoff |

### Flush Triggers

1. **Low Memory**: Android system signals memory pressure
2. **Policy Match**: Workflow conditions met (UI freeze, error, etc.)
3. **Manual**: User clicks Force Flush button
4. **Periodic**: CONTINUOUS/HYBRID mode timers
5. **Recovery**: App restart after crash/force quit

### Signal Types

| Signal | Buffer Type | Default Size | Export Batch |
|--------|-------------|--------------|--------------|
| **Logs** | Two-tier ring buffer | 5,000 (RAM) + 50 MB (disk) | 100 events |
| **Spans** | In-memory queue | 10,000 (CONDITIONAL) | SDK default |
| **Metrics** | Accumulator | Unbounded | All accumulated |

### Export Modes

| Mode | Traces | Metrics | Battery Impact | Use Case |
|------|--------|---------|----------------|----------|
| **CONDITIONAL** | Trigger only | Trigger only | <0.5% | Production |
| **CONTINUOUS** | Every 30s | Every 60s | 3-5% | Development |
| **HYBRID** | Every 60s | Every 120s | 1-2% | Balanced |

## Architecture Diagrams Legend

### Colors
- 🟢 Green: Export/output stages
- 🟡 Yellow: Buffering/storage stages
- 🔴 Red: Critical/trigger stages
- 🔵 Blue: Processing stages

### Shapes
- **Rectangle**: Process or component
- **Diamond**: Decision point
- **Cylinder**: Data storage
- **Parallelogram**: Input/output
- **Circle**: Start/end state

## File Locations

All architecture documents reference actual code locations:

```
otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/
├── MobileLoggerProvider.kt              # Main initialization & flush
├── config/MobileConfig.kt               # Configuration
├── buffering/
│   ├── MobileLogRecordProcessor.kt      # Ring buffer & flush logic
│   ├── DiskLogBuffer.kt                 # Persistent storage
│   └── RetryableExporter.kt             # Retry logic
├── metrics/
│   ├── DeviceMetricsCollector.kt        # Metrics capture
│   └── DeviceMetricsConfig.kt           # Metrics configuration
└── autocapture/
    └── RecoveryTracker.kt               # Low memory & crash detection
```

## Navigation

### By Role

**For Developers**:
1. Start with [System Overview](01-system-overview.md)
2. Read [Ring Buffer Architecture](02-ring-buffer-architecture.md)
3. Understand [Flush Behavior](03-flush-behavior.md)

**For SRE/Operations**:
1. Read [Low Memory Handling](04-low-memory-handling.md)
2. Understand [Flush Behavior](03-flush-behavior.md)
3. Review [System Overview](01-system-overview.md)

**For Product/Planning**:
1. Review [Metrics Capture Proposal](05-metrics-capture.md)
2. Read [System Overview](01-system-overview.md)
3. Understand benefits and trade-offs

### By Topic

**Crash Recovery**:
- [Ring Buffer Architecture](02-ring-buffer-architecture.md#crash-recovery)
- [Flush Behavior](03-flush-behavior.md#5-app-recovery-crashforce-quit)

**Low Memory**:
- [Low Memory Handling](04-low-memory-handling.md)
- [Flush Behavior](03-flush-behavior.md#1-low-memory-detection)

**Performance**:
- [Ring Buffer Architecture](02-ring-buffer-architecture.md#performance-characteristics)
- [Metrics Capture](05-metrics-capture.md#performance-characteristics)

**Configuration**:
- [System Overview](01-system-overview.md#configuration-hierarchy)
- All documents have Configuration sections

## Related Documentation

- [../ARCHITECTURE.md](../ARCHITECTURE.md) - High-level system design
- [../guides/OFFLINE_RESILIENCE.md](../guides/OFFLINE_RESILIENCE.md) - Crash and network loss handling
- [../EXPORT_MODES.md](../EXPORT_MODES.md) - Export mode details
- [../DEVICE_METRICS.md](../DEVICE_METRICS.md) - Device metrics system
- [../.claude/ai_notes.md](../../.claude/ai_notes.md) - Complete project context

## Contributing

When updating architecture diagrams:
1. Use Mermaid diagram syntax (renders in GitHub/GitLab)
2. Keep diagrams focused (one concept per diagram)
3. Use consistent colors and shapes (see legend above)
4. Reference actual code locations with links
5. Update this README when adding new documents

## Questions?

For architecture questions or clarifications:
1. Check the relevant document first
2. Review code references (all files have line number links)
3. See [../README.md](../README.md) for contact information
4. Open an issue with the `documentation` label

---

**Last Updated**: 2026-01-26
**Maintained By**: Mobile OTel Team
