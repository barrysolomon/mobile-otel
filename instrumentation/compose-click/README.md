# instrumentation-compose-click

**Status:** Incubating
**Namespace:** `io.opentelemetry.android.mobile.instrumentation.composeclick`
**Class:** `ComposeClickInstrumentation`

Click telemetry for Jetpack Compose. Walks the Compose semantics tree to find clickable nodes — needed because Compose draws on a single `AndroidComposeView`, so the View-tree-based `TapInstrumentation` only sees the host view, not the composables inside.

## What it emits

- `ui.compose.click` log records
- Attributes: `target.role`, `target.text`, `target.test_tag`, `target.content_description`

## How it's wired

Opt-in (the SDK reflectively checks for `androidx.compose.ui.platform.AndroidComposeView`; if Compose isn't on the classpath this module no-ops):

```kotlin
MobileOtel.initialize(this, MobileConfig(
    composeClickConfig = ComposeClickConfig(enabled = true)
))
```

## Supersedes

Earlier `compose.click` event name. Old name still accepted; emit-side uses the new one.

## Test

```bash
cd ../../examples/demo-app
./gradlew :instrumentation-compose-click:test
```
