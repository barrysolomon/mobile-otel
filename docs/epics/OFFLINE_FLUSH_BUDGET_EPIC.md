# Epic: Offline Flush Budget — Smart Offline Telemetry Management

**Status:** IN PROGRESS — Phase 1 (Android TDD)
**Priority:** P1
**Owner:** Barry Solomon
**Created:** 2026-05-08
**Target:** Before Innovapptive beta deployment

## Motivation

When devices go offline, the SDK currently buffers everything until disk fills
up (50MB default). There is no concept of a "budget" — no deduplication across
flush cycles, no configurable cap for offline scenarios, and no error coalescing.
The PredictiveExportPolicy even fires preemptive flushes (`networkLossRisk →
flushWindow(2)`) on predicted network loss, which contradicts any "zero export
when offline" intent.

Innovapptive (active prospect) runs industrial mobile apps in areas with
intermittent connectivity. They need offline-aware telemetry that doesn't
drain battery or fill disk with redundant data.

## Goals

1. **Flush dedup** — Prevent re-export of already-exported events across flush cycles
2. **Offline budget cap** — Configurable max disk bytes during offline, with oldest-first eviction
3. **Error coalescing** — Group identical errors within a time window, emit count attribute
4. **`offline_error_only` policy mode** — Only export error-severity events when offline
5. **PredictiveExportPolicy gating** — Suppress `networkLossRisk` flush when in offline-only mode
6. **Zero drift** — Every Android change mirrored to iOS

## Non-Goals

- Changing the existing dual-tier buffer architecture (RAM + Disk)
- Modifying OTLP wire format
- Server-side deduplication (that's the collector processor's job)

## Architecture

### Flush Dedup (Phase 1)

Current state: `seqId` on `BufferedEvent` prevents double-export of
crash-safety mirrors (RAM events copied to disk). But across flush cycles,
the same event can be flushed again if it's still in the buffer window.

Solution: Track a high-water mark (`lastExportedSeqId`) per exporter.
On flush, only export events with `seqId > lastExportedSeqId`. Persist
the high-water mark to disk so it survives process death.

```
BufferedEvent(seqId=100) → flush → export → lastExportedSeqId=100
BufferedEvent(seqId=101) → flush → export → lastExportedSeqId=101
BufferedEvent(seqId=100) → flush → SKIP (seqId <= lastExportedSeqId)
```

### Offline Budget Cap (Phase 2)

New `OfflineBudgetConfig`:
- `maxOfflineDiskBytes: Long` (default 10MB — 20% of total 50MB disk budget)
- `evictionStrategy: OLDEST_FIRST | LOWEST_SEVERITY_FIRST` (default OLDEST_FIRST)

When offline, `DiskLogBuffer.persistEvents()` checks total disk usage
against the offline budget. If over budget, evict per strategy before
persisting new events.

### Error Coalescing (Phase 3)

New `ErrorCoalescer` component:
- Groups errors by `(exception.type, exception.message)` key
- Within a configurable window (default 60s), emits only the first
  occurrence plus a `coalesced.count` attribute on export
- Resets count after successful export or window expiry

### Offline Error-Only Mode (Phase 4)

New `ExportMode.OFFLINE_ERROR_ONLY`:
- When device is offline: only buffer events with severity >= ERROR
- When device comes back online: resume normal policy evaluation
- Transition detection: reuse existing `ConnectivityManager` listener

### PredictiveExportPolicy Gating (Phase 5)

When `ExportMode.OFFLINE_ERROR_ONLY` is active, suppress
`PredictiveExportPolicy` flush triggers. The prediction is moot
if we're already in offline-aware mode.

## Phases

| # | Phase | Android | iOS | Tests |
|---|-------|---------|-----|-------|
| 1 | Flush dedup (seqId high-water mark) | `MobileLogRecordProcessor` | `MobileLogRecordProcessor` | Unit + integration |
| 2 | Offline budget cap | `DiskLogBuffer` + new `OfflineBudgetConfig` | `DiskLogBuffer` + config | Unit + integration |
| 3 | Error coalescing | New `ErrorCoalescer` | New `ErrorCoalescer` | Unit |
| 4 | Offline error-only mode | `MobileLogRecordProcessor` + `ExportMode` | Same | Unit + integration |
| 5 | PredictiveExportPolicy gating | `PredictiveExportPolicy` | `PredictiveExportPolicy` | Unit |
| 6 | DSL v2 schema additions | Control plane compiler | — | Compiler tests |
| 7 | Cross-platform validation | E2E | E2E | UAT matrix cells |

## DSL v2 Additions

```json
{
  "buffer_config": {
    "offline_budget_bytes": 10485760,
    "offline_eviction_strategy": "oldest_first",
    "error_coalesce_window_seconds": 60
  },
  "workflows": [{
    "offline_mode": "error_only"
  }]
}
```

## Risk Register

| Risk | Impact | Mitigation |
|------|--------|------------|
| seqId rollover after ~9.2×10¹⁸ events | Theoretical only | AtomicLong; not a practical concern |
| Offline budget eviction drops important events | Medium | LOWEST_SEVERITY_FIRST strategy option |
| Error coalescing hides unique stack traces | Low | Coalesce on type+message only, keep first full stacktrace |
| PredictiveExportPolicy suppression delays data | Medium | Only in OFFLINE_ERROR_ONLY mode; normal modes unaffected |
