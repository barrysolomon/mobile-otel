# Workflow System Architecture

The Mobile OTEL system implements a **Control Plane → Device** workflow architecture where observability policies are defined in a visual UI and automatically executed on mobile devices.

## Architecture Overview

```
┌──────────────────────┐
│  Control Plane UI    │  (React Flow)
│  Workflow Builder    │  Define triggers & actions
└──────────┬───────────┘
           │ HTTP/REST
           ▼
┌──────────────────────┐
│  Collector/Gateway   │
│  /config endpoint    │  Serves workflow configs
└──────────┬───────────┘
           │ Polling (every 5 min)
           ▼
┌──────────────────────┐
│  Mobile SDK          │
│  PolicyEvaluator     │  Evaluates events → triggers actions
└──────────────────────┘
           │
           ▼
┌──────────────────────┐
│  OTLP Export         │  forceFlush() sends data
└──────────────────────┘
```

## Components

### 1. Control Plane UI - Workflow Builder

**Location**: [control-plane-ui/src/components/WorkflowBuilder.tsx](../control-plane-ui/src/components/WorkflowBuilder.tsx)

Visual workflow builder with 25+ node types across 8 categories:

#### Trigger Nodes
- **Event Triggers**: Event Match, Log Severity, Metric Threshold
- **Performance**: UI Freeze, Slow Operation, Frame Drops
- **Network**: HTTP Error, Network Loss, Slow Request
- **Device Health**: Low Memory, Battery Drain, Thermal Throttling, Low Storage
- **Crash/Error**: Crash Detected, Exception Pattern
- **Predictive**: ML-based Risk Prediction

#### Logic Nodes
- **Any (OR)**: Match if any condition is true
- **All (AND)**: Match if all conditions are true

#### Action Nodes
- **Flush Window**: Export buffered events from last N minutes
- **Set Sampling**: Adjust sampling rate dynamically
- **Annotate Event**: Add trigger annotations
- **Send Alert**: Send notifications
- **Adjust Config**: Change runtime parameters

**Output Format**: Workflows are serialized to DSL/JSON and pushed to devices via the `/config` endpoint.

### 2. Mobile SDK - PolicyEvaluator

**Location**: [otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt](../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt)

Evaluates events against workflow triggers in real-time:

#### Features
- **Polling**: Fetches workflow configs every 5 minutes (configurable)
- **Event Matching**: Evaluates log records against trigger conditions
- **Geo Matching**: Country, region, timezone (privacy-safe, coarse only)
- **Device Matching**: Network type, battery state, OS version, build channel
- **Multi-condition**: AND/OR logical operators
- **Trigger Actions**: Returns `PolicyMatchResult` with flush instructions

#### Example Workflow Config
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
      "country": ["US"],
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

### 3. Mobile SDK - MobileLogRecordProcessor

**Location**: [otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/MobileLogRecordProcessor.kt](../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/MobileLogRecordProcessor.kt)

Buffers events and executes workflow actions:

#### Current Status
⚠️ **Policy evaluation is currently COMMENTED OUT** (lines 86, 138, 147-159)

The architecture supports automatic workflow execution but it's disabled. When enabled:

1. **onEmit()**: Every log record is evaluated against workflows
2. **evaluatePolicies()**: Checks if any workflow triggers match
3. **flushWindow()**: Exports events from the last N minutes when triggered

#### To Enable Automatic Workflows

Uncomment these lines in [MobileLogRecordProcessor.kt](../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/MobileLogRecordProcessor.kt):

```kotlin
// Line 4: Uncomment the import
import io.opentelemetry.android.mobile.policy.PolicyEvaluator

// Line 86: Uncomment the policy evaluator initialization
private val policyEvaluator = PolicyEvaluator(context, config)

// Line 138: Uncomment the policy evaluation call
executor.submit { evaluatePolicies(logRecordData) }

// Lines 147-159: Uncomment the evaluatePolicies method
private fun evaluatePolicies(logRecord: LogRecordData) {
    try {
        val matchResult = policyEvaluator.evaluate(logRecord)
        if (matchResult != null) {
            Log.i(TAG, "Policy matched: ${matchResult.policyId}, flushing window")
            flushWindow(matchResult.flushWindowMinutes)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error evaluating policies", e)
    }
}
```

### 4. Demo App - Manual Triggers

**Location**: [examples/demo-app/android/src/main/java/io/opentelemetry/android/demo/MainActivity.kt](../examples/demo-app/android/src/main/java/io/opentelemetry/android/demo/MainActivity.kt)

Currently, the demo app manually triggers flushes to simulate workflows:

#### Scenario A: UI Freeze (line 241)
```kotlin
logger.logRecordBuilder()
    .setBody("ui.freeze")
    .setSeverity(Severity.WARN)
    .setAllAttributes(
        Attributes.of(
            AttributeKey.longKey("duration_ms"), 2500L,
            // ...
        )
    )
    .emit()

// Manually trigger flush (simulates workflow trigger)
loggerProvider.forceFlush(30)
```

#### Scenario C: HTTP Error (line 364)
```kotlin
logger.logRecordBuilder()
    .setBody("http.error")
    .setSeverity(Severity.ERROR)
    .setAllAttributes(
        Attributes.of(
            AttributeKey.longKey("http.status_code"), 500L,
            // ...
        )
    )
    .emit()

// Manually trigger flush (simulates workflow trigger)
loggerProvider.forceFlush(30)
```

## How It Works End-to-End

### Design Time (Control Plane UI)
1. User drags workflow nodes onto canvas:
   - Trigger: "UI Freeze" with duration > 2000ms
   - Action: "Flush Window" for last 2 minutes
2. Workflow is saved and published to `/config` endpoint
3. All connected devices poll and receive the new workflow

### Runtime (Mobile Device)
1. App logs event: `ui.freeze` with `duration_ms=2500`
2. **MobileLogRecordProcessor** receives the log record
3. **PolicyEvaluator** evaluates the event against all workflows
4. **Match found**: UI freeze workflow triggers
5. **flushWindow(2)** is called: exports last 2 minutes of buffered data
6. Events are sent to collector via OTLP/gRPC

## Export Modes

The workflow system respects the configured [Export Mode](./EXPORT_MODES.md):

### CONDITIONAL Mode (Default - Battery Efficient)
- Traces/Metrics: Buffer indefinitely (no scheduled exports)
- Workflow triggers: Execute flush actions when conditions match
- Manual: `forceFlush()` can be called programmatically

### CONTINUOUS Mode (Development)
- Traces: Export every 30s (configurable)
- Metrics: Export every 60s (configurable)
- Workflow triggers: Still execute (redundant but harmless)

### HYBRID Mode (Balanced)
- Traces: Export every 60s (2x interval)
- Metrics: Export every 120s (2x interval)
- Workflow triggers: Execute flush actions for immediate capture

## Current Implementation Status

| Component | Status | Notes |
|-----------|--------|-------|
| **WorkflowBuilder UI** | ✅ Complete | 25 node types, visual editor |
| **DSL Types** | ✅ Complete | TypeScript & Kotlin models |
| **PolicyEvaluator** | ✅ Complete | Evaluates triggers with geo/device context |
| **MobileLogRecordProcessor** | 🟡 Partial | Architecture complete, policy eval commented out |
| **Demo App** | 🟡 Manual | Uses manual forceFlush() calls |
| **Control Plane Backend** | ❌ TODO | Need `/config` endpoint to serve workflows |

## Recommendations

### For Production Use
1. **Enable PolicyEvaluator**: Uncomment the policy evaluation code in MobileLogRecordProcessor
2. **Set up Control Plane**: Implement `/config` endpoint to serve workflow configs
3. **Use CONDITIONAL mode**: Default export mode for battery efficiency
4. **Monitor triggers**: Add telemetry for trigger frequency and flush success rates

### For Development/Testing
1. **Keep manual triggers**: Current demo app approach is good for testing
2. **Use CONTINUOUS mode**: Get full visibility during development
3. **Create test workflows**: Start with simple triggers before complex logic
4. **Validate DSL**: Ensure UI-generated configs match device expectations

## Example Workflows

### Production: Critical Error Capture
```
┌─────────────────┐
│  HTTP Error     │  status_code >= 500 AND route contains "/api"
│  (Trigger)      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  Flush Window   │  minutes: 5, scope: session
│  (Action)       │
└─────────────────┘
```

### Production: Low Memory Alert
```
┌─────────────────┐
│  Low Memory     │  available_mb < 50
│  (Trigger)      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐     ┌─────────────────┐
│  Flush Window   │────▶│  Send Alert     │
│  (Action)       │     │  (Action)       │
└─────────────────┘     └─────────────────┘
```

### Development: Capture All Crashes
```
┌─────────────────┐
│  Crash Marker   │  (always matches)
│  (Trigger)      │
└────────┬────────┘
         │
         ▼
┌─────────────────┐     ┌─────────────────┐
│  Flush Window   │────▶│  Set Sampling   │
│  minutes: 10    │     │  rate: 100%     │
└─────────────────┘     │  duration: 60m  │
                        └─────────────────┘
```

## Bundled Configuration

Mobile apps can ship with pre-configured workflows in `assets/otel-config.json` that work immediately without network connectivity. This provides:

### Benefits
- **Offline-first**: Apps work without requiring Control Plane access on first launch
- **Environment-specific**: Different configs per build variant (dev, staging, prod)
- **Fallback**: If remote config polling fails, bundled config ensures workflows are active
- **Faster deployment**: Pre-configured workflows included in app releases

### Configuration Priority
```
1. Runtime config (SharedPreferences) - Updated by config polling
2. Bundled config (assets/otel-config.json) - Shipped with app
3. Default values - Hardcoded fallback
```

### Example: Bundled Workflows

**File**: `examples/demo-app/android/src/main/assets/otel-config.json`

```json
{
  "serviceName": "otel-mobile-demo",
  "serviceVersion": "1.0.0",
  "collectorEndpoint": "http://10.0.2.2:4317",
  "exportMode": "CONDITIONAL",
  "workflows": [
    {
      "id": "ui-freeze-detector",
      "name": "UI Freeze Detection",
      "enabled": true,
      "trigger": {
        "all": [
          {
            "event": "ui.freeze",
            "where": [{"attr": "duration_ms", "op": ">", "value": 2000}]
          }
        ]
      },
      "actions": [
        {"type": "flush_window", "minutes": 2, "scope": "session"}
      ]
    },
    {
      "id": "http-error-5xx",
      "name": "HTTP 5xx Error Handler",
      "enabled": true,
      "trigger": {
        "all": [
          {
            "event": "http.error",
            "where": [{"attr": "http.status_code", "op": ">=", "value": 500}]
          }
        ]
      },
      "actions": [
        {"type": "flush_window", "minutes": 5, "scope": "session"},
        {"type": "set_sampling", "rate": 100, "duration_minutes": 10}
      ]
    }
  ]
}
```

### How It Works

**First Launch**:
1. ConfigManager loads `assets/otel-config.json`
2. Parses JSON into MobileConfig (including workflows)
3. Saves to SharedPreferences as runtime config
4. App immediately has workflows active

**Subsequent Launches**:
1. ConfigManager loads from SharedPreferences
2. Workflows may be updated by config polling
3. Bundled config remains as fallback

**Dynamic Updates**:
1. Control Plane pushes new workflows via `/config` endpoint
2. Mobile device polls and receives updates
3. Updates saved to SharedPreferences
4. New workflows override bundled config

### Environment-Specific Configs

Use Gradle build variants to ship different configs:

```gradle
android {
    flavorDimensions "environment"
    productFlavors {
        dev {
            dimension "environment"
            // Uses src/dev/assets/otel-config.json
        }
        prod {
            dimension "environment"
            // Uses src/prod/assets/otel-config.json
        }
    }
}
```

**Dev config** (`src/dev/assets/otel-config.json`):
- CONTINUOUS export mode for full visibility
- Local collector endpoint (10.0.2.2:4317)
- All workflows enabled

**Prod config** (`src/prod/assets/otel-config.json`):
- CONDITIONAL export mode for battery efficiency
- Dash0 endpoint (ingress.us.dash0.com:4317)
- Only critical error workflows enabled

### Security Note

⚠️ **Do NOT bundle auth tokens** in `otel-config.json`. Instead:
1. Use bundled config for endpoint URL and workflows
2. Inject auth tokens at build time via environment variables
3. Or fetch tokens from secure backend on first launch

See [BUNDLED_CONFIG.md](./BUNDLED_CONFIG.md) for security best practices.

---

## Related Documentation

- [Bundled Configuration](./BUNDLED_CONFIG.md) - Complete guide to bundled config system
- [Export Modes](./EXPORT_MODES.md) - CONDITIONAL vs CONTINUOUS vs HYBRID
- [Workflow Builder UI](../control-plane-ui/README_WORKFLOWS.md) - React Flow visual editor
- [Collector Configuration](../control-plane-ui/README_COLLECTOR.md) - Endpoint management UI
- [Policy Evaluator](../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt) - Device-side trigger evaluation
- [MobileConfig](../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/MobileConfig.kt) - Configuration options
- [ConfigManager](../examples/demo-app/android/src/main/java/io/opentelemetry/android/demo/ConfigManager.kt) - Configuration management
