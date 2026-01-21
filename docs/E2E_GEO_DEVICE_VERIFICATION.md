# E2E Verification for Geo/Device Policy Extension

**Purpose**: Verify that geo-based and device-based export policies work correctly end-to-end.

**Prerequisites**:
- OpenTelemetry mobile app running on emulator or device
- OTEL Collector deployed and reachable
- Policy configuration with geo/device rules deployed

---

## Scenario 1: Country-Based Policy (US-Only Flush)

### Policy Configuration

```json
{
  "id": "ui-freeze-us-only",
  "enabled": true,
  "match": {
    "logical_operator": "and",
    "attributes": {
      "event.name": {"equals": "ui.freeze"}
    },
    "geo": {
      "country": ["US"]
    }
  },
  "actions": {
    "flush_window_minutes": 2
  }
}
```

### Test Steps

#### 1.1 Set Device Locale to US

**Emulator**:
```bash
# Stop the emulator if running
adb emu kill

# Start emulator
emulator -avd Pixel_6_API_33 &

# Wait for boot, then set locale to US
adb shell settings put system locales en-US
adb shell setprop persist.sys.locale en-US
adb shell setprop persist.sys.country US

# Verify
adb shell getprop persist.sys.country
```

**Expected Output**: `US`

**Physical Device**:
1. Go to Settings > System > Languages & input > Languages
2. Select "English (United States)"
3. Verify in app logs that `country=US`

#### 1.2 Trigger UI Freeze Event

1. Launch the mobile app
2. Tap "Trigger UI Freeze" button (or simulate 2.5s main thread freeze)
3. Check app logs for policy match

**Expected Logcat Output**:
```
I/PolicyEvaluator: Policy matched: ui-freeze-us-only
I/MobileLogRecordProcessor: Flushing 42 events from last 2 minutes
```

#### 1.3 Verify Flush in Collector

```bash
kubectl logs -n mobile-observability -l app=otel-collector --tail=50 | grep "policy.match_id"
```

**Expected Output**:
```
Attributes:
  - policy.match_id: ui-freeze-us-only
  - policy.matched: true
  - geo.country: US
  - event.name: ui.freeze
```

**Success Criteria**:
- ✅ Policy matched in app logs
- ✅ Events flushed (check count > 0)
- ✅ Collector received logs with `policy.match_id=ui-freeze-us-only`

---

#### 1.4 Test Negative Case (Non-US Country)

**Emulator**:
```bash
# Change locale to Germany
adb shell setprop persist.sys.locale de-DE
adb shell setprop persist.sys.country DE
adb reboot  # May need reboot for full effect

# After reboot, verify
adb shell getprop persist.sys.country
```

**Expected Output**: `DE`

**Physical Device**:
1. Settings > Languages > German (Germany)
2. Verify in app logs: `country=DE`

#### 1.5 Trigger UI Freeze Again

1. Launch app
2. Tap "Trigger UI Freeze"
3. Check app logs

**Expected Logcat Output**:
```
D/PolicyEvaluator: No policy matched (country=DE, expected US)
```

**Collector Logs**:
```bash
kubectl logs -n mobile-observability -l app=otel-collector --tail=50 | grep "ui.freeze"
```

**Expected Output**: No new logs (policy did not match, no flush)

**Success Criteria**:
- ✅ Policy did NOT match in non-US locale
- ✅ No flush occurred
- ✅ Collector did NOT receive ui.freeze logs

---

## Scenario 2: Network-Based Policy (Cellular-Only Flush)

### Policy Configuration

```json
{
  "id": "crash-cellular-reduced",
  "enabled": true,
  "match": {
    "logical_operator": "and",
    "attributes": {
      "event.name": {"equals": "crash_marker"}
    },
    "device": {
      "network": ["cellular"]
    }
  },
  "actions": {
    "flush_window_minutes": 1
  }
}
```

### Test Steps

#### 2.1 Simulate Cellular Network

**Emulator**:
```bash
# Open Extended Controls: Emulator menu > ... (More) > Settings > Cellular
# OR via command line (ADB)

# Disable WiFi
adb shell svc wifi disable

# Enable mobile data
adb shell svc data enable

# Verify network type
adb shell dumpsys connectivity | grep "NetworkAgentInfo"
```

**Expected Output**: Should show `MOBILE` or `CELLULAR` as active network

**Physical Device**:
1. Settings > Network & Internet > WiFi > Turn OFF
2. Settings > Network & Internet > Mobile network > Turn ON
3. Pull down notification shade to verify "Mobile data" is active

#### 2.2 Trigger Crash Marker Event

1. Launch app
2. Tap "Simulate Crash" button (sets crash marker)
3. Restart app (crash recovery flow)
4. Check app logs

**Expected Logcat Output**:
```
I/MobileLoggerProvider: Crash detected on restart
I/PolicyEvaluator: Policy matched: crash-cellular-reduced (network=cellular)
I/MobileLogRecordProcessor: Flushing 15 events from last 1 minute
```

#### 2.3 Verify Flush in Collector

```bash
kubectl logs -n mobile-observability -l app=otel-collector --tail=50 | grep "crash_marker"
```

**Expected Output**:
```
Attributes:
  - policy.match_id: crash-cellular-reduced
  - policy.matched: true
  - device.network: cellular
  - event.name: crash_marker
  - flush_window_minutes: 1
```

**Success Criteria**:
- ✅ Network type detected as `cellular` in app logs
- ✅ Policy matched
- ✅ Smaller flush window (1 minute) used
- ✅ Collector received crash marker with policy metadata

---

#### 2.4 Test Negative Case (WiFi Network)

**Emulator**:
```bash
# Enable WiFi, disable cellular
adb shell svc wifi enable
adb shell svc data disable

# Verify
adb shell dumpsys connectivity | grep "NetworkAgentInfo"
```

**Expected Output**: Should show `WIFI` as active network

**Physical Device**:
1. Settings > WiFi > Turn ON
2. Connect to a WiFi network
3. Verify WiFi icon in status bar

#### 2.5 Trigger Crash Marker on WiFi

1. Simulate crash (set marker)
2. Restart app
3. Check app logs

**Expected Logcat Output**:
```
I/MobileLoggerProvider: Crash detected on restart
D/PolicyEvaluator: No policy matched for crash_marker (network=wifi, expected cellular)
```

**Alternative**: If you have a different policy for WiFi crashes, it should trigger that instead.

**Success Criteria**:
- ✅ Network type detected as `wifi`
- ✅ Cellular-only policy did NOT match
- ✅ Either no flush, or different policy matched

---

## Scenario 3: Battery State Policy (Low Battery Suppress)

### Policy Configuration

```json
{
  "id": "error-low-battery-suppress",
  "enabled": true,
  "match": {
    "logical_operator": "and",
    "attributes": {
      "severity": {"gte": 3.0}
    },
    "device": {
      "battery": ["low"]
    }
  },
  "actions": {
    "flush_window_minutes": 0
  }
}
```

**Note**: `flush_window_minutes: 0` means "match but don't flush" (suppression).

### Test Steps

#### 3.1 Simulate Low Battery

**Emulator**:
```bash
# Set battery level to 10% (low)
adb shell dumpsys battery set level 10

# Unplug (not charging)
adb shell dumpsys battery set status 1  # 1 = unknown (not charging)

# Verify
adb shell dumpsys battery
```

**Expected Output**:
```
  level: 10
  status: 1
```

**Physical Device**:
- Wait for real battery to drop below 15% (impractical for testing)
- OR use developer tools if available

#### 3.2 Trigger Error Event

1. Launch app
2. Tap "Trigger HTTP Error" or log an ERROR-level event
3. Check app logs

**Expected Logcat Output**:
```
I/PolicyEvaluator: Policy matched: error-low-battery-suppress (battery=low)
I/MobileLogRecordProcessor: Suppressing flush (window=0) to save battery
```

**Collector Logs**:
```bash
kubectl logs -n mobile-observability -l app=otel-collector --tail=50 | grep "http.error"
```

**Expected Output**: No new logs (flush suppressed)

**Success Criteria**:
- ✅ Battery state detected as `low`
- ✅ Policy matched
- ✅ Flush was suppressed (window=0)
- ✅ Collector did NOT receive error logs

---

#### 3.3 Test Normal Battery Case

**Emulator**:
```bash
# Set battery to 80% (normal)
adb shell dumpsys battery set level 80

# Set charging
adb shell dumpsys battery set status 2  # 2 = charging

# Verify
adb shell dumpsys battery
```

**Expected Output**:
```
  level: 80
  status: 2 (charging)
```

#### 3.4 Trigger Error Again

1. Tap "Trigger HTTP Error"
2. Check app logs

**Expected Logcat Output**:
```
D/PolicyEvaluator: No low-battery suppression (battery=charging, expected low)
I/MobileLogRecordProcessor: Flushing error events (normal battery policy)
```

**Success Criteria**:
- ✅ Battery state NOT `low`
- ✅ Low-battery suppression policy did NOT match
- ✅ Errors flushed normally

---

## Scenario 4: Timezone Glob Matching

### Policy Configuration

```json
{
  "id": "americas-business-hours",
  "enabled": true,
  "match": {
    "geo": {
      "timezone": ["America/*", "US/*"]
    }
  },
  "actions": {
    "flush_window_minutes": 2
  }
}
```

### Test Steps

#### 4.1 Set Timezone to America/Los_Angeles

**Emulator**:
```bash
# Set timezone
adb shell setprop persist.sys.timezone "America/Los_Angeles"
adb reboot

# After reboot, verify
adb shell getprop persist.sys.timezone
```

**Expected Output**: `America/Los_Angeles`

**Physical Device**:
1. Settings > System > Date & time > Select time zone
2. Choose "Los Angeles" or any "America/*" timezone
3. Verify in app logs

#### 4.2 Trigger Event

1. Launch app
2. Trigger any event
3. Check logs

**Expected Logcat Output**:
```
I/PolicyEvaluator: Policy matched: americas-business-hours (timezone=America/Los_Angeles matches America/*)
```

**Success Criteria**:
- ✅ Timezone glob matched correctly

---

#### 4.3 Test Non-America Timezone

**Emulator**:
```bash
# Set timezone to Asia
adb shell setprop persist.sys.timezone "Asia/Tokyo"
adb reboot
```

#### 4.4 Trigger Event

**Expected Output**: Policy does NOT match (timezone not in America/*)

---

## Scenario 5: Build Channel Matching (Beta-Only)

### Policy Configuration

```json
{
  "id": "beta-full-telemetry",
  "enabled": true,
  "match": {
    "device": {
      "buildChannel": ["beta", "internal"]
    }
  },
  "actions": {
    "flush_window_minutes": 5
  }
}
```

### Test Steps

#### 5.1 Set Build Channel to Beta

In your app's `MainActivity.kt` or wherever you initialize `MobileConfig`:

```kotlin
val config = MobileConfig(
    serviceName = "mobile-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "http://10.0.2.2:8080",
    buildChannel = "beta"  // Set to "beta"
)
```

Rebuild and reinstall app:
```bash
cd android-app
./gradlew installDebug
```

#### 5.2 Trigger Event

1. Launch app
2. Trigger any event
3. Check logs

**Expected Logcat Output**:
```
I/PolicyEvaluator: Policy matched: beta-full-telemetry (buildChannel=beta)
I/MobileLogRecordProcessor: Flushing 120 events from last 5 minutes
```

**Success Criteria**:
- ✅ Build channel `beta` detected
- ✅ Policy matched
- ✅ Larger flush window used (5 minutes)

---

#### 5.3 Test Production Build

Change config to:
```kotlin
buildChannel = "prod"
```

Rebuild, reinstall, test.

**Expected Output**: Policy does NOT match (channel not in [beta, internal])

---

## Scenario 6: Attribute Enrichment Verification

### Enable Attribute Enrichment

```kotlin
val config = MobileConfig(
    serviceName = "mobile-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "http://10.0.2.2:8080",
    attachContextAttributes = true  // Enable enrichment
)
```

### Test Steps

#### 6.1 Trigger Any Event

1. Launch app with enrichment enabled
2. Trigger any event that causes a flush
3. Check collector logs

**Collector Logs**:
```bash
kubectl logs -n mobile-observability -l app=otel-collector --tail=100 | grep -A20 "Attributes"
```

**Expected Output**:
```
Attributes:
  - geo.country: US
  - geo.timezone: America/Los_Angeles
  - device.locale: en-US
  - device.network: wifi
  - device.battery: charging
  - device.class: phone
  - app.version: 1.0.0
  - app.build_channel: beta
  - os.version: 33
  - event.name: ui.freeze
```

**Success Criteria**:
- ✅ All context attributes present
- ✅ No PII (no GPS, device IDs, etc.)
- ✅ Attributes correctly namespaced (`geo.*`, `device.*`, `app.*`)

---

#### 6.2 Disable Enrichment

```kotlin
attachContextAttributes = false
```

Rebuild, trigger event, check collector logs.

**Expected Output**: Context attributes NOT present (only original event attributes)

---

## Debugging Tips

### View App Logs
```bash
adb logcat | grep -E "PolicyEvaluator|MobileLogRecordProcessor|ContextSnapshot"
```

### View Collector Logs
```bash
kubectl logs -n mobile-observability -l app=otel-collector -f
```

### Check Policy Configuration
```bash
# If policies are in ConfigMap
kubectl get configmap -n mobile-observability policy-config -o yaml
```

### Verify Context Snapshot
Add temporary logging in `ContextSnapshotProvider.kt`:
```kotlin
val snapshot = getSnapshot(context, config)
Log.i(TAG, "ContextSnapshot: $snapshot")
```

Expected log:
```
I/ContextSnapshotProvider: ContextSnapshot(
  country=US,
  region=CA,
  timezone=America/Los_Angeles,
  locale=en-US,
  networkType=wifi,
  battery=charging,
  ...
)
```

---

## Quick Verification Summary

| Scenario | Device Setup | Expected Behavior |
|----------|--------------|-------------------|
| US-only policy | Locale = en-US | Policy matches, flush occurs |
| US-only policy | Locale = de-DE | Policy does NOT match |
| Cellular-only | Network = cellular | Policy matches, 1-min window |
| Cellular-only | Network = wifi | Policy does NOT match |
| Low-battery suppress | Battery < 15% | Policy matches, flush suppressed |
| Low-battery suppress | Battery > 15% | Policy does NOT match |
| Americas timezone | Timezone = America/* | Policy matches |
| Americas timezone | Timezone = Asia/* | Policy does NOT match |
| Beta channel | buildChannel = beta | Policy matches, 5-min window |
| Beta channel | buildChannel = prod | Policy does NOT match |
| Enrichment enabled | Any event | Context attributes in collector logs |
| Enrichment disabled | Any event | No context attributes |

---

## Success Criteria for Full Extension

✅ **All geo matching scenarios pass**:
- Country list match
- Timezone glob match
- Locale match
- Region match (best-effort)

✅ **All device matching scenarios pass**:
- Network type match
- Battery state match
- Device class match
- OS version range match
- Build channel match
- App version match

✅ **Backward compatibility**:
- Policies without geo/device still work

✅ **Attribute enrichment**:
- When enabled: context attributes present
- When disabled: no context attributes
- Policy match ID always added when policy triggers

✅ **Privacy**:
- No GPS coordinates in logs
- No device IDs in logs
- No PII in logs
- All attributes use namespaced keys

✅ **Performance**:
- Context snapshot < 1ms to compute
- No network calls during evaluation
- No disk I/O during evaluation

---

**This verification suite ensures the geo/device policy extension works correctly end-to-end!**
