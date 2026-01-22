# Bundled Configuration

Ship mobile apps with pre-configured OTEL settings and workflow policies that work immediately, even before network connectivity is established.

## Overview

**Bundled Configuration** allows you to package a complete OTEL configuration as JSON inside your app's APK. This provides:

- **Offline-First Operation**: App works immediately without network dependency
- **Production-Ready Builds**: Ship apps with correct endpoints and policies
- **Easier Testing**: Developers can test with known configurations
- **Faster Time-to-Value**: Users get observability from first launch
- **Fallback Safety**: If remote config fails, bundled config is used

## How It Works

### Configuration Priority

ConfigManager loads configuration in this priority order:

1. **Runtime Configuration** (Highest Priority)
   - Saved in SharedPreferences
   - Updated via remote config polling or manual changes
   - Persists across app restarts

2. **Bundled Configuration** (Fallback)
   - Shipped with app in `assets/otel-config.json`
   - Loaded on first launch if no runtime config exists
   - Automatically saved as runtime config after first load

3. **Default Values** (Last Resort)
   - Hard-coded defaults in ConfigManager
   - Used only if both bundled and runtime configs are missing

### Loading Flow

```
App Starts
    ↓
ConfigManager.loadConfig()
    ↓
Check SharedPreferences ───┐
    ↓ (empty)              │ (exists)
Load assets/otel-config.json  │
    ↓                      │
Save to SharedPreferences  │
    ↓                      │
Return config ←────────────┘
```

## Creating Bundled Configuration

### 1. Create Configuration File

Create `android/src/main/assets/otel-config.json`:

```json
{
  "serviceName": "my-mobile-app",
  "serviceVersion": "1.2.0",
  "collectorEndpoint": "https://ingress.us.dash0.com:4317",
  "exportMode": "CONDITIONAL",
  "traceExportIntervalSeconds": 30,
  "metricExportIntervalSeconds": 60,
  "ramBufferSize": 5000,
  "diskBufferMb": 50,
  "diskBufferTtlHours": 24,
  "exportTimeoutSeconds": 30,
  "configPollIntervalSeconds": 300,
  "maxExportRetries": 3,
  "attachContextAttributes": false,
  "buildChannel": "prod",
  "headers": {
    "Authorization": "Bearer YOUR_DASH0_TOKEN",
    "Dash0-Dataset": "mobile-prod"
  },
  "workflows": [
    {
      "id": "ui-freeze-detector",
      "name": "UI Freeze Detection",
      "enabled": true,
      "description": "Detects UI freezes > 2s and flushes last 2 minutes",
      "trigger": {
        "all": [
          {
            "event": "ui.freeze",
            "where": [
              {
                "attr": "duration_ms",
                "op": ">",
                "value": 2000
              }
            ]
          }
        ]
      },
      "actions": [
        {
          "type": "flush_window",
          "minutes": 2,
          "scope": "session"
        }
      ]
    }
  ]
}
```

### 2. Generate from Control Plane UI

The easiest way to create bundled config is to export from the Control Plane UI:

1. Open Control Plane UI
2. Go to **Configuration** → **📡 Collector Endpoints**
3. Configure your endpoint (Dash0 or custom)
4. Add workflows in **Workflow Builder** tab
5. Click **📥 Export for Mobile App**
6. Save as `otel-config.json` in `android/src/main/assets/`

### 3. Environment-Specific Configs

Use Android build variants to ship different configs:

**Directory Structure**:
```
android/src/
├── main/assets/
│   └── otel-config.json          # Default/debug config
├── debug/assets/
│   └── otel-config.json          # Debug-specific
├── staging/assets/
│   └── otel-config.json          # Staging-specific
└── release/assets/
    └── otel-config.json          # Production config
```

**Example: Debug Config**:
```json
{
  "serviceName": "my-app",
  "serviceVersion": "1.2.0-debug",
  "collectorEndpoint": "http://10.0.2.2:4317",
  "exportMode": "CONTINUOUS",
  "buildChannel": "debug"
}
```

**Example: Production Config**:
```json
{
  "serviceName": "my-app",
  "serviceVersion": "1.2.0",
  "collectorEndpoint": "https://ingress.us.dash0.com:4317",
  "exportMode": "CONDITIONAL",
  "buildChannel": "prod",
  "headers": {
    "Authorization": "Bearer PROD_TOKEN",
    "Dash0-Dataset": "mobile-prod"
  }
}
```

## Configuration Format

### Required Fields

```json
{
  "serviceName": "string",
  "serviceVersion": "string",
  "collectorEndpoint": "string"
}
```

### Optional Fields

All other fields are optional and will use defaults if omitted:

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `exportMode` | String | `"CONDITIONAL"` | Export behavior: CONDITIONAL, CONTINUOUS, HYBRID |
| `traceExportIntervalSeconds` | Long | `30` | Trace export interval (CONTINUOUS mode) |
| `metricExportIntervalSeconds` | Long | `60` | Metric export interval (CONTINUOUS mode) |
| `ramBufferSize` | Int | `5000` | RAM buffer capacity (events) |
| `diskBufferMb` | Int | `50` | Disk buffer capacity (MB) |
| `diskBufferTtlHours` | Int | `24` | Event retention (hours) |
| `exportTimeoutSeconds` | Long | `30` | Network timeout |
| `configPollIntervalSeconds` | Long | `300` | Remote config poll interval |
| `maxExportRetries` | Int | `3` | Retry attempts on failure |
| `attachContextAttributes` | Boolean | `false` | Include geo/device context |
| `buildChannel` | String | `"unknown"` | Build channel: prod/beta/debug |
| `headers` | Object | `null` | HTTP headers for collector |
| `workflows` | Array | `[]` | Workflow policies (optional) |

### Headers

Optional HTTP headers sent with telemetry:

```json
{
  "headers": {
    "Authorization": "Bearer YOUR_TOKEN",
    "Dash0-Dataset": "mobile-prod",
    "X-Custom-Header": "value"
  }
}
```

### Workflows

Optional workflow policies for conditional export:

```json
{
  "workflows": [
    {
      "id": "unique-id",
      "name": "Display Name",
      "enabled": true,
      "description": "What this workflow does",
      "trigger": {
        "all": [
          {
            "event": "event.name",
            "where": [
              {
                "attr": "attribute_name",
                "op": ">",
                "value": 1000
              }
            ]
          }
        ]
      },
      "actions": [
        {
          "type": "flush_window",
          "minutes": 2,
          "scope": "session"
        }
      ]
    }
  ]
}
```

See [Workflow System](./WORKFLOW_SYSTEM.md) for complete workflow documentation.

## Usage in Android

### Automatic Loading

ConfigManager automatically loads bundled config on first launch:

```kotlin
// MainActivity.kt
val config = ConfigManager.loadConfig(this)
val loggerProvider = MobileLoggerProvider.getInstance(this, config)
```

That's it! No additional code needed.

### Manual Loading from JSON

Load configuration from external JSON (e.g., downloaded from server):

```kotlin
val jsonString = """
{
  "serviceName": "my-app",
  "serviceVersion": "1.0.0",
  "collectorEndpoint": "https://collector.example.com:4317"
}
"""

val success = ConfigManager.loadFromJson(context, jsonString)
if (success) {
    // Restart MobileLoggerProvider to apply new config
    loggerProvider.shutdown()
    loggerProvider = MobileLoggerProvider.getInstance(context, ConfigManager.loadConfig(context))
}
```

### Checking Configuration Source

```kotlin
val prefs = context.getSharedPreferences("otel_config", Context.MODE_PRIVATE)
val hasLoadedConfig = prefs.getBoolean("config_loaded_from_bundle", false)

if (hasLoadedConfig) {
    Log.i("Config", "Using bundled or runtime configuration")
} else {
    Log.i("Config", "Using default configuration")
}
```

## Best Practices

### Security

**❌ DO NOT** commit production tokens to version control:

```json
{
  "headers": {
    "Authorization": "Bearer prod_token_here"  // ❌ BAD
  }
}
```

**✅ DO** use build-time injection:

```kotlin
// build.gradle.kts
android {
    buildTypes {
        release {
            buildConfigField("String", "DASH0_TOKEN", "\"${System.getenv("DASH0_TOKEN")}\"")
        }
    }
}

// Runtime
val headers = if (BuildConfig.DASH0_TOKEN.isNotEmpty()) {
    mapOf("Authorization" to "Bearer ${BuildConfig.DASH0_TOKEN}")
} else {
    null
}
```

### Multi-Environment Setup

**Recommended Structure**:
```
android/src/
├── debug/assets/
│   └── otel-config.json       # localhost, CONTINUOUS mode
├── staging/assets/
│   └── otel-config.json       # staging.dash0.com, HYBRID mode
└── release/assets/
    └── otel-config.json       # production.dash0.com, CONDITIONAL mode
```

### Configuration Updates

**Initial Launch**:
1. Bundled config loaded from assets
2. Saved to SharedPreferences
3. Used until remote config available

**Subsequent Launches**:
1. Runtime config loaded from SharedPreferences
2. Optionally poll remote config for updates
3. Update runtime config if remote config newer

### Testing

**Verify bundled config loads**:
```kotlin
@Test
fun testBundledConfigLoads() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext

    // Clear any existing config
    context.getSharedPreferences("otel_config", Context.MODE_PRIVATE)
        .edit().clear().commit()

    // Load should use bundled config
    val config = ConfigManager.loadConfig(context)

    assertNotNull(config)
    assertEquals("my-mobile-app", config.serviceName)
    assertEquals("https://collector.example.com:4317", config.collectorEndpoint)
}
```

## Troubleshooting

### Config Not Loading

**Check file location**:
```bash
# File must be in assets directory
android/src/main/assets/otel-config.json
```

**Check JSON validity**:
```bash
# Validate JSON syntax
cat android/src/main/assets/otel-config.json | jq .
```

**Check logs**:
```kotlin
adb logcat | grep ConfigManager
```

### Using Wrong Configuration

**Priority Issue**: If runtime config exists, bundled config is ignored.

**Solution**: Clear app data to force bundled config reload:
```bash
adb shell pm clear com.your.package
```

Or programmatically:
```kotlin
ConfigManager.resetToDefaults(context)
```

### Configuration Size Limits

**APK Size Impact**: Each config file adds to APK size.

- Typical config: ~2-5 KB
- With workflows: ~10-20 KB
- Negligible impact on modern APK sizes (50-100 MB)

**Optimization**: Minify JSON in production builds:
```json
{"serviceName":"app","serviceVersion":"1.0","collectorEndpoint":"https://collector:4317","exportMode":"CONDITIONAL"}
```

## Example Scenarios

### Scenario 1: Local Development

**Goal**: Test with local OTEL collector

**Config**:
```json
{
  "serviceName": "my-app",
  "serviceVersion": "dev",
  "collectorEndpoint": "http://10.0.2.2:4317",
  "exportMode": "CONTINUOUS",
  "buildChannel": "debug"
}
```

### Scenario 2: Production with Dash0

**Goal**: Ship production app with Dash0 integration

**Config**:
```json
{
  "serviceName": "my-app",
  "serviceVersion": "1.2.0",
  "collectorEndpoint": "https://ingress.us.dash0.com:4317",
  "exportMode": "CONDITIONAL",
  "buildChannel": "prod",
  "headers": {
    "Authorization": "Bearer ${DASH0_TOKEN}",
    "Dash0-Dataset": "mobile-prod"
  }
}
```

**Note**: Use environment variable `${DASH0_TOKEN}` and inject at build time.

### Scenario 3: Multi-Region

**Goal**: Route telemetry based on user region

**Approach**: Use multiple config files and select at runtime:

```kotlin
val region = getUserRegion() // "US" or "EU"
val configFile = when (region) {
    "US" -> "otel-config-us.json"
    "EU" -> "otel-config-eu.json"
    else -> "otel-config.json"
}

val json = context.assets.open(configFile).bufferedReader().use { it.readText() }
ConfigManager.loadFromJson(context, json)
```

## Related Documentation

- [Export Modes](./EXPORT_MODES.md) - CONDITIONAL vs CONTINUOUS vs HYBRID
- [Workflow System](./WORKFLOW_SYSTEM.md) - Workflow triggers and actions
- [MobileConfig](../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/MobileConfig.kt) - Configuration class
- [ConfigManager](../examples/demo-app/android/src/main/java/io/opentelemetry/android/demo/ConfigManager.kt) - Implementation
