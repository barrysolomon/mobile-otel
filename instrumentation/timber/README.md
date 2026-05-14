# instrumentation-timber

**Status:** Incubating
**Namespace:** `io.opentelemetry.android.mobile.instrumentation.timber`

Bridge from [Timber](https://github.com/JakeWharton/timber) to OpenTelemetry. Plant the OTel `Timber.Tree` and all `Timber.d/i/w/e(...)` calls become OTel log records.

## What it emits

- Log records following OTel semconv (`Severity` mapped from Timber priorities)
- Attributes: `log.source=timber`, plus any structured metadata you pass

## How it's wired

User-wired:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MobileOtel.initialize(this, ...)

        // Plant after MobileOtel.initialize so the Logger is available
        Timber.plant(OTelTimberTree())
    }
}
```

## Test

```bash
cd ../../examples/demo-app
./gradlew :instrumentation-timber:test
```
