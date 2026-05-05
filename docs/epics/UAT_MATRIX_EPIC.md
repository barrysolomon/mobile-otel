# Epic: UAT Matrix — Cross-platform Acceptance Across Export Modes

**Status:** In progress (Phase 0 closed 2026-05-05; Phase 1 sweep in flight)
**Priority:** P1
**Owner:** Barry Solomon
**Created:** 2026-05-01
**Spec:** [`docs/superpowers/specs/2026-05-01-uat-matrix-design.md`](../superpowers/specs/2026-05-01-uat-matrix-design.md)
**Plan:** [`docs/superpowers/plans/2026-05-01-uat-matrix.md`](../superpowers/plans/2026-05-01-uat-matrix.md)

## Summary

48-cell acceptance matrix (4 platforms × 12 cells) exercising export-mode × connectivity × crash combinations end-to-end with Dash0-confirmed evidence. Each cell has must-pass and soft-warn assertions captured to a per-run JSONL evidence file; `dash0 -X` query results are the source of truth, not local logs.

## Status grid

| Platform | C1 | C2 | C3 | C4 | C5 | C6 | C7 | C8 | C9 | C10 | C11 | C12 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Android native | 🟢 | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ |
| iOS native | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ |
| RN Android | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ |
| RN iOS | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ |

🟢 pass · 🔴 fail · ⚠️ infra · ➖ skip · ⏳ not yet exercised · 🟡 framework supports but cell skipped (e.g. iOS crash)

Phase 0 closed Android C1 only. Phase 1 (in flight) targets all 12 Android cells × 2 reproducibility runs. Phases 2/3 cover iOS native and RN.

## Phase status

| Phase | Scope | Status |
|---|---|---|
| 0 | Foundation: assertion lib + Android cell 1 green twice | ✅ Closed `42e34e2` |
| 1 | Generalize Android runner to all 12 cells, full sweep | 🚧 In flight |
| 2 | Nightly subset (cells 1-3) on iOS native + RN Android + RN iOS | ⏳ Pending |
| 3 | Epic doc + CI staircase | 🚧 (this doc) |

## Reproduce

```bash
# Full Android sweep (~25 min)
scripts/test/uat/run-uat-matrix.sh --platform=android-native --summary-md=/tmp/uat.md

# Nightly subset (cells 1-3) on all platforms once Phase 2 lands
scripts/test/uat/run-uat-matrix.sh --cells=1-3

# Single cell
scripts/test/uat/run-uat-cell.sh --platform=android-native --mode=cont --connectivity=online --crash=no
```

Per-platform evidence lives in [`docs/uat-matrix/`](../uat-matrix/).

## Open follow-on work

- iOS schemes for `Dash0Conditional` + `Dash0Hybrid` (Task 2.2 deferred)
- iOS `uat::trigger_crash` + `uat::offline` implementations (Task 2.3 deferred)
- RN-iOS crash + offline (Task 2.5 deferred)
- Span attributes parity: stamping `dash0.test.cell_id` on spans (currently only LogRecords carry it via `MobileLogRecordProcessor.onEmit`; Gate 2 network-span queries depend on it)
- CI staircase: nightly subset → weekly full sweep, once a successor to the removed GitHub Actions workflows lands

## Key decisions captured along the way

- **2026-05-05** — `extraResourceAttributes` semantics changed from Resource-level to per-record because Dash0 ingestion strips unknown Resource attribute namespaces. DSL surface name preserved for backwards compatibility. Spec amendment recorded inline in the design doc §3.
- **2026-05-05** — `dash0 -X` CLI requires repeated `--filter` flags for AND; `--filter "A and B"` silently matches zero. Runner's `run_logs_query` / `run_spans_query` are now variadic.
- **2026-05-05** — Lifecycle records gained `event.name=app.foreground` / `app.background` per OTel mobile semconv. Body-only emission left Dash0 filters unable to match.
