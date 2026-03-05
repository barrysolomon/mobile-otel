# Mobile Observability Demo - Deployment Guide

Complete step-by-step guide to deploy and test all 4 components end-to-end.

## Gateway vs. Collector-Only: Which Do You Need?

The system has two deployment modes. Choose based on your requirements:

| | Collector-Only | Full Stack (with Gateway) |
|---|---|---|
| **Receive telemetry from Android app** | ✅ | ✅ |
| **Export to backends (Loki, Tempo, etc.)** | ✅ | ✅ |
| **Built-in export policies** (ui.freeze → flush 2min, app.crash → flush 5min) | ✅ | ✅ |
| **Remote policy config** (change trigger rules without a new app release) | ❌ | ✅ |
| **Drag-and-drop policy builder UI** | ❌ | ✅ |
| **Per-device / per-geo policy targeting** | ❌ | ✅ |
| **Setup complexity** | Simple | Requires building custom Go image |

**Collector-only is the right choice for:**
- Development and testing
- Environments where you can't build/deploy the custom gateway image
- When built-in default policies (ui.freeze, app.crash) are sufficient

**Full stack is needed when:**
- You want to push new export policies to devices without a new app release
- You need the visual policy builder (control-plane-ui)
- You need geo/device-targeted policy routing

> **Note on the gateway image:** The gateway (`otel-gateway:latest`) is a custom Go binary
> that must be built and loaded into your cluster's container runtime before deployment.
> It uses `imagePullPolicy: Never` and is not published to a public registry.
> See the [Building the Gateway Image](#building-the-gateway-image) section below.

## Prerequisites

- k3s or Kubernetes cluster running
- kubectl configured and connected
- Go 1.21+ installed
- Node.js 18+ and npm installed
- Android Studio with SDK (for Android app)
- Terminal access with curl

## Quick Start

### Collector-Only (simplest — works immediately)

```bash
# Deploy collector
kubectl apply -f k8s/otel-collector.yaml

# Wait for pod to be ready
kubectl wait --for=condition=ready pod -l app=otel-collector -n mobile-observability --timeout=60s

# Port-forward OTLP endpoint to localhost
kubectl port-forward -n mobile-observability svc/otel-collector 4317:4317 4318:4318 &

# Verify
kubectl get pods -n mobile-observability
```

The Android SDK sends OTLP directly to the collector. Built-in default export policies
(ui.freeze → 2-min flush, app.crash → 5-min flush) apply automatically with no further config.

### Full Stack (collector + gateway + control plane UI)

```bash
# 1. Build and load the gateway image into your cluster first — see
#    "Building the Gateway Image" section below

# 2. Deploy both components
kubectl apply -f k8s/otel-collector.yaml
kubectl apply -f k8s/otel-gateway.yaml

# 3. Wait for pods to be ready
kubectl wait --for=condition=ready pod -l app=otel-collector -n mobile-observability --timeout=60s
kubectl wait --for=condition=ready pod -l app=otel-gateway -n mobile-observability --timeout=60s

# 4. Port forward for local access
kubectl port-forward -n mobile-observability svc/otel-gateway 8080:8080 &
kubectl port-forward -n mobile-observability svc/otel-collector 4317:4317 4318:4318 &

# 5. Start Control Plane UI
cd control-plane-ui && npm install && npm run dev &

# 6. Test gateway
curl http://localhost:8080/health
```

## Detailed Deployment

### Step 1: Deploy OTEL Collector

```bash
cd /Users/barrysolomon/IdeaProjects/mobile-app

# Create namespace
kubectl create namespace mobile-observability

# Deploy collector
kubectl apply -f k8s/otel-collector.yaml

# Verify deployment
kubectl get pods -n mobile-observability -l app=otel-collector
kubectl logs -n mobile-observability -l app=otel-collector --tail=50

# Expected output: Pod running, logs show "Everything is ready. Begin running and processing data."
```

**Service endpoints:**
- `otel-collector.mobile-observability.svc.cluster.local:4317` - OTLP/gRPC
- `otel-collector.mobile-observability.svc.cluster.local:4318` - OTLP/HTTP

### Step 2: Deploy Gateway (Optional)

> Skip this step if you only need telemetry collection. The collector-only setup is
> sufficient for development, testing, and production use when the built-in export
> policies (ui.freeze, app.crash) meet your requirements.
>
> **Prerequisite:** The `otel-gateway:latest` image must be present in your cluster's
> container runtime before applying this manifest. See
> [Building the Gateway Image](#building-the-gateway-image).

```bash
# Deploy gateway with persistent storage
kubectl apply -f k8s/otel-gateway.yaml

# Verify deployment
kubectl get pods -n mobile-observability -l app=otel-gateway
kubectl get pvc -n mobile-observability

# Check logs
kubectl logs -n mobile-observability -l app=otel-gateway --tail=50

# Expected output: "Starting server on :8080" and "Connected to OTEL Collector"
```

**Service endpoint:** `otel-gateway.mobile-observability.svc.cluster.local:8080`

### Step 3: Port Forward for Local Access

For development and testing, forward services to localhost:

```bash
# Terminal 1: Gateway
kubectl port-forward -n mobile-observability svc/otel-gateway 8080:8080

# Terminal 2: Collector (optional, for direct testing)
kubectl port-forward -n mobile-observability svc/otel-collector 4317:4317 4318:4318
```

Keep these running throughout testing.

### Step 4: Verify Gateway

```bash
# Health check
curl http://localhost:8080/health
# Expected: {"status":"healthy"}

# Get current config
curl "http://localhost:8080/config?app_id=demo-app&device_id=test-device"
# Expected: {"version":0,"limits":{...},"workflows":[]}

# Verify OTEL connection
kubectl logs -n mobile-observability -l app=otel-gateway | grep "Connected to OTEL Collector"
```

### Step 5: Deploy Control Plane UI

```bash
cd /Users/barrysolomon/IdeaProjects/mobile-app/control-plane-ui

# Install dependencies
npm install

# Start development server
npm run dev
```

**Access UI:** http://localhost:3000

The Vite dev server automatically proxies `/api/*` requests to `http://localhost:8080`.

### Step 6: Build Android App

```bash
cd /Users/barrysolomon/IdeaProjects/mobile-app/android-app

# Sync Gradle (first time)
./gradlew build

# Install on device/emulator
./gradlew installDebug

# Or open in Android Studio and run
```

**Configure gateway URL in MainActivity.kt:**
```kotlin
private const val GATEWAY_URL = "http://10.0.2.2:8080" // Android emulator
// or
private const val GATEWAY_URL = "http://<your-local-ip>:8080" // Physical device
```

## End-to-End Testing

### Test 1: Publish Default Workflow via UI

1. **Open Control Plane UI**: http://localhost:3000

2. **Create workflow** (or use sample from UI):
   - Click "New Workflow"
   - Add EventMatchNode: `ui.freeze`
   - Add FlushWindowNode: 2 minutes, session scope
   - Connect nodes
   - Set as entry node

3. **Validate**: Click "Validate" button
   - Should show: "Graph valid ✓"

4. **Publish**: Click "Publish" button
   - Should show: "Published version 1"

5. **Verify in Gateway**:
   ```bash
   curl http://localhost:8080/admin/versions
   # Should show version 1 with your workflow
   ```

### Test 2: Android App - UI Freeze Scenario

1. **Launch Android App**

2. **Check logs for initialization**:
   ```bash
   adb logcat | grep "ObservabilitySDK"
   # Look for: Demo Run ID: run-1234567890
   ```

3. **Tap "Trigger UI Freeze"** button

4. **Check Android logs**:
   ```bash
   adb logcat | grep "WorkflowEvaluator"
   # Should see: Workflow ui-freeze triggered
   # Should see: Flushing 67 events to gateway
   ```

5. **Check gateway logs**:
   ```bash
   kubectl logs -n mobile-observability -l app=otel-gateway --tail=100 | grep "demo_run_id"
   # Should see events with your demo_run_id
   ```

6. **Check collector output**:
   ```bash
   kubectl logs -n mobile-observability -l app=otel-collector --tail=100 | grep "ui.freeze"
   # Should see OTEL log records with ui.freeze events
   ```

### Test 3: Verify Correlation

All events from a single Android session should have the same `demo_run_id`.

```bash
# Get demo_run_id from Android logs
adb logcat | grep "Demo Run ID"
# Output: Demo Run ID: run-1705780000000

# Search gateway logs for that run ID
kubectl logs -n mobile-observability -l app=otel-gateway | grep "run-1705780000000"
# Should see multiple events with matching demo_run_id

# Search collector logs
kubectl logs -n mobile-observability -l app=otel-collector | grep "run-1705780000000"
# Should see OTEL log records with demo_run_id attribute
```

### Test 4: Rollback Version

1. **In Control Plane UI**, go to Versions panel

2. **Publish a second workflow** (make any change)
   - Version should increment to 2

3. **Click "Rollback"** on version 1
   - Should show: "Rolled back to version 1"

4. **Verify**:
   ```bash
   curl http://localhost:8080/config?app_id=demo-app&device_id=test-device
   # Should show version 1 with "active":true
   ```

5. **Android app** should fetch version 1 on next poll

### Test 5: Manual Event Ingestion

Test gateway without Android app:

```bash
# Send event via curl
curl -X POST http://localhost:8080/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "events": [
      {
        "event_name": "test.event",
        "timestamp": '$(date +%s000)',
        "session_id": "test-session",
        "device_id": "test-device",
        "app_id": "demo-app",
        "config_version": 1,
        "attributes": {
          "demo_run_id": "manual-test",
          "test_attr": "value"
        }
      }
    ]
  }'

# Expected: {"received":1,"status":"ok"}

# Check collector logs
kubectl logs -n mobile-observability -l app=otel-collector --tail=50 | grep "test.event"
```

## Verification Checklist

Use this checklist to verify full system operation:

- [ ] OTEL Collector pod running in mobile-observability namespace
- [ ] Gateway pod running with PVC attached
- [ ] Gateway health endpoint returns 200
- [ ] Control Plane UI loads at http://localhost:3000
- [ ] Can create and publish workflow via UI
- [ ] Gateway versions endpoint shows published config
- [ ] Android app installs and launches
- [ ] Android app logs show demo_run_id on startup
- [ ] Triggering UI freeze flushes events to gateway
- [ ] Gateway logs show received events with demo_run_id
- [ ] Collector logs show OTEL log records
- [ ] demo_run_id preserved end-to-end
- [ ] Rollback functionality works in UI
- [ ] Android app fetches updated config

## Building the Gateway Image

The gateway is a custom Go binary not published to any public registry. It uses
`imagePullPolicy: Never`, so the image must exist in your cluster's container runtime
before `kubectl apply -f k8s/otel-gateway.yaml`.

### k3s cluster (Raspberry Pi or similar)

```bash
# On your development machine: build for linux/arm64 and save to a tarball
docker buildx build --platform linux/arm64 \
  -t otel-gateway:latest \
  --output type=docker,dest=otel-gateway.tar \
  gateway/

# Copy tarball to the k3s node
scp otel-gateway.tar <user>@<node-ip>:/tmp/

# On the k3s node: import into containerd
sudo k3s ctr images import /tmp/otel-gateway.tar
```

### k3s cluster (same machine as Docker)

```bash
cd gateway
docker build -t otel-gateway:latest .
docker save otel-gateway:latest | sudo k3s ctr images import -
```

### kind cluster

```bash
cd gateway
docker build -t otel-gateway:latest .
kind load docker-image otel-gateway:latest
```

### minikube

```bash
eval $(minikube docker-env)
cd gateway
docker build -t otel-gateway:latest .
```

### Using a registry instead

If loading images directly isn't practical, push to a registry and update
`imagePullPolicy` in `k8s/otel-gateway.yaml`:

```yaml
image: ghcr.io/your-org/otel-gateway:latest
imagePullPolicy: Always
```

## Troubleshooting

### Gateway can't connect to collector

**Symptom**: Gateway logs show "Failed to connect to OTEL Collector"

**Solution**:
```bash
# Check collector is running
kubectl get pods -n mobile-observability -l app=otel-collector

# Check service exists
kubectl get svc -n mobile-observability otel-collector

# Verify collector is listening on 4317
kubectl exec -n mobile-observability -it $(kubectl get pod -n mobile-observability -l app=otel-collector -o name) -- netstat -ln | grep 4317
```

### Android app can't reach gateway

**Symptom**: Android logs show "Failed to send events: Connection refused"

**Solutions**:

1. **Emulator**: Use `http://10.0.2.2:8080` (special emulator localhost)
2. **Physical device**: Use your computer's local IP
   ```bash
   # macOS/Linux
   ifconfig | grep "inet " | grep -v 127.0.0.1

   # Then update MainActivity.kt GATEWAY_URL
   ```
3. **Verify port forward is running**:
   ```bash
   lsof -i :8080 | grep kubectl
   ```

### Control Plane UI shows network error

**Symptom**: "Network Error" or "Failed to fetch" when publishing

**Solutions**:

1. **Check gateway port forward**:
   ```bash
   curl http://localhost:8080/health
   ```

2. **Check Vite proxy config** in `vite.config.ts`:
   ```typescript
   proxy: {
     '/api': {
       target: 'http://localhost:8080',
       changeOrigin: true
     }
   }
   ```

3. **Check browser console** for CORS errors

### Events not appearing in collector logs

**Symptom**: Android app sends events, but nothing in collector logs

**Solutions**:

1. **Check gateway logs**:
   ```bash
   kubectl logs -n mobile-observability -l app=otel-gateway --tail=100
   ```
   Should see "Received N events" and "Exported event"

2. **Check collector config**:
   ```bash
   kubectl get configmap -n mobile-observability otel-collector-config -o yaml
   ```
   Verify `exporters` includes `debug` or `logging`

3. **Increase collector log verbosity**: Already set to `detailed` in config

### No workflows showing in Android app config

**Symptom**: Android logs show `"workflows":[]`

**Solutions**:

1. **Verify workflow published**:
   ```bash
   curl http://localhost:8080/admin/versions
   # Should show version > 0
   ```

2. **Check config endpoint**:
   ```bash
   curl "http://localhost:8080/config?app_id=demo-app&device_id=test-device"
   ```

3. **Verify workflow is enabled** in Control Plane UI

## Performance Verification

### Buffer Usage

**Android app RAM buffer**:
```bash
adb logcat | grep "RAM buffer"
# Should show current size vs max (5000 events)
```

**Gateway disk usage**:
```bash
kubectl exec -n mobile-observability -it $(kubectl get pod -n mobile-observability -l app=otel-gateway -o name) -- du -sh /data/gateway.db
```

### Event Throughput

**Stress test with rapid events**:
```bash
# Send 100 events in quick succession
for i in {1..100}; do
  curl -s -X POST http://localhost:8080/ingest \
    -H "Content-Type: application/json" \
    -d '{
      "events": [{
        "event_name": "stress.test",
        "timestamp": '$(date +%s000)',
        "session_id": "stress-session",
        "device_id": "stress-device",
        "app_id": "demo-app",
        "config_version": 1,
        "attributes": {"index": '$i'}
      }]
    }' &
done
wait

# Check gateway handled all events
kubectl logs -n mobile-observability -l app=otel-gateway --tail=200 | grep "Received.*events" | wc -l
```

## Production Considerations

### Gateway Scaling

For production, consider:

1. **Horizontal scaling**:
   ```yaml
   spec:
     replicas: 3  # Multiple gateway pods
   ```

2. **Resource limits**:
   ```yaml
   resources:
     requests:
       memory: "256Mi"
       cpu: "250m"
     limits:
       memory: "512Mi"
       cpu: "500m"
   ```

3. **Database: Switch from SQLite to PostgreSQL**:
   - Update `internal/db/db.go`
   - Use PostgreSQL driver
   - Shared database for all gateway pods

### OTEL Collector Scaling

1. **Resource allocation** based on event volume:
   ```yaml
   resources:
     limits:
       memory: 2Gi
     requests:
       memory: 1Gi
   ```

2. **Enable batch processor** (already configured):
   ```yaml
   batch:
     timeout: 10s
     send_batch_size: 1000
   ```

3. **Add persistent exporters** (Prometheus, Loki, etc.):
   ```yaml
   exporters:
     prometheusremotewrite:
       endpoint: "http://prometheus:9090/api/v1/write"
     loki:
       endpoint: "http://loki:3100/loki/api/v1/push"
   ```

### Android App Optimizations

1. **Adjust buffer limits** in ObservabilitySDK.kt:
   ```kotlin
   private val diskMb: Long = 100  // Increase for more history
   private val ramEvents: Int = 10000  // Increase for high-volume apps
   ```

2. **Tune polling interval**:
   ```kotlin
   private val configPollIntervalMs = 60_000L  // Poll every 60 seconds
   ```

3. **Add retry logic** with exponential backoff in GatewayClient.kt

## Next Steps

Now that the system is deployed and verified:

1. **Create custom workflows** for your use cases
2. **Integrate real backend exporters** (replace debug exporter)
3. **Add authentication** to gateway admin endpoints
4. **Implement device monitoring** with real heartbeat polling in Control Plane UI
5. **Add workflow templates library** in UI
6. **Deploy to production cluster** with proper secrets management

## Support

For issues:
- Check logs: `kubectl logs -n mobile-observability -l app=<component>`
- Verify services: `kubectl get svc -n mobile-observability`
- Review verification checklist: [E2E_VERIFICATION_CHECKLIST.md](E2E_VERIFICATION_CHECKLIST.md)
- Check component status: [FINAL_STATUS.md](FINAL_STATUS.md)
