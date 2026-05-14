# instrumentation-compose-navigation

**Status:** Incubating
**Namespace:** `io.opentelemetry.android.mobile.instrumentation.composenavigation`

Page-span lifecycle for `androidx.navigation.compose` (the `NavController` + `NavHost` pattern). Companion to `instrumentation-screen/` which handles Activity/Fragment-based navigation.

## What it emits

- `page.<routeName>` parent spans, started on `NavController.OnDestinationChangedListener` callbacks
- `ui.screen_view` log records on each destination change

## How it's wired

Opt-in:

```kotlin
MobileOtel.initialize(this, MobileConfig(
    composeNavigationConfig = ComposeNavigationConfig(enabled = true)
))
```

The instrumentation auto-discovers `NavController` instances through the Compose lifecycle. No app-side `OnDestinationChangedListener` registration needed.

## Test

```bash
cd ../../examples/demo-app
./gradlew :instrumentation-compose-navigation:test
```

## See also

- [instrumentation/screen/README.md](../screen/README.md) — Activity/Fragment equivalent
- [instrumentation/compose-click/README.md](../compose-click/README.md) — Compose tap counterpart
