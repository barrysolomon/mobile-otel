# Architecture hardening epic

Closes the silent-drift class of bug surfaced by the 2026-05-12 iOS HYBRID misdiagnosis. The SDK works; the discipline that keeps four platforms in sync was the bottleneck. This epic turns that discipline into machine-checkable artifacts.

## Goals

1. Make cross-platform invariants machine-readable (contracts + golden fixtures).
2. Delete the dual paths that caused the SDK-is-fine signal to be missed (legacy `BufferedEventExporter`, two `forceFlush` variants, two entry points).
3. Fix the load-bearing footgun (`ErrorCoalescer` body-key conflation).
4. Add the smoke checks that would have caught the wrong-CLI-filter session waste.

## Tracks

### Track 1 — Contracts (foundation)

- `docs/contracts/` directory with one document per invariant: matchers, conditions, actions, default policies, coalescer key, offline policy, network-restored flush, buffer drain surface.
- `scripts/ci/check-contract-drift.sh` parses the file:line refs in each contract doc and fails CI when a referenced line range moves without the doc updating.

### Track 2 — Golden DSL fixtures

- `golden/dsl/` directory with JSON fixtures pairing a policy DSL + a sample event + the expected match outcome.
- Each platform (Android Kotlin, iOS Swift, Go) has a single test that loads every fixture and asserts the evaluator's verdict matches the expected outcome.
- The fixtures are the executable form of the matcher contract: adding a new matcher requires adding a fixture (caught in code review). A platform that doesn't honor a fixture fails its test — silent drift becomes loud drift.

### Track 3 — Delete legacy `BufferedEventExporter`

- Remove the second constructor on `MobileLogRecordProcessor`.
- Migrate every test that uses `CapturingExporter` (which conforms to `BufferedEventExporter`) to a `RecordingLogExporter`-style mock that conforms to OTel's `LogRecordExporter`.
- Delete `BufferedEventExporter.swift` and `CapturingExporter.swift` from production code; move them under `Tests/` if any tests still need the shape.
- Net effect: every test exercises the production exporter path, so a future regression in the OTel path cannot silently pass tests (which was the gap that masked yesterday's bug).

### Track 4 — `ErrorCoalescer` tuple-keying

- Both platforms key on a tuple that includes the distinguishing attributes for the signal class:
  - `http.error` → `event.name|http.response.status_code|url.full`
  - `app.crash` → `exception.type|exception.message`
  - other structured events with `event.name` set → `event.name` plus the most-distinguishing attribute, otherwise no coalescing
  - raw body without `event.name` → `body|<body>` (preserves the current behaviour as a last resort for genuine error storms)
- The `PolicyPipelineIntegration > updatePolicies changes flush behaviour on next emit` flake is expected to go green once this lands (the test emits two `app.crash` events; with tuple-keying they no longer collapse).
- New tests on both platforms lock in:
  - Two `http.error` records with different status codes do NOT collapse.
  - Two genuine crash duplicates (same exception type + message) DO collapse.

### Track 5 — Collapse duplicate APIs

- `MobileOtel.initialize(...)` is made internal on both platforms; `OTelMobile.start(...)` is the only public entry.
- iOS: `forceFlushBuffered()` becomes internal; `forceFlush(explicitTimeout:)` is the public surface and always drains RAM + disk (matching what `forceFlushBuffered` does today).
- Test code is migrated to the new surface.
- Memory entries `feedback_sdk_two_entry_points` and `feedback_ios_forceflush_two_methods` are updated with the resolution.

### Track 6 — CLI smoke check

- `scripts/ci/dash0-cli-smoke.sh` runs against a known-populated Dash0 dataset and asserts at least one log record returns for the service name. Catches the "wrong filter namespace returns zero" trap that ate yesterday's session.
- Integrated into the existing run-tests.sh pipeline so anyone validating telemetry locally exercises the smoke.

### Track 7 — Parity-test inventory

- A short inventory document listing every cross-cutting behaviour and the connectedAndroidTest / iOS UI-test that locks it down end-to-end.
- Filling the gaps takes time, but the inventory itself is small and high-value: it tells the next contributor what's covered and what isn't.

## Out of scope

- iOS `OTelMobile.start` decomposition. The 600-line factory works; refactoring for line count is low leverage.
- iOS SwiftPM target unbundling. No customer is asking; wait for the actual need.
- Test-support shim cleanup. Touching the `_Testing_Foundation` workarounds risks reintroducing the gap on someone else's CLT install.

## Tracking

This epic ships as a series of commits on `epic/architecture-hardening`, one commit per track plus a final verification commit. Each track is independently reviewable; the contract docs and golden fixtures (Tracks 1–2) are the foundation everything else builds on.
