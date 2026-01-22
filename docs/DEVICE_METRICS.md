# Device Metrics on Flush

Capture comprehensive device health metrics when critical events occur (crashes, errors, triggers).

## Overview

When debugging mobile app issues, knowing the device state at the time of the problem is essential:

- **Crash at 3AM**: Was the device low on battery? Memory? Storage?
- **HTTP 500 error**: Was the network connectivity poor?
- **UI freeze**: Was the CPU throttled due to thermal issues?
- **App killed**: Was memory pressure too high?

The Device Metrics system automatically captures a snapshot of device health when:
- Crashes occur
- Errors are logged (HTTP 5xx, exceptions)
- Workflow triggers activate
- Manual flush is called

## Configuration

### Default Configuration

```kotlin
val config = MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "https://collector:4317",
    deviceMetricsConfig = DeviceMetricsConfig.default(),
    deviceMetricsCaptureConfig = DeviceMetricsCaptureConfig.default()
)
```

**Default captures**:
- ✅ Memory (used, available, low memory state)
- ✅ Battery (level, charging, health, temperature)
- ✅ CPU (core count, architecture)
- ✅ Network (type, connectivity)
- ✅ Storage (used, available, cache size)
- ✅ Thermal (throttling state)
- ✅ Display (resolution, density, orientation)
- ✅ System (OS version, API level, uptime)
- ✅ App (version, install time, foreground state)
- ❌ Location (disabled by default for privacy)

**Default triggers**:
- ✅ Crashes
- ✅ Errors
- ✅ Manual flush
- ✅ Workflow triggers
- ❌ Scheduled flush (battery-saving)

### Custom Configuration

```kotlin
// Minimal: Only essential metrics
val minimalConfig = MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "https://collector:4317",
    deviceMetricsConfig = DeviceMetricsConfig.minimal(),
    deviceMetricsCaptureConfig = DeviceMetricsCaptureConfig.conservative()
)

// Performance-focused: CPU, memory, thermal
val perfConfig = MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "https://collector:4317",
    deviceMetricsConfig = DeviceMetricsConfig.performance()
)

// Network-focused: Network + basic system
val networkConfig = MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "https://collector:4317",
    deviceMetricsConfig = DeviceMetricsConfig.network()
)
```

## Metrics Captured

### 1. Memory Metrics

**When useful**: Crashes, OOM errors, app kills

```
device.memory.used_mb             // MB of memory used by app
device.memory.available_mb        // MB of memory available
device.memory.total_mb            // Total device memory
device.memory.low_memory          // Boolean: low memory state
```

**Example**:
```
device.memory.used_mb = 450
device.memory.available_mb = 150
device.memory.low_memory = true  // ⚠️ Device under memory pressure!
```

### 2. Battery Metrics

**When useful**: Battery drain issues, thermal problems, unexpected shutdowns

```
device.battery.level_percent      // 0-100
device.battery.temperature_celsius // Battery temp
device.battery.charging           // Boolean
device.battery.health             // good/overheat/dead/etc
```

**Example**:
```
device.battery.level_percent = 8  // ⚠️ Low battery!
device.battery.temperature_celsius = 42  // ⚠️ Hot!
device.battery.health = "overheat"
```

### 3. CPU Metrics

**When useful**: Performance issues, UI freezes

```
cpu.core_count                    // Number of CPU cores
cpu.architecture                  // arm64-v8a, armeabi-v7a, etc.
cpu.usage_percent                 // Current CPU usage
```

### 4. Network Metrics

**When useful**: HTTP errors, connectivity issues, slow requests

```
network.type                      // wifi/cellular/ethernet/none
network.connected                 // Boolean
network.signal_strength          // Signal quality
```

**Example**:
```
network.type = "cellular"
network.connected = true
network.signal_strength = "poor"  // ⚠️ Weak signal!
```

### 5. Storage Metrics

**When useful**: Crashes, data corruption, cache issues

```
device.storage.used_mb            // Internal storage used
device.storage.available_mb       // Storage available
device.storage.cache_mb           // Cache size
```

### 6. Thermal Metrics (Android 9+)

**When useful**: Performance degradation, battery drain, crashes

```
device.thermal.state              // 0-4 (none/light/moderate/severe/critical)
```

**Thermal states**:
- 0 = None: Normal operation
- 1 = Light: Minor throttling
- 2 = Moderate: Noticeable throttling
- 3 = Severe: Significant performance impact
- 4 = Critical: Emergency shutdown imminent

**Example**:
```
device.thermal.state = 3  // ⚠️ Severe throttling!
```

### 7. Display Metrics

**When useful**: UI rendering issues, orientation bugs

```
display.width_px                  // Screen width
display.height_px                 // Screen height
display.density                   // Screen density (1.0 = 160dpi)
display.orientation               // portrait/landscape
```

### 8. System Metrics

**When useful**: OS-specific bugs, API level issues

```
os.version                        // "14", "13", etc.
os.api_level                      // 34, 33, etc.
device.model                      // "Pixel 7", "Galaxy S23", etc.
device.manufacturer               // "Google", "Samsung", etc.
system.uptime_ms                  // Device uptime
```

### 9. App Metrics

**When useful**: Version-specific bugs, upgrade issues

```
app.version                       // "1.2.3"
app.version_code                  // 123
app.install_time_ms               // First install timestamp
app.update_time_ms                // Last update timestamp
app.foreground                    // Boolean: app in foreground
```

### 10. Location Metrics (Privacy-Safe)

**When useful**: Region-specific issues (disabled by default)

```
geo.timezone                      // "America/Los_Angeles"
geo.country                       // "US"
```

⚠️ **Privacy Note**: Only coarse, anonymous location data (country/timezone).
No GPS coordinates, no precise location.

## Capture Triggers

### 1. App Start

**Why important**: Understand device state at app launch

```kotlin
// Automatic: Register lifecycle detector in Application.onCreate()
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val loggerProvider = MobileLoggerProvider.getInstance(this, config)
        val logger = loggerProvider.get("lifecycle")
        val metricsCollector = DeviceMetricsCollector(this, meter, metricsConfig)

        // Register lifecycle detector
        AppLifecycleDetector.register(this, logger, captureConfig, metricsCollector)
    }
}

// Metrics automatically captured on app start
// Useful for: startup performance, device conditions at launch
```

**Captured metrics**:
```
device.memory.available_mb = 450
device.battery.level_percent = 75
network.type = "wifi"
session.start_timestamp = 1705948200000
app.first_launch = false
```

### 2. Force Close Detection

**Why important**: Understand why users force close the app

```kotlin
// Automatic: Detected on next app start if previous session didn't shut down cleanly
// No code needed - AppLifecycleDetector handles this

// On next app start after force close:
// - Logs: app.force_close event
// - Captures: device metrics at force close
// - Attributes: time_since_force_close_ms, last_session_end_timestamp
```

**Example**:
```
Force close detected!
time_since_force_close_ms = 3600000  // 1 hour ago
device.memory.available_mb = 25      // ⚠️ Low memory at time of force close
device.battery.level_percent = 3     // ⚠️ Low battery
device.thermal.state = 3             // ⚠️ Thermal throttling

Insight: User force closed due to resource pressure
```

### 3. Crash Detection

```kotlin
// Automatic: Metrics captured when crash marker detected
// No code needed - happens automatically on app restart after crash
```

### 4. Error Events

```kotlin
logger.logRecordBuilder()
    .setBody("http.error")
    .setSeverity(Severity.ERROR)
    .setAllAttributes(Attributes.of(
        AttributeKey.longKey("http.status_code"), 500L
    ))
    .emit()

// Device metrics automatically captured
```

### 5. Manual Flush

```kotlin
// Capture metrics with manual flush
loggerProvider.forceFlush()

// Device metrics included in export
```

### 6. Workflow Triggers

```json
{
  "id": "http-error-5xx",
  "trigger": {
    "event": "http.error",
    "where": [{"attr": "http.status_code", "op": ">=", "value": 500}]
  },
  "actions": [
    {"type": "flush_window", "minutes": 5},
    {"type": "capture_device_metrics"}
  ]
}
```

### 7. Manual Capture

```kotlin
val collector = DeviceMetricsCollector(context, meter, config)

// Capture metrics manually
collector.captureMetrics(CaptureReason.MANUAL_CAPTURE, force = true)
```

## Lifecycle Integration

### Setup in Application Class

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // 1. Initialize OTEL
        val config = MobileConfig(
            serviceName = "my-app",
            serviceVersion = "1.0.0",
            collectorEndpoint = "https://collector:4317",
            deviceMetricsConfig = DeviceMetricsConfig.default(),
            deviceMetricsCaptureConfig = DeviceMetricsCaptureConfig(
                onAppStart = true,      // ✅ Capture on app start
                onForceClose = true,    // ✅ Detect force close
                onCrash = true,
                onError = true
            )
        )

        val loggerProvider = MobileLoggerProvider.getInstance(this, config)
        val logger = loggerProvider.get("lifecycle")
        val meter = loggerProvider.getOpenTelemetrySdk().getMeter("device-metrics")

        // 2. Create metrics collector
        val metricsCollector = DeviceMetricsCollector(
            context = this,
            meter = meter,
            config = config.deviceMetricsConfig
        )

        // 3. Register lifecycle detector (handles app start, force close, background)
        AppLifecycleDetector.register(
            app = this,
            logger = logger,
            captureConfig = config.deviceMetricsCaptureConfig,
            metricsCollector = metricsCollector
        )

        android.util.Log.i("MyApp", "Lifecycle monitoring active")
    }
}
```

### Lifecycle Events Captured

| Event | When Triggered | Metrics Captured | Use Case |
|-------|----------------|------------------|----------|
| `app.start` | First activity created | All configured metrics | Startup performance, launch conditions |
| `app.foreground` | App returns from background | Memory, battery, network | Session analysis |
| `app.background` | All activities stopped | Session duration | Usage patterns |
| `app.force_close` | Detected on next start | All metrics from last session | User frustration, resource issues |

## Use Cases

### Use Case 1: Crash Debugging

**Scenario**: App crashes at random times for some users

**Metrics Captured**:
```
device.memory.available_mb = 45  // ⚠️ Low memory
device.thermal.state = 3         // ⚠️ Thermal throttling
device.battery.level_percent = 5 // ⚠️ Low battery
```

**Insight**: Crashes occur when device is under resource pressure (low memory + thermal throttling + low battery)

**Action**: Reduce memory usage, add low-resource mode

### Use Case 2: HTTP Error Investigation

**Scenario**: HTTP 500 errors spike in production

**Metrics Captured**:
```
network.type = "cellular"
network.signal_strength = "poor"
network.connected = true
device.battery.level_percent = 15
```

**Insight**: Errors occur on poor cellular connections

**Action**: Add retry logic for poor connections, show offline mode

### Use Case 3: Performance Degradation

**Scenario**: UI freezes reported by users

**Metrics Captured**:
```
device.thermal.state = 4         // ⚠️ Critical thermal state
cpu.usage_percent = 95
device.battery.temperature_celsius = 45
```

**Insight**: Device is thermally throttled, CPU maxed out

**Action**: Reduce CPU usage, defer non-critical work

### Use Case 4: Storage Issues

**Scenario**: App fails to save data

**Metrics Captured**:
```
device.storage.available_mb = 12  // ⚠️ Low storage
device.storage.cache_mb = 450     // ⚠️ Large cache
```

**Insight**: Device is out of storage, cache is large

**Action**: Implement cache cleanup, show storage warning

### Use Case 5: App Start Performance

**Scenario**: Users complain about slow app startup

**Metrics Captured on App Start**:
```
app.start event
device.memory.available_mb = 200
device.cpu.core_count = 4
device.thermal.state = 0  // Normal
network.type = "wifi"
network.connected = true
app.first_launch = false
session.start_timestamp = 1705948200000
```

**Insight**: Device has adequate resources, network is good

**Action**: Investigate code initialization, not device constraints

### Use Case 6: Force Close Investigation

**Scenario**: High force close rate in production

**Metrics Captured**:
```
Force close detected (on next app start)
time_since_force_close_ms = 1800000  // 30 minutes ago

Metrics at time of force close:
device.memory.available_mb = 30      // ⚠️ Low memory
device.battery.level_percent = 8     // ⚠️ Low battery
device.thermal.state = 3             // ⚠️ Severe throttling
network.type = "cellular"
network.signal_strength = "poor"

Log tail shows:
- 5 HTTP errors in last 10 logs
- UI freeze (3000ms)
- Memory warning
- User force closed
```

**Insight**: Force closes correlate with resource pressure + poor network + UI freezes

**Action**:
1. Add low-resource mode
2. Improve network error handling
3. Reduce memory usage
4. Defer non-critical work when resources low

## Rate Limiting

To prevent excessive metric capture:

```kotlin
val captureConfig = DeviceMetricsCaptureConfig(
    rateLimitSeconds = 60  // Minimum 60s between captures
)
```

**Why rate limit?**
- Battery efficiency
- Data volume control
- Backend cost management

**Exception**: Use `force = true` to bypass rate limit when needed:

```kotlin
collector.captureMetrics(CaptureReason.CRASH, force = true)
```

## Privacy Considerations

### Privacy-Safe Metrics (Enabled by Default)

- Memory usage
- Battery level
- CPU info
- Network type (wifi/cellular)
- Storage usage
- System info (OS version, device model)

### Privacy-Sensitive Metrics (Disabled by Default)

- **Location**: Only coarse (country/timezone), never GPS
- **Display state**: Can reveal user behavior patterns

### GDPR/CCPA Compliance

```kotlin
// Privacy-focused configuration
val config = MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "https://collector:4317",
    deviceMetricsConfig = DeviceMetricsConfig.privacyFocused()
)
```

**Privacy principles**:
- ✅ Aggregate data only
- ✅ No user identification
- ✅ No precise location
- ✅ Opt-out support
- ✅ Data minimization

## Best Practices

### 1. Enable for Crashes

```kotlin
val captureConfig = DeviceMetricsCaptureConfig(
    onCrash = true,        // ✅ Always capture on crash
    onError = true,        // ✅ Capture on errors
    onManualFlush = false, // ❌ Skip manual flushes (too frequent)
    onScheduledFlush = false  // ❌ Skip scheduled (battery-saving)
)
```

### 2. Use Minimal Config on Low-End Devices

```kotlin
val deviceClass = getDeviceClass()  // low/mid/high

val metricsConfig = when (deviceClass) {
    DeviceClass.LOW -> DeviceMetricsConfig.minimal()
    DeviceClass.MID -> DeviceMetricsConfig.default()
    DeviceClass.HIGH -> DeviceMetricsConfig.performance()
}
```

### 3. Monitor Metric Capture Cost

```kotlin
val meter = openTelemetry.getMeter("metrics-monitoring")

val captureCounter = meter.counterBuilder("device_metrics.capture_count")
    .setDescription("Number of times device metrics were captured")
    .build()

// Track captures
captureCounter.add(1, Attributes.of(
    AttributeKey.stringKey("reason"), "crash"
))
```

### 4. Export Metrics to Dashboard

Use captured metrics to create dashboards:

- **Crash Rate by Memory Level**: Identify memory-related crashes
- **Error Rate by Network Type**: Spot connectivity issues
- **Performance by Thermal State**: Detect throttling problems

## Related Documentation

- [Workflow System](./WORKFLOW_SYSTEM.md) - Trigger metrics via workflows
- [Export Modes](./EXPORT_MODES.md) - When metrics are exported
- [Sampling](./SAMPLING.md) - Control trace sampling

---

**Recommended**: Use default configuration for crash scenarios, minimal for production
