# Changelog

All notable changes to this project will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

## [0.2.0-alpha] — 2026-05-07

Production readiness hardening release.

### Changed

- **Default export mode is now HYBRID** (was CONDITIONAL). HYBRID provides periodic device
  heartbeats + metrics out of the box while still supporting policy-triggered selective flush.
  Existing users who explicitly set `exportMode = ExportMode.CONDITIONAL` are unaffected.

### Fixed

- **SDK shutdown now flushes all pending telemetry** before shutting down. Previously,
  `OTelMobileHandle.stop()` called `shutdown()` without a preceding `forceFlush()`, which
  could silently drop buffered logs, spans, and metrics on normal app termination.
- **Disk event count no longer blocks the main thread**. The `DiskLogBuffer.getEventCount()`
  method used `runBlocking` with a `COUNT(*)` query that could fire on OTel metric gauge
  callbacks (any thread). Replaced with a cached `AtomicInteger` updated on insert/delete.
- **FleetAlertHandler collections are now thread-safe**. `alertTimestamps` and `activeOverrides`
  were plain `mutableListOf`/`mutableMapOf`; replaced with `CopyOnWriteArrayList` and
  `ConcurrentHashMap`.
- **persistedToDisk set no longer grows without bound**. Added periodic pruning in the
  crash-safety mirror task to remove entries for events no longer in the RAM buffer.
- **MobileLoggerProvider singleton clears on shutdown**. The `instance` field is now nulled
  after `shutdown()`, allowing re-initialization in process-reuse scenarios.

### Infrastructure

- **CI pipeline restored**. GitHub Actions workflow with 4 jobs: Android SDK tests + lint,
  Go processor tests with race detection, React Native Jest + typecheck, iOS SwiftPM tests.
- **Control plane CORS patterns derived from endpoint env var** instead of hardcoded regexes.
- **CollectorConfig defaults**: Dash0 US enabled by default; exported configs use HYBRID mode.

### Platforms

- Android native UAT matrix: 12/12 green
- React Native Android UAT matrix: 12/12 green
- React Native iOS UAT matrix: 12/12 green
- iOS native: 4/4 gates green, 107 tests passing

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
