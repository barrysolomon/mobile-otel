# instrumentation-back-press

**Status:** Production
**Namespace:** `io.opentelemetry.android.mobile.instrumentation.backpress`
**Class:** `BackPressInstrumentation`

Captures hardware/gesture back-navigation events. Useful for understanding navigation funnels and "rage-back" patterns (multiple back-presses in succession).

## What it emits

- `ui.back_press` child spans
- Fires on `KEYCODE_BACK ACTION_UP` via `WindowEventHub`

## How it's wired

Auto-enabled. No app-side wiring needed.

## Test

```bash
cd ../../examples/demo-app
./gradlew :instrumentation-back-press:test
```
