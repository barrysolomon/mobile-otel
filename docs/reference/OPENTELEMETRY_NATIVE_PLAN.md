# OpenTelemetry Native Migration Plan

Complete plan to transform the Mobile Observability Demo into a fully OpenTelemetry-native system suitable for contribution to the OpenTelemetry project.

## 🎯 Goal

Transform from **custom demo** to **OpenTelemetry-native implementation** that can be contributed to:
- `opentelemetry-android` (Android SDK enhancements)
- `opentelemetry-collector-contrib` (Custom processor)
- Reference implementation demo

## 🎨 What OpenTelemetry Provides vs What We Add

### OpenTelemetry Core (Used As-Is)

**Data Model & APIs:**
- `LogRecordData`, `Attributes`, `Resource` - Standard data structures
- `Logger.logRecordBuilder()` - Standard logging API
- `LogRecordProcessor` interface - Extension point for custom processing
- `LogRecordExporter` interface - Extension point for export logic
- OTLP/gRPC protocol - Wire format for data transmission

**SDK Components:**
- `BatchLogRecordProcessor` - Time/size-based batching
- `OtlpGrpcLogRecordExporter` - Standard OTLP export
- Semantic conventions - Standard attribute names

**Collector:**
- OTLP receivers (gRPC/HTTP)
- Standard processors (batch, memory_limiter, attributes)
- Standard exporters (OTLP, Loki, Prometheus)

### Mobile-Specific Extensions (What We Add)

**Android Library:**
- **Two-Tier Buffer** - RAM + Disk persistence for offline resilience
- **Export Policies** - DSL for conditional/selective flush (bandwidth optimization)
- **Retry Logic** - Exponential backoff for unreliable mobile networks
- **Crash Recovery** - Disk buffer survives app crashes and restarts
- **Manual Flush API** - User control for critical operations

**Collector Processor:**
- **Policy Evaluation** - Runtime conditional logic for mobile events
- **Log Annotation** - Add policy metadata for downstream processing
- **Mobile-Specific Actions** - Annotate, sample based on mobile context

### Key Terminology

| Old Term | New Term | Reason |
|----------|----------|--------|
| "Workflow" | **"Export Policy"** | Clearer that this controls when/how data is exported |
| "Trigger" | **"Policy Match"** | More aligned with OTEL processor terminology |
| "Replay" | **"Flush"** | Standard OTEL terminology for forcing export |
| "Gateway" | **"Collector"** | Use standard OTEL terminology |

---

## 📋 Current State Analysis

### What We Have (Custom Implementation - Deprecated)

```
Old Architecture (Removed):
┌─────────────────────────────────────────────────────────┐
│ Android App                                             │
│  • Custom JSON event format                             │
│  • Custom captureEvent() API                            │
│  • Ring buffer (good, but not OTEL-integrated)          │
│  • HTTP/JSON to custom Gateway                          │
└─────────────────────────────────────────────────────────┘
                        │ HTTP/JSON
                        ▼
┌─────────────────────────────────────────────────────────┐
│ Custom Gateway (Go)                                     │
│  • Receives custom JSON                                 │
│  • Converts to OTEL Logs                                │
│  • Custom workflow management                           │
│  • Exports OTLP/gRPC                                    │
└─────────────────────────────────────────────────────────┘
```

### What We Have Now (OTEL Native - Implemented)

```
Current Architecture:
┌─────────────────────────────────────────────────────────┐
│ Android App (OTEL SDK)                                  │
│  • OpenTelemetry Android SDK (official)                 │
│  • OTEL Logger API (logRecordBuilder())                │
│  • MobileLogRecordProcessor (implements LogRecordProcessor) │
│  • OTLP/gRPC directly to Collector                     │
└─────────────────────────────────────────────────────────┘
                        │ OTLP/gRPC
                        ▼
┌─────────────────────────────────────────────────────────┐
│ OTEL Collector with Mobile Processor                   │
│  • Standard OTEL Collector                              │
│  • mobilepolicyprocessor (export policy evaluation)    │
│  • Standard OTEL config format                         │
└─────────────────────────────────────────────────────────┘
                        │ OTLP/gRPC
                        ▼
┌─────────────────────────────────────────────────────────┐
│ Backends (Loki, Prometheus, etc)                       │
└─────────────────────────────────────────────────────────┘
```

## 📊 Gap Analysis

| Component | Current State | Target State | Effort | Priority |
|-----------|---------------|--------------|--------|----------|
| Android SDK | Custom JSON API | OTEL Logger API | High | P0 |
| Android Export | HTTP/JSON | OTLP/gRPC | Medium | P0 |
| Ring Buffer | Custom impl | OTEL-integrated | Medium | P1 |
| Gateway | Custom Go service | OTEL Collector processor | High | P0 |
| Workflow DSL | Custom JSON | OTEL config format | Medium | P1 |
| Control Plane UI | Custom React | Keep as reference | Low | P2 |
| Collector Config | Standard | Standard + processor | Low | P0 |

## 🗺️ Migration Phases

### Phase 1: Foundation (Week 1-2)
**Goal:** Set up OTEL-native base without breaking existing functionality

### Phase 2: Android Migration (Week 2-3)
**Goal:** Replace custom Android SDK with OpenTelemetry Android SDK

### Phase 3: Collector Processor (Week 3-4)
**Goal:** Convert Gateway to OTEL Collector processor

### Phase 4: Integration & Testing (Week 4-5)
**Goal:** End-to-end testing with OTEL-native stack

### Phase 5: Documentation & Contribution (Week 5-6)
**Goal:** Prepare for OpenTelemetry contribution

---

## Phase 1: Foundation (Week 1-2)

### 1.1 Project Restructuring

**Create new directory structure:**
```
mobile-observability-otel/
├── otel-android-mobile/              # Contrib library
│   ├── README.md
│   ├── build.gradle.kts
│   ├── src/main/java/io/opentelemetry/android/mobile/
│   │   ├── buffering/
│   │   │   ├── MobileLogRecordProcessor.kt
│   │   │   ├── RingBufferExporter.kt
│   │   │   └── EvictionPolicy.kt
│   │   ├── policy/
│   │   │   ├── ConditionalExportProcessor.kt
│   │   │   ├── PolicyEvaluator.kt
│   │   │   └── TriggerCondition.kt
│   │   └── config/
│   │       ├── MobileConfig.kt
│   │       └── PolicyFetcher.kt
│   └── src/test/
│
├── collector-processor/              # OTEL Collector contrib
│   ├── mobilepolicyprocessor/
│   │   ├── factory.go
│   │   ├── processor.go
│   │   ├── config.go
│   │   ├── policy_evaluator.go
│   │   └── README.md
│   └── mobilepolicyprocessor_test.go
│
├── examples/
│   ├── demo-app/                     # Reference implementation
│   │   ├── android/
│   │   ├── k8s/
│   │   └── control-plane-ui/
│   └── docs/
│
├── docs/
│   ├── OTEPs/
│   │   ├── OTEP-mobile-buffering.md
│   │   └── OTEP-conditional-export.md
│   ├── design/
│   └── tutorials/
│
└── README.md
```

**Tasks:**
- [ ] Create new repository structure
- [ ] Set up build system for Android library (Gradle)
- [ ] Set up build system for Collector processor (Go modules)
- [ ] Move existing code to `examples/demo-app/`
- [ ] Create LICENSE files (Apache 2.0)
- [ ] Create CONTRIBUTING.md
- [ ] Create CODE_OF_CONDUCT.md (OTEL template)

**Deliverables:**
- New repo structure
- Build systems configured
- Existing demo preserved as example

---

## Phase 2: Android Migration (Week 2-3)

### 2.1 Add OpenTelemetry Android SDK

**Update `otel-android-mobile/build.gradle.kts`:**
```kotlin
dependencies {
    // OpenTelemetry SDK
    api("io.opentelemetry:opentelemetry-api:1.34.1")
    api("io.opentelemetry:opentelemetry-sdk:1.34.1")
    api("io.opentelemetry.android:instrumentation:0.4.0-alpha")

    // Existing dependencies
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // OTLP Exporter
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.34.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

### 2.2 Create OTEL-Native Mobile Logger Provider

**File: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/MobileLoggerProvider.kt`**

```kotlin
package io.opentelemetry.android.mobile

import android.content.Context
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.semconv.ResourceAttributes

class MobileLoggerProvider private constructor(
    private val context: Context,
    private val config: MobileConfig
) {
    private lateinit var openTelemetry: OpenTelemetry
    private lateinit var logger: Logger

    fun initialize() {
        val resource = Resource.builder()
            .put(ResourceAttributes.SERVICE_NAME, config.serviceName)
            .put(ResourceAttributes.SERVICE_VERSION, config.serviceVersion)
            .put(ResourceAttributes.DEVICE_ID, getDeviceId())
            .build()

        val otlpExporter = OtlpGrpcLogRecordExporter.builder()
            .setEndpoint(config.collectorEndpoint)
            .build()

        // Custom processor with ring buffer
        val mobileProcessor = MobileLogRecordProcessor.builder()
            .setExporter(otlpExporter)
            .setRamBufferSize(config.ramBufferSize)
            .setDiskBufferSize(config.diskBufferMb)
            .setContext(context)
            .build()

        val loggerProvider = SdkLoggerProvider.builder()
            .setResource(resource)
            .addLogRecordProcessor(mobileProcessor)
            .build()

        openTelemetry = OpenTelemetrySdk.builder()
            .setLoggerProvider(loggerProvider)
            .build()

        logger = openTelemetry.logsBridge.get("mobile-observability")
    }

    fun getLogger(): Logger = logger

    companion object {
        @Volatile
        private var instance: MobileLoggerProvider? = null

        fun getInstance(context: Context, config: MobileConfig): MobileLoggerProvider {
            return instance ?: synchronized(this) {
                instance ?: MobileLoggerProvider(context, config).also {
                    it.initialize()
                    instance = it
                }
            }
        }
    }
}
```

### 2.3 Create Mobile-Specific LogRecordProcessor

**File: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/MobileLogRecordProcessor.kt`**

```kotlin
package io.opentelemetry.android.mobile.buffering

import android.content.Context
import io.opentelemetry.sdk.logs.LogRecordProcessor
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.context.Context as OtelContext
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class MobileLogRecordProcessor private constructor(
    private val exporter: LogRecordExporter,
    private val ramBufferSize: Int,
    private val diskBufferMb: Long,
    private val context: Context,
    private val policyEvaluator: PolicyEvaluator?
) : LogRecordProcessor {

    private val ramBuffer = ConcurrentLinkedQueue<LogRecordData>()
    private val diskBuffer = DiskLogBuffer(context, diskBufferMb)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isShutdown = AtomicBoolean(false)

    override fun onEmit(context: OtelContext, logRecord: LogRecordData) {
        if (isShutdown.get()) return

        // Add to RAM buffer
        ramBuffer.offer(logRecord)

        // Evaluate policy if configured
        policyEvaluator?.let { evaluator ->
            if (evaluator.shouldFlush(logRecord)) {
                scope.launch {
                    flushBuffer()
                }
            }
        }

        // Auto-flush to disk if RAM buffer full
        if (ramBuffer.size >= ramBufferSize) {
            scope.launch {
                flushRamToDisk()
            }
        }
    }

    private suspend fun flushRamToDisk() {
        val records = mutableListOf<LogRecordData>()
        while (ramBuffer.isNotEmpty() && records.size < 1000) {
            ramBuffer.poll()?.let { records.add(it) }
        }
        diskBuffer.write(records)
    }

    private suspend fun flushBuffer() {
        // Flush RAM to disk first
        flushRamToDisk()

        // Get all records from disk
        val records = diskBuffer.readAll()

        // Export via OTLP
        exporter.export(records)

        // Clear disk buffer after successful export
        diskBuffer.clear()
    }

    override fun shutdown(): CompletableResultCode {
        if (isShutdown.compareAndSet(false, true)) {
            scope.cancel()
            diskBuffer.close()
            return exporter.shutdown()
        }
        return CompletableResultCode.ofSuccess()
    }

    override fun forceFlush(): CompletableResultCode {
        scope.launch {
            flushBuffer()
        }
        return CompletableResultCode.ofSuccess()
    }

    class Builder {
        private var exporter: LogRecordExporter? = null
        private var ramBufferSize: Int = 5000
        private var diskBufferMb: Long = 50
        private var context: Context? = null
        private var policyEvaluator: PolicyEvaluator? = null

        fun setExporter(exporter: LogRecordExporter) = apply { this.exporter = exporter }
        fun setRamBufferSize(size: Int) = apply { this.ramBufferSize = size }
        fun setDiskBufferSize(mb: Long) = apply { this.diskBufferMb = mb }
        fun setContext(context: Context) = apply { this.context = context }
        fun setPolicyEvaluator(evaluator: PolicyEvaluator) = apply { this.policyEvaluator = evaluator }

        fun build(): MobileLogRecordProcessor {
            return MobileLogRecordProcessor(
                requireNotNull(exporter) { "Exporter must be set" },
                ramBufferSize,
                diskBufferMb,
                requireNotNull(context) { "Context must be set" },
                policyEvaluator
            )
        }
    }

    companion object {
        fun builder() = Builder()
    }
}
```

### 2.4 Migrate Demo App to Use OTEL SDK

**File: `examples/demo-app/android/src/main/java/MobileObservabilitySDK.kt`**

```kotlin
package com.example.demo

import android.content.Context
import io.opentelemetry.android.mobile.MobileLoggerProvider
import io.opentelemetry.android.mobile.MobileConfig
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes

class MobileObservabilitySDK private constructor(
    context: Context,
    collectorEndpoint: String
) {
    private val logger: Logger
    private val demoRunId = "run-${System.currentTimeMillis()}"

    init {
        val config = MobileConfig(
            serviceName = "mobile-demo-app",
            serviceVersion = "1.0.0",
            collectorEndpoint = collectorEndpoint,
            ramBufferSize = 5000,
            diskBufferMb = 50
        )

        val provider = MobileLoggerProvider.getInstance(context, config)
        logger = provider.getLogger()

        // Log initialization
        logger.logRecordBuilder()
            .setBody("SDK initialized")
            .setSeverity(Severity.INFO)
            .setAttribute(AttributeKey.stringKey("demo_run_id"), demoRunId)
            .emit()
    }

    fun captureEvent(eventName: String, attributes: Map<String, Any> = emptyMap()) {
        val otelAttributes = Attributes.builder()
            .put("demo_run_id", demoRunId)
            .apply {
                attributes.forEach { (key, value) ->
                    when (value) {
                        is String -> put(key, value)
                        is Long -> put(key, value)
                        is Int -> put(key, value.toLong())
                        is Double -> put(key, value)
                        is Boolean -> put(key, value)
                        else -> put(key, value.toString())
                    }
                }
            }
            .build()

        logger.logRecordBuilder()
            .setBody(eventName)
            .setSeverity(Severity.INFO)
            .setAllAttributes(otelAttributes)
            .emit()
    }

    companion object {
        @Volatile
        private var instance: MobileObservabilitySDK? = null

        fun initialize(context: Context, collectorEndpoint: String): MobileObservabilitySDK {
            return instance ?: synchronized(this) {
                instance ?: MobileObservabilitySDK(context, collectorEndpoint).also {
                    instance = it
                }
            }
        }

        fun getInstance(): MobileObservabilitySDK {
            return requireNotNull(instance) { "SDK not initialized" }
        }
    }
}
```

**Tasks:**
- [ ] Implement `MobileLoggerProvider`
- [ ] Implement `MobileLogRecordProcessor` with ring buffer
- [ ] Implement `DiskLogBuffer` using Room
- [ ] Implement `PolicyEvaluator` (basic version)
- [ ] Update demo app to use OTEL SDK
- [ ] Write unit tests for all components
- [ ] Test with Android emulator

**Deliverables:**
- OTEL-native Android library
- Demo app using OTEL SDK
- Ring buffer integrated with OTEL
- Tests passing

---

## Phase 3: Collector Processor (Week 3-4)

### 3.1 Create OTEL Collector Processor

**File: `collector-processor/mobilepolicyprocessor/config.go`**

```go
package mobilepolicyprocessor

import (
    "go.opentelemetry.io/collector/component"
)

type Config struct {
    // Policies defines workflow policies
    Policies []Policy `mapstructure:"policies"`
}

type Policy struct {
    ID      string    `mapstructure:"id"`
    Enabled bool      `mapstructure:"enabled"`
    Match   Match     `mapstructure:"match"`
    Actions []Action  `mapstructure:"actions"`
}

type Match struct {
    Attributes map[string]AttributeMatcher `mapstructure:"attributes"`
    // LogicalOperator: "and" or "or"
    LogicalOperator string `mapstructure:"logical_operator"`
}

type AttributeMatcher struct {
    Equals              *string  `mapstructure:"equals"`
    NotEquals           *string  `mapstructure:"not_equals"`
    GreaterThan         *float64 `mapstructure:"gt"`
    GreaterThanOrEqual  *float64 `mapstructure:"gte"`
    LessThan            *float64 `mapstructure:"lt"`
    LessThanOrEqual     *float64 `mapstructure:"lte"`
    Contains            *string  `mapstructure:"contains"`
    Regex               *string  `mapstructure:"regex"`
}

type Action struct {
    Type       string                 `mapstructure:"type"`
    Parameters map[string]interface{} `mapstructure:"parameters"`
}

func (cfg *Config) Validate() error {
    // Validation logic
    return nil
}
```

**File: `collector-processor/mobilepolicyprocessor/processor.go`**

```go
package mobilepolicyprocessor

import (
    "context"
    "go.opentelemetry.io/collector/pdata/plog"
    "go.uber.org/zap"
)

type mobilePolicyProcessor struct {
    config *Config
    logger *zap.Logger
}

func newMobilePolicyProcessor(config *Config, logger *zap.Logger) *mobilePolicyProcessor {
    return &mobilePolicyProcessor{
        config: config,
        logger: logger,
    }
}

func (p *mobilePolicyProcessor) processLogs(ctx context.Context, ld plog.Logs) (plog.Logs, error) {
    // Iterate through logs
    for i := 0; i < ld.ResourceLogs().Len(); i++ {
        rlogs := ld.ResourceLogs().At(i)
        for j := 0; j < rlogs.ScopeLogs().Len(); j++ {
            slogs := rlogs.ScopeLogs().At(j)
            for k := 0; k < slogs.LogRecords().Len(); k++ {
                logRecord := slogs.LogRecords().At(k)

                // Evaluate policies
                for _, policy := range p.config.Policies {
                    if !policy.Enabled {
                        continue
                    }

                    if p.matchesPolicy(logRecord, policy) {
                        p.logger.Info("Policy matched",
                            zap.String("policy_id", policy.ID),
                            zap.String("log_body", logRecord.Body().AsString()))

                        // Execute actions
                        p.executeActions(logRecord, policy.Actions)
                    }
                }
            }
        }
    }

    return ld, nil
}

func (p *mobilePolicyProcessor) matchesPolicy(logRecord plog.LogRecord, policy Policy) bool {
    // Match logic
    attributes := logRecord.Attributes()

    matches := make([]bool, 0, len(policy.Match.Attributes))

    for attrKey, matcher := range policy.Match.Attributes {
        attrValue, exists := attributes.Get(attrKey)
        if !exists {
            matches = append(matches, false)
            continue
        }

        matches = append(matches, p.matchAttribute(attrValue, matcher))
    }

    // Apply logical operator
    if policy.Match.LogicalOperator == "or" {
        for _, match := range matches {
            if match {
                return true
            }
        }
        return false
    } else { // "and" or default
        for _, match := range matches {
            if !match {
                return false
            }
        }
        return len(matches) > 0
    }
}

func (p *mobilePolicyProcessor) matchAttribute(value pcommon.Value, matcher AttributeMatcher) bool {
    // Matching logic for different types
    switch value.Type() {
    case pcommon.ValueTypeStr:
        strValue := value.Str()
        if matcher.Equals != nil {
            return strValue == *matcher.Equals
        }
        if matcher.Contains != nil {
            return strings.Contains(strValue, *matcher.Contains)
        }
        // ... other matchers
    case pcommon.ValueTypeInt:
        intValue := float64(value.Int())
        if matcher.GreaterThan != nil {
            return intValue > *matcher.GreaterThan
        }
        // ... other matchers
    case pcommon.ValueTypeDouble:
        doubleValue := value.Double()
        if matcher.GreaterThan != nil {
            return doubleValue > *matcher.GreaterThan
        }
        // ... other matchers
    }
    return false
}

func (p *mobilePolicyProcessor) executeActions(logRecord plog.LogRecord, actions []Action) {
    for _, action := range actions {
        switch action.Type {
        case "annotate":
            // Add annotation attributes
            if triggerID, ok := action.Parameters["trigger_id"].(string); ok {
                logRecord.Attributes().PutStr("policy.trigger_id", triggerID)
            }
        case "sample":
            // Set sampling decision
            if rate, ok := action.Parameters["rate"].(float64); ok {
                logRecord.Attributes().PutDouble("sampling.rate", rate)
            }
        }
    }
}
```

**File: `collector-processor/mobilepolicyprocessor/factory.go`**

```go
package mobilepolicyprocessor

import (
    "context"
    "go.opentelemetry.io/collector/component"
    "go.opentelemetry.io/collector/consumer"
    "go.opentelemetry.io/collector/processor"
    "go.opentelemetry.io/collector/processor/processorhelper"
)

const (
    typeStr = "mobilepolicy"
    stability = component.StabilityLevelAlpha
)

func NewFactory() processor.Factory {
    return processor.NewFactory(
        typeStr,
        createDefaultConfig,
        processor.WithLogs(createLogsProcessor, stability),
    )
}

func createDefaultConfig() component.Config {
    return &Config{
        Policies: []Policy{},
    }
}

func createLogsProcessor(
    ctx context.Context,
    set processor.CreateSettings,
    cfg component.Config,
    nextConsumer consumer.Logs,
) (processor.Logs, error) {
    pCfg := cfg.(*Config)

    mp := newMobilePolicyProcessor(pCfg, set.Logger)

    return processorhelper.NewLogsProcessor(
        ctx,
        set,
        cfg,
        nextConsumer,
        mp.processLogs,
        processorhelper.WithCapabilities(consumer.Capabilities{MutatesData: true}),
    )
}
```

### 3.2 Collector Configuration

**File: `examples/demo-app/k8s/otel-collector-config.yaml`**

```yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318

processors:
  memory_limiter:
    limit_mib: 512
    check_interval: 1s

  batch:
    timeout: 10s
    send_batch_size: 1000

  # NEW: Mobile policy processor
  mobilepolicy:
    policies:
      - id: ui-freeze-handler
        enabled: true
        match:
          logical_operator: and
          attributes:
            event.name:
              equals: "ui.freeze"
            duration_ms:
              gt: 2000.0
        actions:
          - type: annotate
            parameters:
              trigger_id: "ui-freeze-handler"
              reason: "UI freeze detected"
          - type: sample
            parameters:
              rate: 1.0
              duration_minutes: 10

      - id: crash-recovery
        enabled: true
        match:
          logical_operator: and
          attributes:
            event.name:
              equals: "crash_marker"
        actions:
          - type: annotate
            parameters:
              trigger_id: "crash-recovery"
              reason: "Crash detected"

      - id: network-error-spike
        enabled: true
        match:
          logical_operator: and
          attributes:
            status_code:
              gte: 500.0
            route:
              contains: "/appointments"
        actions:
          - type: annotate
            parameters:
              trigger_id: "network-error-spike"
          - type: sample
            parameters:
              rate: 1.0
              duration_minutes: 10

exporters:
  debug:
    verbosity: detailed

  logging:
    verbosity: detailed

service:
  pipelines:
    logs:
      receivers: [otlp]
      processors: [memory_limiter, mobilepolicy, batch]
      exporters: [debug, logging]
```

### 3.3 Build Custom Collector

**File: `collector-processor/builder-config.yaml`**

```yaml
dist:
  name: otelcol-mobile
  description: OpenTelemetry Collector with Mobile Policy Processor
  output_path: ./dist

receivers:
  - gomod: go.opentelemetry.io/collector/receiver/otlpreceiver v0.91.0

processors:
  - gomod: go.opentelemetry.io/collector/processor/batchprocessor v0.91.0
  - gomod: go.opentelemetry.io/collector/processor/memorylimiterprocessor v0.91.0
  - gomod: ./mobilepolicyprocessor  # Our custom processor

exporters:
  - gomod: go.opentelemetry.io/collector/exporter/debugexporter v0.91.0
  - gomod: go.opentelemetry.io/collector/exporter/loggingexporter v0.91.0
  - gomod: go.opentelemetry.io/collector/exporter/otlpexporter v0.91.0

extensions:
  - gomod: go.opentelemetry.io/collector/extension/zpagesextension v0.91.0
```

**Build script: `collector-processor/build.sh`**

```bash
#!/bin/bash
set -e

# Install builder
go install go.opentelemetry.io/collector/cmd/builder@latest

# Build custom collector
builder --config=builder-config.yaml

echo "Custom collector built: ./dist/otelcol-mobile"
```

**Tasks:**
- [ ] Implement processor config
- [ ] Implement processor logic
- [ ] Implement policy matching
- [ ] Implement factory
- [ ] Write processor tests
- [ ] Create collector builder config
- [ ] Build custom collector binary
- [ ] Test with demo data

**Deliverables:**
- OTEL Collector processor
- Custom collector binary
- Configuration examples
- Tests passing

---

## Phase 4: Integration & Testing (Week 4-5)

### 4.1 End-to-End Integration

**Architecture verification:**
```
Android App (OTEL SDK)
    │ OTLP/gRPC
    │ LogRecords with standard OTEL format
    ▼
OTEL Collector with mobilepolicyprocessor
    │ Policy evaluation in Collector
    │ Standard OTEL pipelines
    ▼
Backends (Loki, Prometheus, Debug)
```

### 4.2 Test Scenarios

**Test 1: UI Freeze Detection (OTEL Native)**
```kotlin
// Android App
logger.logRecordBuilder()
    .setBody("ui.freeze")
    .setSeverity(Severity.WARN)
    .setAttribute("duration_ms", 3500L)
    .setAttribute("screen", "MainActivity")
    .setAttribute("demo_run_id", demoRunId)
    .emit()

// Expected: Collector applies ui-freeze-handler policy
// Expected: Logs annotated with trigger_id
// Expected: Visible in collector output
```

**Test 2: Crash Recovery (OTEL Native)**
```kotlin
// On crash detection
logger.logRecordBuilder()
    .setBody("crash_marker")
    .setSeverity(Severity.ERROR)
    .setAttribute("crash_type", "uncaught_exception")
    .setAttribute("demo_run_id", demoRunId)
    .emit()

// Expected: Collector applies crash-recovery policy
// Expected: Historical context available
```

**Test 3: Network Errors (OTEL Native)**
```kotlin
logger.logRecordBuilder()
    .setBody("http.error")
    .setSeverity(Severity.ERROR)
    .setAttribute("status_code", 503L)
    .setAttribute("route", "/appointments")
    .setAttribute("demo_run_id", demoRunId)
    .emit()

// Expected: Collector applies network-error-spike policy
// Expected: Sampling adjusted
```

### 4.3 Performance Testing

**Benchmarks to verify:**
- Android log emission: < 1ms per log
- Ring buffer write: < 10ms (RAM), < 50ms (disk)
- OTLP export: < 100ms per batch
- Collector processing: < 5ms per log
- End-to-end latency: < 200ms

### 4.4 Compatibility Testing

**Test with:**
- Android 8.0+ (API 26+)
- OTEL Collector 0.91.0+
- Multiple backend exporters
- Different network conditions
- High volume scenarios (10k+ logs)

**Tasks:**
- [ ] Set up end-to-end test environment
- [ ] Implement all test scenarios
- [ ] Performance benchmarking
- [ ] Load testing
- [ ] Network condition testing
- [ ] Cross-platform testing
- [ ] Documentation of test results

**Deliverables:**
- All tests passing
- Performance benchmarks documented
- End-to-end demo video
- Test reports

---

## Phase 5: Documentation & Contribution (Week 5-6)

### 5.1 Write OpenTelemetry Enhancement Proposals (OTEPs)

**OTEP 1: Mobile Buffering Pattern**

**File: `docs/OTEPs/OTEP-mobile-buffering.md`**

```markdown
# OTEP: Mobile Log Buffering Pattern

## Summary
Proposes a standard pattern for buffering logs in mobile applications with
limited network connectivity and resource constraints.

## Motivation
Mobile applications face unique challenges:
- Intermittent network connectivity
- Battery and bandwidth constraints
- Need to preserve telemetry during offline periods
- Selective data export to minimize costs

## Proposal
Add mobile-specific LogRecordProcessor that:
1. Buffers logs in RAM (fast, limited)
2. Persists overflow to disk (slower, larger)
3. Exports conditionally based on policies
4. Manages eviction for bounded storage

## Design Details
[Technical specification]

## Open Questions
[Questions for community]

## Implementation Plan
[Phased rollout]
```

**OTEP 2: Conditional Export for Resource-Constrained Devices**

**File: `docs/OTEPs/OTEP-conditional-export.md`**

```markdown
# OTEP: Conditional Export for Resource-Constrained Devices

## Summary
Proposes a policy-based conditional export mechanism for mobile and
IoT devices with bandwidth constraints.

## Motivation
Not all telemetry data has equal value. Conditional export allows:
- Selective data transmission based on context
- Bandwidth optimization
- Cost reduction for mobile networks
- Preservation of high-value telemetry

## Proposal
[Detailed proposal]
```

### 5.2 Comprehensive Documentation

**Android Library README**

**File: `otel-android-mobile/README.md`**

```markdown
# OpenTelemetry Android Mobile Extensions

OpenTelemetry extensions for mobile applications with buffering and
conditional export capabilities.

## Features
- RAM + disk buffering for offline resilience
- OTLP/gRPC export directly to Collector
- Policy-based conditional export
- Standard OpenTelemetry APIs

## Installation
\`\`\`kotlin
dependencies {
    implementation("io.opentelemetry.android:mobile:0.1.0-alpha")
}
\`\`\`

## Usage
\`\`\`kotlin
val config = MobileConfig(
    serviceName = "my-app",
    collectorEndpoint = "https://collector:4317",
    ramBufferSize = 5000,
    diskBufferMb = 50
)

val provider = MobileLoggerProvider.getInstance(context, config)
val logger = provider.getLogger()

logger.logRecordBuilder()
    .setBody("user.action")
    .setAttribute("action_type", "button_click")
    .emit()
\`\`\`

## Documentation
- [Quick Start](docs/quickstart.md)
- [Configuration](docs/configuration.md)
- [Best Practices](docs/best-practices.md)
- [API Reference](docs/api-reference.md)

## License
Apache 2.0
```

**Collector Processor README**

**File: `collector-processor/mobilepolicyprocessor/README.md`**

```markdown
# Mobile Policy Processor

OpenTelemetry Collector processor for evaluating mobile-specific policies
and annotating telemetry data.

## Configuration
\`\`\`yaml
processors:
  mobilepolicy:
    policies:
      - id: ui-freeze
        enabled: true
        match:
          attributes:
            event.name:
              equals: "ui.freeze"
            duration_ms:
              gt: 2000.0
        actions:
          - type: annotate
            parameters:
              trigger_id: "ui-freeze"
\`\`\`

## Documentation
- [Configuration Guide](docs/configuration.md)
- [Policy Syntax](docs/policy-syntax.md)
- [Examples](docs/examples.md)

## License
Apache 2.0
```

### 5.3 Contribution Guidelines

**File: `CONTRIBUTING.md`**

```markdown
# Contributing to OpenTelemetry Mobile Extensions

## Code of Conduct
This project follows the [OpenTelemetry Code of Conduct](CODE_OF_CONDUCT.md).

## Development Setup
[Setup instructions]

## Making Changes
1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Run tests: `./test.sh`
6. Submit pull request

## Code Style
- Kotlin: Follow [Kotlin Style Guide](https://kotlinlang.org/docs/coding-conventions.html)
- Go: Follow [Effective Go](https://golang.org/doc/effective_go.html)

## Testing
All changes must include tests.

## Documentation
Update documentation for user-facing changes.
```

### 5.4 Prepare Contribution Packages

**Package 1: Android Library**
```
opentelemetry-android-contrib/mobile/
├── src/
├── docs/
├── examples/
├── README.md
├── CHANGELOG.md
└── LICENSE
```

**Package 2: Collector Processor**
```
opentelemetry-collector-contrib/processor/mobilepolicyprocessor/
├── *.go
├── testdata/
├── docs/
├── README.md
└── CHANGELOG.md
```

**Package 3: Reference Implementation**
```
opentelemetry-mobile-demo/
├── android-demo/
├── k8s/
├── control-plane-ui/
├── docs/
└── README.md
```

**Tasks:**
- [ ] Write OTEP 1 (Mobile Buffering)
- [ ] Write OTEP 2 (Conditional Export)
- [ ] Write Android library documentation
- [ ] Write collector processor documentation
- [ ] Write contribution guidelines
- [ ] Prepare demo documentation
- [ ] Create tutorial videos
- [ ] Write blog post
- [ ] Prepare presentation

**Deliverables:**
- 2 OTEPs ready for community review
- Complete documentation
- Contribution packages ready
- Demo materials prepared

---

## Phase 6: OpenTelemetry Contribution Process

### 6.1 Community Engagement

**Steps:**
1. **Share OTEPs in OpenTelemetry Slack**
   - Post in #otel-android channel
   - Post in #otel-collector channel
   - Request feedback

2. **Present at SIG Meeting**
   - Android SIG (if exists) or Collector SIG
   - Walk through design
   - Gather community input

3. **Iterate on Feedback**
   - Update OTEPs based on community input
   - Refine implementation
   - Address concerns

### 6.2 Submit Contributions

**Android Library:**
1. Fork `open-telemetry/opentelemetry-android`
2. Create PR with mobile extensions
3. Address review comments
4. Get approval from maintainers

**Collector Processor:**
1. Fork `open-telemetry/opentelemetry-collector-contrib`
2. Create PR with mobilepolicyprocessor
3. Address review comments
4. Get approval from maintainers

**Demo:**
1. Create separate repository
2. Link from OTEL documentation
3. Submit to awesome-opentelemetry list

### 6.3 Maintenance

**Ongoing:**
- Respond to issues
- Review PRs
- Update documentation
- Release management
- Community support

---

## Success Criteria

### Technical Success
- [ ] Android app uses official OTEL SDK
- [ ] Logs exported via OTLP/gRPC
- [ ] No custom JSON formats
- [ ] Collector processor follows OTEL patterns
- [ ] All tests passing
- [ ] Performance benchmarks met
- [ ] Documentation complete

### Community Success
- [ ] OTEPs approved
- [ ] PRs merged to OTEL repos
- [ ] 3+ maintainers review and approve
- [ ] Included in OTEL release
- [ ] Demo featured in OTEL documentation
- [ ] Blog post published
- [ ] Conference presentation

### Adoption Success
- [ ] 5+ apps using the library
- [ ] GitHub stars > 100
- [ ] Active issues/PRs
- [ ] Community contributions
- [ ] Featured in OTEL newsletter

---

## Risk Assessment

| Risk | Impact | Mitigation |
|------|--------|------------|
| OTEP not approved | High | Engage early, iterate on feedback |
| API changes in OTEL SDK | Medium | Track upstream, adapt quickly |
| Performance regressions | High | Comprehensive benchmarks, profiling |
| Breaking changes | Medium | Careful versioning, migration guides |
| Low adoption | Medium | Marketing, tutorials, examples |

---

## Timeline Summary

| Phase | Duration | Deliverables |
|-------|----------|--------------|
| Phase 1: Foundation | 2 weeks | New repo structure, build systems |
| Phase 2: Android Migration | 1 week | OTEL-native Android library |
| Phase 3: Collector Processor | 1 week | mobilepolicyprocessor |
| Phase 4: Integration & Testing | 1 week | All tests passing |
| Phase 5: Documentation | 1 week | OTEPs, docs, contribution packages |
| Phase 6: Contribution Process | Ongoing | PRs merged, community adoption |

**Total Time:** 6 weeks to first contribution

---

## Resources Required

### People
- 1 Android developer (Kotlin)
- 1 Backend developer (Go)
- 1 Technical writer (documentation)
- OTEL community maintainers (reviews)

### Infrastructure
- GitHub repository
- CI/CD pipeline (GitHub Actions)
- Test infrastructure
- Documentation hosting

### Budget
- $0 (all open source)
- Time investment: ~480 hours
- Infrastructure: Free (GitHub)

---

## Next Steps

1. **Review this plan** with team
2. **Get approval** to proceed
3. **Create new repository** structure
4. **Start Phase 1** - Foundation
5. **Set up weekly progress meetings**
6. **Begin community engagement**

---

## Questions for Discussion

1. Should we target `opentelemetry-android` or `opentelemetry-android-contrib`?
2. What's the appetite for mobile-specific features in OTEL?
3. Should we start with OTEP or working code?
4. How should we handle backward compatibility with current demo?
5. What's the minimum viable contribution (MVP)?

---

**Status:** Ready for Review and Approval

**Next Action:** Review plan and decide to proceed

**Estimated Start Date:** Upon approval

**Target Completion:** 6 weeks from start
