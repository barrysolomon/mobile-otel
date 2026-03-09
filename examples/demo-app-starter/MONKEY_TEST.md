# Monkey Test Guide

Automated stress testing for the OpenTelemetry Mobile Demo app with crash/ANR detection, activity stretches, and periodic flushes.

## Overview

The monkey test script performs randomized testing of the demo app to validate:
- **Telemetry capture** during normal and abnormal app behavior
- **Buffer persistence** across crashes and ANRs
- **Crash recovery** workflow triggering
- **Background/foreground** lifecycle handling
- **Export reliability** under stress conditions
- **Realistic usage patterns** with configurable activity stretches
- **Manual flush validation** with periodic triggers

## Features

### Activity Stretches (NEW)
Simulate realistic user behavior with bursts of normal activity:
- **Short** (5-10 actions): Quick interactions between triggers
- **Medium** (15-25 actions): Typical user session
- **Long** (30-50 actions): Extended usage without errors
- **Mixed**: Random combination of all stretch types (default)
- **None**: Original weighted random behavior

Stretches perform only normal activities (login, navigate, etc.) without triggering errors, then optionally trigger an error event between stretches.

### Periodic Manual Flushes (NEW)
Automatically trigger manual flushes at configurable intervals:
- Default: Every 20 actions
- Validates telemetry is being buffered correctly
- Tests export on-demand functionality
- Configurable or can be disabled

### Intelligent Action Selection
- **Stretch-aware selection**: Normal activities during stretches, mixed between stretches
- **Background/foreground transitions**: 10% chance of sending app to background and bringing it back
- **Smart crash handling**: Detects crashes and restarts app after random delay (5-60s)
- **ANR detection**: Identifies ANR states and force-kills/restarts app

### Button Distribution
When not in stretch mode:
- **Regular Activities** (70%): Login, Navigate, API Call, Background Task, User Interaction, Form Submit
- **Trigger Events** (20%): UI Freeze, Network Error (crash/ANR weighted lower)
- **Manual Controls** (10%): Force Flush

### Error Handling
- Automatic detection of:
  - App crashes (process died)
  - ANR (Application Not Responding)
  - Hung UI (unresponsive)
- Automatic recovery with random restart delays
- Continuation of test after recovery

## Prerequisites

```bash
# Install Android Debug Bridge (adb)
# macOS
brew install android-platform-tools

# Ubuntu/Debian
sudo apt-get install adb

# Verify installation
adb version
```

## Device Setup

```bash
# Enable USB debugging on your Android device:
# Settings → About Phone → Tap "Build Number" 7 times → Developer Options → USB Debugging

# Connect device and verify
adb devices

# Should show:
# List of devices attached
# <device-id>    device
```

## Usage

### Basic Usage

```bash
cd examples/demo-app

# Run 100 actions with mixed stretches (default)
bash monkey-test.sh --actions 100

# Run for 5 minutes with medium stretches
bash monkey-test.sh --time 300 --stretch-mode medium

# Run 50 actions with long stretches and verbose output
bash monkey-test.sh -a 50 -s long -v
```

### Advanced Options

```bash
# Full option list
bash monkey-test.sh --help

# Custom flush interval (every 15 actions instead of 20)
bash monkey-test.sh --actions 100 --flush-interval 15

# Disable automatic flushes
bash monkey-test.sh -a 100 -f 0

# Use old behavior (no stretches)
bash monkey-test.sh --actions 50 --stretch-mode none

# Custom package name (if modified)
bash monkey-test.sh --actions 50 --package com.example.custom
```

## Command Reference

| Option | Short | Description | Default |
|--------|-------|-------------|---------|
| `--actions N` | `-a` | Run N random actions | Unlimited |
| `--time N` | `-t` | Run for N seconds | Unlimited |
| `--stretch-mode MODE` | `-s` | Activity stretch mode: mixed\|short\|medium\|long\|none | `mixed` |
| `--flush-interval N` | `-f` | Manual flush every N actions (0=disable) | `20` |
| `--package PKG` | `-p` | Target package name | `io.opentelemetry.android.demo` |
| `--verbose` | `-v` | Show detailed debug output | Off |
| `--help` | `-h` | Show help message | - |

### Stretch Modes

| Mode | Description | Actions Between Triggers |
|------|-------------|--------------------------|
| `mixed` | Random mix of short/medium/long (default) | Varies |
| `short` | Quick bursts of activity | 5-10 |
| `medium` | Typical user sessions | 15-25 |
| `long` | Extended usage | 30-50 |
| `none` | Original weighted random | N/A |

## What the Test Does

### 1. Activity Stretches (Default Mode)
Simulates realistic usage patterns with bursts of normal activity:

```
During Stretch (5-50 actions depending on mode):
- Only regular activity buttons: Login, Navigate, API Call, Background, Interaction, Form Submit
- No trigger events
- Simulates normal user sessions

Between Stretches:
- May trigger error events: UI Freeze, Network Error, Crash, Low Memory, ANR
- Tests error handling and recovery

Example Flow (medium mode):
1. 20 normal actions (login, navigate, api calls, etc.)
2. Trigger: UI Freeze
3. 17 normal actions
4. Manual Flush (every 20 actions)
5. 23 normal actions
6. Trigger: Network Error
7. Continue...
```

### 2. Periodic Manual Flushes
Automatically triggers manual flush button every N actions (default: 20):

```
Why This Matters:
- Validates buffering is working correctly
- Tests export-on-demand functionality
- Simulates user-triggered sync
- Helps verify data reaches backend
```

### 3. Button Click Behavior

**During Stretches** (normal activity only):
```
Regular Activity Buttons (equal weight):
- Login, Navigate, API Call, Background, Interaction, Form Submit
```

**Between Stretches or "none" mode** (weighted random):
```
Regular Activity Buttons (weight 15 each):
- Login, Navigate, API Call, Background, Interaction, Form Submit

Trigger Event Buttons (weight 5-1):
- UI Freeze (weight 5)
- Network Error (weight 5)
- Crash (weight 2) - causes app restart
- Low Memory (weight 1) - may cause Android OOM kill
- ANR (weight 1) - causes 30s freeze

Manual Controls (weight 10):
- Force Flush - sends buffered telemetry
```

### 4. Background/Foreground Transitions (10% of actions)
Tests lifecycle handling by:
1. Pressing Home button (app goes to background)
2. Waiting 1-10 seconds
3. Bringing app back to foreground
4. Triggers "app.foreground" event → flushes last 5 minutes of events

### 5. Crash Detection & Recovery
When app crashes:
1. Detects process death
2. Waits random delay (5-60 seconds)
3. Force-stops app cleanly
4. Restarts app
5. Continues test
6. App should log "app.crash_recovery" event on restart

### 6. ANR Detection & Recovery
When app enters ANR state:
1. Detects ANR via `dumpsys activity`
2. Force-kills app process
3. Waits random delay (10-40 seconds)
4. Restarts app
5. Continues test

## Expected Telemetry Events

During a monkey test, you should see these events in Dash0/Jaeger:

### Regular Events
- `user.login` - Login button
- `page.navigation` - Navigate button
- `api.call` - API Call button
- `background.task` - Background Task button
- `user.interaction` - User Interaction button
- `form.submit` - Form Submit button

### Lifecycle Events
- `app.start` - App startup
- `app.background` - App moved to background
- `app.foreground` - App returned to foreground (triggers flush)

### Trigger Events
- `ui.freeze` - UI thread blocked for >2s (triggers flush)
- `http.error` - Network error with status code (triggers flush)
- `device.memory.low` - Low memory condition
- `app.crash_recovery` - App restarted after crash (triggers flush)

## Verification Steps

### Before Running Test

```bash
# 1. Build and install debug version
cd examples/demo-app/android
./gradlew installDebug

# 2. Verify app launches
adb shell am start -n io.opentelemetry.android.demo/.MainActivity

# 3. Check logcat shows OpenTelemetry initialization
adb logcat | grep -i "otel\|MobileLogger"

# Expected:
# OpenTelemetry initialized: deviceId=...
```

### During Test

```bash
# Monitor test progress
./monkey-test.sh -a 100 -v

# In another terminal, watch logcat for events
adb logcat | grep -E "Action #|Clicking|Crash|ANR|Export"

# Expected output:
# [INFO] Action #1: Clicking login
# [INFO] Action #5: Background/Foreground transition
# [WARN] Crash button clicked - expecting app to crash
# [INFO] App restarted after crash
```

### After Test

```bash
# Check final flush happened
adb logcat | grep -i "flush"

# Expected:
# ✅ Export successful to https://your-collector-endpoint:4317/v1/logs
```

### In Dash0/Jaeger

1. Go to Dash0 UI: https://app.dash0.com
2. Navigate to **Services** → `otel-mobile-demo`
3. Filter by time range of test
4. Verify you see:
   - Multiple user action events
   - At least one `app.foreground` event (if background transition occurred)
   - Crash recovery events (if crash was triggered)
   - UI freeze events (if freeze button was clicked)

## Troubleshooting

### App Not Found

```bash
# Verify app is installed
adb shell pm list packages | grep otel

# Should show:
# package:io.opentelemetry.android.demo

# If not found, install it:
cd examples/demo-app/android
./gradlew installDebug
```

### Wrong Button Coordinates

Button coordinates are approximate and may need adjustment based on screen resolution.

```bash
# Get your device resolution
adb shell wm size

# If different from 1080x2340, adjust coordinates in monkey-test.sh:
# Edit the BUTTON_COORDS array with correct coordinates

# To find coordinates, enable "Pointer location" in Developer Options
# and manually tap each button to see coordinates
```

### No Device Connected

```bash
# Check device connection
adb devices

# If no device shown:
# 1. Ensure USB debugging is enabled
# 2. Disconnect and reconnect USB cable
# 3. Accept "Allow USB debugging" prompt on device
# 4. Try: adb kill-server && adb start-server
```

### Script Permission Denied

```bash
# Make script executable
chmod +x monkey-test.sh

# Run again
./monkey-test.sh --actions 10
```

## Performance Tips

### For Long Tests
- Use `--actions` limit to prevent infinite running
- Use `--time` limit for time-boxed tests
- Monitor device battery (will drain during test)
- Keep device plugged in for tests >30 minutes

### For Debugging
- Use `--verbose` to see all actions
- Use small action count first: `--actions 10`
- Watch logcat in parallel terminal
- Check export status in app UI status card

### For CI/CD Integration
```bash
# Example: Run 50 actions, fail if script fails
./monkey-test.sh --actions 50 || exit 1

# Capture logs
./monkey-test.sh --actions 50 2>&1 | tee monkey-test.log
```

## Known Limitations

1. **Coordinate-based clicking**: Uses fixed coordinates that may not work on all screen sizes
2. **No UI state validation**: Doesn't verify app is in correct state before clicking
3. **Simple crash detection**: May not detect all crash types
4. **No screenshot capture**: Doesn't capture screen on failure

## Alternative: Android Monkey

For more sophisticated testing, you can also use Android's built-in monkey tool:

```bash
# Run Android monkey (not our custom script)
adb shell monkey -p io.opentelemetry.android.demo --throttle 1000 -v 100

# Benefits:
# - More random behavior
# - No coordinate dependencies
# - Built-in UI fuzzing

# Drawbacks:
# - No crash recovery
# - No weighted button selection
# - No background/foreground transitions
```

## Next Steps

After running monkey test:
1. Review telemetry in Dash0 UI
2. Verify all expected events were captured
3. Check crash recovery worked correctly
4. Validate background/foreground flush triggered
5. Inspect any unexpected behavior in logs

## Contributing

To improve the monkey test:
- Add more sophisticated ANR detection
- Implement UI Automator integration for reliable button finding
- Add screenshot capture on failures
- Add test result summary report
- Support for multiple devices in parallel
