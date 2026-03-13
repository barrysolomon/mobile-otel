# Deployment Guide

> **Note:** Full-stack deployment (gateway + control plane UI + Kubernetes manifests) has moved to the
> [mobile-otel-control-plane](https://github.com/barrysolomon/mobile-otel-control-plane) repository.
> This guide covers collector-only deployment and Android SDK setup.

## Deployment Options

| Option | Best For | What You Need |
| ------ | -------- | ------------- |
| **Dash0 cloud** | Production (simplest) | Dash0 account + auth token |
| **Docker (otelcol-mobile)** | Development / self-hosted | Docker installed |
| **Kubernetes** | Production self-hosted | k8s cluster |

## Option 1: Dash0 Cloud (Recommended)

No collector deployment needed. Point the SDK directly at Dash0:

```kotlin
val config = MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "https://ingress.dash0.com:4317",
    headers = mapOf(
        "Authorization" to "Bearer YOUR_DASH0_TOKEN",
        "Dash0-Dataset" to "mobile-prod"
    )
)
OTelMobile.start(application, config)
```

## Option 2: Docker (Local / Self-Hosted)

### Build the custom collector

```bash
cd collector-processor/
docker build -t otelcol-mobile:latest .
```

### Run with default config

```bash
docker run -p 4317:4317 -p 4318:4318 -p 13133:13133 otelcol-mobile:latest
```

### Run with custom config

```bash
docker run \
  -p 4317:4317 -p 4318:4318 \
  -v $(pwd)/my-config.yaml:/app/config.yaml:ro \
  otelcol-mobile:latest
```

### Point the SDK at the collector

```kotlin
val config = MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "http://10.0.2.2:4317"  // Android emulator -> host
)
```

## Option 3: Kubernetes

See the [mobile-otel-control-plane](https://github.com/barrysolomon/mobile-otel-control-plane) repository
for Kubernetes manifests, Helm charts, and full-stack deployment instructions.

## Android SDK Setup

Regardless of deployment option, the Android SDK setup is the same:

1. Include the SDK as a Gradle module (see [Android SDK Guide](../ANDROID_SDK_GUIDE.md))
2. Initialize in your `Application.onCreate()`
3. Configure the collector endpoint via `MobileConfig` or bundled `otel-config.json`

See [Bundled Configuration](../BUNDLED_CONFIG.md) for shipping pre-configured settings with your app.

## Verification

```bash
# Check collector health (Docker or k8s port-forward)
curl http://localhost:13133/

# Check Android SDK is exporting
adb logcat | grep "OTelMobile\|MobileOtel"
```

## Related Documentation

- [Collector Processor](../../collector-processor/README.md) - Custom collector build details
- [Operations Guide](../OPERATIONS_GUIDE.md) - Production operations
- [Export Modes](../EXPORT_MODES.md) - CONDITIONAL vs CONTINUOUS vs HYBRID
- [Bundled Configuration](../BUNDLED_CONFIG.md) - Ship config with your app
