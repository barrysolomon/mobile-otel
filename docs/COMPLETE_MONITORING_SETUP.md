# Complete Mobile Monitoring Setup

Quick start guide for setting up comprehensive mobile observability with sampling, device metrics, and log tailing.

## Overview

This guide shows how to configure all advanced features:
- ✅ **Sampling**: Control trace volume (5-10% baseline, 100% for critical events)
- ✅ **Device Metrics**: Capture device state on triggers (crash, error, app start, force close)
- ✅ **Log Tailing**: Keep recent logs for pattern detection
- ✅ **Lifecycle Monitoring**: Track app start, foreground, background, force close

## Quick Setup (5 Minutes)

### 1. Application Class Setup

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Configure complete monitoring
        val config = MobileConfig(
            serviceName = "my-app",
            serviceVersion = "1.0.0",
            collectorEndpoint = "https://collector.prod.com:4317",
            exportMode = ExportMode.CONDITIONAL,  // Battery-efficient

            // Sampling: 5% baseline, 100% for high-priority traces
            samplingConfig = SamplingConfig.dynamic(
                normalRate = 0.05,
                highPriorityRate = 1.0
            ),

            // Device Metrics: Capture on all important events
            deviceMetricsConfig = DeviceMetricsConfig.default(),
            deviceMetricsCaptureConfig = DeviceMetricsCaptureConfig(
                onAppStart = true,      // ✅ Track startup state
                onForceClose = true,    // ✅ Detect force close
                onCrash = true,         // ✅ Capture crash context
                onError = true,         // ✅ Capture error context
                onWorkflowTrigger = true,
                rateLimitSeconds = 60
            ),

            // Log Tailing: Keep last 100 logs
            logTailingConfig = LogTailingConfig(
                tailSize = 100,
                includeDebugLogs = false,
                includeInfoLogs = true,
                includeErrorLogs = true
            )
        )

        // Initialize OTEL
        val loggerProvider = MobileLoggerProvider.getInstance(this, config)
        val logger = loggerProvider.get("lifecycle")
        val meter = loggerProvider.getOpenTelemetrySdk().getMeter("device-metrics")

        // Create device metrics collector
        val metricsCollector = DeviceMetricsCollector(
            context = this,
            meter = meter,
            config = config.deviceMetricsConfig
        )

        // Register lifecycle monitoring
        AppLifecycleDetector.register(
            app = this,
            logger = logger,
            captureConfig = config.deviceMetricsCaptureConfig,
            metricsCollector = metricsCollector
        )

        Log.i("MyApp", "Complete monitoring initialized")
    }
}
```

### 2. Register Application in Manifest

```xml
<application
    android:name=".MyApp"
    android:theme="@style/AppTheme"
    ...>
</application>
```

### 3. Log Events Normally

```kotlin
class MainActivity : AppCompatActivity() {
    private val logger = MobileLoggerProvider.getInstanceOrNull()?.get("MainActivity")
    private val tracer = MobileLoggerProvider.getInstanceOrNull()
        ?.getOpenTelemetrySdk()?.getTracer("MainActivity")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Log events
        logger?.logRecordBuilder()
            .setBody("user.action")
            .setSeverity(Severity.INFO)
            .setAllAttributes(Attributes.of(
                AttributeKey.stringKey("action"), "button_click"
            ))
            .emit()

        // Create traces (with sampling)
        val span = tracer?.spanBuilder("api.request")
            ?.setAttribute("sampling.priority", "high")  // Force sampling
            ?.startSpan()

        try {
            // Make API call
            span?.makeCurrent()?.use {
                makeApiCall()
            }
            span?.setStatus(StatusCode.OK)
        } catch (e: Exception) {
            span?.recordException(e)
            span?.setStatus(StatusCode.ERROR)
        } finally {
            span?.end()
        }
    }

    // Log API errors with status codes (for trigger detection)
    private fun logApiError(statusCode: Int, endpoint: String) {
        logger?.logRecordBuilder()
            .setBody("http.error")
            .setSeverity(Severity.ERROR)
            .setAllAttributes(Attributes.of(
                AttributeKey.longKey("http.status_code"), statusCode.toLong(),
                AttributeKey.stringKey("http.endpoint"), endpoint,
                AttributeKey.stringKey("http.method"), "GET"
            ))
            .emit()

        // Triggers automatically evaluated:
        // - LogTailTrigger.onApiError() → 4xx/5xx
        // - LogTailTrigger.onServerError() → 5xx only
        // - LogTailTrigger.onRepeatedApiErrors() → 3+ errors in sequence
    }
}
```

## What You Get

### 1. Automatic App Lifecycle Tracking

```
Events logged automatically:
- app.start (with device metrics)
- app.foreground
- app.background
- app.force_close (detected on next start)
```

### 2. Device Metrics on Trigger Events

```
On crash/error/force close, captures:
- Memory: used, available, low memory state
- Battery: level, charging, temperature, health
- CPU: cores, architecture
- Network: type, connectivity
- Storage: used, available, cache
- Thermal: throttling state
- System: OS version, device model, uptime
- App: version, install time, foreground state
```

### 3. Trace Sampling

```
- 5% of traces sampled normally (battery-efficient)
- 100% sampling for high-priority traces
- Runtime adjustable via workflows
```

### 4. Log Tailing

```
- Last 100 logs kept in memory
- Pattern detection (e.g., 3 errors → trigger)
- Full context before crash
```

## Production Configuration

### Recommended: Balanced Production Setup

```kotlin
val config = MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "https://ingress.us.dash0.com:4317",
    exportMode = ExportMode.CONDITIONAL,

    // Sampling: Low baseline, high for critical
    samplingConfig = SamplingConfig.dynamic(
        normalRate = 0.05,      // 5% baseline
        highPriorityRate = 1.0  // 100% critical
    ),

    // Device Metrics: Only on critical events
    deviceMetricsConfig = DeviceMetricsConfig.default(),
    deviceMetricsCaptureConfig = DeviceMetricsCaptureConfig(
        onAppStart = true,
        onForceClose = true,
        onCrash = true,
        onError = true,
        onManualFlush = false,      // ❌ Skip manual (too frequent)
        onScheduledFlush = false,   // ❌ Skip scheduled (battery)
        rateLimitSeconds = 60
    ),

    // Log Tailing: Medium size
    logTailingConfig = LogTailingConfig(
        tailSize = 50,  // 50 logs (~25 KB)
        includeDebugLogs = false,
        includeInfoLogs = true,
        includeErrorLogs = true
    ),

    headers = mapOf(
        "Authorization" to "Bearer ${DASH0_TOKEN}",
        "Dash0-Dataset" to "mobile-prod"
    )
)
```

### Aggressive: Maximum Visibility (Dev/Beta)

```kotlin
val config = MobileConfig(
    samplingConfig = SamplingConfig.alwaysOn(),  // 100% sampling
    deviceMetricsConfig = DeviceMetricsConfig.default(),
    deviceMetricsCaptureConfig = DeviceMetricsCaptureConfig.aggressive(),
    logTailingConfig = LogTailingConfig.verbose()  // Include DEBUG
)
```

### Conservative: Minimal Impact (Low-End Devices)

```kotlin
val config = MobileConfig(
    samplingConfig = SamplingConfig.production(rate = 0.01),  // 1% sampling
    deviceMetricsConfig = DeviceMetricsConfig.minimal(),
    deviceMetricsCaptureConfig = DeviceMetricsCaptureConfig.conservative(),
    logTailingConfig = LogTailingConfig.small()  // 20 logs
)
```

## Workflow Integration

### Example: HTTP Error Response

```json
{
  "id": "http-error-response",
  "name": "HTTP Error with Full Context",
  "trigger": {
    "event": "http.error",
    "where": [{"attr": "http.status_code", "op": ">=", "value": 500}]
  },
  "actions": [
    {
      "type": "set_sampling",
      "rate": 1.0,
      "duration_minutes": 10
    },
    {
      "type": "flush_window",
      "minutes": 10
    },
    {
      "type": "flush_tail",
      "include_last_n_logs": 50
    },
    {
      "type": "capture_device_metrics"
    }
  ]
}
```

**Result on HTTP 500 error**:
1. ✅ Increase sampling to 100% for 10 minutes
2. ✅ Flush last 10 minutes of events
3. ✅ Flush last 50 logs from tail
4. ✅ Capture device metrics snapshot

## Verification

### 1. Check Logs

```bash
adb logcat | grep -E "AppLifecycleDetector|DeviceMetricsCollector|DynamicSampler"
```

**Expected output**:
```
AppLifecycleDetector: App started: first_launch=false
DeviceMetricsCollector: Captured metrics: reason=APP_START
DynamicSampler: Current sampling rate: 0.05 (5%)
```

### 2. Verify Lifecycle Events

```kotlin
// In your Activity
val lifecycleDetector = AppLifecycleDetector.getInstance()
val isInForeground = lifecycleDetector?.isAppInForeground()
val sessionDuration = lifecycleDetector?.getSessionDuration()

Log.i("MyApp", "Foreground: $isInForeground, Session: ${sessionDuration}ms")
```

### 3. Check Device Metrics

```kotlin
val metricsCollector = DeviceMetricsCollector(context, meter, config)
val availableMemory = metricsCollector.getAvailableMemoryMb()
val batteryLevel = metricsCollector.getBatteryLevel()

Log.i("MyApp", "Memory: ${availableMemory}MB, Battery: ${batteryLevel}%")
```

### 4. Test Force Close Detection

1. Launch app
2. Force close (swipe away from recent apps)
3. Relaunch app
4. Check logs for `app.force_close` event

**Expected**:
```
AppLifecycleDetector: Force close detected: app did not shut down cleanly
time_since_force_close_ms = 120000
DeviceMetricsCollector: Captured metrics: reason=FORCE_CLOSE
```

## Environment-Specific Configuration

### Development

```kotlin
val devConfig = MobileConfig(
    collectorEndpoint = "http://10.0.2.2:4317",  // Local collector
    exportMode = ExportMode.CONTINUOUS,           // Real-time export
    samplingConfig = SamplingConfig.alwaysOn(),  // 100% sampling
    deviceMetricsConfig = DeviceMetricsConfig.default(),
    logTailingConfig = LogTailingConfig.verbose()
)
```

### Staging

```kotlin
val stagingConfig = MobileConfig(
    collectorEndpoint = "https://staging-collector:4317",
    exportMode = ExportMode.HYBRID,
    samplingConfig = SamplingConfig.production(rate = 0.25),  // 25% sampling
    deviceMetricsConfig = DeviceMetricsConfig.default(),
    logTailingConfig = LogTailingConfig.default()
)
```

### Production

```kotlin
val prodConfig = MobileConfig(
    collectorEndpoint = "https://ingress.us.dash0.com:4317",
    exportMode = ExportMode.CONDITIONAL,
    samplingConfig = SamplingConfig.dynamic(0.05, 1.0),
    deviceMetricsConfig = DeviceMetricsConfig.default(),
    deviceMetricsCaptureConfig = DeviceMetricsCaptureConfig.default(),
    logTailingConfig = LogTailingConfig.medium()
)
```

## Monitoring Your Monitoring

Track the monitoring system itself:

```kotlin
val meter = openTelemetry.getMeter("monitoring-meta")

// Track metric captures
val metricCaptureCounter = meter.counterBuilder("device_metrics.capture_count")
    .setDescription("Device metric capture count")
    .build()

// Track sampling rate changes
val samplingRateGauge = meter.gaugeBuilder("trace.sampling_rate")
    .setDescription("Current trace sampling rate")
    .ofDoubles()
    .build()

// Track tail buffer size
val tailSizeGauge = meter.gaugeBuilder("log_tail.size")
    .setDescription("Log tail buffer size")
    .ofLongs()
    .build()
```

## Troubleshooting

### Issue: No lifecycle events

**Solution**: Verify Application class is registered in manifest

```xml
<application android:name=".MyApp" ...>
```

### Issue: Force close not detected

**Solution**: Ensure clean shutdown is called

```kotlin
override fun onTerminate() {
    super.onTerminate()
    AppLifecycleDetector.getInstance()?.markCleanShutdown()
}
```

### Issue: Device metrics not captured

**Solution**: Check configuration and rate limiting

```kotlin
deviceMetricsCaptureConfig = DeviceMetricsCaptureConfig(
    onAppStart = true,  // ✅ Enable
    rateLimitSeconds = 30  // Lower rate limit for testing
)
```

### Issue: Sampling rate not changing

**Solution**: Verify DynamicSampler is configured

```kotlin
val currentRate = loggerProvider.getCurrentSamplingRate()
Log.i("Sampling", "Current rate: $currentRate")
```

## Best Practices

1. **Use CONDITIONAL mode in production** (battery-efficient)
2. **Enable app start + force close tracking** (critical insights)
3. **Set 5-10% baseline sampling** (cost-effective)
4. **Keep 50-100 log tail** (good context with low memory)
5. **Rate limit metrics to 60s** (prevent excessive capture)
6. **Use workflows for dynamic behavior** (increase sampling on errors)

## Next Steps

- [Sampling Guide](./SAMPLING.md) - Detailed sampling configuration
- [Device Metrics Guide](./DEVICE_METRICS.md) - All device metrics
- [Log Tailing Guide](./LOG_TAILING.md) - Pattern detection
- [Workflow System](./WORKFLOW_SYSTEM.md) - Trigger automation

---

**Result**: Production-grade mobile observability with minimal battery impact
