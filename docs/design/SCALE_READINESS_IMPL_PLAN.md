# Scale Readiness Implementation Plan

**Epic:** [SCALE_READINESS_EPIC.md](../epics/SCALE_READINESS_EPIC.md)
**Total items:** 25 (5 Critical, 7 High, 7 Medium, 6 Low)
**Estimated effort:** 22-29 days
**Sequencing principle:** Fix data-loss and crash vectors first, then perf, then polish.

---

## Phase 1: Stop the Bleeding (Critical — 8-10 days)

These items can cause ANRs, deadlocks, memory leaks, or data loss in production. Fix before any fleet deployment.

### Sprint 1.1: Eliminate runBlocking (3 days)

| Order | Item | Dependency | Effort | Design Doc |
|-------|------|------------|--------|------------|
| 1 | **SR-001: Cache disk event count** | None | 0.5d | [sr-001](sr-001-cached-disk-count.md) |
| 2 | **SR-002: Async flush pipeline** | SR-001 (cached count used in flush) | 2d | [sr-002](sr-002-async-flush-pipeline.md) |
| 3 | Run unit tests, verify no regressions | SR-001, SR-002 | 0.5d | — |

**Deliverable:** Zero `runBlocking` calls in the buffer/export pipeline. Metric gauge callback returns cached value in O(1).

### Sprint 1.2: Singleton & Memory Safety (3 days)

| Order | Item | Dependency | Effort | Design Doc |
|-------|------|------------|--------|------------|
| 4 | **SR-003: Singleton lifecycle** (+SR-014) | None | 1d | [sr-003](sr-003-singleton-lifecycle.md) |
| 5 | **SR-004: Bound persistedToDisk set** | None | 1d | [sr-004](sr-004-persisted-set-cleanup.md) |
| 6 | **SR-005: FleetAlertHandler thread safety** | None | 0.5d | [sr-005](sr-005-fleet-alert-thread-safety.md) |
| 7 | Run full test suite | SR-003..SR-005 | 0.5d | — |

**Deliverable:** shutdown()+restart cycle works correctly. Memory bounded under 24h sessions. Fleet alerts properly rate-limited.

### Sprint 1.3: Validate Critical Fixes (2 days)

| Order | Item | Dependency | Effort |
|-------|------|------------|--------|
| 8 | Manual emulator testing: 1h session, verify ANR-free | SR-001, SR-002 | 0.5d |
| 9 | Manual testing: shutdown/restart with different configs | SR-003 | 0.5d |
| 10 | Run Dash0 E2E validation (validate-demo-telemetry.sh) | All Phase 1 | 0.5d |
| 11 | Code review + PR for Phase 1 | All Phase 1 | 0.5d |

---

## Phase 2: Data Integrity & Resilience (High — 6-8 days)

Fix data loss vectors, thundering herd, and correctness bugs.

### Sprint 2.1: Database Hardening (3 days)

| Order | Item | Dependency | Effort | Design Doc |
|-------|------|------------|--------|------------|
| 12 | **SR-006: Explicit Room migrations** | None | 1.5d | [sr-006](sr-006-room-migrations.md) |
| 13 | **SR-007: Deferred VACUUM** (+SR-015) | SR-001 (cached count) | 1d | [sr-007](sr-007-deferred-vacuum.md) |
| 14 | Run migration test (v2 DB → v3) on emulator | SR-006 | 0.5d | — |

**Deliverable:** Phased rollouts can't wipe user data. VACUUM doesn't block the hot path.

### Sprint 2.2: Quick Wins (2 days)

All inline fixes — no design doc needed, just code changes:

| Order | Item | Effort |
|-------|------|--------|
| 15 | **SR-008: Shared OkHttpClient** — inject into PolicyEvaluator | 0.5d |
| 16 | **SR-009: Retry jitter** — add `* (0.5 + random * 0.5)` to backoff | 0.25d |
| 17 | **SR-011: Remove demo_app_prefs** from ContextSnapshot | 0.25d |
| 18 | **SR-012: Pre-compile Go regexes** — cache at policy load time | 0.5d |
| 19 | Run Go + Android tests | 0.5d |

**Deliverable:** No thundering herd. No demo artifacts in SDK. Go processor 10x faster on regex policies.

### Sprint 2.3: Lock Safety (1 day)

| Order | Item | Dependency | Effort | Design Doc |
|-------|------|------------|--------|------------|
| 20 | **SR-010: Lock-free trigger eval** | None | 0.5d | [sr-010](sr-010-lock-free-trigger-eval.md) |
| 21 | Code review + PR for Phase 2 | All Phase 2 | 0.5d | — |

---

## Phase 3: Correctness & Safety (Medium — 5-7 days)

Fix subtle correctness issues that affect data accuracy at scale.

### Sprint 3.1: Policy Engine Fixes (2 days)

| Order | Item | Dependency | Effort | Design Doc |
|-------|------|------------|--------|------------|
| 22 | **SR-018: Multi-type attribute lookup** | None | 0.5d | Inline fix |
| 23 | **SR-019: ID-based delete in flushWindow** | None | 1d | [sr-019](sr-019-id-based-delete.md) |
| 24 | Add tests for numeric policy conditions | SR-018 | 0.5d | — |

**Deliverable:** Numeric policy conditions (`duration_ms > 2000`) actually work. No TOCTOU data loss in flush.

### Sprint 3.2: Lifecycle & Crash Path (2 days)

| Order | Item | Dependency | Effort | Design Doc |
|-------|------|------------|--------|------------|
| 25 | **SR-016: Crash recovery accuracy** | None | 1d | [sr-016](sr-016-crash-recovery-accuracy.md) |
| 26 | **SR-017: Crash-safe flush** | SR-002 (async flush) | 0.5d | [sr-017](sr-017-crash-safe-flush.md) |
| 27 | **SR-013: Atomic sampler revert** | None | 0.5d | Inline fix |

### Sprint 3.3: Validate & Ship (1 day)

| Order | Item | Dependency | Effort |
|-------|------|------------|--------|
| 28 | Run full test suite (Android + Go) | All Phase 3 | 0.5d |
| 29 | Code review + PR for Phase 3 | All Phase 3 | 0.5d |

---

## Phase 4: Polish (Low — 3-4 days, opportunistic)

Can be done by any team member as time permits. No blocking dependencies.

| Order | Item | Effort |
|-------|------|--------|
| 30 | **SR-020:** ConcurrentHashMap for regexCache | 0.5d |
| 31 | **SR-021:** IPv6 loopback in isLocalhostEndpoint | 0.25d |
| 32 | **SR-022:** JankDetector Choreographer on main thread | 0.5d |
| 33 | **SR-023:** Fix DynamicSampler negative-Long bias | 0.5d |
| 34 | **SR-024:** GDPR/CCPA docs for demographics | 0.5d |
| 35 | **SR-025:** Go processor safe type assertion | 0.25d |

---

## Sequencing Diagram

```
Phase 1 (Critical)          Phase 2 (High)              Phase 3 (Medium)        Phase 4 (Low)
━━━━━━━━━━━━━━━━━━━         ━━━━━━━━━━━━━━━━━━━          ━━━━━━━━━━━━━━━━━━━     ━━━━━━━━━━━━━
SR-001 ──┐                  SR-006 ──┐                   SR-018 ──┐              SR-020..SR-025
SR-002 ──┤ Sprint 1.1       SR-007 ──┤ Sprint 2.1        SR-019 ──┤ Sprint 3.1   (any order)
         │                           │                            │
SR-003 ──┤                  SR-008 ──┤                   SR-016 ──┤
SR-004 ──┤ Sprint 1.2       SR-009 ──┤ Sprint 2.2        SR-017 ──┤ Sprint 3.2
SR-005 ──┘                  SR-011 ──┤                   SR-013 ──┘
                            SR-012 ──┘
                            SR-010 ── Sprint 2.3
```

## Test Strategy

Each phase ends with:
1. All existing unit tests pass (`./gradlew :otel-android-mobile:test`)
2. Go processor tests pass (`go test -v -race ./...`)
3. Demo scenario tests on emulator (Dash0 data verified)
4. Dash0 E2E validation script passes

## Exit Criteria

Phase 1 complete = safe for limited fleet deployment (< 100 devices)
Phase 2 complete = safe for beta deployment (< 1000 devices)
Phase 3 complete = safe for GA deployment
Phase 4 complete = production polish
