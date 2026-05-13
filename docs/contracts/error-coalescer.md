# Error coalescer

Suppresses duplicate error events within a rolling time window so a tight crash loop or a flapping HTTP error doesn't blow out the buffer with N copies of the same record. Only the first occurrence of a coalescing-key passes through `onEmit`; subsequent occurrences are dropped and a `coalesced.count` annotation is added to the kept record at drain time.

## Coalescing key — current shape (post architecture-hardening epic)

Both platforms key by precedence, biased toward distinguishing structured signals while still collapsing genuine error storms:

1. **Exception tuple.** If `exception.type` is set, key is `"$exceptionType|$exceptionMessage"`. Genuine crash duplicates collapse.
2. **`http.error` distinguishing tuple.** If `event.name == "http.error"`, key is `"http.error|$status|$url"`. Two 4xx requests to different URLs no longer collapse.
3. **Any other structured signal.** If `event.name` is set (but not `http.error` and no `exception.type`), the record is NOT coalesced — `coalescingKey` returns null. Structured events are intentional signals, not error noise.
4. **Body fallback.** No `event.name` and no exception, body present → `"body|$body"`. Legacy uncaught exceptions that emit a body without structured attrs still collapse.

| Platform | File:line |
|---|---|
| Android | [ErrorCoalescer.kt:113](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/ErrorCoalescer.kt#L113) |
| iOS | [ErrorCoalescer.swift:101](../../otel-ios-mobile/Sources/OTelMobileSDK/Buffering/ErrorCoalescer.swift#L101) |
| Go | not implemented — Go processor passes all matched records through |

## Why this shape

The original `body|<body>` fallback conflated "signal name" with "duplicate event." Two `http.error` records — one with status 503 to URL A and another with status 500 to URL B — produced identical body strings and the second was silently coalesced away. CONTINUOUS got away with this because the periodic timer drained everything; HYBRID and CONDITIONAL silently lost the second event.

The PolicyPipelineIntegration `updatePolicies changes flush behaviour on next emit` flake on iOS was exactly this footgun firing inside a test: two emits with `body="app.crash"` collapsed, the second never reached the policy evaluator. With the new shape, both emits have `exception.type=null` and `event.name="app.crash"` not set — fall-through to body-fallback still collapses them, so the flake persists as designed (a true duplicate). Tests asserting `app.crash` policy fires on the second emit should set the exception tuple distinctly or use a different body per emit.

## Cross-platform invariants

## Cross-platform invariants

- **First occurrence always passes.** `tryCoalesce(firstEmit) → false` on both platforms. The first time a key is seen, the event is buffered normally.
- **Window is 60 seconds.** After 60s, the key is pruned and a fresh first-occurrence will pass again.
- **Default minSeverity is ERROR.** Events below ERROR severity are not subject to coalescing.
- **Coalescing happens before policy evaluation.** A coalesced event never reaches `policyEvaluator.evaluate(...)` — this is exactly what makes the body-fallback footgun silent.
