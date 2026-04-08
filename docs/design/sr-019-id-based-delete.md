# SR-019: ID-Based Delete in flushWindow

**Severity:** MEDIUM
**Status:** Planned

## Problem

`flushWindow()` queries disk events with `getEventsInWindowDualClock(monoStartMs, wallStartMs, currentBootId)`, then after export completes, calls `deleteEventsInWindow(wallWindowStart)` which deletes by timestamp range. Between query and delete, the crash-mirror task may have written new events with timestamps in the same range. Those events are deleted without being exported — silent data loss.

## Design

Collect row IDs at query time, delete by ID list after export.

### Changes

1. **`getEventsInWindowDualClock()` returns entities with IDs**:

```kotlin
@Query("""
    SELECT * FROM log_records 
    WHERE (bootId = :currentBootId AND monotonicMs >= :monoStartMs)
       OR ((bootId IS NULL OR bootId != :currentBootId) AND timestampMs >= :wallStartMs)
""")
suspend fun getEventsInWindowDualClock(monoStartMs: Long, wallStartMs: Long, currentBootId: String): List<LogRecordEntity>
```

The `LogRecordEntity.id` is the primary key (auto-generated).

2. **New `deleteByIds()` DAO method**:

```kotlin
@Query("DELETE FROM log_records WHERE id IN (:ids)")
suspend fun deleteByIds(ids: List<Long>)
```

3. **flushWindow() collects IDs**:

```kotlin
val entities = diskBuffer.getEventsInWindowDualClock(monoStartMs, wallStartMs, bootId)
val idsToDelete = entities.map { it.id }
val logRecords = entities.map { it.toLogRecordData() }

// Export logRecords...

// After successful export, delete by exact IDs
diskBuffer.deleteByIds(idsToDelete)
```

### SQLite Limit

SQLite's `IN` clause has a default limit of 999 parameters. For large flush windows, batch the IDs:

```kotlin
suspend fun deleteByIds(ids: List<Long>) {
    ids.chunked(900).forEach { chunk ->
        logDao.deleteByIds(chunk)
    }
}
```

## Files Changed

| File | Change |
|------|--------|
| `DiskLogBuffer.kt` | Add `deleteByIds()` DAO method, expose from `DiskLogBuffer` |
| `MobileLogRecordProcessor.kt` | Collect IDs in `flushWindow()`, call `deleteByIds()` after export |
