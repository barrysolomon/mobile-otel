# SR-003: Singleton Lifecycle Management

**Severity:** CRITICAL
**Status:** Planned
**Also covers:** SR-014 (MobileLoggerProvider singleton)

## Problem

`DiskLogBuffer.getInstance()` and `MobileLoggerProvider.getInstance()` are double-checked locking singletons that:

1. **Ignore config parameters after first creation** — if re-initialized with different `maxSizeMb`/`ttlHours`, the old values persist
2. **Are never cleared on `MobileOtel.shutdown()`** — re-initialization reuses stale, already-shut-down instances

This causes silent misconfiguration and dropped telemetry after shutdown/restart cycles.

## Design

Add `resetInstance()` methods to both singletons, called from `MobileOtel.shutdown()`.

### Changes

```kotlin
// DiskLogBuffer.kt
companion object {
    @Volatile private var instance: DiskLogBuffer? = null

    internal fun resetInstance() {
        synchronized(this) {
            instance?.close()  // close Room database
            instance = null
        }
    }
}

// MobileLoggerProvider.kt  
companion object {
    @Volatile private var instance: MobileLoggerProvider? = null
    
    internal fun resetInstance() {
        synchronized(this) {
            instance?.shutdown()
            instance = null
        }
    }
}
```

### Shutdown Sequence

```
MobileOtel.shutdown():
  1. provider?.shutdown()           // flush remaining events
  2. MobileLoggerProvider.resetInstance()
  3. DiskLogBuffer.resetInstance()   // close Room DB
  4. provider = null
```

### Re-initialization Guard

Add a state check in `getInstance()`:

```kotlin
fun getInstance(context: Context, maxSizeMb: Int, ttlHours: Int): DiskLogBuffer {
    return instance ?: synchronized(this) {
        instance ?: DiskLogBuffer(context, maxSizeMb, ttlHours).also { instance = it }
    }
}
```

If `instance` exists but was `.close()`d, the Room database reference is stale. The `resetInstance()` approach avoids this by nulling the reference.

## Files Changed

| File | Change |
|------|--------|
| `DiskLogBuffer.kt` | Add `resetInstance()`, add `close()` method that closes Room DB |
| `MobileLoggerProvider.kt` | Add `resetInstance()` |
| `MobileOtel.kt` | Call both `resetInstance()` methods in `shutdown()` |

## Testing

- Test: `initialize() → shutdown() → initialize()` with different config → verify new config takes effect
- Test: `shutdown()` followed by `getEventCount()` throws or returns 0, not stale data
