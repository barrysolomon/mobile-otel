# Implementation Plan: Monotonic-Safe Flush Window

> **Design Doc**: [MONOTONIC_FLUSH_WINDOW.md](MONOTONIC_FLUSH_WINDOW.md)
> **Estimated effort**: 4-6 hours
> **Risk**: Low — internal-only change, no export format change, backward-compatible migration

---

## Step 1: Add `BootTracker` singleton

**File**: New file `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/core/BootTracker.kt`

```kotlin
internal object BootTracker {
    /** True per-boot ID from Linux kernel. Survives process restarts within same boot. */
    val currentBootId: String by lazy {
        try {
            java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()
        } catch (e: Exception) {
            UUID.randomUUID().toString() // fallback: per-process UUID
        }
    }
}
```

**Tests**:
- Verify `currentBootId` is stable within a process (multiple reads return same value)
- Verify `currentBootId` is stable across object re-access (lazy init)
- Verify fallback UUID is generated when `/proc/sys/kernel/random/boot_id` unreadable

**Risk**: None. Isolated new file.

---

## Step 2: Add `BufferedEvent` wrapper

**File**: New file `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/BufferedEvent.kt`

```kotlin
internal data class BufferedEvent(
    val logRecord: LogRecordData,
    val monotonicMs: Long = SystemClock.elapsedRealtime()
)
```

**Tests**: Verify `monotonicMs` is populated on construction.

**Risk**: None. Isolated new file.

---

## Step 3: Room schema migration — add `monotonicMs` and `bootId` to `LogRecordEntity`

**File**: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/DiskLogBuffer.kt`

Changes:
1. Add `monotonicMs: Long = 0` and `bootId: String? = null` to `LogRecordEntity`
2. Add `Index("monotonicMs")` to entity indices
3. Bump database version
4. Add `MIGRATION_N_TO_N1` object
5. Update `toEntity()` to set `monotonicMs` and `bootId` from `BufferedEvent`
6. Add new DAO query: `getEventsInWindowDualClock(monoStartMs, wallStartMs, currentBootId)`
7. Add new DAO method: `deleteByIds(ids: List<Long>)` for clock-safe deletion
8. Set `exportSchema = true` in `@Database` annotation; configure `room.schemaLocation`
   in `build.gradle.kts` to enable Room migration test infrastructure
9. Keep `.fallbackToDestructiveMigration()` alongside `.addMigrations(MIGRATION_N_TO_N1)`

**New DAO methods**:

```kotlin
@Query("""
    SELECT * FROM log_records
    WHERE (bootId = :currentBootId AND monotonicMs >= :monoStartMs)
       OR ((bootId IS NULL OR bootId != :currentBootId) AND timestampMs >= :wallStartMs)
    ORDER BY timestampMs ASC
""")
suspend fun getEventsInWindowDualClock(
    monoStartMs: Long,
    wallStartMs: Long,
    currentBootId: String
): List<LogRecordEntity>

@Query("DELETE FROM log_records WHERE id IN (:ids)")
suspend fun deleteByIds(ids: List<Long>): Int
```

**Why `deleteByIds` instead of `deleteEventsInWindow`**: The SELECT uses dual-clock
logic. If the DELETE used a separate WHERE clause, clock skew could cause the DELETE
to miss events that were exported (forward jump) or delete events that were NOT
exported (backward jump). Deleting by the exact IDs returned from SELECT eliminates
this inconsistency.

**Tests**:

- Migration test: old DB upgrades without data loss (requires `exportSchema = true`)
- Same-boot events filtered by monotonicMs
- Cross-boot events (bootId=null) filtered by timestampMs
- Mixed boot events return correct combined results
- Pre-migration events (monotonicMs=0, bootId=null) use wall-clock fallback
- `deleteByIds` removes exactly the selected events, no more, no fewer
- SQL precedence: verify `bootId IS NULL` alone does NOT match (parentheses correct)

**Risk**: Medium. Schema migration is the riskiest part. Test with real Room
migration test infrastructure.

---

## Step 4: Change RAM buffer type in `MobileLogRecordProcessor`

**File**: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/MobileLogRecordProcessor.kt`

Changes:
1. `ramBuffer: ConcurrentLinkedQueue<LogRecordData>` → `ConcurrentLinkedQueue<BufferedEvent>`
2. `onEmit()`: wrap `logRecordData` in `BufferedEvent(logRecordData)`
3. `persistRamToDiskForCrashSafety()`: pass `BufferedEvent` to disk persistence
4. `overflowToDisk()`: pass `BufferedEvent` to disk persistence
5. `forceFlush()`: unwrap `.logRecord` when building export list
6. All `IdentityHashMap` usage: switch from `LogRecordData` to `BufferedEvent` identity
7. `persistedToDisk` set: change type to `Collections.newSetFromMap(IdentityHashMap<BufferedEvent, Boolean>())`
8. `currentScreenStartMs` → `currentScreenStartMonoMs`: set to `SystemClock.elapsedRealtime()` on screen.view
9. `lastFlushStartMs` / `lastFlushEndMs` → `lastFlushStartMonoMs` / `lastFlushEndMonoMs`

**Tests**:
- Existing buffer tests must still pass (unwrap `.logRecord`)
- New test: B-020 — clock jumps backward, flush window still correct
- New test: B-021 — clock jumps forward, flush window still correct
- New test: B-022 — monotonic timestamps strictly increasing
- Concurrent access tests with `BufferedEvent`

**Risk**: Medium. Touches many methods in the core processor. Each callsite must
correctly wrap/unwrap.

---

## Step 5: Rewrite `flushWindow()` to use monotonic time

**File**: `MobileLogRecordProcessor.kt`

Changes (line-by-line):

1. **Line 457**: `val now = System.currentTimeMillis()` → `val monoNow = SystemClock.elapsedRealtime()`
2. **Line 458-459**: `lastEnd`/`lastStart` → read from `lastFlushEndMonoMs`/`lastFlushStartMonoMs`
3. **Line 463**: Cooldown check: `(now - lastEnd) < flushCooldownMs` → `(monoNow - lastEndMono) < flushCooldownMs`
4. **Line 466-468**: Overlap check: `proposedStart < lastEnd && now > lastStart` → use mono values for both
5. **Line 472**: `requestedWindowStartMs = now - (...)` → `monoWindowStart = monoNow - (...)`
6. **Lines 474-481**: Screen-start extension:
   - `maxExtensionMs = now - (30 * 60 * 1000L)` → `maxExtensionMonoMs = monoNow - (...)`
   - `screenStartMs = currentScreenStartMs.get()` → `currentScreenStartMonoMs.get()`
   - Both inputs to `maxOf()` must be monotonic
7. **Line 491**: `logRecord.effectiveTimestampMs() >= windowStartMs` → `event.monotonicMs >= monoWindowStart`
8. **Line 496**: Disk query → call `getEventsInWindowDualClock(monoWindowStart, wallWindowStart, BootTracker.currentBootId)` with `wallNow = System.currentTimeMillis()` computed here
9. **Line 518**: `lastFlushStartMs.set(windowStartMs)` → `lastFlushStartMonoMs.set(monoWindowStart)`
10. **Line 519**: `lastFlushEndMs.set(System.currentTimeMillis())` → `lastFlushEndMonoMs.set(SystemClock.elapsedRealtime())`
11. **Line 540**: `diskBuffer.deleteEventsInWindow(windowStartMs)` → `diskBuffer.deleteByIds(diskEntityIds)` where `diskEntityIds` are the IDs from the SELECT result
12. `flushByTraceId()` fallback to `flushWindow()` is automatically covered

**Critical consistency rule**: Every variable in a comparison expression must use
the same clock source. Never compare a monotonic value against a wall-clock value.

**Tests**:

- B-020 (clock backward): mock `System.currentTimeMillis()` to jump back 1h;
  `ShadowSystemClock.setCurrentTimeMillis()` stays normal. Verify correct 2-min
  window via monotonic. Verify exactly the right events are exported.
- B-021 (clock forward): mock wall-clock forward 1h. Verify flush still exports
  correct 2-min window (the critical data-loss fix).
- B-022 (normal operation): no clock skew. Identical behavior to before (regression).
- B-023 (cross-boot disk recovery): insert disk events with different bootId.
  Verify wall-clock fallback selects them correctly.
- B-024 (pre-migration disk events): insert events with monotonicMs=0, bootId=null.
  Verify wall-clock fallback works.
- B-025 (delete consistency): after flush under clock skew, verify exactly the
  exported events are deleted from disk (no extras, no misses).
- B-026 (cooldown under clock skew): verify cooldown suppression still works when
  wall-clock jumps between flushes.
- B-027 (flushByTraceId fallback): trace-based flush falls back to `flushWindow()`,
  verify monotonic protection applies.
- B-028 (negative monoWindowStart): `flushWindow(1440)` on fresh boot (uptime 3 min).
  `monoWindowStart` goes negative — verify all same-boot events are included.
- Robolectric note: verify `ShadowSystemClock` supports independent control of
  `elapsedRealtime()` in the project's Robolectric 4.16.1.

**Risk**: High. This is the core behavioral change. Must have thorough testing.

---

## Step 6: Update `DiskLogBuffer.persistEvents()` to accept monotonic data

**File**: `DiskLogBuffer.kt`

Changes:
1. `persistEvents()` signature change: accept `List<BufferedEvent>` or add
   overload that takes `monotonicMs` and `bootId`
2. `toEntity()` updated to receive and store `monotonicMs` + `bootId`

**Tests**:
- Events persisted with correct monotonicMs and bootId
- Events with monotonicMs=0 (fallback) query correctly
- Bulk insert performance unchanged

**Risk**: Low. Straightforward data passing.

---

## Step 7: Update `ExportModeTest` and all integration tests

**File**: Multiple test files

Changes:
1. Any test that directly creates `LogRecordData` and adds to the processor must
   now account for `BufferedEvent` wrapping
2. Tests that mock `System.currentTimeMillis()` for window verification should
   add `SystemClock.elapsedRealtime()` mocking (Robolectric supports this via
   `ShadowSystemClock`)
3. Add clock-skew test helpers

**Tests**: All existing export mode tests pass with no behavioral change.

**Risk**: Medium. Many test files may need updates.

---

## Step 8: Update test plan B-020/B-021

**File**: `TEST_PLAN.md`

Changes:
- B-020: Update from "known limitation" to "fixed — uses monotonic timestamps"
- B-021: Update from "limitation" to "fixed"
- Add B-022 (monotonic ordering), B-023 (cross-boot), B-024 (pre-migration)

**Risk**: None.

---

## Execution Order and Dependencies

```
Step 1 (BootTracker)  ──┐
Step 2 (BufferedEvent) ──┼── No dependencies, can be parallel
                         │
Step 3 (Room migration) ─┤── Depends on Step 1 (uses BootTracker.currentBootId)
                         │
Step 6 (persist)  ───────┤── Depends on Steps 2, 3 (accepts BufferedEvent, writes new columns)
                         │
Step 4 (RAM buffer)  ────┤── Depends on Steps 2, 6 (wraps in BufferedEvent, passes to persist)
                         │
Step 5 (flushWindow)  ───┤── Depends on Steps 1, 2, 3, 4, 6 (the core rewrite)
                         │
Step 7 (test updates) ───┤── Depends on Steps 4, 5
                         │
Step 8 (test plan) ──────┘── Depends on Step 5
```

**Recommended implementation order**: 1 → 2 → 3 → 6 → 4 → 5 → 7 → 8

Note: Step 4 depends on Step 6 because `persistRamToDiskForCrashSafety()` and
`overflowToDisk()` (changed in Step 4) pass `BufferedEvent` data to
`persistEvents()` (changed in Step 6). Step 6 must be done first so the
persist API accepts the new data.

---

## Rollback Plan

If issues discovered after merge:
1. The Room migration is additive (new nullable columns) — cannot be rolled back
   without a new migration that drops the columns
2. The `BufferedEvent` wrapper is internal — revert to `LogRecordData` by
   removing the wrapper and ignoring the new columns
3. `flushWindow()` can revert to `System.currentTimeMillis()` by changing one line

Low-risk rollback because:
- New columns with defaults don't affect existing queries
- Export format unchanged
- No external API changes

---

## Verification Checklist

- [ ] All existing unit tests pass (zero regressions)
- [ ] Room migration test passes (schema N → N+1, data preserved)
- [ ] B-020: Backward clock jump — correct 2-min flush window
- [ ] B-021: Forward clock jump — correct 2-min flush window
- [ ] B-022: Normal operation — identical to previous behavior
- [ ] B-023: Cross-boot crash recovery — disk events exported via wall-clock fallback
- [ ] B-024: Pre-migration upgrade — old disk events exported correctly
- [ ] B-025: Delete consistency — exported events deleted exactly, no extras/misses under skew
- [ ] B-026: Cooldown suppression still works under clock skew
- [ ] B-027: flushByTraceId fallback uses monotonic protection
- [ ] B-028: Negative monoWindowStart (large window, fresh boot) includes all events
- [ ] SQL precedence: bootId IS NULL alone does NOT match all pre-migration events
- [ ] BootTracker reads /proc/sys/kernel/random/boot_id correctly
- [ ] BootTracker fallback UUID works when boot_id unreadable
- [ ] Integration test — full SDK lifecycle with clock manipulation
- [ ] Performance — no measurable regression in onEmit latency or memory
- [ ] Dash0 E2E — events arrive with correct wall-clock timestamps (monotonic not leaked)