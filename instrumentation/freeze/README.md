# instrumentation-freeze

**Status:** Production
**Namespace:** `io.opentelemetry.android.mobile.instrumentation.freeze`
**Class:** `FreezeInstrumentation`

ANR (Application Not Responding) detection. Runs a Looper watchdog on a background thread that pings the main thread; if a ping doesn't return within the threshold (default 5s), it emits a freeze event.

## What it emits

- `ui.freeze` log records with main-thread stack trace
- Default threshold: 5000ms (matches Android system ANR window)

## How it's wired

Auto-enabled. The watchdog runs on a dedicated background thread for the lifetime of the process.

## Config

```kotlin
MobileOtel.initialize(this, MobileConfig(
    freezeConfig = FreezeConfig(
        enabled = true,
        thresholdMs = 5000,
    )
))
```

## Test

```bash
cd ../../examples/demo-app
./gradlew :instrumentation-freeze:test
```
