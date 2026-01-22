# Log Tailing & Pattern Detection

Keep a circular buffer of recent logs to detect patterns and provide crash context.

## Overview

**Log Tailing** maintains a rolling window of the most recent N log records in memory, enabling:

1. **Pattern Detection**: Trigger on sequences like "3 errors in last 10 logs"
2. **Crash Context**: See what happened before a crash
3. **User Journey**: Understand the sequence of actions leading to an error
4. **Anomaly Detection**: Spot unusual log patterns automatically

This is like having a "breadcrumb trail" that shows what led up to a problem.

## Problem It Solves

### Without Log Tailing
```
App crashes at 3:47 AM
❌ No context - what happened before crash?
❌ Can't detect patterns
❌ Just see the final crash log
```

### With Log Tailing
```
3:46:58 - user.login (success)
3:47:02 - http.request (/api/data)
3:47:05 - http.error (500) ⚠️
3:47:08 - http.request (/api/data) [retry]
3:47:10 - http.error (500) ⚠️ [pattern detected: 2 errors]
3:47:12 - http.request (/api/data) [retry]
3:47:14 - http.error (500) ⚠️ [pattern detected: 3 errors → TRIGGER FLUSH]
3:47:16 - app.crash (NullPointerException)

✅ Context: Crash was caused by repeated HTTP 500 errors
✅ Pattern: 3 errors in a row before crash
✅ Action: Flush last 10 minutes of data for debugging
```

## Configuration

### Basic Configuration

```kotlin
val config = MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "https://collector:4317",
    logTailingConfig = LogTailingConfig.default()
)
```

**Default**: Keep last 100 logs (INFO and above)

### Custom Configuration

```kotlin
// Small tail: 20 logs (memory-constrained devices)
val smallConfig = LogTailingConfig.small()

// Medium tail: 50 logs (balanced)
val mediumConfig = LogTailingConfig.medium()

// Large tail: 200 logs (comprehensive context)
val largeConfig = LogTailingConfig.large()

// Errors only: Only keep ERROR and FATAL logs
val errorsOnlyConfig = LogTailingConfig.errorsOnly()

// Verbose: Include DEBUG logs
val verboseConfig = LogTailingConfig.verbose()
```

### Severity Filtering

```kotlin
val config = LogTailingConfig(
    tailSize = 100,
    includeDebugLogs = false,  // ❌ Skip DEBUG
    includeInfoLogs = true,    // ✅ Keep INFO
    includeWarnLogs = true,    // ✅ Keep WARN
    includeErrorLogs = true,   // ✅ Keep ERROR
    includeFatalLogs = true    // ✅ Keep FATAL
)
```

## Pattern-Based Triggers

### Trigger 1: App Start

Trigger on app startup to capture initial state:

```kotlin
val trigger = LogTailTrigger.onAppStart()

// Result: Flushes 5 minutes of data on app start
// Useful for: Cold start performance, initialization issues
```

**Use Case**: Debug slow app starts, initialization errors

### Trigger 2: Force Close

Trigger when force close is detected (on next app start):

```kotlin
val trigger = LogTailTrigger.onForceClose()

// Result: Flushes last 50 logs and 15 minutes of data
// Captures: Full user journey before force close
```

**Use Case**: Understand why users force close the app

**Example**:
```
Logs before force close:
1. user.login (success)
2. api.request (/feed)
3. http.error (500) ⚠️
4. retry attempt 1
5. http.error (500) ⚠️
6. retry attempt 2
7. http.error (500) ⚠️
8. memory.warning (low)
9. ui.freeze (2500ms)
10. app.force_close ← User gave up!

Insight: User force closed due to repeated errors + UI freeze
```

### Trigger 3: Any Error

Trigger immediately when ANY error or fatal log occurs:

```kotlin
val trigger = LogTailTrigger.onAnyError()

// Result: Flushes 5 minutes of data on first ERROR/FATAL log
```

**Use Case**: Critical apps where every error must be captured

### Trigger 4: Repeated Errors

Trigger when multiple errors occur in recent logs:

```kotlin
val trigger = LogTailTrigger.onRepeatedErrors(count = 3)

// Result: Triggers when 3+ errors in last 10 logs
```

**Use Case**: Detect error spirals before crash

### Trigger 5: Event Name Pattern

Trigger on specific event names:

```kotlin
val crashTrigger = LogTailTrigger.onEventName("app.crash")
val freezeTrigger = LogTailTrigger.onEventName("ui.freeze")
val networkErrorTrigger = LogTailTrigger.onEventName("http.error")
```

**Use Case**: Watch for specific critical events

### Trigger 6: Attribute Pattern

Trigger on attribute conditions:

```kotlin
// Trigger on HTTP 500 errors
val http500Trigger = LogTailTrigger.onAttribute(
    attrName = "http.status_code",
    op = ">=",
    value = 500
)

// Trigger on slow requests (>5s)
val slowRequestTrigger = LogTailTrigger.onAttribute(
    attrName = "http.duration_ms",
    op = ">",
    value = 5000
)

// Trigger on low memory
val lowMemoryTrigger = LogTailTrigger.onAttribute(
    attrName = "memory.available_mb",
    op = "<",
    value = 50
)
```

**Supported operators**: `=`, `!=`, `>`, `<`, `>=`, `<=`, `contains`

### Trigger 7: API/HTTP Error Triggers

Convenient helpers for API and HTTP error detection:

```kotlin
// Trigger on any API error (4xx or 5xx)
val apiErrorTrigger = LogTailTrigger.onApiError()

// Trigger on server errors (5xx only)
val serverErrorTrigger = LogTailTrigger.onServerError()

// Trigger on repeated API errors (3+ in last 10 logs)
val cascadingErrorTrigger = LogTailTrigger.onRepeatedApiErrors(count = 3, lookback = 10)
```

**Use Case**: Detect API failures and backend issues

**Example Scenario**:
```
User journey with API failures:
1. user.login (success)
2. api.request (/feed) → http.status_code = 200 ✅
3. api.request (/posts) → http.status_code = 500 ⚠️
4. retry attempt 1 → http.status_code = 500 ⚠️
5. retry attempt 2 → http.status_code = 503 ⚠️
6. onRepeatedApiErrors() TRIGGERED → Flush last 10 logs + 15 minutes

Result: Full context of API error cascade captured
- Network state at time of errors
- Request/response details
- Retry behavior
- User actions leading to errors
```

### Trigger 8: Custom Pattern

Trigger with custom logic:

```kotlin
val trigger = LogTailTrigger(
    id = "custom-pattern",
    name = "Custom Pattern Detector",
    pattern = TailPattern.CustomPredicate { logRecord ->
        // Custom logic here
        val body = logRecord.body.asString()
        val attrs = logRecord.attributes

        // Example: Trigger on error + low battery
        logRecord.severity == Severity.ERROR &&
        attrs.get(AttributeKey.longKey("battery.level_percent")) ?: 100 < 20
    },
    lookbackCount = 5,
    flushWindowMinutes = 10
)
```

## Usage Example

### Setup

```kotlin
// 1. Configure log tailing
val tailingConfig = LogTailingConfig(
    enabled = true,
    tailSize = 50
)

// 2. Define triggers
val triggers = listOf(
    LogTailTrigger.onAnyError(),
    LogTailTrigger.onRepeatedErrors(count = 3),
    LogTailTrigger.onEventName("app.crash"),
    LogTailTrigger.onServerError(),           // NEW: Detect 5xx errors
    LogTailTrigger.onRepeatedApiErrors(3, 10) // NEW: Detect API error cascades
)

// 3. Create tail buffer
val tailBuffer = LogTailBuffer(tailingConfig, triggers)

// 4. Integrate with log processor
// (happens automatically in MobileLogRecordProcessor)
```

### Logging with Tail Buffer

```kotlin
// Log events normally
logger.logRecordBuilder()
    .setBody("user.action")
    .setSeverity(Severity.INFO)
    .setAllAttributes(Attributes.of(
        AttributeKey.stringKey("action"), "button_click"
    ))
    .emit()

// Tail buffer automatically:
// 1. Adds log to circular buffer
// 2. Evaluates triggers
// 3. Returns matched triggers
// 4. Processor flushes if trigger matched
```

### Logging API Calls for Trigger Detection

```kotlin
// Log API calls with proper attributes for trigger detection
suspend fun makeApiCall(endpoint: String): Response {
    val startTime = System.currentTimeMillis()

    return try {
        val response = httpClient.get(endpoint)
        val duration = System.currentTimeMillis() - startTime

        // Log based on status code
        if (response.status in 400..599) {
            // API error - will trigger onApiError() or onServerError()
            logger.logRecordBuilder()
                .setBody("http.error")
                .setSeverity(if (response.status >= 500) Severity.ERROR else Severity.WARN)
                .setAllAttributes(Attributes.of(
                    AttributeKey.longKey("http.status_code"), response.status.toLong(),
                    AttributeKey.stringKey("http.endpoint"), endpoint,
                    AttributeKey.stringKey("http.method"), "GET",
                    AttributeKey.longKey("http.duration_ms"), duration
                ))
                .emit()
        } else {
            // Success
            logger.logRecordBuilder()
                .setBody("http.request")
                .setSeverity(Severity.INFO)
                .setAllAttributes(Attributes.of(
                    AttributeKey.longKey("http.status_code"), response.status.toLong(),
                    AttributeKey.stringKey("http.endpoint"), endpoint,
                    AttributeKey.longKey("http.duration_ms"), duration
                ))
                .emit()
        }

        response
    } catch (e: Exception) {
        val duration = System.currentTimeMillis() - startTime

        // Network failure - will trigger onAnyError()
        logger.logRecordBuilder()
            .setBody("http.exception")
            .setSeverity(Severity.ERROR)
            .setAllAttributes(Attributes.of(
                AttributeKey.stringKey("http.endpoint"), endpoint,
                AttributeKey.stringKey("exception.type"), e.javaClass.simpleName,
                AttributeKey.stringKey("exception.message"), e.message ?: "unknown",
                AttributeKey.longKey("http.duration_ms"), duration
            ))
            .emit()

        throw e
    }
}

// Result: API errors automatically detected by triggers
// - onServerError() → 500, 502, 503, 504
// - onApiError() → 400, 401, 403, 404, 500, 502, etc.
// - onRepeatedApiErrors() → 3+ errors in sequence
```

### Retrieving Tail for Debugging

```kotlin
// Get last 10 logs (for debugging UI)
val recentLogs = tailBuffer.getTail(count = 10)

recentLogs.forEach { log ->
    println("${log.timestampEpochNanos} - ${log.body.asString()}")
}
```

## Pattern Examples

### Pattern 1: Error Spike Detection

**Goal**: Detect when errors spike suddenly

```kotlin
val errorSpikeDetector = LogTailTrigger(
    id = "error-spike",
    name = "Error Spike Detector",
    pattern = TailPattern.CountThreshold(
        severities = listOf(Severity.ERROR, Severity.FATAL),
        minCount = 5  // 5+ errors
    ),
    lookbackCount = 20,  // in last 20 logs
    flushWindowMinutes = 15
)
```

**Example**:
```
Normal: 1-2 errors per 20 logs → No trigger
Spike:  7 errors in last 20 logs → ⚠️ TRIGGER!
```

### Pattern 2: HTTP Error Cascade

**Goal**: Detect cascading HTTP failures

```kotlin
val httpCascade = LogTailTrigger(
    id = "http-cascade",
    name = "HTTP Error Cascade",
    pattern = TailPattern.CustomPredicate { _ ->
        val recentLogs = tailBuffer.getTail(10)
        val httpErrors = recentLogs.count { log ->
            log.body.asString() == "http.error"
        }
        httpErrors >= 3  // 3+ HTTP errors in last 10 logs
    },
    lookbackCount = 10,
    flushWindowMinutes = 10
)
```

### Pattern 3: Memory Pressure Escalation

**Goal**: Detect progressive memory degradation

```kotlin
val memoryPressure = LogTailTrigger(
    id = "memory-pressure",
    name = "Memory Pressure Escalation",
    pattern = TailPattern.CustomPredicate { _ ->
        val recentLogs = tailBuffer.getTail(5)
        val memoryLogs = recentLogs.mapNotNull { log ->
            log.attributes.get(AttributeKey.longKey("memory.available_mb"))
        }

        // Trigger if memory decreased in last 5 logs
        memoryLogs.zipWithNext().all { (prev, next) -> next < prev }
    },
    lookbackCount = 5,
    flushWindowMinutes = 5
)
```

### Pattern 4: User Journey Before Crash

**Goal**: Capture complete user journey before crash

```kotlin
val crashJourney = LogTailTrigger(
    id = "crash-journey",
    name = "User Journey Before Crash",
    pattern = TailPattern.EventNameMatch("app.crash"),
    lookbackCount = 50,  // Last 50 actions
    flushWindowMinutes = 30  // Flush last 30 minutes
)
```

**Result**:
```
50 logs before crash showing:
- Screen transitions
- Button clicks
- API calls
- Background tasks
- Memory state
- Network state

→ Full context for crash debugging
```

## Memory Usage

### Memory Calculation

```
Memory per log ≈ 500 bytes (average)
Tail size = 100 logs
Total memory ≈ 50 KB

Tail size = 200 logs
Total memory ≈ 100 KB

Tail size = 1000 logs (max)
Total memory ≈ 500 KB
```

**Recommendation**: 50-100 logs (25-50 KB) for most apps

### Memory-Constrained Devices

For low-end devices:

```kotlin
val config = LogTailingConfig(
    tailSize = 20,  // Minimal: 10 KB
    includeDebugLogs = false,
    includeInfoLogs = false,
    includeWarnLogs = true,
    includeErrorLogs = true,
    includeFatalLogs = true
)
```

## Use Cases

### Use Case 1: Crash Debugging

**Problem**: App crashes, but crash report doesn't show cause

**Solution**: Log tail shows the 50 events before crash

```
Example tail before crash:
3:46:58 - user.login
3:47:02 - api.request (/user/profile)
3:47:05 - api.error (500)
3:47:08 - cache.read (null)  ← Root cause
3:47:10 - app.crash (NullPointerException)

Insight: Crash caused by null cache after API error
```

### Use Case 2: Error Pattern Detection

**Problem**: Users report "app acting weird" before crash

**Solution**: Detect repeated errors before crash

```
Pattern detected:
- 3 HTTP 500 errors in 10 logs
- Memory decreasing steadily
- Network switching wifi → cellular → offline

→ Trigger flush immediately
→ Capture full context before crash
```

### Use Case 3: User Journey Analysis

**Problem**: Can't reproduce crash

**Solution**: See exact user journey

```
User journey before crash:
1. App start
2. Login (success)
3. Navigate to feed
4. Scroll (50 items loaded)
5. Pull to refresh
6. HTTP error (500)
7. Retry
8. HTTP error (500)
9. Memory warning
10. Crash (OOM)

Insight: Crash caused by memory leak during refresh
```

### Use Case 4: A/B Test Monitoring

**Problem**: New feature causes errors for some users

**Solution**: Track error patterns in A/B groups

```
Group A (new feature):
- 5 errors in last 20 logs ⚠️

Group B (control):
- 1 error in last 20 logs ✅

Insight: New feature has error spike → rollback
```

## Performance Impact

### CPU Overhead

- **Adding log to tail**: O(1) - constant time
- **Evaluating 1 trigger**: O(N) where N = lookbackCount
- **Evaluating 5 triggers**: ~5× O(N)

**Typical**: <1ms per log with 5 triggers

### Memory Overhead

- **50 logs**: ~25 KB
- **100 logs**: ~50 KB
- **200 logs**: ~100 KB

**Recommendation**: 100 logs (50 KB) provides good context with minimal overhead

### Battery Impact

Negligible - log tailing is in-memory only, no disk I/O or network calls

## Best Practices

### 1. Size Tail Appropriately

```kotlin
// Mobile app (typical)
tailSize = 50-100 logs

// High-traffic app (complex flows)
tailSize = 200 logs

// Memory-constrained device
tailSize = 20 logs
```

### 2. Filter by Severity

```kotlin
// Production: Skip DEBUG logs
LogTailingConfig(
    includeDebugLogs = false,  // Reduce noise
    includeInfoLogs = true,
    includeErrorLogs = true
)

// Development: Include all logs
LogTailingConfig(
    includeDebugLogs = true,
    includeInfoLogs = true,
    includeErrorLogs = true
)
```

### 3. Use Specific Triggers

```kotlin
// ✅ Good: Specific error threshold
LogTailTrigger.onRepeatedErrors(count = 3)

// ❌ Bad: Too sensitive
LogTailTrigger.onAnyError()  // Triggers on every single error
```

### 4. Combine with Workflows

```json
{
  "id": "crash-with-context",
  "trigger": {
    "event": "app.crash"
  },
  "actions": [
    {"type": "flush_tail", "include_last_n_logs": 50},
    {"type": "flush_window", "minutes": 10},
    {"type": "capture_device_metrics"}
  ]
}
```

**Result**: On crash, export:
- Last 50 logs (tail buffer)
- Last 10 minutes of events (time window)
- Device metrics snapshot

### 5. Monitor Tail Buffer

```kotlin
// Track tail buffer usage
val meter = openTelemetry.getMeter("tail-monitoring")

val tailSizeGauge = meter.gaugeBuilder("tail.size")
    .setDescription("Current tail buffer size")
    .ofLongs()
    .build()

tailSizeGauge.record(tailBuffer.size().toLong())
```

## Related Documentation

- [Device Metrics](./DEVICE_METRICS.md) - Capture device state on triggers
- [Workflow System](./WORKFLOW_SYSTEM.md) - Workflow-triggered patterns
- [Export Modes](./EXPORT_MODES.md) - When tail is flushed

---

**Recommended**: 50-100 log tail with error pattern detection for production apps
