# UAT Matrix — Android Native

**Service name:** `otel-android-astronomy-shop`
**Demo app:** `examples/upstream-demo-app/` (flavors: `dash0Continuous`, `dash0Conditional`, `dash0Hybrid`)
**Last full non-crash sweep:** 2026-05-05 (6/6 🟢)

## Status

| Cell | Mode | Conn | Crash | Result | Last verified |
|---|---|---|---|---|---|
| 1 | CONT | online | no | 🟢 | 2026-05-05 (sweep) |
| 2 | CONT | online | yes | ⏳ | crash subset pending |
| 3 | CONT | offline | no | 🟢 | 2026-05-05 (sweep) |
| 4 | CONT | offline | yes | ⏳ | crash subset pending |
| 5 | COND | online | no | 🟢 | 2026-05-05 (sweep) |
| 6 | COND | online | yes | ⏳ | crash subset pending |
| 7 | COND | offline | no | 🟢 | 2026-05-05 (sweep) |
| 8 | COND | offline | yes | ⏳ | crash subset pending |
| 9 | HYB | online | no | 🟢 | 2026-05-05 (sweep) |
| 10 | HYB | online | yes | ⏳ | crash subset pending |
| 11 | HYB | offline | no | 🟢 | 2026-05-05 (sweep) |
| 12 | HYB | offline | yes | ⏳ | crash subset pending |

Status legend: 🟢 pass, 🔴 fail, ⚠️ infra, ➖ skip, ⏳ pending.

## Reproduce

```bash
scripts/test/uat/run-uat-matrix.sh --platform=android-native --summary-md=/tmp/uat.md
```

Expected runtime: ~25 minutes for a full 12-cell pass.

## Latest evidence summary

*Will be filled in by the first Task 1.5 sweep. The dated summary file in this directory is the immutable record; this section is a quick-look pointer.*
