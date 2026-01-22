# OpenTelemetry Mobile Demo App

Production-ready Android demo showcasing mobile observability with Dash0 integration.

## ⚡ Quick Start

```bash
# 1. Configure credentials
cd android
cp local.properties.template local.properties
# Edit local.properties with your Dash0 token

# 2. Build and run
./gradlew assembleRelease

# 3. Verify in Dash0
# Open app.dash0.com and look for service: otel-mobile-demo
```

**First time?** Read [SETUP.md](SETUP.md) for detailed instructions.

---

## 📚 Documentation

| Document | Purpose | Audience |
|----------|---------|----------|
| **[SETUP.md](SETUP.md)** | Quick start guide (3 min) | Developers |
| **[CONFIGURATION_GUIDE.md](CONFIGURATION_GUIDE.md)** | Complete config reference | DevOps, Developers |
| **[GLOBAL_DEPLOYMENT.md](GLOBAL_DEPLOYMENT.md)** | Multi-region deployment | DevOps, Platform Teams |

---

## 🏗️ Build Variants

### Debug (Local Development)
- **Endpoint**: Local collector (`http://10.0.2.2:4317`)
- **Export**: CONTINUOUS (every 10s)
- **Use**: Local testing, rapid feedback

### Release (Production)
- **Endpoint**: Dash0 (US or EU region)
- **Export**: CONDITIONAL (battery-efficient)
- **Use**: App Store deployment, production testing

---

## 🌍 Global Deployment

Supports worldwide deployment with automatic region selection:

| Region | Endpoint | Config |
|--------|----------|--------|
| **US/Americas** | `ingress.dash0.com:4317` | `dash0.endpoint=https://ingress.dash0.com:4317` |
| **EU/Africa** | `ingress.eu-west-1.aws.dash0.com:4317` | `dash0.endpoint=https://ingress.eu-west-1.aws.dash0.com:4317` |

See [GLOBAL_DEPLOYMENT.md](GLOBAL_DEPLOYMENT.md) for CI/CD examples.

---

## 🔐 Security

**Credentials are NEVER committed to git.**

- ✅ Environment variables (CI/CD)
- ✅ local.properties (developer machines)
- ✅ Build-time injection (Gradle scripts)
- ❌ Hardcoded tokens in source
- ❌ Bundled tokens in APKs

All sensitive files are in `.gitignore`.

---

## 🎯 Features Demonstrated

1. **Offline Resilience**: Crash recovery, disk buffering, retry logic
2. **Battery Efficiency**: Conditional export, adaptive sampling
3. **Device Metrics**: Memory, battery, CPU, network (10 categories)
4. **Log Tailing**: Pattern detection, API error cascades
5. **Lifecycle Tracking**: Force quit detection, ANR recovery
6. **Sampling Strategies**: Dynamic, trace-ID-based, high-priority
7. **Workflow System**: Pre-configured policies for common scenarios

---

## 📱 Demo Scenarios

Open the app and try these scenarios:

| Scenario | Button | What It Does |
|----------|--------|--------------|
| **True ANR** | 🚫 ANR (30s) | Blocks main thread, triggers Android ANR dialog |
| **Crash** | 💥 Crash | Throws exception, demonstrates crash recovery |
| **Network Error** | 🌐 Network Error | HTTP 500 cascade, triggers error flush |
| **Low Memory** | 🧠 Low Memory | Allocates 100MB chunks until OOM |

Plus 6 regular activities (login, navigation, API calls, etc.) and manual flush controls.

---

## 🔧 Configuration

### Option 1: local.properties (Recommended)

```properties
# android/local.properties
sdk.dir=/Users/your-name/Library/Android/sdk
dash0.endpoint=https://ingress.dash0.com:4317
dash0.authToken=YOUR_TOKEN_HERE
dash0.dataset=mobile-production
```

### Option 2: Environment Variables

```bash
export DASH0_ENDPOINT=https://ingress.dash0.com:4317
export DASH0_AUTH_TOKEN=your_token_here
export DASH0_DATASET=mobile-production
./gradlew assembleRelease
```

### Option 3: In-App Configuration

1. Open app → Menu → **Configuration**
2. Fill in endpoint, token, dataset
3. Save and restart

---

## 🚀 CI/CD Integration

### GitHub Actions Example

```yaml
- name: Build Release APK
  env:
    DASH0_ENDPOINT: ${{ secrets.DASH0_ENDPOINT }}
    DASH0_AUTH_TOKEN: ${{ secrets.DASH0_AUTH_TOKEN }}
    DASH0_DATASET: mobile-production
  run: ./gradlew assembleRelease
```

See [GLOBAL_DEPLOYMENT.md](GLOBAL_DEPLOYMENT.md) for complete examples.

---

## 📊 Verifying Data in Dash0

After running the app:

1. Open [app.dash0.com](https://app.dash0.com)
2. Navigate to **Services** → `otel-mobile-demo`
3. Look for recent traces and logs
4. Check attributes: `device_id`, `demo_run_id`, `recovery_type`

Expected data:
- **Traces**: App lifecycle, HTTP requests, background tasks
- **Logs**: 19 event types with OTEL semantic conventions
- **Metrics**: Device health (if enabled)

---

## 🐛 Troubleshooting

### "Token not set" during build

```bash
# Check your config
cat android/local.properties | grep dash0
# Should show: dash0.authToken=auth_...

# Or set environment variable
export DASH0_AUTH_TOKEN=your_token
```

### No data in Dash0

1. **Check token**: Visit dash0.com/settings → API Tokens
2. **Check endpoint**: Verify region (US vs EU)
3. **Check logs**: `adb logcat | grep -i otel`
4. **Check network**: Ensure device has internet

### Build fails

```bash
# Clean build
./gradlew clean
rm -rf .gradle build

# Rebuild
./gradlew assembleRelease --info
```

---

## 🧪 Testing

```bash
# Unit tests
./gradlew test

# Integration tests
./gradlew connectedAndroidTest

# Build verification
./gradlew assembleRelease --dry-run
```

---

## 📂 Project Structure

```
examples/demo-app/
├── README.md                       # This file
├── SETUP.md                        # Quick setup guide
├── CONFIGURATION_GUIDE.md          # Complete config reference
├── GLOBAL_DEPLOYMENT.md            # Multi-region deployment
├── .env.template                   # Environment variable template
└── android/
    ├── local.properties.template   # Local config template
    ├── build-config-inject.gradle  # Token injection script
    ├── process-config-template.gradle  # Config processor
    ├── src/
    │   ├── main/                   # Shared code
    │   ├── debug/                  # Debug-specific (local)
    │   │   └── assets/
    │   │       └── otel-config.json
    │   └── release/                # Release-specific (Dash0)
    │       └── assets/
    │           ├── otel-config.json.template
    │           └── otel-config.json  # Generated at build time
    └── build.gradle.kts
```

---

## 🤝 Contributing

This demo app demonstrates best practices for:
- Secure credential management
- Multi-region deployment
- Battery-efficient telemetry
- OTEL semantic conventions compliance

Found an issue? [Open a ticket](../../issues) or submit a PR.

---

## 📄 License

Apache 2.0 - See [LICENSE](../../LICENSE)

---

## 🔗 Related Links

- [Dash0 Documentation](https://docs.dash0.com)
- [OpenTelemetry Android](https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/main/instrumentation/android)
- [Main Project README](../../README_OTEL_NATIVE.md)
- [Architecture Overview](../../docs/reference/ARCHITECTURE.md)

---

**Questions?** Check [SETUP.md](SETUP.md) or [CONFIGURATION_GUIDE.md](CONFIGURATION_GUIDE.md).
