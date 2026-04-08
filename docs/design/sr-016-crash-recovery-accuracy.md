# SR-016: Crash Recovery Accuracy

**Severity:** MEDIUM
**Status:** Planned

## Problem

`AppLifecycleDetector.markCleanShutdown()` is called every time the app goes to background (`onActivityStopped`). If the app is OOM-killed from background (the most common kill path), `KEY_CLEAN_SHUTDOWN = true` is already recorded, and the crash-recovery flush on next launch is skipped.

## Design

Only mark clean shutdown in the explicit `OTelMobile.stop()` path.

### Changes

1. **Remove `markCleanShutdown()` from `logAppBackground()`** — backgrounding is not a shutdown
2. **Add `markCleanShutdown()` to `OTelMobile.stop()`** — the only place where the app explicitly signals "I'm done"
3. **Heuristic for background OOM**: On next launch, if `KEY_CLEAN_SHUTDOWN == false` AND `KEY_LAST_SESSION_END` was < 30 seconds before last known process time, treat as background OOM → trigger crash-recovery flush

### State Machine

```
App start     → KEY_CLEAN_SHUTDOWN = false
App stop()    → KEY_CLEAN_SHUTDOWN = true, KEY_LAST_SESSION_END = now
App background → KEY_LAST_BACKGROUND_TIME = now  (no clean shutdown mark)
OOM kill      → (nothing written — process dies)
Next launch   → if !KEY_CLEAN_SHUTDOWN: triggerCrashRecoveryFlush()
```

## Files Changed

| File | Change |
|------|--------|
| `AppLifecycleDetector.kt` | Remove `markCleanShutdown()` from `logAppBackground()`, add `KEY_LAST_BACKGROUND_TIME` |
| `OTelMobile.kt` | Call `markCleanShutdown()` in `stop()` |
| `MobileLogRecordProcessor.kt` | Check for background OOM on init |
