# instrumentation-vitals

**Status:** Production
**Namespace:** `io.opentelemetry.android.mobile.instrumentation.vitals`
**Class:** `VitalsInstrumentation`

Device-health metrics emitted via OTel `Meter` (gauges + histograms). This is the source of the `device.*` series in Dash0 dashboards.

## What it emits

| Metric | Type | Notes |
|--------|------|-------|
| `device.memory.heap_used` | Gauge | JVM heap utilization |
| `device.memory.native_used` | Gauge | Native heap (PSS) |
| `device.battery.level` | Gauge | 0–100 |
| `device.battery.charging` | Gauge | 0/1 |
| `device.jank.frames_dropped` | Counter | Frames > 16ms |
| `device.app_start.duration` | Histogram | Cold/warm start time |

## How it's wired

Auto-enabled. Sampling cadence default 30s. Uses the standard OTel Metric SDK.

## Config

```kotlin
MobileOtel.initialize(this, MobileConfig(
    vitalsConfig = VitalsConfig(
        enabled = true,
        sampleIntervalMs = 30_000,
    )
))
```

## Test

```bash
cd ../../examples/demo-app
./gradlew :instrumentation-vitals:test
```

## See also

- [docs/DEVICE_METRICS.md](../../docs/DEVICE_METRICS.md) — full metric catalog
