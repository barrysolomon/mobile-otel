# instrumentation-errors

**Status:** Production
**Namespace:** `io.opentelemetry.android.mobile.instrumentation.errors`
**Class:** `ErrorInstrumentation`

Captures uncaught exceptions (main thread and coroutines) before the app dies. Deduplicates by stack-trace signature within a 5-minute window. Rate-limits at 10 errors/minute via `RateLimiter`.

## What it emits

- `app.crash` log records for uncaught exceptions on the main thread
- `app.error` log records for non-fatal exceptions (e.g. `CoroutineExceptionHandler`)
- `app.recovery_start` on next app launch after a crash (this is the reliable signal — `app.crash` can race with `KillApplicationHandler`; see memory `feedback_crash_handler_race.md`)

## How it's wired

Auto-enabled. Installs `Thread.setDefaultUncaughtExceptionHandler` and chains to any existing handler. The crash event is force-flushed via `persistForCrash()` so the dual-tier buffer drains to disk before the process dies.

## Config

```kotlin
MobileOtel.initialize(this, MobileConfig(
    errorConfig = ErrorConfig(
        maxErrorsPerMinute = 10,
        deduplicationWindowMs = 300_000,  // 5 min
    )
))
```

## Test

```bash
cd ../../examples/demo-app
./gradlew :instrumentation-errors:test
```

## See also

- [docs/IOS_CRASH_REPORTING.md](../../docs/IOS_CRASH_REPORTING.md) — iOS equivalent
- [HOW_TO_DEMO.md](../../HOW_TO_DEMO.md) — crash recovery demo
