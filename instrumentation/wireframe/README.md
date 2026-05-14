# instrumentation-wireframe

**Status:** Incubating
**Namespace:** `io.opentelemetry.android.mobile.instrumentation.wireframe`
**Class:** `WireframeInstrumentation`

Lightweight alternative to screenshots: captures the view hierarchy as JSON (~1–5 KB) for journey replay in the control plane. Renderable in the UI as a wireframe sketch.

## What it emits

- `ui.wireframe` log records with JSON payload
- Fires on: screen transition, tap, error (configurable)

## How it's wired

Opt-in:

```kotlin
MobileOtel.initialize(this, MobileConfig(
    wireframeConfig = WireframeConfig(
        enabled = true,
        captureOn = setOf(WireframeTrigger.SCREEN_CHANGE, WireframeTrigger.TAP),
        maxDepth = 12,
    )
))
```

## Why pick this over screenshot

- ~5 KB vs ~50–500 KB per capture
- Auto-redacted (no rasterized text)
- Renders fast in the replay UI

## Test

```bash
cd ../../examples/demo-app
./gradlew :instrumentation-wireframe:test
```

## See also

- [instrumentation/screenshot/README.md](../screenshot/README.md) — rasterized alternative
