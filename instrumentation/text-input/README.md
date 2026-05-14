# instrumentation-text-input

**Status:** Production
**Namespace:** `io.opentelemetry.android.mobile.instrumentation.textinput`
**Class:** `TextInputInstrumentation`

Fires on `EditText` focus-leave to record which form field a user interacted with. Never captures the entered text — only field metadata.

## What it emits

- `ui.text_input` child spans
- Attributes: `target.id`, `target.hint` (the hint text), `input.length` (count, not contents)

## How it's wired

Auto-enabled. Registers a focus-change listener via `WindowEventHub`.

## Privacy

Field **values** are never recorded. PII-sensitive fields are still emitted as events but with `input.length` only — useful for funnel analysis without leaking content.

## Config

```kotlin
MobileOtel.initialize(this, MobileConfig(
    textInputConfig = TextInputConfig(
        enabled = true,
        capturedFieldHints = listOf("Email", "Name"),  // allowlist for hint capture
    )
))
```

## Test

```bash
cd ../../examples/demo-app
./gradlew :instrumentation-text-input:test
```
