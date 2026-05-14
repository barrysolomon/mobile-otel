# instrumentation-screen-orientation

**Status:** Incubating
**Namespace:** `io.opentelemetry.android.mobile.instrumentation.screenorientation`

Captures device rotation. Useful for diagnosing layout bugs and tablet/foldable-specific issues.

## What it emits

- `device.orientation_change` log records
- Attributes: `orientation` (`portrait` / `landscape`), `rotation_degrees`

## How it's wired

Opt-in:

```kotlin
MobileOtel.initialize(this, MobileConfig(
    screenOrientationConfig = ScreenOrientationConfig(enabled = true)
))
```

Hooks `Application.registerComponentCallbacks` for `onConfigurationChanged`.

## Test

```bash
cd ../../examples/demo-app
./gradlew :instrumentation-screen-orientation:test
```
