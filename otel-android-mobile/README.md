# OpenTelemetry Android Mobile Extensions

[![Apache License][license-image]][license-url]

Mobile-specific extensions for OpenTelemetry Android including buffering and conditional export capabilities.

## Features

- **Ring Buffer**: Two-tier buffering (RAM + disk) for offline resilience
- **Conditional Export**: Policy-based selective data transmission
- **OTLP/gRPC**: Direct export to OpenTelemetry Collector
- **Standard APIs**: Built on official OpenTelemetry SDK
- **Low Overhead**: < 5% performance impact

## Installation

Add to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.opentelemetry.android:mobile:0.1.0-alpha")
}
```

## Quick Start

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
            collectorEndpoint = "https://otel-collector.example.com:4317",
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
    │      ├─► Disk Buffer (Room/SQLite, 50MB, 24h)
    │      ├─► PolicyEvaluator (conditional export)
    │      └─► OTLP Exporter (gRPC)
    │
    └──► OTEL Collector
           └─► Backends (Loki, Prometheus, etc)
```

## Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `serviceName` | String | Required | Service name for telemetry |
| `serviceVersion` | String | Required | Service version |
| `collectorEndpoint` | String | Required | OTEL Collector endpoint (gRPC) |
| `ramBufferSize` | Int | 5000 | Maximum events in RAM buffer |
| `diskBufferMb` | Long | 50 | Maximum disk buffer size (MB) |
| `retentionHours` | Int | 24 | Maximum age for buffered events |

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
# Run unit tests
./gradlew test

# Run instrumentation tests
./gradlew connectedAndroidTest

# Run with coverage
./gradlew jacocoTestReport
```

## Documentation

- [Configuration Guide](docs/configuration.md)
- [Best Practices](docs/best-practices.md)
- [API Reference](docs/api-reference.md)
- [Examples](examples/)
- [Troubleshooting](docs/troubleshooting.md)

## Contributing

We welcome contributions! Please see [CONTRIBUTING.md](../CONTRIBUTING.md) for details.

## License

Apache License 2.0 - See [LICENSE](../LICENSE) for details.

## Support

- [GitHub Issues](https://github.com/open-telemetry/opentelemetry-android-contrib/issues)
- [Slack Channel](https://cloud-native.slack.com/archives/C01N7PP1THC) (#otel-android)
- [Documentation](https://opentelemetry.io/docs/languages/android/)

## Credits

Built with ❤️ by the OpenTelemetry community.

[license-image]: https://img.shields.io/badge/license-Apache_2.0-green.svg?style=flat
[license-url]: https://github.com/open-telemetry/opentelemetry-android-contrib/blob/main/LICENSE
