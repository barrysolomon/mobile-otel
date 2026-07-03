# Global Deployment Guide

Deploy the mobile observability demo app to users worldwide with automatic region selection and secure credential management.

## 🌍 Multi-Region Strategy

### Dash0 Regional Infrastructure

| Region | Endpoint | Latency (from region) | Best For |
|--------|----------|----------------------|----------|
| **US** | `ingress.us-west-2.aws.dash0.com:4317` | <100ms Americas | North/South America |
| **EU** | `ingress.eu-west-1.aws.dash0.com:4317` | <50ms Europe | Europe, Africa, Middle East |

### Automatic Region Selection

Implement geo-based endpoint selection in your app:

```kotlin
// Add to ConfigManager.kt
fun getOptimalDash0Endpoint(): String {
    val timezone = TimeZone.getDefault().id
    return when {
        timezone.startsWith("Europe/") ||
        timezone.startsWith("Africa/") ->
            "https://ingress.eu-west-1.aws.dash0.com:4317"

        else -> // Americas, Asia, Pacific
            "https://ingress.us-west-2.aws.dash0.com:4317"
    }
}
```

Or use network-based detection:

```kotlin
suspend fun getOptimalDash0Endpoint(): String = withContext(Dispatchers.IO) {
    // Ping both endpoints, use faster one
    val usLatency = measureLatency("https://ingress.us-west-2.aws.dash0.com:4317")
    val euLatency = measureLatency("https://ingress.eu-west-1.aws.dash0.com:4317")

    if (euLatency < usLatency) {
        "https://ingress.eu-west-1.aws.dash0.com:4317"
    } else {
        "https://ingress.us-west-2.aws.dash0.com:4317"
    }
}
```

---

## 🔐 Production Security Model

### Token Distribution Architecture

```
┌─────────────────────────────────────────┐
│ CI/CD Pipeline (GitHub Actions)         │
│ Secrets: DASH0_US_TOKEN, DASH0_EU_TOKEN │
└──────────────┬──────────────────────────┘
               │
               ├─── US Build (Americas)
               │    ├── Endpoint: ingress.us-west-2.aws.dash0.com
               │    └── Token: DASH0_US_TOKEN
               │
               └─── EU Build (Europe/Africa/ME)
                    ├── Endpoint: ingress.eu-west-1.aws.dash0.com
                    └── Token: DASH0_EU_TOKEN
```

### Build Flavors for Regions

```kotlin
// app/build.gradle.kts
android {
    flavorDimensions += listOf("region", "environment")

    productFlavors {
        // Regions
        create("us") {
            dimension = "region"
            buildConfigField("String", "DASH0_ENDPOINT",
                "\"https://ingress.us-west-2.aws.dash0.com:4317\"")
            buildConfigField("String", "DASH0_REGION", "\"US\"")
        }

        create("eu") {
            dimension = "region"
            buildConfigField("String", "DASH0_ENDPOINT",
                "\"https://ingress.eu-west-1.aws.dash0.com:4317\"")
            buildConfigField("String", "DASH0_REGION", "\"EU\"")
        }

        create("global") {
            dimension = "region"
            // Auto-selects best region at runtime
            buildConfigField("String", "DASH0_ENDPOINT", "\"AUTO\"")
            buildConfigField("String", "DASH0_REGION", "\"AUTO\"")
        }

        // Environments
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }

        create("staging") {
            dimension = "environment"
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
        }

        create("prod") {
            dimension = "environment"
        }
    }
}
```

This generates builds like:
- `usDevDebug` - US region, development, debug
- `euProdRelease` - EU region, production, release
- `globalProdRelease` - Auto-region, production, release

---

## 🏭 CI/CD Pipeline Examples

### GitHub Actions (Multi-Region)

```yaml
# .github/workflows/build-multi-region.yml
name: Build Multi-Region Apps

on:
  push:
    branches: [main, release/*]
  workflow_dispatch:

jobs:
  build-us:
    name: Build US Release
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Build US Release APK
        env:
          DASH0_ENDPOINT: https://ingress.us-west-2.aws.dash0.com:4317
          DASH0_AUTH_TOKEN: ${{ secrets.DASH0_US_TOKEN }}
          DASH0_DATASET: mobile-production-us
        run: |
          cd examples/demo-app/android
          ./gradlew assembleUsProdRelease

      - name: Upload US APK
        uses: actions/upload-artifact@v3
        with:
          name: app-us-release.apk
          path: examples/demo-app/android/app/build/outputs/apk/usProd/release/*.apk

  build-eu:
    name: Build EU Release
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Build EU Release APK
        env:
          DASH0_ENDPOINT: https://ingress.eu-west-1.aws.dash0.com:4317
          DASH0_AUTH_TOKEN: ${{ secrets.DASH0_EU_TOKEN }}
          DASH0_DATASET: mobile-production-eu
        run: |
          cd examples/demo-app/android
          ./gradlew assembleEuProdRelease

      - name: Upload EU APK
        uses: actions/upload-artifact@v3
        with:
          name: app-eu-release.apk
          path: examples/demo-app/android/app/build/outputs/apk/euProd/release/*.apk

  build-global:
    name: Build Global Release (Auto-Region)
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Build Global Release APK
        env:
          DASH0_AUTH_TOKEN: ${{ secrets.DASH0_GLOBAL_TOKEN }}
          DASH0_DATASET: mobile-production-global
        run: |
          cd examples/demo-app/android
          ./gradlew assembleGlobalProdRelease

      - name: Upload Global APK
        uses: actions/upload-artifact@v3
        with:
          name: app-global-release.apk
          path: examples/demo-app/android/app/build/outputs/apk/globalProd/release/*.apk
```

### Google Play Console Distribution

```yaml
# .github/workflows/deploy-play-store.yml
name: Deploy to Play Store

on:
  push:
    tags:
      - 'v*'

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Build AAB (Global)
        env:
          DASH0_AUTH_TOKEN: ${{ secrets.DASH0_GLOBAL_TOKEN }}
          DASH0_DATASET: mobile-production
        run: |
          cd examples/demo-app/android
          ./gradlew bundleGlobalProdRelease

      - name: Sign AAB
        uses: r0adkll/sign-android-release@v1
        with:
          releaseDirectory: examples/demo-app/android/app/build/outputs/bundle/globalProdRelease
          signingKeyBase64: ${{ secrets.SIGNING_KEY }}
          alias: ${{ secrets.KEY_ALIAS }}
          keyStorePassword: ${{ secrets.KEY_STORE_PASSWORD }}
          keyPassword: ${{ secrets.KEY_PASSWORD }}

      - name: Upload to Play Store
        uses: r0adkll/upload-google-play@v1
        with:
          serviceAccountJsonPlainText: ${{ secrets.PLAY_SERVICE_ACCOUNT }}
          packageName: com.example.demo
          releaseFiles: examples/demo-app/android/app/build/outputs/bundle/globalProdRelease/*.aab
          track: production
```

---

## 📊 Dataset Strategy

### Organize by Region + Environment

```
mobile-production-us       # US production users
mobile-production-eu       # EU production users
mobile-staging-global      # Global staging
mobile-dev-{developer}     # Individual dev datasets
```

### Configure in CI/CD

```bash
# Set dataset based on build variant
DASH0_DATASET="mobile-${ENVIRONMENT}-${REGION}"
```

---

## 🎯 Token Rotation Strategy

### Monthly Rotation Schedule

```bash
# Month 1: Create new tokens
gh secret set DASH0_US_TOKEN_NEW --body "new_us_token"
gh secret set DASH0_EU_TOKEN_NEW --body "new_eu_token"

# Month 2: Switch to new tokens
gh secret set DASH0_US_TOKEN --body "new_us_token"
gh secret set DASH0_EU_TOKEN --body "new_eu_token"

# Month 3: Delete old tokens in Dash0 dashboard
```

### Emergency Rotation (Breach)

1. **Immediate**: Revoke compromised token in Dash0 dashboard
2. **Generate**: Create new token with different name
3. **Deploy**: Emergency release with new token
4. **Force Update**: Push mandatory app update

---

## 🚀 Deployment Checklist

### Pre-Release

- [ ] Verify token rotation date (< 90 days old)
- [ ] Test both US and EU endpoints
- [ ] Validate dataset permissions
- [ ] Check rate limits for your plan
- [ ] Review telemetry volume projections
- [ ] Test token fallback behavior

### Release

- [ ] Build region-specific APKs/AABs
- [ ] Verify injected credentials (check logs)
- [ ] Upload to Play Store/App Store
- [ ] Monitor first 1000 installs in Dash0
- [ ] Check error rates by region

### Post-Release

- [ ] Monitor telemetry ingestion rates
- [ ] Verify geo-distribution in Dash0
- [ ] Check for authentication errors
- [ ] Review bandwidth usage by region
- [ ] Analyze crash rates by build variant

---

## 🔍 Monitoring & Observability

### Key Metrics to Track

| Metric | Target | Alert Threshold |
|--------|--------|-----------------|
| **Ingestion Success Rate** | >99.5% | <98% |
| **Auth Failure Rate** | <0.1% | >1% |
| **Average Latency (US)** | <150ms | >500ms |
| **Average Latency (EU)** | <100ms | >300ms |
| **Token Expiry Days** | >30 | <14 |

### Dash0 Queries

```
# Auth failures by region
error.type:"authentication_failed"
  | stats count by dash0.region

# Latency by endpoint
http.url:*dash0.com*
  | stats avg(duration_ms) by http.host

# Token usage
telemetry.sdk.name:"opentelemetry-android"
  | stats count by dash0.dataset
```

---

## 🛡️ Security Hardening

### Certificate Pinning (Optional)

For high-security apps, pin Dash0 certificates:

```kotlin
val certificatePinner = CertificatePinner.Builder()
    .add("ingress.us-west-2.aws.dash0.com", "sha256/AAAAAAAAAA...")
    .add("ingress.eu-west-1.aws.dash0.com", "sha256/BBBBBBBBBB...")
    .build()
```

### Token Validation

Add runtime token validation:

```kotlin
fun validateDash0Token(token: String): Boolean {
    // Basic format check
    if (!token.startsWith("auth_") || token.length < 32) {
        return false
    }

    // Optional: Test token with HEAD request
    val testUrl = "$dash0Endpoint/health"
    val response = httpClient.head(testUrl) {
        headers {
            append("Authorization", "Bearer $token")
        }
    }

    return response.status == HttpStatusCode.OK
}
```

---

## 📱 App Store Compliance

### Privacy Labels (iOS)

```
Data Used for Tracking: NO
Data Linked to You: NO
Data Not Linked to You: YES
  - Diagnostics: Crash Data, Performance Data
```

### Google Play Data Safety

```
Data Collection: YES
  - App activity (crashes, performance)
  - Device IDs (for debugging only)
  - App info and performance

Data Sharing: YES (with observability provider)
Data Security: Encrypted in transit
Data Deletion: Available on request
```

---

## 💰 Cost Optimization

### Tiered Dataset Strategy

```
Free Tier (100GB/month):
  - mobile-dev-*           # Developer builds

Standard Tier (1TB/month):
  - mobile-staging-global  # Staging environment

Pro Tier (10TB/month):
  - mobile-production-us   # US production
  - mobile-production-eu   # EU production
```

### Sampling by Region

High-volume regions use lower sampling rates:

```kotlin
val samplingRate = when (BuildConfig.DASH0_REGION) {
    "US" -> 0.05  // 5% (high volume)
    "EU" -> 0.10  // 10% (medium volume)
    else -> 0.20  // 20% (low volume)
}
```

---

## 📞 Support Contacts

- **Dash0 Support**: support@dash0.com
- **Status Page**: status.dash0.com
- **API Docs**: docs.dash0.com/api

---

**Ready to deploy globally?** Follow the [SETUP.md](SETUP.md) guide to get started.
