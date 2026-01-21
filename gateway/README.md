# Mobile Observability Gateway

Go-based gateway that receives mobile events via HTTP/JSON and exports them as OpenTelemetry Logs to the OTEL Collector via OTLP/gRPC.

## Architecture

```
Android App → [JSON/HTTP] → Gateway → [OTLP/gRPC] → OTEL Collector
```

## Features

* Receives event batches from mobile devices
* Converts events to OTEL Log format
* Exports to OTEL Collector via OTLP/gRPC
* Manages workflow configurations (DSL)
* Tracks device heartbeats
* SQLite persistence for config versions and heartbeats

## API Endpoints

### Device Endpoints

* `POST /ingest` - Receive event batches
* `GET /config?app_id=X&device_id=Y` - Get active workflow config
* `POST /status` - Receive device heartbeat

### Admin Endpoints

* `POST /admin/publish` - Publish new workflow version
* `POST /admin/rollback` - Rollback to previous version
* `GET /admin/versions?limit=N` - List config versions

### Health

* `GET /health` - Health check

## Build & Deploy

### Prerequisites

* Go 1.21+
* Docker (for k3s deployment)
* k3s cluster with OTEL Collector deployed

### Verified by Running

```bash
# Tested on Go 1.22/1.23 (requires Go 1.21+)
$ go version
go version go1.23.1 darwin/arm64

# Download dependencies
$ go mod tidy

# Verify build
$ go build ./...
# Success - no output

# Run tests
$ go test ./...
?   	github.com/mobile-observability/gateway	[no test files]
?   	github.com/mobile-observability/gateway/internal/config	[no test files]
?   	github.com/mobile-observability/gateway/internal/db	[no test files]
?   	github.com/mobile-observability/gateway/internal/handlers	[no test files]
?   	github.com/mobile-observability/gateway/internal/otel	[no test files]
```

All packages compile successfully with the specified dependency versions.

### Local Development

```bash
# Verify build first (recommended)
./verify.sh

# Install dependencies
go mod tidy

# Run locally
PORT=8080 \
DB_PATH=./data/gateway.db \
OTEL_COLLECTOR_ENDPOINT=localhost:4317 \
go run main.go
```

### Build Docker Image

```bash
# Build for k3s
docker build -t otel-gateway:latest .

# Import into k3s
k3s ctr images import otel-gateway.tar

# Or build directly in k3s
k3s ctr images import <(docker save otel-gateway:latest)
```

### Deploy to k3s

```bash
# Apply gateway manifest
kubectl apply -f ../k8s/otel-gateway.yaml

# Check status
kubectl get pods -n mobile-observability -l app=otel-gateway

# View logs
kubectl logs -n mobile-observability -l app=otel-gateway -f
```

### Verify Deployment

```bash
# Port-forward gateway
kubectl port-forward -n mobile-observability svc/otel-gateway 8080:8080

# Test health endpoint
curl http://localhost:8080/health

# Test config endpoint
curl "http://localhost:8080/config?app_id=test&device_id=test123"

# Test ingest (sample event)
curl -X POST http://localhost:8080/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "events": [
      {
        "event_name": "ui.freeze",
        "session_id": "sess-123",
        "device_id": "dev-456",
        "config_version": 1,
        "timestamp": 1704067200000,
        "attributes": {
          "duration_ms": 3000,
          "screen": "HomeActivity"
        }
      }
    ]
  }'

# Check collector logs for received data
kubectl logs -n mobile-observability -l app=otel-collector | tail -20
```

## Configuration

Environment variables:

* `PORT` - HTTP port (default: 8080)
* `DB_PATH` - SQLite database path (default: ./data/gateway.db)
* `OTEL_COLLECTOR_ENDPOINT` - Collector gRPC endpoint (default: otel-collector.mobile-observability.svc.cluster.local:4317)

## Database Schema

### config_versions
* version (PK, autoincrement)
* graph_json (React Flow format)
* dsl_json (device format)
* published_at
* published_by
* is_active (only one active at a time)

### device_heartbeats
* id (PK)
* device_id
* app_id
* session_id
* buffer_usage_mb
* last_triggers (JSON array)
* config_version
* timestamp

## Data Flow

1. Mobile device sends event batch to `/ingest`
2. Gateway validates JSON
3. Gateway converts each event to OTEL Log:
   * body = event_name
   * attributes = event attributes + session_id + device_id + trigger_id + config_version
4. Gateway exports logs to Collector via OTLP/gRPC
5. Collector processes and exports to debug/logging exporters

## Workflow Management

### Publishing a Workflow

```bash
curl -X POST http://localhost:8080/admin/publish \
  -H "Content-Type: application/json" \
  -d '{
    "graph_json": "{...react flow graph...}",
    "dsl_json": "{...compiled dsl...}",
    "published_by": "admin"
  }'
```

### Rollback

```bash
curl -X POST http://localhost:8080/admin/rollback \
  -H "Content-Type: application/json" \
  -d '{"version": 1}'
```

### List Versions

```bash
curl http://localhost:8080/admin/versions?limit=10
```

## Troubleshooting

### Gateway can't connect to Collector

Check service DNS resolution:

```bash
kubectl exec -n mobile-observability deploy/otel-gateway -- nslookup otel-collector.mobile-observability.svc.cluster.local
```

### Database locked errors

Ensure only one replica is running (SQLite limitation):

```bash
kubectl scale deployment -n mobile-observability otel-gateway --replicas=1
```

### Events not appearing in collector logs

1. Check gateway logs for export errors
2. Verify collector is receiving on port 4317
3. Check collector logs for processing errors
4. Verify debug/logging exporters are configured

## Next Steps

* Step 3: Android app implementation
* Step 4: React control plane UI
