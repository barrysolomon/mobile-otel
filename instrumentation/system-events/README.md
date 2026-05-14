# instrumentation-system-events

**Status:** Incubating
**Namespace:** `io.opentelemetry.android.mobile.instrumentation.systemevents`
**Class:** `SystemEventsInstrumentation`

Surfaces system-level state changes (battery, power, airplane mode, storage) via `BroadcastReceiver`. Drives the `predictive_risk` and `battery_drain` policy matchers.

## What it emits

| Event | Trigger |
|-------|---------|
| `device.battery.low` | `ACTION_BATTERY_LOW` |
| `device.battery.okay` | `ACTION_BATTERY_OKAY` |
| `device.power.connected` | `ACTION_POWER_CONNECTED` |
| `device.power.disconnected` | `ACTION_POWER_DISCONNECTED` |
| `device.airplane_mode` | `ACTION_AIRPLANE_MODE_CHANGED` |
| `device.storage.low` | `ACTION_DEVICE_STORAGE_LOW` |

## How it's wired

Opt-in:

```kotlin
MobileOtel.initialize(this, MobileConfig(
    systemEventsConfig = SystemEventsConfig(enabled = true)
))
```

The instrumentation registers a `BroadcastReceiver` for the actions above. No manifest entry required — registrations are runtime.

## Test

```bash
cd ../../examples/demo-app
./gradlew :instrumentation-system-events:test
```
