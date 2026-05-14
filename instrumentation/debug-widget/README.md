# instrumentation-debug-widget

**Status:** Incubating (developer-only)
**Namespace:** `io.opentelemetry.android.mobile.instrumentation.debug`

In-app overlay that renders live SDK state — buffer counts, last export time, device-health gauges, recent events — on top of the running app. **Do not ship enabled in production.** Useful during meetings, on-device debugging, and recording demos.

## What it shows

- RAM + disk buffer counts (live)
- Export status (last success, last failure, retry queue size)
- Active export policy + last policy match
- Device health (memory, battery, jank) sampled from the Vitals module

## How it's wired

Opt-in only:

```kotlin
MobileOtel.initialize(this, MobileConfig(
    debugWidgetConfig = DebugWidgetConfig(enabled = true)
))
```

Renders via `WindowManager` overlay. Requires `SYSTEM_ALERT_WINDOW` permission on API 23+ if you want it over other apps; otherwise it stays inside your own activity.

## Test

```bash
cd ../../examples/demo-app
./gradlew :instrumentation-debug-widget:test
```

## See also

- [tools/dcc-tui/README.md](../../tools/dcc-tui/README.md) — TUI alternative running from your terminal
