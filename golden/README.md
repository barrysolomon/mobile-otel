# Golden fixtures

Executable form of the cross-platform contracts in `docs/contracts/`. Each fixture pairs:

- A policy DSL document (or a fragment a platform parses internally as `Match` + `Condition`)
- A set of test events with attributes
- The expected verdict per event: matched policy ID, or `null` if no match

Every platform (Android, iOS, Go) has a single test that loads every fixture and asserts its evaluator's verdict matches the expected outcome.

## Why this exists

Memory entries `feedback_no_platform_drift` and `feedback_otel_native_nonnegotiable` document the discipline. The architecture-hardening epic turns that discipline into a check: a new matcher added to one platform without the others fails the others' fixture tests. Silent drift becomes loud drift.

## Fixture format

```json
{
  "$schema": "../schema.json",
  "name": "human-readable name",
  "description": "what this fixture proves",
  "policies": [
    {
      "id": "policy-id",
      "enabled": true,
      "match": {
        "operator": "and",
        "attributes": {
          "event.name": { "equals": "http.error" }
        }
      },
      "actions": { "flushWindowMinutes": 2 }
    }
  ],
  "cases": [
    {
      "name": "matches when event.name equals http.error",
      "attributes": { "event.name": "http.error" },
      "expectedMatch": "policy-id"
    },
    {
      "name": "no match when event.name differs",
      "attributes": { "event.name": "ui.tap" },
      "expectedMatch": null
    }
  ]
}
```

## Subdirectories

- `matchers/` — one fixture per JSON matcher type from `docs/contracts/dsl-matchers.md`
- `operators/` — one fixture per condition operator from `docs/contracts/dsl-conditions.md`
- `composition/` — `and`/`or` logical operators, multi-condition policies, disabled policies

## How to add a new fixture

1. Create the JSON under the appropriate subdirectory.
2. Add at least three cases: positive match, no-match-on-different-value, missing-attribute.
3. Run each platform's test (`./scripts/ci/run-tests.sh --android-only`, `--ios`, `--go-only`) and confirm green.
4. If any platform doesn't honor it, that's the parity gap the fixture is meant to surface — either fix the platform or update the contract doc to declare the matcher unsupported there.

## Platform coverage

- Android: `otel-android-mobile/src/test/java/io/opentelemetry/android/mobile/policy/GoldenFixtureTest.kt`
- iOS: `otel-ios-mobile/Tests/OTelMobileSDKTests/Policy/GoldenFixtureTests.swift`
- Go: **not yet** — the Go processor evaluates against OTLP record streams rather than a JSON policy + attribute map shape. A Go-side runner is tracked in `docs/epics/ARCHITECTURE_HARDENING_EPIC.md` as follow-up; the contract docs and Android/iOS runners are the load-bearing parity guard.

## Per-platform documented drift

A case can record platform-specific verdicts under `knownDrift.<platform>`:

```json
"knownDrift": {
  "android": { "actual": "or-policy", "reason": "documented in docs/contracts/dsl-conditions.md" }
}
```

When set, the test accepts the documented `actual` verdict instead of `expectedMatch` and prints a notice. Use sparingly — every entry is a parity gap waiting to be closed. The architecture-hardening epic spec is the index of intentional drift.

## Why JSON, not language-native

The producer (control-plane-ui graphToDSLv2.ts) emits JSON. The Go processor consumes JSON. Android and iOS parse the JSON into language-native types via PolicyParser. Keeping the fixtures in the same JSON shape the producer emits means a fixture can be lifted directly from a real control-plane export — no translation step that could itself drift.
