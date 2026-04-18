# Epic: iOS ↔ Android Parity + 100% OTel-Native SDK

**Status:** Active
**Owner:** Mobile Observability (iOS + Android SDK teams)
**Last updated:** 2026-04-17
**Target GA:** End Q3 2026 — 5 phases, ~12 working weeks

## Goal

Bring the iOS SDK to the point where it can be marketed as
"feature-equivalent to our Android SDK and 100% OTel-native at the data plane"
without qualification. The Dash0-proprietary control plane (policy DSL,
predictive export, fleet alerts, dual-tier buffer with crash-safe replay)
is **not** part of the OTel-native scope and is the deliberate value-add
that distinguishes our SDK from vanilla `opentelemetry-swift`.

### Definition of done

Every claim below must be true in code AND verified in CI before we ship a
v1.0 GA tag:

1. **Feature parity:** every Android instrumentation module that has an iOS
   API analog ships and behaves identically. Modules with no iOS analog
   (`back-press`, `compose-click`, `system-events`, `database`, `file-io`,
   `amplify-datastore`, `timber`, `screen-orientation`) are documented as
   N/A in [`IOS_ANDROID_PARITY.md`](../IOS_ANDROID_PARITY.md).
2. **OTel-native data plane:** customer can change `endpoint` to any OTel
   collector and emission code is unchanged. SDK emits standard OTLP via
   stock OTel-Swift providers/processors. CI proves this with a swap test.
3. **Test parity:** ≥ 60% of Android's test count, with key suites
   (PII, sampling, autocapture, recovery) at 1:1 behavioural parity.
4. **Scenario parity:** all 28 `validate-us0XX-*.sh` scripts have iOS
   equivalents under `scripts/test/validate-ios-us0XX-*.sh`.
5. **Demo parity:** AstronomyShop on both platforms emits the same contract
   ([`shop-telemetry-contract.md`](../design/shop-telemetry-contract.md));
   dual-platform demo drives both via real UI events.
6. **Real-device validation:** the [`IOS_REAL_DEVICE_VALIDATION.md`](../IOS_REAL_DEVICE_VALIDATION.md)
   playbook has been run on at least 3 distinct iPhone models and recorded.

### Out of scope (deliberately)

- Porting the Dash0-proprietary control plane to be "OTel-spec compliant."
  Policy DSL v2, `OnDevicePredictor`, `FleetAlert*`, `MobileLogRecordProcessor`,
  `flushWindow(minutes:)`, the dual-tier buffer with `seqId` crash-safe
  semantics, the `Dash0-Dataset` header — these stay Dash0-specific and
  are documented as such.
- Replacing `opentelemetry-swift` with our own OTLP implementation. We use
  upstream OTel-Swift; we contribute fixes upstream when we hit them
  (see `docs/upstream/`).

## Current state (2026-04-17)

| Dimension | Android | iOS | Parity |
|---|---|---|---|
| SDK feature parity | 100% | ~70% | gaps in PII, sampling, retry, log tailing, several UI modules |
| Test count | ~980 | 139 | **14%** |
| Scenario scripts | 28 | 7 | **25%** |
| Demo apps | AstronomyShop on both | shipped + UI-driven | **100%** ✓ |
| Cross-platform contract | yes | yes | **100%** ✓ |
| Real-device validation | yes | no (sim only) | **0%** |
| OTel-native data-plane | n/a (Kotlin idioms) | yes (boundary) | **100%** ✓ |

Five phases below close the remaining gaps in dependency order.

---

## Phase 1: Missing iOS SDK modules (~3 weeks)

**Why first:** every test we'd write for missing modules can't exist until
the modules do. PII scrubbing in particular blocks demo content that
contains realistic user data.

### 1.1 Privacy primitives — PII scrubber + redaction pipeline (1 week)

- New `OTelMobileSDK/Privacy/PiiScrubber.swift` — port from
  `otel-android-mobile/src/main/.../PiiScrubber.kt`. Email / phone / SSN /
  credit-card / IPv4 / IPv6 / GPS-coordinate regex passes with a configurable
  replacement token.
- Wire into `MobileLogRecordProcessor.onEmit` — every log body + attribute
  string runs through scrubber when `MobileConfig.privacyConfig.enableScrubbing`
  (default true in `PrivacyMode.privacy` preset, false in `.minimal`).
- Privacy presets: `.privacy` (full scrub + tap-coordinate bucketing + skip
  location), `.balanced`, `.minimal` (no scrubbing). Mirrors Android's
  `PrivacyMode` enum.
- 40 tests — port `PiiScrubberTest.kt` verbatim by JSON-string comparison.

**Definition of done:** customer running with `.privacy` preset never sees
PII land in Dash0 — proven by an integration test that pipes 50 known-PII
strings through the SDK and asserts every one is masked in the captured
exporter output.

### 1.2 Retry + status surfacing (1 week)

- `RetryableExporter` wrapping any underlying `LogRecordExporter` /
  `SpanExporter` / `MetricExporter`. Exponential backoff with jitter
  (1s/2s/4s/8s/16s, capped). Persistent failure after 5 retries → drop
  with metric `dash0.exporter.dropped` counter.
- `ExportStatusManager` — actor-style observable status surface. Emits
  `dash0.exporter.status` log on every state change (`ok` →
  `transient_failure` → `recovered` → `terminal_failure`).
- Wire into `OTelMobile.start(config:)` — wrap every OTLP exporter in
  `RetryableExporter` by default. Customer can opt out via
  `MobileConfig.exportRetryConfig = .none`.
- 7 tests — port `RetryableExporterTest.kt` patterns: success path,
  transient → success, terminal → drop + counter increment, status
  notifications fire on transitions.

**Definition of done:** with airplane mode enabled then disabled mid-export,
the SDK retries and eventually delivers; failure counters increment when
we exceed retry budget; observable status surface lets a customer wire
their own UI / health endpoint without forking the SDK.

### 1.3 Sampling + identity + boot tracker (1 week)

- `DynamicSampler` + `SamplerFactory` + `SamplingConfig` — port from
  Android's runtime-adjustable sampler. Sampler decisions are
  log-scoped (per-log-record, not per-event-type). Default 100% emit.
- `UserIdentity` — `MobileConfig.userIdentity = UserIdentity(id:, attrs:)`.
  Sets `user.id` + arbitrary `user.*` attributes on every signal's
  resource.
- `BootTracker` — emits `app.boot` log on first launch, `app.warm_boot` on
  subsequent process starts within an SDK-defined window. Captures
  `app.boot.duration_ms` from `posix_spawn` time to first SDK call.
- `RateLimiter` — extracted from existing instrumentation code as a shared
  utility (max-events-per-window). Used by `screenshot`, `wireframe`,
  `errors` in 1.4 + 2.x.
- `EnrichingLogRecordExporter` — wraps any exporter to add resource-derived
  attributes (`mobile.app.version`, `mobile.device.locale`, `mobile.geo.*`)
  to every log record. Mirrors Android's processor-side enricher.
- ~30 tests across these modules.

**Definition of done:** customer can call `mobile.userIdentity = ...` and
every subsequent emission carries the user attributes; sampler can be
reconfigured at runtime via the policy poller; boot tracker emits
warm-boot vs cold-boot correctly across foreground/background transitions.

---

## Phase 2: UI instrumentation parity (~3 weeks)

**Why now:** Android's `tap` module alone has 18 tests. Without these, the
test-count gap can't close.

### 2.1 SwiftUI tap / gesture instrumentation (1 week)

- New `TapInstrumentation` Swift package target — auto-installed via
  `OTelMobile.start(config:)` when `AutoCaptureOptions.tap` is set.
- Use SwiftUI's `.simultaneousGesture(SpatialTapGesture(...))` injected
  via a `ViewModifier` tree-walk, similar to how `ScreenInstrumentation`
  bridges. Tap location is bucketed (Android does this too — quantize to
  nearest 10pt) before emission per `PrivacyConfig`.
- Emits `ui.tap` log + `ui.tap` zero-duration child span (controlled by
  `TapConfig.uiTelemetryMode = .events / .spans / .both`).
- 18 tests — mirrors Android's `TapInstrumentationTest.kt`.

### 2.2 SwiftUI scroll + text-input (1 week)

- `ScrollInstrumentation` — `ScrollView` `onPreferenceChange` of
  `OffsetKey` to emit throttled `ui.scroll` events (max 1/sec per
  ScrollView).
- `TextInputInstrumentation` — `.onSubmit` + `.focused()` bindings for
  `TextField` / `TextEditor`. Emits `ui.text_input` on focus loss with
  the field's accessibility identifier (NEVER the text content — that's
  what PII scrubber is for if customer opts in).
- ~15 tests across both modules.

### 2.3 Screenshot + Wireframe (1 week, *gated by* design review)

- Implementation of [`docs/design/screenshot-wireframe-privacy.md`](../design/screenshot-wireframe-privacy.md).
  Design must be reviewed + approved before code starts.
- `ScreenshotInstrumentation` — opt-in, consent-gated, redaction-on-capture
  walker, payload size cap, rate limiter from Phase 1.3.
- `WireframeInstrumentation` — view-hierarchy JSON tree, same gates.
- ~20 tests including OCR-based "no readable text leaked" assertion.

**Definition of done:** every Android UI module has an iOS analog. The
`UIInstrumentationParityTests` suite (new in Phase 3) compares emission
shapes line-for-line.

---

## Phase 3: Test backfill to ≥ 60% of Android (~3 weeks)

**Why now:** modules ported in 1+2 give us something to test against.

### 3.1 Buffer + processor coverage (1 week)

- Port Android's 89-test buffer suite to iOS `Buffering/`:
  `BufferSystemComprehensiveTests`, `BufferCrashPathTests` (we have a
  partial version from Phase 0), `MobileLogRecordProcessorTests`
  enrichment, dedup, ordering, overflow eviction.
- Backfill `DiskLogBuffer` to match Android's 26-test suite — TTL pruning
  edge cases (clock skew, future-dated events), size-budget eviction with
  partial-batch removal, recovery on schema-version mismatch.
- Target: 60+ new tests in `OTelMobileSDKTests/Buffering/`.

### 3.2 Policy + sampling runtime coverage (1 week)

- Port Android's 116-test policy evaluator runtime suite. Many of these
  are JSON-driven behavioural tests; copy the JSON bodies verbatim and
  match the expected match results.
- Port the 47-test sampler suite once Phase 1.3's sampler ships.
- Port the 16+ Recovery + 30+ AutocaptureTracker tests.
- Target: 80+ new tests in `OTelMobileSDKTests/Policy/` and
  `OTelMobileSDKTests/Lifecycle/`.

### 3.3 Network + session + privacy coverage (1 week)

- Network: 66 Android tests (currently 9 iOS) — header capture, allowed/
  ignored host edge cases, redirect handling, multipart body interception.
- Session: 26 Android tests (0 iOS) — UUID rotation on inactivity,
  UserDefaults persistence, manual reset, attribute stamping on every
  signal.
- PII: 60 Android tests — paired with Phase 1.1's port.
- Target: 100+ new tests across these areas.

**Definition of done at end of Phase 3:** ≥ 580 iOS tests (60% of
Android's ~980). Per-area coverage in
[`IOS_ANDROID_PARITY.md`](../IOS_ANDROID_PARITY.md) shows ≥ 50% for every
SDK area, with PII / sampling / autocapture at 100%.

---

## Phase 4: Scenario script + automation parity (~1 week)

**Why now:** scenario scripts assert on emissions that only exist after
Phases 1–3 ship.

### 4.1 Port the remaining 21 Android scenarios

The 7 we've shipped: us050, us057, us063, us071, us073, us077, plus
`validate-ios-uidriven.sh`. Remaining 21:

- us051 browse-refresh, us052 network-error, us053 get-directions,
  us054 multi-screen-nav, us055 form-input, us056 session-lifecycle,
  us058 battery-drain, us059 thermal-throttle, us060 memory-pressure,
  us061 combined-stress, us062 network-loss, us064 http-error-flush,
  us065 freeze-flush, us066 no-false-flush, us067 ram-overflow,
  us068 disk-ttl, us069 selective-flush, us070 timestamp-monotonic,
  us072 cross-signal, us074 dynamic-sampling, us075 continuous-periodic,
  us076 hybrid-mode

Each follows the same pattern: drive AstronomyShop via the XCUITest
journey loop with scenario-specific perturbations (network down, low
memory, thermal pressure via `simctl`), then query Dash0 MCP for the
expected emissions.

Most are mechanical ports (~30 min each). The "stress" ones
(us058–us061) need `simctl device thermal-state` + memory-pressure
helpers wrapped in lib-ios.

### 4.2 CI matrix expansion

- `.github/workflows/ios-tests.yml` — currently runs unit tests + smoke
  build. Add scheduled cron job that runs the full UI-driven validate
  suite against a hosted Dash0 sandbox, gates merge to `main` on a
  green run.
- New `lib-ios/simctl-stress.sh` — helpers for `simctl device thermal-state`,
  `simctl device memory-pressure`, `simctl status_bar override` (network
  state for us052/us062).

**Definition of done:** all 28 `validate-ios-us0XX-*.sh` scripts exist,
all pass `bash -n`, and at least 25 of 28 pass against a real Dash0
sandbox.

---

## Phase 5: Real-device validation + GA gates (~2 weeks)

**Why last:** signs off everything 1–4 actually works on hardware.

### 5.1 Real-device runs (1 week, mostly waiting)

- Run [`IOS_REAL_DEVICE_VALIDATION.md`](../IOS_REAL_DEVICE_VALIDATION.md)
  on iPhone 13 (iOS 16 baseline), iPhone 15, iPhone 17 (current target).
- Record results inline in the playbook doc per its template.
- Identify any sim-only assumptions in the SDK (e.g. mach API behavior
  differences, signal handler behaviour under crash-on-launch).

### 5.2 Performance budget (1 week)

- New `docs/IOS_PERFORMANCE_BUDGET.md` — baseline measurements:
  - SDK init time (target < 150ms cold start contribution)
  - Per-emit overhead (target < 50µs for `logger.emit(...)`)
  - Memory footprint at 1k buffered events (target < 12MB)
  - Battery impact over 1h continuous emission at 2Hz (target < 1%)
- `Benchmarks/` test target — XCTest performance baselines wired into
  CI as `xcodebuild test -test-iterations N -resultBundlePath ...`
  with regressions failing the build at +20% over baseline.
- Same metrics gathered on Android using existing benchmark suite, so
  parity assessment goes beyond "does it work" into "does it cost the
  same."

### 5.3 GA tag

- Cut `v1.0.0-alpha` after Phase 3
- `v1.0.0-beta` after Phase 4 + 1 week of soak under real-device demo
- `v1.0.0` GA after Phase 5 + sign-off from Barry

## Risks + mitigations

| Risk | Mitigation |
|---|---|
| SwiftUI accessibility tree regressions on iOS 27+ break XCUITest path | UITest-suite runs on every Xcode beta; we contributed `-DASH0_UI_TEST` bypass that we keep across versions |
| OTel-Swift upstream churn between phases (we already hit one ViewRegistry bug) | Pin to specific version in `Package.swift`; bump deliberately with full test re-run |
| Real-device validation surfaces issues unique to ProMotion (120Hz) or low-power devices | Phase 5 explicit budget for one ProMotion + one non-ProMotion + one >2-year-old device |
| PII scrubber port reveals Android logic that doesn't translate cleanly to Swift regex | Build a JSON-driven test bench first (Phase 1.1 day 1), validate that all 40 Android cases still hold for our regex syntax before writing the production code |
| Scope creep into porting Dash0-proprietary modules to be "OTel-compliant" | Scope explicitly excludes this; any "should we make `flushWindow` an OTel proposal?" conversation is a separate spec, not part of this epic |

## Success metrics (tracked weekly)

- iOS test count vs Android: chart toward ≥ 60% by end of Phase 3
- Scenario script count: 7 → 28 by end of Phase 4
- AstronomyShop demo signal volume in Dash0 (logs/spans/metrics per minute)
  on iOS vs Android: chart variance toward < 5% by end of Phase 5
- iOS SDK binary size: tracked, target ≤ Android equivalent (currently
  iOS is smaller, but adding modules will close that)

## Working split

This is a single-author SDK today. Ports below are independent enough
that two engineers could parallelise:
- Engineer A owns Phases 1.1, 1.2, 2.1, 3.2
- Engineer B owns Phases 1.3, 2.2, 2.3, 3.1, 3.3
- Phase 4 is mostly mechanical scenario porting — can fan out to as many
  engineers as we have available
- Phase 5 needs a human with physical devices

## What this epic explicitly does NOT change

- Dash0-proprietary control plane stays Dash0-proprietary. The policy DSL,
  predictive export, fleet alerts, dual-tier buffer, `flushWindow`, and
  `Dash0-Dataset` header are intentional product differentiators against
  vanilla `opentelemetry-swift`. They will be documented as such in
  customer-facing materials and not labelled "OTel-native."
- The OTel-native claim applies only at the SDK data-plane boundary:
  emission APIs (`logger.logRecordBuilder()`, `tracer.spanBuilder()`,
  `meter.counterBuilder()`), wire format (OTLP/HTTP, OTLP/gRPC), and
  resource semantic conventions. Customer can swap our SDK's `endpoint`
  to any OTel collector and emission code is unchanged. CI proves that
  with a swap test (added in Phase 4.2).
