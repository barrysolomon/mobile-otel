# instrumentation-lifecycle

**Status:** Production
**Namespace:** `io.opentelemetry.android.mobile.instrumentation.lifecycle`
**Class:** `LifecycleInstrumentation`

Tracks Android activity and fragment lifecycle (create / start / resume / pause / stop / destroy). Foundation for screen tracking, session boundaries, and the `app.foreground` / `app.background` log events used by export-policy triggers.

## What it emits

- `app.foreground` / `app.background` log records (per OTel semconv)
- Activity-lifecycle spans (`activity.<name>` with phase attributes)
- Fragment-lifecycle spans when a `FragmentActivity` is detected

## How it's wired

Auto-enabled by `MobileOtel.initialize(...)`. No app code required.

## Opt out

```kotlin
MobileOtel.initialize(this, MobileConfig(
    lifecycleConfig = LifecycleConfig(enabled = false)
))
```

## Test

```bash
cd ../../examples/demo-app
./gradlew :instrumentation-lifecycle:test
```

## See also

- [instrumentation/screen/README.md](../screen/README.md) — page spans built on lifecycle hooks
- [docs/AUTO_INSTRUMENTATION.md](../../docs/AUTO_INSTRUMENTATION.md)
