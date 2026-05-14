# otel-android-mobile-core

Shared foundations that every Android instrumentation module depends on. Not consumed directly by app code — depend on `otel-android-mobile` (the public SDK) instead.

## What's in here

| Package | Purpose |
|---------|---------|
| `core/` | `PiiScrubber` and other cross-cutting utilities |
| `breadcrumb/` | `JourneyBreadcrumb` — the ring buffer of recent user actions attached to crashes/errors |
| `instrumentation/` | `MobileInstrumentation` interface, `InstrumentationContext` DI container, `WindowEventHub` fan-out dispatcher, `WindowEventHubInstaller`, `@Incubating` annotation |
| `navigation/` | `NavigationInstrumentation`, `FragmentLifecycleInstrumentation` — page-span lifecycle hooks reused by `instrumentation/screen/` and the Compose navigation module |

## Why a separate module

The instrumentation modules under `instrumentation/<name>/` each `api(project(":otel-android-mobile-core"))` so they can implement `MobileInstrumentation` without pulling in the whole SDK. Keeps the dependency graph one-directional: `otel-android-mobile` → `<modules>` → `otel-android-mobile-core`.

## Build & test

Built through `examples/demo-app/` (no standalone `gradlew`):

```bash
cd ../examples/demo-app
./gradlew :otel-android-mobile-core:test
./gradlew :otel-android-mobile-core:lint
```

## See also

- [otel-android-mobile/README.md](../otel-android-mobile/README.md) — the public SDK that consumers depend on
- [docs/ARCHITECTURE_OVERVIEW.md](../docs/ARCHITECTURE_OVERVIEW.md) — where this module sits
