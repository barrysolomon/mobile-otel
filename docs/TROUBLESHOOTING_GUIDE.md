# Troubleshooting Guide

> **Note:** Gateway and Control Plane UI troubleshooting has moved to the
> [mobile-otel-control-plane](https://github.com/barrysolomon/mobile-otel-control-plane) repository.

Troubleshooting guide for the Android SDK, OTEL Collector, and collector processor.

## Table of Contents

1. [Quick Diagnosis](#quick-diagnosis)
2. [Android SDK Issues](#android-sdk-issues)
3. [OTEL Collector Issues](#otel-collector-issues)
4. [Network & Connectivity](#network--connectivity)
5. [Performance Issues](#performance-issues)
6. [Data Issues](#data-issues)
7. [Common Error Messages](#common-error-messages)

## Quick Diagnosis

### Decision Tree

```
Problem?
│
├─ Events not reaching collector
│  └─ See: Data Issues → Events Not Appearing in Collector
│
├─ Android app crashes
│  └─ See: Android SDK Issues → App Crashes on Startup
│
├─ SDK not exporting
│  └─ See: Android SDK Issues → Events Not Being Captured
│
├─ Slow performance
│  └─ See: Performance Issues
│
└─ Collector issues
   └─ See: OTEL Collector Issues
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

        OTelMobile.start(this, MobileConfig(
            serviceName = "my-app",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://10.0.2.2:4317"  // Verify URL
        ))
    }
}

// Register in AndroidManifest.xml:
<application
    android:name=".MyApplication"
    ...>
```

### SDK Can't Connect to Collector

**Error:**
```logcat
E/OTelMobile: Failed to export: java.net.ConnectException: Connection refused
```

**Diagnosis:**

```bash
# Check collector endpoint in config or logcat
adb logcat | grep "OTelMobile\|MobileOtel"

# Common issues:
# - Emulator: Should use 10.0.2.2 not localhost
# - Physical device: Should use host machine's local IP
# - Collector must be listening on port 4317 (gRPC) or 4318 (HTTP)
```

**Solutions:**

**A. Emulator connection:**
```kotlin
// Use special emulator localhost
val config = MobileConfig(
    collectorEndpoint = "http://10.0.2.2:4317"
)
```

**B. Physical device:**
```bash
# Get your local IP
# macOS/Linux:
ifconfig | grep "inet " | grep -v 127.0.0.1
# Example: 192.168.1.100

# Update in app config:
# collectorEndpoint = "http://192.168.1.100:4317"
```

**C. Collector not running:**
```bash
# If using Docker:
docker ps | grep otel
# If using Dash0, verify endpoint and auth token
```

### Events Not Being Captured

**Symptoms:**
- App runs without errors
- No events appearing in collector/backend

**Diagnosis:**

```bash
# Check Android logs
adb logcat | grep -E "OTelMobile|MobileOtel|MobileLogRecordProcessor"

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
# Check policy evaluation logs
adb logcat | grep "PolicyEvaluator"

# Look for:
# - "Evaluating event: [event_name]"
# - "Policy [id] triggered" (should appear)
# - "Policy [id] did not match" (if not triggered)
```

**Solutions:**

```kotlin
// 1. Check trigger conditions
// Event attributes must match predicates exactly

// Example event:
MobileOtel.sendEvent("ui.freeze", mapOf(
    "duration_ms" to 3500  // Must be number, not string
))

// Matching trigger:
// {"field": "duration_ms", "op": ">", "value": 2000}

// 2. Verify bundled config has policies
// Check assets/otel-config.json contains "workflows" array

// 3. Force flush manually
MobileOtel.forceFlush(windowMinutes = 5)
```

## Network & Connectivity

### SDK Can't Reach Collector Endpoint

**Symptoms:** Export failures in logcat, events buffered but never sent.

**Solutions:**

```bash
# 1. Verify the collector endpoint is reachable
# From emulator: use http://10.0.2.2:4317
# From physical device: use your machine's local IP

# 2. Check collector is running
docker ps | grep otel  # If using Docker
# or check your Dash0/cloud endpoint is correct

# 3. Verify Android network permissions
# AndroidManifest.xml must have:
# <uses-permission android:name="android.permission.INTERNET" />
```

### SSL/TLS Certificate Errors

**Error:**
```
x509: certificate signed by unknown authority
```

**Solutions:**

- Ensure your collector endpoint uses a valid TLS certificate
- For local development, use `http://` (not `https://`) with `10.0.2.2:4317`
- For Dash0 cloud endpoints, TLS is handled automatically

### Timeout Errors

**Error:**
```
context deadline exceeded
```

**Solutions:**

```kotlin
// Increase export timeout in MobileConfig
val config = MobileConfig(
    exportTimeoutSeconds = 60  // Default is 30
)
```

## Performance Issues

### High Memory Usage on Device

**Symptoms:**
- App uses excessive memory
- OOM crashes on low-end devices

**Solutions:**

```kotlin
// 1. Reduce RAM buffer size
val config = MobileConfig(
    ramBufferSize = 2000  // Default is 5000
)

// 2. Reduce disk buffer size
val config = MobileConfig(
    diskBufferMb = 20  // Default is 50
)

// 3. Use CONDITIONAL export mode (default) to minimize background work
val config = MobileConfig(
    exportMode = ExportMode.CONDITIONAL
)
```

### Collector High Memory Usage

**Solutions:**

```yaml
# Add memory_limiter processor to collector config
processors:
  memory_limiter:
    limit_mib: 1024
    check_interval: 1s

# Reduce batch size
processors:
  batch:
    send_batch_size: 1000  # Reduce from larger value
```

## Data Issues

### Events Not Appearing in Backend

**Symptoms:**
- SDK logs show events captured
- Nothing visible in Dash0 or other backend

**Diagnosis:**

```bash
# 1. Check SDK logs for export errors
adb logcat | grep -E "OTelMobile|export|OTLP"

# 2. If using a local collector, check its logs
docker logs <collector-container> 2>&1 | grep -i error

# 3. Add debug exporter to collector config for visibility
```

**Solutions:**

```yaml
# Add debug exporter to collector config
exporters:
  debug:
    verbosity: detailed

service:
  pipelines:
    logs:
      receivers: [otlp]
      processors: [memory_limiter, batch]
      exporters: [debug, otlp]  # Add debug for stdout visibility
```

### Missing Attributes

**Symptoms:**
- Events appear but missing expected attributes

**Solutions:**

```bash
# 1. Verify attributes in Android app
adb logcat | grep "OTelMobile"
# Check full event attributes

# 2. Verify EnrichingLogRecordExporter is adding device/session attributes
# These are added automatically: device.id, device.model.name, os.version,
# mobile.session.id, app.version, service.name

# 3. Check collector processors aren't dropping attributes
# Review collector config processors
```

### Incorrect Timestamps

**Symptoms:**
- Events appear with wrong timestamps

**Solutions:**

```kotlin
// Android: The SDK uses System.currentTimeMillis() internally
// If sending custom events, ensure timestamp is in milliseconds:
val timestamp = System.currentTimeMillis()  // Correct
// NOT: System.currentTimeMillis() / 1000 (seconds)

// Verify in logs:
adb logcat | grep "timestamp"
```

## Common Error Messages

### "Invalid DSL JSON"

**Cause:** Malformed export policy configuration in bundled config.

**Fix:**

- Check JSON syntax in `assets/otel-config.json`
- Ensure all required fields present in workflow definitions
- See [Bundled Configuration](./BUNDLED_CONFIG.md) for the correct format

### "Queue is full" (Collector)

**Cause:** Collector can't keep up with event rate.

**Fix:**

```yaml
# Increase batch size in collector config
processors:
  batch:
    send_batch_size: 10000
    timeout: 10s

# Or increase memory limit
```

## Getting Help

### Collect Diagnostic Information

```bash
# Android SDK logs
adb logcat | grep -E "OTelMobile|MobileOtel|PolicyEvaluator" > sdk-logs.txt

# Collector logs (if running locally via Docker)
docker logs <collector-container> > collector.log 2>&1
```

### Support Checklist

When requesting help, provide:

- [ ] Android SDK logcat output (filtered for OTelMobile)
- [ ] Collector logs (if applicable)
- [ ] `otel-config.json` contents (redact auth tokens)
- [ ] Export mode in use (CONDITIONAL / CONTINUOUS / HYBRID)
- [ ] What you were trying to do
- [ ] What actually happened
- [ ] Steps you've already tried

## Related Documentation

- [Quick Start](QUICK_START.md) - Getting started
- [Android SDK Guide](ANDROID_SDK_GUIDE.md) - SDK integration
- [Developer Guide](DEVELOPER_GUIDE.md) - Extending the system
- [Export Modes](EXPORT_MODES.md) - Export mode details
- [Control Plane](https://github.com/barrysolomon/mobile-otel-control-plane) - Gateway, UI, and deployment (sister repo)
