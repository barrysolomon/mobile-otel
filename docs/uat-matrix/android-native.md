# UAT Matrix — Android Native

**Service name:** `otel-android-astronomy-shop`
**Demo app:** `examples/upstream-demo-app/` (flavors: `dash0Continuous`, `dash0Conditional`, `dash0Hybrid`)
**Last full sweep:** 2026-05-05 (7/12 🟢)

## Status

| Cell | Mode | Conn | Crash | Result | Last verified |
|---|---|---|---|---|---|
| 1 | CONT | online | no | 🟢 | 2026-05-05 (sweep) |
| 2 | CONT | online | yes | 🟢 | 2026-05-05 (sweep) |
| 3 | CONT | offline | no | 🟢 | 2026-05-05 (sweep) |
| 4 | CONT | offline | yes | 🔴 | 2026-05-05 (recovery_present=0) |
| 5 | COND | online | no | 🟢 | 2026-05-05 (sweep) |
| 6 | COND | online | yes | 🔴 | 2026-05-05 (recovery_present=0) |
| 7 | COND | offline | no | 🟢 | 2026-05-05 (sweep) |
| 8 | COND | offline | yes | 🔴 | 2026-05-05 (recovery_present=0) |
| 9 | HYB | online | no | 🟢 | 2026-05-05 (sweep) |
| 10 | HYB | online | yes | 🔴 | 2026-05-05 (recovery_present=0) |
| 11 | HYB | offline | no | 🟢 | 2026-05-05 (sweep) |
| 12 | HYB | offline | yes | 🔴 | 2026-05-05 (recovery_present=0) |

**Open issue (cells 4, 6, 8, 10, 12):** The SDK emits `app.recovery_start` on relaunch (verified in logcat: `Emitted app.recovery_start marker with event_count=N`), but the export of that record is racing other concurrent flushes. Only the first recovery cell of a sweep (cell 2) reliably reaches Dash0 with the marker; subsequent recovery cells emit on-device but the marker doesn't get queryable by `event.name is app.recovery_start AND dash0.test.cell_id is <recovery_id>`. Suspected causes: (a) `MobileLogRecordProcessor` re-emits on the recovery launch but the recovery record is in a concurrent batch with disk-replayed events that get dedup'd away, (b) the recovery record's `dash0.test.cell_id` is stamped via `extraResourceAttributes` on the processor — but the recovery_start emit happens BEFORE the processor's onEmit (it goes through the SDK's logger directly via `sdkLoggerProvider.get(...).logRecordBuilder()`). Investigation continues.

Status legend: 🟢 pass, 🔴 fail, ⚠️ infra, ➖ skip, ⏳ pending.

## Reproduce

```bash
scripts/test/uat/run-uat-matrix.sh --platform=android-native --summary-md=/tmp/uat.md
```

Expected runtime: ~25 minutes for a full 12-cell pass.

## Latest evidence summary

*Will be filled in by the first Task 1.5 sweep. The dated summary file in this directory is the immutable record; this section is a quick-look pointer.*
