# Design: Monotonic-Safe Flush Window

> **Status**: Proposed
> **Author**: Barry Solomon
> **Date**: 2026-04-07
> **Affects**: `MobileLogRecordProcessor`, `DiskLogBuffer`, `LogRecordEntity`

---

## Problem Statement

`flushWindow(minutes)` calculates the time window using `System.currentTimeMillis()`,
which is subject to wall-clock adjustments (NTP sync, user changing device time,
carrier time updates). OTel event timestamps are also wall-clock-based
(`timestampEpochNanos`).

When the device clock shifts, the flush window breaks:

| Clock Change | Effect on `flushWindow(2)` | Severity |
| ------------- | --------------------------- | ---------- |
| Backward jump (1h) | `now - 2min` is 1h before events. Entire buffer matches the window — exports everything instead of 2 minutes | Medium: wastes bandwidth but no data loss |
| Forward jump (1h) | `now - 2min` is 58min after events. Zero events match the window — **silent data loss** for that flush | **Critical**: policy-triggered flush appears to succeed but exports nothing |
| NTP sync (<1s) | Negligible — well within any practical window | None |
| DST / timezone change | No effect (epoch milliseconds unchanged) | None |

The forward-jump scenario is the critical bug. A crash policy triggers
`flushWindow(5)`, the flush "succeeds" with 0 events, and 5 minutes of
pre-crash telemetry is silently lost.

## Design

### Approach: Dual-Clock Buffering

Store a monotonic timestamp (`SystemClock.elapsedRealtime()`) alongside each
buffered event. Use monotonic time for window calculations within the current
boot. Fall back to wall-clock for cross-boot disk recovery.

```
Event arrives → onEmit()
  ├── Store in RAM as BufferedEvent(logRecord, monotonicMs)
  ├── Mirror to disk with both timestampMs (wall) and monotonicMs
  └── Export uses logRecord.timestampEpochNanos (wall-clock, unchanged)

flushWindow(N) →
  ├── RAM: filter by monotonicMs (always current boot, always correct)
  ├── Disk: filter by monotonicMs if same boot, else wall-clock fallback
  └── Export: sends original OTel timestamps (no modification)
```

**Key property**: The monotonic timestamp is never exported. It is purely an
internal ordering mechanism for window calculations. Exported telemetry retains
the original OTel wall-clock timestamps.

### Why `SystemClock.elapsedRealtime()`?

| Clock Source | Survives Sleep? | Survives User Change? | Survives Reboot? |
| ------------- | ---------------- | ---------------------- | ----------------- |
| `System.currentTimeMillis()` | Yes | **No** | Yes (epoch) |
| `SystemClock.uptimeMillis()` | **No** (pauses in deep sleep) | Yes | No |
| `SystemClock.elapsedRealtime()` | **Yes** | **Yes** | **No** |

`elapsedRealtime()` is the right choice because:

- It counts real elapsed time including deep sleep (unlike `uptimeMillis`)
- It is immune to user clock changes (unlike `currentTimeMillis`)
- The only limitation (reset on reboot) is handled by boot detection

### Changes

#### 1. New `BufferedEvent` wrapper

```kotlin
internal data class BufferedEvent(
    val logRecord: LogRecordData,
    val monotonicMs: Long = SystemClock.elapsedRealtime()
)
```

Replaces raw `LogRecordData` in the RAM buffer. Minimal overhead: one Long per event (8 bytes).

#### 2. RAM buffer type change

```kotlin
// Before:
private val ramBuffer = ConcurrentLinkedQueue<LogRecordData>()

// After:
private val ramBuffer = ConcurrentLinkedQueue<BufferedEvent>()
```

All code that reads from `ramBuffer` must unwrap `.logRecord`. All code that
writes must wrap in `BufferedEvent(logRecord)`.

#### 3. `LogRecordEntity` schema migration

```kotlin
@Entity(tableName = "log_records",
        indices = [Index("timestampMs"), Index("traceId"), Index("monotonicMs")])
data class LogRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val monotonicMs: Long = 0,        // NEW: 0 = unknown (pre-migration or cross-boot)
    val bootId: String? = null,       // NEW: identifies which boot this event belongs to
    // ... existing fields unchanged
)
```

- `monotonicMs`: Monotonic timestamp from `SystemClock.elapsedRealtime()`
- `bootId`: Random UUID generated once per process start, stored with each event
- Room auto-migration adds the columns with default values (0 and null)

#### 4. Boot detection

```kotlin
internal object BootTracker {
    /**
     * True per-boot identifier read from the Linux kernel. Survives process
     * restarts within the same boot, resets only on device reboot — which is
     * exactly when elapsedRealtime() also resets.
     *
     * Preferred over a per-process UUID because it allows monotonic timestamps
     * from a previous process (same boot) to remain valid for window calculations.
     */
    val currentBootId: String by lazy {
        try {
            java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()
        } catch (e: Exception) {
            // Fallback: per-process UUID (safe but loses cross-process monotonic accuracy)
            UUID.randomUUID().toString()
        }
    }
}
```

Uses the Linux kernel's `boot_id` which is a UUID generated once per device boot.
This is strictly more accurate than a per-process UUID because `elapsedRealtime()`
is continuous within a boot, even across process restarts. If `boot_id` is
unreadable (security policy), falls back to per-process UUID.

#### 5. `flushWindow()` changes

```kotlin
fun flushWindow(windowMinutes: Int): CompletableResultCode {
    // ...existing validation...

    val monoNow = SystemClock.elapsedRealtime()
    val monoWindowStart = monoNow - (windowMinutes * 60 * 1000L)

    // RAM: always use monotonic (all RAM events are from this boot)
    val ramEventsToFlush = mutableListOf<BufferedEvent>()
    ramBuffer.forEach { event ->
        if (event.monotonicMs >= monoWindowStart) {
            ramEventsToFlush.add(event)
        }
    }

    // Disk: use monotonic for same-boot events, wall-clock for cross-boot
    val wallNow = System.currentTimeMillis()
    val wallWindowStart = wallNow - (windowMinutes * 60 * 1000L)
    val diskEventsToFlush = runBlocking {
        diskBuffer.getEventsInWindow(
            monoStartMs = monoWindowStart,
            wallStartMs = wallWindowStart,
            currentBootId = BootTracker.currentBootId
        )
    }

    val allEventsToFlush = ramEventsToFlush.map { it.logRecord } + diskEventsToFlush
    // ...rest of export logic unchanged...
}
```

#### 6. Disk query changes

```sql
-- Same-boot events: filter by monotonicMs (immune to clock changes)
-- Cross-boot events: filter by timestampMs (wall-clock, best we have)
-- NOTE: explicit parentheses required — SQL AND binds tighter than OR
SELECT * FROM log_records
WHERE (bootId = :currentBootId AND monotonicMs >= :monoStartMs)
   OR ((bootId IS NULL OR bootId != :currentBootId) AND timestampMs >= :wallStartMs)
ORDER BY timestampMs ASC
```

Cross-boot events (crash recovery) use wall-clock as fallback. This is acceptable
because:

- They are from a previous process execution (app crashed/was killed)
- The clock was presumably correct when they were written
- The window is typically large for crash recovery (5+ minutes)
- Even if the clock shifted between boots, the events should be included

#### 6b. Delete by IDs (not by window)

After a successful flush, the current code calls `deleteEventsInWindow(windowStartMs)`.
This must NOT use a separate WHERE clause because a clock-skewed DELETE could miss
or over-delete events vs. what the SELECT returned.

**Fix**: Delete by the specific row IDs returned from the SELECT query:

```kotlin
// DAO:
@Query("DELETE FROM log_records WHERE id IN (:ids)")
suspend fun deleteByIds(ids: List<Long>): Int

// In flushWindow(), after successful export:
val diskIds = diskEntities.map { it.id }
runBlocking { diskBuffer.deleteByIds(diskIds) }
```

This guarantees the DELETE matches exactly what was exported — no clock-consistency
risk between read and delete.

#### 6c. `flushByTraceId()` coverage

`flushByTraceId()` falls back to `flushWindow()` when trace-matched events are
sparse. The monotonic fix in `flushWindow()` automatically covers this fallback
path. The non-fallback path in `flushByTraceId` (iterating by trace ID) does not
depend on wall-clock windowing, so it needs no changes.

#### 7. `currentScreenStartMs` change

The screen-start extension logic also needs monotonic time. Both the storage
AND the comparison in `flushWindow()` must use the same clock:

```kotlin
// Before:
private val currentScreenStartMs = AtomicLong(0)
// onEmit:
currentScreenStartMs.set(logRecordData.effectiveTimestampMs())    // wall-clock
// flushWindow:
val maxExtensionMs = now - (30 * 60 * 1000L)                     // wall-clock
val screenStartMs = currentScreenStartMs.get()                    // wall-clock
val windowStartMs = maxOf(screenStartMs, maxExtensionMs)          // wall-clock

// After:
private val currentScreenStartMonoMs = AtomicLong(0)
// onEmit:
currentScreenStartMonoMs.set(SystemClock.elapsedRealtime())       // monotonic
// flushWindow:
val maxExtensionMonoMs = monoNow - (30 * 60 * 1000L)             // monotonic
val screenStartMonoMs = currentScreenStartMonoMs.get()            // monotonic
val windowStartMonoMs = maxOf(screenStartMonoMs, maxExtensionMonoMs) // monotonic — consistent
```

**Critical**: All values in the `maxOf()` comparison MUST use the same clock
source. Mixing monotonic and wall-clock here would produce nonsensical results.

#### 8. Cooldown/dedup tracking

The `lastFlushStartMs` and `lastFlushEndMs` used for cooldown should also be
monotonic. The cooldown check at line 463 (`now - lastEnd < flushCooldownMs`)
must be rewritten to use monotonic throughout:

```kotlin
// Before:
private val lastFlushStartMs = AtomicLong(0)
private val lastFlushEndMs = AtomicLong(0)
// Check: val now = System.currentTimeMillis()
//        if (lastEnd > 0 && (now - lastEnd) < flushCooldownMs) ...

// After:
private val lastFlushStartMonoMs = AtomicLong(0)
private val lastFlushEndMonoMs = AtomicLong(0)
// Check: val monoNow = SystemClock.elapsedRealtime()
//        if (lastEndMono > 0 && (monoNow - lastEndMono) < flushCooldownMs) ...
// Set:   lastFlushStartMonoMs.set(monoWindowStart)
//        lastFlushEndMonoMs.set(SystemClock.elapsedRealtime())
```

**Critical**: The `proposedStart < lastEnd` comparison in the cooldown overlap
check (line 466-468) must also use monotonic values. Both `proposedStart` and
`lastEnd` must come from the same clock.

### What Does NOT Change

- **Event timestamps in LogRecordData**: `timestampEpochNanos` and
  `observedTimestampEpochNanos` remain wall-clock. They are OTel standard.
- **Exported telemetry**: The OTLP payload uses original wall-clock timestamps.
  Backends (Dash0, Jaeger, etc.) see normal timestamps.
- **forceFlush()**: Exports everything regardless of timestamp. No change needed.
- **CONTINUOUS mode periodic export**: Calls `forceFlush()`. No change needed.
- **TTL cleanup**: Uses wall-clock (`deleteOlderThan`). Acceptable because TTL
  is 24h — a <1h clock shift is noise at that scale.
- **DiskLogBuffer.getEventsAfter()**: Still available for non-window queries.

### Edge Cases

| Scenario | Behavior |
| ---------- | ---------- |
| Clock jumps backward 1h during active session | RAM events use monotonic — correct 2-min window exported |
| Clock jumps forward 1h during active session | RAM events use monotonic — correct 2-min window exported |
| Device reboots, disk events from previous boot | bootId mismatch → wall-clock fallback for disk. RAM is empty (fresh boot) |
| NTP adjusts clock by 500ms | Monotonic unaffected. Wall-clock shift within noise margin |
| Device in airplane mode for 48h (no NTP) | Monotonic still correct. Wall-clock may drift but isn't used for RAM |
| Very long session (device on for months) | `elapsedRealtime()` returns a `Long` — wraps at ~292 million years (Long.MAX_VALUE ms). Not a practical concern. |
| Pre-migration disk events (monotonicMs=0, bootId=null) | Treated as cross-boot → wall-clock fallback (correct) |
| Large window on fresh boot (e.g., `flushWindow(1440)` 3 min after boot) | `monoWindowStart` goes negative (`180000 - 86400000 < 0`). All same-boot RAM events have `monotonicMs >= 0 >= monoWindowStart`, so they are all included. Correct behavior — "flush everything from this boot" is the intended semantic when the window exceeds uptime |

### Performance Impact

| Change | Cost |
| -------- | ------ |
| `BufferedEvent` wrapper | +8 bytes per RAM event (Long field). At 5000 events: +40KB. Negligible. |
| `SystemClock.elapsedRealtime()` call per event | ~10ns per call. At 100 events/sec: 1μs/sec. Negligible. |
| Extra disk column (`monotonicMs` + `bootId`) | ~20 bytes per row. At 50MB limit: ~0.1% overhead. |
| Compound disk query (OR clause) | Index on `monotonicMs` + `bootId` ensures no full scan. |

### Room Migration

```kotlin
val MIGRATION_N_TO_N1 = object : Migration(N, N+1) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE log_records ADD COLUMN monotonicMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE log_records ADD COLUMN bootId TEXT DEFAULT NULL")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_log_records_monotonicMs ON log_records(monotonicMs)")
    }
}
```

Existing events get `monotonicMs=0` and `bootId=null`, which triggers the
wall-clock fallback path. Seamless upgrade.

**Important**: The current `DiskLogBuffer` uses `.fallbackToDestructiveMigration()`.
This must be KEPT as a safety net alongside the explicit migration:

```kotlin
Room.databaseBuilder(...)
    .addMigrations(MIGRATION_N_TO_N1)
    .fallbackToDestructiveMigration()  // keep as safety net
    .build()
```

If the migration fails (corrupted DB, schema mismatch), Room will recreate the
database rather than crashing the app. For a telemetry buffer, data loss is
preferable to an app-startup crash.

**Schema export**: Set `exportSchema = true` in the `@Database` annotation and
configure `room.schemaLocation` in `build.gradle.kts` to enable Room migration
testing with auto-generated schema JSON files.
</content>
</invoke>
