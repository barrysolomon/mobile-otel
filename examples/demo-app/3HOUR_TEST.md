# 3-Hour Monkey Test Guide

Complete guide for running a comprehensive 3-hour stress test with transaction flows, demographic cycling, and realistic usage patterns.

## Test Configuration

The enhanced monkey test simulates realistic mobile app usage over an extended period with:

### Transaction Flows
- **Login → API Call → Navigate** sequences every 15 actions
- Simulates complete user sessions with correlated events
- Creates distributed traces across multiple operations

### Demographic Cycling
- Changes user profile every 50 actions
- 8 different demographic profiles including:
  - Device types (smartphone, tablet, phablet)
  - Regions (US, EU, Asia, LATAM)
  - Age groups (18-24, 25-34, 35-44, 45-54, 55-64)
  - Subscription tiers (free, basic, premium)

### Activity Patterns
- **Long stretches** (30-50 normal actions) between error triggers
- Simulates extended periods of healthy app usage
- Periodic errors to test recovery and crash workflows

### Manual Flushes
- Automatic flush every 25 actions
- Validates telemetry buffering and export
- Ensures data reaches Dash0 throughout test

## Running the 3-Hour Test

### Prerequisites

```bash
# 1. Install adb
brew install android-platform-tools

# 2. Connect device and enable USB debugging
adb devices

# 3. Install app
cd examples/demo-app/android
./gradlew installDebug

# 4. Verify app works
adb shell am start -n io.opentelemetry.android.demo/.MainActivity
```

### Run the Test

```bash
cd examples/demo-app

# 3-hour test with all features (RECOMMENDED)
bash monkey-test.sh --time 10800

# With verbose output (helps debug issues)
bash monkey-test.sh --time 10800 --verbose

# Custom configuration
bash monkey-test.sh \
  --time 10800 \
  --stretch-mode long \
  --transactions 15 \
  --demographics 50 \
  --flush-interval 25 \
  --verbose
```

### What Happens During the Test

**Typical 3-hour session:**
- ~800-1000 total actions (depends on delays and error handling)
- ~50-65 transaction flows (login → api → navigate)
- ~16-20 demographic profile changes
- ~35-40 manual flushes
- ~15-25 error triggers (crashes, ANRs, network errors, etc.)
- ~80-100 background/foreground transitions

**Expected Timeline:**
```
0:00 - Test starts, initial demographic profile set
0:03 - First transaction flow
0:05 - First manual flush
0:10 - First demographic change
0:15 - First error trigger (after long stretch)
0:20 - Second transaction flow
...
3:00 - Test complete, final flush
```

## Monitoring the Test

### In Terminal (Running Test)

```bash
cd examples/demo-app
bash monkey-test.sh --time 10800 --verbose 2>&1 | tee test-$(date +%Y%m%d-%H%M%S).log
```

**You'll see output like:**
```
[INFO] ==== Enhanced Monkey Test Configuration ====
[INFO] Max duration: 3h 0m (10800s)
[INFO] Stretch mode: long
[INFO] Flush interval: every 25 actions
[INFO] Transaction flow: every 15 actions
[INFO] Demographics change: every 50 actions
[INFO] =============================================

[DEMOGRAPHIC] Setting profile #1: device_type:smartphone,region:us,age_group:25-34,tier:premium
[STRETCH] Starting with stretch: 42 normal actions
[INFO] Action #1: Clicking login (stretch: 1/42)
[INFO] Action #2: Clicking navigate (stretch: 2/42)
...
[INFO] Action #15: Clicking form_submit (stretch: 15/42)
[TRANSACTION] Executing transaction flow: login → api_call → navigate
[TRANSACTION]   Step 1/3: Login
[TRANSACTION]   Step 2/3: API Call
[TRANSACTION]   Step 3/3: Navigate
[TRANSACTION] Transaction flow complete
...
[INFO] 🚀 Triggering manual flush (interval: 25)
...
[STRETCH] End of stretch - triggering: ui_freeze
[STRETCH] Starting new stretch: 35 normal actions
...
[DEMOGRAPHIC] Setting profile #2: device_type:tablet,region:eu,age_group:35-44,tier:free
...
```

### In Another Terminal (Watch Logcat)

```bash
# Watch app logs
adb logcat | grep -E "OTELDemoApp|MobileLogger|Export|LoggingHttpExporter"

# Watch for exports
adb logcat | grep -i "export"

# Watch for transaction flows
adb logcat | grep -E "user.login|api.call|page.navigation"

# Watch for errors
adb logcat | grep -E "FATAL|ERROR|ANR|Crash"
```

### Expected Logcat Output

```
I MobileLogger: OpenTelemetry initialized: deviceId=abc-123
I OTELDemoApp: Logged user.login event
I OTELDemoApp: Logged api.call event
I OTELDemoApp: Logged page.navigation event
D LoggingHttpExporter: === EXPORT ATTEMPT ===
D LoggingHttpExporter: Log count: 45
I LoggingHttpExporter: ✅ Export successful to https://ingress.us-west-2.aws.dash0.com:4317/v1/logs (45 logs)
I OTELDemoApp: Logged app.background event
I OTELDemoApp: Logged app.foreground event
I MobileLogger: Flushed 23 events to collector
W OTELDemoApp: Logged ui.freeze event (duration: 3200ms)
```

## Verifying Results in Dash0

### During the Test

1. Go to https://app.dash0.com
2. Navigate to **Services** → `otel-mobile-demo`
3. Set time range to "Last 15 minutes"
4. Check for recent activity:
   - Logs stream should show constant activity
   - Traces should show login → api → navigate patterns
   - Metrics should show increasing counters

### After the Test

**Check for Key Events:**
```
Search for:
- user.login (should see ~60 instances)
- api.call (should see ~60 instances)
- page.navigation (should see ~60 instances)
- app.background (should see ~90 instances)
- app.foreground (should see ~90 instances)
- ui.freeze (should see ~5-10 instances)
- http.error (should see ~5-10 instances)
- app.crash_recovery (should see ~2-5 instances if crashes occurred)
```

**Check Demographics Distribution:**
- Filter by device_type: smartphone vs tablet vs phablet
- Filter by region: us vs eu vs asia vs latam
- Filter by age_group: 18-24 vs 25-34 vs 35-44 vs 45-54 vs 55-64
- Filter by tier: free vs basic vs premium

**Check Transaction Traces:**
1. Go to **Traces** tab
2. Look for traces with 3 spans:
   - Span 1: user.login
   - Span 2: api.call
   - Span 3: page.navigation
3. Verify they're correlated by trace_id
4. Check span timing and relationships

**Check Export Reliability:**
- Total events should be ~800-1000
- Export success rate should be >95%
- No major gaps in timeline
- All demographic profiles represented

## Test Statistics

At the end, you'll see:
```
[INFO] Enhanced monkey test complete!
[INFO] ==== Test Statistics ====
[INFO] Total actions: 947
[INFO] Duration: 3h 0m 12s
[INFO] Actions/min: 5.3
[INFO] =========================
[INFO] Triggering final flush...
[INFO] Test finished successfully
```

## Troubleshooting

### Test Stops Prematurely

```bash
# Check if app crashed
adb shell pidof io.opentelemetry.android.demo

# If no output, app crashed. Check logs:
adb logcat -d | grep -E "FATAL|AndroidRuntime"

# Restart test from where it failed
bash monkey-test.sh --time <remaining-seconds>
```

### Device Screen Locks

```bash
# Keep screen awake during test
adb shell settings put system screen_off_timeout 2147483647

# After test, reset to default (2 minutes)
adb shell settings put system screen_off_timeout 120000
```

### Device Battery Dies

```bash
# Keep device plugged in during 3-hour test
# Monitor battery level:
adb shell dumpsys battery | grep level

# Check if charging:
adb shell dumpsys battery | grep status
```

### High Memory Usage

```bash
# Monitor app memory during test
adb shell dumpsys meminfo io.opentelemetry.android.demo

# If OOM kills occur, they'll be logged and recovered automatically
```

### No Data in Dash0

```bash
# Check network connectivity
adb shell ping -c 3 ingress.us-west-2.aws.dash0.com

# Check export logs
adb logcat | grep LoggingHttpExporter

# Verify auth token is correct
adb logcat | grep "Authorization"
```

## Test Variations

### More Aggressive (More Errors)

```bash
# Short stretches = more frequent triggers
bash monkey-test.sh --time 10800 --stretch-mode short
```

### More Conservative (Fewer Errors)

```bash
# Very long stretches, fewer triggers
bash monkey-test.sh --time 10800 --stretch-mode long --transactions 10 --flush-interval 30
```

### Focus on Transactions

```bash
# More frequent transactions, less randomness
bash monkey-test.sh --time 10800 --transactions 10 --flush-interval 20
```

### Focus on Demographics

```bash
# Change demographics more frequently
bash monkey-test.sh --time 10800 --demographics 30
```

### Overnight Test (8 hours)

```bash
# Extended stress test
bash monkey-test.sh --time 28800 --stretch-mode long --transactions 20 --demographics 100
```

## Post-Test Analysis

### Generate Report

```bash
# Extract statistics from log
grep -E "\[INFO\]|\[TRANSACTION\]|\[DEMOGRAPHIC\]|\[STRETCH\]" test-*.log | \
  awk '
    /TRANSACTION/ { transactions++ }
    /DEMOGRAPHIC/ { demographics++ }
    /Total actions/ { actions=$NF }
    /Duration/ { duration=$0 }
    END {
      print "=== Test Summary ==="
      print duration
      print "Total Actions:", actions
      print "Transaction Flows:", transactions
      print "Demographic Changes:", demographics
    }
  '
```

### Check for Issues

```bash
# Count error types
grep -E "Crash|ANR|OOM|NetworkError" test-*.log | \
  sort | uniq -c | sort -rn

# Check recovery success rate
grep -c "restarted after" test-*.log
```

## CI/CD Integration

For automated testing:

```bash
#!/bin/bash
# ci-monkey-test.sh

set -e

# Ensure device is connected
if ! adb devices | grep -q "device$"; then
  echo "No device connected"
  exit 1
fi

# Install app
./gradlew installDebug

# Run test
cd examples/demo-app
bash monkey-test.sh --time 3600 --stretch-mode long > test.log 2>&1

# Check for failures
if grep -q "FATAL" test.log; then
  echo "Test failed with fatal errors"
  exit 1
fi

# Verify minimum actions completed
ACTIONS=$(grep "Total actions:" test.log | awk '{print $NF}')
if [ "$ACTIONS" -lt 200 ]; then
  echo "Test completed too few actions: $ACTIONS"
  exit 1
fi

echo "Test passed successfully"
exit 0
```

## Expected Results

After a successful 3-hour test:

✅ **Telemetry Captured:**
- 800-1000 total events
- 60+ transaction flows
- 16-20 demographic profiles cycled
- 35-40 manual flushes executed

✅ **Error Handling:**
- 2-5 crash recoveries
- 1-3 ANR recoveries
- 5-10 UI freeze triggers
- 5-10 network error triggers

✅ **Lifecycle Events:**
- 80-100 background/foreground transitions
- App foreground workflow triggered after each return

✅ **Export Reliability:**
- >95% export success rate
- All events visible in Dash0
- No major data gaps
- Transaction traces properly correlated

✅ **Demographic Distribution:**
- All 8 profiles represented
- Even distribution across device types, regions, age groups, tiers

## Next Steps

After successful 3-hour test:
1. Review telemetry in Dash0 UI
2. Analyze transaction patterns
3. Check demographic distribution
4. Validate export reliability
5. Document any issues or observations
6. Consider longer tests (8-24 hours) for production validation
