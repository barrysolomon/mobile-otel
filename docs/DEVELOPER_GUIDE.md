# Developer Guide

Complete guide for developers extending and customizing the mobile observability system.

## Table of Contents

1. [Development Setup](#development-setup)
2. [Architecture Overview](#architecture-overview)
3. [Component Development](#component-development)
4. [Android SDK Integration](#android-sdk-integration)
5. [Testing](#testing)
6. [Contributing](#contributing)

## Development Setup

### Prerequisites

- **Go 1.21+**: Collector processor development
- **Android Studio**: Android app development
- **JDK 17**: Android SDK library
- **Docker**: Custom collector build (optional)
- **Git**: Version control

> **Note:** Gateway and Control Plane UI development has moved to the
> [mobile-otel-control-plane](https://github.com/barrysolomon/mobile-otel-control-plane) repository.

### Clone and Setup

```bash
# Clone repository
git clone https://github.com/your-org/mobile-otel.git
cd mobile-otel

# Setup Collector Processor
cd collector-processor/mobilepolicyprocessor && go mod tidy && go build ./... && cd ../..

# Build Android SDK (via demo app)
cd examples/demo-app && ./gradlew :otel-android-mobile:build && cd ../..
```

### Development Environment

```bash
# Terminal 1: Android development
cd examples/demo-app
./gradlew installDebug
adb logcat | grep "OTelMobile\|MobileOtel"

# Terminal 2: Collector processor development
cd collector-processor/mobilepolicyprocessor
go test -v -race ./...
```

## Architecture Overview

### System Components

```
┌──────────────┐                        ┌──────────────┐
│   Android    │───────────────────────►│    OTEL      │
│     App      │      OTLP/gRPC        │  Collector   │
│   (Kotlin)   │                        │              │
└──────────────┘                        └──────────────┘
       │                                       │
       ▼                                       ▼
┌──────────────┐                        ┌──────────────┐
│ Ring Buffer  │                        │   Backends   │
│ (RAM + Disk) │                        │ (Dash0, etc) │
└──────────────┘                        └──────────────┘
```

> Gateway, Control Plane UI, and k8s manifests are in the
> [sister repo](https://github.com/barrysolomon/mobile-otel-control-plane).

### Data Flow

1. **Event Capture**: Android app captures events to ring buffer
2. **Policy Evaluation**: DSL evaluator checks triggers
3. **Selective Flush**: Window-based data export on match
4. **OTEL Export**: Send to collector via OTLP/gRPC
5. **Backend Storage**: Dash0, Loki, Prometheus, etc.

### Key Technologies

| Component | Tech Stack |
|-----------|------------|
| Android SDK | Kotlin, Room, OkHttp, Coroutines |
| Collector Processor | Go, OTEL Collector SDK |

## Component Development

> **Note:** Gateway and Control Plane UI development guides have moved to the
> [mobile-otel-control-plane](https://github.com/barrysolomon/mobile-otel-control-plane) repository.

### Android SDK (Kotlin)

#### Project Structure

```
otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/
├── MobileOtel.kt            # Core facade — wires all modules, public API
├── OTelMobile.kt            # Auto-capture entry point (delegates to MobileOtel)
├── MobileLoggerProvider.kt  # OTel LoggerProvider + processor
├── autocapture/             # Tap, scroll, freeze, ANR, lifecycle
├── buffering/               # Two-tier ring buffer (RAM + SQLite via Room)
├── config/                  # MobileConfig, NetworkConfig, AutoCaptureOptions
├── errors/                  # ErrorInstrumentation (uncaught, coroutine, RxJava)
├── export/                  # EnrichingLogRecordExporter, RetryableExporter
├── network/                 # OTelNetworkInterceptor (OkHttp)
├── policy/                  # PolicyEvaluator (DSL engine)
├── predictive/              # PredictiveExportPolicy, DeviceHealthMonitor
└── vitals/                  # VitalsCollector, JankDetector, AppStartInstrumentation
```

#### SDK Architecture

```kotlin
// Two entry points:

// 1. Full auto-instrumentation (errors + vitals + predictive + UI capture)
OTelMobile.start(applicationContext, MobileConfig(
    serviceName       = "my-app",
    serviceVersion    = "1.0.0",
    collectorEndpoint = "https://collector.example.com:4317"
))

// 2. Core only (buffering + policy evaluation, no UI auto-capture)
MobileOtel.initialize(applicationContext, MobileConfig(...))
```

## Android SDK Integration

See [ANDROID_SDK_GUIDE.md](ANDROID_SDK_GUIDE.md) for the full integration guide. Quick reference:

**1. Include as local Gradle module:**

```kotlin
// settings.gradle.kts
include(":otel-android-mobile")
project(":otel-android-mobile").projectDir = file("path/to/otel-android-mobile")

// app/build.gradle.kts
dependencies { implementation(project(":otel-android-mobile")) }
```

**2. Initialize:**

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OTelMobile.start(this, MobileConfig(
            serviceName       = "my-app",
            serviceVersion    = BuildConfig.VERSION_NAME,
            collectorEndpoint = "https://collector.example.com:4317"
        ))
    }
}
```

**3. Custom events and error reporting:**

```kotlin
MobileOtel.sendEvent("checkout.completed", mapOf("total_cents" to 4299))
MobileOtel.reportError(exception, mapOf("context" to "checkout"))
MobileOtel.forceFlush(windowMinutes = 5)
```

**4. Network instrumentation (OkHttp):**

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(OTelNetworkInterceptor.create(
        context    = applicationContext,
        config     = NetworkConfig.production(),
        tracer     = OTelMobile.getTracer("network"),
        propagator = openTelemetry.propagators.textMapPropagator
    ))
    .build()
```

## Testing

### Unit Tests

#### Android SDK (Kotlin + Robolectric)

Tests live in `otel-android-mobile/src/test/`. Run via the demo app project:

```bash
cd examples/demo-app
./gradlew :otel-android-mobile:test
./gradlew :otel-android-mobile:test --tests "*.PolicyEvaluatorTest"
```

Pattern for testing telemetry output — inject `MockLogRecordExporter` with synchronous processor:

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MyFeatureTest {
    private lateinit var mockExporter: MockLogRecordExporter
    private lateinit var logger: Logger

    @Before fun setup() {
        mockExporter = MockLogRecordExporter()
        val provider = SdkLoggerProvider.builder()
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(mockExporter))
            .build()
        logger = provider.get("test")
    }

    @Test fun `emits expected event`() {
        // exercise the class under test with the injected logger
        val events = mockExporter.findLogs { it.body.toString() == "app.crash" }
        assertEquals(1, events.size)
    }
}
```

See `RecoveryTrackerTest` and `PolicyEvaluatorTest` for full examples.

## Contributing

### Code Style

#### Go

- Follow [Effective Go](https://golang.org/doc/effective_go.html)
- Use `gofmt` for formatting
- Run `go vet` before committing

#### TypeScript

- Use ESLint configuration
- Follow React best practices
- Use TypeScript strict mode

#### Kotlin

- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use Android Studio formatter

### Pull Request Process

1. Fork the repository
2. Create feature branch: `git checkout -b feature/my-feature`
3. Make changes and add tests
4. Run tests: `npm test`, `go test ./...`, `./gradlew test`
5. Commit with clear message
6. Push and create pull request
7. Wait for review

### Commit Message Format

```
type(scope): subject

body (optional)

footer (optional)
```

**Types:** feat, fix, docs, style, refactor, test, chore

**Example:**
```
feat(gateway): add custom processor support

- Add processor interface
- Implement custom processor
- Add tests

Closes #123
```

## Next Steps

- **[Troubleshooting](TROUBLESHOOTING_GUIDE.md)** - Common issues
- **[Testing Strategy](guides/TESTING_STRATEGY.md)** - Testing approach
- **[Control Plane](https://github.com/barrysolomon/mobile-otel-control-plane)** - Gateway, UI, and deployment (sister repo)
