# Epic: Network-Restored Flush — Wake the Exporter When Connectivity Returns

**Status:** SPEC
**Priority:** P1 (demo-blocker for offline storytelling, real customer gap for Innovapptive)
**Owner:** Barry Solomon
**Created:** 2026-05-12
**Target:** Before Innovapptive beta deployment

## Motivation

Today, when a device goes offline and comes back online, **nothing in the SDK notices**. The buffered events stay on disk until one of three things happens:

1. App restart → next launch sees non-empty disk → emits `app.recovery_start` → drains.
2. Some unrelated policy trigger fires (another error, lifecycle bg→fg, periodic tick in CONTINUOUS mode).
3. The host app calls `OTelMobile.forceFlush()` manually.

This is the gap Barry hit during a live demo on 2026-05-12:

> "I attempted to create a booking that failed while in airplane mode. When I toggled airplane mode off, I should have seen traces/logs but none were emitted."

That is the demo moment we *want* to land — and right now it silently doesn't.

### Why we got here

- `OnDevicePredictor` on both platforms already listens to network availability transitions — but only to record snapshots into a history deque for *risk prediction* (`networkLossRisk`). The `onAvailable` / `pathUpdateHandler` callbacks **do not** wake the exporter.
- `RetryableExporter` retries with exponential backoff up to `maxRetries=3` (~60s cap), then returns `fail()`. Events stay buffered, but no one re-attempts later.
- The Predictive policy fires preemptively on *network-loss risk*, not on *network-restored fact* — see [feedback_predictive_policy_offline_flush.md](../../../.claude/projects/-Users-barrysolomon-Projects-Dash0-mobile-observability/memory/feedback_predictive_policy_offline_flush.md).
- iOS has the symmetric gap: [feedback_ios_bg_flush_for_cond_hyb.md](../../../.claude/projects/-Users-barrysolomon-Projects-Dash0-mobile-observability/memory/feedback_ios_bg_flush_for_cond_hyb.md).

## Goals

1. **`NetworkRestoredTrigger`** — A first-class DSL trigger that fires on `LOST → AVAILABLE` transition.
2. **Default-on behavior** — In CONDITIONAL and HYBRID modes, network restoration drains the disk buffer automatically. No DSL required to get the demo moment.
3. **Zero drift** — Android, iOS, RN bridge, and collector DSL parser all in sync (per [feedback_no_platform_drift.md](../../../.claude/projects/-Users-barrysolomon-Projects-Dash0-mobile-observability/memory/feedback_no_platform_drift.md)).
4. **Honor offline budget** — When [Offline Flush Budget](OFFLINE_FLUSH_BUDGET_EPIC.md) lands, network-restored flush respects the budget cap.
5. **Demo-able** — Airplane mode off triggers visible Dash0 ingestion within ~3s.

## Non-Goals

- Replacing `RetryableExporter`. Retries still handle short blips (<60s). This epic handles the *long offline* case.
- Polling for connectivity. We use OS callbacks (`ConnectivityManager.NetworkCallback` / `NWPathMonitor`) which are already wired.
- New transport types or OTLP changes.

## Architecture

### Trigger transition semantics

A trigger fires only on the *transition* `LOST → AVAILABLE`, not on every `onAvailable` callback (which can fire on Wi-Fi handoff, validation, etc.). We track previous state and emit one event per genuine transition. We also debounce by 500ms to absorb spurious double-callbacks during airplane-mode toggle.

```
state: LOST ──onAvailable──► AVAILABLE
                                 │
                                 ▼
                       NetworkRestoredTrigger fires
                                 │
                                 ▼
              MobileLogRecordProcessor.flushWindow(N)
                                 │
                                 ▼
                  RetryableExporter → OTLP → ingested
```

### Wiring point: not the predictor

`OnDevicePredictor` is for *prediction* (history snapshots). We do not want the trigger semantics tangled with that. Instead, introduce a small dedicated component:

- **Android:** `NetworkAvailabilityWatcher` — owns the `ConnectivityManager.NetworkCallback`, exposes a `Flow<NetworkTransition>` (or simple listener interface for API consistency with the existing hub pattern).
- **iOS:** `NetworkAvailabilityWatcher` — owns `NWPathMonitor`, exposes a `Combine` publisher / closure-based listener.
- **RN:** No new code. The thin facade inherits behavior because all buffering and triggers live in native.
- **Collector processor:** Add `network_restored` as a recognized trigger type in the DSL schema for symmetry, even though the processor doesn't enforce it (it's a device-side trigger).

`MobileLogRecordProcessor` subscribes to the watcher and calls `flushWindow(minutes)` on transition. Window size is configurable; default = the full disk buffer window.

### DSL surface

Add to DSL v2 schema (per [dsl-schema-sync-check]):

```json
{
  "trigger": {
    "type": "network_restored",
    "debounceMs": 500,
    "flushWindowMinutes": 60
  }
}
```

When no policy mentions `network_restored`, the SDK still installs a default
internal trigger so the demo moment works out of the box. Users who want to
suppress it explicitly set `triggers.networkRestored.enabled=false` in
`MobileConfig`.

### Interaction with Offline Flush Budget

When [Offline Flush Budget](OFFLINE_FLUSH_BUDGET_EPIC.md) ships, the flush
respects the per-flush budget cap. Already-exported events (tracked by
`lastExportedSeqId` high-water mark) are skipped — no duplicate ingestion.

## Tasks

### Phase 1 — Android (TDD)

- **NF-001** Failing test: `NetworkAvailabilityWatcherTest` — `onAvailable` after `onLost` emits one `Restored` event; back-to-back `onAvailable` emits zero. (Robolectric, pin SDK 28 per [feedback_robolectric_sdk36_jdk21.md](../../../.claude/projects/-Users-barrysolomon-Projects-Dash0-mobile-observability/memory/feedback_robolectric_sdk36_jdk21.md))
- **NF-002** Implement `NetworkAvailabilityWatcher` in `otel-android-mobile/.../network/`.
- **NF-003** Failing test: `MobileLogRecordProcessorTest.flushOnNetworkRestored` — disk has 5 events, simulate transition, assert exporter sees all 5.
- **NF-004** Subscribe processor to watcher in `MobileLoggerProvider.init`.
- **NF-005** Failing test: `PolicyEvaluatorTest.networkRestoredTrigger` — DSL with `type: network_restored` causes evaluator to call `flushWindow` on transition.
- **NF-006** Wire trigger type into DSL v1 + v2 parsers ([dsl-schema-sync-check]).
- **NF-007** UAT cell: airplane mode on → buffered error → airplane mode off → assert error lands in Dash0 within 5s.

### Phase 2 — iOS (parity)

- **NF-008** Failing test: `NetworkAvailabilityWatcherTests` (Swift Testing). Use `_offlineOverride` seam per [feedback_ios_nwpathmonitor_test_seam.md](../../../.claude/projects/-Users-barrysolomon-Projects-Dash0-mobile-observability/memory/feedback_ios_nwpathmonitor_test_seam.md).
- **NF-009** Implement `NetworkAvailabilityWatcher.swift`.
- **NF-010** Failing test: processor flushes RAM+disk on transition. Note: must call `forceFlushBuffered()`, not `forceFlush()` ([feedback_ios_forceflush_two_methods.md](../../../.claude/projects/-Users-barrysolomon-Projects-Dash0-mobile-observability/memory/feedback_ios_forceflush_two_methods.md)).
- **NF-011** Subscribe processor in `OTelMobile.start()`.
- **NF-012** Mirror DSL parser changes.
- **NF-013** UAT cell parity.

### Phase 3 — RN bridge + collector DSL

- **NF-014** Add `network_restored` to collector processor DSL schema (`mobilepolicyprocessor`).
- **NF-015** Update `graphToDSLv2.ts` producer in control-plane-ui to surface the new trigger as a graph node.
- **NF-016** Jest test in RN package confirming the trigger flows through the bridge to native (no JS-side handling needed; this is a contract test).

### Phase 4 — Demo + docs

- **NF-017** Add airplane-mode demo step to `HOW_TO_DEMO.md` and the demo control center script.
- **NF-018** Update [BARRY_STUDY_GUIDE.md](../../BARRY_STUDY_GUIDE.md) Part 3 with the new demo moment.
- **NF-019** Record a 20-second demo reel ([ce-demo-reel]).

## Open Questions

1. **Should `RetryableExporter` itself subscribe to the watcher and replay its in-memory failed batch?** Probably yes — it's the most surgical fix for the <60s blip case. Punt to a follow-up if it grows the diff too much.
2. **Default `flushWindowMinutes`?** Suggest the same as the disk buffer TTL (24h). Open to tuning.
3. **What about Wi-Fi → cellular handoff?** Current spec only fires on LOST → AVAILABLE. Handoff (AVAILABLE → AVAILABLE with different transport) is a no-op. Confirm with PM whether that's correct.

## Acceptance Criteria

- [ ] All 4 platforms pass UAT cell: airplane on → error → airplane off → telemetry in Dash0 within 5s.
- [ ] Matrix tests on Android + iOS + RN-Android + RN-iOS green.
- [ ] DSL schema sync check passes ([dsl-schema-sync-check]).
- [ ] Platform parity check passes ([platform-parity-check]).
- [ ] Demo reel recorded and linked in PR.
- [ ] [BARRY_STUDY_GUIDE.md](../../BARRY_STUDY_GUIDE.md) updated.

## Related

- [OFFLINE_FLUSH_BUDGET_EPIC.md](OFFLINE_FLUSH_BUDGET_EPIC.md) — companion epic; this one wakes the exporter, that one bounds how much it sends.
- [feedback_predictive_policy_offline_flush.md](../../../.claude/projects/-Users-barrysolomon-Projects-Dash0-mobile-observability/memory/feedback_predictive_policy_offline_flush.md) — Predictive policy already triggers on *risk*, not *fact*.
- [feedback_ios_bg_flush_for_cond_hyb.md](../../../.claude/projects/-Users-barrysolomon-Projects-Dash0-mobile-observability/memory/feedback_ios_bg_flush_for_cond_hyb.md) — symmetric iOS gap.
- [feedback_no_platform_drift.md](../../../.claude/projects/-Users-barrysolomon-Projects-Dash0-mobile-observability/memory/feedback_no_platform_drift.md) — zero-drift rule.
