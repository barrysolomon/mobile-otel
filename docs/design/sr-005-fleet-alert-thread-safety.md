# SR-005: FleetAlertHandler Thread Safety

**Severity:** CRITICAL
**Status:** Planned

## Problem

`FleetAlertHandler.alertTimestamps` (`MutableList<Long>`) and `activeOverrides` (`MutableMap<String, ActiveOverride>`) are plain `ArrayList` and `HashMap`. `onFleetAlert()` is not synchronized and can be called concurrently from fleet broadcast receivers. Two threads can simultaneously pass the rate-limit check, defeating the `maxAlertsPerHour` limit.

## Design

Replace mutable collections with thread-safe alternatives and add synchronization to the rate-limit check.

```kotlin
private val alertTimestamps = CopyOnWriteArrayList<Long>()
private val activeOverrides = ConcurrentHashMap<String, ActiveOverride>()
private val rateLimitLock = ReentrantLock()

fun isRateLimited(): Boolean = rateLimitLock.withLock {
    val cutoff = System.currentTimeMillis() - 3_600_000
    alertTimestamps.removeAll { it < cutoff }
    alertTimestamps.size >= maxAlertsPerHour
}

fun recordAlertTimestamp() = rateLimitLock.withLock {
    alertTimestamps.add(System.currentTimeMillis())
}
```

The lock scope is narrow (only rate-limit check + record) so contention is minimal.

## Files Changed

| File | Change |
|------|--------|
| `FleetAlertHandler.kt` | Replace collections, add `ReentrantLock` around rate-limit check |

## Testing

- Test: 20 concurrent `onFleetAlert()` calls with `maxAlertsPerHour=5` → verify exactly 5 execute
