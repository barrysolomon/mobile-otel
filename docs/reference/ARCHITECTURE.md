# System Architecture

Visual architecture and data flow for the mobile observability demo system.

## Component Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        MOBILE OBSERVABILITY DEMO                     │
└─────────────────────────────────────────────────────────────────────┘

┌──────────────────┐         ┌──────────────────┐         ┌──────────────────┐
│                  │         │                  │         │                  │
│  Control Plane   │◄────────┤   Go Gateway     │◄────────┤  Android App     │
│  UI (React)      │  HTTP   │   (REST API)     │  HTTP   │  (Kotlin)        │
│                  │         │                  │         │                  │
│  localhost:3000  │         │  ClusterIP:8080  │         │  Mobile Device   │
│                  │         │                  │         │                  │
└────────┬─────────┘         └────────┬─────────┘         └────────┬─────────┘
         │                            │                            │
         │ Workflow                   │ OTLP/gRPC                 │ Capture
         │ Management                 │ (Export Logs)              │ Events
         │                            │                            │
         │                   ┌────────▼─────────┐                 │
         │                   │                  │                 │
         └──────────────────►│  OTEL Collector  │◄────────────────┘
                  Poll        │   (Observability │   Workflow DSL
                  Config      │    Pipeline)     │   Evaluation
                             │                  │
                             │  ClusterIP:4317  │
                             │  ClusterIP:4318  │
                             │                  │
                             └────────┬─────────┘
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

### Gateway Components

```
┌───────────────────────────────────────────────────────────────────┐
│                          Go Gateway                               │
├───────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌──────────────────────┐         ┌──────────────────────┐      │
│  │   HTTP Server        │────────►│   OTEL Exporter      │      │
│  │   (net/http)         │         │   (otlploggrpc)      │      │
│  └──────────┬───────────┘         └──────────────────────┘      │
│             │                                                     │
│             │                                                     │
│  ┌──────────▼───────────┐         ┌──────────────────────┐      │
│  │   Config Manager     │────────►│   Database Layer     │      │
│  │   (Versioning)       │         │   (SQLite)           │      │
│  └──────────────────────┘         └──────────────────────┘      │
│                                                                   │
│  ┌───────────────────────────────────────────────────────┐      │
│  │                 Persistent Volume (PVC)               │      │
│  │                 /data/gateway.db                      │      │
│  └───────────────────────────────────────────────────────┘      │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘
```

**Key Features:**
- **REST API**: /ingest, /config, /admin/* endpoints
- **Version Management**: Atomic config activation, rollback
- **OTEL Export**: Real-time conversion to OTEL Logs
- **Persistent Storage**: SQLite with PVC for versions
- **Connection Pooling**: gRPC connection reuse

### Control Plane UI Components

```
┌───────────────────────────────────────────────────────────────────┐
│                    Control Plane UI (React)                       │
├───────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌──────────────────────┐         ┌──────────────────────┐      │
│  │   WorkflowBuilder    │────────►│   Graph Compiler     │      │
│  │   (React Flow)       │         │   (graphToDSL)       │      │
│  └──────────────────────┘         └──────────────────────┘      │
│                                                                   │
│  ┌──────────────────────┐         ┌──────────────────────┐      │
│  │   Node Components    │         │   Gateway API        │      │
│  │   (EventMatch,       │         │   (axios)            │      │
│  │    FlushWindow,      │         │                      │      │
│  │    Logic)            │         │                      │      │
│  └──────────────────────┘         └──────────────────────┘      │
│                                                                   │
│  ┌──────────────────────┐         ┌──────────────────────┐      │
│  │   DeviceMonitor      │         │   Version Manager    │      │
│  │   (Dashboard)        │         │   (Publish/Rollback) │      │
│  └──────────────────────┘         └──────────────────────┘      │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘
```

**Key Features:**
- **Visual Editor**: Drag-and-drop workflow builder
- **8 Node Types**: Triggers, logic, actions
- **Graph Validation**: Cycle detection, edge validation
- **Real-time Compilation**: Graph → DSL conversion
- **Version Control**: Publish, rollback, history
- **Device Monitoring**: Real-time device status (planned)

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

### Kubernetes Cluster

```
┌─────────────────────────────────────────────────────────────┐
│                    Namespace: otel-demo                     │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────────────────┐   ┌─────────────────────────┐ │
│  │  Service:               │   │  Service:               │ │
│  │  otel-collector         │   │  otel-gateway           │ │
│  │  ClusterIP              │   │  ClusterIP              │ │
│  │  - 4317/gRPC            │   │  - 8080/HTTP            │ │
│  │  - 4318/HTTP            │   │                         │ │
│  │  - 8888/metrics         │   │                         │ │
│  └──────────┬──────────────┘   └──────────┬──────────────┘ │
│             │                              │                │
│  ┌──────────▼──────────────┐   ┌──────────▼──────────────┐ │
│  │  Deployment:            │   │  Deployment:            │ │
│  │  otel-collector         │   │  otel-gateway           │ │
│  │  - 1 replica            │   │  - 1 replica            │ │
│  │  - ConfigMap mount      │   │  - PVC mount            │ │
│  └─────────────────────────┘   └──────────┬──────────────┘ │
│                                            │                │
│                                 ┌──────────▼──────────────┐ │
│                                 │  PVC:                   │ │
│                                 │  gateway-data           │ │
│                                 │  1Gi RWO                │ │
│                                 │  (SQLite DB)            │ │
│                                 └─────────────────────────┘ │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Local Development

```
┌────────────────────────────────────────────────────────────────┐
│                      Developer Machine                         │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  localhost:3000 ──► Control Plane UI (npm run dev)            │
│       │                                                        │
│       └──► Proxy /api/* to localhost:8080                     │
│                                                                │
│  localhost:8080 ──► kubectl port-forward                      │
│       │              (Gateway in k8s)                          │
│       │                                                        │
│       └──► POST /ingest, GET /config, POST /admin/*           │
│                                                                │
│  localhost:4317 ──► kubectl port-forward                      │
│                      (Collector in k8s, optional)             │
│                                                                │
└────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────┐
│                      Android Device/Emulator                   │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  http://10.0.2.2:8080 ──► Emulator → Host machine             │
│  http://<local-ip>:8080 ──► Physical device → Dev machine     │
│                                                                │
│       │                                                        │
│       └──► POST /ingest, GET /config                          │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

## Security Considerations

### Current Demo Implementation
- No authentication on gateway endpoints
- Open OTEL Collector receivers
- SQLite for simplicity (single writer)
- Port-forward for local access

### Production Recommendations

1. **Gateway Authentication**
   ```
   - API key per app_id
   - JWT tokens for admin endpoints
   - Rate limiting per device_id
   ```

2. **Network Policies**
   ```
   - Restrict collector ingress to gateway only
   - Restrict gateway admin endpoints to control plane only
   - Add NetworkPolicy resources
   ```

3. **Storage**
   ```
   - Migrate from SQLite to PostgreSQL
   - Enable encryption at rest
   - Regular backups
   ```

4. **Secrets Management**
   ```
   - Use Kubernetes Secrets for credentials
   - Rotate API keys periodically
   - Audit admin actions
   ```

## Scalability

### Horizontal Scaling

**Gateway:**
```yaml
spec:
  replicas: 3
```
- Requires shared database (PostgreSQL)
- Load balancer for traffic distribution

**OTEL Collector:**
```yaml
spec:
  replicas: 2
```
- Stateless, can scale freely
- Consider collector per workload type

### Vertical Scaling

**Increase resources for high volume:**
```yaml
resources:
  requests:
    memory: "1Gi"
    cpu: "500m"
  limits:
    memory: "2Gi"
    cpu: "1000m"
```

### Performance Characteristics

| Component | Events/sec | Latency (p95) | Memory |
|-----------|------------|---------------|--------|
| Gateway   | 10,000     | 50ms          | 256MB  |
| Collector | 50,000     | 10ms          | 512MB  |
| Android   | Local      | <1ms (buffer) | 100MB  |

## Monitoring

### Key Metrics to Track

**Gateway:**
- `/ingest` request rate and latency
- `/config` request rate
- Events per second exported to collector
- OTLP gRPC connection health
- Database query latency

**OTEL Collector:**
- Receiver metrics (accepted/refused spans)
- Processor queue sizes
- Exporter success/failure rates
- Memory usage

**Android App:**
- RAM buffer usage (current/max)
- Disk buffer size (MB)
- Flush frequency
- Config poll success rate
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

### Backup Strategy

1. **Gateway Database** (SQLite/PostgreSQL):
   - Daily snapshots of PVC
   - Backup active config version
   - Retain 30 days

2. **Control Plane Workflows**:
   - Version control (Git) for graph_json
   - Export workflows as JSON files
   - Store in S3/GCS

3. **Recovery Process**:
   ```bash
   # Restore gateway database
   kubectl cp backup.db <gateway-pod>:/data/gateway.db

   # Republish workflows from backup
   curl -X POST http://localhost:8080/admin/publish \
     -d @workflow-backup.json
   ```

## Development Roadmap

### Phase 1: MVP (Current)
- ✅ Visual workflow builder
- ✅ Basic node types (8 total)
- ✅ Graph validation and compilation
- ✅ Publish/rollback functionality
- ✅ Device monitoring (mock)
- ✅ End-to-end event flow

### Phase 2: Enhanced UX
- [ ] Drag-and-drop node palette
- [ ] Real-time device heartbeat polling
- [ ] Workflow simulation/testing
- [ ] Multi-user authentication
- [ ] Workflow templates library

### Phase 3: Production Ready
- [ ] PostgreSQL for gateway storage
- [ ] Kubernetes NetworkPolicies
- [ ] API authentication (JWT)
- [ ] Rate limiting
- [ ] Comprehensive monitoring dashboards
- [ ] Automated testing suite

### Phase 4: Advanced Features
- [ ] Real-time collaboration (multi-user editing)
- [ ] A/B testing workflows
- [ ] Advanced analytics (event frequency, trigger patterns)
- [ ] Custom node types (plugins)
- [ ] Workflow versioning/diffing UI
- [ ] Performance profiling tools
