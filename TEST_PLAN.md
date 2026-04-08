# Mobile OTel SDK — Comprehensive Test Plan

> **Goal**: 100% coverage of all code paths, state permutations, and cross-cutting
> concerns. Zero tolerance for SDK-induced performance degradation, crashes, or
> data loss in the host application.

---

## Table of Contents

1. [Test Strategy Overview](#1-test-strategy-overview)
2. [Test Categories & Layers](#2-test-categories--layers)
3. [Module-Level Unit Tests](#3-module-level-unit-tests)
4. [Cross-Cutting Concern Tests](#4-cross-cutting-concern-tests)
5. [Integration Test Suites](#5-integration-test-suites)
6. [Dash0 E2E Telemetry Validation](#6-dash0-end-to-end-telemetry-validation)
7. [Performance and Safety Tests](#7-performance--safety-tests)
8. [Permutation Matrices](#8-permutation-matrices)
9. [Collector Processor Tests (Go)](#9-collector-processor-tests-go)
10. [Test Infrastructure Requirements](#10-test-infrastructure-requirements)

---

## 1. Test Strategy Overview

### Principles

| Principle | Rationale |
|-----------|-----------|
| **Host-app safety first** | Every test category includes a "does NOT affect the host" assertion |
| **Fail-silent guarantee** | SDK must never throw into host code — every public entry point wrapped in try-catch with internal logging |
| **Bounded resource usage** | Memory, CPU, disk, battery drain must stay within declared budgets under all conditions |
| **Deterministic under chaos** | Offline, low-memory, thermal throttle, clock skew — all must produce correct (or gracefully degraded) behavior |
| **No data loss on crash** | Dual-tier buffer must survive process death and recover 100% of persisted events |

### Coverage Targets

| Layer | Target | Tool |
|-------|--------|------|
| Unit (Robolectric) | 100% line + branch | JaCoCo |
| Integration (Emulator) | All critical user journeys | AndroidJUnit4 + Espresso |
| Performance | No regression > 5% on benchmarks | AndroidX Benchmark |
| Stress | No OOM/ANR under 10x normal load | Custom stress harness |

---

## 2. Test Categories & Layers

```
┌─────────────────────────────────────────────────────────────────┐
│ L5: End-to-End Smoke Tests (real device / emulator)             │
│     Full SDK init → generate events → verify OTLP export        │
├─────────────────────────────────────────────────────────────────┤
│ L4: Performance & Safety Regression Tests                       │
│     Benchmarks, memory budgets, ANR detection, battery drain    │
├─────────────────────────────────────────────────────────────────┤
│ L3: Cross-Cutting Integration Tests                             │
│     Offline↔Online × ExportMode × TelemetryType matrices       │
├─────────────────────────────────────────────────────────────────┤
│ L2: Component Integration Tests                                 │
│     Buffer + PolicyEvaluator + Exporter pipeline                │
├─────────────────────────────────────────────────────────────────┤
│ L1: Unit Tests (per-class, per-method)                          │
│     Every public/internal method, every branch                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. Module-Level Unit Tests

### 3.1 Dual-Tier Buffer System

#### 3.1.1 MobileLogRecordProcessor

| # | Test Case | Precondition | Action | Expected |
|---|-----------|-------------|--------|----------|
| B-001 | Add event to empty RAM buffer | RAM empty | `onEmit(logRecord)` | Event in RAM, not on disk |
| B-002 | RAM buffer at capacity | RAM at 5000 events | `onEmit(logRecord)` | Oldest event moved to disk, new event in RAM |
| B-003 | RAM buffer overflow batch | RAM at 4990, add 20 events | Rapid `onEmit()` × 20 | 10 overflow to disk, RAM stays at 5000 |
| B-004 | Disk buffer at size limit | Disk at 50MB | Overflow from RAM | Oldest disk events evicted, new events stored |
| B-005 | TTL expiration | Events older than 24h on disk | Hourly cleanup fires | Expired events deleted, VACUUM runs |
| B-006 | Crash-safe mirror (RAM → disk) | 100 events in RAM | 2s mirror timer fires | All 100 events on disk (identity-deduplicated) |
| B-007 | Identity dedup on mirror | Same events mirrored twice | Two mirror cycles | No duplicate entries on disk |
| B-008 | flushWindow(2) | Events spanning last 5 minutes | `flushWindow(2)` | Only last 2 min exported, others retained |
| B-009 | flushWindow(0) edge | No events | `flushWindow(0)` | No-op, no crash |
| B-010 | flushWindow(1440) max | 24h of events | `flushWindow(1440)` | All events exported |
| B-011 | forceFlush (CONTINUOUS) | Events in both tiers | `forceFlush()` | All RAM + disk events exported |
| B-012 | Concurrent onEmit from 10 threads | Empty buffer | 10 threads × 100 events | All 1000 events captured, no lost events |
| B-013 | Concurrent flush + onEmit | Active writing | Flush during writes | No ConcurrentModificationException |
| B-014 | Process death recovery | Events on disk, process killed | New process starts | Disk events available for export |
| B-015 | Empty buffer export | No events anywhere | forceFlush() | Export called with empty collection, SUCCESS |
| B-016 | Custom RAM buffer size | Config: ramBufferSize=100 | Fill to 101 | Overflow at 100, not default 5000 |
| B-017 | Custom disk buffer size | Config: diskBufferMb=10 | Fill to 10MB | Eviction at 10MB |
| B-018 | Disk buffer disabled | Config: diskBufferMb=0 | RAM overflow | Events dropped (not persisted), warning logged |
| B-019 | Room DB corruption | Corrupt SQLite file | Any disk operation | Graceful degradation to RAM-only, error logged |
| B-020 | Clock skew backward (1h) | Events in RAM with monotonic timestamps | System.currentTimeMillis() jumps back 1h | `flushWindow(2)` still exports correct 2-min window via `SystemClock.elapsedRealtime()`. Wall-clock irrelevant for RAM. See [MONOTONIC_FLUSH_WINDOW.md](docs/design/MONOTONIC_FLUSH_WINDOW.md) |
| B-021 | Clock skew forward (1h) | Events in RAM with monotonic timestamps | System.currentTimeMillis() jumps forward 1h | `flushWindow(2)` still exports correct 2-min window via monotonic. **This was the critical data-loss bug before the fix.** |
| B-022 | No clock skew (regression) | Normal operation | No clock manipulation | Identical behavior to pre-fix: correct 2-min window, same events exported |
| B-023 | Cross-boot disk recovery | Disk events from previous boot (different bootId) | App restart after crash | Disk events use wall-clock fallback (`timestampMs >= wallWindowStart`). All crash-recovery events exported |
| B-024 | Pre-migration disk events | Disk events with monotonicMs=0, bootId=null | App upgraded from old schema | Wall-clock fallback used. Events exported correctly |
| B-025 | Delete consistency under skew | Export + delete under clock skew | Clock jumps between SELECT and DELETE | `deleteByIds()` deletes exactly the IDs returned by SELECT. No extras, no misses |
| B-026 | Cooldown under clock skew | Two flushes 5s apart, clock jumps between them | Clock jumps 1h forward between flushes | Cooldown uses monotonic, still suppresses duplicate flush correctly |
| B-027 | flushByTraceId fallback | Trace-based flush falls back to flushWindow | Sparse trace events trigger fallback | Monotonic protection applies through the fallback path |
| B-028 | Negative monoWindowStart | flushWindow(1440) on 3-min-old boot | monoWindowStart goes negative | All same-boot events included (correct: "flush everything from this boot") |

#### 3.1.2 DiskLogBuffer

| # | Test Case | Expected |
|---|-----------|----------|
| D-001 | Insert single event | Persisted, retrievable by ID |
| D-002 | Insert 10,000 events | All persisted, correct count |
| D-003 | getEventsAfter(timestamp) | Returns only events after timestamp |
| D-004 | getEventsAfter with empty DB | Returns empty list |
| D-005 | deleteOlderThan(timestamp) | Only older events removed |
| D-006 | deleteOldest(count) | Exactly count oldest removed |
| D-007 | Size enforcement at limit | Oldest events evicted to stay under limit |
| D-008 | VACUUM after bulk delete | DB file size reduced |
| D-009 | Concurrent insert + query | No deadlocks, correct results |
| D-010 | Query by traceId | Returns only events with matching traceId |
| D-011 | getAllEvents ordering | Returned in timestamp order |
| D-012 | Insert with null attributes | Handles gracefully, nullable fields stored as null |

#### 3.1.3 RetryableExporter

| # | Test Case | Expected |
|---|-----------|----------|
| R-001 | Export succeeds first try | SUCCESS, 0 retries |
| R-002 | Export fails once, succeeds on retry | SUCCESS after 1 retry, ~1s delay |
| R-003 | Export fails all retries (3) | FAILED, delays: ~1s, ~2s, ~4s (exponential) |
| R-004 | Non-retryable exception (IllegalArgument) | Immediate FAILED, 0 retries |
| R-005 | Auth error (heuristic: message contains "unauthenticated", "unauthorized", "token", "permission denied", or "status code 16") | ExportStatus.AuthError returned, 0 retries. Note: OTel SDK does not surface distinct auth exception types — classification is string-based via `classifyFailure()` |
| R-005a | Auth keyword "unauthenticated" | classifyFailure returns AuthError |
| R-005b | Auth keyword "unauthorized" | classifyFailure returns AuthError |
| R-005c | Auth keyword "token" (e.g., "invalid token") | classifyFailure returns AuthError |
| R-005d | Auth keyword "permission denied" | classifyFailure returns AuthError |
| R-005e | gRPC "status code 16" (UNAUTHENTICATED) | classifyFailure returns AuthError |
| R-005f | Non-auth failure message (e.g., "connection reset") | classifyFailure returns Failed, NOT AuthError |
| R-006 | Max delay cap (60s) | Delay never exceeds 60s |
| R-007 | Concurrent export calls | Each gets independent retry state |
| R-008 | Export with empty collection | SUCCESS immediately, no network call |
| R-009 | Network timeout during export | Retries with backoff |
| R-010 | Partial upload (some events succeed) | Remaining events retried |

---

### 3.2 Policy Evaluation Engine

#### 3.2.1 Condition Matching — All Operators × Data Types

| # | Operator | Input | Condition | Expected |
|---|----------|-------|-----------|----------|
| P-001 | equals | "ui.freeze" | equals "ui.freeze" | MATCH |
| P-002 | equals | "ui.freeze" | equals "ui.tap" | NO MATCH |
| P-003 | equals | null attribute | equals "anything" | NO MATCH |
| P-004 | equals | "" (empty) | equals "" | MATCH |
| P-005 | contains | "network_error_timeout" | contains "error" | MATCH |
| P-006 | contains | "success" | contains "error" | NO MATCH |
| P-007 | contains | null | contains "x" | NO MATCH |
| P-008 | contains | "" | contains "" | MATCH |
| P-009 | gt | "500" | gt "499" | MATCH |
| P-010 | gt | "500" | gt "500" | NO MATCH |
| P-011 | gt | "abc" (non-numeric) | gt "100" | NO MATCH (graceful) |
| P-012 | gt | null | gt "0" | NO MATCH |
| P-013 | lt | "100" | lt "200" | MATCH |
| P-014 | lt | "200" | lt "200" | NO MATCH |
| P-015 | gte | "500" | gte "500" | MATCH |
| P-016 | lte | "500" | lte "500" | MATCH |
| P-017 | regex | "error_code_404" | regex "error_code_\d+" | MATCH |
| P-018 | regex | "success" | regex "error_code_\d+" | NO MATCH |
| P-019 | regex | "test" | regex "[invalid" | NO MATCH (invalid pattern) |
| P-020 | regex | "aaa...aaa" (1000 chars) | regex "(a+)+" (ReDoS) | NO MATCH (pattern > 200 chars rejected) |
| P-021 | regex | value | regex pattern (cached) | Second eval uses cache |
| P-022 | regex | value | 65th unique pattern | LRU evicts oldest cached pattern |

#### 3.2.2 Geo Matching

| # | Test Case | Context | Condition | Expected |
|---|-----------|---------|-----------|----------|
| G-001 | Country match | country="US" | countries=["US","CA"] | MATCH |
| G-002 | Country no match | country="DE" | countries=["US","CA"] | NO MATCH |
| G-003 | Country null | country=null | countries=["US"] | NO MATCH |
| G-004 | Region match | region="CA" | regions=["CA","NY"] | MATCH |
| G-005 | Timezone glob match | tz="America/Los_Angeles" | timezone="America/*" | MATCH |
| G-006 | Timezone exact match | tz="UTC" | timezone="UTC" | MATCH |
| G-007 | Timezone glob no match | tz="Europe/London" | timezone="America/*" | NO MATCH |
| G-008 | Locale match | locale="en-US" | locales=["en-US","es-ES"] | MATCH |
| G-009 | All geo conditions AND | country+region+tz+locale | All match | MATCH |
| G-010 | Mixed geo (some match, some don't) with AND | country match, region no match | AND operator | NO MATCH |
| G-011 | Empty geo conditions | any context | no geo conditions | MATCH (vacuously true) |
| G-012 | Privacy: location capture disabled | captureLocation=false | country=["US"] | NO MATCH (context empty) |

#### 3.2.3 Device Matching

| # | Test Case | Context | Condition | Expected |
|---|-----------|---------|-----------|----------|
| DV-001 | Network WiFi match | network="wifi" | networks=["wifi"] | MATCH |
| DV-002 | Network cellular match | network="cellular" | networks=["cellular"] | MATCH |
| DV-003 | Network offline | network="offline" | networks=["wifi"] | NO MATCH |
| DV-004 | Battery charging | battery="charging" | battery=["charging"] | MATCH |
| DV-005 | Battery low | battery="low" | battery=["low","normal"] | MATCH |
| DV-006 | OS version in range | SDK_INT=33 | osVersionMin=28, osVersionMax=36 | MATCH |
| DV-007 | OS version below min | SDK_INT=26 | osVersionMin=28 | NO MATCH |
| DV-008 | OS version above max | SDK_INT=37 | osVersionMax=36 | NO MATCH |
| DV-009 | App version match | appVersion="2.1.0" | appVersions=["2.1.0","2.2.0"] | MATCH |
| DV-010 | Device class match | deviceClass="phone" | deviceClasses=["phone","tablet"] | MATCH |
| DV-011 | Build channel match | channel="beta" | channels=["beta","internal"] | MATCH |
| DV-012 | All device conditions AND | All match | AND operator | MATCH |
| DV-013 | Mixed device conditions | network match, battery no match | AND operator | NO MATCH |

#### 3.2.4 Logical Operators & Compound Policies

| # | Test Case | Expected |
|---|-----------|----------|
| L-001 | AND: all conditions match | MATCH |
| L-002 | AND: one condition fails | NO MATCH |
| L-003 | OR: one condition matches | MATCH |
| L-004 | OR: no conditions match | NO MATCH |
| L-005 | Nested: AND(OR(a,b), OR(c,d)) | Correct evaluation |
| L-006 | Nested: OR(AND(a,b), AND(c,d)) | Correct evaluation |
| L-007 | 3-level nesting | Correct evaluation |
| L-008 | Empty conditions list | NO MATCH (zero-constraint rejected) |
| L-009 | Single condition AND | Equivalent to standalone |
| L-010 | Single condition OR | Equivalent to standalone |
| L-011 | Attribute AND + Geo OR + Device AND | Cross-dimension combination correct |

#### 3.2.5 Policy Actions

| # | Test Case | Expected |
|---|-----------|----------|
| A-001 | flush_buffer action | flushWindow called with correct minutes |
| A-002 | set_sampling action | DynamicSampler rate adjusted |
| A-003 | annotate action | Event tagged with trigger_id + reason |
| A-004 | Multiple actions per match | All actions executed in order |
| A-005 | flush_buffer minutes=0 | Rejected (min=1) |
| A-006 | flush_buffer minutes=1441 | Capped at 1440 |
| A-007 | Action on disabled policy | Not executed |
| A-008 | Action when no match | Not executed |

#### 3.2.6 Config Polling & Remote Policies

| # | Test Case | Expected |
|---|-----------|----------|
| CP-001 | Initial fetch succeeds | Remote policies active |
| CP-002 | Initial fetch fails (network) | Default policies used (ui.freeze, crash, http.error) |
| CP-003 | Periodic refresh updates | New policies active after refresh |
| CP-004 | Periodic refresh fails | Previous policies retained |
| CP-005 | Malformed JSON response | Previous policies retained, error logged |
| CP-006 | Empty policy list response | Default policies used |
| CP-007 | > 100 policies in response | Truncated to 100 |
| CP-008 | Policy with > 50 conditions | Conditions truncated to 50 |
| CP-009 | Config endpoint returns 404 | Default policies used |
| CP-010 | Config endpoint returns 500 | Default policies used, retry on next interval |
| CP-011 | DSL v2 vs v1 negotiation | Correct version requested based on SDK capability |

#### 3.2.7 Built-in Default Policies

| # | Test Case | Expected |
|---|-----------|----------|
| DF-001 | ui.freeze event, no remote config | Matches default policy, flush 2 min |
| DF-002 | app.crash event, no remote config | Matches default policy, flush 5 min |
| DF-003 | http.error event, no remote config | Matches default policy, flush 5 min |
| DF-004 | ui.tap event, no remote config | No match (not a default trigger) |
| DF-005 | Remote config overrides defaults | Default policies replaced, not merged |

---

### 3.3 Export Modes — Complete Permutation Matrix

#### CONDITIONAL Mode

| # | Test Case | Expected |
|---|-----------|----------|
| EC-001 | Events buffered, no policy match | No export occurs |
| EC-002 | Policy match triggers flush | Only matching window exported |
| EC-003 | Multiple policy matches in sequence | Each triggers independent flush |
| EC-004 | Policy match during ongoing flush | Second flush queued after first completes |
| EC-005 | No periodic timer fires | Confirmed: zero periodic exports |
| EC-006 | Buffer fills completely without match | Overflow to disk, no export |
| EC-007 | App backgrounded, no policy match | No export (events stay buffered) |
| EC-008 | App killed, events on disk | Recovered on next launch, awaiting policy match |

#### CONTINUOUS Mode

| # | Test Case | Expected |
|---|-----------|----------|
| EN-001 | Periodic timer fires | All buffered events exported |
| EN-002 | Timer interval respected | Export every N seconds (configurable) |
| EN-003 | Export fails, next timer fires | Previous events + new events exported |
| EN-004 | Empty buffer at timer | No-op export |
| EN-005 | Policy match during CONTINUOUS | Also triggers immediate flush |
| EN-006 | High event rate | Timer-based export drains buffer periodically |
| EN-007 | App backgrounded | Timer continues / suspends per config |

#### HYBRID Mode

| # | Test Case | Expected |
|---|-----------|----------|
| EH-001 | Heartbeat emitted | device.heartbeat logged at configured interval |
| EH-002 | Lightweight periodic export | Metrics/heartbeats exported on schedule |
| EH-003 | Policy match triggers bulk flush | Full buffer window exported |
| EH-004 | Prediction cycle on heartbeat | Runs on heartbeat tick (no self-scheduler) |
| EH-005 | No double-firing | Prediction cycle runs once per heartbeat, not independently |
| EH-006 | High-risk prediction | Pre-emptive flushWindow called |
| EH-007 | Low-risk prediction | No extra export |

---

### 3.4 Sampling

| # | Test Case | Expected |
|---|-----------|----------|
| S-001 | Baseline rate 0.5 | ~50% of traces sampled (statistical) |
| S-002 | Baseline rate 1.0 | All traces sampled |
| S-003 | Baseline rate 0.0 | No traces sampled |
| S-004 | Page span always sampled | page.* spans: 100% regardless of rate |
| S-005 | App startup always sampled | app.start spans: 100% |
| S-006 | Dynamic rate adjustment | Rate changes to new value |
| S-007 | Rate adjustment revert | Reverts to baseline after duration |
| S-008 | High-priority rate override | High-priority spans use elevated rate |
| S-009 | TraceID ratio-based determinism | Same traceId always gets same decision |
| S-010 | Concurrent sampling decisions | Thread-safe, no race conditions |

---

### 3.5 Session Management

| # | Test Case | Expected |
|---|-----------|----------|
| SM-001 | Session created on first launch | UUID generated, persisted to EncryptedSharedPreferences |
| SM-002 | Session restored on relaunch | Same session ID if within timeout |
| SM-003 | Session renewed after timeout | New session ID after inactivity threshold (30min default) |
| SM-004 | Session renewed on foreground after timeout | New ID generated on app.foreground |
| SM-005 | View ID rotates on screen change | New viewId per screen transition |
| SM-006 | User identity set (plaintext) | userId, email, name stored |
| SM-007 | User identity set (hashed) | email SHA-256 hashed |
| SM-008 | Global attributes added | Up to 128 attributes persisted |
| SM-009 | Global attribute overflow (129th) | Rejected, warning logged |
| SM-010 | Key too long (>256 chars) | Rejected |
| SM-011 | Value too long (>4096 chars) | Rejected |
| SM-012 | Custom user attributes | Attached to all subsequent events |
| SM-013 | Clear user identity | Identity removed from subsequent events |
| SM-014 | Concurrent session access | Thread-safe reads/writes |
| SM-015 | EncryptedSharedPreferences unavailable | Fallback to regular SharedPrefs, warning logged |

---

### 3.6 Device Metrics Collector

| # | Test Case | Expected |
|---|-----------|----------|
| DM-001 | Memory gauges emitted | memory.used, memory.free, memory.max, memory.percent_used |
| DM-002 | Battery gauges emitted | battery.level, battery.drain_rate, battery.state |
| DM-003 | Thermal gauges emitted | thermal.state, thermal.throttle_level |
| DM-004 | Network gauges emitted | network.type, network.quality |
| DM-005 | Jank gauge emitted | jank.frame_drop_count |
| DM-006 | App start metric | app.start.time recorded once |
| DM-007 | Custom capture interval | Metrics collected at configured rate |
| DM-008 | Metrics disabled | No collection occurs |
| DM-009 | Individual metric toggle off | Only enabled metrics collected |
| DM-010 | Battery API unavailable | Graceful: gauge reports "unknown" |
| DM-011 | Thermal API < Android 10 | Graceful: gauge reports "unknown" |

---

### 3.7 Predictive Intelligence

| # | Test Case | Expected |
|---|-----------|----------|
| PR-001 | Prediction cycle produces risk scores | crash, network_loss, perf_degradation, battery_drain scores |
| PR-002 | Network loss risk > 0.7 | flushWindow(2) called (last 2 min) |
| PR-003 | Crash risk > 0.7 | flushWindow(5) called (last 5 min) |
| PR-004 | All risks < 0.7 | No pre-emptive flush |
| PR-005 | Risk = exactly 0.7 | Threshold met, flush triggered |
| PR-006 | Prediction event emitted (debug) | prediction.cycle log record at DEBUG |
| PR-007 | High-risk alert emitted (warn) | high.risk.alert log record at WARN |
| PR-008 | Prediction disabled | No cycles run, no events |
| PR-009 | Prediction with stale health data | Uses latest available, doesn't crash |
| PR-010 | HYBRID mode: prediction on heartbeat | Runs on heartbeat tick only |
| PR-011 | CONDITIONAL mode: self-scheduled | Runs on own 30s timer |

---

### 3.8 Instrumentation Modules — Per-Module Tests

#### 3.8.1 Tap Instrumentation

| # | Test Case | Expected |
|---|-----------|----------|
| TAP-001 | Single tap emits ui.tap | Log record with correct attributes |
| TAP-002 | Long press emits ui.long_press | Duration tracked |
| TAP-003 | Swipe emits ui.swipe | Direction attribute (up/down/left/right) |
| TAP-004 | Swipe below threshold (49px) | No swipe event (treated as tap) |
| TAP-005 | Swipe at threshold (50px) | Swipe event emitted |
| TAP-006 | captureTaps=false | No tap events |
| TAP-007 | captureSwipe=false | No swipe events, taps still captured |
| TAP-008 | captureLongPress=false | No long_press events |
| TAP-009 | UiTelemetryMode.EVENTS | Log records only |
| TAP-010 | UiTelemetryMode.SPANS | Spans only (under page span) |
| TAP-011 | UiTelemetryMode.BOTH | Both log records and spans |
| TAP-012 | Hit-test identifies clicked view | ui.element.id, ui.element.class correct |
| TAP-013 | Hit-test at maxDepth boundary | Stops at maxHitTestDepth |
| TAP-014 | ACTION_CANCEL event | No event emitted |
| TAP-015 | Tap on view with no ID | ui.element.id absent or "unknown" |
| TAP-016 | Coordinate bucketing | Quantized coordinates in attributes |
| TAP-017 | Install/uninstall lifecycle | Listeners registered/removed cleanly |

#### 3.8.2 Scroll Instrumentation

| # | Test Case | Expected |
|---|-----------|----------|
| SCR-001 | Vertical scroll down | ui.scroll with direction="down" |
| SCR-002 | Vertical scroll up | ui.scroll with direction="up" |
| SCR-003 | Horizontal scroll right | ui.scroll with direction="right" |
| SCR-004 | Horizontal scroll left | ui.scroll with direction="left" |
| SCR-005 | Small scroll (<50px) | distance_bucket="small" |
| SCR-006 | Medium scroll (50-200px) | distance_bucket="medium" |
| SCR-007 | Large scroll (>200px) | distance_bucket="large" |
| SCR-008 | Throttle: 2 scrolls within 500ms | Only first event emitted |
| SCR-009 | Throttle: scroll after 500ms | Second event emitted |
| SCR-010 | Custom throttle interval | Respected |
| SCR-011 | Multiple RecyclerViews on screen | Each tracked independently |
| SCR-012 | RecyclerView removed from layout | Listener unregistered, no leak |
| SCR-013 | Activity with no RecyclerView | No crash, no listeners |

#### 3.8.3 Text Input Instrumentation

| # | Test Case | Expected |
|---|-----------|----------|
| TI-001 | EditText focus loss | ui.text_input emitted |
| TI-002 | captureCharCount=true | char_count attribute present |
| TI-003 | captureIsSet=true | is_set attribute present |
| TI-004 | captureTextContent=true, view in allowlist | Full text captured |
| TI-005 | captureTextContent=true, view NOT in allowlist | Text NOT captured |
| TI-006 | Empty EditText blur | char_count=0, is_set=false |
| TI-007 | Focus change between two EditTexts | Event for first only (blur) |
| TI-008 | Activity destroyed with focus | No crash, listeners cleaned |
| TI-009 | WeakHashMap GC of ViewTreeObserver | No memory leak |

#### 3.8.4 Back Press Instrumentation

| # | Test Case | Expected |
|---|-----------|----------|
| BP-001 | Back button pressed | ui.back_press emitted |
| BP-002 | Non-back key event | No event |
| BP-003 | ACTION_DOWN for back | No event (only ACTION_UP) |
| BP-004 | UiTelemetryMode.EVENTS | Log record |
| BP-005 | UiTelemetryMode.SPANS | Span under page span |
| BP-006 | UiTelemetryMode.BOTH | Both |

#### 3.8.5 Screen View Instrumentation

| # | Test Case | Expected |
|---|-----------|----------|
| SV-001 | Activity resumed | ui.screen_view + page span started |
| SV-002 | Activity paused → new activity | Previous page span ended, new started |
| SV-003 | Fragment resumed | ui.screen_view for fragment |
| SV-004 | Sequential screens A→B→A | 3 screen_view events, 3 page spans |
| SV-005 | screen.render span | Measures time to first draw via OnPreDraw |
| SV-006 | previous_screen attribute | Correct on transitions |
| SV-007 | time_on_screen_ms | Accurate duration |
| SV-008 | session_id and view_id on page span | Correct, view_id changes per screen |

#### 3.8.6 Lifecycle Instrumentation

| # | Test Case | Expected |
|---|-----------|----------|
| LC-001 | First activity created | app.start emitted once |
| LC-002 | App goes to background | app.background emitted |
| LC-003 | App returns to foreground | app.foreground emitted |
| LC-004 | Session renewed on foreground | session_renewed=true attribute |
| LC-005 | Background duration tracked | background_duration_ms accurate |
| LC-006 | Multi-activity: A starts B | No app-level background (activeActivities > 0) |
| LC-007 | Multi-activity: B finishes, A still running | No app-level background |
| LC-008 | All activities stopped | app.background emitted |

#### 3.8.7 Network Instrumentation

| # | Test Case | Expected |
|---|-----------|----------|
| NET-001 | Successful HTTP GET 200 | Span with http.method, status_code, url |
| NET-002 | HTTP POST 201 | Span attributes correct |
| NET-003 | HTTP 404 client error | Span + http.error log record |
| NET-004 | HTTP 500 server error | Span + http.error log record |
| NET-005 | Network timeout | Span with error status |
| NET-006 | DNS failure | Span with error status |
| NET-007 | W3C trace context propagated | traceparent header injected |
| NET-008 | URL privacy scrubbing | Path params redacted |
| NET-009 | Header allowlist (default preset) | Only allowed headers captured |
| NET-010 | Header allowlist (debug preset) | More headers captured |
| NET-011 | Body capture disabled | No body attributes |
| NET-012 | Body capture enabled | Request/response body in attributes |
| NET-013 | Host on denylist | No span created |
| NET-014 | Host on allowlist only | Only allowed hosts instrumented |
| NET-015 | Connection type attribute | wifi/cellular/offline correct |
| NET-016 | Size bucket attributes | Request/response size buckets correct |
| NET-017 | Concurrent HTTP requests | Independent spans, no cross-contamination |

#### 3.8.8 Errors Instrumentation

| # | Test Case | Expected |
|---|-----------|----------|
| ERR-001 | Uncaught exception | error log with stack trace |
| ERR-002 | Coroutine exception | error log captured |
| ERR-003 | Duplicate exception within 5 min | Deduplicated (1 event only) |
| ERR-004 | Same exception after 5 min | New event (dedup window expired) |
| ERR-005 | Different exception types | Both captured |
| ERR-006 | Rate limit: 11 errors in 1 minute | Only 10 captured |
| ERR-007 | Error attributes | error.type, error.message, stack_trace |
| ERR-008 | Original UncaughtExceptionHandler preserved | Previous handler still called |

#### 3.8.9 Vitals Instrumentation

| # | Test Case | Expected |
|---|-----------|----------|
| VIT-001 | Cold start measured | app.start.type="cold", duration_ms present |
| VIT-002 | Warm start measured | app.start.type="warm" |
| VIT-003 | Jank detection (>16ms frame) | jank event with dropped frames count |
| VIT-004 | Severe jank (>100ms frame) | severe_jank event |
| VIT-005 | ANR risk (>3s block) | anr_risk event |
| VIT-006 | Memory tracking | memory metrics emitted periodically |
| VIT-007 | Thermal monitoring | thermal.state gauge updated |
| VIT-008 | Input latency tracking | input_latency_ms metric |
| VIT-009 | Preset: minimal | Only app start + memory |
| VIT-010 | Preset: aggressive | All metrics, fast intervals |
| VIT-011 | Preset: batteryFriendly | Reduced sampling rate |
| VIT-012 | Sampling rate 0.0 | No metrics emitted |
| VIT-013 | Sampling rate 1.0 | All metrics emitted |

#### 3.8.10 Freeze Instrumentation

| # | Test Case | Expected |
|---|-----------|----------|
| FRZ-001 | Main thread blocked 700ms | ui.freeze emitted |
| FRZ-002 | Main thread blocked 500ms | No event (below threshold) |
| FRZ-003 | Main thread blocked 5000ms | ui.freeze + app.anr emitted |
| FRZ-004 | Custom freeze threshold (250ms) | Freeze at 250ms |
| FRZ-005 | Custom ANR threshold (3000ms) | ANR at 3000ms |
| FRZ-006 | Freeze already in progress | No duplicate events |
| FRZ-007 | Freeze recovery | Event emitted after recovery |
| FRZ-008 | duration_ms attribute accuracy | Within 250ms of actual (watchdog interval) |
| FRZ-009 | screen_name on freeze event | Correct current screen |
| FRZ-010 | Freeze detection disabled | No watchdog thread started |

#### 3.8.11 Wireframe Instrumentation

| # | Test Case | Expected |
|---|-----------|----------|
| WF-001 | Screen view triggers wireframe | ui.wireframe with JSON data |
| WF-002 | Tap triggers wireframe | ui.wireframe on tap (if configured) |
| WF-003 | Error triggers wireframe | ui.wireframe on error (if configured) |
| WF-004 | maxDepth respected | Tree truncated at depth |
| WF-005 | Text redaction enabled | No user text in wireframe |
| WF-006 | Text redaction disabled | Text hints included |
| WF-007 | Rate limit: >30 captures/min | Excess dropped silently |
| WF-008 | Node count attribute | Accurate count |
| WF-009 | Size bytes attribute | Accurate JSON size |
| WF-010 | Disabled config | No capture occurs |
| WF-011 | View with resource ID | includeResourceIds controls presence |
| WF-012 | Clickable/scrollable tracking | Correct flags on nodes |

#### 3.8.12 Screenshot Instrumentation

| # | Test Case | Expected |
|---|-----------|----------|
| SS-001 | Error triggers screenshot | ui.screenshot with data_url |
| SS-002 | Screen view triggers screenshot (if enabled) | ui.screenshot on transition |
| SS-003 | Text redaction | TextViews covered with rectangles |
| SS-004 | Max dimensions downscale | Preserves aspect ratio, fits in maxWidth×maxHeight |
| SS-005 | JPEG quality | Compressed at configured quality (50%) |
| SS-006 | Payload exceeds maxPayloadKb | Silently dropped |
| SS-007 | Rate limit: >5 captures/min | Excess dropped |
| SS-008 | PixelCopy API available (26+) | Uses hardware capture |
| SS-009 | PixelCopy fallback | Uses View.draw() |
| SS-010 | No foreground activity | No crash, capture skipped |
| SS-011 | Disabled config | No capture occurs |

---

### 3.9 Privacy & PII

| # | Test Case | Expected |
|---|-----------|----------|
| PII-001 | Email in URL scrubbed | user@example.com → [email] |
| PII-002 | SSN in text scrubbed | 123-45-6789 → [ssn] |
| PII-003 | Credit card scrubbed | 4111-1111-1111-1111 → [card] |
| PII-004 | Phone number scrubbed | +1-555-123-4567 → [phone] |
| PII-005 | UUID in URL path | /users/abc-123-def → /users/[id] |
| PII-006 | Numeric ID in URL path | /orders/12345 → /orders/[id] |
| PII-007 | Query params scrubbed | ?token=abc → ?token=[redacted] |
| PII-008 | PrivacyMode.OFF | No scrubbing |
| PII-009 | PrivacyMode.BASIC | Emails and SSNs scrubbed |
| PII-010 | PrivacyMode.STRICT | All PII patterns scrubbed |
| PII-011 | Deep link scrubbing | myapp://user/abc → myapp://user/[id] |
| PII-012 | No false positives on safe strings | "hello world" unchanged |

---

### 3.10 Recovery Tracker

| # | Test Case | Expected |
|---|-----------|----------|
| REC-001 | Crash recovery on next launch | recovery event with type="crash" |
| REC-002 | ANR recovery | recovery event with type="anr" |
| REC-003 | Low memory recovery | recovery event with type="low_memory" |
| REC-004 | Clean shutdown, normal launch | No recovery event |
| REC-005 | Multiple crashes, single recovery | One recovery event |
| REC-006 | Recovery clears crash flag | Flag reset after emission |

---

### 3.11 Log Tailing

| # | Test Case | Expected |
|---|-----------|----------|
| LT-001 | Buffer holds last N events | Circular buffer at configured size |
| LT-002 | Buffer overflow | Oldest events evicted |
| LT-003 | Severity filter | Only events >= configured severity |
| LT-004 | Tag filter | Only matching tags included |
| LT-005 | Buffer disabled | No accumulation |

---

### 3.12 Fleet Alerts

#### 3.12.1 FleetAlertHandler

| # | Test Case | Expected |
|---|-----------|----------|
| FA-001 | Valid alert with flush_buffer action | `processor.flushWindow(minutes)` called, result.executed=true |
| FA-002 | Valid alert with set_sampling action | Sampling override applied, activeOverrides updated |
| FA-003 | Valid alert with take_screenshot action | Screenshot triggered (if privacy allows) |
| FA-004 | Expired alert (expiresAt in past) | result.executed=false, reason="expired" |
| FA-005 | Invalid expiresAt format | result.executed=false, reason="invalid_expiry" |
| FA-006 | Duplicate alert (same alertId) | result.executed=false, reason="duplicate" |
| FA-007 | Rate limit: 6th alert within 1 hour | result.executed=false, reason="rate_limited" |
| FA-008 | Rate limit: 5th alert within 1 hour | Allowed (at limit, not over) |
| FA-009 | Rate limit window expiry | Alert >1h ago cleared, new alert allowed |
| FA-010 | Privacy: allowFleetFlush=false | flush_buffer action skipped, in actionsSkipped list |
| FA-011 | Privacy: allowFleetSampling=false | set_sampling action skipped |
| FA-012 | Privacy: allowFleetScreenshot=false | take_screenshot action skipped |
| FA-013 | Priority preemption: higher priority overrides lower | Lower-priority set_sampling blocked when higher-priority override active |
| FA-014 | Priority preemption: lower priority does NOT override higher | Returns false, existing override preserved |
| FA-015 | Unknown action type | Action skipped with warning, other actions still execute |
| FA-016 | Action throws exception | Caught, logged, other actions still execute |
| FA-017 | Multiple actions in one alert | All valid actions executed, results tracked |
| FA-018 | Alert with empty actions list | result.executed=true, no actions |

#### 3.12.2 FleetAlertDeduplicator

| # | Test Case | Expected |
|---|-----------|----------|
| FD-001 | isProcessed for unknown alertId | Returns false |
| FD-002 | markProcessed then isProcessed | Returns true |
| FD-003 | Cleanup removes entries >1 hour old | Old entries purged from SharedPreferences |
| FD-004 | Cleanup preserves recent entries | Entries <1h old retained |
| FD-005 | clear() removes all entries | All alertIds removed |
| FD-006 | Persistence across deduplicator instances | SharedPreferences survives re-instantiation |

#### 3.12.3 FleetAlert Data Model

| # | Test Case | Expected |
|---|-----------|----------|
| FM-001 | Deserialization from valid JSON | All fields populated correctly |
| FM-002 | Default values applied | type="fleet_alert", hop=0, priority=2, truncated=false |
| FM-003 | FleetAction with config map | config["minutes"] accessible |
| FM-004 | FleetAction with empty config | config is emptyMap, no crash |

---

### 3.13 Core Module Classes

#### 3.13.1 WindowEventHub and WindowEventHubInstaller

| # | Test Case | Expected |
|---|-----------|----------|
| WH-001 | addListener registers listener | Listener receives events |
| WH-002 | removeListener unregisters | Listener no longer receives events |
| WH-003 | Idempotent addListener | Same listener added twice, receives events once |
| WH-004 | Dispatch touch event to multiple listeners | All listeners receive the event |
| WH-005 | Dispatch key event to multiple listeners | All listeners receive the event |
| WH-006 | No-op with empty listener list | No crash on dispatch |
| WH-007 | Listener count tracks correctly | count() matches add/remove |
| WH-008 | CopyOnWriteArrayList thread safety | Concurrent add/remove/dispatch, no ConcurrentModificationException |
| WH-009 | Installer wraps Window.Callback per activity | HubDispatcher installed on resume |
| WH-010 | Installer restores original callback on uninstall | Original Window.Callback preserved |
| WH-011 | HubDispatcher delegates to original callback | Host app always receives events even if listeners throw |
| WH-012 | Exception in listener does not prevent delegation | Original callback still invoked |

#### 3.13.2 OTelMobileBuilder and SDK Initialization

| # | Test Case | Expected |
|---|-----------|----------|
| OB-001 | build() returns valid handle | Non-null OTelMobileHandle |
| OB-002 | All modules installed via ServiceLoader | InstrumentationRegistry discovers all SPI entries |
| OB-003 | Custom session provider wired | Custom provider used for session IDs |
| OB-004 | stop() uninstalls all modules | All listeners removed, no leaks |
| OB-005 | Double start() is idempotent | No crash, no duplicate instrumentation |
| OB-006 | Start with null application | Graceful error, no crash |
| OB-007 | OTelMobile vs MobileOtel entry points | Both produce equivalent instrumentation |

#### 3.13.3 InstrumentationRegistry

| # | Test Case | Expected |
|---|-----------|----------|
| IR-001 | install() loads all SPI modules | All registered modules discovered |
| IR-002 | uninstall() cleans up all modules | All modules get uninstall() called |
| IR-003 | Empty module list | No crash, no instrumentation |
| IR-004 | Module throws during install | Other modules still installed, error logged |

#### 3.13.4 RateLimiter

| # | Test Case | Expected |
|---|-----------|----------|
| RL-001 | Allow events under limit | Returns true for maxPerWindow events |
| RL-002 | Reject at limit | Returns false for maxPerWindow+1 |
| RL-003 | Events expire after window | Old events cleared, new events allowed |
| RL-004 | Custom window length | Respects configured windowMs |
| RL-005 | Thread-safe concurrent access | 10 threads, no race conditions |
| RL-006 | Reset clears all events | count() returns 0 after reset |

#### 3.13.5 Breadcrumb System

| # | Test Case | Expected |
|---|-----------|----------|
| BC-001 | Breadcrumbs accumulated during journey | Circular buffer stores events |
| BC-002 | Buffer overflow | Oldest breadcrumbs evicted |
| BC-003 | Screen whitelist (empty = all) | All screens tracked |
| BC-004 | Screen whitelist (non-empty) | Only allowlisted screens tracked |
| BC-005 | Case sensitivity on screen names | Exact match required |
| BC-006 | maxSize validation | Positive value required |
| BC-007 | Preset: default | Standard settings |
| BC-008 | Preset: minimal | Reduced capture |
| BC-009 | Preset: privacyFocused | No text content |
| BC-010 | Preset: disabled | No breadcrumbs collected |
| BC-011 | Navigation tracking | Deep links and activity transitions captured |

#### 3.13.6 WindowCallbackWrapper (Host Safety Critical)

| # | Test Case | Expected |
|---|-----------|----------|
| WC-001 | Delegate always invoked on touch | Original Window.Callback receives MotionEvent regardless of SDK state |
| WC-002 | Delegate always invoked on key | Original Window.Callback receives KeyEvent regardless of SDK state |
| WC-003 | Exception in tap handler | Caught internally, delegate still called |
| WC-004 | Exception in back-press handler | Caught internally, delegate still called |
| WC-005 | Multi-touch events | All pointers dispatched to delegate |
| WC-006 | Null original callback | No crash, events handled internally only |

#### 3.13.7 ContextSnapshot

| # | Test Case | Expected |
|---|-----------|----------|
| CS-001 | Country/region/timezone extraction | Correct values from device locale |
| CS-002 | ConnectivityManager null | network.type = "unknown" |
| CS-003 | BatteryManager null | battery.level = -1, state = "unknown" |
| CS-004 | captureLocation disabled | Geo fields empty |
| CS-005 | Performance | Snapshot computed in < 1ms |
| CS-006 | All fields populated (happy path) | geo.*, device.*, app.*, session.* all present |

#### 3.13.8 Export Module Classes

| # | Test Case | Expected |
|---|-----------|----------|
| EX-001 | EnrichingLogRecordExporter adds geo attributes | Geo attributes present on exported logs |
| EX-002 | EnrichingLogRecordExporter adds device attributes | Device attributes present |
| EX-003 | AttributeEnricher all namespaces | geo.*, device.*, app.*, policy.* attributes added |
| EX-004 | AttributeEnricher with null context | No crash, attributes absent |
| EX-005 | ExportStatus.Success | Correct variant |
| EX-006 | ExportStatus.Failed with reason | Reason preserved |
| EX-007 | ExportStatus.AuthError | Distinct from generic failure |
| EX-008 | LoggingHttpExporter logs export attempts | Debug callback invoked |
| EX-009 | MobileLoggerProvider singleton | Second call returns same instance |
| EX-010 | MobileLoggerProvider wiring | Tracer, Logger, Meter all configured |
| EX-011 | MobileLoggerProvider resource attributes | service.name, service.version, device.id correct |
| EX-012 | Invalid configuration | Graceful error, defaults used |

#### 3.13.9 MobileSemconv (Semantic Conventions)

| # | Test Case | Expected |
|---|-----------|----------|
| SC-001 | Event name constants valid | All names match OTel semantic conventions |
| SC-002 | Attribute key constants valid | All keys follow dotted.notation pattern |
| SC-003 | All emitted spans use semconv names | No hardcoded string literals in instrumentation |
| SC-004 | All emitted logs use semconv event names | Consistent naming |

### 3.14 Incubating Instrumentation Modules

#### 3.14.1 Database Instrumentation

| # | Test Case | Expected |
|---|-----------|----------|
| DB-001 | Room QueryCallback hooks SQL queries | db.query span created |
| DB-002 | Query attributes | db.statement, db.system, db.operation present |
| DB-003 | Slow query threshold | Only queries > threshold generate spans |
| DB-004 | Install/uninstall lifecycle | Callback registered/removed cleanly |
| DB-005 | No Room available | Graceful no-op |

#### 3.14.2 File I/O Instrumentation

| # | Test Case | Expected |
|---|-----------|----------|
| FIO-001 | File read tracked | io.read span with file.path, file.size |
| FIO-002 | File write tracked | io.write span |
| FIO-003 | Install/uninstall lifecycle | Clean setup/teardown |
| FIO-004 | Non-file streams | Handled gracefully |

#### 3.14.3 System Events Instrumentation

| # | Test Case | Expected |
|---|-----------|----------|
| SE-001 | Battery change broadcast | system.battery_changed event |
| SE-002 | Power connected/disconnected | system.power_connected event |
| SE-003 | Airplane mode toggle | system.airplane_mode event |
| SE-004 | Storage low broadcast | system.storage_low event |
| SE-005 | BroadcastReceiver lifecycle | Registered on install, unregistered on uninstall |
| SE-006 | Rapid broadcast storm | Rate limited |

#### 3.14.4 Timber Instrumentation

| # | Test Case | Expected |
|---|-----------|----------|
| TIM-001 | Timber.d() forwarded to OTel | Log record at DEBUG severity |
| TIM-002 | Timber.e() forwarded to OTel | Log record at ERROR severity |
| TIM-003 | Timber.w() forwarded to OTel | Log record at WARN severity |
| TIM-004 | Timber.Tree installed/removed | Plant on install, uproot on uninstall |
| TIM-005 | Timber not on classpath | Graceful no-op, no ClassNotFoundException |

### 3.15 OTel Semantic Convention Conformance

All emitted telemetry must conform to OpenTelemetry semantic conventions.
`MobileSemconv.kt` defines the SDK's constants — these tests verify they are
used correctly throughout all instrumentation modules.

#### 3.15.1 Span Naming Conventions

| # | Test Case | Expected |
|---|-----------|----------|
| SN-001 | Page spans named `page.<ScreenName>` | Matches `page.` prefix convention |
| SN-002 | Network spans use HTTP method as name | `GET`, `POST`, `PUT`, etc. (OTel HTTP semconv) |
| SN-003 | Render spans named `screen.render` | Consistent naming |
| SN-004 | Journey spans use provided name | `journeyName` passed to `startJourney()` |
| SN-005 | UI interaction spans use semconv event names | `ui.tap`, `ui.scroll`, `ui.text_input`, `ui.back_press` |
| SN-006 | No hardcoded string literals in instrumentation | All span/event names reference `MobileSemconv` constants |

#### 3.15.2 Attribute Key Conventions

| # | Test Case | Expected |
|---|-----------|----------|
| AK-001 | HTTP attributes follow OTel semconv | `http.request.method`, `http.response.status_code`, `url.full`, `server.address` |
| AK-002 | Network attributes follow OTel semconv | `network.connection.type` (not custom name) |
| AK-003 | Session attributes use dotted notation | `session.id`, `view.id`, `screen.name` |
| AK-004 | Error attributes follow OTel semconv | `error.type`, `error.message` (not `exception.type`) |
| AK-005 | Device attributes use consistent namespace | `device.id`, `device.model`, `device.class` |
| AK-006 | Geo attributes use consistent namespace | `geo.country`, `geo.region`, `geo.timezone` |
| AK-007 | App attributes use consistent namespace | `app.version`, `app.build`, `app.environment` |

#### 3.15.3 Span Kind and Status Conventions

| # | Test Case | Expected |
|---|-----------|----------|
| SK-001 | Network spans: SpanKind.CLIENT | HTTP requests are client spans |
| SK-002 | Page spans: SpanKind.INTERNAL | UI navigation spans are internal |
| SK-003 | HTTP 5xx: StatusCode.ERROR | Server errors set span status to ERROR |
| SK-004 | HTTP 4xx (client error): StatusCode.ERROR or UNSET | Depending on semconv version |
| SK-005 | Successful operations: StatusCode.UNSET or OK | No spurious error status |

#### 3.15.4 Log Severity Mapping

| # | Test Case | Expected |
|---|-----------|----------|
| LS-001 | ui.tap, ui.scroll, ui.screen_view | Severity.INFO |
| LS-002 | ui.freeze | Severity.ERROR |
| LS-003 | app.anr | Severity.ERROR |
| LS-004 | http.error (4xx/5xx) | Severity.WARN or ERROR |
| LS-005 | app.crash / error | Severity.ERROR |
| LS-006 | prediction.cycle (debug) | Severity.DEBUG |
| LS-007 | device.heartbeat | Severity.INFO |
| LS-008 | recovery event | Severity.WARN |

#### 3.15.5 Resource Attributes

| # | Test Case | Expected |
|---|-----------|----------|
| RA-001 | service.name set from config | Matches MobileConfig.serviceName |
| RA-002 | service.version set from config | Matches MobileConfig.serviceVersion |
| RA-003 | telemetry.sdk.name | "opentelemetry" |
| RA-004 | telemetry.sdk.language | "kotlin" or "android" |
| RA-005 | device.id persistent | Same across app restarts |
| RA-006 | os.type | "android" |
| RA-007 | os.version | Matches Build.VERSION.SDK_INT |

---

## 4. Cross-Cutting Concern Tests

### 4.1 Offline ↔ Online State Matrix

This is the most critical cross-cutting concern. Every export-related behavior
must be tested under both network states and all transitions.

| # | Network State | Export Mode | Event Type | Action | Expected |
|---|--------------|-------------|------------|--------|----------|
| OO-001 | Online | CONDITIONAL | ui.freeze (policy match) | Policy triggers flush | Events exported successfully |
| OO-002 | Online | CONDITIONAL | ui.tap (no match) | Normal buffering | Events stay in RAM |
| OO-003 | Online | CONTINUOUS | Any trace | Periodic timer fires | Events exported |
| OO-004 | Online | CONTINUOUS | Any log | Periodic timer fires | Events exported |
| OO-005 | Online | CONTINUOUS | Any metric | Periodic timer fires | Metrics exported |
| OO-006 | Online | HYBRID | Heartbeat | Heartbeat tick | Lightweight export |
| OO-007 | Online | HYBRID | Policy match | Bulk flush | Full buffer window exported |
| OO-008 | **Offline** | CONDITIONAL | ui.freeze (match) | Policy triggers flush | Export FAILS → events stay buffered (RAM+disk) |
| OO-009 | **Offline** | CONDITIONAL | ui.tap (no match) | Buffering | Events accumulate normally |
| OO-010 | **Offline** | CONTINUOUS | Any | Timer fires | Export FAILS → retry with backoff, events retained |
| OO-011 | **Offline** | HYBRID | Heartbeat | Tick fires | Export FAILS → heartbeat buffered |
| OO-012 | **Offline** | HYBRID | Policy match | Flush attempt | FAILS → events retained for later |
| OO-013 | **Offline→Online** | CONDITIONAL | Accumulated events | Network restored | Next policy match exports buffered events |
| OO-014 | **Offline→Online** | CONTINUOUS | Accumulated events | Network restored, timer fires | All accumulated events exported |
| OO-015 | **Offline→Online** | HYBRID | Accumulated events | Network restored | Heartbeats + any pending bulk flush exported |
| OO-016 | **Online→Offline** | CONTINUOUS | Mid-export | Connection drops during upload | Partial failure, retry on reconnect |
| OO-017 | **Online→Offline** | Any | Events in flight | Connection drops | RetryableExporter handles with backoff |
| OO-018 | **Offline (extended, 24h)** | Any | Buffer filling | No network for 24h | RAM overflows to disk, TTL cleanup runs, oldest events expired |
| OO-019 | **Offline (extended, 48h)** | Any | TTL expiry | No network for 48h | All events past 24h TTL expired, newer events retained |
| OO-020 | **Flaky network** | CONTINUOUS | Rapid on/off | Alternating every 10s | Backoff increases, no crash, events eventually exported |
| OO-021 | **WiFi → Cellular** | Any | Active session | Network type change | No data loss, export continues, network.type attribute updated |
| OO-022 | **Cellular → WiFi** | Any | Buffered events | Switch to WiFi | Pending exports proceed |
| OO-023 | **Airplane mode** | Any | All modules active | Network gone instantly | All modules continue buffering, no crash |

### 4.2 Telemetry Type × Export Mode Matrix

| # | Telemetry | Export Mode | Generated By | Expected Behavior |
|---|-----------|-------------|-------------|-------------------|
| TT-001 | **Traces** (spans) | CONDITIONAL | Network interceptor, page spans | Spans buffered, exported on policy match |
| TT-002 | **Traces** | CONTINUOUS | Network interceptor, page spans | Spans exported periodically |
| TT-003 | **Traces** | HYBRID | Network interceptor, page spans | Spans in periodic lightweight + bulk flush |
| TT-004 | **Logs** (events) | CONDITIONAL | All UI events, errors, freeze | Logs buffered, exported on policy match |
| TT-005 | **Logs** | CONTINUOUS | All UI events, errors, freeze | Logs exported periodically |
| TT-006 | **Logs** | HYBRID | All UI events, errors, freeze | Logs in bulk flush on policy match |
| TT-007 | **Metrics** (gauges) | CONDITIONAL | DeviceMetricsCollector | Metrics emitted to OTel meter, export per OTel SDK |
| TT-008 | **Metrics** | CONTINUOUS | DeviceMetricsCollector | Metrics exported with regular OTel metric export |
| TT-009 | **Metrics** | HYBRID | DeviceMetricsCollector | Metrics on heartbeat schedule |
| TT-010 | **Mixed** (all) | CONDITIONAL | Full instrumentation | Only matching telemetry types exported per policy scope |
| TT-011 | **Mixed** | CONTINUOUS | Full instrumentation | All types exported together periodically |
| TT-012 | **Mixed** | HYBRID | Full instrumentation | Heartbeat metrics + bulk traces/logs on match |

### 4.3 Concurrent Operations Safety

| # | Test Case | Expected |
|---|-----------|----------|
| CC-001 | 10 threads emitting logs simultaneously | All events captured, none lost |
| CC-002 | Export + onEmit concurrent | ConcurrentLinkedQueue handles safely |
| CC-003 | Policy evaluation + config refresh | AtomicReference swap, no stale reads |
| CC-004 | Session renewal during active span | Span retains original session, new events get new session |
| CC-005 | Disk mirror during flush | Identity-based dedup prevents duplicates |
| CC-006 | Disk cleanup during insert | No deadlock (Room transactions) |
| CC-007 | Multiple instrumentation modules firing simultaneously | Each independent, no cross-contamination |
| CC-008 | Prediction cycle + manual flush | No double export of same events |
| CC-009 | Config refresh during policy evaluation | Evaluation uses snapshot, not live reference |
| CC-010 | forceFlush + flushWindow at same time | Serialized, no data corruption |

### 4.4 App Lifecycle × SDK State

| # | Lifecycle Event | SDK State | Expected |
|---|----------------|-----------|----------|
| AL-001 | App cold start | SDK initializing | All modules installed via ServiceLoader |
| AL-002 | App foreground (from background) | SDK running | Session potentially renewed, metrics resume |
| AL-003 | App background | SDK running | Continuous export suspends/continues per config |
| AL-004 | App killed by OS | SDK running | RAM lost, disk events survive |
| AL-005 | App crash (uncaught exception) | SDK running | Error captured, crash flag set, disk events survive |
| AL-006 | App ANR (5s block) | SDK running | ANR detected, events buffered |
| AL-007 | Low memory warning | SDK running | No SDK memory released (host-first principle) |
| AL-008 | Configuration change (rotation) | SDK running | No re-initialization, state preserved |
| AL-009 | Multi-process app | SDK in main process | No cross-process interference |
| AL-010 | App update (new version) | SDK reinit | Disk buffer migration (Room handles schema) |

### 4.5 Privacy Mode × Data Capture

| # | Privacy Mode | Module | Expected |
|---|-------------|--------|----------|
| PM-001 | OFF | Tap | Full element info captured |
| PM-002 | BASIC | Tap | Element ID but not labels |
| PM-003 | STRICT | Tap | Coordinates only, no element info |
| PM-004 | OFF | Text Input | Full text captured (if in allowlist) |
| PM-005 | BASIC | Text Input | Char count only |
| PM-006 | STRICT | Text Input | is_set only |
| PM-007 | OFF | Network | Full URLs, headers, body |
| PM-008 | BASIC | Network | Scrubbed URLs, allowed headers only |
| PM-009 | STRICT | Network | Method + status only |
| PM-010 | OFF | Screenshot | Full screenshots |
| PM-011 | BASIC | Screenshot | Text redacted |
| PM-012 | STRICT | Screenshot | Disabled entirely |
| PM-013 | OFF | Wireframe | Full tree with text |
| PM-014 | BASIC | Wireframe | Tree without text content |
| PM-015 | STRICT | Wireframe | Bounds and types only |

---

## 5. Integration Test Suites

### 5.1 User Journey: Happy Path (Online + CONTINUOUS)

```
1. App launches cold
2. SDK initializes (all 11 modules)
3. User views Screen A → page span started
4. User taps button → ui.tap event
5. HTTP request fires → network span
6. User scrolls list → ui.scroll event
7. User navigates to Screen B → screen_view, new page span
8. User types in form → ui.text_input on blur
9. Timer fires → all events exported via OTLP
10. Verify: OTLP receiver got spans + logs + metrics
11. Verify: trace context propagated across spans
12. Verify: session_id consistent across all events
13. Verify: view_id changes on screen transition
```

### 5.2 User Journey: Crash Recovery (Offline + CONDITIONAL)

```
1. App starts online → SDK initializes
2. Device goes offline
3. User generates 100 events
4. Events buffer in RAM → mirror to disk
5. App crashes (simulated uncaught exception)
6. App relaunches
7. Recovery event emitted (type="crash")
8. Device comes online
9. ui.freeze or crash policy matches
10. Verify: all 100 pre-crash events + recovery event exported
11. Verify: no data loss despite crash + offline
```

### 5.3 User Journey: Extended Offline (HYBRID, 1 hour offline)

```
1. App starts online → SDK initializes in HYBRID mode
2. Device goes offline
3. Generate events for 60 minutes (simulated clock advance):
   - 200 UI events, 50 HTTP errors, 10 freezes
4. RAM overflows → events on disk
5. Heartbeats attempted → export fails → buffered
6. Device comes online
7. Heartbeat export succeeds
8. Freeze policy matches → bulk flush
9. Verify: all events with valid timestamps exported
10. Verify: TTL not exceeded (still within 24h)
11. Verify: disk buffer cleaned after export
```

### 5.4 User Journey: High-Frequency Event Storm

```
1. App running CONTINUOUS mode
2. Rapid fire: 1000 events in 10 seconds
   - 500 taps, 200 scrolls, 200 network calls, 100 errors
3. RAM buffer fills → overflow to disk
4. Timer fires → export
5. Verify: no events lost
6. Verify: no OOM
7. Verify: UI thread not blocked (no jank from SDK)
8. Verify: throttling works (scroll events ~2/sec max)
9. Verify: error dedup works (unique errors only)
```

### 5.5 User Journey: Policy-Driven Selective Export

```
1. Configure policies:
   - Policy A: ui.freeze > 1000ms AND network=wifi → flush 2 min
   - Policy B: http.error AND country=US → flush 5 min
2. Generate mixed events online:
   - ui.freeze 500ms (no match - below threshold)
   - ui.freeze 1500ms on wifi (matches A)
   - http.error in DE (no match - wrong country)
   - http.error in US (matches B)
3. Verify: flush triggered only for matching events
4. Verify: flush window correct (2 min for A, 5 min for B)
5. Verify: non-matching events remain buffered
```

### 5.6 User Journey: Dynamic Sampling Adjustment

```
1. Start with baseline sampling rate 0.1 (10%)
2. Generate 100 traces
3. Verify: ~10 traces sampled (statistical tolerance ±5)
4. Policy action: set_sampling rate=1.0, duration_minutes=5
5. Generate 100 more traces
6. Verify: all 100 traces sampled
7. Wait 5 minutes (simulated)
8. Generate 100 more traces
9. Verify: ~10 traces sampled (reverted to baseline)
10. Verify: page spans always sampled (100%) regardless
```

### 5.7 Showcase: Distributed Trace — Tap to Backend and Back

> **Demo value**: A single trace ID links a user's finger tap on glass to
> the backend database query and back. This is the "wow" moment for anyone
> who has debugged mobile issues with disconnected logs.

```
1. SDK init with CONTINUOUS mode, OTelNetworkInterceptor on OkHttpClient
2. Demo backend running (Express + OTel SDK, same Dash0 dataset)
3. User taps "Book Appointment" button
4. Capture the traceId from the page span context
5. OkHttp interceptor creates GET /api/appointments span (SpanKind.CLIENT)
6. W3C traceparent header injected: 00-{traceId}-{spanId}-01
7. Backend receives header, creates server span with same traceId
8. Backend queries SQLite, returns appointments JSON
9. Response arrives → mobile span ends with status 200
10. Force flush

Dash0 Validation:
  - dash0 -X traces get {traceId}
  - Verify tree: journey → page.BookFragment → ui.tap → GET /api/appointments → [backend spans]
  - Verify: mobile span and backend span share SAME traceId
  - Verify: backend span is CHILD of mobile HTTP span (parent_span_id matches)
  - Verify: total trace duration = mobile tap → backend response
  - Verify: http.request.method, http.response.status_code, url.full all present
  - Visual: Dash0 trace waterfall shows mobile + backend in one view
```

### 5.8 Showcase: Silent Buffer → Crash → Full Context Recovery

> **Demo value**: 30 seconds of silent user activity, then a crash. On
> relaunch, the ENTIRE pre-crash journey exports to Dash0. Every tap, every
> screen, every breadcrumb — reconstructing what happened before the crash.

```
1. SDK init with CONDITIONAL mode (no periodic exports)
2. User journey (30 seconds of activity, all silently buffered):
   a. Launch → app.start event
   b. Navigate HomeScreen → page span + screen_view
   c. Tap search → ui.tap event
   d. Navigate SearchScreen → page span + screen_view
   e. Type query → ui.text_input event
   f. Scroll results → 3x ui.scroll events
   g. Tap result item → ui.tap event
   h. Navigate DetailScreen → page span + screen_view
   i. HTTP GET /api/details → network span (200)
   j. Tap "Book" button → ui.tap event
   k. HTTP POST /api/book → network span (500 error!)
   l. http.error event emitted
3. Verify: ZERO events exported to Dash0 so far (all buffered!)
4. Simulate crash: throw RuntimeException("Booking service unavailable")
5. app.crash event → policy matches → flushWindow(5)
6. ALL ~15 events from the last 5 minutes export in a single batch

Dash0 Validation:
  - Query by session.id: all 15 events present
  - Breadcrumb order: Home → Search → type → scroll → Detail → book → error → crash
  - Network spans: GET 200 + POST 500, both with traceId
  - Crash event has full stack trace
  - Timeline shows exact user path leading to the crash
  - NOTHING was exported before the crash (conditional = silent until triggered)
```

### 5.9 Showcase: Predictive Pre-emptive Flush — "Saving Data Before the Ship Sinks"

> **Demo value**: The SDK detects that the device is about to lose network
> or crash, and pre-emptively exports buffered events BEFORE the failure
> happens. Future-proof debugging.

```
1. SDK init with HYBRID mode + PredictiveExportPolicy enabled
2. Generate 50 events over 2 minutes (normal user activity)
3. Simulate deteriorating device health:
   a. Set available memory to 45MB (below 50MB critical threshold)
   b. Set memory trend declining at 15MB/min
   c. Set battery to 8% (below 10% critical)
   d. Set thermal state to SEVERE
4. Prediction cycle fires on next heartbeat:
   - crashRisk = 0.6 (memory) + 0.3 (trend) = 0.9 (above 0.7 threshold!)
   - networkLossRisk = 0.3 (normal)
   - batteryDrainRisk = 0.6 + 0.1 = 0.7 (at threshold)
5. PredictiveExportPolicy triggers: processor.flushWindow(5)
6. prediction.high_risk_alert event emitted at WARN severity
7. ALL 50 buffered events export + the prediction event itself

Dash0 Validation:
  - prediction.cycle event shows risk scores (crash=0.9, battery=0.7)
  - prediction.high_risk_alert event at WARN severity
  - All 50 pre-prediction events present (the "saved" context)
  - device.heartbeat events show deteriorating health metrics
  - Timeline: healthy heartbeats → deterioration → prediction → flush → all context
8. NOW simulate the crash (or don't — the data is already safe!)
```

### 5.10 Showcase: Wireframe Journey Replay — "See What the User Saw"

> **Demo value**: Every screen transition captures a lightweight wireframe
> (1-5KB JSON) showing the layout, buttons, and interactive elements. String
> these together and you get a clickable journey replay without screenshots.

```
1. SDK init with WireframeConfig(captureOnScreenView=true, captureOnTap=true)
2. Navigate through 5 screens:
   a. HomeScreen (2 buttons, 1 list, nav bar)
   b. SearchScreen (search field, filter chips, results list)
   c. DetailScreen (image, text blocks, "Book" button)
   d. BookingScreen (form: name, date, time fields, submit button)
   e. ConfirmationScreen (success icon, booking ID, "Done" button)
3. Tap "Book" on DetailScreen (captures tap-triggered wireframe)
4. Force flush

Dash0 Validation:
  - 5 screen-transition wireframes + 1 tap wireframe = 6 ui.wireframe events
  - Each has: wireframe.data (valid JSON), wireframe.node_count, wireframe.size_bytes
  - wireframe.sequence numbers are monotonically increasing (1..6)
  - Parse wireframe.data JSON for DetailScreen:
    {
      "type": "FrameLayout", "bounds": [0,0,1080,2400],
      "children": [
        {"type": "ImageView", "bounds": [0,0,1080,600]},
        {"type": "TextView", "bounds": [40,620,1040,700], "id": "title"},
        {"type": "Button", "bounds": [340,1900,740,2000], "id": "btn_book", "clickable": true}
      ]
    }
  - No user text in wireframes (only hints if enabled)
  - Control plane UI can render these as interactive journey replay
```

### 5.11 Showcase: Screenshot on Error with Privacy Redaction

> **Demo value**: When an error occurs, the SDK captures a screenshot of the
> exact screen state — with all text automatically redacted. You see the
> layout and colors but not private data.

```
1. SDK init with ScreenshotConfig(captureOnError=true, redactTextViews=true)
2. Navigate to a screen with:
   - User's name displayed
   - Email address visible
   - Credit card last-4 shown
   - Form fields with typed data
3. Trigger HTTP 500 error on that screen
4. Screenshot capture fires automatically:
   a. PixelCopy captures hardware-accelerated bitmap
   b. All TextView bounds collected recursively
   c. Dark gray rectangles drawn over every TextView
   d. Bitmap compressed to JPEG at 50% quality
   e. Downscaled to 480x960 max
   f. Base64-encoded as data:image/jpeg;base64,...
   g. Payload size checked (< 200KB)
5. Force flush

Dash0 Validation:
  - ui.screenshot event with screenshot.data_url attribute
  - screenshot.format = "jpeg"
  - screenshot.redacted = true
  - screenshot.size_bytes < 200000
  - Decode the base64 data URL → visual inspection:
    - Layout and button shapes visible
    - All text areas covered by solid gray rectangles
    - NO readable user data in the image
  - Screenshot timestamp matches the error event timestamp
```

### 5.12 Showcase: Three Export Modes Side-by-Side

> **Demo value**: Run the exact same user journey under CONDITIONAL,
> CONTINUOUS, and HYBRID modes. Show how each mode changes what appears
> in Dash0 and when.

```
Run 1: CONDITIONAL mode (test.run_id = "cond-{uuid}")
  1. Generate 20 events over 30 seconds
  2. Wait 60 seconds
  3. Query Dash0: ZERO events (nothing triggered a policy)
  4. Generate ui.freeze (1200ms) → policy match → flush 2 min
  5. Query Dash0: ~20 events arrive in a single batch
  6. Verify: events have original timestamps from 30-90s ago

Run 2: CONTINUOUS mode (test.run_id = "cont-{uuid}")
  1. Generate 20 events over 30 seconds
  2. Wait for 2 export timer cycles (e.g., 60s interval)
  3. Query Dash0: events arriving in periodic batches
  4. Verify: events arrive in groups aligned to timer interval
  5. Generate ui.freeze → also exported (next timer cycle)

Run 3: HYBRID mode (test.run_id = "hybr-{uuid}")
  1. Generate 20 events over 30 seconds
  2. Wait 60 seconds
  3. Query Dash0: only device.heartbeat events (lightweight stream)
  4. Generate ui.freeze → policy match → bulk flush
  5. Query Dash0: heartbeats + all 20 events + freeze event
  6. Verify: heartbeats are periodic, bulk events are policy-triggered

Comparison Table (in test output):
  | Mode        | Events at T=60s | Events at T=90s (after freeze) |
  |-------------|----------------|-------------------------------|
  | CONDITIONAL | 0              | ~20 (batch)                   |
  | CONTINUOUS  | ~10 (partial)  | ~20 (all, via timer)          |
  | HYBRID      | 2 heartbeats   | 2 heartbeats + ~20 (batch)    |
```

### 5.13 Showcase: Geo/Device Policy — "Smart Export for Enterprise"

> **Demo value**: A single policy that exports crash context ONLY for
> high-value users on WiFi in North America. Shows enterprise-grade
> cost control.

```
1. Configure policy:
   {
     "id": "premium-crash-na-wifi",
     "match": {
       "logical_operator": "and",
       "attributes": {"event.name": {"equals": "app.crash"}},
       "geo": {"country": ["US","CA"], "timezone": ["America/*"]},
       "device": {"network": ["wifi"], "battery": ["charging","normal"]}
     },
     "actions": {"flush_window_minutes": 10}
   }

2. Scenario A: Crash on WiFi in US → MATCH
   - Context: country=US, tz=America/New_York, network=wifi, battery=normal
   - Generate 30 events + crash
   - Verify: all 30 events + crash exported to Dash0

3. Scenario B: Same crash on Cellular in US → NO MATCH
   - Context: country=US, network=cellular
   - Generate 30 events + crash
   - Verify: ZERO events in Dash0 (policy didn't match)

4. Scenario C: Same crash on WiFi in Germany → NO MATCH
   - Context: country=DE, network=wifi
   - Generate 30 events + crash
   - Verify: ZERO events in Dash0

5. Scenario D: Crash matches default fallback policy
   - Remove custom policy, use defaults
   - Crash → default "crash-recovery" policy → flush 5 min
   - Verify: events exported (default always catches crashes)
```

### 5.14 Showcase: Offline Resilience — "48 Hours in a Tunnel"

> **Demo value**: The app works offline for an extended period. Events
> buffer to disk, survive the journey, and export when connectivity
> returns. Not a single event is lost.

```
1. SDK init with CONDITIONAL mode, disk buffer 50MB, TTL 48h
2. Go online briefly → SDK initializes, fetches config
3. Go OFFLINE (airplane mode)
4. Simulate 4 hours of user activity (accelerated):
   - 200 UI events (taps, scrolls, screen views)
   - 50 HTTP attempts (all fail → http.error events)
   - 10 freeze events (ui.freeze)
   - 5 battery change events
   - RAM buffer overflows → events move to disk
5. Verify: RAM buffer at capacity, disk buffer has overflow
6. Verify: zero exports attempted (or all failed gracefully)
7. Go ONLINE
8. ui.freeze event triggers policy → flushWindow(240) (last 4 hours)
9. ALL events from 4 hours export in batches of 100

Dash0 Validation:
  - Total event count matches: 200 + 50 + 10 + 5 = 265 events
  - Timestamps span 4 hours (original capture times, not export time)
  - Events are in chronological order
  - No gaps in the timeline
  - Disk buffer cleaned after successful export
  - Zero duplicate events
```

### 5.15 Showcase: Full User Journey with Breadcrumb Trail

> **Demo value**: A complete user journey from app launch to booking
> confirmation, with every interaction captured as a breadcrumb. When
> something goes wrong, the trail shows exactly what happened.

```
1. SDK init with all modules enabled + breadcrumbs
2. Complete booking journey:
   a. Cold launch → app.start (cold, duration_ms)
   b. Home screen → page.Home span, screen_view, wireframe
   c. Tap "Find Doctor" → ui.tap (element: btn_find)
   d. Search screen → page.Search span, screen_view, wireframe
   e. Type "cardiology" → ui.text_input (char_count: 10, is_set: true)
   f. Scroll results → ui.scroll (direction: down, distance: large) x3
   g. Tap Dr. Smith → ui.tap (element: result_item_3)
   h. Detail screen → page.Detail span, screen_view, wireframe
   i. HTTP GET /api/doctor/3 → network span (200, 340ms)
   j. Tap "Book Appointment" → ui.tap (element: btn_book)
   k. Booking form → page.BookingForm span, screen_view, wireframe
   l. Fill form fields → ui.text_input x4 (name, date, time, notes)
   m. Tap "Confirm" → ui.tap (element: btn_confirm)
   n. HTTP POST /api/appointments → network span (201, 890ms)
   o. Confirmation screen → page.Confirmation span, screen_view, wireframe
   p. Tap "Done" → ui.tap, navigate back to Home
3. Force flush (CONTINUOUS mode)

Dash0 Validation:
  - Journey span contains all page spans as children
  - Page spans contain UI interaction spans as children
  - Breadcrumb trail: launch → Home → tap → Search → type → scroll x3 →
    tap → Detail → API → tap → Booking → fill x4 → tap → API → Confirm → tap
  - 5 wireframes showing layout at each screen
  - 2 network spans (GET + POST) with distributed trace context
  - session.id consistent across all events
  - view.id changes at each screen transition (5 unique values)
  - Total: ~30 events forming a complete narrative
```

---

## 6. Dash0 End-to-End Telemetry Validation

> **Purpose**: Validate that telemetry exported by the SDK actually arrives in
> Dash0 with correct structure, attributes, and relationships. Unit tests verify
> local behavior; only Dash0 validation proves the full pipeline works.

### 6.0 Infrastructure and Authentication

**Prerequisites:**
- `DASH0_API_URL` — API endpoint (e.g., `https://api.us-west-2.aws.dash0.com`)
- `DASH0_AUTH_TOKEN` — API token with read access
- `DASH0_DATASET` — Dataset name (e.g., `otel-mobile-test`)
- `DASH0_INGRESS_ENDPOINT` — OTLP ingress (e.g., `https://ingress.us-west-2.aws.dash0.com:4317`)
- Dash0 CLI installed (experimental `-X` commands enabled)

**Query Tools:**

| Tool | Signal | Command Pattern |
|------|--------|-----------------|
| Dash0 CLI | Spans | `dash0 -X spans query --from now-{T} --filter "{attr} is {val}" --output json` |
| Dash0 CLI | Traces | `dash0 -X traces get {traceId}` |
| Dash0 CLI | Logs | `dash0 -X logs query --from now-{T} --filter "{attr} is {val}" --output json` |
| PromQL | Metrics | `dash0.spans.duration`, `dash0.logs`, `dash0.spans` synthetic metrics |

**Filter Operators:**
`is`, `is_not`, `contains`, `starts_with`, `matches` (regex), `gt`, `gte`, `lt`, `lte`, `is_set`, `is_not_set`, `is_one_of`

**Test Isolation Strategy:**
Each test run injects a unique `test.run_id` (UUID) global attribute via `SessionManager`.
All Dash0 queries filter by this attribute to isolate from other data:
```
dash0 -X spans query --from now-15m --filter "test.run_id is {RUN_ID}" --output json
```

**Timing:** Dash0 ingestion has 5-30s propagation delay. Tests use configurable
`DASH0_QUERY_DELAY_SEC` (default 30s) with retry (up to 3 attempts, 15s apart).

### 6.1 Span Validation

| # | Test Case | SDK Action | Dash0 Query and Assertion |
|---|-----------|-----------|--------------------------|
| DV-001 | Page span exists | Navigate to ScreenA | `spans query --filter "span.name starts_with page."` returns span with name `page.ScreenA` |
| DV-002 | Network span exists | HTTP GET /api/appointments | `spans query --filter "http.request.method is GET"` returns span with `url.full`, status_code |
| DV-003 | Parent-child hierarchy | Tap during page span | `traces get {traceId}` shows `ui.tap` nested under `page.ScreenA` |
| DV-004 | Journey span tree | Journey with 3 screens | `traces get {traceId}` shows journey -> page.A -> ui.tap, page.B -> ui.scroll, page.C |
| DV-005 | Span attributes complete | Any network span | `service.name`, `session.id`, `device.id`, `network.connection.type` all present |
| DV-006 | Distributed trace context | HTTP to demo backend | Backend span shares same `traceId` as mobile network span |
| DV-007 | Span duration accuracy | HTTP with ~500ms latency | `http.request.duration_ms` within +/-100ms |
| DV-008 | Error span status | HTTP 500 response | `otel.span.status.code is ERROR` |
| DV-009 | Sampling respected | 100 traces at rate 0.5 | ~50 traces visible (+/-15% tolerance) |
| DV-010 | Zero-duration UI spans | Tap event as span | Span exists with duration near 0ms |

### 6.2 Log and Event Validation

| # | Test Case | SDK Action | Dash0 Query and Assertion |
|---|-----------|-----------|--------------------------|
| DL-001 | ui.tap arrives | Tap a button | `logs query --filter "body contains ui.tap"` returns log with session.id, view.id, screen.name |
| DL-002 | ui.scroll arrives | Scroll RecyclerView | Log with scroll.direction, scroll.distance_bucket |
| DL-003 | ui.text_input arrives | Blur EditText | Log with char_count attribute |
| DL-004 | ui.back_press arrives | Press back | Log exists |
| DL-005 | ui.screen_view arrives | Navigate screens | Log with previous_screen, time_on_screen_ms |
| DL-006 | ui.freeze arrives | Block main thread 1s | Log with `mobile.freeze.duration_ms gte 700` |
| DL-007 | error event arrives | Uncaught exception | Log with error.type, error.message, stack trace |
| DL-008 | http.error arrives | HTTP 500 | Log with http.response.status_code = 500 |
| DL-009 | app.start arrives | Cold launch | Log with app.start.type = cold |
| DL-010 | app.foreground/background | BG then FG | Both events with correct timestamps |
| DL-011 | ui.wireframe arrives | Navigate (wireframe on) | Log with wireframe.data (valid JSON), wireframe.node_count > 0 |
| DL-012 | ui.screenshot arrives | Error (screenshot on) | Log with screenshot.data_url starts_with data:image/jpeg |
| DL-013 | recovery event | Kill + relaunch | Log with recovery.type = crash |
| DL-014 | prediction event | High-risk cycle | Log at DEBUG severity |
| DL-015 | device.heartbeat (HYBRID) | HYBRID mode running | Periodic heartbeat events present |
| DL-016 | Severity correctness | freeze=ERROR, tap=INFO | `otel.log.severity.range is_one_of ERROR` for freeze |
| DL-017 | Event ordering | 10 sequential events | All 10 present, timestamps chronological |

### 6.3 Metrics Validation

| # | Test Case | SDK Action | Dash0 Assertion |
|---|-----------|-----------|-----------------|
| DM-001 | Memory metrics | Vitals enabled | memory.heap_used_mb gauge present |
| DM-002 | Battery metrics | SDK running | battery.level gauge 0-100 |
| DM-003 | Jank metrics | UI jank generated | jank.frame_drop_count > 0 |
| DM-004 | App start metric | Cold launch | app.start.time recorded |
| DM-005 | Span-derived metrics | Multiple HTTP calls | PromQL `rate(dash0.spans.duration{service.name=...}[5m])` returns data |
| DM-006 | Log-derived metrics | Generate errors | PromQL `increase(dash0.logs{..., otel_log_severity_range="ERROR"}[5m])` > 0 |
| DM-007 | Metric attributes | Any metric | service.name, service.version, device.id labels present |

### 6.4 Correlation and Relationship Validation

| # | Test Case | SDK Action | Dash0 Assertion |
|---|-----------|-----------|-----------------|
| DC-001 | Session ID consistent | 20 events, one session | All share same session.id |
| DC-002 | Session ID changes | BG > 30min, FG | New session.id |
| DC-003 | Trace context cross-signal | HTTP call | Span and log share traceId |
| DC-004 | Distributed trace E2E | Mobile -> demo backend | `traces get` shows mobile + backend spans |
| DC-005 | View ID rotation | 3 screens | Distinct view.id per screen |
| DC-006 | Device ID persistence | Two runs, same emulator | Same device.id |
| DC-007 | Service identity | Any telemetry | service.name/version match config |
| DC-008 | Policy annotation | Policy match flush | Exported events have policy.matched=true, correct policy.id |

### 6.5 Export Mode Validation in Dash0

| # | Export Mode | Scenario | Dash0 Assertion |
|---|------------|---------|-----------------|
| DE-001 | CONDITIONAL | 50 events, only crash matches | Only flush-window events in Dash0 |
| DE-002 | CONDITIONAL | No policy match | Zero events in Dash0 for session |
| DE-003 | CONTINUOUS | 20 events, 2 timer cycles | All 20 present |
| DE-004 | CONTINUOUS | Check arrival timing | Batches align with export interval |
| DE-005 | HYBRID | No policy match | Only heartbeat events in Dash0 |
| DE-006 | HYBRID | Crash policy match | Heartbeats + full flush window |
| DE-007 | CONDITIONAL | 30 offline, reconnect, crash | All 30 arrive after reconnect |

### 6.6 Offline/Online Validation in Dash0

| # | Test Case | Dash0 Assertion |
|---|-----------|-----------------|
| DO-001 | Offline events exported on reconnect | Original timestamps preserved (not export time) |
| DO-002 | Crash during offline, recovery online | Pre-crash + recovery events all present |
| DO-003 | Extended offline (1h), reconnect | Non-TTL-expired events present |
| DO-004 | Flaky network, retries | No duplicate events (dedup by event ID) |
| DO-005 | WiFi->Cellular mid-session | All events present, network.connection.type reflects transition |

### 6.7 Privacy and Data Integrity Validation

| # | Test Case | Dash0 Assertion |
|---|-----------|-----------------|
| DP-001 | URL PII scrubbing | url.full has no emails, SSNs, cards |
| DP-002 | Screenshot text redaction | Redacted regions present |
| DP-003 | STRICT mode | No element labels, text content, or location |
| DP-004 | Auth tokens not leaked | No attribute contains DASH0_AUTH_TOKEN |
| DP-005 | Header scrubbing | No Authorization header values in span attributes |

### 6.8 Negative Validation (What Should NOT Be in Dash0)

| # | Test Case | Dash0 Assertion |
|---|-----------|-----------------|
| DN-001 | Rate-limited errors | Error count <= 10/min despite 20 generated |
| DN-002 | Deduplicated errors | 5 identical exceptions -> 1 event |
| DN-003 | Unsampled traces | At 50% rate, ~50% absent |
| DN-004 | TTL-expired events | Events > 24h TTL not present after extended offline |
| DN-005 | Buffer-only (CONDITIONAL, no match) | Non-matching events absent |
| DN-006 | Filtered hosts | Denylist host -> no span |
| DN-007 | Disabled modules | Screenshot off -> zero ui.screenshot |

### 6.9 Automated Dash0 Validation Harness

```kotlin
/**
 * Dash0ValidationHarness -- wraps Dash0 CLI for programmatic
 * telemetry validation in integration tests.
 *
 * Runs as JUnit Rule. After each test:
 * 1. Waits for propagation delay
 * 2. Queries Dash0 via CLI with test.run_id filter
 * 3. Parses JSON output
 * 4. Asserts expected telemetry
 */
class Dash0ValidationHarness(
    private val apiUrl: String = System.getenv("DASH0_API_URL"),
    private val authToken: String = System.getenv("DASH0_AUTH_TOKEN"),
    private val queryDelaySec: Int =
        System.getenv("DASH0_QUERY_DELAY_SEC")?.toIntOrNull() ?: 30,
    private val maxRetries: Int = 3,
    private val retryIntervalSec: Int = 15
) {

    fun querySpans(
        runId: String,
        lookback: String = "15m",
        vararg filters: String
    ): List<SpanResult> {
        val allFilters = listOf("test.run_id is $runId") + filters.toList()
        val args = mutableListOf(
            "dash0", "-X", "spans", "query",
            "--from", "now-$lookback", "--output", "json"
        )
        allFilters.forEach { args.addAll(listOf("--filter", it)) }
        return executeWithRetry { ProcessBuilder(args).start() }.parseSpans()
    }

    fun queryLogs(runId: String, lookback: String = "15m",
                  vararg filters: String): List<LogResult> { /* same pattern */ }

    fun getTrace(traceId: String): TraceResult {
        return ProcessBuilder("dash0", "-X", "traces", "get", traceId)
            .start().parseTrace()
    }

    // Assert helpers
    fun assertSpanExists(runId: String, spanName: String) { ... }
    fun assertLogExists(runId: String, bodyContains: String) { ... }
    fun assertEventCount(runId: String, bodyContains: String,
                         expected: Int, tolerance: Int = 0) { ... }
    fun assertAttributePresent(runId: String, attrKey: String) { ... }
    fun assertAttributeAbsent(runId: String, attrKey: String) { ... }
    fun assertTraceHierarchy(traceId: String, expected: List<String>) { ... }
    fun assertNoEventsExist(runId: String, bodyContains: String) { ... }
}
```

**Usage in Tests:**
```kotlin
@get:Rule val dash0 = Dash0ValidationHarness()

@Test fun tapEvent_ArrivesInDash0_WithCorrectAttributes() {
    val runId = UUID.randomUUID().toString()
    MobileOtel.setGlobalAttribute("test.run_id", runId)
    simulateTap(R.id.bookButton)
    MobileOtel.forceFlush()

    dash0.assertLogExists(runId, bodyContains = "ui.tap")
    val logs = dash0.queryLogs(runId, filters = arrayOf("body contains ui.tap"))
    assertThat(logs).hasSize(1)
    assertThat(logs[0].attributes["ui.element.id"]).isEqualTo("bookButton")
    assertThat(logs[0].attributes["session.id"]).isNotEmpty()
}
```

### 6.10 CI Integration

```
Nightly CI: Dash0 E2E Validation Pipeline (~15 min)

1. Start emulator (Pixel_7, API 36)
2. Set env: DASH0_API_URL, DASH0_AUTH_TOKEN, DASH0_DATASET=otel-mobile-ci
3. Build + install demo app with test config
4. Run:  ./gradlew :android:connectedAndroidTest \
          -Pandroid.testInstrumentationRunnerArguments.class=*.dash0validation.*
5. Each test: generate run_id -> SDK actions -> flush -> query Dash0 -> assert
6. Report: pass/fail + Dash0 deeplink URLs for debugging
```

---

## 7. Performance & Safety Tests

### 7.1 Host Application Safety Guarantees

These are the **most critical** tests. The SDK must NEVER degrade the host app.

| # | Test Case | Measurement | Pass Criteria |
|---|-----------|-------------|---------------|
| HS-001 | SDK init time | Time from init() to ready | < 50ms on main thread |
| HS-002 | Main thread time per event | Time in onEmit() on main thread | < 1ms per event |
| HS-003 | No ANR from SDK | Systrace during 10 min session | Zero main thread blocks > 100ms from SDK code |
| HS-004 | Memory footprint (idle) | Heap delta after SDK init | < 5MB |
| HS-005 | Memory footprint (active, 1K events buffered) | Heap delta | < 15MB |
| HS-006 | Memory footprint (max buffer) | Heap at 5000 RAM events | < 30MB |
| HS-007 | No OOM contribution | Force low-memory condition | SDK does not trigger OOM |
| HS-008 | CPU usage (idle) | CPU% over 60s idle period | < 1% |
| HS-009 | CPU usage (active, CONTINUOUS) | CPU% during active session | < 3% |
| HS-010 | Battery drain (8h background) | mAh delta vs no-SDK baseline | < 2% additional drain |
| HS-011 | Disk usage maximum | SQLite + WAL + SHM | < 55MB (50MB buffer + overhead) |
| HS-012 | Network bandwidth (CONTINUOUS, moderate) | Bytes/min | < 50KB/min average |
| HS-013 | App startup regression | Cold start time delta | < 100ms additional |
| HS-014 | No UI jank from SDK | Frame timing during SDK operations | Zero dropped frames from SDK |
| HS-015 | SDK exception isolation | Throw in every SDK public method | Never propagates to host |
| HS-016 | Thread safety audit | Run TSan/thread dump analysis | Zero data races |

### 7.2 Stress Tests

| # | Test Case | Conditions | Pass Criteria |
|---|-----------|-----------|---------------|
| ST-001 | Event flood: 10K events/sec for 60s | 600K total events | No OOM, no ANR, all buffered |
| ST-002 | Large attribute values | 4096-char values on every event | No OOM, truncation works |
| ST-003 | Deep view hierarchy (50 levels) | Tap hit-test | Completes within 5ms |
| ST-004 | 100 concurrent HTTP requests | Network instrumentation | All spans created, no cross-contamination |
| ST-005 | Rapid screen transitions (100/sec) | Screen + lifecycle | No leaked spans, correct ordering |
| ST-006 | Disk buffer at 50MB, continues receiving | Size enforcement | Eviction keeps size stable |
| ST-007 | 1000 unique regex patterns in policies | Policy evaluation | LRU cache works, no memory growth |
| ST-008 | Clock jump forward 24h | TTL cleanup | Events correctly expired |
| ST-009 | Clock jump backward 24h | Event ordering | No crash, timestamps may be unusual but no data loss |
| ST-010 | Rapid config changes (100 updates) | Config polling | AtomicReference swap, no stale state |

### 7.3 Failure Injection Tests

| # | Failure | Component Under Test | Expected Behavior |
|---|---------|---------------------|-------------------|
| FI-001 | Room DB file deleted | DiskLogBuffer | Recreated automatically, warning logged |
| FI-002 | Room DB corrupted (truncated) | DiskLogBuffer | Fallback to RAM-only, error logged |
| FI-003 | EncryptedSharedPreferences key deleted | SessionManager | New session created, warning logged |
| FI-004 | OTel SDK components null | MobileLoggerProvider | No crash, instrumentation disabled gracefully |
| FI-005 | Collector endpoint unreachable | RetryableExporter | Exponential backoff, events retained |
| FI-006 | Collector returns malformed response | Config polling | Previous config retained |
| FI-007 | DNS poisoning (wrong IP) | RetryableExporter | TLS verification fails, retries |
| FI-008 | OutOfMemoryError during export | RetryableExporter | Caught, events retained for next attempt |
| FI-009 | SecurityException on EncryptedSharedPrefs | SessionManager | Fallback to regular SharedPrefs |
| FI-010 | Activity null during capture | All instrumentation | No NullPointerException, capture skipped |
| FI-011 | WindowManager gone | Wireframe/Screenshot | No crash, capture skipped |
| FI-012 | Choreographer unavailable | Freeze/Vitals | Graceful disable, warning logged |
| FI-013 | ConnectivityManager null | Device metrics | network.type = "unknown" |
| FI-014 | BatteryManager null | Device metrics | battery.level = -1, battery.state = "unknown" |

---

## 8. Permutation Matrices

### 8.1 Master Permutation: Export Mode × Network × Telemetry Type

```
Export Modes: [CONDITIONAL, CONTINUOUS, HYBRID]
Network States: [Online, Offline, Flaky, WiFi→Cell, Cell→WiFi]
Telemetry Types: [Traces, Logs, Metrics, Mixed(All)]
```

**Total permutations: 3 × 5 × 4 = 60 test cases**

Each permutation tests:
1. Event generation
2. Buffering behavior
3. Export trigger (or non-trigger)
4. Export success/failure handling
5. Data integrity after export

### 8.2 Policy Condition Permutations

```
Attribute Operators: [equals, contains, gt, lt, gte, lte, regex]  → 7
Geo Dimensions: [country, region, timezone, locale, none]           → 5
Device Dimensions: [network, battery, os_version, app_version, deviceClass, buildChannel, none] → 7
Logical Operators: [AND, OR]                                        → 2
Nesting Levels: [flat, 2-deep, 3-deep]                             → 3
```

**Critical permutations (not full cartesian): ~150 focused combinations**

### 8.3 Privacy Mode × Module Matrix

```
Privacy Modes: [OFF, BASIC, STRICT]
Modules: [Tap, Scroll, TextInput, BackPress, Screen, Network, Errors,
          Vitals, Freeze, Wireframe, Screenshot]
```

**Total: 3 × 11 = 33 test cases** (covered in section 4.5)

### 8.4 Instrumentation Module Enable/Disable Matrix

```
All 16 modules × [enabled, disabled] = 2^16 = 65536 combinations (impractical)
```

Focus on:
- All enabled (default) — 1 test
- All disabled — 1 test
- Each module individually disabled (others enabled) — 16 tests
- Each module individually enabled (others disabled) — 16 tests
- Common presets — 4 tests (default, minimal, performance, privacy)

**Total: ~38 focused tests**

### 8.5 Buffer Configuration Permutations

| RAM Size | Disk Size | TTL | Strategy | Test Focus |
|----------|-----------|-----|----------|------------|
| 100 (min) | 0 (disabled) | 1h | RAM-only | Overflow drops events |
| 5000 (default) | 50MB (default) | 24h | Dual-tier | Standard flow |
| 10000 (large) | 100MB (large) | 48h | Large buffer | Memory pressure |
| 100 | 1MB | 1h | Constrained | Rapid eviction |
| 5000 | 50MB | 0h | No TTL | Events never expire |

---

## 9. Collector Processor Tests (Go)

### 9.1 Policy Evaluation (Server-Side)

| # | Test Case | Expected |
|---|-----------|----------|
| GO-001 | equals condition match | policy.matched=true annotated |
| GO-002 | contains condition match | policy.matched=true annotated |
| GO-003 | regex condition match | policy.matched=true annotated |
| GO-004 | gt/lt/gte/lte numeric conditions | Correct comparison |
| GO-005 | AND operator (all conditions) | All must match |
| GO-006 | OR operator (any condition) | Any can match |
| GO-007 | Disabled policy skipped | No evaluation |
| GO-008 | Multiple policies, first match wins | Correct policy.id annotated |
| GO-009 | No match | No annotation added |
| GO-010 | Empty policy list | Pass-through, no annotation |
| GO-011 | Invalid regex pattern | Graceful skip, error logged |
| GO-012 | Non-numeric value with numeric operator | Graceful skip |

### 9.2 Integration Scenarios (Server-Side)

| # | Test Case | Expected |
|---|-----------|----------|
| GO-013 | Crash recovery flush | All buffered events annotated |
| GO-014 | UI freeze > threshold | Matching events annotated |
| GO-015 | HTTP error 5xx | Server error policy matches |
| GO-016 | Mixed telemetry batch | Each event evaluated independently |
| GO-017 | Large batch (10K events) | Processed within 1s |
| GO-018 | Config hot reload | New policies active without restart |

---

## 10. Test Infrastructure Requirements

### 10.1 Test Dependencies

```kotlin
// Unit testing
testImplementation("junit:junit:4.13.2")
testImplementation("io.mockk:mockk:1.14.7")
testImplementation("org.robolectric:robolectric:4.16.1")
testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.58.0")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
testImplementation("androidx.test:core:1.6.1")

// Integration testing
androidTestImplementation("androidx.test.ext:junit:1.2.1")
androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

// Performance testing
androidTestImplementation("androidx.benchmark:benchmark-junit4:1.4.0")

// Coverage
apply(plugin = "jacoco")
```

### 10.2 Test Execution Strategy

```bash
# L1: Unit tests (fast, CI gating)
./gradlew :otel-android-mobile:test
./gradlew :otel-android-mobile-core:test
# All instrumentation module tests:
./gradlew test  # from examples/demo-app

# L2: Component integration tests (medium, CI)
./gradlew :otel-android-mobile:test --tests "*Integration*"

# L3: Cross-cutting tests (medium, CI)
./gradlew :otel-android-mobile:test --tests "*CrossCutting*" --tests "*Permutation*"

# L4: Performance tests (slow, nightly)
./gradlew :demo-app:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=*.benchmark.*

# L5: E2E tests (slow, nightly, requires emulator)
./gradlew :demo-app:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=*.e2e.*

# Go processor tests
cd collector-processor/mobilepolicyprocessor && go test -v -race -count=1 ./...

# L6: Dash0 E2E validation (nightly, requires emulator + Dash0 credentials)
DASH0_API_URL=$DASH0_API_URL DASH0_AUTH_TOKEN=$DASH0_AUTH_TOKEN \
./gradlew :android:connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=*.dash0validation.*

# Coverage report
./gradlew jacocoTestReport
```

### 10.3 CI Pipeline Stages

```
┌─────────────┐   ┌──────────────┐   ┌───────────────┐   ┌──────────────┐
│ PR Gate      │ → │ Merge Gate   │ → │ Nightly       │ → │ Release Gate │
│              │   │              │   │               │   │              │
│ L1 Unit      │   │ L1 + L2      │   │ L1-L4         │   │ L1-L5        │
│ Lint         │   │ Integration  │   │ + Performance  │   │ + E2E        │
│ Build        │   │ Go tests     │   │ + Stress       │   │ + Benchmark  │
│              │   │              │   │ + Emulator     │   │ + Dash0 E2E  │
│ ~2 min       │   │ ~5 min       │   │ + Dash0 E2E   │   │ + Manual QA  │
│              │   │              │   │ ~45 min       │   │ ~75 min      │
└─────────────┘   └──────────────┘   └───────────────┘   └──────────────┘
```

### 10.4 Coverage Tracking

| Module | Current Est. | Target | Priority |
|--------|-------------|--------|----------|
| MobileLogRecordProcessor | ~70% | 100% | P0 |
| PolicyEvaluator | ~80% | 100% | P0 |
| DiskLogBuffer | ~75% | 100% | P0 |
| RetryableExporter | ~60% | 100% | P0 |
| DynamicSampler | ~70% | 100% | P1 |
| SessionManager | ~65% | 100% | P1 |
| DeviceMetricsCollector | ~50% | 100% | P1 |
| PredictiveExportPolicy | ~40% | 100% | P1 |
| Each instrumentation module | ~60% | 100% | P1 |
| Privacy/PII | ~70% | 100% | P0 |
| Cross-cutting permutations | ~20% | 100% | P0 |
| Performance benchmarks | ~10% | Full suite | P1 |

### 10.5 Test Naming Convention

```
<Module>_<Scenario>_<Condition>_<Expected>

Examples:
Buffer_FlushWindow_WhenOffline_EventsRetainedOnDisk
Policy_RegexMatch_WithReDoSPattern_RejectedSafely
Export_Continuous_WhenNetworkFlaky_RetriesWithBackoff
Tap_HitTest_AtMaxDepth_StopsAndReturnsPartial
Session_Renewal_AfterInactivityTimeout_NewIdGenerated
```

---

## Appendix A: Test Count Summary

| Category | Test Cases |
|----------|-----------|
| Buffer System (B, D, R) | 51 |
| Policy Evaluation (P, G, DV, L, A, CP, DF) | 87 |
| Export Modes (EC, EN, EH) | 22 |
| Sampling (S) | 10 |
| Session Management (SM) | 15 |
| Device Metrics (DM) | 11 |
| Predictive Intelligence (PR) | 11 |
| Instrumentation Modules (TAP-SS) | 124 |
| Privacy/PII | 12 |
| Recovery Tracker (REC) | 6 |
| Log Tailing (LT) | 5 |
| Fleet Alerts (FA, FD, FM) | 28 |
| **Cross-Cutting: Offline/Online (OO)** | **23** |
| **Cross-Cutting: Telemetry × Mode (TT)** | **12** |
| **Cross-Cutting: Concurrency (CC)** | **10** |
| **Cross-Cutting: Lifecycle (AL)** | **10** |
| **Cross-Cutting: Privacy × Module (PM)** | **15** |
| Integration Journeys | 6 suites |
| **Showcase Demo Suites** | **9 suites** |
| Performance/Safety (HS) | 16 |
| Stress Tests (ST) | 10 |
| Failure Injection (FI) | 14 |
| Permutation Matrices | ~270 |
| Core Module: WindowEventHub (WH) | 12 |
| Core Module: Builder & Registry (OB, IR) | 11 |
| Core Module: RateLimiter (RL) | 6 |
| Core Module: Breadcrumbs (BC) | 11 |
| Core Module: WindowCallbackWrapper (WC) | 6 |
| Core Module: ContextSnapshot (CS) | 6 |
| Core Module: Export Classes (EX) | 12 |
| Core Module: Semconv (SC) | 4 |
| OTel Semantic Convention Conformance (SN, AK, SK, LS, RA) | 33 |
| Incubating: Database (DB) | 5 |
| Incubating: File I/O (FIO) | 4 |
| Incubating: System Events (SE) | 6 |
| Incubating: Timber (TIM) | 5 |
| Collector Processor (GO) | 18 |
| **Dash0 E2E: Span Validation (DV)** | **10** |
| **Dash0 E2E: Log Validation (DL)** | **17** |
| **Dash0 E2E: Metrics Validation (DM)** | **7** |
| **Dash0 E2E: Correlation (DC)** | **8** |
| **Dash0 E2E: Export Mode (DE)** | **7** |
| **Dash0 E2E: Offline/Online (DO)** | **5** |
| **Dash0 E2E: Privacy (DP)** | **5** |
| **Dash0 E2E: Negative (DN)** | **7** |
| **TOTAL** | **~975 unique test cases** |

---

## Appendix B: Implementation Priority

### Phase 1 (P0 — Must have before any release)
- All Buffer System tests (B, D, R)
- All Policy Evaluation tests (P, G, DV, L)
- All Offline/Online permutation tests (OO)
- Host Safety tests (HS)
- Privacy/PII tests
- Failure Injection tests (FI)
- Dash0 E2E: Core span + log validation (DV-001..005, DL-001..010)

### Phase 2 (P1 — Must have for GA)
- Export Mode permutation matrix
- Cross-cutting concurrency tests (CC)
- All instrumentation module tests at 100%
- Performance benchmarks (HS performance subset)
- Stress tests (ST)
- Dash0 E2E: Full suite (correlation, export mode, offline/online, negative)
- Dash0 CI pipeline integration (nightly)

### Phase 3 (P2 — Continuous improvement)
- Full permutation matrix coverage
- E2E integration journeys (device farm)
- Long-running stability tests (8h soak test)
- Multi-device compatibility matrix (API 26-36)
- Dash0 E2E: Multi-region validation, dataset access control verification
