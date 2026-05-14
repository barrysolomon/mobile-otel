# instrumentation/

All the modular instrumentation that the Android SDK auto-wires (or you opt into). Each module is independently buildable, testable, and consumable — you can include just the ones you need.

## Module catalog

### Production (10) — auto-wired, demo-proven

| Module | Captures | Default |
|--------|----------|---------|
| [lifecycle](lifecycle/) | Activity / fragment lifecycle, `app.foreground` / `app.background` | On |
| [tap](tap/) | Tap, long-press, swipe gestures | On |
| [scroll](scroll/) | `RecyclerView` scroll direction + distance | On |
| [text-input](text-input/) | `EditText` focus events (field metadata, never values) | On |
| [back-press](back-press/) | Hardware / gesture back navigation | On |
| [screen](screen/) | Page-span lifecycle (`page.<ScreenName>`) | On |
| [errors](errors/) | Uncaught exceptions, coroutine errors, `app.crash` / `app.recovery_start` | On |
| [freeze](freeze/) | ANR (main-thread > 5s) | On |
| [vitals](vitals/) | Memory, battery, jank, app-start metrics | On |
| [network](network/) | OkHttp HTTP spans + `http.error` logs | User-wired |

### Incubating (9) — opt-in via config

| Module | Captures | Notes |
|--------|----------|-------|
| [screenshot](screenshot/) | Redacted screen captures (PixelCopy / View.draw) | ~50–500 KB / capture |
| [wireframe](wireframe/) | View-hierarchy JSON tree | ~1–5 KB / capture |
| [compose-click](compose-click/) | Jetpack Compose click events | No-op if Compose not on classpath |
| [compose-navigation](compose-navigation/) | Compose `NavController` page spans | Companion to `screen` |
| [screen-orientation](screen-orientation/) | Device rotation changes | — |
| [debug-widget](debug-widget/) | In-app developer overlay | Do not ship in production |
| [database](database/) | Room / SQLite `QueryCallback` spans | User-wired |
| [file-io](file-io/) | File read/write spans via traced wrapper | User-wired |
| [timber](timber/) | Bridge from Timber logs to OTel | User-wired |
| [system-events](system-events/) | Battery, power, airplane mode, storage | Opt-in |
| [amplify-datastore](amplify-datastore/) | Amplify outbox / sync / conflicts | Opt-in, for offline-first apps |

## How modules fit together

```
            ┌─────────────────────────────────────────┐
            │  otel-android-mobile (public SDK)       │
            │  MobileOtel.initialize(MobileConfig)    │
            └──────────────────┬──────────────────────┘
                               │ wires every module via
                               │ InstrumentationRegistry
                               ▼
            ┌─────────────────────────────────────────┐
            │  Each module implements                  │
            │  MobileInstrumentation { install() }    │
            └──────────────────┬──────────────────────┘
                               │ depends on
                               ▼
            ┌─────────────────────────────────────────┐
            │  otel-android-mobile-core               │
            │  - WindowEventHub (fan-out)             │
            │  - InstrumentationContext (DI)          │
            │  - PiiScrubber, RateLimiter             │
            └─────────────────────────────────────────┘
```

Every module:

1. Implements `MobileInstrumentation` from `otel-android-mobile-core`
2. Receives an `InstrumentationContext` (carrying `OpenTelemetry`, `MobileSessionProvider`, `WindowEventHub`, `Application`) at `install()` time
3. Emits OTel-native signals (logs / spans / metrics) through the shared `LoggerProvider` / `TracerProvider`
4. Has its own Gradle module under `instrumentation/<name>/` with isolated tests

## Build & test all modules

```bash
cd ../examples/demo-app
./gradlew :instrumentation-tap:test \
          :instrumentation-screen:test \
          :instrumentation-errors:test \
          # ... etc.

# Or all at once:
./gradlew testDebugUnitTest
```

## See also

- [otel-android-mobile/README.md](../otel-android-mobile/README.md) — public SDK that wires these
- [otel-android-mobile-core/README.md](../otel-android-mobile-core/README.md) — shared foundation
- [docs/AUTO_INSTRUMENTATION.md](../docs/AUTO_INSTRUMENTATION.md) — auto-capture reference
- [otel-ios-mobile/README.md](../otel-ios-mobile/README.md) — iOS equivalents
