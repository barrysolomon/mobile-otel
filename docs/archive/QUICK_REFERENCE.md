# Quick Reference Card

Essential commands for deploying and testing the mobile observability demo.

## 🚀 One-Line Deploy

```bash
cd /Users/barrysolomon/IdeaProjects/mobile-app && kubectl apply -f k8s/ && kubectl wait --for=condition=ready pod -l app=otel-collector -n otel-demo --timeout=60s && kubectl wait --for=condition=ready pod -l app=otel-gateway -n otel-demo --timeout=60s && kubectl port-forward -n otel-demo svc/otel-gateway 8080:8080 &
```

## 📋 Component Status

```bash
# All pods
kubectl get pods -n otel-demo

# All services
kubectl get svc -n otel-demo

# Gateway logs (last 50 lines)
kubectl logs -n otel-demo -l app=otel-gateway --tail=50

# Collector logs (last 50 lines)
kubectl logs -n otel-demo -l app=otel-collector --tail=50

# Storage
kubectl get pvc -n otel-demo
```

## 🔍 Health Checks

```bash
# Gateway health
curl http://localhost:8080/health

# Current config
curl "http://localhost:8080/config?app_id=demo-app&device_id=test-device"

# List versions
curl http://localhost:8080/admin/versions

# OTEL Collector metrics
kubectl port-forward -n otel-demo svc/otel-collector 8888:8888
curl http://localhost:8888/metrics
```

## 🎯 Testing Workflows

```bash
# Publish test event via gateway
curl -X POST http://localhost:8080/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "events": [{
      "event_name": "test.event",
      "timestamp": '$(date +%s000)',
      "session_id": "test-session",
      "device_id": "test-device",
      "app_id": "demo-app",
      "config_version": 1,
      "attributes": {"demo_run_id": "quick-test"}
    }]
  }'

# Check if event reached collector
kubectl logs -n otel-demo -l app=otel-collector --tail=100 | grep "test.event"
```

## 📱 Android Testing

```bash
# Watch Android app logs
adb logcat | grep -E "ObservabilitySDK|WorkflowEvaluator|RingBufferManager"

# Find demo_run_id
adb logcat | grep "Demo Run ID"

# Watch for triggers
adb logcat | grep "Workflow.*triggered"

# Monitor buffer usage
adb logcat | grep "RAM buffer"

# Check config fetch
adb logcat | grep "Fetched config version"
```

## 🖥️ Control Plane UI

```bash
# Start UI (from control-plane-ui directory)
npm run dev

# Build for production
npm run build

# Preview production build
npm run preview

# Access UI
open http://localhost:3000
```

## 🔄 Port Forwarding

```bash
# Gateway (required for UI and Android)
kubectl port-forward -n otel-demo svc/otel-gateway 8080:8080 &

# Collector (optional, for direct testing)
kubectl port-forward -n otel-demo svc/otel-collector 4317:4317 4318:4318 &

# Collector metrics
kubectl port-forward -n otel-demo svc/otel-collector 8888:8888 &

# Kill all port forwards
pkill -f "kubectl port-forward"
```

## 🔧 Common Fixes

```bash
# Restart gateway
kubectl rollout restart deployment/otel-gateway -n otel-demo

# Restart collector
kubectl rollout restart deployment/otel-collector -n otel-demo

# Clear gateway database
kubectl exec -n otel-demo -it $(kubectl get pod -n otel-demo -l app=otel-gateway -o name) -- rm -f /data/gateway.db
kubectl rollout restart deployment/otel-gateway -n otel-demo

# View gateway DB content
kubectl exec -n otel-demo -it $(kubectl get pod -n otel-demo -l app=otel-gateway -o name) -- sqlite3 /data/gateway.db ".tables"
```

## 📊 Correlation Tracking

```bash
# Get current demo_run_id from Android
DEMO_RUN_ID=$(adb logcat -d | grep "Demo Run ID" | tail -1 | awk '{print $NF}')
echo "Tracking: $DEMO_RUN_ID"

# Search gateway logs
kubectl logs -n otel-demo -l app=otel-gateway | grep "$DEMO_RUN_ID"

# Search collector logs
kubectl logs -n otel-demo -l app=otel-collector | grep "$DEMO_RUN_ID"

# Count events with this run ID
kubectl logs -n otel-demo -l app=otel-collector | grep -c "$DEMO_RUN_ID"
```

## 🗂️ Config Management

```bash
# Publish workflow (example with curl)
curl -X POST http://localhost:8080/admin/publish \
  -H "Content-Type: application/json" \
  -d '{
    "graph_json": "[{\"id\":\"test-workflow\",\"name\":\"Test\",\"enabled\":true,\"nodes\":[],\"edges\":[]}]",
    "dsl_json": "{\"version\":1,\"limits\":{\"diskMb\":50,\"ramEvents\":5000,\"retentionHours\":24},\"workflows\":[{\"id\":\"test-workflow\",\"trigger\":{\"any\":[{\"event\":\"test.event\"}]},\"actions\":[{\"flush_window\":{\"minutes\":2,\"scope\":\"session\"}}]}]}",
    "published_by": "cli-test"
  }'

# Rollback to version 1
curl -X POST http://localhost:8080/admin/rollback \
  -H "Content-Type: application/json" \
  -d '{"version": 1}'

# Get specific version
curl "http://localhost:8080/admin/versions?limit=1"
```

## 🧹 Cleanup

```bash
# Remove everything
kubectl delete namespace otel-demo

# Just stop port forwards
pkill -f "kubectl port-forward"

# Stop UI dev server
pkill -f "vite"

# Clear Android app data
adb shell pm clear <your.package.name>
```

## 📈 Performance Testing

```bash
# Stress test gateway (100 events)
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
    }' > /dev/null &
done
wait
echo "Sent 100 events"

# Check gateway received all
kubectl logs -n otel-demo -l app=otel-gateway --tail=200 | grep "Received.*events"

# Check collector processed all
kubectl logs -n otel-demo -l app=otel-collector --tail=200 | grep "stress.test" | wc -l
```

## 🐛 Debug Mode

```bash
# Enable verbose logging in collector
kubectl edit configmap -n otel-demo otel-collector-config
# Set verbosity: detailed (already default)

# Tail all logs
kubectl logs -n otel-demo -l app=otel-collector -f &
kubectl logs -n otel-demo -l app=otel-gateway -f &

# Android verbose logs
adb logcat -c  # Clear logs first
adb logcat *:V | grep -E "ObservabilitySDK|WorkflowEvaluator|RingBufferManager|GatewayClient"
```

## 📁 File Locations

| Component | Directory |
|-----------|-----------|
| Kubernetes YAML | `/Users/barrysolomon/IdeaProjects/mobile-app/k8s/` |
| Go Gateway | `/Users/barrysolomon/IdeaProjects/mobile-app/gateway/` |
| Android App | `/Users/barrysolomon/IdeaProjects/mobile-app/android-app/` |
| Control Plane UI | `/Users/barrysolomon/IdeaProjects/mobile-app/control-plane-ui/` |
| Documentation | `/Users/barrysolomon/IdeaProjects/mobile-app/*.md` |

## 🌐 Service Endpoints

### Inside Cluster
- Collector gRPC: `otel-collector.otel-demo.svc.cluster.local:4317`
- Collector HTTP: `otel-collector.otel-demo.svc.cluster.local:4318`
- Gateway: `otel-gateway.otel-demo.svc.cluster.local:8080`

### Port-Forwarded (localhost)
- Gateway API: `http://localhost:8080`
- Collector gRPC: `localhost:4317`
- Collector HTTP: `localhost:4318`
- Collector Metrics: `http://localhost:8888/metrics`
- Control Plane UI: `http://localhost:3000`

### Android App
- Emulator: `http://10.0.2.2:8080` (gateway)
- Physical device: `http://<your-local-ip>:8080`

## 📚 Documentation Files

- `DEPLOYMENT_GUIDE.md` - Complete deployment instructions
- `E2E_VERIFICATION_CHECKLIST.md` - Detailed verification steps
- `VERIFICATION_PACK.md` - 30-second smoke test
- `FINAL_STATUS.md` - Overall system status
- `STEP2_SUMMARY.md` - Gateway implementation details
- `STEP3_SUMMARY.md` - Android app details
- `STEP4_SUMMARY.md` - Control Plane UI details
