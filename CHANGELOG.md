# Changelog

All notable changes to this project will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

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
