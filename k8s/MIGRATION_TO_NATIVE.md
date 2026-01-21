# Migration to OTEL-Native Architecture

## 🎯 What Changed

### Old Architecture (Custom Gateway)
```
Android App → JSON/HTTP → Custom Gateway → OTLP → Collector
                          (Go service)
                          (SQLite DB)
```

### New Architecture (OTEL-Native)
```
Android App → OTLP/gRPC → Collector (with mobile processor)
                          (No gateway needed!)
```

## 🚀 Migration Steps

### Step 1: Remove Old Gateway

```bash
# Delete old gateway deployment
kubectl delete -f k8s/otel-gateway.yaml

# Or delete specific resources
kubectl delete deployment otel-gateway -n mobile-observability
kubectl delete service otel-gateway -n mobile-observability
kubectl delete pvc gateway-data -n mobile-observability
```

### Step 2: Deploy OTEL-Native Collector

```bash
# Apply new collector configuration
kubectl apply -f k8s/otel-collector-native.yaml
```

**Note**: The mobile policy processor is configured but won't work until you build a custom collector (see Phase 4 in REMAINING_WORK.md). For now, it will use the standard collector without the processor.

### Step 3: Verify Deployment

```bash
# Check pods are running
kubectl get pods -n mobile-observability

# Should see:
# NAME                              READY   STATUS    RESTARTS   AGE
# otel-collector-xxxxxxxxxx-xxxxx   1/1     Running   0          30s

# Check logs
kubectl logs -n mobile-observability -l app=otel-collector -f
```

### Step 4: Get Collector Endpoint for Android App

```bash
# If using NodePort (default):
# Collector is accessible at: <node-ip>:30317 (gRPC) or :30318 (HTTP)

# Get node IP
kubectl get nodes -o wide

# Test OTLP endpoint
curl -v http://<node-ip>:30318/v1/logs
```

### Step 5: Update Android App Configuration

Update your Android app to point directly to the collector:

```kotlin
val config = MobileConfig(
    serviceName = "my-mobile-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "http://<node-ip>:30317"  // Direct to collector!
)
```

## 🔍 Troubleshooting

### Issue: Collector pod not starting

```bash
# Check pod status
kubectl describe pod -n mobile-observability -l app=otel-collector

# Check events
kubectl get events -n mobile-observability --sort-by='.lastTimestamp'
```

**Common causes:**
- ConfigMap not loaded (check: `kubectl get configmap -n mobile-observability`)
- Resource limits too low
- Invalid collector config

### Issue: Mobile policy processor not working

This is **expected** until Phase 4 is complete. The processor config is there, but the standard collector doesn't include our custom processor yet.

**To fix:**
1. Build custom collector with ocb (OpenTelemetry Collector Builder)
2. Create Docker image with custom collector
3. Update deployment to use custom image

See: [REMAINING_WORK.md](../REMAINING_WORK.md) Phase 4 for details.

### Issue: Android app can't connect

```bash
# Test from inside cluster
kubectl run -it --rm debug --image=curlimages/curl --restart=Never -n mobile-observability -- sh
curl -v http://otel-collector:4318/v1/logs

# Test from outside cluster (NodePort)
curl -v http://<node-ip>:30318/v1/logs
```

**Common causes:**
- Firewall blocking ports
- Wrong endpoint in Android app
- Service not exposed correctly

### Issue: No logs appearing

```bash
# Check collector logs for received data
kubectl logs -n mobile-observability -l app=otel-collector | grep -i received

# Check for errors
kubectl logs -n mobile-observability -l app=otel-collector | grep -i error
```

## 📊 What's Different

### Configuration Management

**Before (Gateway):**
- Policies in SQLite database
- REST API for policy management
- Requires database migrations

**After (Collector):**
- Policies in ConfigMap
- Edit YAML and reapply
- No database needed

```bash
# Update policies
kubectl edit configmap otel-collector-config -n mobile-observability

# Or edit the file and reapply
vi k8s/otel-collector-native.yaml
kubectl apply -f k8s/otel-collector-native.yaml

# Restart collector to pick up changes
kubectl rollout restart deployment otel-collector -n mobile-observability
```

### Data Flow

**Before:**
```
App → Gateway (JSON) → Gateway DB → Collector (OTLP) → Backends
```

**After:**
```
App → Collector (OTLP) → Backends
     ↑
     (Policy evaluation happens in collector processor)
```

### Persistence

**Before:**
- Gateway had SQLite database
- PersistentVolumeClaim needed

**After:**
- Collector is stateless
- Policies in ConfigMap (gitops-friendly!)
- No PVC needed

## 🎉 Benefits

1. **Simpler**: One less service to manage
2. **Standard**: 100% OpenTelemetry, no custom code
3. **GitOps**: Policies in YAML, version controlled
4. **Scalable**: Collector can scale horizontally
5. **Maintainable**: Official OTEL project, community support

## 🔄 Rollback Plan

If you need to rollback to the old architecture:

```bash
# Revert to old collector
kubectl apply -f k8s/otel-collector.yaml

# Redeploy gateway
kubectl apply -f k8s/otel-gateway.yaml

# Update Android app endpoint back to gateway
# collectorEndpoint = "http://<gateway-ip>:8080"
```

## 📝 Next Steps

1. ✅ Deploy OTEL-native collector (this guide)
2. ⏳ Build custom collector with mobile processor (Phase 4)
3. ⏳ Update Android app to use OTEL SDK (already done in code)
4. ⏳ Test all three demo scenarios
5. ⏳ Monitor and validate

## 🆘 Getting Help

- **Collector not starting**: Check [OTEL Collector docs](https://opentelemetry.io/docs/collector/)
- **Policy syntax**: See [collector-processor/mobilepolicyprocessor/testdata/config.yaml](../collector-processor/mobilepolicyprocessor/testdata/config.yaml)
- **OTLP issues**: See [OTLP specification](https://opentelemetry.io/docs/specs/otlp/)

## 📚 Reference

- **Old Architecture**: k8s/otel-gateway.yaml + k8s/otel-collector.yaml
- **New Architecture**: k8s/otel-collector-native.yaml
- **Remaining Work**: [REMAINING_WORK.md](../REMAINING_WORK.md)
- **Testing**: [TESTING_IMPLEMENTATION.md](../TESTING_IMPLEMENTATION.md)
