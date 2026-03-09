# Mobile OTEL Demo App - Configuration Guide

This guide explains how to configure the demo app with Dash0 credentials for development and production.

## 🔐 Security Model

**NEVER commit tokens to git!** This project uses environment variables and build-time injection to keep credentials secure.

```
❌ DON'T: Hardcode tokens in code or config files
✅ DO: Use environment variables + .gitignore
✅ DO: Use different configs for dev/release builds
✅ DO: Inject tokens at build time from CI/CD
```

---

## 🏗️ Build Variant Strategy

### Debug Build (Development)
- **Config**: `src/debug/assets/otel-config.json`
- **Endpoint**: Local collector at `http://10.0.2.2:4317`
- **Export Mode**: CONTINUOUS (fast feedback)
- **Purpose**: Local development, testing, debugging

### Release Build (Production)
- **Config**: `src/release/assets/otel-config.json` (generated from template)
- **Endpoint**: Dash0 ingress (US or EU region)
- **Export Mode**: CONDITIONAL (battery-efficient)
- **Purpose**: Production deployments, TestFlight, Play Store

---

## 🚀 Quick Setup

### Option 1: Local Development (Recommended)

1. **Create local.properties** (already gitignored):
```properties
# examples/demo-app/android/local.properties
dash0.endpoint=https://ingress.dash0.com:4317
dash0.authToken=YOUR_DASH0_TOKEN_HERE
dash0.dataset=mobile-dev
```

2. **Build and run**:
```bash
cd examples/demo-app/android
./gradlew assembleRelease
```

The build system will automatically inject your credentials into `otel-config.json`.

---

### Option 2: Environment Variables (CI/CD)

1. **Set environment variables**:
```bash
export DASH0_ENDPOINT=https://ingress.dash0.com:4317
export DASH0_AUTH_TOKEN=your_token_here
export DASH0_DATASET=mobile-production
```

2. **Build**:
```bash
./gradlew assembleRelease
```

---

### Option 3: Runtime Configuration (Testing)

Use the Configuration Activity in the app:

1. Open app → Menu → **Configuration**
2. Set **Collector Endpoint**: `https://ingress.dash0.com:4317`
3. Set **Auth Token**: Your Dash0 token
4. Set **Dataset**: `mobile-production`
5. **Save** and restart app

---

## 🌍 Dash0 Regions

Choose the appropriate endpoint for your region:

| Region | Endpoint | Environment Variable |
|--------|----------|----------------------|
| **US** | `https://ingress.dash0.com:4317` | `DASH0_ENDPOINT=https://ingress.dash0.com:4317` |
| **EU** | `https://ingress.eu-west-1.aws.dash0.com:4317` | `DASH0_ENDPOINT=https://ingress.eu-west-1.aws.dash0.com:4317` |

The build system automatically detects the region and logs it during the build.

---

## 📁 File Structure

```
examples/demo-app/
├── .env.template                          # Template for environment variables
├── .gitignore                              # Ignores .env and local.properties
├── CONFIGURATION_GUIDE.md                  # This file
└── android/
    ├── local.properties                    # Your local config (gitignored)
    ├── build-config-inject.gradle          # Credential injection logic
    ├── process-config-template.gradle      # Template processor
    ├── src/
    │   ├── debug/
    │   │   └── assets/
    │   │       └── otel-config.json        # Dev config (local collector)
    │   └── release/
    │       └── assets/
    │           ├── otel-config.json.template   # Production template
    │           └── otel-config.json            # Generated (gitignored)
    └── build.gradle.kts                    # Integrates Gradle scripts
```

---

## 🔧 Build Script Integration

Add to your `build.gradle.kts`:

```kotlin
// examples/demo-app/android/build.gradle.kts

apply(from = "build-config-inject.gradle")
apply(from = "process-config-template.gradle")

android {
    buildTypes {
        release {
            // Config will be auto-generated from template
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        debug {
            // Uses src/debug/assets/otel-config.json directly
            applicationIdSuffix = ".debug"
        }
    }
}
```

---

## 🔍 Verifying Configuration

After building, check the logs:

```bash
./gradlew assembleRelease | grep "otel-config"
```

Expected output:
```
✅ Generated otel-config.json from template
   Endpoint: https://ingress.dash0.com:4317
   Region: US
   Dataset: mobile-production
   Token: ✅ SET
```

---

## 🚨 Troubleshooting

### "Token: ❌ NOT SET"

**Cause**: Environment variable or local.properties not set.

**Fix**:
```bash
# Option 1: Set environment variable
export DASH0_AUTH_TOKEN=your_token_here

# Option 2: Add to local.properties
echo "dash0.authToken=your_token_here" >> android/local.properties

# Rebuild
./gradlew clean assembleRelease
```

### "Connection refused" to Dash0

**Cause**: Wrong endpoint or invalid token.

**Fix**:
1. Verify endpoint matches your region (US vs EU)
2. Check token is valid at https://dash0.com
3. Ensure `Authorization` header format: `Bearer <token>`

### Build fails with "template not found"

**Cause**: Template file missing in src/release/assets.

**Fix**:
```bash
# Create template if missing
cp src/debug/assets/otel-config.json src/release/assets/otel-config.json.template

# Edit template and replace values with placeholders:
# "collectorEndpoint": "${DASH0_ENDPOINT}",
# "Authorization": "Bearer ${DASH0_AUTH_TOKEN}",
```

---

## 🔒 Security Best Practices

### ✅ DO
- Use environment variables for tokens
- Store tokens in local.properties (gitignored)
- Inject tokens at build time from CI/CD secrets
- Use different datasets for dev/staging/prod
- Rotate tokens periodically

### ❌ DON'T
- Commit tokens to git (even in private repos)
- Hardcode tokens in source code
- Share tokens in Slack/email
- Use production tokens in debug builds
- Log tokens in plaintext

---

## 🎯 CI/CD Examples

### GitHub Actions

```yaml
# .github/workflows/build.yml
name: Build Release
on: push

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Set up JDK
        uses: actions/setup-java@v3
        with:
          java-version: '17'

      - name: Build Release APK
        env:
          DASH0_ENDPOINT: ${{ secrets.DASH0_ENDPOINT }}
          DASH0_AUTH_TOKEN: ${{ secrets.DASH0_AUTH_TOKEN }}
          DASH0_DATASET: ${{ secrets.DASH0_DATASET }}
        run: |
          cd examples/demo-app/android
          ./gradlew assembleRelease

      - name: Upload APK
        uses: actions/upload-artifact@v3
        with:
          name: app-release.apk
          path: examples/demo-app/android/app/build/outputs/apk/release/
```

### GitLab CI

```yaml
# .gitlab-ci.yml
build:release:
  stage: build
  image: openjdk:17-jdk
  variables:
    DASH0_ENDPOINT: "https://ingress.dash0.com:4317"
  script:
    - export DASH0_AUTH_TOKEN=$DASH0_TOKEN_SECRET
    - export DASH0_DATASET=$DASH0_DATASET_SECRET
    - cd examples/demo-app/android
    - ./gradlew assembleRelease
  artifacts:
    paths:
      - examples/demo-app/android/app/build/outputs/apk/release/
```

---

## 📚 Related Documentation

- [Dash0 Documentation](https://dash0.com/docs)
- [OpenTelemetry Android SDK](https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/main/instrumentation/android)
- [AUTHENTICATION_SETUP.md](../../AUTHENTICATION_SETUP.md) - General auth guide
- [BUNDLED_CONFIG.md](../../docs/BUNDLED_CONFIG.md) - Config system details

---

## 💡 Pro Tips

1. **Multiple Environments**: Use build flavors for dev/staging/prod
```kotlin
android {
    flavorDimensions += "environment"
    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            resValue("string", "dash0_dataset", "mobile-dev")
        }
        create("prod") {
            dimension = "environment"
            resValue("string", "dash0_dataset", "mobile-production")
        }
    }
}
```

2. **Token Validation**: Add build-time validation
```groovy
task validateDash0Config {
    doLast {
        def token = getDash0AuthToken()
        if (token.isEmpty() && gradle.taskGraph.hasTask(':app:assembleRelease')) {
            throw new GradleException("❌ DASH0_AUTH_TOKEN not set for release build!")
        }
    }
}
```

3. **Region Auto-Selection**: Use geolocation to pick closest region
```kotlin
val region = if (isEuropeTimezone()) "eu-west-1" else "us"
val endpoint = "https://ingress${if (region == "eu-west-1") ".$region.aws" else ""}.dash0.com:4317"
```

---

**Need help?** Open an issue or contact the maintainers.
