# Workflow Builder - Control Plane UI

Visual workflow editor for defining mobile observability triggers and actions using React Flow.

## Overview

The Workflow Builder allows you to create visual workflows that control when and how mobile telemetry data is captured and exported. Workflows are defined once in the UI and automatically executed on all connected mobile devices.

## Architecture

```
┌──────────────────────────────────────────────┐
│  WorkflowBuilder (React Flow Canvas)        │
│                                              │
│  Drag & Drop Nodes:                         │
│  ┌──────────┐   ┌──────────┐   ┌─────────┐│
│  │ Trigger  │──▶│  Logic   │──▶│ Action  ││
│  └──────────┘   └──────────┘   └─────────┘│
│                                              │
└──────────────────┬───────────────────────────┘
                   │
                   ▼ Serialize to DSL/JSON
┌──────────────────────────────────────────────┐
│  {                                           │
│    "id": "workflow-1",                       │
│    "trigger": {...},                         │
│    "actions": [{...}]                        │
│  }                                           │
└──────────────────┬───────────────────────────┘
                   │
                   ▼ Push to devices
┌──────────────────────────────────────────────┐
│  Mobile SDK - PolicyEvaluator                │
│  Evaluates events → Executes actions         │
└──────────────────────────────────────────────┘
```

## Node Types

### 1. Event Triggers

Detect specific events in the telemetry stream:

#### **Event Match** 🎯
Match events by name with optional attribute predicates.

**Example**: Capture UI freezes
```
Event Name: ui.freeze
Predicates:
  - duration_ms > 2000
  - screen == "MainActivity"
```

#### **Log Severity Match** 📋
Match logs by severity level.

**Example**: Capture all errors
```
Min Severity: ERROR
Body Contains: "exception"
```

#### **Metric Threshold** 📊
Trigger on metric threshold violations.

**Example**: High memory usage
```
Metric Name: process.runtime.jvm.memory.usage
Operator: >
Threshold: 0.9
```

### 2. Performance Triggers

Detect performance issues:

#### **UI Freeze** ❄️
Detect UI thread blocking (ANRs).

**Configuration**:
- Duration (ms): Minimum freeze duration

**Example**: `duration_ms: 2000` (2 second freeze)

#### **Slow Operation** 🐌
Detect operations exceeding threshold.

**Configuration**:
- Operation Name: Operation to monitor
- Threshold (ms): Maximum acceptable duration

**Example**:
```
Operation: database.query
Threshold: 1000ms
```

#### **Frame Drops** 🎬
Detect dropped frames indicating janky UI.

**Configuration**:
- Dropped Frames: Number of frames dropped
- Window (ms): Time window for measurement

**Example**: `30 frames dropped in 1000ms`

### 3. Network Triggers

Detect network issues:

#### **HTTP Error** 🌐
Match HTTP error status codes.

**Configuration**:
- Status Min: Minimum status code (e.g., 500)
- Route Contains: Filter by route pattern

**Example**:
```
Status Min: 500
Route: /api/appointments
```

#### **Network Loss** 📡
Detect network disconnection.

**Configuration**:
- Consecutive Failures: Number of failed requests

**Example**: `3 consecutive failures`

#### **Slow Request** ⏱️
Detect slow HTTP requests.

**Configuration**:
- Threshold (ms): Maximum acceptable duration
- Route: Optional route filter

**Example**:
```
Threshold: 3000ms
Route: /api/*
```

### 4. Device Health Triggers

Monitor device resource state:

#### **Low Memory** 💾
Detect low available memory.

**Configuration**:
- Available (MB): Minimum acceptable memory

**Example**: `Available < 50MB`

#### **Battery Drain** 🔋
Detect rapid battery discharge.

**Configuration**:
- Drain Rate (%/min): Maximum acceptable drain rate

**Example**: `> 1% per minute`

#### **Thermal Throttling** 🌡️
Detect device overheating.

**Configuration**:
- Min Level: LIGHT | MODERATE | SEVERE | CRITICAL

**Example**: `Level >= MODERATE`

#### **Low Storage** 💿
Detect low available storage.

**Configuration**:
- Available (MB): Minimum acceptable storage

**Example**: `Available < 100MB`

### 5. Crash/Error Triggers

Detect crashes and exceptions:

#### **Crash Detected** 💥
Trigger on app crash (detected after restart).

**Configuration**: None (always matches crash events)

#### **Exception Pattern** ⚠️
Match specific exception types.

**Configuration**:
- Exception Type: Java/Kotlin exception class name
- Message Pattern: Optional regex pattern for message

**Example**:
```
Type: java.lang.NullPointerException
Message: ".*UserProfile.*"
```

### 6. Predictive Triggers

ML-based risk prediction:

#### **Predictive Risk** 🔮
Trigger on predicted future issues.

**Configuration**:
- Risk Type: crash | network_loss | performance_degradation | battery_drain
- Min Score: Minimum confidence (0.0 - 1.0)

**Example**:
```
Risk Type: crash
Min Score: 0.7 (70% confidence)
```

### 7. Logic Nodes

Combine multiple conditions:

#### **Any (OR)** 🔀
Match if **any** connected condition is true.

**Use Case**: Capture multiple error types
```
HTTP Error (5xx) OR Exception Pattern OR Crash
```

#### **All (AND)** 🔗
Match if **all** connected conditions are true.

**Use Case**: Specific scenario targeting
```
UI Freeze AND Low Memory AND Network: Cellular
```

### 8. Action Nodes

Define what happens when triggers fire:

#### **Flush Window** 📤
Export buffered events from last N minutes.

**Configuration**:
- Minutes: Lookback window (1-60)
- Scope: session | device

**Example**:
```
Minutes: 2
Scope: session
```

**Effect**: Exports logs, traces, and metrics from the last 2 minutes.

#### **Set Sampling** 🎲
Dynamically adjust sampling rate.

**Configuration**:
- Rate: Sampling percentage (0-100)
- Duration (minutes): How long to apply

**Example**:
```
Rate: 100%
Duration: 10 minutes
```

**Use Case**: Increase sampling after detecting an error.

#### **Annotate Event** 🏷️
Add trigger annotation to events.

**Configuration**:
- Trigger ID: Unique identifier
- Reason: Human-readable description

**Example**:
```
Trigger ID: ui-freeze-us-prod
Reason: "UI freeze detected on US production devices"
```

#### **Send Alert** 🚨
Send notification to alerting channels.

**Configuration**:
- Severity: info | warning | critical
- Message: Alert message
- Channels: email, slack, pagerduty

**Example**:
```
Severity: critical
Message: "Crash detected on {device_id}"
Channels: [pagerduty, slack]
```

#### **Adjust Config** ⚙️
Change runtime configuration dynamically.

**Configuration**:
- Parameter: Config key to change
- Value: New value
- Duration (minutes): How long to apply (0 = permanent)

**Example**:
```
Parameter: log_level
Value: DEBUG
Duration: 30 minutes
```

## Example Workflows

### Workflow 1: Capture Critical HTTP Errors

```
┌────────────────────┐
│  HTTP Error        │  status >= 500 AND route contains "/api"
│  Trigger           │
└──────────┬─────────┘
           │
           ▼
┌────────────────────┐
│  Flush Window      │  minutes: 5, scope: session
│  Action            │
└────────────────────┘
```

**DSL Output**:
```json
{
  "id": "critical-http-errors",
  "enabled": true,
  "trigger": {
    "all": [
      {
        "event": "http.error",
        "where": [
          {"attr": "status_code", "op": ">=", "value": 500},
          {"attr": "route", "op": "contains", "value": "/api"}
        ]
      }
    ]
  },
  "actions": [
    {
      "type": "flush_window",
      "minutes": 5,
      "scope": "session"
    }
  ]
}
```

### Workflow 2: Low Memory + UI Freeze (Complex)

```
┌────────────────────┐     ┌────────────────────┐
│  Low Memory        │────▶│  All (AND)         │
│  available < 50MB  │     │  Logic             │
└────────────────────┘     └──────┬─────────────┘
                                   │
┌────────────────────┐            │
│  UI Freeze         │────────────┘
│  duration > 2000ms │
└────────────────────┘
           │
           ▼
┌────────────────────┐     ┌────────────────────┐
│  Flush Window      │────▶│  Send Alert        │
│  minutes: 2        │     │  severity: critical│
└────────────────────┘     └────────────────────┘
```

**DSL Output**:
```json
{
  "id": "low-memory-freeze",
  "enabled": true,
  "trigger": {
    "all": [
      {
        "event": "device.memory.low",
        "where": [{"attr": "available_mb", "op": "<", "value": 50}]
      },
      {
        "event": "ui.freeze",
        "where": [{"attr": "duration_ms", "op": ">", "value": 2000}]
      }
    ]
  },
  "actions": [
    {
      "type": "flush_window",
      "minutes": 2,
      "scope": "session"
    },
    {
      "type": "send_alert",
      "severity": "critical",
      "message": "Low memory UI freeze detected",
      "channels": ["pagerduty", "slack"]
    }
  ]
}
```

### Workflow 3: Crash with Increased Sampling

```
┌────────────────────┐
│  Crash Detected    │  (after app restart)
│  Trigger           │
└──────────┬─────────┘
           │
           ▼
┌────────────────────┐     ┌────────────────────┐
│  Flush Window      │────▶│  Set Sampling      │
│  minutes: 10       │     │  rate: 100%        │
│  scope: device     │     │  duration: 60min   │
└────────────────────┘     └────────────────────┘
```

**Use Case**: After a crash, flush the last 10 minutes and increase sampling for the next hour to capture detailed post-crash behavior.

## How to Use

### 1. Start the Dev Server

```bash
cd control-plane-ui
npm install
npm run dev
```

Open http://localhost:5173

### 2. Build a Workflow

1. **Drag trigger node** from palette to canvas (e.g., "HTTP Error")
2. **Configure trigger** by clicking the node and editing properties
3. **Add logic nodes** (optional) for complex conditions
4. **Add action nodes** (e.g., "Flush Window")
5. **Connect nodes** by dragging from source to target handle
6. **Save workflow** (serializes to DSL/JSON)

### 3. Publish Workflow

The workflow JSON needs to be served from a `/config` endpoint that mobile devices can poll.

**Example `/config` response**:
```json
{
  "version": 1,
  "workflows": [
    {
      "id": "workflow-1",
      "enabled": true,
      "trigger": {...},
      "actions": [...]
    }
  ]
}
```

### 4. Test on Device

Mobile devices will:
1. Poll `/config` endpoint every 5 minutes
2. Evaluate events against workflow triggers
3. Execute actions when conditions match
4. Send telemetry to collector via OTLP/gRPC

## Integration with Mobile SDK

The workflows you create in the UI are automatically executed on mobile devices through the [PolicyEvaluator](../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt).

### Current Status
⚠️ **Note**: Policy evaluation is currently commented out in [MobileLogRecordProcessor.kt](../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/MobileLogRecordProcessor.kt). See [WORKFLOW_SYSTEM.md](../docs/WORKFLOW_SYSTEM.md) for details on enabling automatic workflow execution.

### Manual Testing (Demo App)

The [demo app](../examples/demo-app/android/src/main/java/io/opentelemetry/android/demo/MainActivity.kt) currently simulates workflows by manually calling `forceFlush()` after logging trigger events:

```kotlin
// Scenario A: UI Freeze
logger.logRecordBuilder()
    .setBody("ui.freeze")
    .setSeverity(Severity.WARN)
    .setAllAttributes(Attributes.of(
        AttributeKey.longKey("duration_ms"), 2500L
    ))
    .emit()

// Manually trigger flush (simulates workflow action)
loggerProvider.forceFlush(30)
```

This demonstrates the expected behavior once PolicyEvaluator is enabled.

## Best Practices

### Battery Efficiency
- Use specific triggers (avoid "match all" policies)
- Set appropriate flush windows (2-5 minutes typical)
- Combine with [CONDITIONAL export mode](../docs/EXPORT_MODES.md)
- Test trigger frequency in production before rollout

### Targeting
- Use geo/device conditions to limit scope
- Example: Only flush on cellular networks in specific regions
- Example: Only capture on beta/internal builds

### Action Ordering
- Place most critical actions first (flush before alert)
- Use Set Sampling to increase capture after errors
- Annotate events for easier debugging

### Testing
1. Start with disabled workflows
2. Test trigger matching with mock events
3. Gradually enable for beta/internal builds
4. Monitor trigger frequency and bandwidth impact
5. Roll out to production incrementally

## Related Files

- [workflow.ts](src/types/workflow.ts) - TypeScript type definitions
- [WorkflowBuilder.tsx](src/components/WorkflowBuilder.tsx) - React Flow component
- [TriggerNode.tsx](src/components/nodes/TriggerNode.tsx) - Trigger node renderer
- [ActionNode.tsx](src/components/nodes/ActionNode.tsx) - Action node renderer
- [PolicyEvaluator.kt](../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt) - Device-side evaluator

## Related Documentation

- [Workflow System Architecture](../docs/WORKFLOW_SYSTEM.md) - End-to-end system overview
- [Export Modes](../docs/EXPORT_MODES.md) - CONDITIONAL vs CONTINUOUS vs HYBRID
- [MobileConfig](../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/MobileConfig.kt) - SDK configuration
