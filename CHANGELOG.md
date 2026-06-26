# Changelog

All notable changes to this project will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

## [0.5.0-alpha] — 2026-06-26

Upstream `opentelemetry-android` 1.5.0 alignment and `screen.name` → `app.screen.name` semantic-convention convergence across all three platforms.

### Changed

- **Bumped upstream `opentelemetry-android` 1.2.0-alpha → 1.5.0** (`session`, `android-instrumentation`, `agent-api`). 1.5.0 marked the Instrumentation API stable and (in 1.3.0) changed `AndroidInstrumentation.install`'s signature from a single `InstallationContext` to `(Context, OpenTelemetryRum)`. The adapter layer migrates via a thin new `MobileOpenTelemetryRum` shim, so the 11 concrete instrumentation modules are unaffected. `kotlin-stdlib` is pinned to 2.2.10 across modules (upstream drags 2.4.0, unreadable by the bundled compiler).
- **`@Supersedes` audit vs 1.5.0** — zero name drift. The new upstream `thermal` / `power_save_mode` modules are deliberately **not** superseded (our thermal signal is a metric gauge vs upstream's semconv events; we don't emit power-save), so `discoverAll` consumers get both by design.

### Added

- **`app.screen.name` semantic-convention convergence.** Upstream renamed `screen.name` → `app.screen.name` in 1.5.0. The log-processor `onEmit` choke point now mirrors both legacy spellings (`mobile.screen.name` on Android, `screen.name` on iOS/RN/app-authored) onto `app.screen.name` on Android **and** iOS; React Native emits it directly at its single navigation site. The legacy aliases remain (transition aliases, dropped at 1.0); spans keep the alias until the 1.0 flip, mirroring the session-id treatment. `MobileSemconv.APP_SCREEN_NAME` added. (`device.crash` → `app.crash` needed no action — already aligned.) See [docs/SEMCONV_AUDIT.md](docs/SEMCONV_AUDIT.md).
- **`docs/epics/UPSTREAM_CONTRIBUTION.md`** — proposal scoping a `MobileInstrumentation` + `WindowEventHub` contribution upstream now that the Instrumentation API is stable.

### Fixed

- **iOS `exportBuffered` missing `await`** — `LogRecordExporter.export(logRecords:)` became `async` in the resolved opentelemetry-swift; the call wasn't awaited, breaking the iOS build. Now awaited.

### Tests

- Screen-name convergence tests on all three platforms (Android `ScreenNameConvergenceTest`, iOS `ScreenNameConvergenceTests`, RN `navigation.test.ts`), plus a `@Supersedes` coexistence test locking the thermal/power-save decision. Full Android unit suite + 152 RN jest + iOS `xcodebuild` (4/4 on iPhone 17 sim) green; Android on-device 23/23 + 20/20.
- **Validated end-to-end in Dash0** — `app.screen.name` confirmed landing alongside the legacy key on Android (CONTINUOUS + HYBRID), iOS (CONTINUOUS), and React-Native-Android (dash0Continuous).

## [0.4.2-alpha] — 2026-06-15

Crash-span recovery and Dash0 auth documentation.

### Fixed

- **`PersistingSpanProcessor` — crash-safe checkpoint for long-running spans.** Long-running `page.*` and `journey.*` spans that sit open in `BatchSpanProcessor`'s RAM queue when the process is killed were never exported. Their children had already reached the backend; the parent had not. Dash0 rendered the children as orphans under a "Missing span" placeholder. The new `PersistingSpanProcessor` wraps `BatchSpanProcessor`: on `onStart` it writes a `SpanCheckpoint` (traceId/spanId/parentSpanId/name/startEpochNanos) to SharedPreferences via synchronous `commit()` so it survives a crash. On `onEnd` it removes the checkpoint — span ended normally, no recovery needed. On next launch, `recoverAndExport()` synthesises truncated `SpanData` for every un-ended checkpoint, preserving the original IDs so Dash0 can stitch the previously-orphaned children back under the recovered parent span. Recovery runs before `SdkTracerProvider.build()` to guarantee synthesised parents arrive before new children sharing the same traceId. Verified E2E: real `adb force-stop` → relaunch → recovered span lands in Dash0 with `crash.recovered=true`, correct `service.name`, and `parentSpanId` intact.
- **Recovered spans now carry the real SDK resource.** `SynthesizedSpanData` was returning `Resource.getDefault()`, causing recovered spans to appear in Dash0 as `unknown_service:java`. `PersistingSpanProcessor` now accepts the SDK resource at construction and stamps every recovered span with it.

### Added

- **`SpanCheckpoint` / `SpanCheckpointStore`** — new public types under `io.opentelemetry.android.mobile.buffering`. `SharedPreferencesSpanCheckpointStore` is the production implementation; `InMemorySpanCheckpointStore` ships in the test sources for easy unit testing.
- **Dash0 auth documentation.** README now includes "Sending to Dash0?" snippets for all three SDKs (Android, iOS, React Native), showing the exact field names, endpoint, and header conventions for each platform.
- **`dash0` CLI v1.14.0 install recipe** added to the Dash0 CLI reference (homebrew tap is gone; direct GitHub release download is the install path).

### Tests

- 14 TDD tests covering `PersistingSpanProcessor` (RED-GREEN verified): checkpoint lifecycle, crash recovery, `parentSpanId` preservation (the exact Dash0 stitching scenario), `crash.recovered` attribute, store clear after recovery, delegate forwarding, and SDK resource propagation.

## [0.4.1-alpha] — 2026-06-12

Pre-soak hardening — the crash-reporter coexistence and self-observability an integrator needs before adopting the SDK alongside their existing tooling.

### Fixed

- **iOS crash handlers now coexist with a host crash reporter on the signal path too.** Installing the SDK used to silently disconnect a PLCrashReporter / KSCrash / Sentry signal handler installed before it (nothing was captured to chain to), and `stop()` reset signal dispositions to `SIG_DFL` instead of restoring the host's. Now the SDK captures the previous `sigaction` per fatal signal, chains to it after writing its crash marker, and restores it on uninstall — matching the NSException path, which already chained. (The Android equivalent shipped in 0.3.1-alpha.)
- **`getBufferStats().diskBufferSize` no longer sticks at a stale `0`.** A race between an async disk persist and a concurrent stats read could permanently cache a pre-insert count (regression from the 0.4.0 cache change). The cache now seeds without clobbering published writes and re-seeds authoritatively after each insert.

### Added

- **`sdk.events.dropped` metric** (`reason` ∈ `oversize` | `remote_gate` | `ttl_expired`) — a broken or remotely-disabled SDK is now distinguishable from a quiet app, and the counter reports even while the kill switch is dropping everything else. See [SEMCONV_AUDIT.md](docs/SEMCONV_AUDIT.md).
- **Maven Central groundwork** — complete POM metadata on every published artifact and [docs/MAVEN_CENTRAL.md](docs/MAVEN_CENTRAL.md), the runbook for moving off GitHub Packages (one open decision: the published groupId).

### CI

- The journey/fault/offline scenario suites now run in `device-tests.yml` with the demo backend started on the runner.
- Demo-backend tracing skips initialization under tests / placeholder endpoints (a unit-test run no longer fails on an ENOTFOUND to the example collector host).


## [0.4.0-alpha] — 2026-06-12

The deliberate breaking release before the API freeze: the 1.0 stability gates were executed ([API_STABILITY.md](docs/API_STABILITY.md), [SEMCONV_AUDIT.md](docs/SEMCONV_AUDIT.md)), and every scheduled breaking change landed here in one batch — pre-1.0 minors may break; this is the last one planned to.

### BREAKING — API (Android)

- **Implementation classes are no longer public.** `MobileLogRecordProcessor`, `RetryableExporter`, `DiskLogBuffer` (+ Room types), `LoggingHttpExporter`, `EnrichingLogRecordExporter`, `ErrorCoalescer`, and `FleetAlertHandler` are now `internal`. Migration: flush and stats are first-class on the provider — `getLoggerProvider().flushWindow(minutes)` / `.getBufferStats()` / `.forceFlush()`; export-status display goes through `ExportStatusManager.addListener`. `BufferStats` is now a top-level public type.
- **`MobileOtel.setSessionEnabled` removed** — it was never implemented (logged a warning, did nothing). Configure `SessionConfig.enabled` at start.
- **`MobileOtel.getErrorStatistics()/getBufferStats()` have typed returns** (`ErrorStatistics?` / `BufferStats?`).
- **`@Incubating` now means something:** removed from the stable entry points (`OTelMobile`, `MobileOtel`, `MobileConfig`, `OpenTelemetryMobile`, `OtlpProtocol`, `ExportMode`) — previously every `start()` call warned — and added member-level exactly where the [API freeze list](docs/API_STABILITY.md) says the surface may still move.

### BREAKING — telemetry names ([SEMCONV_AUDIT.md](docs/SEMCONV_AUDIT.md))

- **iOS screen-view events are now `ui.screen_view`** (was `screen.view`) — matches Android and React Native. Update dashboards/alerts filtering on the old name.
- **Android resource attribute `device.platform` dropped** (redundant with `os.name`).
- **Android now emits `session.id`** (the OTel semconv name, matching iOS) on every event, alongside the legacy `mobile.session.id` — the alias is dual-emitted through 0.x and drops at 1.0. Migrate dashboards to `session.id`.

### Added

- `MobileLoggerProvider.flushWindow(minutes)` and `getBufferStats()` — the public home for what consumers previously reached through the (now internal) processor.
- **1.0 governance docs:** [API_STABILITY.md](docs/API_STABILITY.md) tiers every public symbol on all three platforms (stable-at-1.0 / incubating / sealed); [SEMCONV_AUDIT.md](docs/SEMCONV_AUDIT.md) freezes every emitted telemetry name with its semconv status; [VERSIONING.md](docs/VERSIONING.md) carries the concrete deprecation policy (one-MINOR residence, dual-emit cycle for telemetry renames).

### Deferred (documented)

- iOS `app.launch` vs `app.start` convergence: investigation showed they are different emitters (lifecycle install vs start-timing vitals); a blind rename would double-count app starts. Needs an ownership decision before 1.0 — tracked in SEMCONV_AUDIT.md.


## [0.3.1-alpha] — 2026-06-12

Launch-readiness release: every P0 gate from the QA hardening plan is now closed, and the two real defects those gates caught are fixed — Android SDK init no longer blocks the app's main thread for ~a quarter second, and `stop()` is callable from any thread.

### Fixed

- **Android SDK init is ~3× faster and off the main thread.** `OTelMobile.start()` blocked the host's main thread for 205–298 ms (measured against TEST_PLAN HS-001's 50 ms budget — that cost was added to every host app's cold start). Now ~28 ms device-equivalent: EncryptedSharedPreferences/Keystore warm-up, the Room/SQLCipher disk-buffer open, OTLP exporter construction, the policy evaluator, predictive export, and the crash-recovery disk probe all moved to background threads. Semantics preserved: a single observable session id per launch (one-shot reconcile against the persisted id), crash-mirror seqId dedup (wall-clock seeding + disk re-raise), `identify()` writes still visible on return. (#42)
- **`OTelMobile.stop()` no longer crashes off the main thread.** `LifecycleInstrumentation.uninstall` called `LifecycleRegistry.removeObserver` directly, which androidx asserts must run on main; teardown from a worker/JS thread threw `IllegalStateException`. Now hops to main, mirroring what `install()` already did. (#42)

### Added — launch-gate test coverage (#41)

- **Receipt gate for all four platforms:** `scripts/e2e/run-platform-e2e.sh` drives the iOS-native (Schedulr), RN-Android, and RN-iOS demos through launch → foreground-cycle → crash → recovery and fails unless the expected telemetry actually lands in Dash0, run-scoped. (Android-native was already gated via `run-e2e.sh`.) Schedulr gained the same `-DASH0_CRASH_NOW` launch-argument crash hook the AstronomyShop demos have.
- **Kill-switch end-to-end proof:** `KillSwitchEndToEndTest` drives the real remote-config poll path over local HTTP and proves `sdk.enabled=false` stops both export choke points (logs and spans), then re-enables — the README's kill-switch claim is now backed by a test.
- **Crash-handler chaining:** `CrashHandlerChainingTest` proves a Crashlytics-style `UncaughtExceptionHandler` installed before or after the SDK still runs, the SDK persists its buffer *before* delegating, and exactly one `app.crash` is reported per crash.
- **Startup budget gate:** `StartupBudgetTest` enforces HS-001's 50 ms main-thread budget on every instrumented run (bare 50 ms on physical devices; documented 3× allowance on software-rendered emulators, which the pre-fix code still fails). `StopThreadSafetyTest` guards the off-main `stop()` path.
- `dash0_assert.py` supports trailing-`*` prefix matching for span/log names.

## [0.3.0-alpha] — 2026-06-11

Data-durability release: a full QA audit of the flush/buffer path found and fixed three silent data-loss bugs on Android, and the release/test gates were hardened so a regression in this class physically cannot reach a release tag again. Minor (not patch) because the flush-result contract changed behavior.

### BREAKING (behavior) — review before upgrading

- **`forceFlush()` / `flushWindow()` results now complete only after export *and* buffer cleanup settle.** Previously a deferred `forceFlush()` returned instant fake success while a flush was still in progress. Callers that `join()`/await the result now get the real outcome (the in-progress flush's completion) instead of a lie; callers that ignored the result are unaffected. (#37, #38)

### Fixed — silent data loss (Android flush/buffer path)

- **Force-flush cleanup no longer deletes unexported events.** Cleanup ran `clearAll()` on the disk buffer, deleting rows persisted *after* the flush snapshot — events that were never exported. Cleanup now deletes exactly the exported row ids. `flushWindow()` had the identical flaw and got the identical fix. (#38)
- **Events mid-overflow are no longer invisible to flush.** The RAM→disk overflow move left events in neither tier for a window, so a concurrent flush under-exported (observed: 245 of 500). The move is now atomic with respect to flush snapshots. (#38)
- **A failed flush can be retried.** The flush gate was released asynchronously even on failure, so an immediate retry silently no-opped. Failure paths now release the gate synchronously. (#37, #38)
- **Disk buffer survives a missing SQLCipher native library.** The SDK no longer deletes the encrypted disk buffer (offline data) when the SQLCipher native lib fails to load — it falls back without destroying data. (#33)
- **`screen.render` emits one span per resume, not one per frame.** (#32)
- **`OtlpProtocol` is wired through the `MobileOtel` DSL**, and RN Android gRPC export works. (#34)
- **RN iOS launches again** — `react-native` is deduped in Metro so the `PlatformConstants` crash is gone. (#35)

### CI / release safety

- **Dash0 receipt gate:** e2e tests are green only when telemetry actually lands in Dash0, scoped to the current run (`--since`) and polling through ingestion latency (`--retry-for`). Skipping requires an explicit `--allow-no-dash0`. (#36, #38)
- **`run-e2e.sh` exits non-zero on suite failures** (previously always 0). (#38)
- **Publishing refuses red commits:** tagging `v*` now verifies the tagged SHA has green CI before npm/Maven publish. (#38)
- **R8 minified-consumer gate:** the demo app release build runs with `minifyEnabled = true` on every push; CI asserts the public entry points survive shrinking identity-mapped. (#39)
- **Pre-1.0 npm releases also set the `latest` dist-tag** so a bare `npm install` gets the newest 0.x. (#30)

## [0.2.1-alpha] — 2026-06-10

Patch release: correct the SDK's self-reported version and complete Android transport-security parity (both surfaced by the v0.2.0-alpha documentation audit).

### Fixed

- **The SDK now reports its real version.** `0.2.0-alpha` shipped with hardcoded `0.1.0-alpha` version literals, so emitted telemetry carried the wrong `telemetry.sdk.version` (iOS) / `telemetry.distro.version` (React Native). Fixed:
  - iOS — `ResourceBuilder.sdkVersion` → `0.2.1-alpha`.
  - React Native — `DISTRO_VERSION` → `0.2.1-alpha`.
  - (Android already derived its version correctly from the OpenTelemetry SDK resource.)

### Added

- **Android transport-security parity with iOS.** `0.2.0-alpha` shipped transport security on iOS only; Android now matches, with the same `MobileConfig` API names/semantics:
  - **`allowInsecureTransport`** (default `false`) — cleartext `http://` to a non-loopback host is now *rejected* (export disabled / poller skipped, gracefully — never crashes the host), not merely logged. Loopback (`localhost`/`127.0.0.1`/`::1`/`*.local`/`10.0.2.2`) is exempt.
  - **Certificate / public-key pinning** (`pinningConfig`: SPKI SHA-256 pins and/or DER certs) — applied via OkHttp `CertificatePinner` on the config poller and a pinning `TrustManager` on the OTLP/HTTP exporter. Pin mismatch fails only that connection. (Pinning requires the HTTP protocol — the default; a warning is logged if set with `protocol = GRPC`.)
  - **`configSigningKey`** (HMAC-SHA256) — when set, the remote-config payload's `X-Dash0-Config-Signature` is verified (constant-time) before applying; failure keeps the last-applied config. So a MITM/OTA payload can't flip the kill switch on Android either.

  The iOS source comments that referenced "Android's `MobileConfig.allowInsecureTransport` / `pinningConfig` / `configSigningKey`" are now accurate.

### Docs

- Bumped install coordinates to `0.2.1-alpha` across READMEs and guides (npm `@0.2.1-alpha` / dist-tag `@alpha`, Android Maven `0.2.1-alpha`, iOS SwiftPM tag `v0.2.1-alpha`). Historical "new in 0.2.0-alpha" provenance notes left intact.

## [0.2.0-alpha] — 2026-06-10

First release hardened against a real production integration (Loper — Expo SDK 56 / RN 0.85, ~400k users, self-hosted OTel collector → Dash0). Thanks to Loper engineering for an exceptional teardown with reproductions and patches. This entry consolidates the earlier (never-published) production-readiness work with the Loper fixes and the security/reliability hardening pass.

### BREAKING (behavior) — review before upgrading

- **Android default OTLP protocol is now HTTP/protobuf** (was gRPC). Both platforms now target one collector endpoint (`<endpoint>/v1/{logs,traces,metrics}`), and exports traverse HTTPS-terminating proxies / managed ingress that cannot forward HTTP/2 gRPC. Restore gRPC with `MobileConfig.protocol = OtlpProtocol.GRPC`. *(Loper #3)*
- **React Native manual spans now default to always-on sampling.** Native auto-instrumentation keeps `dynamic(0.1)`; only the RN-bridged default changed. RN manual spans are root spans with arbitrary names, so the old default silently dropped ~90% of a user's first span (on iOS the dropped span was a non-recording `PropagatedSpan` whose `end()` was a silent no-op). Set `sampling` in `StartConfig` to override. *(Loper #4)*
- **iOS screenshot & wireframe capture now default OFF** behind an explicit consent gate.
- **iOS remote-config polling now defaults ON** so the remote kill switch works out of the box.
- **Default export mode is HYBRID** (was CONDITIONAL) — periodic device heartbeats + metrics out of the box, still supporting policy-triggered selective flush. Explicit `ExportMode.CONDITIONAL` users unaffected.

### Added — consumability & cross-platform parity (from Loper feedback)

- **Android: the full module set now publishes to GitHub Packages** — `mobile-core` and all 21 `mobile-instrumentation-*` modules, not just the umbrella. Consumers can finally resolve `io.opentelemetry.android:mobile`'s dependency tree. *(Loper #1)*
- **`OtlpProtocol` (HTTP_PROTOBUF | GRPC) + `protocol` on Android `MobileConfig`** with per-signal URL building (trailing-slash safe). *(Loper #3)*
- **Sampling configurable via the RN `StartConfig`** — `sampling: { strategy: 'always_on' | 'always_off' | 'dynamic'; normalRate?; highPriorityRate? }`, threaded to both native sinks. *(Loper #4)*
- **Native Android RN network instrumentation** — an OkHttp interceptor installed before JS runs (captures `expo/fetch`, which Expo SDK 52+ routes through OkHttp instead of XHR), recording native CLIENT spans and **injecting W3C `traceparent`** from the real native span context. Android mobile→backend distributed traces now stitch (iOS already did). JS XHR shim auto-gated off on Android to prevent double-counting. Host-safe by construction: telemetry failure never affects the host request. *(Loper #5)*

### Added — features & hardening

- **Remote kill switch + global sampling** over remote config (`sdk.enabled` / `sample_rate`), honored on all platforms; transitively covers React Native.
- **Capture consent API** (`shouldCapture`) + deterministic SwiftUI/UIKit redaction (replaces a class-name heuristic).
- **Transport security**:
  - **iOS** — HTTPS enforcement (cleartext rejected unless `allowInsecureTransport`), optional certificate / public-key **pinning**, and **HMAC-signed remote config** (`configSigningKey`) so the kill switch can't be flipped by a MITM/OTA payload.
  - **Android** — HTTPS enforcement (logs a prominent error on cleartext to a non-loopback host) + disk at-rest encryption (below). Cert pinning, `allowInsecureTransport`, and signed-config verification are **not yet implemented on Android** (iOS-only this release); tracked as a follow-up.
- **Android disk-buffer encryption at rest** (SQLCipher + Android Keystore) — parity with iOS `NSFileProtection`.
- **Android RAM byte caps** (10 MB total / 256 KB per event), **iOS error rate-limiter + dedup**, **O(1) RN-iOS live-span store** (was unbounded).
- **iOS CI restored** (cost-bounded: path-filtered macOS job + nightly), a dependency-free **secret-scan** CI job, and the **RN-iOS production sink is now compiled and unit-tested** in CI.

### Fixed

- **iOS compile failure against current Xcode/Swift** at the `v0.1.0-alpha` tag (async exporter signature) — resolved in current code; this tag builds on Xcode 26.x. *(Loper #2)*
- **SDK shutdown now flushes all pending telemetry** before shutting down (was dropping buffered telemetry on normal termination).
- **`DiskLogBuffer.getEventCount()` no longer blocks the main thread** — cached `AtomicInteger` instead of a `runBlocking` `COUNT(*)` reachable from gauge callbacks.
- **`FleetAlertHandler` collections are now thread-safe** (`CopyOnWriteArrayList` / `ConcurrentHashMap`).
- **`persistedToDisk` set no longer grows unbounded** — periodic pruning in the crash-safety mirror task.
- **`MobileLoggerProvider` singleton clears on shutdown** — allows re-init in process-reuse scenarios.
- Production-readiness review fixes: Android ingest-token Logcat leak, iOS off-main UIKit capture, RN network-interceptor fault isolation, Android touch-dispatch crash isolation, the RN-iOS sink compile defect, breadcrumb/URL PII scrubbing, and ~25 more.
- Cross-platform kill-switch defects caught in adversarial review: span sampling keyed on opposite halves of the trace ID (aligned both to the OTel-standard lower bytes); JSON numeric `"enabled": 0/1` wrongly disabling iOS.

### Docs

- Documented **Expo SDK 52+ `fetch` behavior** and why native Android network instrumentation is the default RN story.
- `docs/PRODUCTION_READINESS_REVIEW.md`, `docs/design/remote-kill-switch.md`, updated screenshot/wireframe privacy design.

### Upgrading

1. Bump `@barrysolomon/mobile-react-native` → `0.2.0-alpha`, `io.opentelemetry.android:mobile` → `0.2.0-alpha`, iOS SwiftPM tag → `v0.2.0-alpha`.
2. gRPC-only collector? Set `MobileConfig.protocol = OtlpProtocol.GRPC` (Android).
3. Relied on 10% RN sampling? Set `sampling: { strategy: 'dynamic', normalRate: 0.1 }` in `StartConfig` (or sample in the collector).
4. Want iOS screenshot/wireframe capture? Opt in and provide a `shouldCapture` consent gate.

### Platforms (UAT)

- Android native, React Native Android, React Native iOS UAT matrices: 12/12 green
- iOS native SwiftPM: green

## [0.1.0-alpha] — 2026-03-13

Initial alpha release of the OpenTelemetry Android Mobile SDK.

### Added

**Android SDK (`otel-android-mobile`)**
- Dual-tier ring buffer: RAM (5 000 events) → SQLite disk (50 MB, 24 h TTL) with crash survival
- Three export modes: `CONTINUOUS`, `CONDITIONAL`, `HYBRID`
- Selective flush: `flushWindow(minutes)` exports only the relevant time window
- On-device export policy DSL: event-driven triggers, condition evaluation, flush actions
- Auto-instrumentation modules: tap, scroll, text input, back press, screen view, freeze detection, crash recovery
- Error instrumentation: uncaught exceptions, coroutine errors, deduplication (5 min), rate limiting (10/min)
- Device metrics: battery, memory, CPU, network, storage, thermal, display via OTel metrics
- App vitals: cold/warm start, TTID, jank detection, input latency, ANR risk
- Predictive export: on-device crash/network-loss risk scoring with pre-emptive flush
- Breadcrumb trail: `JourneyBreadcrumb` captured across user journey for contextual error diagnosis
- Network interceptor: `OTelNetworkInterceptor` for OkHttp — W3C trace context propagation, HTTP semconv attributes
- `OTelMobileBuilder` modular API with `@Incubating` stability markers
- `MobileSemconv` central constants for all mobile-specific attribute keys
- Session management with configurable rotation, persistence across restarts

**Collector Processor (`mobilepolicyprocessor`)**
- Custom OpenTelemetry Collector processor that evaluates mobile export policies server-side
- Annotates logs with policy match results for downstream routing

**OTel Semantic Convention compliance**
- Exception attributes: `exception.type`, `exception.message`, `exception.stacktrace`
- HTTP attributes: full OTel HTTP semconv via `io.opentelemetry.semconv.*`
- Resource attributes: `os.name`, `os.version`, `device.model.name`, `device.manufacturer`, `telemetry.sdk.*`
- Metric units: UCUM-compliant (`By` for bytes, `s` for seconds, `%` for percent)
- `SpanKind.INTERNAL` on all UI interaction spans
- Fully-qualified instrumentation scope names (`io.opentelemetry.android.mobile.*`)

**Demo App (`examples/demo-app`)**
- Scheduling app with full SDK integration demonstrating all instrumentation modules
- Espresso scenario tests: user journeys, fault injection, conditional flush, stress scenarios
- `ConfigManager.isDash0Configured()` guard — tests skip gracefully when credentials absent

### Known Limitations

- Compose Navigation not yet instrumented (Fragment/Activity only)
- ProGuard/R8 symbolication for stack trace deobfuscation not implemented
- Collector processor requires custom collector build (not yet in `opentelemetry-collector-contrib`)
- Min SDK: Android 8.0 (API 26)

### Dependencies

| Component | Key Dependencies |
|-----------|-----------------|
| Android SDK | OpenTelemetry SDK 1.58.0, Room 2.8.4, OkHttp 4.12.0, Coroutines 1.10.2 |
| Collector Processor | Go 1.24, OTel Collector 1.39.0 |

[Unreleased]: https://github.com/barrysolomon/mobile-otel/compare/mobile-v0.2.0-alpha...HEAD
[0.2.0-alpha]: https://github.com/barrysolomon/mobile-otel/compare/mobile-v0.1.0-alpha...mobile-v0.2.0-alpha
[0.1.0-alpha]: https://github.com/barrysolomon/mobile-otel/releases/tag/mobile-v0.1.0-alpha
