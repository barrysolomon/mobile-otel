# iOS crash-export parity epic — RESOLVED-BY-DISCOVERY 2026-05-13

**Status: closed.** Originally proposed to fix what looked like an iOS-only crash-export gap in CONT/COND modes (UAT 2026-05-13 cells 2/4/6 failed `crash_present=0`). Investigation showed there is NO SDK bug — the failures were state contamination from prior phases in the back-to-back matrix sweep.

## Original problem (now resolved)

iPhone 17 UAT cells 2, 4, 6 observed zero `app.crash` records in Dash0; HYBRID cells 10, 12 observed the expected record. Hypothesis: iOS COND/HYB lacks periodic flush, so the crash log doesn't export in-window for non-HYBRID modes.

## What the investigation actually found

1. **`IOSCrashRecoveryFlushTests.swift`** (3 cases, CONT/COND/HYBRID): all 3 PASS at the SDK unit level. The `MobileLogRecordProcessor.onEmit → policy → flushWindow` path is mode-agnostic and handles `app.crash` correctly for every export mode.
2. **Single-cell re-run** (`--cells=2`) after `wipe-device.sh ios "iPhone 17"`: PASS.
3. **Cells 1-6 re-run** (full CONT + COND block) after wipe: 6/6 PASS, including all three previously-failing crash cells.

Same simulator, same SDK build (architecture-hardening epic merged on `main`), same matrix script. The only difference: explicit pre-phase wipe.

## Root cause

The original sweep ran `android-native → rn-android → ios-native` in sequence. The iPhone 17 simulator carried iOS state across the boundaries — leftover crash markers, disk-buffered records carrying old cell_ids, possibly Schedulr / AstronomyShop process state. The early iOS-native cells observed those stale records instead of (or alongside) the cell's actual emissions.

## Resolution

`scripts/test/uat/wipe-device.sh ios "<sim>"` — added during the original sweep — is the fix. It needs to run BEFORE every iOS phase begins.

**Action items (lightweight, NOT a multi-track epic):**

1. Update `scripts/test/uat/run-uat-matrix.sh` so it auto-invokes `wipe-device.sh` for the target platform/device at run start. _(One commit.)_
2. Keep `IOSCrashRecoveryFlushTests.swift` as a permanent regression guard for the SDK-layer guarantee.
3. Memory entry `feedback_ios_cont_cond_crash_export_gap` → RESOLVED with this discovery.

## What we did NOT change

- No SDK code change. The `MobileLogRecordProcessor` flush path is correct.
- No new lifecycle-bg-flush hook (originally proposed Track B). Android has one for legitimate reasons (uncaught-exception path); iOS doesn't need a parallel hook to make UAT cells pass — they pass already when state is clean.

## Lesson for future investigations

When a UAT matrix surfaces failures that pattern-match a known SDK area, **first re-run the failing cell after a wipe.** If it passes, the bug isn't in the SDK; it's in the matrix's state-management. Memory `feedback_uat_lessons_learned` already documented this; the 2026-05-13 detour is the latest reminder.
