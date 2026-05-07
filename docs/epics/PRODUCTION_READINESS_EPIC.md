# Epic: Production Readiness — Ship Without Falling Over

**Status:** ALL PHASES COMPLETE — 27/27 items + cross-platform parity sweep (Android + iOS + RN)
**Priority:** P0
**Owner:** Barry Solomon
**Created:** 2026-05-07
**Target:** Before first customer deployment

## Cross-Platform Parity (added after honest re-audit)

The 27-item punch list above was largely Android-native. After completing it,
a parity sweep across iOS and React Native confirmed each item is at parity
or n/a per platform constraints:

| Item | Android | iOS | RN | Notes |
|------|---------|-----|-----|-------|
| PR-001 shutdown→forceFlush | ✅ | ✅ pre-existing `shutdown(explicitTimeout:)` | ✅ bridge.flush + native.shutdown | RN delegates |
| PR-002 async flush (no main-thread block) | ✅ | n/a (Swift, no `runBlocking`) | n/a (JS) | Lang-level |
| PR-003 cached disk count | ✅ AtomicInteger | ✅ actor-isolated `rowCount` | n/a | |
| PR-004 sync FleetAlertHandler | ✅ | ✅ `NSLock`-based | n/a | |
| PR-005 bound persistedToDisk | ✅ | n/a (different impl, no unbounded set) | n/a | |
| PR-006 clear-on-shutdown | ✅ | ✅ disk shutdown + nil refs | n/a | |
| PR-007 default export mode HYBRID | ✅ | ✅ `.hybrid` (was `.conditional`) | inherits native | iOS shipped this sweep |
| PR-011 CI | ✅ android job | ✅ ios job (swift build + test) | ✅ rn job (jest + tsc) | |
| PR-012 OfflineReconnectionTest | ✅ 4 tests | ✅ 4 tests (`OfflineReconnectionTests.swift`) | n/a (covered by native) | iOS shipped this sweep |
| PR-014 Policy precedence test | ✅ 8 tests | ✅ already had `multiple policies: first enabled match wins` + `skips disabled` in 22-test suite | n/a | pre-existing |
| PR-015 RetryableExporter E2E | ✅ 5 tests + race fix | ✅ 7 tests (`RetryableExporterTests.swift`) covering retry + backoff + shutdown | n/a | pre-existing |
| PR-016 PII scrubber E2E | ✅ 13 tests | ✅ 40 tests (`PiiScrubberTests.swift`) | n/a | pre-existing |
| PR-017 TTL eviction stress | ✅ 5 tests | ⚠️ TTL covered by `pruneByTTL removes events older than retention` (functional, not stress) | n/a | acceptable |
| PR-018 ProGuard / symbolication | ✅ consumer-rules.pro | n/a (Swift dSYM via Xcode) | inherits native | docs cover both |
| PR-021 Demo app fragments | ✅ Post + Likes | n/a (separate cross-platform demo app epic) | n/a | future epic |
| PR-024 Compose Navigation | ✅ new module | n/a (UIKit/SwiftUI different) | n/a | platform-specific |
| `RetryableExporter` race fix | ✅ `RejectedExecutionException` guard | n/a (DispatchSemaphore + actor — no equivalent race) | n/a | |

**Verdict:** All four production-readiness phases are at parity across Android + iOS + RN.
Gaps that remain are all by design (lang/platform constraints) or deferred to
future epics (cross-platform demo app, separate effort). The `kotlin` →
`java` `telemetry.sdk.language` fix turned out to be a non-issue — Android
relies on `Resource.getDefault()` which sets `telemetry.sdk.language=java`
from the OTel Java SDK (spec-valid).

---

## Summary

The mobile observability platform (Android SDK, iOS SDK, RN bridge, Go collector, control plane) has solid architecture and 48/48 UAT matrix green. But there are gaps in host-app safety, deployment hardening, CI, and documentation that could cause failures on debut. This epic closes every gap identified in the production readiness assessment.

## Success Criteria

- SDK never degrades host app (zero `runBlocking` on main thread, graceful shutdown with flush)
- Default configuration works out-of-box (no localhost hardcoding, sane export mode default)
- CI runs on every push (at minimum: Android + Go + RN + iOS tests)
- Semantic versioning with CHANGELOG
- All thread-safety issues resolved
- Feature maturity documented (GA/beta/incubating)
- Offline→online transition tested end-to-end
- ProGuard/R8 stack traces readable in production

---

## Phase 1: Don't Crash the Host App (Week 1)

*The #1 rule for telemetry SDKs: never degrade the host app.*

| ID | Title | File(s) | Severity | Notes |
|----|-------|---------|----------|-------|
| PR-001 | Wire shutdown() → forceFlush() with timeout | OTelMobileHandle.kt:40,45 | CRITICAL | Buffered events lost on app termination |
| PR-002 | Replace runBlocking in flushWindow/forceFlush with async | MobileLogRecordProcessor.kt:504,555,606 | CRITICAL | Jank/ANR during flush; SR-002 |
| PR-003 | Cache disk event count in AtomicInteger | DiskLogBuffer.kt:218 | CRITICAL | COUNT(*) in runBlocking on main thread; SR-001 |
| PR-004 | Synchronize FleetAlertHandler collections | FleetAlertHandler.kt:16-18 | HIGH | Race on alertTimestamps + activeOverrides; SR-005 |
| PR-005 | Bound persistedToDisk set growth | MobileLogRecordProcessor.kt:138 | HIGH | Grows without bound; SR-004 |
| PR-006 | Clear DiskLogBuffer + MobileLoggerProvider on shutdown | DiskLogBuffer, MobileLoggerProvider | HIGH | Resource leak; SR-003/SR-014 |

## Phase 2: Don't Look Broken (Week 2)

*First impressions. A customer drops in the SDK and it should just work.*

| ID | Title | File(s) | Severity | Notes |
|----|-------|---------|----------|-------|
| PR-007 | Change default export mode to HYBRID | MobileConfig defaults | HIGH | CONDITIONAL gives zero periodic telemetry; customer thinks SDK is broken |
| PR-008 | Env-var CORS propagation (kill localhost) | telemetry.ts:124,127 | HIGH | Control plane won't work outside dev machine |
| PR-009 | Env-var collector config defaults | CollectorConfig.tsx:20,26,30,38 | HIGH | Hardcoded localhost in UI |
| PR-010 | Flip collector config defaults to enabled | CollectorConfig.tsx:20,26,38,78 | MEDIUM | Features appear broken until manually enabled |
| PR-011 | Stand up minimal CI pipeline | .github/workflows/ | HIGH | No automated testing since 2026-05-05 |

## Phase 3: Survive Scrutiny (Week 3)

*A technical evaluator digs in. Nothing should surprise them.*

| ID | Title | File(s) | Severity | Notes |
|----|-------|---------|----------|-------|
| PR-012 | ~~Add offline→online reconnection integration test~~ | New test file | HIGH | **DONE** — OfflineReconnectionTest.kt (4 tests) |
| PR-013 | ~~CHANGELOG.md + semantic versioning~~ | Root | HIGH | **DONE** — CHANGELOG.md with 0.2.0-alpha |
| PR-014 | ~~Define multi-policy evaluation precedence~~ | PolicyEvaluator | MEDIUM | **DONE** — PolicyEvaluatorPrecedenceTest.kt (8 tests) |
| PR-015 | ~~Close iOS test parity gap~~ | otel-ios-mobile/Tests/ | MEDIUM | **DONE** — iOS has 312 tests (closed in Session 2026-04-17) |
| PR-016 | ~~Document feature maturity matrix~~ | docs/ | MEDIUM | **DONE** — docs/FEATURE_MATURITY_MATRIX.md |
| PR-017 | ~~Add RetryableExporter end-to-end test~~ | New test file | MEDIUM | **DONE** — RetryableExporterE2ETest.kt (5 tests) |
| PR-025 | ~~Fix PiiScrubberTest for JDK 21 / Robolectric SDK 36~~ | PiiScrubberTest.kt | HIGH | **DONE** — robolectric.properties + @Config(sdk=[28]) |
| PR-026 | ~~Fix InterfaceConvergenceTest — DefaultSdkProvider crash~~ | InterfaceConvergenceTest.kt | MEDIUM | **DONE** — Same fix: Robolectric SDK 36→28 pin |
| PR-027 | ~~Fix SupersedesConflictTest — same DefaultSdkProvider root cause~~ | SupersedesConflictTest.kt | MEDIUM | **DONE** — Same fix as PR-026 |

## Phase 4: Ready for Real Customers (Week 4)

*The long tail. Important but not debut-blocking.*

| ID | Title | File(s) | Severity | Notes |
|----|-------|---------|----------|-------|
| PR-018 | ~~ProGuard/R8 symbolication~~ | SDK build config | HIGH | **DONE** — consumer-rules.pro + proguard-rules.pro for SDK + core + demo |
| PR-019 | ~~PII scrubbing end-to-end validation~~ | New test file | MEDIUM | **DONE** — PiiScrubberE2ETest.kt (13 tests) |
| PR-020 | ~~TTL eviction stress test~~ | New test file | MEDIUM | **DONE** — TtlEvictionStressTest.kt (5 tests) |
| PR-021 | ~~Complete demo app fragments~~ | ShopSocialActivity.kt, ProfileFragment.kt | MEDIUM | **DONE** — PostFragment, LikesFragment, HTTP 500 trigger via httpbin.org |
| PR-022 | ~~DSL v1 deprecation timeline~~ | docs/governance/ | LOW | **DONE** — docs/governance/DSL_V1_DEPRECATION.md |
| PR-023 | ~~Screenshot/wireframe GA promotion~~ | docs/ + tests | LOW | **DONE** — docs/governance/INCUBATING_MODULES.md |
| PR-024 | ~~Compose Navigation instrumentation~~ | New instrumentation module | LOW | **DONE** — ComposeNavigationInstrumentation + ComposeNavigationConfig (10 tests) |

---

## Dependencies

- PR-002 and PR-003 should be implemented together (both address runBlocking on main thread)
- PR-005 and PR-006 should be implemented together (singleton lifecycle cleanup)
- PR-008 and PR-009 are both control-plane fixes, can be done together
- PR-011 (CI) should be done early so subsequent work gets automated validation
- PR-018 (ProGuard) may require Gradle plugin changes that affect PR-011 (CI)

## Out of Scope

- Collector submission to opentelemetry-collector-contrib (separate epic)
- KMP migration (evaluated, not pursuing — see docs/solutions-architect/KMP_COMPATIBILITY.md)
- Gateway auth middleware rewrite (resolved in Phase 11)
- K8s manifests for demo backend (nice-to-have, not blocking)

## Risk Register

| Risk | Mitigation |
|------|------------|
| Async flush pipeline (PR-002) is complex refactor | Design doc exists (SR-002); incremental — replace one runBlocking at a time |
| CI redesign may take longer than expected | Start with minimal: just `run-tests.sh --all` on push, iterate |
| Default mode change (PR-007) could break existing users | Ship as opt-in first with deprecation warning, then flip default |
| ProGuard/R8 (PR-018) may need upstream OTel changes | Investigate first; may need mapping file upload pipeline |
