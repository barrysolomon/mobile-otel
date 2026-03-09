# Transaction Tracking Guide

Complete guide for transaction tracking with configurable pass/fail/crash outcomes in the OpenTelemetry Mobile Demo app.

## Overview

The demo app implements comprehensive transaction tracking that simulates realistic mobile app behavior with configurable outcome rates. This allows testing how the observability system handles successful operations, graceful failures, and unexpected crashes.

## Transaction Outcomes

### Configuration

Default outcome distribution:
- **70% PASS**: Transactions complete successfully
- **20% FAIL**: Transactions fail gracefully with error handling
- **10% CRASH**: App crashes before transaction completes

### Outcome Types

#### 1. PASS (Success)
Transaction completes successfully with `StatusCode.OK`:

**Login Example**:
```kotlin
// Successful authentication with session ID
span.setStatus(StatusCode.OK)
logger.log("auth.login.success")
// Transaction markers cleared
```

**API Call Example**:
```kotlin
// HTTP 200 OK response
span.setAttribute("http.status_code", 200L)
span.setStatus(StatusCode.OK)
// Transaction markers cleared
```

#### 2. FAIL (Graceful Failure)
Transaction fails but handles error properly with `StatusCode.ERROR`:

**Login Example**:
```kotlin
// Invalid credentials - graceful failure
span.setStatus(StatusCode.ERROR, "Invalid credentials")
logger.log("auth.login.failure", severity=WARN)
// Transaction markers cleared
```

**API Call Example**:
```kotlin
// HTTP 500 server error - graceful failure
span.setAttribute("http.status_code", 500L)
span.setStatus(StatusCode.ERROR, "HTTP 500")
// Transaction markers cleared
```

#### 3. CRASH (Incomplete Transaction)
App crashes before transaction completes - markers remain active in SharedPreferences:

**During Crash**:
```kotlin
// Transaction started but NOT ended
startTrackedTransaction(id, type, span)
// ... crash occurs ...
// endTrackedTransaction() NEVER called
```

**On Next App Start**:
```kotlin
// Detects incomplete transaction
logAppStart() {
    if (transactionWasActive) {
        // Log incomplete transaction event
        logger.log("transaction.incomplete", severity=WARN)

        // Create synthetic span
        val syntheticSpan = tracer.spanBuilder(transactionType)
            .setAttribute("transaction.synthetic", true)
            .setAttribute("transaction.incomplete", true)
            .setStartTimestamp(previousStartTime)
            .startSpan()

        syntheticSpan.addEvent("transaction_interrupted_by_crash")
        syntheticSpan.setStatus(StatusCode.ERROR, "Transaction interrupted by app crash")
        syntheticSpan.end(crashTime)
    }
}
```

## Tracked Transactions

### Login (auth.login)

**Transaction Type**: `auth.login`

**PASS Outcome**:
```json
{
  "span": {
    "name": "auth.login",
    "status": "OK",
    "attributes": {
      "transaction.id": "abc-123",
      "transaction.type": "auth.login",
      "transaction.outcome": "PASS",
      "user.id": "user_12345",
      "session.id": "session-xyz"
    }
  },
  "log": {
    "body": "auth.login.success",
    "attributes": {
      "transaction.id": "abc-123",
      "transaction.outcome": "PASS"
    }
  }
}
```

**FAIL Outcome**:
```json
{
  "span": {
    "name": "auth.login",
    "status": "ERROR",
    "statusMessage": "Invalid credentials",
    "attributes": {
      "transaction.id": "abc-123",
      "transaction.outcome": "FAIL",
      "error.type": "auth.invalid_credentials"
    }
  },
  "log": {
    "body": "auth.login.failure",
    "severity": "WARN",
    "attributes": {
      "transaction.id": "abc-123",
      "transaction.outcome": "FAIL",
      "error.type": "invalid_credentials"
    }
  }
}
```

**CRASH Outcome** (detected on restart):
```json
{
  "log": {
    "body": "transaction.incomplete",
    "severity": "WARN",
    "attributes": {
      "transaction.id": "abc-123",
      "transaction.type": "auth.login",
      "transaction.status": "incomplete_due_to_crash",
      "transaction.duration_before_crash_ms": 234,
      "recovery_type": "crash"
    }
  },
  "synthetic_span": {
    "name": "auth.login",
    "status": "ERROR",
    "statusMessage": "Transaction interrupted by app crash",
    "attributes": {
      "transaction.id": "abc-123",
      "transaction.synthetic": true,
      "transaction.incomplete": true,
      "recovery_type": "crash",
      "duration_before_crash_ms": 234
    },
    "events": ["transaction_interrupted_by_crash"]
  }
}
```

### API Call (http.request)

**Transaction Type**: `http.request`

**PASS**: HTTP 200 OK with response data
**FAIL**: HTTP 500/502/503 server error
**CRASH**: Crashes during API call (incomplete transaction)

### Navigation (screen.navigation)

**Transaction Type**: `screen.navigation`

**PASS**: Successful screen transition
**FAIL**: Screen not found error
**CRASH**: Crashes during navigation (incomplete transaction)

## Configuring Outcome Rates

### Default Configuration

In `MainActivity.kt`:
```kotlin
private val transactionOutcomeConfig = TransactionOutcomeConfig(
    passRate = 70,    // 70% pass
    failRate = 20,    // 20% fail
    crashRate = 10    // 10% crash
)
```

### Custom Configurations

**More Stable (90% success)**:
```kotlin
private val transactionOutcomeConfig = TransactionOutcomeConfig(
    passRate = 90,
    failRate = 8,
    crashRate = 2
)
```

**More Chaotic (50% success)**:
```kotlin
private val transactionOutcomeConfig = TransactionOutcomeConfig(
    passRate = 50,
    failRate = 30,
    crashRate = 20
)
```

**No Crashes (testing graceful failures)**:
```kotlin
private val transactionOutcomeConfig = TransactionOutcomeConfig(
    passRate = 70,
    failRate = 30,
    crashRate = 0
)
```

**Crash-Heavy (testing recovery)**:
```kotlin
private val transactionOutcomeConfig = TransactionOutcomeConfig(
    passRate = 50,
    failRate = 20,
    crashRate = 30
)
```

## Observability Benefits

### 1. Transaction Success Rate
Query Dash0 for transaction outcomes:
```
transaction.outcome:PASS
transaction.outcome:FAIL
transaction.outcome:CRASH
```

Calculate success rate:
```
PASS / (PASS + FAIL + CRASH) * 100
```

### 2. Crash Impact Analysis
See which transactions were interrupted:
```
transaction.incomplete:true
transaction.synthetic:true
```

Analyze by type:
```
transaction.type:auth.login AND transaction.incomplete:true
transaction.type:http.request AND transaction.incomplete:true
```

### 3. Error Patterns
Identify graceful failure patterns:
```
transaction.outcome:FAIL AND error.type:*
```

### 4. Recovery Validation
Verify crash recovery works:
```
recovery_type:crash AND transaction.incomplete:true
```

## Testing Scenarios

### Scenario 1: Normal Operations
**Config**: 90% PASS, 10% FAIL, 0% CRASH
**Purpose**: Validate happy path and graceful error handling
**Expected**: Mostly successful transactions with occasional failures

### Scenario 2: Unstable Environment
**Config**: 50% PASS, 30% FAIL, 20% CRASH
**Purpose**: Test resilience under adverse conditions
**Expected**: Mix of outcomes, frequent crash recovery events

### Scenario 3: Crash Recovery Focus
**Config**: 40% PASS, 30% FAIL, 30% CRASH
**Purpose**: Stress test crash detection and synthetic span creation
**Expected**: Many incomplete transactions and synthetic spans

### Scenario 4: Production-Like
**Config**: 70% PASS, 20% FAIL, 10% CRASH (default)
**Purpose**: Simulate realistic production ratios
**Expected**: Mostly successful with realistic failure rates

## Verification in Dash0

### Check Transaction Distribution

1. Go to Dash0 UI: https://app.dash0.com
2. Navigate to **Services** → `otel-mobile-demo`
3. Search for transactions:
   ```
   transaction.outcome:PASS
   transaction.outcome:FAIL
   transaction.outcome:CRASH
   ```

### Verify Incomplete Transactions

1. Search for: `transaction.incomplete:true`
2. Check attributes:
   - `transaction.id` - unique identifier
   - `transaction.type` - operation type
   - `transaction.duration_before_crash_ms` - time before crash
   - `recovery_type` - crash, anr, etc.

### Examine Synthetic Spans

1. Go to **Traces** tab
2. Filter: `transaction.synthetic:true`
3. Verify span properties:
   - Status: ERROR
   - Status Message: "Transaction interrupted by app crash"
   - Event: "transaction_interrupted_by_crash"
   - Start/end times reflect actual crash timing

## OpenTelemetry Best Practices

This implementation follows OTel best practices:

✅ **Use Span Status**: `StatusCode.OK` for success, `StatusCode.ERROR` for failures
✅ **Add Span Events**: Mark important milestones like crashes
✅ **Record Exceptions**: Use `span.recordException(e)` for caught errors
✅ **Semantic Attributes**: Follow conventions like `transaction.id`, `error.type`
✅ **Synthetic Spans**: Create spans for incomplete operations to avoid data loss
✅ **Persistent Tracking**: Use SharedPreferences to survive crashes
✅ **Correlation**: Use `transaction.id` to correlate logs, spans, and recovery events

## Troubleshooting

### No Incomplete Transactions Detected

**Symptoms**: Crashes occur but no incomplete transaction events
**Causes**:
- SharedPreferences not persisting (check permissions)
- App not crashing (use CRASH button to test)
- Recovery markers being cleared incorrectly

**Fix**:
```bash
# Check logcat for transaction tracking
adb logcat | grep -E "Started tracked transaction|Detected incomplete transaction"
```

### Transaction Outcomes Not Matching Configuration

**Symptoms**: Actual outcome ratios don't match configured rates
**Causes**:
- Small sample size (need 100+ transactions for statistical accuracy)
- Random number generator variance

**Fix**: Run longer tests with more transactions

### Synthetic Spans Missing

**Symptoms**: Incomplete transaction logs but no synthetic spans
**Causes**:
- Tracer not initialized before logAppStart()
- Exception during synthetic span creation

**Fix**: Check initialization order in onCreate()

## Next Steps

After successful transaction tracking:
1. Analyze transaction success rates in Dash0
2. Identify patterns in failures
3. Validate crash recovery workflow
4. Adjust outcome rates for different test scenarios
5. Integrate with monkey test for automated stress testing
