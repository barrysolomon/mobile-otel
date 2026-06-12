# OpenTelemetry Android Mobile Extensions

[![Apache License][license-image]][license-url]

Mobile-specific extensions for OpenTelemetry Android including buffering and conditional export capabilities.

## Features

- **Ring Buffer**: Two-tier buffering (RAM + disk) for offline resilience, with optional at-rest encryption
- **Conditional Export**: Policy-based selective data transmission
- **OTLP/HTTP or gRPC**: Direct export to an OpenTelemetry Collector (HTTP/protobuf by default; gRPC opt-in)
- **Standard APIs**: Built on official OpenTelemetry SDK
- **Low Overhead**: < 5% performance impact

## Installation

The SDK is published to **GitHub Packages** (not Maven Central). Consuming it requires (1) declaring the Maven repository, (2) authenticating with a GitHub Personal Access Token that has the `read:packages` scope, and (3) adding the dependency.

### 1. Declare the repository

In `settings.gradle.kts` (or your root `build.gradle.kts` `repositories` block):

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/barrysolomon/mobile-otel")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.token").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

### 2. Provide credentials

GitHub Packages requires authentication even for public packages. Add a PAT with the `read:packages` scope to `~/.gradle/gradle.properties` (keep it out of version control):

```properties
gpr.user=your-github-username
gpr.token=ghp_your_personal_access_token_with_read_packages
```

Or export `GITHUB_ACTOR` / `GITHUB_TOKEN` in the environment (the repo block above falls back to these).

### 3. Add the dependency

```kotlin
dependencies {
    implementation("io.opentelemetry.android:mobile:0.4.0-alpha")
}
```

The umbrella `io.opentelemetry.android:mobile` artifact pulls in its full dependency tree — `mobile-core` and every `mobile-instrumentation-<name>` module — all published to the same GitHub Packages repository, so a single dependency line resolves everything.

## Quick Start

### Auto-Capture (Drop-in)

```kotlin
import io.opentelemetry.android.mobile.OTelMobile
import io.opentelemetry.android.mobile.config.MobileConfig

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val config = MobileConfig(
            serviceName = "my-mobile-app",
            serviceVersion = "1.0.0",
            // HTTP/protobuf (default): the SDK POSTs to <endpoint>/v1/{logs,traces,metrics}
            collectorEndpoint = "https://otel-collector.example.com"
        )

        // start() enables all auto-instrumentation (taps, scrolls, back press,
        // freeze/ANR, lifecycle, screen views, errors, vitals).
        OTelMobile.start(application = this, config = config)
    }
}
```

### Basic Setup

```kotlin
import io.opentelemetry.android.mobile.MobileLoggerProvider
import io.opentelemetry.android.mobile.config.MobileConfig

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val config = MobileConfig(
            serviceName = "my-mobile-app",
            serviceVersion = "1.0.0",
            collectorEndpoint = "https://otel-collector.example.com",
            ramBufferSize = 5000,
            diskBufferMb = 50
        )

        val provider = MobileLoggerProvider.getInstance(this, config)
        val logger = provider.getLogger()

        // Log events
        logger.logRecordBuilder()
            .setBody("user.action")
            .setAttribute("action_type", "button_click")
            .setAttribute("screen", "MainActivity")
            .emit()
    }
}
```

### With Conditional Export

```kotlin
import io.opentelemetry.android.mobile.policy.PolicyEvaluator
import io.opentelemetry.android.mobile.policy.TriggerCondition

val policyEvaluator = PolicyEvaluator.builder()
    .addCondition(
        TriggerCondition.builder()
            .matchAttribute("event.name", "error")
            .build()
    )
    .build()

val processor = MobileLogRecordProcessor.builder()
    .setExporter(otlpExporter)
    .setPolicyEvaluator(policyEvaluator)
    .setContext(context)
    .build()
```

## Architecture

```
Mobile App (Your Code)
    │
    ├──► OpenTelemetry Logger API
    │
    ├──► MobileLogRecordProcessor
    │      ├─► RAM Buffer (ConcurrentQueue, 5000 events)
    │      ├─► Disk Buffer (Room/SQLite, 50MB, 24h, encrypted at rest by default)
    │      ├─► PolicyEvaluator (conditional export)
    │      └─► OTLP Exporter (HTTP/protobuf by default; gRPC opt-in)
    │
    └──► OTEL Collector
           └─► Backends (Loki, Prometheus, etc)
```

## Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `serviceName` | String | Required | Service name for telemetry |
| `serviceVersion` | String | Required | Service version |
| `collectorEndpoint` | String | Required | OTEL Collector / ingest endpoint. With the default `HTTP_PROTOBUF` protocol the SDK appends `/v1/{logs,traces,metrics}` automatically, so pass the base URL (e.g. `https://collector.example.com`). |
| `protocol` | `OtlpProtocol` | `HTTP_PROTOBUF` | OTLP wire protocol: `HTTP_PROTOBUF` (default, matches iOS, works behind HTTPS proxies) or `GRPC` (single endpoint, typically `:4317`). |
| `exportMode` | `ExportMode` | `HYBRID` | `CONDITIONAL`, `CONTINUOUS`, or `HYBRID`. |
| `ramBufferSize` | Int | 5000 | Maximum events in RAM buffer (count cap) |
| `ramBufferMaxTotalBytes` | Long | 10 MB | Total-byte budget for the RAM buffer; oldest events overflow to disk past this. |
| `ramBufferMaxEventBytes` | Int | 256 KB | Per-event byte cap; oversize events are dropped and counted. |
| `diskBufferMb` | Int | 50 | Maximum disk buffer size (MB) |
| `diskBufferTtlHours` | Int | 24 | Maximum age (hours) for buffered events |
| `encryptDiskBufferAtRest` | Boolean | `true` | Encrypt the on-disk buffer at rest (SQLCipher + Android Keystore). Crash-safe; degrades to cleartext if unavailable. Set `false` to skip the SQLCipher native-library cost. |
| `allowInsecureTransport` | Boolean | `false` | When `false`, a cleartext `http://` endpoint to a non-loopback host is rejected (export disabled gracefully, never crashes). Set `true` for dev/local collectors. Loopback / `10.0.2.2` exempt. |
| `pinningConfig` | `TransportSecurity.PinningConfig?` | `null` | Certificate / public-key pinning (SPKI SHA-256 pins and/or DER certs) for the OTLP/HTTP exporter + config poller. Pin mismatch fails only that connection. Requires `HTTP_PROTOBUF` (not applied on gRPC). |
| `configSigningKey` | `ByteArray?` | `null` | HMAC-SHA256 secret; when set, remote config's `X-Dash0-Config-Signature` is verified (constant-time) before applying — a bad/missing signature keeps the last-applied config. |

## Use Cases

### 1. Error Monitoring

```kotlin
logger.logRecordBuilder()
    .setBody("error.occurred")
    .setSeverity(Severity.ERROR)
    .setAttribute("error_type", "NetworkException")
    .setAttribute("error_message", exception.message)
    .emit()
```

### 2. Performance Tracking

```kotlin
val startTime = System.currentTimeMillis()
// ... operation ...
val duration = System.currentTimeMillis() - startTime

logger.logRecordBuilder()
    .setBody("operation.completed")
    .setAttribute("duration_ms", duration)
    .setAttribute("operation", "image_load")
    .emit()
```

### 3. User Analytics

```kotlin
logger.logRecordBuilder()
    .setBody("user.action")
    .setAttribute("action_type", "purchase")
    .setAttribute("product_id", "premium_plan")
    .setAttribute("amount", 9.99)
    .emit()
```

## Best Practices

### Event Naming

Use dot notation with category.action:
- ✅ `user.login`
- ✅ `http.request`
- ✅ `screen.view`
- ❌ `userLogin`
- ❌ `LOGIN_EVENT`

### Attribute Types

Use appropriate data types:
```kotlin
// Good
.setAttribute("duration_ms", 1500L)        // Long for numbers
.setAttribute("success", true)              // Boolean
.setAttribute("user_id", "user-123")       // String

// Bad
.setAttribute("duration_ms", "1500")       // String (should be Long)
.setAttribute("success", "true")           // String (should be Boolean)
```

### Sensitive Data

Never log sensitive information:
```kotlin
// ❌ Never do this
.setAttribute("password", userPassword)
.setAttribute("credit_card", cardNumber)
.setAttribute("ssn", socialSecurity)

// ✅ Log non-sensitive data only
.setAttribute("user_id", userId)
.setAttribute("login_method", "email")
.setAttribute("success", true)
```

## Performance

Benchmarks on Pixel 7:
- Event capture: < 1ms per event
- RAM buffer write: < 10ms
- Disk buffer write: < 50ms
- OTLP export: < 100ms per batch

Memory usage:
- RAM buffer: ~10-50 MB (depending on event volume)
- Disk buffer: Configurable (default 50 MB)

## Testing

```bash
# Run unit tests (no device needed)
cd examples/demo-app
./gradlew :otel-android-mobile:test

# Run instrumentation tests (requires a running emulator or device)
./gradlew :otel-android-mobile:connectedDebugAndroidTest

# Run with coverage
./gradlew :otel-android-mobile:testDebugUnitTestCoverage
```

**No emulator running?** Start one before running instrumented tests:

```bash
# List available AVDs
emulator -list-avds

# Start headless (Pixel_3a is lightest on RAM)
nohup emulator -avd Pixel_3a -no-window -no-audio -no-snapshot-save \
    > /tmp/emulator.log 2>&1 &

# Wait for full boot before installing/testing
until adb shell "getprop dev.bootcomplete" 2>/dev/null | grep -q 1; do sleep 5; done
until adb shell "getprop sys.boot_completed" 2>/dev/null | grep -q 1; do sleep 5; done
echo "Ready"
```

See [Quick Start — Setting Up an Emulator](../docs/QUICK_START.md#step-3-start-an-emulator) for full instructions including creating new AVDs.

## Documentation

- [Android SDK Integration Guide](../docs/ANDROID_SDK_GUIDE.md)
- [Auto-Instrumentation Reference](../docs/AUTO_INSTRUMENTATION.md)
- [Export Modes](../docs/EXPORT_MODES.md)
- [Sampling](../docs/SAMPLING.md)
- [API Reference](../docs/API_REFERENCE.md)
- [Instrumentation modules](../instrumentation/README.md)

## Contributing

We welcome contributions! Please see [CONTRIBUTING.md](../CONTRIBUTING.md) for details.

## License

Apache License 2.0 - See [LICENSE](../LICENSE) for details.

## Support

- [GitHub Issues](https://github.com/barrysolomon/mobile-otel/issues)
- [Slack Channel](https://cloud-native.slack.com/archives/C01N7PP1THC) (#otel-android)
- [Documentation](https://opentelemetry.io/docs/languages/android/)

## Credits

Built with ❤️ by the OpenTelemetry community.

[license-image]: https://img.shields.io/badge/license-Apache_2.0-green.svg?style=flat
[license-url]: https://github.com/open-telemetry/opentelemetry-android-contrib/blob/main/LICENSE
