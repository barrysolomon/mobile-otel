# Epic: Scale Readiness — Production Hardening for Fleet Deployment

**Status:** In progress — 5/25 complete (SR-008, SR-009, SR-018, SR-021, SR-025)
**Priority:** P0
**Owner:** TBD
**Created:** 2026-04-07
**Target:** Before first external fleet deployment

---

## Summary

Comprehensive review of the mobile-otel Android SDK identified 25 issues that would degrade or break under production scale (thousands of devices, long-running sessions, unreliable networks). This epic tracks every finding from CRITICAL to LOW, with design docs for complex items and a sequenced implementation plan.

## Success Criteria

- Zero `runBlocking` calls on metric callback or main threads
- All policy numeric conditions evaluate correctly
- No silent data loss in flush/delete paths
- Retry backoff includes jitter (no thundering herd)
- Room migrations defined explicitly (no destructive fallback)
- No demo-app artifacts in SDK library code
- All thread-safety issues resolved
- Memory growth bounded under 24h+ sessions

---

## Work Items by Severity

### CRITICAL (Must fix before any fleet deployment)

| ID | Title | File(s) | Design Doc |
|----|-------|---------|------------|
| SR-001 | runBlocking on OTel metric gauge callback causes ANR | MobileLogRecordProcessor:157, DiskLogBuffer:218 | [SR-001](../design/sr-001-cached-disk-count.md) |
| SR-002 | flushWindow() runBlocking deadlocks executor pool | MobileLogRecordProcessor:504,555,606 | [SR-002](../design/sr-002-async-flush-pipeline.md) |
| SR-003 | DiskLogBuffer singleton ignores config on re-init | DiskLogBuffer:318, MobileOtel:401 | [SR-003](../design/sr-003-singleton-lifecycle.md) |
| SR-004 | persistedToDisk set grows without bound | MobileLogRecordProcessor:138 | [SR-004](../design/sr-004-persisted-set-cleanup.md) |
| SR-005 | FleetAlertHandler collections not thread-safe | FleetAlertHandler:16-18 | [SR-005](../design/sr-005-fleet-alert-thread-safety.md) |

### HIGH (Fix before beta / limited deployment)

| ID | Title | File(s) | Design Doc |
|----|-------|---------|------------|
| SR-006 | fallbackToDestructiveMigration silently drops data | DiskLogBuffer:59 | [SR-006](../design/sr-006-room-migrations.md) |
| SR-007 | VACUUM on hot insert path blocks all DB writes | DiskLogBuffer:299 | [SR-007](../design/sr-007-deferred-vacuum.md) |
| SR-008 | ✅ PolicyEvaluator accepts injected OkHttpClient (2026-05-20). Wiring it through `OTelMobileBuilder` is the follow-on. | PolicyEvaluator:88 | Inline fix |
| SR-009 | ✅ Full-jitter backoff on Android + iOS (2026-05-20). | RetryableExporter:172 (Android), RetryableExporter.swift:69 (iOS) | Inline fix |
| SR-010 | LogTailBuffer holds read lock during user predicate | LogTailBuffer:100 | [SR-010](../design/sr-010-lock-free-trigger-eval.md) |
| SR-011 | ContextSnapshot reads demo_app_prefs in SDK code | ContextSnapshot:119 | Inline fix |
| SR-012 | Go processor recompiles regex on every call | processor.go:204 | Inline fix |

### MEDIUM (Fix before GA)

| ID | Title | File(s) | Design Doc |
|----|-------|---------|------------|
| SR-013 | DynamicSampler non-atomic read→write lock upgrade | DynamicSampler:165 | Inline fix |
| SR-014 | MobileLoggerProvider singleton never reset on shutdown | MobileLoggerProvider:282 | Covered by SR-003 |
| SR-015 | enforceSizeLimit reads filesystem size, over-deletes | DiskLogBuffer:283 | Covered by SR-007 |
| SR-016 | markCleanShutdown on background misses OOM kills | AppLifecycleDetector:336 | [SR-016](../design/sr-016-crash-recovery-accuracy.md) |
| SR-017 | ErrorInstrumentation forceFlush on crash thread | ErrorInstrumentation:78 | [SR-017](../design/sr-017-crash-safe-flush.md) |
| SR-018 | ✅ getAttributeValue tries all 4 AttributeKey types (shipped 2026-04-14, see [Session 2026-04-14](../../../../.claude/projects/-Users-barrysolomon-Projects-Dash0-mobile-observability/memory/project_session_2026_04_14.md)). | PolicyEvaluator:230-235 | Inline fix |
| SR-019 | flushWindow deletes by timestamp not row IDs — TOCTOU data loss | DiskLogBuffer:174, MobileLogRecordProcessor:553 | [SR-019](../design/sr-019-id-based-delete.md) |

### LOW (Fix opportunistically)

| ID | Title | File(s) |
|----|-------|---------|
| SR-020 | regexCache synchronized LinkedHashMap serializes evals | PolicyEvaluator:83 |
| SR-021 | ✅ isLocalhostEndpoint accepts IPv6 `[::1]` and full form (2026-05-20). | MobileConfig:166-185 |
| SR-022 | JankDetector may construct Choreographer on wrong thread | JankDetector:35 |
| SR-023 | DynamicSampler negative-Long bias — 50% always sampled | DynamicSampler:209 |
| SR-024 | ContextSnapshot demographics may constitute PII | ContextSnapshot:72 |
| SR-025 | ✅ Comma-ok type assertion + body fall-back (2026-05-20). | processor.go:106 |

---

## Dependencies

- SR-003 and SR-014 should be implemented together (singleton lifecycle)
- SR-007 and SR-015 should be implemented together (disk buffer size management)
- SR-001 and SR-002 share the same root cause (runBlocking in hot paths)
- SR-006 must be done before any schema v4 changes

## Risks

- **Room migration testing** (SR-006): Requires testing upgrade from v2→v3→v4 on real devices with existing data
- **Async flush pipeline** (SR-002): Refactoring synchronous flush to fully async changes error semantics throughout the export path
- **Crash-safe flush** (SR-017): Reducing crash-time export to disk-only requires validating the crash-recovery path handles all edge cases

## Estimated Effort

| Severity | Items | Estimated Days |
|----------|-------|----------------|
| Critical | 5 | 8-10 |
| High | 7 | 6-8 |
| Medium | 7 | 5-7 |
| Low | 6 | 3-4 |
| **Total** | **25** | **22-29** |
