# UAT Matrix — Per-platform Evidence

This directory holds per-platform 12-cell UAT evidence. For the design and methodology, see [`docs/superpowers/specs/2026-05-01-uat-matrix-design.md`](../superpowers/specs/2026-05-01-uat-matrix-design.md). For the cross-platform status grid, see [`docs/epics/UAT_MATRIX_EPIC.md`](../epics/UAT_MATRIX_EPIC.md).

## Cell numbering

| # | Mode | Conn | Crash |
|---|---|---|---|
| 1 | CONT | online | no |
| 2 | CONT | online | yes |
| 3 | CONT | offline | no |
| 4 | CONT | offline | yes |
| 5 | COND | online | no (expected nothing) |
| 6 | COND | online | yes |
| 7 | COND | offline | no (disk probe) |
| 8 | COND | offline | yes |
| 9 | HYB | online | no |
| 10 | HYB | online | yes |
| 11 | HYB | offline | no |
| 12 | HYB | offline | yes |

## Running

```bash
# Full sweep on one platform
scripts/test/uat/run-uat-matrix.sh --platform=android-native --summary-md=/tmp/run.md

# Just nightly subset (cells 1-3) on all platforms
scripts/test/uat/run-uat-matrix.sh --cells=1-3

# Single cell (Phase 0 smoke test)
scripts/test/uat/run-uat-cell.sh --platform=android-native --mode=cont --connectivity=online --crash=no
```

## Per-platform evidence

- [`android-native.md`](android-native.md)
- `ios-native.md` *(Phase 2)*
- `rn-android.md` *(Phase 2)*
- `rn-ios.md` *(Phase 2)*

## Sweep archive

Dated sweep summaries from `run-uat-matrix.sh --summary-md=...` are kept here as `<platform>-sweep-YYYYMMDD-HHMM-runN.md`. They are immutable evidence; per-platform `.md` files are living dashboards summarising the latest sweep.
