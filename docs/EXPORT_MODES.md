# Export Modes

The Mobile OTEL SDK supports three export modes to balance observability needs with battery life and bandwidth constraints.

## Export Modes

> **Demo app default:** The Schedulr demo app uses **CONTINUOUS** mode so traces and logs export on a 30s schedule without requiring a running policy gateway. For production use, switch to **CONDITIONAL** or **HYBRID**.

### 1. CONDITIONAL (Most Battery Efficient)

**Best for: Production apps, battery-sensitive scenarios**

Data is **only exported when triggered by conditions**:
- ❌ No scheduled exports
- ✅ Only exports on `forceFlush()` or workflow triggers
- ✅ Triggers can include:
  - Errors (HTTP 5xx, exceptions, crashes)
  - Performance issues (UI freeze, slow operations, frame drops)
  - Device health (low memory, battery drain, thermal throttling, low storage)
  - Network issues (network loss, slow requests)
  - Predictive risks (ML-based crash prediction, etc.)

**How it works:**
- Traces: Buffered in memory (1 hour timeout, effectively disabled)
- Metrics: Buffered in memory (1 hour timeout, effectively disabled)
- Logs: Policy-based (only exported on trigger)
- All signals flush together when a trigger condition is met

**Configuration:**
```kotlin
val config = MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "https://collector.example.com:4317",
    exportMode = ExportMode.CONDITIONAL
)
```

**Battery Impact:** ⚡ Minimal - Only sends data when issues occur
**Bandwidth Usage:** 📡 Minimal - No regular uploads
**Observability:** 🔍 Issue-focused - Complete data for problems, sparse data otherwise

---

### 2. CONTINUOUS (Always Sending)

**Best for: Development, debugging, A/B testing specific features**

Data is **exported on fixed schedules** regardless of conditions:
- ✅ Traces exported every 30 seconds (configurable)
- ✅ Metrics exported every 60 seconds (configurable)
- ✅ Logs still policy-based (unchanged)
- ✅ Consistent data flow for real-time monitoring

**How it works:**
- Traces: Exported every `traceExportIntervalSeconds` (default: 30s)
- Metrics: Exported every `metricExportIntervalSeconds` (default: 60s)
- Logs: Policy-based (same as CONDITIONAL)

**Configuration:**
```kotlin
val config = MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "https://collector.example.com:4317",
    exportMode = ExportMode.CONTINUOUS,
    traceExportIntervalSeconds = 30,  // Adjust as needed
    metricExportIntervalSeconds = 60  // Adjust as needed
)
```

**Battery Impact:** ⚡⚡⚡ High - Regular network activity
**Bandwidth Usage:** 📡📡📡 High - Constant uploads
**Observability:** 🔍🔍🔍 Complete - Full visibility into all user sessions

---

### 3. HYBRID (Balanced)

**Best for: Production apps with higher observability needs**

HYBRID is **not** a continuous stream. Bulk event data stays in the ring buffer and only exports when a policy trigger fires. What distinguishes it from CONDITIONAL is:

- ✅ **Periodic device metrics** — device health gauges (battery, memory, network) exported on a schedule
- ✅ **Periodic prediction cycles** — predictive risk assessments run on a schedule and emit `device.heartbeat` / `prediction.cycle` logs that are immediately forwarded (not buffered)
- ✅ **Policy-triggered selective flush** — same as CONDITIONAL; a matching event (error, freeze, crash) exports the relevant time window from the buffer
- ❌ **No continuous trace/log stream** — bulk events are never periodically dumped; they only export on a trigger

**How it works:**

- Bulk events (taps, screens, network calls): buffered, exported only on policy trigger
- Device metrics: exported every `metricExportIntervalSeconds * 2` (default: 120s)
- Heartbeat + prediction logs: emitted and immediately forwarded every `predictionIntervalSeconds` (default: 30s) — these are lightweight and not buffered
- Full buffer flush on trigger (crash, freeze, HTTP error, etc.)

**Configuration:**
```kotlin
val config = MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "https://collector.example.com:4317",
    exportMode = ExportMode.HYBRID,
    metricExportIntervalSeconds = 60,   // Device metrics exported every 120s
    predictionIntervalSeconds = 30      // Heartbeat/prediction every 30s
)
```

**Battery Impact:** ⚡⚡ Moderate — periodic metrics + predictions, no continuous bulk stream
**Bandwidth Usage:** 📡📡 Low-moderate — heartbeats are tiny; bulk data only on triggers
**Observability:** 🔍🔍 Good — live device health signal + full context on problems

---

## Comparison Table

| Feature | CONDITIONAL | CONTINUOUS | HYBRID |
| ------- | ----------- | ---------- | ------ |
| **Bulk event export** | On trigger only | Every 30s (default) | On trigger only |
| **Device metric export** | On trigger only | Every 60s (default) | Every 120s (default) |
| **Heartbeat / prediction** | None | None | Every 30s (lightweight, not buffered) |
| **Log export** | On trigger only | On trigger only | On trigger only |
| **Battery Impact** | ⚡ Minimal | ⚡⚡⚡ High | ⚡⚡ Low-moderate |
| **Bandwidth** | 📡 Minimal | 📡📡📡 High | 📡📡 Low-moderate |
| **Best For** | Production | Debug/Dev | Production w/ live device health |

---

## Trigger Conditions (All Modes)

When using CONDITIONAL or HYBRID modes, data can be triggered by:

### Error Triggers
- **HTTP errors**: Status codes >= 500 (or custom threshold)
- **Exceptions**: Specific exception types or patterns
- **Crashes**: App crash detection

### Performance Triggers
- **UI Freeze**: UI thread blocked > 2s (configurable)
- **Slow operations**: Operations exceeding threshold
- **Frame drops**: Dropped frames exceeding threshold

### Device Health Triggers
- **Low memory**: Available memory below threshold
- **Battery drain**: Rapid battery discharge
- **Thermal throttling**: Device overheating
- **Low storage**: Storage space below threshold

### Network Triggers
- **Network loss**: Connection lost
- **Slow requests**: Request duration exceeds threshold

### Predictive Triggers
- **Crash risk**: ML-predicted crash probability > threshold
- **Performance degradation risk**: Predicted slowdown
- **Battery drain risk**: Predicted rapid discharge

---

## Force Flush

All modes support manual flushing:

```kotlin
// Manually flush all buffered data (logs, traces, metrics)
loggerProvider.forceFlush(timeoutSeconds = 30)
```

This is essential in CONDITIONAL mode where data only exports on triggers or explicit flush.

---

## Changing Modes at Runtime

Update the configuration and restart the app:

```kotlin
// Save new configuration
val newConfig = config.copy(exportMode = ExportMode.HYBRID)
ConfigManager.saveConfig(context, newConfig)

// Restart required for changes to take effect
```

---

## Recommendations

- **Production apps (general)**: Use **CONDITIONAL** for best battery life
- **Production apps (high-value users)**: Use **HYBRID** for better visibility
- **Development/QA**: Use **CONTINUOUS** for maximum observability
- **A/B testing**: Use **CONDITIONAL** for control, **HYBRID** for variant
- **Performance testing**: Use **CONTINUOUS** to capture all data

---

## Battery Impact Estimates

Based on typical usage patterns:

| Mode | Additional Battery Drain | Network Data (per day) |
|------|-------------------------|------------------------|
| **CONDITIONAL** | < 0.5% | 1-5 MB (only on issues) |
| **HYBRID** | 1-2% | 10-50 MB |
| **CONTINUOUS** | 3-5% | 50-200 MB |

*Actual impact varies based on app usage and data volume*

---

## Migration Guide

### From No Export Control → CONDITIONAL

Default behavior - no changes needed. The SDK defaults to CONDITIONAL mode.

### From Always-On → CONDITIONAL

```kotlin
// Before
val config = MobileConfig(...)

// After
val config = MobileConfig(
    ...,
    exportMode = ExportMode.CONDITIONAL  // Explicit (but this is default)
)
```

Ensure you have workflow triggers configured, or data will only export on `forceFlush()`.

### From CONDITIONAL → CONTINUOUS (for debugging)

```kotlin
val config = MobileConfig(
    ...,
    exportMode = ExportMode.CONTINUOUS,
    traceExportIntervalSeconds = 10,  // More frequent for debugging
    metricExportIntervalSeconds = 20
)
```

Remember to switch back to CONDITIONAL before releasing to production!

---

## Bundled Configuration

Export modes can be pre-configured in `assets/otel-config.json` and shipped with the app. This provides offline-first configuration that works immediately on first launch.

### Example Bundled Config

**File**: `examples/demo-app/android/src/main/assets/otel-config.json`

```json
{
  "serviceName": "otel-mobile-demo",
  "serviceVersion": "1.0.0",
  "collectorEndpoint": "http://10.0.2.2:4317",
  "exportMode": "CONDITIONAL",
  "traceExportIntervalSeconds": 30,
  "metricExportIntervalSeconds": 60,
  "ramBufferSize": 5000,
  "diskBufferMb": 50,
  "diskBufferTtlHours": 24
}
```

### Environment-Specific Export Modes

Use Gradle build variants to ship different export modes per environment:

**Development** (`src/dev/assets/otel-config.json`):
```json
{
  "exportMode": "CONTINUOUS",
  "traceExportIntervalSeconds": 10,
  "metricExportIntervalSeconds": 20
}
```

**Production** (`src/prod/assets/otel-config.json`):
```json
{
  "exportMode": "CONDITIONAL",
  "traceExportIntervalSeconds": 30,
  "metricExportIntervalSeconds": 60
}
```

**Benefits**:
- Dev builds automatically use CONTINUOUS mode for full observability
- Prod builds automatically use CONDITIONAL mode for battery efficiency
- No code changes needed - just build the appropriate variant
- Works offline on first launch

See [BUNDLED_CONFIG.md](./BUNDLED_CONFIG.md) for complete guide.

---

## Related Documentation

- [Workflow System](./WORKFLOW_SYSTEM.md) - Complete workflow architecture
- [Bundled Configuration](./BUNDLED_CONFIG.md) - Pre-configured settings shipped with app
- [Workflow Builder UI](../control-plane-ui/README_WORKFLOWS.md) - Visual workflow editor
- [Collector Configuration](../control-plane-ui/README_COLLECTOR.md) - Endpoint management UI
- [MobileConfig](../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/MobileConfig.kt) - Configuration options
