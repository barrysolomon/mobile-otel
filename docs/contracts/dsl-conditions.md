# Condition operators

Each matcher reduces to one or more attribute conditions. A `Condition` is a single attribute key plus one operator + operand. All four platforms implement the same 8 operators with identical semantics; this document records the file:line where each platform's operator dispatch lives so a behavioural change can't ship to one platform without the others.

## Operator catalogue

| Operator | Semantics | Android | iOS | Go |
|---|---|---|---|---|
| `equals` | exact string match | [PolicyEvaluator.kt:249](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L249) | [PolicyEvaluator.swift:137](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyEvaluator.swift#L137) | [processor.go:155](../../collector-processor/mobilepolicyprocessor/processor.go#L155) |
| `notEquals` | mismatch; missing attr → no match | [PolicyEvaluator.kt:250](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L250) | [PolicyEvaluator.swift:142](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyEvaluator.swift#L142) | [processor.go:158](../../collector-processor/mobilepolicyprocessor/processor.go#L158) |
| `gt` | numeric strictly greater; parse failure → no match | [PolicyEvaluator.kt:251](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L251) | [PolicyEvaluator.swift:149](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyEvaluator.swift#L149) | [processor.go:164](../../collector-processor/mobilepolicyprocessor/processor.go#L164) |
| `lt` | numeric strictly less | [PolicyEvaluator.kt:252](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L252) | [PolicyEvaluator.swift:154](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyEvaluator.swift#L154) | [processor.go:170](../../collector-processor/mobilepolicyprocessor/processor.go#L170) |
| `gte` | numeric greater-or-equal | [PolicyEvaluator.kt:253](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L253) | [PolicyEvaluator.swift:159](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyEvaluator.swift#L159) | [processor.go:176](../../collector-processor/mobilepolicyprocessor/processor.go#L176) |
| `lte` | numeric less-or-equal | [PolicyEvaluator.kt:254](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L254) | [PolicyEvaluator.swift:164](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyEvaluator.swift#L164) | [processor.go:182](../../collector-processor/mobilepolicyprocessor/processor.go#L182) |
| `contains` | substring match | [PolicyEvaluator.kt:255](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L255) | [PolicyEvaluator.swift:169](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyEvaluator.swift#L169) | [processor.go:188](../../collector-processor/mobilepolicyprocessor/processor.go#L188) |
| `regex` | full-string regex match | [PolicyEvaluator.kt:256](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L256) | [PolicyEvaluator.swift:174](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyEvaluator.swift#L174) | [processor.go:194](../../collector-processor/mobilepolicyprocessor/processor.go#L194) |

## Cross-platform invariants

These properties of `evaluateCondition` must hold identically on all three platforms:

- **Missing attribute is never a match.** If the event has no attribute by the key the condition specifies, every operator returns false. A condition with all-nil operator fields also returns false.
- **Numeric parse failures return no match.** `parseDouble("abc") → nil` → operator returns false. Never throws, never panics.
- **Regex is full-string, not substring.** A pattern of `error` does not match `internal error`; it must be anchored. Android's `String.matches(Regex)` is full-string by default; iOS's `NSRegularExpression.firstMatch` is range-equality-checked; Go's `regexp.MatchString` is substring by default and must be anchored explicitly.
- **Regex compilation errors are swallowed and cached.** Patterns that fail to compile are stored as a "broken" marker so a hostile remote config can't recompile on every event. Android: cache of `Result<Regex>` per pattern; iOS: cache of `NSRegularExpression?` with nil meaning broken. Go: today silently skips without caching (see [error logging gap](#observability-gap-go-silently-drops-malformed-conditions)).
- **Regex pattern length is capped.** Android and iOS both enforce a 200-character limit on remote-config regex patterns to mitigate ReDoS. Go does not.
- **And/or composition.** A `Match` with `logicalOperator: "or"` returns true if any condition matches. Default and "and" require every condition to match.

## Observability gap: Go silently drops malformed conditions

Android logs a warning when regex compilation fails; iOS caches the failure and silently no-matches subsequent events; **Go silently no-matches with no log at all**. Customers shipping a Go collector cannot tell from logs that a remote config's regex is malformed — only that policy matches dropped to zero. Tracked under the architecture-hardening epic.

## How to add a new operator

1. Pick a verbatim name (no abbreviations).
2. Add a case to each platform's `evaluateCondition` dispatch.
3. Add a row to this table.
4. Add a golden fixture in `golden/dsl/operators/<name>.json` with at least three test inputs: matching, non-matching, missing-attribute.
