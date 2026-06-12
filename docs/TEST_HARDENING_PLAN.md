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

1. **R8/ProGuard consumer test.** ✅ Build gate DONE: the demo app's release
   build now runs with `minifyEnabled = true` and CI's `android-minified` job
   builds it on every push, asserting the public entry points survive
   shrinking identity-mapped. Remaining (P1): runtime smoke of the minified
   APK on an emulator — R8 build success doesn't catch reflection-at-runtime
   breakage.
2. **Crash-handler chaining test.** ✅ DONE (Android):
   `CrashHandlerChainingTest` proves — with a Crashlytics-style stub — that a
   handler installed before OR after the SDK still runs, the SDK captures +
   persists BEFORE delegating downstream (a crash reporter may kill the
   process), exactly one `app.crash` is emitted regardless of chain depth,
   and a downstream handler that throws cannot lose our capture. Remaining:
   mirror on iOS for signal/Mach handlers.
3. **Kill-switch end-to-end test.** ✅ DONE: `KillSwitchEndToEndTest` drives
   the REAL `PolicyEvaluator.fetchConfig` over a loopback MockWebServer and
   proves poll → parse → `RemoteGate` apply → BOTH choke points stop
   (`MobileLogRecordProcessor.onEmit` drops; `DynamicSampler` hard-DROPs even
   page/startup spans), then re-enables via a fresh poll and proves exports
   resume — so the test can actually fail. Also proves events emitted while
   disabled are dropped, not parked for later export. The README claim
   stands.
4. **Startup budget gate.** ✅ DONE — and it caught a real violation:
   `StartupBudgetTest` (instrumented, runs in `connectedDebugAndroidTest`,
   i.e. every `run-e2e.sh` pass) measures `OTelMobile.start()` on the main
   thread against HS-001's 50 ms budget. First measurement: **205–298 ms**.
   Fixed by moving init I/O and heavy construction off the main thread —
   async EncryptedSharedPreferences/Keystore warm-up in SessionManager (with
   a one-shot session-id reconcile so no session ever splits), lazy
   Room/SQLCipher disk-buffer open (seqId seeded from wall-clock, re-raised
   from disk max in the warm-up), lazy OTLP exporter construction, deferred
   PolicyEvaluator / PredictiveExportPolicy / HealthMetricsCollector, and a
   backgrounded recovery probe. Now **~83 ms on a Pixel_7a swiftshader
   emulator ≈ ~28 ms device-equivalent**. The test enforces the bare 50 ms
   on physical devices and a documented 3× calibration on emulators
   (software rendering inflates cold class-loading; the pre-fix code still
   fails the calibrated bound). It also asserts init actually *succeeded*,
   so the silent-degrade-to-no-op path can't sneak under the budget. Bonus
   finding, also fixed: `OTelMobile.stop()` threw off the main thread
   (`LifecycleRegistry.removeObserver`) — now hops to main;
   `StopThreadSafetyTest` guards it.
5. **Receipt gate for all 4 platforms.** ✅ DONE:
   `scripts/e2e/run-platform-e2e.sh` drives ios-native (Schedulr, via a new
   `-DASH0_CRASH_NOW` launch-arg hook), rn-android, and rn-ios through the
   launch → foreground-cycle → crash → recovery journey using the UAT
   platform primitives, then runs `verify-dash0.sh <platform> --since
   <run-start>` — run-scoped, so green means *this run's* telemetry is in
   Dash0. android-native remains gated by `run-e2e.sh`. When the script
   builds the RN demos it stamps the platform-specific `serviceName` into
   the bundled otel-config.json (and restores it), since service identity
   is fixed at build time.
6. **Rotate the demo `.env` Dash0 token** — ✅ DONE (rotated 2026-06-11).

## 3. P1 — before GA

- **Instrumented tests in CI.** ✅ DONE — `.github/workflows/device-tests.yml`
  runs the SDK instrumented suite (incl. the HS-001 budget and stop()
  thread-safety gates) on an API-34 emulator nightly, on every `v*` tag, and
  on demand. Not a publish gate (publish would wait ~30 min per tag);
  revisit at 1.0. The full journey/fault/offline scenario suites
  (`:android:connectedDebugAndroidTest`) still need the demo backend — they
  remain local-only for now.
- **iOS test execution in CI.** ✅ DONE — the same workflow runs the full
  simulator suite (`xcodebuild test -scheme OTelMobile-Package`) on macOS
  nightly/tags/dispatch. Per-push ios-ci.yml stays compile-only by cost
  design.
- **Upgrade-path test.** ✅ DONE — `DiskBufferUpgradePathTest` seeds a real
  v1-schema database file and proves the 1→2→3→4 migration chain preserves
  every event and the buffer keeps accepting writes. Writing it found a real
  bug: `adjustCachedCount` seeded an unseeded count cache from its own delta,
  permanently undercounting pre-existing disk rows (crash-mirrored events
  from a previous process) in the stats gauge and recovery probe — fixed.
- **DB corruption recovery.** ✅ DONE — `DiskBufferCorruptionRecoveryTest`
  proves a garbage file, a truncated database, and a schema-foreign database
  all recover via `openDatabaseCrashSafe()` (recreate, never crash) and the
  buffer accepts writes afterwards.
- **Battery/size budgets.** AAR size gate ✅ DONE — per-push `aar-size` CI job
  enforces a 700 KB budget on the umbrella AAR (582 KB today; raising the
  budget is a same-PR reviewed decision). CONTINUOUS-mode CPU/battery
  profile harness still open.
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
