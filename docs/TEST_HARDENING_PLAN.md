# Test Hardening Plan

Outcome of a full QA audit of the test, e2e, and CI safety net (2026-06-11).
This document records (1) what the audit found, (2) what is fixed as of the
`qa/test-hardening` branch, and (3) the tiered plan for what remains.

The organizing principle the audit kept tripping over: **signal existed but
enforcement didn't** — tests existed that CI never ran, gates existed that
could not fail, and docs promised behavior no test backed. Hardening means
closing the gap between "we have a check" and "a regression physically cannot
reach a release tag."

---

## 1. Fixed in this branch

### Product bugs found by the audit (all in the flush/buffer path)

| Bug | Mechanism | Fix |
|---|---|---|
| **Silent data loss on force flush** | `forceFlush()` cleanup ran `diskBuffer.clearAll()`, deleting disk rows persisted *after* its snapshot — events that were never exported. | Snapshots carry Room row ids; cleanup deletes exactly the exported rows (`deleteByIds`), plus mirror rows of exported RAM events (`deleteBySeqIds`). Same fix applied to `flushWindow()`, whose `deleteEventsInWindow(start)` had the identical flaw. |
| **In-flight events invisible to flush** | `overflowToDisk()` polled events out of RAM then persisted them *asynchronously* — during that window events were in neither tier, so a concurrent flush under-exported (observed: 245 of 500). | `bufferMoveLock` makes the RAM→disk move atomic w.r.t. flush snapshots; the overflow persist is blocking inside the lock. |
| **Dishonest defer + gate-release race** | A deferred `forceFlush()` returned instant fake success; the flush gate was released via `executor.submit` even when nothing needed cleanup — so an immediate retry-after-failure silently no-opped. | Deferring `forceFlush()` returns the in-progress flush's completion (`activeFlushResult`). Failure paths release the gate synchronously in `whenComplete`. `forceFlush()`/`flushWindow()` results now complete only after export **and** cleanup settle — callers can `join()` instead of sleeping. |

Regression tests: `MobileLogRecordProcessorTest` ("flush cleanup deletes only
the disk rows it exported", latch-based defer test), exact-count assertions in
`TtlEvictionStressTest`, `BufferCrashPathTest`, `FlushDedupTest` siblings.

### Test-suite hygiene

- The 3 CI-failing tests were root-caused (not sleep-band-aided): one real
  product bug (above), two timing races against async flush internals.
- Sleeps replaced with deterministic waits in the touched tests:
  `result.join()` on the new flush contract, and a latch-controlled
  `MockLogRecordExporter` (`blockExports` / `exportStarted`) to hold an export
  in-flight without wall-clock guessing.
- `OfflinePolicyTest` coalescing test decoupled from the built-in
  crash-recovery policy (its async `flushWindow` raced the assertion).

### Gates that could not fail

- `run-e2e.sh` now **exits non-zero** when suites fail (previously always 0).
- The Dash0 receipt gate **fails loudly** when no token is configured;
  skipping requires an explicit `--allow-no-dash0`.
- `dash0_assert.py` gained `--retry-for/--retry-interval` (polls through
  ingestion latency and transient API errors; auth errors still fail fast)
  and `--since` (run-scoped window: telemetry from a previous run can no
  longer green this one). `run-e2e.sh` stamps `RUN_START_EPOCH` and passes it
  through.
- `publish.yml` gained a `verify-ci-green` preflight: tags pointing at a
  commit without green CI **refuse to publish** (ios-ci, being path-filtered,
  counts as green when no run exists for the SHA).
- All workflows bumped to Node-24-ready action majors (GitHub forces Node 24
  on June 16, 2026).

---

## 2. P0 — before public launch

1. **R8/ProGuard consumer test.** `consumer-rules.pro` ships untested. Add a
   minified demo-app variant (`minifyEnabled true`) to CI and run the smoke
   scenario against it. The most common day-1 SDK integration failure is
   "crashes when minified."
2. **Crash-handler chaining test.** Install Crashlytics (or a stub
   `UncaughtExceptionHandler`) alongside the SDK; assert both handlers run,
   ordering is preserved, and the crash is reported exactly once. Mirror on
   iOS for signal/Mach handlers when that lands.
3. **Kill-switch end-to-end test.** README promises `sdk.enabled` remote
   disable. Prove poll → parse → choke-points-stop-exporting in one test, or
   soften the README before someone tries it live.
4. **Startup budget gate.** TEST_PLAN HS-001 says init < 50 ms on main
   thread; nothing enforces it. Add a macrobenchmark (or instrumented timing
   assertion) with a hard threshold.
5. **Receipt gate for all 4 platforms.** `run-e2e.sh` only gates
   android-native; `verify-dash0.sh` already supports ios-native, rn-android,
   rn-ios — wire the other three into whatever drives their demos.
6. **Rotate the demo `.env` Dash0 token** (it sits in working trees and has
   been read by tooling) and keep it out of future audits.

## 3. P1 — before GA

- **Instrumented tests in CI.** The journey/fault/offline scenario suites run
  only on a developer's machine. Add an emulator job (API 34, headless) on a
  schedule or pre-release trigger if per-push cost is too high.
- **iOS test execution in CI.** 500+ tests exist; CI compiles only. At
  minimum run the host-safe subset; full simulator suite on release tags.
- **Upgrade-path test.** Old-schema disk buffer (v1) → new SDK: events
  survive Room migration and export.
- **DB corruption recovery.** Truncate/corrupt the SQLite file; SDK falls
  back without crashing and reports the loss.
- **Battery/size budgets.** AAR size delta gate; CONTINUOUS-mode CPU/battery
  profile harness.
- **PII property tests.** Generative corpus through PiiScrubber; ReDoS guard.
- **429/jitter retry test.** Backoff exists; assert jitter and respect for
  Retry-After to avoid fleet-synchronized retry storms.
- **Sleep eradication.** ~170 `Thread.sleep` calls remain across the suite.
  Convert opportunistically to the `join()`/latch patterns introduced here —
  every sleep is a future CI flake.

## 4. P2 — post-launch backlog

- Dependency/version matrices (AGP/Kotlin, OkHttp, RN old-vs-new arch, Expo).
- Hostile-environment suite: clock skew during sessions, Doze/app-standby,
  iOS background task expiry mid-flush, captive portals, IPv6-only, disk-full.
- Cardinality/payload abuse guards (attribute count/size circuit breakers) + tests.
- SDK self-observability: dropped-event counters and a health metric, so a
  broken SDK is distinguishable from a quiet app in production.
- Config DSL builder validation tests (6 DSL files currently untested).
- Span-ID-uniqueness assertion in `dash0_assert.py` (catches client
  double-export that backend dedup hides).

## 5. Invariants to keep

- A flush result that returns success **means** the data is exported and the
  buffers are consistent — tests `join()` it; they do not sleep.
- Every event is visible in exactly one buffer tier at any observable moment.
- Cleanup deletes only what was exported. When in doubt, leave the row —
  duplicates are recoverable upstream; silent loss is not.
- A skipped gate is a failed gate unless skipping was explicitly requested.
- Nothing publishes from a commit without green CI.
