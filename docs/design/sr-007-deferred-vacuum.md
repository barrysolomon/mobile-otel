# SR-007: Deferred VACUUM

**Severity:** HIGH
**Status:** Planned
**Also covers:** SR-015 (filesystem size enforcement)

## Problem

`enforceSizeLimit()` (DiskLogBuffer:299) is called from `persistEvents()` — the hot insert path. When the DB exceeds `maxSizeMb`, it runs `VACUUM` which holds an exclusive SQLite lock for seconds to minutes, blocking all concurrent reads and writes.

Additionally, the size check uses `dbFile.length()` which reflects physical file size, not logical data size. After `DELETE` but before `VACUUM`, the file size doesn't shrink, causing over-deletion.

## Design

### 1. Replace filesystem size check with row-count based enforcement

Use the cached event count (from SR-001) combined with estimated bytes-per-row:

```kotlin
private fun shouldEnforceLimit(): Boolean {
    val estimatedSizeMb = cachedCount.get() * AVG_BYTES_PER_ROW / (1024 * 1024)
    return estimatedSizeMb > maxSizeMb
}
```

Where `AVG_BYTES_PER_ROW ≈ 1024` (measured from typical log record serialization).

### 2. Move VACUUM to periodic cleanup

```kotlin
private val vacuumJob = scope.launch {
    while (isActive) {
        delay(6 * 3_600_000L)  // every 6 hours
        try {
            database.openHelper.writableDatabase.execSQL("VACUUM")
        } catch (e: Exception) {
            Log.w(TAG, "VACUUM failed, will retry next cycle", e)
        }
    }
}
```

### 3. Size enforcement just deletes rows

```kotlin
suspend fun enforceSizeLimit() {
    if (!shouldEnforceLimit()) return
    val excess = cachedCount.get() - maxRowCount
    if (excess > 0) {
        logDao.deleteOldest(excess)
        cachedCount.addAndGet(-excess)
    }
}
```

No `VACUUM` on the insert path. The physical file may be larger than `maxSizeMb` between VACUUM cycles, but logical data is bounded.

## Files Changed

| File | Change |
|------|--------|
| `DiskLogBuffer.kt` | Rewrite `enforceSizeLimit()` to use row count, move `VACUUM` to periodic job |

## Testing

- Test: insert 10K events rapidly while `enforceSizeLimit` runs — no blocking
- Test: after 1000 deletes, VACUUM periodic job reclaims space
