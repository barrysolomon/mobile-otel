# Authentication Setup Guide

**Last Updated**: January 21, 2026

This guide explains how to configure the OpenTelemetry Android demo app to work with authenticated OTLP endpoints like Dash0, Honeycomb, Lightstep, and other cloud observability platforms.

---

## Overview

The demo app now supports:
- **Bearer token authentication** (via Authorization header - **works for both gRPC and HTTP**)
- **Multi-tenant dataset/tenant IDs** (via custom headers like Dash0-Dataset)
- **Both gRPC and HTTP OTLP protocols** with protocol selector
- **Dynamic endpoint hints** based on selected protocol

**Important**: The same Bearer token authentication works for both gRPC and HTTP protocols. The app automatically includes the token in gRPC metadata or HTTP headers depending on your protocol selection.

All authentication is configured via the Settings UI - no code changes required.

---

## Quick Start: Dash0 Configuration

### Step 1: Get Your Credentials

From your Dash0 account:
1. Navigate to Settings → API Tokens
2. Create or copy your authentication token (e.g., `YOUR_AUTH_TOKEN_HERE`)
3. Note your dataset name (e.g., `pi5-k3s`)

### Step 2: Configure the App

1. Launch the demo app
2. Tap the menu (⋮) → **Settings**
3. Configure the following fields:

**For gRPC (recommended)**:
```
Protocol: Select "gRPC (port 4317)"
Collector Endpoint: https://your-collector-endpoint:4317
Authorization Token: YOUR_AUTH_TOKEN_HERE
Dataset: pi5-k3s
```

**For HTTP**:
```
Protocol: Select "HTTP (path /v1/signal)"
Collector Endpoint: https://your-collector-endpoint/v1/logs
Authorization Token: YOUR_AUTH_TOKEN_HERE
Dataset: pi5-k3s
```

**Note**: The Authorization Token is the same for both protocols. The app automatically formats it correctly (as gRPC metadata or HTTP header) based on your protocol selection.

4. Tap **Save**
5. **Restart the app** for changes to take effect

### Step 3: Verify

1. Run any demo scenario (e.g., Scenario A: UI Freeze)
2. Check your Dash0 dashboard for incoming telemetry
3. Events should appear with service name: `otel-mobile-demo`

---

## How Authentication Works

### Header Injection

When you configure auth token and dataset in Settings, the app automatically builds headers:

```kotlin
headers = mapOf(
    "Authorization" to "Bearer $authToken",
    "Dash0-Dataset" to "$dataset"
)
```

These headers are passed to the OTLP exporter and included in every export request.

### Implementation Details

**ConfigManager.kt** handles credential storage:
- Auth token stored in SharedPreferences (MODE_PRIVATE)
- Dataset stored in SharedPreferences
- Headers map built dynamically from stored values
- Empty headers omitted from configuration

**MobileConfig.kt** accepts headers parameter:
```kotlin
MobileConfig(
    collectorEndpoint = "https://ingress.dash0.com:4317",
    headers = mapOf(
        "Authorization" to "Bearer auth_token",
        "Dash0-Dataset" to "my-dataset"
    ),
    // ... other config
)
```

**OTLP Exporter** receives headers and includes them in gRPC metadata or HTTP headers.

---

## Supported Platforms

### Dash0

**Endpoints**:
- gRPC: `https://ingress.{region}.aws.dash0.com:4317`
- HTTP: `https://ingress.{region}.aws.dash0.com/v1/{signal}`

**Regions**: `us-west-2`, `eu-central-1`, etc.

**Required Headers**:
- `Authorization: Bearer {token}`
- `Dash0-Dataset: {dataset}` (optional, for multi-tenancy)

**Signals**:
- Logs: `/v1/logs`
- Traces: `/v1/traces`
- Metrics: `/v1/metrics`

### Honeycomb

**Endpoint**:
```
https://api.honeycomb.io:443
```

**Required Headers**:
- `x-honeycomb-team: {api_key}` (use Authorization Token field)
- `x-honeycomb-dataset: {dataset}` (use Dataset field)

**Configuration**:
```
Endpoint: https://api.honeycomb.io:443
Authorization Token: your_honeycomb_api_key
Dataset: your_dataset_name
```

Note: You'll need to manually edit the header names in ConfigManager if not using standard Bearer auth.

### Lightstep

**Endpoint**:
```
https://ingest.lightstep.com:443
```

**Required Headers**:
- `lightstep-access-token: {token}` (use Authorization Token field)

### Generic Cloud Collector

Any OTLP endpoint that requires Bearer authentication:

```
Endpoint: https://your-collector.example.com:4317
Authorization Token: your_bearer_token
```

---

## Security Considerations

### Current Implementation

**✅ Good**:
- Credentials stored in SharedPreferences MODE_PRIVATE (not world-readable)
- Auth token field uses `inputType="textPassword"` (masked in UI)
- Tokens not logged or exposed in debug output

**⚠️ Limitations** (acceptable for demo, not for production):
- SharedPreferences not encrypted
- Tokens visible in Android backup if enabled
- No certificate pinning for HTTPS

### Production Recommendations

For a production app, implement these security enhancements:

1. **Use EncryptedSharedPreferences** (Jetpack Security):
```kotlin
val masterKey = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()

val encryptedPrefs = EncryptedSharedPreferences.create(
    context,
    "otel_config_secure",
    masterKey,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)
```

2. **Disable Android Backup** for sensitive data:
```xml
<application
    android:allowBackup="false"
    android:fullBackupContent="false">
```

3. **Certificate Pinning** for HTTPS:
```kotlin
val certificatePinner = CertificatePinner.Builder()
    .add("ingress.dash0.com", "sha256/AAAAAAAAAA...")
    .build()
```

4. **Token Rotation**: Implement periodic token refresh from secure backend

5. **ProGuard/R8**: Obfuscate code to prevent reverse engineering

6. **Root Detection**: Warn users if device is rooted

---

## Troubleshooting

### "Export failed after 4 attempts"

**Possible causes**:
1. **Wrong endpoint** - Verify URL is correct (check region, port, protocol)
2. **Invalid token** - Token may be expired or incorrect
3. **Network issues** - Check internet connectivity
4. **Missing dataset** - Some platforms require dataset header

**Solutions**:
- Test with curl to verify credentials:
```bash
curl https://your-collector-endpoint/v1/traces \
  -X POST \
  -H "Authorization: Bearer YOUR_AUTH_TOKEN_HERE" \
  -H "Dash0-Dataset: pi5-k3s" \
  -H "Content-Type: application/json" \
  -d '{"resourceSpans":[]}'
```

- Check logcat for detailed error messages:
```bash
adb logcat | grep -E "(OTEL|RetryableExporter|MobileLogR)"
```

### "Settings saved but not working"

**Solution**: Restart the app! Configuration changes only take effect after restart.

### "Token field is empty after saving"

**Cause**: Password input fields don't display saved values by default.

**Verification**: Configuration is saved correctly. Check by:
1. Restart app
2. Run a scenario
3. Check if export succeeds (no retry errors in logcat)

### "Unauthorized (401) error"

**Solutions**:
1. Verify token is correct and not expired
2. Check token has correct permissions
3. Ensure token includes no extra whitespace
4. Regenerate token in your platform's settings

---

## Configuration Examples

### Local Collector (No Auth)

```
Endpoint: http://10.0.2.2:4317
Authorization Token: (leave empty)
Dataset: (leave empty)
```

### Dash0 US West 2

```
Endpoint: https://your-collector-endpoint:4317
Authorization Token: YOUR_AUTH_TOKEN_HERE
Dataset: production-mobile
```

### Dash0 HTTP (Logs only)

```
Endpoint: https://your-collector-endpoint/v1/logs
Authorization Token: YOUR_AUTH_TOKEN_HERE
Dataset: production-mobile
```

### Testing with Multiple Datasets

**Dataset 1** (Production):
```
Dataset: production-mobile
```

**Dataset 2** (Staging):
```
Dataset: staging-mobile
```

Switch between them in Settings to route telemetry to different datasets.

---

## API Reference

### ConfigManager Methods

```kotlin
// Save auth token
ConfigManager.saveAuthToken(context, "auth_token_here")

// Get auth token
val token = ConfigManager.getAuthToken(context)

// Save dataset
ConfigManager.saveDataset(context, "my-dataset")

// Get dataset
val dataset = ConfigManager.getDataset(context)

// Load full config (includes headers)
val config = ConfigManager.loadConfig(context)
// config.headers = mapOf("Authorization" to "Bearer ...", "Dash0-Dataset" to "...")
```

### MobileConfig Headers

```kotlin
val config = MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "https://collector.example.com:4317",
    headers = mapOf(
        "Authorization" to "Bearer token_here",
        "Dash0-Dataset" to "dataset_here",
        "Custom-Header" to "custom_value"
    )
)
```

Headers are passed to the OTLP exporter and included in all requests.

---

## Testing Authentication

### Test with curl (Dash0 Logs)

```bash
curl https://your-collector-endpoint/v1/logs \
  -X POST \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Dash0-Dataset: YOUR_DATASET" \
  -H "Content-Type: application/json" \
  -d '{
    "resourceLogs": [{
      "resource": {
        "attributes": [{
          "key": "service.name",
          "value": {"stringValue": "auth-test"}
        }]
      },
      "scopeLogs": [{
        "logRecords": [{
          "body": {"stringValue": "Test log"},
          "timeUnixNano": "'$(date +%s)000000000'"
        }]
      }]
    }]
  }'
```

Expected response: `200 OK` or `202 Accepted`

### Test with curl (Dash0 Traces)

```bash
curl https://your-collector-endpoint/v1/traces \
  -X POST \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Dash0-Dataset: YOUR_DATASET" \
  -H "Content-Type: application/json" \
  -d '{
    "resourceSpans": [{
      "resource": {
        "attributes": [{
          "key": "service.name",
          "value": {"stringValue": "auth-test"}
        }]
      },
      "scopeSpans": [{
        "spans": [{
          "name": "Test Span",
          "kind": 1,
          "startTimeUnixNano": "'$(date +%s)000000000'",
          "endTimeUnixNano": "'$(date +%s)000000000'",
          "traceId": "849250e8aba6b3a1b5268ced0823565d",
          "spanId": "a9c3c029d1580fff"
        }]
      }]
    }]
  }'
```

---

## Next Steps

1. **Configure your endpoint** in Settings
2. **Run demo scenarios** to generate telemetry
3. **Verify in your platform** (Dash0, Honeycomb, etc.)
4. **Customize service name** to match your app
5. **Adjust buffer sizes** for your use case

For production deployment, see [DEPLOYMENT_GUIDE.md](docs/guides/DEPLOYMENT_GUIDE.md) for additional considerations.

---

**Created**: January 21, 2026
**Status**: ✅ Authentication fully implemented and tested
**Build**: examples/demo-app/android/build/outputs/apk/debug/android-debug.apk
