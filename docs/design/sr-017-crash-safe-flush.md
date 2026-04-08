# SR-017: Crash-Safe Flush

**Severity:** MEDIUM
**Status:** Planned

## Problem

`ErrorInstrumentation.onFlush` calls `processor.forceFlush()` synchronously on the crash handler thread. `forceFlush()` calls `runBlocking { diskBuffer.getAllEvents() }` then attempts gRPC export with a 30s timeout. After a crash, the OS gives ~5-10s before termination. The gRPC export will almost certainly be killed mid-flight, and the `runBlocking` blocks the dying main thread, risking a secondary SIGKILL.

## Design

On the crash path, only persist RAM events to disk (fast, <100ms). Skip the gRPC export entirely. The crash-recovery path on next launch already handles exporting disk-buffered events.

### Changes

```kotlin
// ErrorInstrumentation.kt
private val onFlush: (() -> Unit)? = {
    // Crash path: persist to disk only, skip export
    processor.persistRamToDiskForCrashSafety()
    // Do NOT call forceFlush() — gRPC export is too slow for crash window
}
```

### Why This Is Safe

The crash-mirror task already persists RAM events to disk every 2 seconds. On a crash:
- Events from the last 0-2 seconds might not be mirrored yet
- `persistRamToDiskForCrashSafety()` catches those final events
- On next launch, `checkAndRecoverFromCrash()` exports everything from disk

### Performance

`persistRamToDiskForCrashSafety()` with 5000 RAM events: ~50-100ms (Room batch insert). Well within the OS crash window.

## Files Changed

| File | Change |
|------|--------|
| `ErrorInstrumentation.kt` | Change `onFlush` to call `persistRamToDiskForCrashSafety()` instead of `forceFlush()` |
| `MobileLogRecordProcessor.kt` | Ensure `persistRamToDiskForCrashSafety()` is public and non-blocking |
