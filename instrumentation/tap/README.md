# instrumentation-tap

**Status:** Production
**Namespace:** `io.opentelemetry.android.mobile.instrumentation.tap`
**Class:** `TapInstrumentation`

Captures tap, long-press, and swipe gestures across the whole app via `WindowEventHub`. Emits OTel log records and zero-duration child spans nested under the active page span.

## What it emits

| Event | Trigger | Default |
|-------|---------|---------|
| `ui.tap` | Single tap (ACTION_DOWN → ACTION_UP, no movement) | On |
| `ui.long_press` | Held > GestureDetector long-press threshold | On |
| `ui.swipe` | Movement > `swipeMinDistancePx` (default 50px) | On |

Each event carries `target.id`, `target.text` (PII-scrubbed), `target.class`, plus coordinates.

## How it's wired

Auto-enabled. Relies on `WindowEventHubInstaller` (registered by the SDK at start) to intercept `Window.Callback`.

## Config

```kotlin
MobileOtel.initialize(this, MobileConfig(
    tapConfig = TapConfig(
        enabled = true,
        captureSwipes = true,
        addSpanEvents = true,        // nests taps as child spans under the page span
        swipeMinDistancePx = 50,
    )
))
```

## Test

```bash
cd ../../examples/demo-app
./gradlew :instrumentation-tap:test
```

## See also

- [docs/AUTO_INSTRUMENTATION.md](../../docs/AUTO_INSTRUMENTATION.md)
