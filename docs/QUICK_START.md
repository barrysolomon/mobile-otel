# Quick Start Guide

Get the mobile observability demo up and running in 5 minutes.

## What You'll Build

By the end of this guide, you'll have:
- ✅ OTEL Collector running in Kubernetes
- ✅ Gateway API processing events
- ✅ Control Plane UI for workflow management
- ✅ Test event flowing through the entire pipeline

## Prerequisites

- Kubernetes cluster (k3s or standard)
- kubectl configured
- Node.js 18+
- 5 minutes

## Step 1: Deploy Backend (2 minutes)

```bash
# Navigate to project
cd /Users/barrysolomon/IdeaProjects/mobile-app

# Deploy everything to Kubernetes
kubectl apply -f k8s/

# Wait for pods to be ready
kubectl wait --for=condition=ready pod -n mobile-observability --all --timeout=60s

# Verify deployment
kubectl get pods -n mobile-observability
```

**Expected output:**
```
NAME                              READY   STATUS    RESTARTS   AGE
otel-collector-xxxxxxxxxx-xxxxx   1/1     Running   0          30s
otel-gateway-xxxxxxxxxx-xxxxx     1/1     Running   0          30s
```

## Step 2: Port Forward Gateway (30 seconds)

```bash
# Forward gateway port to localhost
kubectl port-forward -n mobile-observability svc/otel-gateway 8080:8080 &

# Test gateway health
curl http://localhost:8080/health
```

**Expected output:**
```json
{"status":"healthy"}
```

## Step 3: Start Control Plane UI (2 minutes)

```bash
# Navigate to UI directory
cd control-plane-ui

# Install dependencies (first time only)
npm install

# Start development server
npm run dev
```

**Expected output:**
```
VITE ready in 500 ms

➜  Local:   http://localhost:3000/
➜  Network: use --host to expose
```

Open browser to http://localhost:3000

## Step 4: Send Test Event (30 seconds)

```bash
# Send a test event through the gateway
curl -X POST http://localhost:8080/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "events": [{
      "event_name": "quickstart.test",
      "timestamp": '$(date +%s000)',
      "session_id": "quickstart-session",
      "device_id": "quickstart-device",
      "app_id": "demo-app",
      "config_version": 1,
      "attributes": {
        "demo_run_id": "quickstart-run",
        "message": "Hello from Quick Start!"
      }
    }]
  }'
```

**Expected output:**
```json
{"received":1,"status":"ok"}
```

## Step 5: Verify End-to-End (1 minute)

```bash
# Check gateway received the event
kubectl logs -n mobile-observability -l app=otel-gateway --tail=20 | grep "quickstart.test"

# Check collector processed the event
kubectl logs -n mobile-observability -l app=otel-collector --tail=20 | grep "quickstart.test"
```

**Expected output in collector logs:**
```
Body: Str(quickstart.test)
Attributes:
  -> session_id: Str(quickstart-session)
  -> device_id: Str(quickstart-device)
  -> demo_run_id: Str(quickstart-run)
  -> message: Str(Hello from Quick Start!)
```

## 🎉 Success!

You've successfully:
1. Deployed the OTEL Collector and Gateway to Kubernetes
2. Started the Control Plane UI
3. Sent an event through the gateway
4. Verified the event reached the collector as an OTEL Log

## What's Next?

### Create Your First Workflow

1. **Open Control Plane UI**: http://localhost:3000
2. **Click "New Workflow"**
3. **Add nodes**:
   - EventMatchNode: Match `quickstart.test` events
   - FlushWindowNode: Flush last 2 minutes
4. **Connect nodes**: Drag from output handle to input handle
5. **Click "Publish"**

### Install Android App

```bash
cd ../android-app
./gradlew installDebug
```

Configure gateway URL in `MainActivity.kt`:
```kotlin
private const val GATEWAY_URL = "http://10.0.2.2:8080"  // Emulator
```

### Test Demo Scenarios

Launch the Android app and try:
- **UI Freeze**: Tap button to trigger UI performance workflow
- **Crash Recovery**: Tap button to simulate crash
- **Network Errors**: Tap button to trigger HTTP error workflow

## Common Issues

### Gateway health check fails

**Problem**: `curl: (7) Failed to connect to localhost port 8080`

**Solution**: Verify port-forward is running
```bash
lsof -i :8080 | grep kubectl
# If empty, restart port-forward:
kubectl port-forward -n mobile-observability svc/otel-gateway 8080:8080 &
```

### UI won't start

**Problem**: `npm run dev` fails

**Solution**: Clear node_modules and reinstall
```bash
rm -rf node_modules package-lock.json
npm install
npm run dev
```

### No output in collector logs

**Problem**: Collector logs don't show events

**Solution**: Check gateway logs first
```bash
kubectl logs -n mobile-observability -l app=otel-gateway --tail=50
# Look for "Exported event" messages
```

## Quick Reference Commands

```bash
# Check all pods
kubectl get pods -n mobile-observability

# Gateway logs
kubectl logs -n mobile-observability -l app=otel-gateway -f

# Collector logs
kubectl logs -n mobile-observability -l app=otel-collector -f

# Restart gateway
kubectl rollout restart deployment/otel-gateway -n mobile-observability

# Restart collector
kubectl rollout restart deployment/otel-collector -n mobile-observability

# Stop port-forward
pkill -f "kubectl port-forward"

# Clean up everything
kubectl delete namespace mobile-observability
```

## Learning Path

Now that you have the system running, explore:

1. **[User Guide](USER_GUIDE.md)** - Learn how to create workflows and use the UI
2. **[Developer Guide](DEVELOPER_GUIDE.md)** - Extend the system with custom nodes
3. **[API Reference](API_REFERENCE.md)** - Complete API documentation
4. **[Operations Guide](OPERATIONS_GUIDE.md)** - Deploy to production

## Architecture Overview

```
Android App          Gateway             OTEL Collector
    │                   │                      │
    │ Capture events    │                      │
    │ (Ring Buffer)     │                      │
    │                   │                      │
    │ POST /ingest      │                      │
    ├──────────────────►│                      │
    │                   │ Convert to OTEL      │
    │                   ├─────────────────────►│
    │                   │ OTLP/gRPC            │
    │                   │                      │
    │                   │                      ├─► Loki
    │                   │                      ├─► Prometheus
    │                   │                      └─► Debug logs
    │                   │
    │ GET /config       │
    │◄──────────────────┤
    │                   │

Control Plane UI ──────►│
  Manage workflows      │
```

## Next Steps Checklist

- [ ] Deploy backend (Step 1)
- [ ] Start Control Plane UI (Step 3)
- [ ] Send test event (Step 4)
- [ ] Verify end-to-end (Step 5)
- [ ] Create first workflow via UI
- [ ] Install Android app
- [ ] Test demo scenarios
- [ ] Read [User Guide](USER_GUIDE.md)
- [ ] Explore [Developer Guide](DEVELOPER_GUIDE.md)

## Support

- **Documentation**: See `/docs` directory
- **Issues**: Check logs with `kubectl logs`
- **Questions**: Review [Troubleshooting Guide](TROUBLESHOOTING_GUIDE.md)

---

**Time to complete**: ~5 minutes
**Difficulty**: Beginner
**Prerequisites**: Kubernetes, kubectl, Node.js
