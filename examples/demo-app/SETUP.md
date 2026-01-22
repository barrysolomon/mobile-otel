# Quick Setup Guide

Get the demo app running with Dash0 in 3 minutes.

## Prerequisites

- Android Studio or Android SDK CLI tools
- Java 17+
- Dash0 account ([sign up at dash0.com](https://dash0.com))

---

## 🚀 Quick Start

### 1. Configure Credentials

**Option A: Using local.properties** (Recommended)

```bash
cd examples/demo-app/android

# Copy template
cp local.properties.template local.properties

# Edit with your values
nano local.properties
```

Fill in:
```properties
sdk.dir=/Users/YOUR_USERNAME/Library/Android/sdk
dash0.endpoint=https://ingress.dash0.com:4317
dash0.authToken=YOUR_DASH0_TOKEN_HERE  # Get from dash0.com
dash0.dataset=mobile-production
```

**Option B: Using environment variables**

```bash
export DASH0_ENDPOINT=https://ingress.dash0.com:4317
export DASH0_AUTH_TOKEN=your_token_here
export DASH0_DATASET=mobile-production
```

---

### 2. Build and Run

**Debug build** (uses local collector):
```bash
./gradlew installDebug
```

**Release build** (uses Dash0):
```bash
./gradlew assembleRelease

# Check configuration was injected
cat src/release/assets/otel-config.json | grep "dash0.com"
```

---

### 3. Verify Telemetry

1. Open the demo app
2. Tap any scenario button (UI Freeze, Crash, etc.)
3. Check Dash0 dashboard: [app.dash0.com](https://app.dash0.com)
4. Look for service name: `otel-mobile-demo`

---

## 🌍 Regional Endpoints

| Your Location | Endpoint | Config |
|---------------|----------|--------|
| **US/Americas** | `https://ingress.dash0.com:4317` | `dash0.endpoint=https://ingress.dash0.com:4317` |
| **Europe** | `https://ingress.eu-west-1.aws.dash0.com:4317` | `dash0.endpoint=https://ingress.eu-west-1.aws.dash0.com:4317` |

---

## 🔧 Troubleshooting

### Build fails: "Token not set"

```bash
# Verify your config
cat android/local.properties | grep dash0

# Should show:
# dash0.authToken=auth_...
```

### No data in Dash0

1. **Check token**: Visit [dash0.com/settings](https://dash0.com/settings) → API Tokens
2. **Check endpoint**: US users must use `.dash0.com`, EU users must use `.eu-west-1.aws.dash0.com`
3. **Check logs**:
   ```bash
   adb logcat | grep -i "otel\|dash0"
   ```

### "Connection refused"

- **Debug build**: Start local collector first:
  ```bash
  docker-compose -f k8s/docker-compose.yml up -d
  ```
- **Release build**: Check internet connection and firewall

---

## 📖 More Documentation

- **[CONFIGURATION_GUIDE.md](CONFIGURATION_GUIDE.md)** - Complete configuration reference
- **[AUTHENTICATION_SETUP.md](../../AUTHENTICATION_SETUP.md)** - Auth providers guide
- **[QUICKSTART.md](../../QUICKSTART.md)** - Full project quickstart

---

## 💡 Tips

1. **Use debug builds for development** - Faster, local-only, more verbose logging
2. **Use release builds for testing Dash0** - Production-like, battery-efficient
3. **Never commit tokens** - Always use local.properties or env vars
4. **Check build logs** - Look for "✅ Generated otel-config.json" message

---

**Need help?** Open an issue or check the [CONFIGURATION_GUIDE.md](CONFIGURATION_GUIDE.md) for advanced topics.
