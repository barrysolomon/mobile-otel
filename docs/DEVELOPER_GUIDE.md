# Developer Guide

Complete guide for developers extending and customizing the mobile observability system.

## Table of Contents

1. [Development Setup](#development-setup)
2. [Architecture Overview](#architecture-overview)
3. [Component Development](#component-development)
4. [Adding Custom Node Types](#adding-custom-node-types)
5. [Extending the Gateway](#extending-the-gateway)
6. [Android SDK Integration](#android-sdk-integration)
7. [Testing](#testing)
8. [Deployment](#deployment)
9. [Contributing](#contributing)

## Development Setup

### Prerequisites

- **Go 1.21+**: Gateway development
- **Node.js 18+**: Control Plane UI
- **Android Studio**: Android app development
- **kubectl**: Kubernetes deployment
- **Docker**: Containerization (optional)
- **Git**: Version control

### Clone and Setup

```bash
# Clone repository
git clone https://github.com/your-org/mobile-otel.git
cd mobile-otel

# Setup Gateway
cd gateway && go mod download && go build ./... && cd ..

# Setup Control Plane UI
cd control-plane-ui && npm install && cd ..

# Build Android SDK (via demo app)
cd examples/demo-app && ./gradlew :otel-android-mobile:build && cd ../..
```

### Development Environment

```bash
# Terminal 1: Kubernetes cluster
kubectl apply -f k8s/
kubectl port-forward -n mobile-observability svc/otel-gateway 8080:8080

# Terminal 2: Control Plane UI
cd control-plane-ui
npm run dev

# Terminal 3: Watch logs
kubectl logs -n mobile-observability -l app=otel-gateway -f

# Terminal 4: Android development
cd examples/demo-app
./gradlew installDebug
adb logcat | grep "OTelMobile\|MobileOtel"
```

## Architecture Overview

### System Components

```
┌─────────────────────────────────────────────────────────────┐
│                     System Architecture                     │
└─────────────────────────────────────────────────────────────┘

┌──────────────┐      ┌──────────────┐      ┌──────────────┐
│   Android    │─────►│   Gateway    │─────►│    OTEL      │
│     App      │ HTTP │   (Go API)   │ gRPC │  Collector   │
│   (Kotlin)   │      │              │      │              │
└──────────────┘      └──────────────┘      └──────────────┘
       │                     │                      │
       │                     │                      │
       ▼                     ▼                      ▼
┌──────────────┐      ┌──────────────┐      ┌──────────────┐
│ Ring Buffer  │      │   SQLite     │      │   Backends   │
│ (RAM + Disk) │      │  (Versions)  │      │ (Loki, etc)  │
└──────────────┘      └──────────────┘      └──────────────┘
       ▲
       │
       │ Poll config
       │
┌──────────────┐
│  Control     │
│  Plane UI    │
│  (React)     │
└──────────────┘
```

### Data Flow

1. **Event Capture**: Android app captures events to ring buffer
2. **Workflow Evaluation**: DSL evaluator checks triggers
3. **Selective Flush**: Window-based data export on match
4. **Gateway Processing**: Convert JSON to OTEL Logs
5. **OTEL Export**: Send to collector via OTLP/gRPC
6. **Backend Storage**: Loki, Prometheus, etc.

### Key Technologies

| Component | Tech Stack |
|-----------|------------|
| Android | Kotlin, Room, OkHttp, Coroutines |
| Gateway | Go, OTEL SDK, SQLite, gRPC |
| UI | React, TypeScript, React Flow, Vite |
| Infra | Kubernetes, OTEL Collector |

## Component Development

### Gateway (Go)

#### Project Structure

```
gateway/
├── main.go                 # HTTP server, routing
├── go.mod, go.sum         # Dependencies
├── internal/
│   ├── otel/
│   │   └── exporter.go    # OTEL log export
│   ├── db/
│   │   └── db.go          # SQLite operations
│   ├── config/
│   │   └── manager.go     # Version management
│   └── handlers/
│       └── handlers.go    # HTTP handlers
├── Dockerfile
└── README.md
```

#### Adding New Endpoint

**1. Define handler in `internal/handlers/handlers.go`:**

```go
func (h *Handler) HandleNewEndpoint(w http.ResponseWriter, r *http.Request) {
    // Parse request
    var req NewRequest
    if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
        http.Error(w, err.Error(), http.StatusBadRequest)
        return
    }

    // Process request
    result, err := h.processNewEndpoint(req)
    if err != nil {
        http.Error(w, err.Error(), http.StatusInternalServerError)
        return
    }

    // Return response
    w.Header().Set("Content-Type", "application/json")
    json.NewEncoder(w).Encode(result)
}
```

**2. Register route in `main.go`:**

```go
func main() {
    // ... existing code ...

    http.HandleFunc("/new-endpoint", h.HandleNewEndpoint)

    // ... existing code ...
}
```

**3. Add tests:**

```go
func TestHandleNewEndpoint(t *testing.T) {
    // Setup
    db := setupTestDB(t)
    defer db.Close()

    handler := NewHandler(db, nil, nil)

    // Test
    req := httptest.NewRequest("POST", "/new-endpoint", strings.NewReader(`{"key":"value"}`))
    w := httptest.NewRecorder()

    handler.HandleNewEndpoint(w, req)

    // Assert
    assert.Equal(t, 200, w.Code)
}
```

#### Adding New OTEL Attribute

**Modify `internal/otel/exporter.go`:**

```go
func (e *LogExporter) exportEvent(ctx context.Context, event MobileEvent) error {
    // ... existing code ...

    // Add new attribute
    record.AddAttributes(
        log.String("custom_attribute", event.CustomField),
    )

    // ... existing code ...
}
```

#### Database Migrations

**For schema changes, update `internal/db/db.go`:**

```go
func (d *Database) createTables() error {
    // ... existing tables ...

    // Add new table
    _, err = d.db.Exec(`
        CREATE TABLE IF NOT EXISTS new_table (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            field TEXT NOT NULL,
            created_at INTEGER NOT NULL
        )
    `)
    if err != nil {
        return err
    }

    return nil
}
```

### Control Plane UI (React)

#### Project Structure

```
control-plane-ui/
├── src/
│   ├── components/
│   │   ├── WorkflowBuilder.tsx
│   │   ├── DeviceMonitor.tsx
│   │   └── nodes/
│   │       ├── EventMatchNode.tsx
│   │       ├── FlushWindowNode.tsx
│   │       └── LogicNode.tsx
│   ├── types/
│   │   └── workflow.ts
│   ├── utils/
│   │   └── graphToDSL.ts
│   ├── api/
│   │   └── gateway.ts
│   ├── App.tsx
│   ├── App.css
│   └── main.tsx
├── package.json
├── vite.config.ts
└── tsconfig.json
```

#### Component Guidelines

**Functional Components with Hooks:**

```typescript
import React, { useState, useEffect } from 'react';

interface MyComponentProps {
  prop1: string;
  prop2: number;
}

export const MyComponent: React.FC<MyComponentProps> = ({ prop1, prop2 }) => {
  const [state, setState] = useState<string>('');

  useEffect(() => {
    // Side effects
  }, [prop1, prop2]);

  return (
    <div className="my-component">
      {/* JSX */}
    </div>
  );
};
```

**Type Safety:**

```typescript
// Define types in src/types/
export interface WorkflowGraph {
  id: string;
  name: string;
  nodes: GraphNode[];
  edges: GraphEdge[];
}

// Use types everywhere
const workflow: WorkflowGraph = {
  id: 'my-workflow',
  name: 'My Workflow',
  nodes: [],
  edges: [],
};
```

### Android SDK (Kotlin)

#### Project Structure

```
otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/
├── MobileOtel.kt            # Core facade — wires all modules, public API
├── OTelMobile.kt            # Auto-capture entry point (delegates to MobileOtel)
├── MobileLoggerProvider.kt  # OTel LoggerProvider + processor
├── autocapture/             # Tap, scroll, freeze, ANR, lifecycle
├── buffering/               # Two-tier ring buffer (RAM + SQLite via Room)
├── config/                  # MobileConfig, NetworkConfig, AutoCaptureOptions
├── errors/                  # ErrorInstrumentation (uncaught, coroutine, RxJava)
├── export/                  # EnrichingLogRecordExporter, RetryableExporter
├── network/                 # OTelNetworkInterceptor (OkHttp)
├── policy/                  # PolicyEvaluator (DSL engine)
├── predictive/              # PredictiveExportPolicy, DeviceHealthMonitor
└── vitals/                  # VitalsCollector, JankDetector, AppStartInstrumentation
```

#### SDK Architecture

```kotlin
// Two entry points:

// 1. Full auto-instrumentation (errors + vitals + predictive + UI capture)
OTelMobile.start(applicationContext, MobileConfig(
    serviceName       = "my-app",
    serviceVersion    = "1.0.0",
    collectorEndpoint = "https://collector.example.com:4317"
))

// 2. Core only (buffering + policy evaluation, no UI auto-capture)
MobileOtel.initialize(applicationContext, MobileConfig(...))
```

## Adding Custom Node Types

### Step 1: Define Node Type

**Add to `control-plane-ui/src/types/workflow.ts`:**

```typescript
export type NodeType =
  | 'event_match'
  | 'http_error_match'
  | 'crash_marker'
  | 'any'
  | 'all'
  | 'flush_window'
  | 'annotate'
  | 'set_sampling'
  | 'custom_node';  // New type

export interface CustomNodeData {
  customField: string;
  customValue: number;
}
```

### Step 2: Create Node Component

**Create `control-plane-ui/src/components/nodes/CustomNode.tsx`:**

```typescript
import React, { memo } from 'react';
import { Handle, Position, NodeProps } from 'reactflow';

interface CustomNodeData {
  customField: string;
  customValue: number;
}

export const CustomNode = memo(({ data, selected }: NodeProps<CustomNodeData>) => {
  return (
    <div className={`node custom-node ${selected ? 'selected' : ''}`}>
      <Handle type="target" position={Position.Left} />

      <div className="node-header">
        <span className="node-icon">🔧</span>
        <span className="node-title">Custom Node</span>
      </div>

      <div className="node-body">
        <div className="node-field">
          <label>Field:</label>
          <span>{data.customField}</span>
        </div>
        <div className="node-field">
          <label>Value:</label>
          <span>{data.customValue}</span>
        </div>
      </div>

      <Handle type="source" position={Position.Right} />
    </div>
  );
});

CustomNode.displayName = 'CustomNode';
```

### Step 3: Register Node Type

**Update `control-plane-ui/src/components/WorkflowBuilder.tsx`:**

```typescript
import { CustomNode } from './nodes/CustomNode';

const nodeTypes = {
  event_match: EventMatchNode,
  flush_window: FlushWindowNode,
  logic: LogicNode,
  custom_node: CustomNode,  // Register new type
};

export const WorkflowBuilder = () => {
  return (
    <ReactFlow
      nodes={nodes}
      edges={edges}
      nodeTypes={nodeTypes}
      // ... other props
    />
  );
};
```

### Step 4: Add Styling

**Update `control-plane-ui/src/App.css`:**

```css
.custom-node {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border: 2px solid #5568d3;
  color: white;
}

.custom-node.selected {
  box-shadow: 0 0 0 2px #5568d3;
}
```

### Step 5: Update Compiler

**Update `control-plane-ui/src/utils/graphToDSL.ts`:**

```typescript
function buildActions(
  actionNodes: GraphNode[],
  allNodes: GraphNode[],
  edges: GraphEdge[]
): DSLAction[] {
  return actionNodes.map((node) => {
    switch (node.type) {
      case 'flush_window':
        return {
          flush_window: {
            minutes: node.data.windowMinutes,
            scope: node.data.scope,
          },
        };

      case 'custom_node':  // Handle new type
        return {
          custom_action: {
            field: node.data.customField,
            value: node.data.customValue,
          },
        };

      default:
        throw new Error(`Unknown action type: ${node.type}`);
    }
  });
}
```

### Step 6: Implement Backend Handler

**Gateway: Add to `internal/handlers/handlers.go`:**

```go
func (h *Handler) handleCustomAction(action CustomAction) error {
    // Implement custom action logic
    log.Printf("Custom action: field=%s, value=%d", action.Field, action.Value)
    return nil
}
```

**Android: Add to `workflow/WorkflowEvaluator.kt`:**

```kotlin
private fun executeAction(action: DSLAction) {
    when {
        action.flushWindow != null -> executeFlushWindow(action.flushWindow)
        action.customAction != null -> executeCustomAction(action.customAction)
        // ... other actions
    }
}

private fun executeCustomAction(action: CustomAction) {
    // Implement custom action logic
    Log.d(TAG, "Custom action: field=${action.field}, value=${action.value}")
}
```

## Extending the Gateway

### Adding Custom Processors

**Create `internal/processors/custom.go`:**

```go
package processors

type CustomProcessor struct {
    // Configuration
}

func NewCustomProcessor() *CustomProcessor {
    return &CustomProcessor{}
}

func (p *CustomProcessor) Process(events []Event) ([]Event, error) {
    processed := make([]Event, 0, len(events))

    for _, event := range events {
        // Process event
        processed = append(processed, event)
    }

    return processed, nil
}
```

**Integrate in `main.go`:**

```go
func main() {
    // ... existing code ...

    // Add custom processor
    customProcessor := processors.NewCustomProcessor()
    h := handlers.NewHandler(database, exporter, configMgr, customProcessor)

    // ... existing code ...
}
```

### Adding Custom Exporters

**Beyond OTEL, export to custom backends:**

```go
type CustomExporter struct {
    endpoint string
    client   *http.Client
}

func (e *CustomExporter) Export(events []Event) error {
    for _, event := range events {
        // Convert to custom format
        data := convertToCustomFormat(event)

        // Send to backend
        resp, err := e.client.Post(e.endpoint, "application/json", data)
        if err != nil {
            return err
        }
        defer resp.Body.Close()
    }
    return nil
}
```

### Adding Middleware

**Authentication middleware:**

```go
func authMiddleware(next http.HandlerFunc) http.HandlerFunc {
    return func(w http.ResponseWriter, r *http.Request) {
        // Check auth header
        apiKey := r.Header.Get("X-API-Key")
        if !isValidAPIKey(apiKey) {
            http.Error(w, "Unauthorized", http.StatusUnauthorized)
            return
        }

        next(w, r)
    }
}

// Apply to routes
http.HandleFunc("/admin/publish", authMiddleware(h.HandlePublish))
```

## Android SDK Integration

See [ANDROID_SDK_GUIDE.md](ANDROID_SDK_GUIDE.md) for the full integration guide. Quick reference:

**1. Include as local Gradle module:**

```kotlin
// settings.gradle.kts
include(":otel-android-mobile")
project(":otel-android-mobile").projectDir = file("path/to/otel-android-mobile")

// app/build.gradle.kts
dependencies { implementation(project(":otel-android-mobile")) }
```

**2. Initialize:**

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OTelMobile.start(this, MobileConfig(
            serviceName       = "my-app",
            serviceVersion    = BuildConfig.VERSION_NAME,
            collectorEndpoint = "https://collector.example.com:4317"
        ))
    }
}
```

**3. Custom events and error reporting:**

```kotlin
MobileOtel.sendEvent("checkout.completed", mapOf("total_cents" to 4299))
MobileOtel.reportError(exception, mapOf("context" to "checkout"))
MobileOtel.forceFlush(windowMinutes = 5)
```

**4. Network instrumentation (OkHttp):**

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(OTelNetworkInterceptor.create(
        context    = applicationContext,
        config     = NetworkConfig.production(),
        tracer     = OTelMobile.getTracer("network"),
        propagator = openTelemetry.propagators.textMapPropagator
    ))
    .build()
```

## Testing

### Unit Tests

#### Gateway (Go)

```go
// gateway/internal/handlers/handlers_test.go
func TestHandleIngest(t *testing.T) {
    // Setup
    db := setupTestDB(t)
    defer db.Close()

    exporter := &mockExporter{}
    handler := NewHandler(db, exporter, nil)

    // Test data
    payload := `{
        "events": [{
            "event_name": "test.event",
            "timestamp": 1234567890000,
            "session_id": "test",
            "device_id": "test",
            "app_id": "test",
            "config_version": 1
        }]
    }`

    // Execute
    req := httptest.NewRequest("POST", "/ingest", strings.NewReader(payload))
    w := httptest.NewRecorder()

    handler.HandleIngest(w, req)

    // Assert
    assert.Equal(t, 200, w.Code)
    assert.Equal(t, 1, exporter.exportedCount)
}
```

#### Control Plane UI (TypeScript)

```typescript
// control-plane-ui/src/utils/graphToDSL.test.ts
import { describe, it, expect } from 'vitest';
import { compileGraphToDSL, validateGraph } from './graphToDSL';

describe('graphToDSL', () => {
  it('should compile simple workflow', () => {
    const graph: WorkflowGraph = {
      id: 'test',
      name: 'Test',
      enabled: true,
      entryNodeId: 'node-1',
      nodes: [
        {
          id: 'node-1',
          type: 'event_match',
          data: { eventName: 'test.event', predicates: [] },
          position: { x: 0, y: 0 },
        },
      ],
      edges: [],
    };

    const dsl = compileGraphToDSL([graph], {
      diskMb: 50,
      ramEvents: 5000,
      retentionHours: 24,
    });

    expect(dsl.workflows).toHaveLength(1);
    expect(dsl.workflows[0].id).toBe('test');
  });
});
```

#### Android SDK (Kotlin + Robolectric)

Tests live in `otel-android-mobile/src/test/`. Run via the demo app project:

```bash
cd examples/demo-app
./gradlew :otel-android-mobile:test
./gradlew :otel-android-mobile:test --tests "*.PolicyEvaluatorTest"
```

Pattern for testing telemetry output — inject `MockLogRecordExporter` with synchronous processor:

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MyFeatureTest {
    private lateinit var mockExporter: MockLogRecordExporter
    private lateinit var logger: Logger

    @Before fun setup() {
        mockExporter = MockLogRecordExporter()
        val provider = SdkLoggerProvider.builder()
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(mockExporter))
            .build()
        logger = provider.get("test")
    }

    @Test fun `emits expected event`() {
        // exercise the class under test with the injected logger
        val events = mockExporter.findLogs { it.body.toString() == "app.crash" }
        assertEquals(1, events.size)
    }
}
```

See `RecoveryTrackerTest` and `PolicyEvaluatorTest` for full examples.

### Integration Tests

#### End-to-End Test Script

```bash
#!/bin/bash
# test-e2e.sh

set -e

echo "Starting E2E test..."

# 1. Deploy backend
kubectl apply -f k8s/
kubectl wait --for=condition=ready pod -n mobile-observability --all --timeout=60s

# 2. Port forward
kubectl port-forward -n mobile-observability svc/otel-gateway 8080:8080 &
PF_PID=$!
sleep 5

# 3. Send test event
DEMO_RUN_ID="e2e-test-$(date +%s)"
curl -X POST http://localhost:8080/ingest \
  -H "Content-Type: application/json" \
  -d "{
    \"events\": [{
      \"event_name\": \"e2e.test\",
      \"timestamp\": $(date +%s000),
      \"session_id\": \"e2e-session\",
      \"device_id\": \"e2e-device\",
      \"app_id\": \"demo-app\",
      \"config_version\": 1,
      \"attributes\": {\"demo_run_id\": \"$DEMO_RUN_ID\"}
    }]
  }"

# 4. Verify in collector logs
sleep 2
kubectl logs -n mobile-observability -l app=otel-collector --tail=100 | grep "$DEMO_RUN_ID"

# 5. Cleanup
kill $PF_PID

echo "E2E test passed!"
```

### Performance Tests

```bash
#!/bin/bash
# Load test gateway

echo "Running load test..."

# Send 1000 events
for i in {1..1000}; do
  curl -s -X POST http://localhost:8080/ingest \
    -H "Content-Type: application/json" \
    -d "{
      \"events\": [{
        \"event_name\": \"load.test\",
        \"timestamp\": $(date +%s000),
        \"session_id\": \"load-session\",
        \"device_id\": \"load-device\",
        \"app_id\": \"demo-app\",
        \"config_version\": 1,
        \"attributes\": {\"index\": $i}
      }]
    }" > /dev/null &
done

wait
echo "Sent 1000 events"

# Check success rate
sleep 5
SUCCESS_COUNT=$(kubectl logs -n mobile-observability -l app=otel-collector --tail=1000 | grep -c "load.test")
echo "Collector received: $SUCCESS_COUNT events"
```

## Deployment

### Docker Build

#### Gateway

```dockerfile
# gateway/Dockerfile
FROM golang:1.21-alpine AS builder

WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=1 go build -o gateway .

FROM alpine:latest
RUN apk add --no-cache sqlite-libs

COPY --from=builder /app/gateway /gateway
ENTRYPOINT ["/gateway"]
```

```bash
docker build -t mobile-observability-gateway:latest gateway/
docker push your-registry/mobile-observability-gateway:latest
```

#### Control Plane UI

```dockerfile
# control-plane-ui/Dockerfile
FROM node:18-alpine AS builder

WORKDIR /app
COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
```

```bash
docker build -t mobile-observability-ui:latest control-plane-ui/
docker push your-registry/mobile-observability-ui:latest
```

### Kubernetes Production Deployment

#### Update Image References

```yaml
# k8s/otel-gateway.yaml
spec:
  template:
    spec:
      containers:
      - name: gateway
        image: your-registry/mobile-observability-gateway:v1.0.0
```

#### Add Resource Limits

```yaml
resources:
  requests:
    memory: "256Mi"
    cpu: "250m"
  limits:
    memory: "512Mi"
    cpu: "500m"
```

#### Add Health Checks

```yaml
livenessProbe:
  httpGet:
    path: /health
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 30

readinessProbe:
  httpGet:
    path: /health
    port: 8080
  initialDelaySeconds: 5
  periodSeconds: 10
```

### CI/CD Pipeline

#### GitHub Actions Example

```yaml
# .github/workflows/deploy.yml
name: Deploy

on:
  push:
    branches: [main]

jobs:
  build-and-deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Build Gateway
        run: |
          cd gateway
          docker build -t ${{ secrets.REGISTRY }}/gateway:${{ github.sha }} .
          docker push ${{ secrets.REGISTRY }}/gateway:${{ github.sha }}

      - name: Build UI
        run: |
          cd control-plane-ui
          docker build -t ${{ secrets.REGISTRY }}/ui:${{ github.sha }} .
          docker push ${{ secrets.REGISTRY }}/ui:${{ github.sha }}

      - name: Deploy to Kubernetes
        run: |
          kubectl set image deployment/otel-gateway \
            gateway=${{ secrets.REGISTRY }}/gateway:${{ github.sha }} \
            -n mobile-observability
```

## Contributing

### Code Style

#### Go

- Follow [Effective Go](https://golang.org/doc/effective_go.html)
- Use `gofmt` for formatting
- Run `go vet` before committing

#### TypeScript

- Use ESLint configuration
- Follow React best practices
- Use TypeScript strict mode

#### Kotlin

- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use Android Studio formatter

### Pull Request Process

1. Fork the repository
2. Create feature branch: `git checkout -b feature/my-feature`
3. Make changes and add tests
4. Run tests: `npm test`, `go test ./...`, `./gradlew test`
5. Commit with clear message
6. Push and create pull request
7. Wait for review

### Commit Message Format

```
type(scope): subject

body (optional)

footer (optional)
```

**Types:** feat, fix, docs, style, refactor, test, chore

**Example:**
```
feat(gateway): add custom processor support

- Add processor interface
- Implement custom processor
- Add tests

Closes #123
```

## Next Steps

- **[API Reference](API_REFERENCE.md)** - Complete API documentation
- **[Operations Guide](OPERATIONS_GUIDE.md)** - Production deployment
- **[Troubleshooting](TROUBLESHOOTING_GUIDE.md)** - Common issues

---

Happy coding! 🚀
