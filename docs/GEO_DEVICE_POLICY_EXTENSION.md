# Geo & Device Context Policy Extension

**Status**: Active Extension
**Version**: 0.4.0-alpha
**Last Updated**: 2026-01-21

---

## Summary

This extension adds **geo-based** and **device-context-based** match conditions to export policies, allowing mobile apps to make smarter bandwidth and observability decisions based on where and how the app is running.

**Key Principles**:
- **Privacy-first**: No GPS coordinates, no PII, coarse location only
- **Client-side only**: All evaluation happens on-device
- **OTEL-compatible**: Uses standard OTEL SDK APIs and attribute conventions
- **Additive**: Extends existing policy system without breaking changes

---

## What OTEL Provides vs What We Add

| Capability | OTEL Provides | This Extension Adds |
|------------|---------------|---------------------|
| **Policy matching** | Attribute-based conditions (equals, gt, contains, etc.) | ✅ Geo match (country, timezone, locale) |
| **Policy matching** | Logical operators (AND, OR) | ✅ Device match (network, battery, OS version) |
| **Context propagation** | Trace/span context | ✅ Device context snapshot (non-PII only) |
| **Attribute enrichment** | Manual via SDK | ✅ Optional auto-enrichment (config-controlled) |
| **Privacy** | Developers control what's logged | ✅ Built-in privacy safeguards (no GPS, no PII) |
| **Geo data** | ❌ Not provided | ✅ Coarse location (country/region/timezone) |
| **Device state** | ❌ Not provided | ✅ Network type, battery state, device class |

**What We Do NOT Change**:
- ❌ No new telemetry formats (still OTLP/gRPC logs)
- ❌ No custom OTEL protocols
- ❌ No new semantic conventions (all attributes namespaced: `device.*`, `geo.*`, `app.*`)
- ❌ No backend processing requirements (optional collector-side annotation)

---

## Policy Schema Changes

### Before (Attribute-Only Matching)

```json
{
  "id": "ui-freeze",
  "enabled": true,
  "match": {
    "logical_operator": "and",
    "attributes": {
      "event.name": {"equals": "ui.freeze"},
      "duration_ms": {"gt": 2000.0}
    }
  },
  "actions": {
    "flush_window_minutes": 2
  }
}
```

### After (With Geo & Device Matching)

```json
{
  "id": "ui-freeze-us-only",
  "enabled": true,
  "match": {
    "logical_operator": "and",
    "attributes": {
      "event.name": {"equals": "ui.freeze"},
      "duration_ms": {"gt": 2000.0}
    },
    "geo": {
      "country": ["US", "CA"],
      "timezone": ["America/*"]
    },
    "device": {
      "network": ["cellular"],
      "battery": ["normal", "charging"]
    }
  },
  "actions": {
    "flush_window_minutes": 2
  }
}
```

**Backward Compatibility**: Policies without `geo` or `device` still work as before.

---

## Privacy Notes (OTEP-Style)

This extension follows OpenTelemetry's privacy-by-default principles:

### What We Collect

**Geo (Coarse Only)**:
- `country` - ISO 3166-1 alpha-2 (e.g., "US", "GB") via `Locale.getDefault().country`
- `region` - Best-effort state/province (e.g., "CA", "Ontario") via carrier or timezone inference
- `timezone` - IANA timezone (e.g., "America/Los_Angeles") via `TimeZone.getDefault().id`
- `locale` - BCP-47 language tag (e.g., "en-US") via `Locale.getDefault().toLanguageTag()`

**Device (Non-PII)**:
- `app.version` - From `BuildConfig.VERSION_NAME`
- `os.version` - Android SDK_INT (e.g., 33 = Android 13)
- `device.class` - Heuristic: phone/tablet/unknown (via screen size)
- `network.connection.type` - wifi/cellular/offline/unknown (via `ConnectivityManager`)
- `battery.state` - charging/low/normal/unknown (via `BatteryManager`)
- `build.channel` - Developer-provided: prod/beta/internal/unknown

### What We Do NOT Collect

- ❌ **No GPS coordinates** (no latitude/longitude)
- ❌ **No precise location** (no city/street/postal code)
- ❌ **No device identifiers** (no IMEI, Android ID, Advertising ID)
- ❌ **No user identifiers** (no phone number, email, name)
- ❌ **No network details** (no IP address, SSID, carrier name)
- ❌ **No persistent tracking** (context snapshot is ephemeral)

### Privacy Safeguards

1. **Coarse-only geo**: Country/timezone are public information (IP-based geoIP gives same data)
2. **No persistence**: ContextSnapshot is computed on-demand, never stored raw
3. **Config-controlled enrichment**: Attributes only attached if `attachContextAttributes: true`
4. **Namespaced attributes**: All non-OTEL attributes use `device.*`, `geo.*`, `app.*` prefixes
5. **Local evaluation**: All policy matching happens client-side (no server round-trips)

---

## Attribute Naming Guidance

Since OpenTelemetry does not (yet) define semantic conventions for mobile device context, we namespace all attributes to avoid collisions:

| Attribute Key | Type | Example Value | Description |
|---------------|------|---------------|-------------|
| `geo.country` | string | "US" | ISO 3166-1 alpha-2 country code |
| `geo.region` | string | "CA" | State/province (best-effort) |
| `geo.timezone` | string | "America/Los_Angeles" | IANA timezone |
| `device.locale` | string | "en-US" | BCP-47 language tag |
| `network.connection.type` | string | "wifi" | wifi \| cellular \| offline \| unknown |
| `device.battery` | string | "charging" | charging \| low \| normal \| unknown |
| `device.class` | string | "phone" | phone \| tablet \| unknown |
| `app.version` | string | "1.2.3" | Application version |
| `app.build_channel` | string | "prod" | prod \| beta \| internal \| unknown |
| `os.version` | int | 33 | Android SDK_INT |
| `policy.match_id` | string | "ui-freeze-us" | Policy ID that triggered flush |
| `policy.matched` | boolean | true | Indicates policy-triggered flush |

**Future OTEL Integration**: If/when OTEL defines mobile semantic conventions, we can migrate to standard names via a deprecation cycle.

---

## Example Policies

### Example 1: US-Only UI Freeze Flush

**Use Case**: Only send detailed telemetry for UI freezes in the US market (other markets use lower sampling).

```json
{
  "id": "ui-freeze-us-only",
  "enabled": true,
  "match": {
    "logical_operator": "and",
    "attributes": {
      "event.name": {"equals": "ui.freeze"},
      "duration_ms": {"gt": 2000.0}
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

**Behavior**:
- In the US: Flush last 2 minutes of events when UI freeze > 2s
- Outside the US: No flush (fall back to background sampling)

---

### Example 2: Cellular-Only Crash Context Flush

**Use Case**: On cellular networks (metered data), send crash context with a smaller window to save bandwidth.

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

**Behavior**:
- On cellular: Flush only last 1 minute (bandwidth-conscious)
- On WiFi: Use default crash policy (5 minutes)

---

### Example 3: Low-Battery Suppress Flush

**Use Case**: When battery is low, reduce telemetry to preserve battery life.

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

**Behavior**:
- When battery low: Don't flush errors (save battery)
- When battery normal/charging: Use default error policy

**Note**: `flush_window_minutes: 0` means "match but don't flush" (effectively a suppression rule).

---

### Example 4: Beta Channel Enhanced Logging

**Use Case**: Internal beta testers get full telemetry; production users get sampled telemetry.

```json
{
  "id": "beta-full-telemetry",
  "enabled": true,
  "match": {
    "logical_operator": "or",
    "device": {
      "build_channel": ["beta", "internal"]
    }
  },
  "actions": {
    "flush_window_minutes": 5
  }
}
```

**Behavior**:
- Beta/internal builds: Flush all events every 5 minutes
- Production builds: No match (use default policies)

---

### Example 5: Regional Timezone Matching

**Use Case**: Match users in specific timezone regions (e.g., all of Americas, all of Europe).

```json
{
  "id": "americas-business-hours",
  "enabled": true,
  "match": {
    "logical_operator": "and",
    "attributes": {
      "event.name": {"equals": "transaction.complete"}
    },
    "geo": {
      "timezone": ["America/*", "US/*"]
    }
  },
  "actions": {
    "flush_window_minutes": 2
  }
}
```

**Behavior**:
- Matches any timezone starting with "America/" or "US/" (glob pattern)
- Enables region-specific observability campaigns

---

## Implementation Details

### ContextSnapshot Structure

```kotlin
data class ContextSnapshot(
    // Geo (coarse)
    val country: String,        // e.g., "US"
    val region: String?,        // e.g., "CA" (best-effort, nullable)
    val timezone: String,       // e.g., "America/Los_Angeles"
    val locale: String,         // e.g., "en-US"

    // Device (non-PII)
    val appVersion: String,     // e.g., "1.2.3"
    val osVersion: Int,         // e.g., 33 (SDK_INT)
    val deviceClass: String,    // "phone" | "tablet" | "unknown"
    val networkType: String,    // "wifi" | "cellular" | "offline" | "unknown"
    val batteryState: String,   // "charging" | "low" | "normal" | "unknown"
    val buildChannel: String    // "prod" | "beta" | "internal" | "unknown"
)
```

### How ContextSnapshot is Generated

```kotlin
// Called once per policy evaluation (lightweight, no I/O)
val context = ContextSnapshotProvider.getSnapshot(androidContext, config)

// Provider implementation
object ContextSnapshotProvider {
    fun getSnapshot(context: Context, config: MobileConfig): ContextSnapshot {
        return ContextSnapshot(
            country = Locale.getDefault().country,
            region = inferRegion(context),
            timezone = TimeZone.getDefault().id,
            locale = Locale.getDefault().toLanguageTag(),
            appVersion = BuildConfig.VERSION_NAME,
            osVersion = Build.VERSION.SDK_INT,
            deviceClass = inferDeviceClass(context),
            networkType = getNetworkType(context),
            batteryState = getBatteryState(context),
            buildChannel = config.buildChannel
        )
    }
}
```

**Performance**:
- No network calls
- No disk I/O
- < 1ms to compute
- Safe to call on every policy evaluation

---

## Policy Evaluation Integration

### Extended Match Logic

```kotlin
// PolicyEvaluator.kt - updated matchesPolicy()
private fun matchesPolicy(
    logRecord: LogRecordData,
    context: ContextSnapshot,
    policy: Policy
): Boolean {
    // 1. Check attribute conditions (existing)
    val attributeMatch = matchAttributes(logRecord, policy.match.attributes)

    // 2. Check geo conditions (new)
    val geoMatch = matchGeo(context, policy.match.geo)

    // 3. Check device conditions (new)
    val deviceMatch = matchDevice(context, policy.match.device)

    // 4. Combine with logical operator
    return when (policy.match.logicalOperator) {
        "and" -> attributeMatch && geoMatch && deviceMatch
        "or" -> attributeMatch || geoMatch || deviceMatch
        else -> false
    }
}
```

### Geo Matching Implementation

```kotlin
private fun matchGeo(context: ContextSnapshot, geo: GeoMatch?): Boolean {
    if (geo == null) return true  // No geo constraint = always match

    var matches = true

    // Country list match
    if (geo.country != null && geo.country.isNotEmpty()) {
        matches = matches && context.country in geo.country
    }

    // Region list match
    if (geo.region != null && geo.region.isNotEmpty()) {
        matches = matches && context.region in geo.region
    }

    // Timezone glob match
    if (geo.timezone != null && geo.timezone.isNotEmpty()) {
        val timezoneMatches = geo.timezone.any { pattern ->
            matchGlob(context.timezone, pattern)
        }
        matches = matches && timezoneMatches
    }

    // Locale match
    if (geo.locale != null && geo.locale.isNotEmpty()) {
        matches = matches && context.locale in geo.locale
    }

    return matches
}

private fun matchGlob(value: String, pattern: String): Boolean {
    // Simple glob: "America/*" matches "America/Los_Angeles"
    if (pattern.endsWith("/*")) {
        val prefix = pattern.removeSuffix("/*")
        return value.startsWith(prefix + "/")
    }
    return value == pattern
}
```

### Device Matching Implementation

```kotlin
private fun matchDevice(context: ContextSnapshot, device: DeviceMatch?): Boolean {
    if (device == null) return true  // No device constraint = always match

    var matches = true

    // Network type list match
    if (device.network != null && device.network.isNotEmpty()) {
        matches = matches && context.networkType in device.network
    }

    // Battery state list match
    if (device.battery != null && device.battery.isNotEmpty()) {
        matches = matches && context.batteryState in device.battery
    }

    // Device class list match
    if (device.deviceClass != null && device.deviceClass.isNotEmpty()) {
        matches = matches && context.deviceClass in device.deviceClass
    }

    // OS version comparison
    if (device.osVersionMin != null) {
        matches = matches && context.osVersion >= device.osVersionMin
    }
    if (device.osVersionMax != null) {
        matches = matches && context.osVersion <= device.osVersionMax
    }

    // App version string comparison (best-effort)
    if (device.appVersion != null && device.appVersion.isNotEmpty()) {
        matches = matches && context.appVersion in device.appVersion
    }

    // Build channel match
    if (device.buildChannel != null && device.buildChannel.isNotEmpty()) {
        matches = matches && context.buildChannel in device.buildChannel
    }

    return matches
}
```

---

## OTEL Attribute Enrichment (Optional)

### Configuration

```kotlin
val config = MobileConfig(
    serviceName = "my-app",
    collectorEndpoint = "https://otel-collector:4317",
    attachContextAttributes = true  // NEW: Default false
)
```

### Behavior When Enabled

When `attachContextAttributes: true`, context attributes are automatically added to **every exported log record**:

```kotlin
// Before export (in MobileLogRecordProcessor)
if (config.attachContextAttributes) {
    logRecord.attributes.put("geo.country", context.country)
    logRecord.attributes.put("geo.timezone", context.timezone)
    logRecord.attributes.put("device.network", context.networkType)
    logRecord.attributes.put("device.battery", context.batteryState)
    // ... (all context fields)
}
```

### Policy Match Annotation

When a policy triggers a flush, add debug attributes:

```kotlin
// After policy match
logRecord.attributes.put("policy.match_id", policyId)
logRecord.attributes.put("policy.matched", true)
```

**Use Case**: Helps debug policy behavior in the collector logs.

---

## Limitations & Future Work

### Current Limitations

1. **Region inference is best-effort**: Android doesn't provide a standard "state/province" API. We infer from timezone or carrier info (nullable field).

2. **App version comparison is string-based**: Numeric comparison (e.g., "1.2.3" > "1.2.2") is not implemented. Use list match for now.

3. **No city-level geo**: Intentionally excluded for privacy. Country/timezone is the finest granularity.

4. **No A/B test cohort matching**: Not in scope for this extension (use dedicated feature flag system).

### Future Enhancements

1. **Semantic version comparison**: Implement proper semver comparison for `app.version`

2. **OTEL semantic conventions**: When OTEL defines mobile conventions, migrate to standard names

3. **Dynamic context refresh**: Currently, context is computed once per evaluation. Could cache with TTL.

4. **Collector-side policy evaluation**: Optional server-side variant for centralized policy management

---

## Testing Recommendations

### Unit Tests

See [`PolicyEvaluatorGeoDeviceTest.kt`](../otel-android-mobile/src/test/java/io/opentelemetry/android/mobile/policy/PolicyEvaluatorGeoDeviceTest.kt) for full test coverage:

- ✅ Country list matching
- ✅ Timezone glob matching
- ✅ Network type matching
- ✅ Battery state matching
- ✅ OS version range matching
- ✅ Build channel matching
- ✅ Backward compatibility (policies without geo/device)
- ✅ Attribute enrichment flag

### Integration Tests

1. **Emulator locale/timezone simulation**:
   - Settings > System > Date & time > Select time zone
   - Settings > System > Languages & input > Languages
   - Verify policy matches correctly

2. **Network type simulation**:
   - Emulator: Extended controls > Cellular > Network type
   - Physical device: Toggle WiFi/mobile data
   - Verify cellular-only policies trigger

3. **Battery state simulation**:
   - `adb shell dumpsys battery set level 10` (low battery)
   - `adb shell dumpsys battery set status 2` (charging)
   - Verify battery-based policies trigger

### E2E Verification

1. **US-only policy**: Set device locale to US, trigger event, verify flush in collector logs
2. **Cellular-only policy**: Disable WiFi, trigger event, verify flush with smaller window
3. **Policy debug attributes**: Enable `attachContextAttributes`, verify `policy.match_id` in logs

---

## Migration Guide

### For Existing Users

**No action required.** All existing policies without `geo` or `device` fields continue to work as before.

### To Add Geo/Device Matching

1. Update policy JSON to include `geo` and/or `device` fields
2. Deploy updated policy configuration to collector
3. No app code changes needed (library handles automatically)

### To Enable Attribute Enrichment

```kotlin
// Before
val config = MobileConfig(
    serviceName = "my-app",
    collectorEndpoint = "..."
)

// After
val config = MobileConfig(
    serviceName = "my-app",
    collectorEndpoint = "...",
    attachContextAttributes = true  // NEW
)
```

---

## References

- [OTEL Semantic Conventions](https://opentelemetry.io/docs/specs/semconv/) - Standard attribute names
- [OTEP Process](../WHAT_IS_OTEP.md) - How to propose upstream changes
- [Privacy Best Practices](https://opentelemetry.io/docs/specs/otel/logs/data-model/#privacy) - OTEL privacy guidelines
- [Android Location APIs](https://developer.android.com/training/location) - Why we avoid GPS
- [Offline Resilience Guide](./guides/OFFLINE_RESILIENCE.md) - Crash recovery and network loss handling

---

**This is an additive extension. The core system remains OTEL-native and unchanged.**
