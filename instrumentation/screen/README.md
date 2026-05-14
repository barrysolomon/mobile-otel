# instrumentation-screen

**Status:** Production
**Namespace:** `io.opentelemetry.android.mobile.instrumentation.screen`
**Class:** `ScreenViewInstrumentation`

Page-span lifecycle: starts a `page.<ScreenName>` span when a screen becomes visible, ends it when it leaves. All UI events emitted while that page is current (taps, scrolls, text-input) are nested as child spans under it.

## What it emits

- `ui.screen_view` log records
- `page.<ScreenName>` parent spans (active for the duration the screen is visible)

## How it's wired

Auto-enabled. Hooks `Activity` and `Fragment` lifecycle via `LifecycleInstrumentation`, then makes the page span current on the main thread so `Context.current()` picks it up as parent for child spans.

## Why the page span matters

Without it, taps and network calls would be parentless / siblings. With it, you get a clean trace tree: `journey → page → tap` that's queryable in Dash0 by `page.name`.

## Test

```bash
cd ../../examples/demo-app
./gradlew :instrumentation-screen:test
```

## See also

- [instrumentation/lifecycle/README.md](../lifecycle/README.md)
- [instrumentation/tap/README.md](../tap/README.md) — child spans nested under page spans
