# SR-004: Bound persistedToDisk Set

**Severity:** CRITICAL
**Status:** Planned

## Problem

`persistedToDisk` is an `IdentityHashMap`-backed `MutableSet<BufferedEvent>` that tracks which RAM events have been crash-mirrored to disk. Entries are removed on export, but events that age out via TTL disk cleanup are never removed from the in-memory set. Over 24h+ sessions with high event throughput, this set grows without bound, holding strong references to `BufferedEvent` objects that can never be GC'd.

### Growth Rate

With 5000-event RAM buffer cycling every ~10 minutes in a busy app:
- 5000 events/10min = 500 events/min mirrored to disk
- 30,000 events/hour added to `persistedToDisk`
- Over 24h: 720,000 entries, each holding a `BufferedEvent` (~200 bytes) = ~144MB leaked

## Design

### Option A: Remove on TTL cleanup (chosen)

When `DiskLogBuffer.cleanup()` deletes expired events, return the count deleted. The processor then scans `persistedToDisk` to remove entries whose `monotonicMs` falls before the cleanup cutoff.

```kotlin
// After cleanup
val deletedCount = diskBuffer.cleanupExpired()
if (deletedCount > 0) {
    val cutoffMonoMs = SystemClock.elapsedRealtime() - (ttlHours * 3_600_000L)
    persistedToDisk.removeAll { it.monotonicMs < cutoffMonoMs }
}
```

### Option B: Periodic cross-reference (alternative)

Every N minutes, compute `persistedToDisk - ramBuffer` to find entries no longer in RAM, and remove them. Simpler but requires iterating both collections.

### Option C: WeakReference (rejected)

Using `WeakReference<BufferedEvent>` in the set would let GC reclaim them, but the set would accumulate stale `WeakReference` wrappers and `IdentityHashMap` doesn't support weak references natively.

## Decision

**Option A** — cleanup-driven removal. It's event-driven (only runs when TTL cleanup runs), has minimal overhead, and is easy to reason about.

## Files Changed

| File | Change |
|------|--------|
| `DiskLogBuffer.kt` | `cleanupExpired()` returns count of deleted rows |
| `MobileLogRecordProcessor.kt` | After cleanup, prune `persistedToDisk` by monotonic cutoff |

## Testing

- Test: buffer 1000 events, advance time past TTL, run cleanup, verify `persistedToDisk.size == 0`
- Test: 24h simulated session with continuous event cycling, verify memory stable
