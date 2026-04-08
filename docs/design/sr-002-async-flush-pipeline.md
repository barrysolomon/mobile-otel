# SR-002: Async Flush Pipeline

**Severity:** CRITICAL
**Status:** Planned
**Blocks:** Fleet deployment

## Problem

`flushWindow()` and `forceFlush()` use `runBlocking { diskBuffer.getEventsInWindowDualClock(...) }` inside the 2-thread `ScheduledExecutorService`. Both executor threads can simultaneously block on Room coroutines via `Dispatchers.IO`. If `Dispatchers.IO` is saturated, the executor pool is fully blocked — no new policy evaluations, no overflow-to-disk, no heartbeats.

## Design

Convert the flush pipeline from blocking executor threads to a dedicated coroutine scope.

### Architecture

```
Current:  executor.submit { runBlocking { diskQuery() } }  // blocks executor thread
Proposed: flushScope.launch { diskQuery() }                 // suspends, frees thread
```

### Key Changes

1. **Create `flushScope`**: A `CoroutineScope(SupervisorJob() + Dispatchers.IO)` owned by the processor. Cancelled on `shutdown()`.

2. **`flushWindow()` becomes `suspend fun`**: All callers already submit to executor — change them to `flushScope.launch { flushWindow(minutes) }`.

3. **`evaluatePolicies()` launches, doesn't block**: Currently `evaluatePolicies()` calls `flushWindow()` synchronously. Change to `flushScope.launch { flushWindow(n) }` and track the Job for status reporting.

4. **`forceFlush()` returns `CompletableFuture`**: It already returns `CompletableResultCode`. Internally, launch a coroutine and complete the result when the coroutine finishes.

5. **`flushInProgress` flag**: Replace `AtomicBoolean` with a `Mutex` from `kotlinx.coroutines.sync` to coordinate concurrent flush requests.

### Risk: Error Semantics

Today, `forceFlush()` callers get synchronous result. With async, the `CompletableResultCode` is completed later. This matches the OTel spec for `forceFlush()` which explicitly returns an async result.

## Files Changed

| File | Change |
|------|--------|
| `MobileLogRecordProcessor.kt` | Add `flushScope`, convert `flushWindow`/`forceFlush` to coroutines, replace `runBlocking` with `suspend` |
| `DiskLogBuffer.kt` | All query functions are already `suspend` — no changes needed |

## Testing

- Existing `BufferSystemComprehensiveTest` tests flush behavior — should pass unchanged
- New test: 100 concurrent `flushWindow()` calls don't deadlock
- New test: `forceFlush()` completes even when `Dispatchers.IO` has 64 active coroutines
