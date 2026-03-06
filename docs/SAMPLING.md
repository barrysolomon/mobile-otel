# OpenTelemetry Sampling for Mobile

Comprehensive guide to trace and span sampling in the Mobile OTEL SDK, following OpenTelemetry specifications.

## Overview

**Sampling** is the process of deciding which traces and spans to record and export. On mobile devices, sampling is critical for:

- **Battery Life**: Recording all traces consumes CPU and battery
- **Bandwidth**: Exporting all data uses cellular data and battery
- **Storage**: Buffering all spans fills RAM and disk
- **Backend Cost**: Processing all traces costs money
- **Data Volume**: High-traffic apps generate millions of spans

The Mobile OTEL SDK implements **OpenTelemetry-standard sampling** with mobile-specific enhancements.

## Sampling Standards

Follows [OpenTelemetry Trace Sampling Specification](https://opentelemetry.io/docs/specs/otel/trace/sdk/#sampling):

- **Trace ID Ratio-Based**: Consistent sampling based on trace ID
- **Always On/Off**: 100% or 0% sampling
- **Parent-Based**: Inherit sampling decision from parent span
- **Dynamic**: Mobile-specific runtime adjustable sampling

## Sampling Strategies

### 1. ALWAYS_ON (100% Sampling)

**Use Case**: Development, debugging, critical user flows

```kotlin
val config = MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "http://collector:4317",
    samplingConfig = SamplingConfig.alwaysOn()
)
```

**Characteristics**:
- ✅ Records all traces
- ✅ Complete visibility
- ❌ High battery usage
- ❌ High bandwidth usage
- ❌ High backend cost

**Best For**: Development environments, manual testing, critical flows (checkout, payments)

### 2. ALWAYS_OFF (0% Sampling)

**Use Case**: Temporarily disable tracing

```kotlin
val config = MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "http://collector:4317",
    samplingConfig = SamplingConfig.alwaysOff()
)
```

**Characteristics**:
- ✅ No battery overhead
- ✅ No bandwidth usage
- ❌ No trace visibility

**Best For**: Emergency kill switch, A/B testing (control group), specific app versions

### 3. TRACE_ID_RATIO (Probability-Based)

**Use Case**: Production with controlled data volume

```kotlin
val config = MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "http://collector:4317",
    samplingConfig = SamplingConfig.production(rate = 0.1)  // 10% sampling
)
```

**Characteristics**:
- ✅ Predictable sampling percentage
- ✅ Battery-friendly
- ✅ Consistent decisions across distributed systems
- ✅ Statistical validity with proper sample size

**How It Works**:
1. Hash the trace ID (deterministic)
2. Convert to 0.0-1.0 range
3. Sample if value < sampling rate

**Best For**: Production apps, statistical monitoring, SLO tracking

### 4. PARENT_BASED (Distributed Tracing)

**Use Case**: Microservices, client-server apps

```kotlin
val config = MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "http://collector:4317",
    samplingConfig = SamplingConfig.parentBased(rootRate = 0.1)
)
```

**Characteristics**:
- ✅ Consistent with upstream services
- ✅ Complete distributed traces (no partial traces)
- ✅ Respects backend sampling decisions

**How It Works**:
1. **Root span** (no parent): Sample based on root sampler (e.g., 10%)
2. **Child span** (has parent): Inherit parent's sampling decision

**Best For**: Mobile apps calling backend APIs, microservice architectures

### 5. DYNAMIC (Adaptive Sampling) ⭐ Recommended

**Use Case**: Mobile production with adaptive behavior

```kotlin
val config = MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "http://collector:4317",
    samplingConfig = SamplingConfig.dynamic(
        normalRate = 0.05,      // 5% baseline
        highPriorityRate = 1.0  // 100% for critical events
    )
)
```

**Characteristics**:
- ✅ Low baseline sampling (battery-efficient)
- ✅ High sampling for critical events (error traces captured)
- ✅ Runtime adjustable (workflow actions)
- ✅ Temporary rate increases (revert after duration)

**How It Works**:
1. **Baseline**: Sample at low rate (e.g., 5%)
2. **High-Priority**: Sample at 100% when `sampling.priority = "high"` attribute present
3. **Workflow Trigger**: Increase rate temporarily (e.g., 100% for 10 min after crash)
4. **Auto-Revert**: Return to baseline after scheduled duration

**Best For**: Mobile production apps (recommended default)

## Configuration

### Basic Configuration

```kotlin
// Development: 100% sampling
val devConfig = MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0-dev",
    collectorEndpoint = "http://10.0.2.2:4317",
    samplingConfig = SamplingConfig.alwaysOn()
)

// Production: 10% sampling
val prodConfig = MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "https://collector.prod.com:4317",
    samplingConfig = SamplingConfig.production(rate = 0.1)
)

// Dynamic: 5% baseline, 100% high-priority
val config = MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "https://collector.prod.com:4317",
    samplingConfig = SamplingConfig.dynamic(normalRate = 0.05, highPriorityRate = 1.0)
)
```

### Advanced Configuration

```kotlin
val config = MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "https://collector.prod.com:4317",
    samplingConfig = SamplingConfig(
        strategy = SamplingStrategy.DYNAMIC,
        samplingRate = 0.05,                    // 5% baseline
        highPrioritySamplingRate = 1.0,         // 100% high-priority
        parentBasedRoot = SamplingStrategy.TRACE_ID_RATIO,
        parentBasedRootSamplingRate = 0.1
    )
)
```

## Marking High-Priority Traces

Use attributes to force sampling of critical traces:

```kotlin
val tracer = openTelemetry.getTracer("my-component")

// High-priority trace (always sampled with dynamic sampler)
val span = tracer.spanBuilder("checkout.payment")
    .setAttribute("sampling.priority", "high")  // Forces sampling
    .startSpan()

try {
    span.makeCurrent().use {
        // Process payment
        processPayment()
    }
    span.setStatus(StatusCode.OK)
} finally {
    span.end()
}
```

**When to Mark High-Priority**:
- Checkout/payment flows
- User authentication
- Critical errors (HTTP 5xx, exceptions)
- Rare events (first-time user, churning user)
- A/B test variants
- VIP users

## Runtime Sampling Adjustment

### Workflow Actions

Workflows can dynamically adjust sampling based on events:

```kotlin
// In workflow action handler
loggerProvider.setSamplingRate(1.0, durationMinutes = 10)  // 100% for 10 min
```

### Example Workflow: HTTP Error Response

```json
{
  "id": "http-error-5xx",
  "trigger": {
    "event": "http.error",
    "where": [{"attr": "http.status_code", "op": ">=", "value": 500}]
  },
  "actions": [
    {"type": "set_sampling", "rate": 1.0, "duration_minutes": 10},
    {"type": "flush_window", "minutes": 5}
  ]
}
```

**Result**: After HTTP 500 error, sample 100% of traces for 10 minutes, then revert to baseline.

### Manual Adjustment

```kotlin
val loggerProvider = MobileLoggerProvider.getInstance(context, config)

// Increase sampling to 100% for debugging
loggerProvider.setSamplingRate(1.0)

// Increase sampling to 50% for 5 minutes
loggerProvider.setSamplingRate(0.5, durationMinutes = 5)

// Reset to baseline
loggerProvider.resetSamplingToBaseline()

// Check current rate
val currentRate = loggerProvider.getCurrentSamplingRate()  // e.g., 0.05
```

### Via MobileOtel facade (no restart required)

`MobileOtel.getProvider().setSamplingRate(rate)` is the preferred call site when you don't hold a direct reference to the logger provider. Changes take effect immediately for all new spans:

```kotlin
// Live update — new spans use this rate immediately
MobileOtel.getProvider().setSamplingRate(0.5)
```

The demo app exposes this as a drag slider in **Profile → OTel Config**. The slider calls `setSamplingRate()` on every change event, so the rate updates in real time as you drag — no restart needed.

## Sampling Decision Attributes

Sampling decisions are recorded as span attributes (following OTEL spec):

```
sampling.rate = 0.1                  // Applied sampling rate
sampling.strategy = "dynamic"        // Sampler type
sampling.high_priority = true        // High-priority flag (if applicable)
```

These attributes are useful for:
- Debugging sampling behavior
- Analyzing sampled vs. dropped traces
- Backend adjustments and filtering

## Best Practices

### 1. Choose the Right Strategy

| Environment | Strategy | Rate | Reason |
|-------------|----------|------|--------|
| Development | ALWAYS_ON | 100% | Full visibility for debugging |
| Staging | DYNAMIC | 10% baseline | Test production behavior |
| Production | DYNAMIC | 5-10% baseline | Balance cost and observability |
| Beta Testing | TRACE_ID_RATIO | 25% | Higher visibility for new features |

### 2. Start Conservative

- **Production**: Start with 5% sampling
- **Monitor backend cost** and adjust upward if needed
- **Monitor battery usage** and adjust downward if complaints

### 3. Use High-Priority Sampling

```kotlin
// Mark critical flows as high-priority
span.setAttribute("sampling.priority", "high")

// Examples:
// - Checkout flow
// - Payment processing
// - User authentication
// - Error scenarios
```

### 4. Combine with Conditional Export

```kotlin
val config = MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "https://collector.prod.com:4317",
    exportMode = ExportMode.CONDITIONAL,      // Only export on triggers
    samplingConfig = SamplingConfig.dynamic(
        normalRate = 0.05,
        highPriorityRate = 1.0
    )
)
```

**Benefits**:
- Sample 5% of traces
- Only export when triggered (errors, crashes)
- Maximum battery efficiency

### 5. Monitor Sampling Effectiveness

Track metrics to validate sampling:

```kotlin
val meter = openTelemetry.getMeter("sampling-metrics")

val sampledCounter = meter.counterBuilder("traces.sampled")
    .setDescription("Number of sampled traces")
    .build()

val droppedCounter = meter.counterBuilder("traces.dropped")
    .setDescription("Number of dropped traces")
    .build()
```

## Bundled Configuration

### Include in `assets/otel-config.json`

```json
{
  "serviceName": "my-mobile-app",
  "serviceVersion": "1.0.0",
  "collectorEndpoint": "https://ingress.us.dash0.com:4317",
  "exportMode": "CONDITIONAL",
  "samplingConfig": {
    "strategy": "DYNAMIC",
    "samplingRate": 0.05,
    "highPrioritySamplingRate": 1.0
  }
}
```

### Environment-Specific Sampling

```
android/src/
├── debug/assets/otel-config.json     # 100% sampling
├── staging/assets/otel-config.json   # 10% sampling
└── release/assets/otel-config.json   # 5% sampling
```

## Troubleshooting

### Issue: No Traces Appearing

**Symptoms**: Traces not showing in backend

**Diagnosis**:
1. Check sampling rate: `loggerProvider.getCurrentSamplingRate()`
2. Verify sampler type: Check if ALWAYS_OFF

**Solution**:
```kotlin
// Temporarily enable 100% sampling for testing
loggerProvider.setSamplingRate(1.0)
```

### Issue: Too Many Traces

**Symptoms**: High backend cost, storage issues

**Diagnosis**: Sampling rate too high

**Solution**:
```kotlin
// Reduce sampling rate
val newConfig = config.copy(
    samplingConfig = SamplingConfig.production(rate = 0.01)  // 1%
)
```

### Issue: Missing Critical Errors

**Symptoms**: Error traces not captured

**Diagnosis**: Low sampling rate missed rare errors

**Solution**:
```kotlin
// Mark error spans as high-priority
span.setAttribute("sampling.priority", "high")

// Or use workflow to increase sampling after errors
```

### Issue: Inconsistent Distributed Traces

**Symptoms**: Partial traces in microservices

**Diagnosis**: Mobile and backend using different sampling

**Solution**:
```kotlin
// Use parent-based sampling
val config = config.copy(
    samplingConfig = SamplingConfig.parentBased(rootRate = 0.1)
)
```

## Performance Impact

### Battery Impact by Sampling Rate

| Sampling Rate | Battery Impact | Typical Use Case |
|---------------|----------------|------------------|
| 100% | ~5% overhead | Development only |
| 50% | ~2.5% overhead | Beta testing |
| 10% | ~0.5% overhead | Production monitoring |
| 5% | ~0.25% overhead | Cost-conscious production |
| 1% | ~0.05% overhead | High-traffic apps |

**Note**: Impact varies by app usage patterns. Measure in your app.

### Bandwidth Impact

**Example**: App with 1000 requests/day per user

| Sampling | Traces/Day | Data/User/Day | Monthly (1M users) |
|----------|------------|---------------|---------------------|
| 100% | 1000 | ~500 KB | ~15 TB |
| 10% | 100 | ~50 KB | ~1.5 TB |
| 5% | 50 | ~25 KB | ~750 GB |
| 1% | 10 | ~5 KB | ~150 GB |

## Sampling Algorithm

The OTEL-standard trace ID ratio-based algorithm ensures consistent decisions:

```kotlin
fun shouldSample(traceId: String, rate: Double): Boolean {
    if (rate >= 1.0) return true
    if (rate <= 0.0) return false

    // Take first 16 hex chars (8 bytes) of trace ID
    val traceIdPrefix = traceId.substring(0, 16)
    val traceIdLong = java.lang.Long.parseUnsignedLong(traceIdPrefix, 16)

    // Convert to 0.0-1.0 range
    val traceIdRatio = traceIdLong.toDouble() / Long.MAX_VALUE.toDouble()

    // Sample if ratio < rate
    return traceIdRatio < rate
}
```

**Benefits**:
- **Deterministic**: Same trace ID always yields same decision
- **Distributed**: Mobile and backend make same decision
- **Unbiased**: Statistically random distribution

## Related Documentation

- [Export Modes](./EXPORT_MODES.md) - CONDITIONAL vs CONTINUOUS vs HYBRID
- [Workflow System](./WORKFLOW_SYSTEM.md) - Workflow-triggered sampling adjustments
- [Bundled Config](./BUNDLED_CONFIG.md) - Shipping sampling configuration with app
- [OpenTelemetry Sampling Spec](https://opentelemetry.io/docs/specs/otel/trace/sdk/#sampling)

---

**Recommended Starting Point**: Dynamic sampling with 5% baseline, 100% high-priority, CONDITIONAL export mode
