# System Architecture

Visual architecture and data flow for the mobile observability demo system.

## Component Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        MOBILE OBSERVABILITY DEMO                     │
└─────────────────────────────────────────────────────────────────────┘

┌──────────────────┐                              ┌──────────────────┐
│                  │                              │                  │
│  Android App     │                              │  OTEL Collector  │
│  (Kotlin)        │         OTLP/gRPC            │  (Observability  │
│                  │─────────────────────────────►│   Pipeline)      │
│  Mobile Device   │                              │                  │
│                  │                              │  :4317, :4318    │
└──────────────────┘                              └────────┬─────────┘
                                      │
                                      │ Export to
                                      │ Backends
                                      │
                   ┌──────────────────┼──────────────────┐
                   │                  │                  │
                   ▼                  ▼                  ▼
            ┌──────────┐       ┌──────────┐      ┌──────────┐
            │ Debug    │       │ Loki     │      │ Prom     │
            │ Console  │       │ (Logs)   │      │ (Metrics)│
            └──────────┘       └──────────┘      └──────────┘
```

## Data Flow

### 1. Workflow Creation Flow

```
User                 Control Plane UI        Gateway              Database
  │                        │                    │                    │
  │ 1. Create workflow     │                    │                    │
  │───────────────────────►│                    │                    │
  │                        │                    │                    │
  │ 2. Add nodes/edges     │                    │                    │
  │───────────────────────►│                    │                    │
  │                        │                    │                    │
  │ 3. Validate            │                    │                    │
  │───────────────────────►│ (Client-side       │                    │
  │◄───────────────────────│  validation)       │                    │
  │ "Graph valid ✓"        │                    │                    │
  │                        │                    │                    │
  │ 4. Publish             │                    │                    │
  │───────────────────────►│                    │                    │
  │                        │ 5. POST /admin/    │                    │
  │                        │    publish         │                    │
  │                        │───────────────────►│                    │
  │                        │                    │ 6. INSERT version  │
  │                        │                    │───────────────────►│
  │                        │                    │                    │
  │                        │                    │ 7. Activate config │
  │                        │                    │───────────────────►│
  │                        │                    │◄───────────────────│
  │                        │ 8. Response        │                    │
  │◄───────────────────────│    {version: N}    │                    │
  │ "Published version N"  │◄───────────────────│                    │
  │                        │                    │                    │
```

### 2. Event Capture and Selective Flush Flow

```
Android App                Ring Buffer          Workflow Evaluator      Gateway
    │                          │                       │                    │
    │ 1. captureEvent()        │                       │                    │
    │─────────────────────────►│                       │                    │
    │                          │ 2. Add to RAM buffer  │                    │
    │                          │   (ConcurrentQueue)   │                    │
    │                          │                       │                    │
    │ 3. New event             │                       │                    │
    │──────────────────────────┼──────────────────────►│                    │
    │                          │                       │ 4. Evaluate        │
    │                          │                       │    triggers        │
    │                          │                       │                    │
    │                          │                       │ 5. Match found!    │
    │◄──────────────────────────────────────────────────│    (ui.freeze)    │
    │ TriggerResult            │                       │                    │
    │ [FlushWindow(2min)]      │                       │                    │
    │                          │                       │                    │
    │ 6. getEventsForFlush()   │                       │                    │
    │─────────────────────────►│                       │                    │
    │                          │ 7. Flush RAM to disk  │                    │
    │                          │    Query last 2min    │                    │
    │◄─────────────────────────│                       │                    │
    │ [67 events]              │                       │                    │
    │                          │                       │                    │
    │ 8. POST /ingest          │                       │                    │
    │──────────────────────────┼───────────────────────┼───────────────────►│
    │                          │                       │                    │
```

### 3. Event Export to OTEL Collector Flow

```
Gateway                   OTEL Exporter          Collector              Backends
   │                           │                      │                     │
   │ 1. Receive events         │                      │                     │
   │  from Android             │                      │                     │
   │                           │                      │                     │
   │ 2. For each event         │                      │                     │
   │──────────────────────────►│                      │                     │
   │                           │ 3. Convert to        │                     │
   │                           │    OTEL Log Record   │                     │
   │                           │                      │                     │
   │                           │ record.SetTimestamp()│                     │
   │                           │ record.SetBody()     │                     │
   │                           │ record.AddAttributes()│                    │
   │                           │   - session_id       │                     │
   │                           │   - device_id        │                     │
   │                           │   - demo_run_id      │                     │
   │                           │   - ...              │                     │
   │                           │                      │                     │
   │                           │ 4. Emit via OTLP/gRPC│                     │
   │                           │─────────────────────►│                     │
   │                           │                      │ 5. Process pipeline │
   │                           │                      │   (memory_limiter,  │
   │                           │                      │    batch)           │
   │                           │                      │                     │
   │                           │                      │ 6. Export to        │
   │                           │                      │    backends         │
   │                           │                      │────────────────────►│
   │                           │                      │                     │
   │                           │                      │                     │ Loki
   │                           │                      │────────────────────►│ Prometheus
   │                           │                      │                     │ Debug logs
   │                           │                      │                     │
```

### 4. Config Polling Flow

```
Android App             Gateway              Database
    │                      │                    │
    │ (Every 60s)          │                    │
    │                      │                    │
    │ GET /config?         │                    │
    │  app_id=demo-app     │                    │
    │  &device_id=xyz      │                    │
    │─────────────────────►│                    │
    │                      │ Query active       │
    │                      │ config version     │
    │                      │───────────────────►│
    │                      │◄───────────────────│
    │                      │ {version: N,       │
    │◄─────────────────────│  dsl_json: {...}}  │
    │                      │                    │
    │ Parse DSL            │                    │
    │ Update workflows     │                    │
    │                      │                    │
```

## Component Details

### Android App Components

```
┌───────────────────────────────────────────────────────────────────┐
│                        Android App                                │
├───────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌──────────────────────┐         ┌──────────────────────┐      │
│  │  ObservabilitySDK    │────────►│  RingBufferManager   │      │
│  │  (Main Orchestrator) │         │  (RAM + Disk Buffer) │      │
│  └──────────┬───────────┘         └──────────────────────┘      │
│             │                                                     │
│             │                                                     │
│  ┌──────────▼───────────┐         ┌──────────────────────┐      │
│  │  WorkflowEvaluator   │         │    GatewayClient     │      │
│  │  (DSL Execution)     │         │    (HTTP Client)     │      │
│  └──────────────────────┘         └──────────────────────┘      │
│                                                                   │
│  ┌───────────────────────────────────────────────────────┐      │
│  │              Room Database (SQLite)                   │      │
│  │  - events (EventEntity)                               │      │
│  │  - crash_markers (CrashMarkerEntity)                  │      │
│  │  - kv_store (metadata)                                │      │
│  └───────────────────────────────────────────────────────┘      │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘
```

**Key Features:**
- **RAM Buffer**: ConcurrentLinkedQueue (5000 events, lock-free)
- **Disk Buffer**: Room/SQLite (50MB, 24h retention, auto-eviction)
- **DSL Evaluation**: Real-time trigger matching
- **Selective Flush**: Window-based (minutes) with session/device scope
- **Config Polling**: Auto-update workflows every 60s
- **Correlation Tracking**: Auto-inject demo_run_id

> **Note:** The Go Gateway and Control Plane UI components have been moved to the
> [mobile-otel-control-plane](https://github.com/barrysolomon/mobile-otel-control-plane) repository.

### OTEL Collector Pipeline

```
┌───────────────────────────────────────────────────────────────────┐
│                    OTEL Collector Pipeline                        │
├───────────────────────────────────────────────────────────────────┤
│                                                                   │
│  Receivers                                                        │
│  ┌──────────────────────┐                                        │
│  │  OTLP/gRPC :4317     │────┐                                   │
│  └──────────────────────┘    │                                   │
│  ┌──────────────────────┐    │                                   │
│  │  OTLP/HTTP :4318     │────┤                                   │
│  └──────────────────────┘    │                                   │
│                               │                                   │
│                               ▼                                   │
│  Processors                                                       │
│  ┌──────────────────────┐                                        │
│  │  memory_limiter      │────┐                                   │
│  │  (512 MiB)           │    │                                   │
│  └──────────────────────┘    │                                   │
│  ┌──────────────────────┐    │                                   │
│  │  batch               │────┤                                   │
│  │  (10s timeout)       │    │                                   │
│  └──────────────────────┘    │                                   │
│                               │                                   │
│                               ▼                                   │
│  Exporters                                                        │
│  ┌──────────────────────┐                                        │
│  │  debug (console)     │◄───┤                                   │
│  └──────────────────────┘    │                                   │
│  ┌──────────────────────┐    │                                   │
│  │  logging (detailed)  │◄───┤                                   │
│  └──────────────────────┘    │                                   │
│  ┌──────────────────────┐    │                                   │
│  │  [Future: Loki, etc] │◄───┘                                   │
│  └──────────────────────┘                                        │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘
```

**Key Features:**
- **Multi-Protocol Receivers**: gRPC and HTTP
- **Memory Protection**: Memory limiter processor
- **Batching**: Efficient batch export
- **Flexible Exporters**: Console + future backends

## Workflow DSL Structure

### Graph Format (React Flow - Editing)

```json
{
  "id": "ui-freeze-handler",
  "name": "UI Freeze Handler",
  "enabled": true,
  "entryNodeId": "node-1",
  "nodes": [
    {
      "id": "node-1",
      "type": "event_match",
      "data": {
        "eventName": "ui.freeze",
        "predicates": [
          {"field": "duration_ms", "op": ">", "value": 2000}
        ]
      },
      "position": {"x": 100, "y": 100}
    },
    {
      "id": "node-2",
      "type": "flush_window",
      "data": {
        "windowMinutes": 2,
        "scope": "session"
      },
      "position": {"x": 300, "y": 100}
    }
  ],
  "edges": [
    {"id": "e1", "source": "node-1", "target": "node-2"}
  ]
}
```

### DSL Format (Device - Execution)

```json
{
  "version": 1,
  "limits": {
    "diskMb": 50,
    "ramEvents": 5000,
    "retentionHours": 24
  },
  "workflows": [
    {
      "id": "ui-freeze-handler",
      "enabled": true,
      "trigger": {
        "any": [
          {
            "event": "ui.freeze",
            "where": [
              {"field": "duration_ms", "op": ">", "value": 2000}
            ]
          }
        ]
      },
      "actions": [
        {
          "flush_window": {
            "minutes": 2,
            "scope": "session"
          }
        },
        {
          "annotate": {
            "trigger_id": "ui-freeze-handler",
            "reason": "UI freeze detected"
          }
        }
      ]
    }
  ]
}
```

## Network Topology

> **Note:** Kubernetes manifests and the Go Gateway have been moved to the
> [mobile-otel-control-plane](https://github.com/barrysolomon/mobile-otel-control-plane) repository.
> The Android SDK sends OTLP directly to any collector endpoint.

### Local Development

```
┌────────────────────────────────────────────────────────────────┐
│                      Developer Machine                         │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  localhost:4317 ──► OTEL Collector (local or port-forwarded)  │
│                                                                │
└────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────┐
│                      Android Device/Emulator                   │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  http://10.0.2.2:4317 ──► Emulator → Host machine             │
│  http://<local-ip>:4317 ──► Physical device → Dev machine     │
│                                                                │
│       │                                                        │
│       └──► OTLP/gRPC telemetry export                         │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

## Security Considerations

> **Note:** Gateway authentication and k8s deployment security are covered in the
> [sister repo](https://github.com/barrysolomon/mobile-otel-control-plane).

### SDK-Side Security
- PII scrubbing enabled by default (emails, phone numbers, credit cards)
- URL query parameter scrubbing
- Stack trace path sanitization
- Session data stored in EncryptedSharedPreferences

### OTEL Collector
- Open receivers (configure authentication as needed)
- Network policies recommended for production

## Performance Characteristics

| Component | Events/sec | Latency (p95) | Memory |
|-----------|------------|---------------|--------|
| Collector | 50,000     | 10ms          | 512MB  |
| Android   | Local      | <1ms (buffer) | 100MB  |

## Monitoring

### Key Metrics to Track

**OTEL Collector:**
- Receiver metrics (accepted/refused spans)
- Processor queue sizes
- Exporter success/failure rates
- Memory usage

**Android App:**
- RAM buffer usage (current/max)
- Disk buffer size (MB)
- Flush frequency
- Network errors

### Observability Stack Integration

```
┌──────────────────────────────────────────────────────────┐
│              Production Observability Stack              │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  OTEL Collector                                          │
│       │                                                  │
│       ├──► Loki (Logs)                                   │
│       │     ├─► Grafana (Visualization)                  │
│       │     └─► Alertmanager (Alerts)                    │
│       │                                                  │
│       ├──► Prometheus (Metrics)                          │
│       │     ├─► Grafana (Dashboards)                     │
│       │     └─► Alertmanager (Alerts)                    │
│       │                                                  │
│       └──► Jaeger/Tempo (Traces - if added)              │
│             └─► Grafana (Trace visualization)            │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

## Disaster Recovery

> **Note:** Gateway and Control Plane backup/recovery procedures are in the
> [sister repo](https://github.com/barrysolomon/mobile-otel-control-plane).

### SDK-Side Recovery

The Android SDK handles recovery automatically:
- **Crash markers** written to SharedPreferences before process death
- **ANR markers** written when main thread blocked > 5s
- **Low-memory markers** written on ComponentCallbacks2 signal
- On next launch, `RecoveryTracker` detects markers and triggers flush of pre-crash buffer

## Development Roadmap

See [BACKLOG.md](../../BACKLOG.md) for the full prioritized backlog.

For Control Plane UI and Gateway roadmap, see the
[sister repo](https://github.com/barrysolomon/mobile-otel-control-plane).
