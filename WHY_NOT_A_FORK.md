# Why This Is Not an OTEL Fork

**TL;DR**: This project uses 100% official OpenTelemetry components and extends them with mobile-specific patterns through standard OTEL extension mechanisms. No protocols, APIs, or core components are modified or replaced.

---

## What We DON'T Do (Fork Indicators)

❌ **NO custom wire protocol** - We use OTLP/gRPC exclusively
❌ **NO modified SDK APIs** - We implement standard OTEL interfaces
❌ **NO proprietary data models** - We use `LogRecordData`, `Attributes`, `Resource` as-is
❌ **NO patched OTEL libraries** - We depend on official artifacts
❌ **NO incompatible changes** - Everything works with standard OTEL backends

## What We DO (Composition, Not Forking)

✅ **Implement standard interfaces**:
```kotlin
class MobileLogRecordProcessor : LogRecordProcessor {
    // Standard OTEL interface, mobile implementation
}
```

✅ **Extend via documented mechanisms**:
```go
func createDefaultConfig() component.Config {
    // Standard OTEL Collector processor pattern
}
```

✅ **Compose existing components**:
```kotlin
val pipeline = MobileLogRecordProcessor(
    delegate = BatchLogRecordProcessor(  // Official OTEL component
        exporter = OtlpGrpcLogRecordExporter.builder()  // Official OTEL exporter
            .setEndpoint(config.collectorEndpoint)
            .build()
    )
)
```

---

## Line-by-Line: OTEL Native Architecture

### Data Creation (100% OTEL)
```kotlin
logger.logRecordBuilder()              // io.opentelemetry.api.logs.Logger
    .setBody("user.action")            // OTEL LogRecordBuilder API
    .setAttribute("key", "value")      // OTEL Attributes API
    .emit()                            // OTEL standard method
```

### Processing (OTEL Interface + Mobile Implementation)
```kotlin
class MobileLogRecordProcessor : LogRecordProcessor {
    // ✅ OTEL interface: LogRecordProcessor
    // ✅ Implementation detail: Two-tier buffer for mobile

    override fun onEmit(context: OtelContext, logRecord: LogRecordData) {
        ramBuffer.offer(logRecord)  // Mobile-specific: persistent buffering
    }

    override fun forceFlush(): CompletableResultCode {
        return delegate.forceFlush()  // ✅ Delegates to official OTEL BatchLogRecordProcessor
    }
}
```

### Export (100% OTEL)
```kotlin
val exporter = OtlpGrpcLogRecordExporter.builder()  // io.opentelemetry.exporter.otlp.logs
    .setEndpoint("https://collector:4317")
    .build()

exporter.export(logs)  // ✅ Standard OTLP/gRPC
```

### Collector Processing (OTEL Extension Mechanism)
```go
func (p *mobilePolicyProcessor) ConsumeLogs(ctx context.Context, ld plog.Logs) error {
    // ✅ OTEL Collector interface: consumer.Logs
    // ✅ Implementation: Mobile policy evaluation
    // ✅ Output: Standard plog.Logs (no format change)
}
```

---

## How This Differs from an Actual Fork

| Characteristic | Fork | This Project |
|---|---|---|
| **Code base** | Copied OTEL code | Uses OTEL as dependency |
| **Maintenance** | Must merge upstream changes | Automatically get upstream updates |
| **Compatibility** | Breaks with OTEL updates | Compatible by design |
| **Wire protocol** | Custom/modified | OTLP/gRPC standard |
| **Distribution** | Separate binaries | Extension libraries |
| **Contribution path** | Divergent | Converges via OTEP |

---

## Analogy: Express.js Middleware vs Node.js Fork

**This project is like writing Express.js middleware**:
- Express.js is built on Node.js (doesn't fork it)
- Middleware extends functionality without modifying core
- All standard Node.js APIs work unchanged
- Can be contributed back to Express.js ecosystem

**A fork would be like creating a custom Node.js runtime**:
- Modifies the JavaScript engine
- Incompatible with standard Node.js
- Requires separate maintenance forever
- Cannot easily merge upstream improvements

We're the "middleware", not the "custom runtime".

---

## OTEL Extension Points We Use (By Design)

OpenTelemetry **explicitly provides** extension mechanisms:

### 1. LogRecordProcessor Interface
```kotlin
interface LogRecordProcessor {
    fun onEmit(context: OtelContext, logRecord: LogRecordData)
    fun forceFlush(): CompletableResultCode
    fun shutdown(): CompletableResultCode
}
```
**Purpose**: "Implement this to add custom processing logic"
**Our use**: Mobile-specific buffering implementation

### 2. Collector Processor Factory
```go
func NewFactory() processor.Factory {
    return processor.NewFactory(
        metadata.Type,
        createDefaultConfig,
        processor.WithLogs(createLogsProcessor, metadata.LogsStability),
    )
}
```
**Purpose**: "Register custom processors following this pattern"
**Our use**: Mobile policy evaluation processor

### 3. Resource Attributes
```kotlin
Resource.builder()
    .put("mobile.device_id", deviceId)  // Custom attributes explicitly allowed
    .build()
```
**Purpose**: "Add domain-specific attributes"
**Our use**: Mobile device context

---

## Path to Upstream Contribution

### Phase 1: Prove It Works (Current)
- ✅ Build reference implementation
- ✅ Deploy in limited production
- ✅ Document architecture
- ✅ Create test suite

### Phase 2: Community Engagement (Next)
- ⏳ Write OTEPs (OpenTelemetry Enhancement Proposals)
  - OTEP: Mobile Buffering Patterns
  - OTEP: Conditional Export for Bandwidth Optimization
- ⏳ Present at OTEL SIG meetings
- ⏳ Gather feedback from maintainers

### Phase 3: Upstream Contribution (Future)
- ⏳ Submit PRs to `opentelemetry-android`
- ⏳ Submit PRs to `opentelemetry-collector-contrib`
- ⏳ Move under official OTEL GitHub org
- ⏳ Adopt OTEL governance

**Goal**: Deprecate this repo once functionality is in official OTEL projects.

---

## Red Flags This Project Avoids

🚩 **"We have our own protocol"** → We use OTLP
🚩 **"Compatible with OTEL (mostly)"** → We're 100% compatible
🚩 **"We forked the SDK to add..."** → We implement interfaces
🚩 **"You need our special collector"** → Works with any OTEL collector (processor is optional)
🚩 **"Different attribute names for..."** → We use semantic conventions
🚩 **"Our enhanced SDK..."** → We extend via composition

---

## Validation: Works with Standard OTEL Tools

✅ **Jaeger** - Views our OTLP traces without modification
✅ **Prometheus** - Scrapes our OTLP metrics as expected
✅ **Loki** - Indexes our OTLP logs using standard parsers
✅ **OTEL Collector** - Processes our data with standard receivers
✅ **Grafana** - Visualizes using standard OTEL dashboard templates

If we were a fork, these would all break.

---

## Commitment to OTEL Alignment

### We Promise To:
1. **Never modify OTEL wire protocols**
2. **Never break compatibility with standard OTEL components**
3. **Always use official OTEL dependencies** (no vendored/patched versions)
4. **Contribute patterns back via OTEP** (not hoard them)
5. **Deprecate this repo** if/when functionality moves upstream

### We Reserve The Right To:
1. **Implement OTEL interfaces** with mobile-optimized logic
2. **Add mobile-specific attributes** following semantic conventions
3. **Create collector processors** using standard factory patterns
4. **Release as standalone library** until upstream adoption
5. **Maintain backward compatibility** during transition

---

## Final Statement

This project exists because:
1. **Mobile apps have unique requirements** (crashes, offline, bandwidth)
2. **OTEL doesn't yet have mobile patterns** (it's a new domain)
3. **We need solutions today** (can't wait years for standardization)
4. **We want to contribute upstream** (this is the reference implementation)

If OpenTelemetry officially adds mobile buffering and conditional export, **we will immediately deprecate this project and migrate to the official solution**.

That's the opposite of a fork.

---

**Questions? See the [Design Document](DESIGN.md) for technical details or the [Architecture Deep Dive](docs/reference/ARCHITECTURE.md) for implementation specifics.**
