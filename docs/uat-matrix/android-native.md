# UAT Matrix — Android Native

**Service name:** `otel-android-astronomy-shop`
**Demo app:** `examples/upstream-demo-app/` (flavors: `dash0Continuous`, `dash0Conditional`, `dash0Hybrid`)
**Last full sweep:** *pending Task 1.5 first run*

## Status

| Cell | Mode | Conn | Crash | Result | Last verified |
|---|---|---|---|---|---|
| 1 | CONT | online | no | 🟢 | 2026-05-05 (Phase 0 close) |
| 2 | CONT | online | yes | ⏳ | pending sweep |
| 3 | CONT | offline | no | ⏳ | pending sweep |
| 4 | CONT | offline | yes | ⏳ | pending sweep |
| 5 | COND | online | no | ⏳ | pending sweep |
| 6 | COND | online | yes | ⏳ | pending sweep |
| 7 | COND | offline | no | ⏳ | pending sweep |
| 8 | COND | offline | yes | ⏳ | pending sweep |
| 9 | HYB | online | no | ⏳ | pending sweep |
| 10 | HYB | online | yes | ⏳ | pending sweep |
| 11 | HYB | offline | no | ⏳ | pending sweep |
| 12 | HYB | offline | yes | ⏳ | pending sweep |

Status legend: 🟢 pass, 🔴 fail, ⚠️ infra, ➖ skip, ⏳ pending.

## Reproduce

```bash
scripts/test/uat/run-uat-matrix.sh --platform=android-native --summary-md=/tmp/uat.md
```

Expected runtime: ~25 minutes for a full 12-cell pass.

## Latest evidence summary

*Will be filled in by the first Task 1.5 sweep. The dated summary file in this directory is the immutable record; this section is a quick-look pointer.*
