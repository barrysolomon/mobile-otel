# instrumentation-screenshot

**Status:** Incubating
**Namespace:** `io.opentelemetry.android.mobile.instrumentation.screenshot`
**Class:** `ScreenshotInstrumentation`

Periodically captures a redacted screenshot for journey replay in the control plane. Uses `PixelCopy` on API 24+ and falls back to `View.draw()` otherwise.

## What it emits

- `ui.screenshot` log records with a base64-encoded data URL payload
- Attributes: `screenshot.width`, `screenshot.height`, `screenshot.size_bytes`, `screenshot.format`

## How it's wired

Opt-in via config:

```kotlin
MobileOtel.initialize(this, MobileConfig(
    screenshotConfig = ScreenshotConfig(
        enabled = true,
        redactText = true,
        quality = 50,                  // JPEG quality 0–100
        maxWidth = 720,                // downscale for size
        captureOn = setOf(ScreenshotTrigger.SCREEN_CHANGE, ScreenshotTrigger.ERROR),
    )
))
```

Rate-limited via shared `RateLimiter`.

## Privacy

`redactText = true` walks the view tree and replaces `TextView` contents with `█` glyphs before capture. Use this in production.

## Test

```bash
cd ../../examples/demo-app
./gradlew :instrumentation-screenshot:test
```

## See also

- [instrumentation/wireframe/README.md](../wireframe/README.md) — lighter-weight alternative (~1-5KB JSON)
