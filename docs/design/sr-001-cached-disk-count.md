# SR-001: Cached Disk Event Count

**Severity:** CRITICAL
**Status:** Planned
**Blocks:** Fleet deployment

## Problem

`DiskLogBuffer.getEventCount()` calls `runBlocking { logDao.getCount() }` which executes `SELECT COUNT(*) FROM log_records` synchronously. This is called from:

1. **OTel async gauge callback** (MobileLogRecordProcessor:157) — may run on any thread including main
2. **`getBufferStats()` public API** (MobileLogRecordProcessor:837) — called from UI threads in demo/debug
3. **`emitHeartbeat()`** (MobileLogRecordProcessor:358) — runs on executor, less dangerous

On a 50MB database with 50K+ rows, `COUNT(*)` takes 5-50ms. On the main thread or metric reader thread, this causes ANRs.

## Design

Replace the live SQL count with a cached `AtomicInteger` maintained by increment/decrement on every insert and delete.

### Architecture

```
Insert path:  persistEvents() → DAO.insertAll(entities) → cachedCount.addAndGet(entities.size)
Delete path:  deleteOldest(n) → DAO.deleteOldest(n)     → cachedCount.addAndGet(-n)
              cleanup()       → DAO.deleteExpired()      → cachedCount.set(DAO.getCount()) // recalibrate
Query path:   getEventCount() → return cachedCount.get()  // O(1), no DB, no blocking
```

### Key Decisions

1. **Recalibrate on cleanup**: The hourly TTL cleanup may delete an unknown number of rows. After cleanup, do a single `COUNT(*)` to recalibrate. This runs on the cleanup coroutine (Dispatchers.IO), not a hot path.

2. **Initialize on first access**: On `getInstance()`, launch a coroutine to read the initial count from DB. Until it completes, `getEventCount()` returns `-1` (or the caller handles "not yet loaded").

3. **Overflow safety**: `addAndGet(-n)` could go negative if a race between delete and count occurs. Clamp to `max(0, newValue)`.

## Files Changed

| File | Change |
|------|--------|
| `DiskLogBuffer.kt` | Add `private val cachedCount = AtomicInteger(0)`, update all insert/delete paths, remove `runBlocking` from `getEventCount()` |
| `MobileLogRecordProcessor.kt` | No changes needed — `getEventCount()` API unchanged |

## Testing

- Unit test: insert 100 events, verify `getEventCount() == 100`, delete 50, verify `== 50`
- Unit test: concurrent insert from 10 threads, verify count matches actual DB count
- Unit test: `getEventCount()` never calls `runBlocking`
