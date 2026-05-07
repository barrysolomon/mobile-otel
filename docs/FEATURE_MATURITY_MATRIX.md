# Feature Maturity Matrix

**Last updated:** 2026-05-07  
**SDK version:** 0.2.0-alpha

This document defines the maturity level of every feature in the Dash0 Mobile Observability SDK across Android, iOS, and React Native platforms.

## Maturity Levels

| Level | Meaning | Stability Guarantee |
|-------|---------|---------------------|
| **GA** | Production-ready. OTel semconv compliant. Fully tested. | API stable; breaking changes only in major versions |
| **Beta** | Feature-complete, undergoing final validation. May have edge cases. | API mostly stable; minor changes possible in minor versions |
| **Incubating** | Opt-in, non-OTel-native, or undergoing active development. | API may change without notice |

---

## Android SDK (`otel-android-mobile/`)

### Auto-Instrumentation Modules

| Module | Maturity | OTel Native | Tests | Signal Types |
|--------|----------|-------------|-------|--------------|
| Tap | GA | Yes | Unit + E2E | `ui.tap`, `ui.long_press`, `ui.swipe` spans + logs |
| Scroll | GA | Yes | Unit + E2E | `ui.scroll` spans |
| Text Input | GA | Yes | Unit | `ui.text_input` spans |
| Back Press | GA | Yes | Unit + E2E | `ui.back_press` spans |
| Screen View | GA | Yes | Unit + E2E | `ui.screen_view` logs + `page.*` spans |
| Freeze / ANR | GA | Yes | Unit + E2E | `ui.freeze` events |
| Errors | GA | Yes | Unit + E2E | Exception logs, rate-limited (10/min, 5-min dedup) |
| Lifecycle | GA | Yes | Unit + E2E | Activity/fragment transition spans |
| Vitals | GA | Yes | Unit + E2E | Memory, battery, jank, app-start (OTel Meter gauges) |
| Network | GA | Yes | Unit | OkHttp interceptor, HTTP semconv, privacy presets |
| Screenshot | Incubating | No | Unit | `ui.screenshot` with base64 data URL |
| Wireframe | Incubating | No | Unit | `ui.wireframe` JSON tree (1-5 KB) |
| Debug Widget | Incubating | No | Unit | In-app overlay (dev/demo only) |
| Database | Incubating | Yes | Unit | `db.query` spans via Room QueryCallback |
| File I/O | Incubating | Yes | Unit | `io.read`/`io.write` spans |
| System Events | Incubating | Yes | Unit | Battery/power/airplane mode broadcasts |
| Timber | Incubating | Yes | Unit | Timber log bridge to OTel log records |
| Amplify DataStore | Incubating | Yes | Unit | AWS Amplify DataStore operation spans |
| Screen Orientation | Incubating | Yes | Unit | Configuration change events |
| Compose Click | Incubating | Yes | Unit | Jetpack Compose gesture detection |
| Compose Navigation | Incubating | Yes | Unit | Compose NavHost screen tracking (`ui.screen_view` + page spans) |

### Core Subsystems

| Subsystem | Maturity | Tests | Key Properties |
|-----------|----------|-------|----------------|
| Dual-tier buffering | GA | 26+ | RAM (5K events) + SQLite (50 MB, 24h TTL), crash-safe |
| Export pipeline | GA | 10+ | OTLP/gRPC, RetryableExporter with exponential backoff |
| Export modes | GA | 194 config tests | CONDITIONAL, CONTINUOUS, HYBRID (default) |
| Policy evaluation | GA | 194 behavioral tests | DSL engine, geo/device matching, 21 matcher types |
| Session management | GA | Integrated | ID rotation, boot tracking, crash recovery markers |
| PII scrubbing | GA | 35 | URL, deep link, exception, stack trace, attribute scrubbing |
| Predictive export | Beta | Stress tests | Device health scoring, pre-emptive flush on risk >= 0.7 |
| DSL v2 parser | GA | 25 | Auto-detect v1/v2, negotiation, 10 action types |
| DSL v1 parser | GA (deprecated) | Integrated | Sunset timeline TBD |

---

## iOS SDK (`otel-ios-mobile/`)

| Feature | Maturity | Tests | Parity with Android |
|---------|----------|-------|---------------------|
| Errors / crash recovery | GA | 34+ | Full |
| Freeze detection | GA | Integrated | Full |
| Lifecycle | GA | Unit | Full |
| Network (URLProtocol) | GA | Unit | Full (different interception mechanism) |
| Screen view | GA | Integrated | Full |
| Vitals | GA | Integrated | Full |
| Dual-tier buffering | GA | Unit | Full (RAM + disk) |
| OTLP export (HTTP) | GA | Unit | Partial (HTTP/4318 vs Android gRPC/4317) |
| Session management | GA | Integrated | Full |
| Dynamic sampling | GA | Unit | Full |
| Policy evaluation | Beta | Integrated | Subset of Android matchers |
| Screenshot | Not implemented | — | Roadmap |
| Wireframe | Not implemented | — | Roadmap |
| Database | Not implemented | — | Roadmap |

---

## React Native SDK (`packages/react-native/`)

| Feature | Maturity | Tests | Implementation |
|---------|----------|-------|----------------|
| Native bridge | GA | 18 | JS→native method channel, 50 ms batching |
| Fetch/XHR spans | GA | 6 | Auto-capture, privacy presets |
| JS error capture | GA | 3 | Global error handler + unhandled rejection |
| AppState lifecycle | GA | 2 | Foreground/background detection |
| React Navigation | GA | 2 | `installReactNavigationInstrumentation(navRef)` |
| Tap telemetry | GA | 2 | `withTapTelemetry('target', handler)` |
| OTel API shim | GA | 2 | `otel.trace.getTracer(...)` for 3rd-party compat |

All RN features delegate buffering, policy evaluation, and OTLP export to the underlying native SDKs (Android/iOS). The JS layer is a thin marshaller.

---

## Go Collector Processor (`mobilepolicyprocessor/`)

| Feature | Maturity | Tests |
|---------|----------|-------|
| Mobile policy processor | GA | `go test -race` |
| Server-side policy evaluation | GA | Unit |

---

## Control Plane (`mobile-otel-control-plane/`)

| Feature | Maturity |
|---------|----------|
| React Flow graph editor | GA |
| graphToDSL v1 compiler | GA (deprecated) |
| graphToDSL v2 compiler | GA |
| Go gateway API | GA |
| Config versioning (v1/v2) | GA |
| Device management | Beta |

---

## Platform Feature Matrix

Cross-platform comparison of key capabilities:

| Capability | Android | iOS | RN |
|------------|---------|-----|-----|
| Auto-instrumentation modules | 20 | 6 | 7 (thin) |
| Dual-tier buffering | GA | GA | Delegated |
| Export modes (3) | GA | GA | Delegated |
| Policy DSL evaluation | GA | Beta | Delegated |
| Predictive export | Beta | Roadmap | Delegated |
| PII scrubbing | GA | GA | Delegated |
| Crash recovery | GA | GA | Delegated |
| OTLP transport | gRPC (:4317) | HTTP (:4318) | Per-platform |
| Screenshot capture | Incubating | Roadmap | Roadmap |
| Wireframe capture | Incubating | Roadmap | Roadmap |
| Debug widget | Incubating | Roadmap | Roadmap |
| UAT matrix validated | 12/12 | 12/12 | 24/24 |

---

## Promotion Criteria

To promote a feature from one maturity level to the next:

**Incubating → Beta:**
- Unit tests with >80% branch coverage
- No known crash bugs
- API reviewed for consistency with GA modules

**Beta → GA:**
- E2E validation in UAT matrix (all cells green)
- OTel semantic convention compliance (where applicable)
- Documentation in SDK guide
- No API changes in last 2 releases
- Performance profiled (no jank, no ANR contribution)
