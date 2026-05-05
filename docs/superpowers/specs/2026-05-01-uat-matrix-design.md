# UAT Matrix — Methodical Acceptance Testing Across Export Modes, Connectivity, and Crash

**Status:** Design approved 2026-05-01
**Author:** Barry Solomon (with Claude)
**Related:** [`docs/epics/VALIDATION_MATRIX_EPIC.md`](../../epics/VALIDATION_MATRIX_EPIC.md), [`docs/matchy-matchy/`](../../matchy-matchy/)

---

## 1. Summary

Build a methodical, per-cell acceptance / UAT framework that drives each platform's emulator (or simulator) through a controlled telemetry-generating sequence and validates arrival in Dash0. The matrix has three independent axes:

- **Export mode**: `CONTINUOUS`, `CONDITIONAL`, `HYBRID`
- **Connectivity**: `online`, `offline→online drain`
- **Crash**: `no_crash`, `crash_during_run`

This yields **12 cells per platform**, **48 cells total** across Android native, iOS native, RN Android, RN iOS.

The framework extends — does not replace — the existing four-gate validation bar (`VALIDATION_MATRIX_EPIC.md`). The four gates ask "does signal X arrive?". This matrix asks "does signal X arrive *under each operating regime*?" — a stronger claim that exercises the SDK's policy engine, dual-tier buffer, and crash-recovery paths together.

## 2. Why this matters

**Today:** matchy-matchy runbooks validate the four gates on `CONTINUOUS` mode only, with online connectivity (`Gate 4` swaps to offline mid-run, but only for that single gate). `CONDITIONAL` and `HYBRID` modes have unit-test coverage of the policy evaluator, but no end-to-end Dash0-confirmed evidence.

**Risk this addresses:** every customer using `CONDITIONAL` or `HYBRID` is running code paths that have never been validated as a system. A regression in policy-trigger flushing, or a bug in HYBRID's tick + trigger combination, would land in production undetected.

**Cost of not having this:** each new SDK release ships a green `CONTINUOUS-online` four-gate result and an unverified claim that the other 11 cells still work.

## 3. Scope

### In scope

- One parameterized cell-runner script (`run-uat-cell.sh`) that takes `(platform, export_mode, connectivity, crash)` and emits Dash0 query JSON evidence
- One outer-loop matrix runner (`run-uat-matrix.sh`) that drives N cells and aggregates a 4×12 results table
- Per-platform "primitive" shell libraries (`lib-uat-platform-android.sh` etc.) that translate the cell's abstract steps into platform-specific commands (adb / simctl / RN bridge install)
- Two new Android Gradle product flavors: `dash0Conditional`, `dash0Hybrid` (existing `dash0` flavor stays as the CONTINUOUS reference)
- Two new iOS schemes: `Dash0Conditional`, `Dash0Hybrid` (existing scheme stays as CONTINUOUS reference)
- Equivalent build configurations for the RN demo app on each host
- Demo-app code changes to read a `DASH0_CELL_ID` launch-intent extra (Android) / `SIMCTL_CHILD_DASH0_CELL_ID` env var (iOS) and pass it to `MobileConfig.extraResourceAttributes`
- The 12-cell expectation table with tiered (must-pass + soft-warn) assertions
- Per-platform evidence files in `docs/uat-matrix/<platform>.md`
- Top-level epic doc `docs/epics/UAT_MATRIX_EPIC.md` tracking 4×12 status

### Out of scope (explicitly NOT delivered by this design)

- **Physical-device cells** — emulator/simulator only. Physical-device coverage is a separate epic.
- **Policy DSL semantics tests** — `mobilepolicyprocessor`'s Go test suite already covers DSL evaluation. The matrix exercises end-to-end behavior, not DSL grammar.
- **Per-instrumentation-module sweep** — the matrix asserts the four-gate envelope (lifecycle + network + crash + recovery), not screenshot/wireframe/scroll/etc. Those have their own unit and integration tests.
- **A new SDK-level "test mode"** — the matrix uses the production code path. The only test-specific signal is the `cell_id` attribute, which is indistinguishable to the SDK from any other user-supplied attribute.

> **Amendment 2026-05-05:** The original spec scoped out SDK changes for `cell_id` plumbing on the assumption that `MobileConfig.extraResourceAttributes` round-trips through Dash0 to the queryable Resource. **It does not.** Empirical verification on 2026-05-05 showed Dash0 ingestion strips Resource attributes outside its known schema (anything not under `service.*`, `device.*`, `os.*`, `telemetry.sdk.*`, `dash0.resource.*`, `dash0.auth.*`). The SDK side is correct — the merged Resource at SDK init contains `dash0.test.cell_id` — but the attribute is dropped server-side and unqueryable.
>
> **Resolution:** `cell_id` and `export_mode` are now copied onto **every emitted LogRecord** as record-level attributes (not Resource-level). The SDK change lives in `EnrichingLogRecordExporter`, which already enriches every record on the export path. The DSL surface (`extraResourceAttributes`) keeps its name for backwards-compatibility with already-shipped Tasks 0.2/0.3, but its semantics are now "attributes attached to every emitted record" — equivalent to a per-record stamp. Filter syntax in §5 stays identical (`dash0.test.cell_id is <uuid>`) since the dash0 CLI matches LogRecord attributes. Span attributes will need a parallel change for Gate 2 — tracked as a follow-on.
- **Runtime export-mode swap** — out of scope per design conversation. Each export mode is a pre-built flavor.

## 4. Architecture

### 4.1 Layout

```
docs/
  epics/
    UAT_MATRIX_EPIC.md              ← new: 48-cell tracker
  superpowers/specs/
    2026-05-01-uat-matrix-design.md ← this file
  uat-matrix/                       ← new: per-platform evidence
    README.md                       ← matrix overview + how to run
    android-native.md               ← cells 1-12 evidence
    ios-native.md
    rn-android.md
    rn-ios.md

scripts/test/uat/                   ← new
  run-uat-cell.sh                   ← single-cell runner
  run-uat-matrix.sh                 ← outer loop
  lib-uat-assertions.sh             ← must-pass + soft-warn helpers
  lib-uat-platform-android.sh       ← adb primitives
  lib-uat-platform-ios.sh           ← simctl primitives
  lib-uat-platform-rn-android.sh
  lib-uat-platform-rn-ios.sh
  evidence/<run-id>/                ← per-run output (gitignored)
    <plat>-<mode>-<conn>-<crash>.json

# Pre-built artifacts (one-time setup per platform per non-default mode)
examples/upstream-demo-app/                                  ← Android: +2 flavors
examples/upstream-demo-app-ios/                              ← iOS: +2 schemes
examples/upstream-demo-app-rn/AstronomyShopRN/android/      ← RN-Android: +2 build types
examples/upstream-demo-app-rn/AstronomyShopRN/ios/          ← RN-iOS: +2 schemes
```

### 4.2 The cell template

Every cell is a four-tuple `(platform, export_mode, connectivity, crash)` and runs through this fixed sequence:

| t | Step | Conditional |
|---|---|---|
| 0s | Install + launch app with `cell_id=<uuid>` injected as resource attribute | always |
| 2s | App-driven HTTPS GET (Gate 2) | always |
| 10s | Background → foreground (lifecycle cycle 1) | always |
| 20s | Background → foreground (lifecycle cycle 2) | always |
| 30s | Connectivity → offline (per-platform primitive) | only if `connectivity=offline` |
| 40s | App-driven HTTPS GET (will fail → buffered) | only if `connectivity=offline` |
| 50s | Trigger crash | only if `crash=yes` |
| 60s | Connectivity → online | only if `connectivity=offline` |
| 70s | Relaunch app (drain on startup) | only if `crash=yes` OR `connectivity=offline` |
| 90s | Stop, run 5-query Dash0 batch filtered by `cell_id`, evaluate assertions | always |

90s cell duration is calibrated to give HYBRID's continuous tick a chance to fire (default trace tick = configurable; demo flavors set short for cells).

### 4.3 The 5-query Dash0 batch

All filters end with `and dash0.test.cell_id is <uuid>` so cells never collide.

| # | Name | Tool | Filter (skeleton) |
|---|---|---|---|
| 1 | lifecycle | `dash0 -X logs query` | `service.name is <svc> and event.name is app.foreground and dash0.test.cell_id is <uuid>` |
| 2 | network | `dash0 -X spans query` | `service.name is <svc> and http.request.method is GET and dash0.test.cell_id is <uuid>` |
| 3 | crash | `dash0 -X logs query` | `service.name is <svc> and event.name is app.crash and dash0.test.cell_id is <uuid>` |
| 4 | recovery | `dash0 -X logs query` | `service.name is <svc> and event.name is app.recovery_start and dash0.test.cell_id is <recovery_launch_uuid>` (see §7) |
| 5 | presence | `dash0 -X logs query` | `service.name is <svc> and dash0.test.cell_id is <uuid>` (no event filter — total record count for soft-warn cardinality) |

All queries use `--from now-3m -o json`, per existing matchy-matchy convention.

### 4.4 Per-platform primitives

Each platform's `lib-uat-platform-*.sh` exposes 6 functions with the same signatures:

| Function | Android native | iOS native | RN Android | RN iOS |
|---|---|---|---|---|
| `uat::install <flavor>` | `adb install -r <apk>` | `xcrun simctl install booted <app>` | same | same |
| `uat::launch <cell_id>` | `am start … --es DASH0_CELL_ID <uuid>` | `simctl launch … --SIMCTL_CHILD_DASH0_CELL_ID=<uuid>` | same as Android | same as iOS |
| `uat::offline` | `adb shell svc wifi disable && svc data disable` | endpoint-swap to `*.invalid:4318`, relaunch | same as Android | same as iOS |
| `uat::online` | `adb shell svc wifi enable && svc data enable` | endpoint-swap back, relaunch | same | same |
| `uat::trigger_crash` | `adb shell am crash <pkg>` | env var `DASH0_GATE3_CRASH=1` → demo `fatalError()` after warmup | env var → JS `throw` | same |
| `uat::cleanup` | `adb uninstall <pkg>` | `simctl uninstall booted <bundle>` | same | same |

The iOS offline primitive swaps the endpoint config because iOS Simulator shares the host network stack — there is no per-simulator airplane mode. Documented in `VALIDATION_MATRIX_EPIC.md` and memory `feedback_otlp_exporter_failure_detection.md`.

## 5. The 12-cell expectation table

`fg` = `app.foreground`, `bg` = `app.background`, `GET` = a span with `http.request.method=GET`. All assertions filter on the cell's `cell_id` resource attribute.

| # | Mode | Conn | Crash | Must-pass | Soft-warn |
|---|---|---|---|---|---|
| 1 | CONT | online | no | `fg ≥ 3`, `bg ≥ 2`, `GET × 1`, NO `app.crash`, NO `app.recovery_start` | exactly 3 fg / 2 bg |
| 2 | CONT | online | yes | (1) + `app.crash × 1` w/ `crash.from_marker=true`, severity FATAL | crash arrives ≤30s post-relaunch |
| 3 | CONT | offline | no | `fg ≥ 3`, `app.recovery_start × 1` w/ `dash0.recovery.event_count ≥ 4`, NO `app.crash` | event_count == counted offline events |
| 4 | CONT | offline | yes | (3) + `app.crash × 1`, recovery event_count includes crash-window events | crash log timestamp from offline window |
| 5 | COND | online | no | **No four-gate signals** (lifecycle/network/crash/recovery queries all return 0) | total presence count == 0 |
| 6 | COND | online | yes | `app.crash × 1`, `fg ≥ 3`, `GET × 1` (crash drains synchronously), NO `app.recovery_start` (online drain succeeds) | drain-time match to crash time |
| 7 | COND | offline | no | No four-gate signals on the wire AND **sqlite disk-buffer count ≥ 4** (probed via `adb shell run-as` / simctl container inspection) | disk count == counted offline events |
| 8 | COND | offline | yes | `app.recovery_start × 1` w/ event_count ≥ 4+crash-events, `app.crash × 1` (FATAL), all lifecycle drained on relaunch | event_count == disk row count pre-drain |
| 9 | HYB | online | no | (1) — within 90s the continuous tick fires; envelope same as CONT online no-crash | tick latency within configured period |
| 10 | HYB | online | yes | (2) | crash via marker, lifecycle via tick |
| 11 | HYB | offline | no | (3) — drain on reconnect | event_count match |
| 12 | HYB | offline | yes | (4) — full path: tick + trigger + drain | event_count includes crash window |

### Notes on specific cells

- **Cells 5 and 7 ("expected nothing")**: must-pass is "no four-gate signals," not "literally zero records." Other records (e.g., a heartbeat or session-start log if the SDK ever emits one outside policy) are tolerated as soft-warn drift. This keeps the matrix robust to future SDK additions while still proving CONDITIONAL respects its contract for the four canonical signals.
- **Cell 7's sqlite disk-buffer probe** is must-pass, not optional. Without it cell 7 is indistinguishable from cell 5; the disk probe is the only observable correctness signal.
- **Cell 6 explicitly asserts NO `app.recovery_start`**: online + CONDITIONAL + crash drains synchronously via the crash policy match. The recovery marker fires only when buffered events are still on disk at next launch.
- **Cells 4, 8, 12 (offline + crash)** are the most rigorous — they exercise dual-tier buffer + policy + crash-mirror + drain together.

## 6. Service names + resource attribute scheme

Existing per-platform service names are reused, no new services in Dash0:

| Platform | Service name |
|---|---|
| Android native | `otel-android-astronomy-shop` |
| iOS native | `otel-ios-astronomy-shop` |
| RN Android / RN iOS | `otel-rn-astronomy-shop` |

Per-cell distinction is via two resource attributes injected at `MobileConfig.extraResourceAttributes`:

- `dash0.test.export_mode` ∈ `{cont, cond, hyb}`
- `dash0.test.cell_id` = a fresh UUIDv4 per run

`cell_id` makes cells idempotently re-runnable: a flake on cell 8 doesn't pollute prior evidence; the new UUID isolates the re-run.

## 7. Crash-marker `cell_id` propagation — design decision

**Finding (verified 2026-05-01 against `MobileLoggerProvider.kt:228-248`):** The `app.recovery_start` marker is emitted at recovery launch using the *current* `sdkLoggerProvider`, which carries the *recovery launch's* resource. So the recovery marker will carry the *recovery launch's* `cell_id`, NOT the original (crashed/offline) cell's `cell_id`.

**However**, the buffered events themselves (drained from disk via `DiskLogBuffer.toLogRecord()` at line 667) reconstruct the *original* resource, so `cell_id` survives for them.

**Decision:** Two `cell_id`s per "drain" cell — `original_cell_id` (the run that buffered the events) and `recovery_cell_id` (the run that drained). The runner generates and tracks both. Queries:

- Lifecycle/network/crash queries filter on `original_cell_id` (asserts the original session's events landed)
- Recovery query filters on `recovery_cell_id` AND `dash0.recovery.event_count ≥ <expected>` (asserts the marker fired in the drain session and accounts for the right count)

The runner schedules the relaunch (step at t=70s) using the same flavor + a fresh `recovery_cell_id`. The expected `event_count` is the must-pass correlation between the two sessions.

This is more honest than papering over the asymmetry, and it surfaces a real product question: **should the recovery marker carry the original session's resource attributes?** That is a separate consideration outside this design — the matrix works with current behavior, and an SDK improvement (carry original resource attrs into the marker) would simplify the queries but isn't required.

## 8. Pre-built flavors

For each platform, **2 new build configurations** (the existing CONTINUOUS one stays):

| Platform | New configurations | Mechanism |
|---|---|---|
| Android native | `dash0Conditional`, `dash0Hybrid` product flavors | Gradle `productFlavors` block; each sets `MobileConfig.exportMode` via `BuildConfig` field |
| iOS native | `Dash0Conditional`, `Dash0Hybrid` schemes | xcconfig + `Info.plist` keyed env reads |
| RN Android | `dash0Conditional`, `dash0Hybrid` build types | Gradle, mirrors native Android |
| RN iOS | matching iOS schemes | mirrors native iOS |

12 build artifacts total (3 per platform × 4 platforms). Built once, then cell runs are install + launch + assert — no rebuild per cell.

## 9. Tiered assertions

`lib-uat-assertions.sh` exposes:

```bash
must::eq <name> <observed> <expected>          # exit 1 on mismatch
must::ge <name> <observed> <expected>          # exit 1 if observed < expected
must::zero <name> <observed>                   # exit 1 if observed != 0
warn::eq <name> <observed> <expected>          # log WARN, do not exit
warn::within <name> <observed> <expected> <%>  # log WARN if outside tolerance
```

Each assertion writes a JSON line to the cell's evidence file:

```json
{ "tier": "must", "gate": "lifecycle", "claim": "fg >= 3", "observed": 3, "passed": true }
```

Final exit:
- `0` → all must-pass green
- `1` → ≥1 must-pass failed (regression)
- `2` → infrastructure failure (emulator down, Dash0 auth expired) — distinct from real test failure
- `3` → cell skipped (platform not configured on this host)

## 10. Runner contracts

### `run-uat-cell.sh`

```bash
scripts/test/uat/run-uat-cell.sh \
  --platform=android-native|ios-native|rn-android|rn-ios \
  --mode=cont|cond|hyb \
  --connectivity=online|offline \
  --crash=no|yes \
  [--run-id=<uuid>] \
  [--evidence-dir=<path>] \
  [--keep-app]
```

### `run-uat-matrix.sh`

```bash
scripts/test/uat/run-uat-matrix.sh \
  [--platform=android-native,...]   # comma-separated; default = all 4
  [--cells=1-12|1,3,5]              # default = all 12
  [--fail-fast]                     # default false
  [--summary-md=<path>]              # write 4×12 markdown table
```

## 11. CI staircase

| Cadence | Cells | Platforms | Why | Wall-clock |
|---|---|---|---|---|
| Per-PR | 1 (CONT online no-crash) | android-native | Smoke; stays in cache | ~2 min |
| Nightly | 1, 2, 3 | all 4 | Catches CONTINUOUS regressions | ~25 min |
| Weekly / release | 1-12 | all 4 | Full sweep | ~100 min |

`cell_id` UUIDs make a parallel `--parallel` mode safe in principle (no Dash0 collision), but parallel runs require multiple emulators booted simultaneously — opt-in only.

## 12. Acceptance criteria

This design is delivered when:

1. The 12 build artifacts (3 per platform × 4 platforms) build cleanly and produce installable apps.
2. `run-uat-cell.sh` can run a single cell on each platform end-to-end and emit a valid evidence JSON file.
3. `run-uat-matrix.sh` can sweep all 12 cells on at least one platform (Android native) and produce a 4×12 markdown summary.
4. All 12 cells on Android native pass their must-pass assertions in Dash0, with evidence captured in `docs/uat-matrix/android-native.md`.
5. The other three platforms have at least cells 1-3 green (the nightly subset), with their evidence files populated.
6. `docs/epics/UAT_MATRIX_EPIC.md` exists and tracks the 4×12 status grid with green/yellow/red marks.

Full sweep on iOS / RN-Android / RN-iOS is a follow-on; this design only requires that the framework can run them.

## 13. Risks + mitigations

| Risk | Mitigation |
|---|---|
| HYBRID's tick configurable period is longer than 90s on a default-config flavor → cells 9-12 mistake "tick hasn't fired" for "tick broken" | Demo flavors override tick to a short value (e.g. 30s) so HYBRID is observable within cell duration |
| iOS Simulator endpoint-swap requires rebuild → slow per-cell (per `VALIDATION_MATRIX_EPIC` notes) | The demo app reads `otel-config.json` at startup; we swap the file (not rebuild) and relaunch. Already proven on iOS native |
| Android `am crash` reportedly produces non-deterministic timing on API 36 | Provide both primitives — `am crash` (canonical) and launch-intent `--ez gate3_crash true` (deterministic warmup-then-crash). Pick per platform footnote |
| `dash0` CLI auth expires mid-sweep on long full-matrix runs | `run-uat-matrix.sh` re-checks auth between cells; exit code 2 (infra) on auth-expired so it's distinguishable from a real failure |
| RN bridge crash on iOS doesn't always reach SDK fatal handler (per memory `feedback_otlp_exporter_failure_detection.md`) | Cell 6/8/10/12 on RN-iOS may need an extra `forceFlush` step before crash; documented per-platform footnote |
| The recovery `cell_id` asymmetry (§7) confuses readers expecting symmetric filtering | Documented in §7 explicitly; runner emits both `original_cell_id` and `recovery_cell_id` to evidence file |

## 14. Open question — answered during review

**Q (during review):** Should crash-marker `cell_id` persistence be discovered during implementation or verified up front?

**A:** Verified up front. The recovery marker is emitted with the *current* (recovery-launch) resource. Buffered events drained from disk reconstruct the *original* resource. The matrix uses both `original_cell_id` and `recovery_cell_id` as documented in §7. No SDK change required.
