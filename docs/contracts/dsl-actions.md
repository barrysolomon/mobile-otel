# Action types

When a policy matches, it executes an action. The DSL v2 producer emits one of several action node types, but the SDK runtime today implements only one: `flush_window`.

## Action catalogue

| JSON action | SDK runtime | Producer compiles | Notes |
|---|---|---|---|
| `flush_window` | implemented | yes | Maps to `flushWindowMinutes` integer, clamped to `[1, 60]`, default 2. Both Android and iOS call `flushWindow(minutes:)` on the buffer processor. |
| `set_sampling` | not implemented | yes | Producer emits; SDK ignores. |
| `annotate_trigger` | partially (Go) | yes | Go processor annotates matched records with the policy ID; SDK-side has no annotation. |
| `send_alert` | not implemented | yes | Producer emits; SDK ignores. |
| `adjust_config` | not implemented | yes | Producer emits; SDK ignores. |
| `emit_metric` | not implemented | yes | Producer emits; SDK ignores. |
| `record_session` | not implemented | yes | Producer emits; SDK ignores. |
| `create_funnel` / `create_sankey` | server-side only | yes | Visualization actions; not SDK runtime. |
| `take_screenshot` | not implemented | yes | Producer emits; SDK ignores. ScreenshotInstrumentation has its own rate-limited capture path. |

## flush_window: the only implemented runtime action

This is the action the entire HYBRID / CONDITIONAL pipeline depends on. The matched policy carries a `flushWindowMinutes` integer; the buffer processor drains the last N minutes of buffered events through the OTLP log exporter.

- Android: [PolicyEvaluator.kt:98-128](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L98-L128) (default policies) and [MobileLogRecordProcessor.kt:503](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/MobileLogRecordProcessor.kt#L503) (flushWindow).
- iOS: [PolicyParser.swift:284-295](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyParser.swift#L284-L295) (clamping) and [MobileLogRecordProcessor.swift:306](../../otel-ios-mobile/Sources/OTelMobileSDK/Buffering/MobileLogRecordProcessor.swift#L306) (flushWindow).
- Go: [processor.go:218-252](../../collector-processor/mobilepolicyprocessor/processor.go#L218-L252) (annotation path).

## Why the other actions exist in the producer

The control-plane UI emits the full action surface because the gateway and visualisation layer use them. The SDK runtime only honors `flush_window` today because that's the only action with on-device semantics that aren't already handled by another path (sampling lives in `SamplingConfig`; screenshots have their own instrumentation; alerts are a server-side concern).

If a future feature implements one of the unimplemented actions, add a row to the runtime column and a fixture under `golden/dsl/actions/`.
