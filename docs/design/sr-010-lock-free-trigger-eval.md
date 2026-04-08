# SR-010: Lock-Free Trigger Evaluation

**Severity:** HIGH
**Status:** Planned

## Problem

`LogTailBuffer.evaluateTriggers()` (line 100) acquires a read lock then calls `evaluatePattern()`, which for `CustomPredicate` invokes arbitrary user code (`pattern.predicate(log)`). If the user's predicate calls `clear()` or `addLog()` (which need the write lock), deadlock occurs because `ReentrantReadWriteLock` cannot upgrade from read to write.

## Design

Snapshot the buffer contents before evaluating triggers. Evaluate against the snapshot without holding any lock.

```kotlin
fun addLog(log: LogRecordData) {
    val snapshot: List<LogRecordData>
    lock.write {
        buffer.addLast(log)
        while (buffer.size > maxSize) buffer.removeFirst()
        snapshot = buffer.toList()  // O(n) copy under write lock
    }
    // No lock held — user predicates can safely call addLog/clear
    evaluateTriggers(snapshot)
}

private fun evaluateTriggers(snapshot: List<LogRecordData>) {
    // No lock acquisition here
    for (trigger in triggers) {
        if (evaluatePattern(trigger.pattern, snapshot)) {
            trigger.action(snapshot)
        }
    }
}
```

### Trade-off

The snapshot copy is O(n) under the write lock. With `maxSize=100` (typical), this is ~100 reference copies = negligible. For `maxSize=10000`, consider a persistent data structure or ring buffer with immutable snapshots.

## Files Changed

| File | Change |
|------|--------|
| `LogTailBuffer.kt` | Snapshot inside write lock, evaluate outside any lock |

## Testing

- Test: `CustomPredicate` that calls `addLog()` inside the predicate — must not deadlock
- Test: `CustomPredicate` that calls `clear()` inside the predicate — must not deadlock
