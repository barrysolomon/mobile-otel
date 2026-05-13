# Error coalescer

Suppresses duplicate error events within a rolling time window so a tight crash loop or a flapping HTTP error doesn't blow out the buffer with N copies of the same record. Only the first occurrence of a coalescing-key passes through `onEmit`; subsequent occurrences are dropped and a `coalesced.count` annotation is added to the kept record at drain time.

## Coalescing key — current shape

The platforms today key on either an exception tuple (when `exception.type` is present) or the log body as a fallback:

| Platform | Key construction | File:line |
|---|---|---|
| Android | `exception.type` present → `"$exceptionType\|$exceptionMessage"`; else → `"body\|$body"` | [ErrorCoalescer.kt:113-125](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/ErrorCoalescer.kt#L113-L125) |
| iOS | same shape | [ErrorCoalescer.swift:101-116](../../otel-ios-mobile/Sources/OTelMobileSDK/Buffering/ErrorCoalescer.swift#L101-L116) |
| Go | not implemented | n/a — Go processor passes all matched records through |

## Known issue with the body fallback

The body-fallback key conflates "signal name" with "duplicate event." Two `http.error` records — one with status 503 to URL A and another with status 500 to URL B — produce identical body strings and the second is silently coalesced away. This is fine for genuine error storms but actively wrong for structured signals that happen to share a body string.

The PolicyPipelineIntegration `updatePolicies changes flush behaviour on next emit` flake on iOS is exactly this footgun firing inside a test: two emits with `body="app.crash"` collapse, the second never reaches the policy evaluator, the assertion fails.

The architecture-hardening epic fixes this by tuple-keying: include the distinguishing attributes (`event.name`, `http.response.status_code`, `url.full` for HTTP errors; `exception.type`, `exception.message` for crashes; `body` only as a last resort). Both Android and iOS must change together.

After the fix, this contract doc will be updated to describe the new key. The fixture under `golden/coalescer/` will cover both genuine-duplicate and false-duplicate cases.

## Cross-platform invariants

- **First occurrence always passes.** `tryCoalesce(firstEmit) → false` on both platforms. The first time a key is seen, the event is buffered normally.
- **Window is 60 seconds.** After 60s, the key is pruned and a fresh first-occurrence will pass again.
- **Default minSeverity is ERROR.** Events below ERROR severity are not subject to coalescing.
- **Coalescing happens before policy evaluation.** A coalesced event never reaches `policyEvaluator.evaluate(...)` — this is exactly what makes the body-fallback footgun silent.
