# instrumentation-scroll

**Status:** Production
**Namespace:** `io.opentelemetry.android.mobile.instrumentation.scroll`
**Class:** `ScrollInstrumentation`

Throttled scroll telemetry for `RecyclerView` (and any view exposing `OnScrollChangeListener`). One span per directional change, not per dy.

## What it emits

- `ui.scroll` child spans nested under the active page span
- Attributes: `scroll.direction` (`up` / `down`), `scroll.distance_px`, `target.id`

## How it's wired

Auto-enabled. The SDK walks the view tree for `RecyclerView` instances on activity-resume and attaches an `OnScrollListener`.

## Config

```kotlin
MobileOtel.initialize(this, MobileConfig(
    scrollConfig = ScrollConfig(
        enabled = true,
        throttleMs = 250,
    )
))
```

## Test

```bash
cd ../../examples/demo-app
./gradlew :instrumentation-scroll:test
```
