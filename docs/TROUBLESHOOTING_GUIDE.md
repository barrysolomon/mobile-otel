# Troubleshooting Guide

Comprehensive troubleshooting guide for the Mobile Observability system.

## Table of Contents

1. [Quick Diagnosis](#quick-diagnosis)
2. [Gateway Issues](#gateway-issues)
3. [OTEL Collector Issues](#otel-collector-issues)
4. [Control Plane UI Issues](#control-plane-ui-issues)
5. [Android SDK Issues](#android-sdk-issues)
6. [Network & Connectivity](#network--connectivity)
7. [Performance Issues](#performance-issues)
8. [Data Issues](#data-issues)
9. [Deployment Issues](#deployment-issues)
10. [Common Error Messages](#common-error-messages)

## Quick Diagnosis

### System Health Check

Run this quick health check script:

```bash
#!/bin/bash
# health-check.sh

echo "=== Mobile Observability System Health Check ==="

# 1. Kubernetes Pods
echo -e "\n[1] Checking pods..."
kubectl get pods -n mobile-observability

# 2. Gateway Health
echo -e "\n[2] Checking gateway..."
curl -s http://localhost:8080/health || echo "Gateway unreachable"

# 3. Collector Logs
echo -e "\n[3] Checking collector (last 10 lines)..."
kubectl logs -n mobile-observability -l app=otel-collector --tail=10

# 4. Gateway Logs
echo -e "\n[4] Checking gateway (last 10 lines)..."
kubectl logs -n mobile-observability -l app=otel-gateway --tail=10

# 5. Services
echo -e "\n[5] Checking services..."
kubectl get svc -n mobile-observability

echo -e "\n=== Health Check Complete ==="
```

### Decision Tree

```
Problem?
│
├─ Gateway not responding
│  └─ See: Gateway Issues → Gateway Health Check Fails
│
├─ Events not reaching collector
│  └─ See: Data Issues → Events Not Appearing in Collector
│
├─ UI not loading
│  └─ See: Control Plane UI Issues → UI Won't Load
│
├─ Android app crashes
│  └─ See: Android SDK Issues → App Crashes on Startup
│
├─ Slow performance
│  └─ See: Performance Issues
│
└─ Deployment failing
   └─ See: Deployment Issues
```

## Gateway Issues

### Gateway Health Check Fails

**Symptoms:**
```bash
$ curl http://localhost:8080/health
curl: (7) Failed to connect to localhost port 8080: Connection refused
```

**Diagnosis:**

```bash
# 1. Check if pods are running
kubectl get pods -n mobile-observability -l app=otel-gateway

# 2. Check pod logs
kubectl logs -n mobile-observability -l app=otel-gateway --tail=50

# 3. Check port-forward
lsof -i :8080 | grep kubectl
```

**Solutions:**

**A. Gateway pod not running:**
```bash
# Check pod status
kubectl describe pod -n mobile-observability -l app=otel-gateway

# Common causes:
# - Image pull error: Check image name and registry access
# - CrashLoopBackOff: Check logs for startup errors
# - Resource limits: Check node capacity

# Restart deployment
kubectl rollout restart deployment/otel-gateway -n mobile-observability
```

**B. Port-forward not running:**
```bash
# Restart port-forward
pkill -f "kubectl port-forward"
kubectl port-forward -n mobile-observability svc/otel-gateway 8080:8080 &
```

**C. Service misconfigured:**
```bash
# Check service
kubectl get svc -n mobile-observability otel-gateway

# Verify service selector matches pod labels
kubectl get svc otel-gateway -n mobile-observability -o yaml | grep selector
kubectl get pods -n mobile-observability -l app=otel-gateway --show-labels
```

### Gateway Returns 500 Errors

**Symptoms:**
```bash
$ curl -X POST http://localhost:8080/ingest -d '{...}'
{"error":"Internal server error"}
```

**Diagnosis:**

```bash
# Check gateway logs for stack traces
kubectl logs -n mobile-observability -l app=otel-gateway --tail=100 | grep -A 20 ERROR

# Common errors:
# - "Failed to connect to OTEL Collector"
# - "Database connection failed"
# - "Failed to export events"
```

**Solutions:**

**A. Collector connection failed:**
```bash
# Verify collector is running
kubectl get pods -n mobile-observability -l app=otel-collector

# Test connectivity from gateway pod
kubectl exec -n mobile-observability -it <gateway-pod> -- \
  nc -zv otel-collector 4317

# If fails, check collector service
kubectl get svc -n mobile-observability otel-collector
```

**B. Database connection failed:**
```bash
# Check database pod (if using in-cluster)
kubectl get pods -n mobile-observability -l app=postgres

# Test connectivity
kubectl exec -n mobile-observability -it <gateway-pod> -- \
  nc -zv postgres 5432

# Check credentials
kubectl get secret -n mobile-observability postgres-secret -o yaml
```

**C. Out of memory:**
```bash
# Check memory usage
kubectl top pods -n mobile-observability -l app=otel-gateway

# Increase memory limit in deployment
kubectl edit deployment otel-gateway -n mobile-observability
# Update: resources.limits.memory: "512Mi" -> "1Gi"
```

### Gateway Logs Show "Connection Refused"

**Error:**
```
Error exporting events: rpc error: code = Unavailable desc = connection refused
```

**Solutions:**

```bash
# 1. Verify collector endpoint
kubectl get svc -n mobile-observability otel-collector
# Should show: otel-collector.mobile-observability.svc.cluster.local:4317

# 2. Check collector is listening on 4317
kubectl exec -n mobile-observability -it <collector-pod> -- netstat -ln | grep 4317

# 3. Check NetworkPolicy isn't blocking
kubectl get networkpolicy -n mobile-observability

# 4. Restart both services
kubectl rollout restart deployment/otel-collector -n mobile-observability
kubectl rollout restart deployment/otel-gateway -n mobile-observability
```

## OTEL Collector Issues

### Collector Not Receiving Events

**Symptoms:**
- Gateway logs show successful export
- Collector logs show no incoming events

**Diagnosis:**

```bash
# Check collector logs
kubectl logs -n mobile-observability -l app=otel-collector --tail=100

# Look for:
# - "Everything is ready. Begin running and processing data."
# - No log entries for incoming data
```

**Solutions:**

```bash
# 1. Verify collector config
kubectl get configmap -n mobile-observability otel-collector-config -o yaml

# Ensure receivers are configured:
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317

# 2. Check collector is listening
kubectl exec -n mobile-observability -it <collector-pod> -- netstat -ln | grep 4317

# 3. Test with manual event
kubectl port-forward -n mobile-observability svc/otel-collector 4317:4317
# Use grpcurl or similar to test

# 4. Restart collector
kubectl rollout restart deployment/otel-collector -n mobile-observability
```

### Collector Logs Show "Queue is Full"

**Error:**
```
Queue is full, dropping data
```

**Symptoms:**
- High event volume
- Collector can't keep up with ingestion

**Solutions:**

```bash
# 1. Increase batch size
kubectl edit configmap -n mobile-observability otel-collector-config

# Update processors:
processors:
  batch:
    timeout: 10s
    send_batch_size: 10000  # Increase from 1000

# 2. Scale up collectors
kubectl scale deployment otel-collector -n mobile-observability --replicas=3

# 3. Increase memory limit
kubectl edit deployment otel-collector -n mobile-observability
# Update: resources.limits.memory: "1Gi" -> "2Gi"

# 4. Add memory_limiter
processors:
  memory_limiter:
    limit_mib: 2048
    spike_limit_mib: 512
```

### Collector Crashes on Startup

**Symptoms:**
```bash
$ kubectl get pods -n mobile-observability -l app=otel-collector
NAME                              READY   STATUS             RESTARTS   AGE
otel-collector-xxx-yyy            0/1     CrashLoopBackOff   5          3m
```

**Diagnosis:**

```bash
# Check previous logs
kubectl logs -n mobile-observability <collector-pod> --previous

# Common errors:
# - "failed to build pipelines: unknown exporter type"
# - "failed to load config: yaml: unmarshal errors"
```

**Solutions:**

**A. Invalid config:**
```bash
# Validate config locally
kubectl get configmap -n mobile-observability otel-collector-config -o jsonpath='{.data.otel-collector-config\.yaml}' > config.yaml

# Check YAML syntax
yamllint config.yaml

# Test config with collector binary
docker run --rm -v $(pwd)/config.yaml:/config.yaml \
  otel/opentelemetry-collector-contrib:latest \
  --config=/config.yaml --dry-run
```

**B. Missing exporter:**
```bash
# Ensure using contrib distribution
# Check image in deployment:
kubectl get deployment otel-collector -n mobile-observability -o yaml | grep image:
# Should be: otel/opentelemetry-collector-contrib (not otel/opentelemetry-collector)
```

## Control Plane UI Issues

### UI Won't Load

**Symptoms:**
```bash
$ curl http://localhost:3000
curl: (7) Failed to connect to localhost port 3000: Connection refused
```

**Diagnosis:**

```bash
# Check if dev server is running
lsof -i :3000

# Check npm logs
cd control-plane-ui
npm run dev
```

**Solutions:**

**A. Dev server not started:**
```bash
cd control-plane-ui
npm install
npm run dev
```

**B. Port already in use:**
```bash
# Kill process using port 3000
lsof -ti :3000 | xargs kill -9

# Or change port in vite.config.ts:
server: {
  port: 3001
}
```

**C. Node modules corrupted:**
```bash
rm -rf node_modules package-lock.json
npm install
npm run dev
```

### UI Shows "Network Error" When Publishing

**Symptoms:**
- Click Publish button
- Error: "Network Error" or "Failed to fetch"

**Diagnosis:**

```bash
# 1. Check gateway port-forward
curl http://localhost:8080/health

# 2. Check browser console (F12)
# Look for CORS errors or connection failures

# 3. Check Vite proxy config
cat control-plane-ui/vite.config.ts | grep proxy -A 5
```

**Solutions:**

**A. Gateway not accessible:**
```bash
# Restart port-forward
pkill -f "kubectl port-forward"
kubectl port-forward -n mobile-observability svc/otel-gateway 8080:8080 &

# Verify
curl http://localhost:8080/health
```

**B. Proxy misconfigured:**
```typescript
// vite.config.ts
export default defineConfig({
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',  // Verify this URL
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '')
      }
    }
  }
})
```

**C. CORS issue:**
```bash
# If deploying gateway separately, add CORS headers
# Update gateway to allow UI origin
```

### Workflow Validation Fails

**Error:**
```
Cannot publish: Entry node not found
```

**Solutions:**

```bash
# In UI:
# 1. Right-click workflow in left sidebar
# 2. Select "Set Entry Node"
# 3. Choose starting trigger node
# 4. Click Validate again

# If validation still fails:
# - Check all nodes are connected
# - Remove any circular connections
# - Ensure no disconnected nodes
```

### Graph Doesn't Render

**Symptoms:**
- Blank canvas
- No nodes visible

**Solutions:**

```bash
# 1. Check browser console for errors
# Open DevTools (F12) → Console

# 2. Clear React Flow state
# Refresh page (Ctrl+R or Cmd+R)

# 3. Check workflow data
# In browser console:
localStorage.getItem('workflows')

# 4. Reset local storage
localStorage.clear()
# Refresh page
```

## Android SDK Issues

### App Crashes on Startup

**Error:**
```logcat
FATAL EXCEPTION: main
java.lang.IllegalStateException: SDK not initialized
```

**Solutions:**

```kotlin
// Ensure SDK is initialized in Application class
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        ObservabilitySDK.initialize(
            context = this,
            gatewayUrl = "http://10.0.2.2:8080"  // Verify URL
        )
    }
}

// Register in AndroidManifest.xml:
<application
    android:name=".MyApplication"
    ...>
```

### SDK Can't Connect to Gateway

**Error:**
```logcat
E/GatewayClient: Failed to send events: java.net.ConnectException: Connection refused
```

**Diagnosis:**

```bash
# Check gateway URL in MainActivity.kt or config
adb logcat | grep "Gateway URL"

# Common issues:
# - Emulator: Should use 10.0.2.2 not localhost
# - Physical device: Should use host machine's local IP
```

**Solutions:**

**A. Emulator connection:**
```kotlin
// Use special emulator localhost
private const val GATEWAY_URL = "http://10.0.2.2:8080"
```

**B. Physical device:**
```bash
# Get your local IP
# macOS/Linux:
ifconfig | grep "inet " | grep -v 127.0.0.1
# Example: 192.168.1.100

# Update in app:
private const val GATEWAY_URL = "http://192.168.1.100:8080"
```

**C. Port-forward not running:**
```bash
# Verify port-forward active
lsof -i :8080 | grep kubectl

# Restart if needed
kubectl port-forward -n mobile-observability svc/otel-gateway 8080:8080 &
```

### Events Not Being Captured

**Symptoms:**
- App runs without errors
- No events in gateway logs

**Diagnosis:**

```bash
# Check Android logs
adb logcat | grep -E "ObservabilitySDK|RingBufferManager"

# Look for:
# - "Captured event: [event_name]"
# - "Adding event to buffer"
```

**Solutions:**

```kotlin
// 1. Verify SDK is capturing events
sdk.captureEvent("test.event", mapOf(
    "test" to "value"
))

// 2. Check logs for capture
// Should see: "Captured event: test.event"

// 3. Manually trigger flush
// In MainActivity, add button:
binding.btnFlush.setOnClickListener {
    lifecycleScope.launch {
        sdk.manualFlush()  // If method exists
    }
}

// 4. Check buffer size
adb logcat | grep "RAM buffer size"
```

### Workflow Not Triggering

**Symptoms:**
- Events captured
- Workflow doesn't trigger flush

**Diagnosis:**

```bash
# Check workflow evaluation logs
adb logcat | grep "WorkflowEvaluator"

# Look for:
# - "Evaluating event: [event_name]"
# - "Workflow [id] triggered" (should appear)
# - "Workflow [id] did not match" (if not triggered)
```

**Solutions:**

```kotlin
// 1. Verify config version
adb logcat | grep "Fetched config version"
// Should show latest version

// 2. Check trigger conditions
// Event attributes must match predicates exactly

// Example event:
sdk.captureEvent("ui.freeze", mapOf(
    "duration_ms" to 3500  // Must be number, not string
))

// Matching trigger:
// {"field": "duration_ms", "op": ">", "value": 2000}

// 3. Force config refresh
// Restart app or wait 60s for next poll
```

## Network & Connectivity

### DNS Resolution Fails

**Error:**
```
no such host: otel-collector.mobile-observability.svc.cluster.local
```

**Solutions:**

```bash
# 1. Check if services exist
kubectl get svc -n mobile-observability

# 2. Verify service names
kubectl get svc -n mobile-observability otel-collector

# 3. Check DNS from pod
kubectl exec -n mobile-observability -it <gateway-pod> -- \
  nslookup otel-collector.mobile-observability.svc.cluster.local

# 4. Use IP address as fallback
COLLECTOR_IP=$(kubectl get svc -n mobile-observability otel-collector -o jsonpath='{.spec.clusterIP}')
# Update gateway config to use IP instead of hostname
```

### SSL/TLS Certificate Errors

**Error:**
```
x509: certificate signed by unknown authority
```

**Solutions:**

```bash
# 1. Check certificate
kubectl get secret -n mobile-observability gateway-tls-secret

# 2. Verify cert-manager
kubectl get certificates -n mobile-observability

# 3. Check certificate status
kubectl describe certificate gateway-tls -n mobile-observability

# 4. Recreate certificate
kubectl delete certificate gateway-tls -n mobile-observability
kubectl apply -f certificate.yaml

# 5. For development, skip TLS verification (not for production)
curl -k https://gateway.yourcompany.com/health
```

### Timeout Errors

**Error:**
```
context deadline exceeded
```

**Solutions:**

```bash
# 1. Increase timeout in gateway
# Update OTEL exporter timeout:
# exporter, err := otel.NewLogExporter(ctx, collectorEndpoint,
#     otlploggrpc.WithTimeout(30 * time.Second))  # Increase

# 2. Check network latency
kubectl exec -n mobile-observability -it <gateway-pod> -- \
  time nc -zv otel-collector 4317

# 3. Check collector responsiveness
kubectl top pods -n mobile-observability -l app=otel-collector
# High CPU/memory may cause timeouts

# 4. Scale up collectors
kubectl scale deployment otel-collector -n mobile-observability --replicas=2
```

## Performance Issues

### High Gateway Latency

**Symptoms:**
- Slow response times
- Timeouts on /ingest

**Diagnosis:**

```bash
# 1. Check resource usage
kubectl top pods -n mobile-observability -l app=otel-gateway

# 2. Profile gateway
kubectl logs -n mobile-observability -l app=otel-gateway | grep "duration"

# 3. Check database performance
kubectl logs -n mobile-observability -l app=postgres | grep "slow query"
```

**Solutions:**

```bash
# 1. Scale horizontally
kubectl scale deployment otel-gateway -n mobile-observability --replicas=3

# 2. Increase resources
kubectl edit deployment otel-gateway -n mobile-observability
# Update:
resources:
  limits:
    cpu: "1000m"
    memory: "1Gi"

# 3. Add database indexes
# Connect to database and add indexes on frequently queried columns

# 4. Enable HPA
kubectl apply -f hpa.yaml  # See Operations Guide
```

### High Memory Usage

**Symptoms:**
- OOMKilled pod status
- Frequent restarts

**Diagnosis:**

```bash
# Check memory usage
kubectl top pods -n mobile-observability

# Check pod events
kubectl describe pod -n mobile-observability <pod-name> | grep -A 5 Events
```

**Solutions:**

```bash
# 1. Increase memory limit
kubectl edit deployment <component> -n mobile-observability
# Update: resources.limits.memory

# 2. Add memory_limiter processor (collector)
processors:
  memory_limiter:
    limit_mib: 1024
    check_interval: 1s

# 3. Reduce batch size
processors:
  batch:
    send_batch_size: 1000  # Reduce from larger value

# 4. Review code for memory leaks
# Check goroutine/thread count
```

### Database Slow Queries

**Symptoms:**
- Gateway slow on /config endpoint
- High database CPU

**Solutions:**

```bash
# 1. Add indexes
# Connect to PostgreSQL:
kubectl exec -n mobile-observability -it postgres-0 -- psql -U gateway

# Add indexes:
CREATE INDEX idx_config_versions_active ON config_versions(active);
CREATE INDEX idx_config_versions_version ON config_versions(version);

# 2. Vacuum database
VACUUM ANALYZE;

# 3. Increase connection pool
# Update gateway config:
# maxOpenConns = 25
# maxIdleConns = 5

# 4. Use read replicas for /config (advanced)
```

## Data Issues

### Events Not Appearing in Collector

**Symptoms:**
- Gateway logs show successful export
- Collector logs show no events

**Diagnosis:**

```bash
# 1. Check collector processors
kubectl get configmap -n mobile-observability otel-collector-config -o yaml

# 2. Check collector exporters
# Look for debug or logging exporter

# 3. Verify collector is exporting
kubectl logs -n mobile-observability -l app=otel-collector | grep -i export
```

**Solutions:**

```bash
# 1. Add debug exporter
exporters:
  debug:
    verbosity: detailed
  logging:
    verbosity: detailed

# Update pipeline:
service:
  pipelines:
    logs:
      receivers: [otlp]
      processors: [memory_limiter, batch]
      exporters: [debug, logging]  # Add debug

# 2. Restart collector
kubectl rollout restart deployment/otel-collector -n mobile-observability

# 3. Check logs again
kubectl logs -n mobile-observability -l app=otel-collector -f
```

### Missing Attributes

**Symptoms:**
- Events appear but missing expected attributes

**Solutions:**

```bash
# 1. Verify attributes in Android app
adb logcat | grep "Captured event"
# Check full event JSON

# 2. Check gateway transformation
kubectl logs -n mobile-observability -l app=otel-gateway | grep "Exporting event"

# 3. Ensure attributes are included in OTEL record
# Check internal/otel/exporter.go:
record.AddAttributes(
    log.String("custom_attr", event.Attributes["custom_attr"])
)

# 4. Check collector processors aren't dropping attributes
# Review collector config processors
```

### Incorrect Timestamps

**Symptoms:**
- Events appear with wrong timestamps

**Solutions:**

```kotlin
// Android: Ensure timestamp is in milliseconds
val timestamp = System.currentTimeMillis()  // Correct
// NOT: System.currentTimeMillis() / 1000 (seconds)

// Verify in logs:
adb logcat | grep "timestamp"

// Gateway: Verify timestamp parsing
// In internal/otel/exporter.go:
timestamp := time.UnixMilli(event.Timestamp)
```

## Deployment Issues

### Image Pull Errors

**Error:**
```
Failed to pull image "gcr.io/your-project/gateway:latest": unauthorized
```

**Solutions:**

```bash
# 1. Check image exists
docker images | grep gateway

# 2. Verify registry credentials
kubectl get secret -n mobile-observability <registry-secret>

# 3. Create image pull secret
kubectl create secret docker-registry registry-secret \
  --docker-server=gcr.io \
  --docker-username=_json_key \
  --docker-password="$(cat key.json)" \
  -n mobile-observability

# 4. Add to deployment
spec:
  template:
    spec:
      imagePullSecrets:
      - name: registry-secret
```

### Pod Stuck in Pending

**Symptoms:**
```bash
$ kubectl get pods -n mobile-observability
NAME                    READY   STATUS    RESTARTS   AGE
gateway-xxx-yyy         0/1     Pending   0          5m
```

**Diagnosis:**

```bash
# Check pod events
kubectl describe pod -n mobile-observability <pod-name>

# Common causes:
# - Insufficient resources
# - PVC not bound
# - Node selector not matched
```

**Solutions:**

**A. Insufficient resources:**
```bash
# Check node capacity
kubectl describe nodes | grep -A 5 "Allocated resources"

# Reduce resource requests or add nodes
```

**B. PVC not bound:**
```bash
# Check PVC status
kubectl get pvc -n mobile-observability

# Check storage class
kubectl get storageclass

# Create storage class if missing
```

**C. Node affinity:**
```bash
# Check node labels
kubectl get nodes --show-labels

# Update deployment to remove node selector
kubectl edit deployment <component> -n mobile-observability
```

## Common Error Messages

### "Failed to connect to OTEL Collector"

**Cause:** Gateway can't reach collector

**Fix:**
```bash
kubectl get svc -n mobile-observability otel-collector
kubectl get pods -n mobile-observability -l app=otel-collector
kubectl rollout restart deployment/otel-gateway -n mobile-observability
```

### "Database connection failed"

**Cause:** Gateway can't reach database

**Fix:**
```bash
kubectl get pods -n mobile-observability -l app=postgres
kubectl get secret -n mobile-observability postgres-secret
# Verify credentials and restart gateway
```

### "Version not found"

**Cause:** Trying to rollback to non-existent version

**Fix:**
```bash
curl http://localhost:8080/admin/versions
# Use valid version number from list
```

### "Invalid DSL JSON"

**Cause:** Malformed workflow configuration

**Fix:**
- Validate workflow in UI before publishing
- Check JSON syntax
- Ensure all required fields present

### "Queue is full"

**Cause:** Collector can't keep up with event rate

**Fix:**
```bash
# Scale collectors
kubectl scale deployment otel-collector -n mobile-observability --replicas=3

# Increase batch size in config
# Increase memory limit
```

## Getting Help

### Collect Diagnostic Information

```bash
#!/bin/bash
# collect-diagnostics.sh

mkdir -p diagnostics
cd diagnostics

# Pod status
kubectl get pods -n mobile-observability -o wide > pods.txt

# Logs
kubectl logs -n mobile-observability -l app=otel-gateway --tail=500 > gateway.log
kubectl logs -n mobile-observability -l app=otel-collector --tail=500 > collector.log

# Describe resources
kubectl describe deployment -n mobile-observability otel-gateway > gateway-describe.txt
kubectl describe deployment -n mobile-observability otel-collector > collector-describe.txt

# Events
kubectl get events -n mobile-observability --sort-by='.lastTimestamp' > events.txt

# Service status
kubectl get svc -n mobile-observability -o yaml > services.yaml

# ConfigMaps
kubectl get configmap -n mobile-observability -o yaml > configmaps.yaml

echo "Diagnostics collected in diagnostics/"
```

### Support Checklist

When requesting help, provide:
- [ ] Output of `kubectl get pods -n mobile-observability`
- [ ] Gateway logs (last 100 lines)
- [ ] Collector logs (last 100 lines)
- [ ] Describe output for failing pods
- [ ] Recent events
- [ ] What you were trying to do
- [ ] What actually happened
- [ ] Steps you've already tried

## Related Documentation

- [Quick Start](QUICK_START.md) - Getting started
- [User Guide](USER_GUIDE.md) - Using the system
- [Developer Guide](DEVELOPER_GUIDE.md) - Extending the system
- [Operations Guide](OPERATIONS_GUIDE.md) - Production deployment

---

**Last Updated:** 2024-01-20
