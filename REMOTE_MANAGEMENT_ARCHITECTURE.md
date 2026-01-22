# Remote Configuration Management Architecture

**Status**: Design Document
**Created**: January 21, 2026

---

## Overview

This document describes the architecture for remote configuration management, allowing a management application to push configuration changes, environment variables, and export policies/workflows to mobile devices in real-time.

---

## Requirements

### Functional Requirements

1. **Remote Configuration Push**
   - Management app can update collector endpoints
   - Management app can modify auth tokens, datasets
   - Management app can change buffer sizes, retry settings
   - Management app can add/modify environment variables

2. **Policy/Workflow Management**
   - Management app can create new export policies
   - Management app can modify existing policies
   - Management app can delete/disable policies
   - Policies take effect without app restart

3. **Real-Time Updates**
   - Devices poll for configuration changes periodically
   - Optional push notifications for immediate updates
   - Graceful fallback if management server unavailable

4. **Security**
   - Authentication for management API
   - Configuration signing/validation
   - Audit logging of configuration changes

5. **Multi-Tenancy**
   - Different configurations per environment (dev/staging/prod)
   - Device groups/segments for targeted rollouts
   - A/B testing support

### Non-Functional Requirements

1. **Performance**
   - Minimal battery impact from polling
   - Efficient diff-based updates
   - Configurable poll intervals

2. **Reliability**
   - Offline resilience (use cached config)
   - Graceful degradation
   - Configuration rollback on failure

3. **Observability**
   - Log configuration changes
   - Track which devices have which config version
   - Monitor config fetch success/failure

---

## Architecture

### System Components

```
┌─────────────────────────────────────────────────────────────┐
│                   Management Application                     │
│  (Web UI / Admin Console / CI/CD Pipeline)                  │
└────────────────┬────────────────────────────────────────────┘
                 │ REST API (HTTPS)
                 ▼
┌─────────────────────────────────────────────────────────────┐
│              Configuration Management Server                 │
│  - Store configurations (PostgreSQL/MongoDB)                │
│  - Serve config via REST API                                │
│  - Version control & audit logging                          │
│  - Device registration & grouping                           │
└────────────────┬────────────────────────────────────────────┘
                 │ HTTPS GET /config
                 ▼
┌─────────────────────────────────────────────────────────────┐
│                  Android Mobile App                          │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  ConfigFetchService (Background Service)             │  │
│  │  - Polls config endpoint every N minutes             │  │
│  │  - Validates & applies configuration                 │  │
│  │  - Caches config locally (SharedPreferences/Room)   │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  PolicyEngine (Runtime)                              │  │
│  │  - Evaluates policies against log events            │  │
│  │  - Dynamically reloads policies on config change    │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

---

## Configuration Schema

### Remote Configuration JSON

```json
{
  "version": "1.2.3",
  "updated_at": "2026-01-21T10:30:00Z",
  "device_id": "device-12345",
  "device_group": "production-mobile",

  "otel_config": {
    "service_name": "my-mobile-app",
    "service_version": "2.1.0",
    "protocol": "grpc",
    "collector_endpoint": "https://ingress.us-west-2.aws.dash0.com:4317",
    "auth_token": "auth_ABC123XYZ",
    "dataset": "production-mobile",
    "ram_buffer_size": 5000,
    "disk_buffer_mb": 50,
    "disk_buffer_ttl_hours": 24,
    "export_timeout_seconds": 30,
    "max_export_retries": 3,
    "attach_context_attributes": true,
    "build_channel": "production"
  },

  "environment_vars": {
    "FEATURE_FLAG_NEW_UI": "true",
    "API_BASE_URL": "https://api.example.com",
    "LOG_LEVEL": "INFO",
    "CUSTOM_VAR_1": "value1"
  },

  "export_policies": [
    {
      "id": "ui-freeze-policy",
      "enabled": true,
      "priority": 100,
      "match": {
        "attributes": {
          "event.name": {"equals": "ui.freeze"},
          "duration_ms": {"gt": 2000}
        }
      },
      "actions": [
        {
          "type": "flush_window",
          "parameters": {
            "window_minutes": 2
          }
        }
      ]
    },
    {
      "id": "crash-recovery-policy",
      "enabled": true,
      "priority": 200,
      "match": {
        "attributes": {
          "event.name": {"equals": "app.crash_recovery"}
        }
      },
      "actions": [
        {
          "type": "flush_window",
          "parameters": {
            "window_minutes": 5
          }
        }
      ]
    },
    {
      "id": "network-error-policy",
      "enabled": true,
      "priority": 150,
      "match": {
        "attributes": {
          "event.name": {"equals": "http.error"},
          "http.status_code": {"gte": 500}
        }
      },
      "actions": [
        {
          "type": "flush_window",
          "parameters": {
            "window_minutes": 2
          }
        },
        {
          "type": "increase_sampling",
          "parameters": {
            "rate": 1.0,
            "duration_minutes": 10
          }
        }
      ]
    }
  ],

  "polling_config": {
    "interval_seconds": 300,
    "retry_on_failure": true,
    "max_retries": 3
  },

  "feature_flags": {
    "enable_geo_context": true,
    "enable_crash_detection": true,
    "enable_network_stats": false
  }
}
```

---

## API Specification

### Management Server API

#### 1. Get Configuration for Device

**Request**:
```http
GET /api/v1/config/{device_id}
Authorization: Bearer {device_token}
```

**Response** (200 OK):
```json
{
  "version": "1.2.3",
  "updated_at": "2026-01-21T10:30:00Z",
  "otel_config": { ... },
  "environment_vars": { ... },
  "export_policies": [ ... ],
  "polling_config": { ... },
  "feature_flags": { ... }
}
```

**Response** (304 Not Modified):
```
(No body - config unchanged)
```

**Headers**:
- `ETag: "v1.2.3"` - Configuration version
- `Cache-Control: max-age=300` - Cache for 5 minutes

#### 2. Register Device

**Request**:
```http
POST /api/v1/devices/register
Content-Type: application/json

{
  "device_id": "device-12345",
  "os_version": "Android 14",
  "app_version": "2.1.0",
  "device_group": "production-mobile"
}
```

**Response** (201 Created):
```json
{
  "device_token": "token_ABC123",
  "config_url": "https://config.example.com/api/v1/config/device-12345",
  "polling_interval": 300
}
```

#### 3. Report Configuration Applied

**Request**:
```http
POST /api/v1/config/{device_id}/applied
Authorization: Bearer {device_token}
Content-Type: application/json

{
  "version": "1.2.3",
  "applied_at": "2026-01-21T10:35:00Z",
  "success": true,
  "errors": []
}
```

**Response** (200 OK):
```json
{
  "acknowledged": true
}
```

---

## Management Application API

### Admin REST API for Config Management

#### 1. Create/Update Configuration

**Request**:
```http
PUT /admin/api/v1/configs/{device_group}
Authorization: Bearer {admin_token}
Content-Type: application/json

{
  "otel_config": { ... },
  "environment_vars": { ... },
  "export_policies": [ ... ]
}
```

**Response** (200 OK):
```json
{
  "version": "1.2.4",
  "affected_devices": 150,
  "rollout_strategy": "immediate"
}
```

#### 2. Create Export Policy

**Request**:
```http
POST /admin/api/v1/policies
Authorization: Bearer {admin_token}
Content-Type: application/json

{
  "id": "new-policy-id",
  "name": "High Memory Usage Policy",
  "enabled": true,
  "priority": 120,
  "device_groups": ["production-mobile", "staging-mobile"],
  "match": {
    "attributes": {
      "event.name": {"equals": "memory.high"},
      "memory_mb": {"gt": 500}
    }
  },
  "actions": [
    {
      "type": "flush_immediate"
    }
  ]
}
```

**Response** (201 Created):
```json
{
  "policy_id": "new-policy-id",
  "version": "1.2.5",
  "deployed_to_groups": ["production-mobile", "staging-mobile"]
}
```

#### 3. Update Environment Variable

**Request**:
```http
PATCH /admin/api/v1/configs/{device_group}/env
Authorization: Bearer {admin_token}
Content-Type: application/json

{
  "FEATURE_FLAG_NEW_UI": "false",
  "API_BASE_URL": "https://api-v2.example.com"
}
```

**Response** (200 OK):
```json
{
  "version": "1.2.6",
  "updated_vars": ["FEATURE_FLAG_NEW_UI", "API_BASE_URL"]
}
```

#### 4. Get Device Status

**Request**:
```http
GET /admin/api/v1/devices/{device_id}/status
Authorization: Bearer {admin_token}
```

**Response** (200 OK):
```json
{
  "device_id": "device-12345",
  "device_group": "production-mobile",
  "current_config_version": "1.2.3",
  "last_config_fetch": "2026-01-21T10:30:00Z",
  "last_seen": "2026-01-21T10:35:00Z",
  "config_applied_successfully": true,
  "active_policies": ["ui-freeze-policy", "crash-recovery-policy"]
}
```

---

## Android Implementation

### ConfigFetchService

**Location**: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/ConfigFetchService.kt`

```kotlin
package io.opentelemetry.android.mobile.config

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class ConfigFetchService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startPolling()
        return START_STICKY
    }

    private fun startPolling() {
        pollingJob = scope.launch {
            while (isActive) {
                try {
                    fetchAndApplyConfig()
                } catch (e: Exception) {
                    Log.e(TAG, "Config fetch failed", e)
                }

                val interval = RemoteConfigManager.getPollingInterval(this@ConfigFetchService)
                delay(interval * 1000L)
            }
        }
    }

    private suspend fun fetchAndApplyConfig() {
        val configUrl = RemoteConfigManager.getConfigUrl(this)
        val deviceToken = RemoteConfigManager.getDeviceToken(this)
        val currentVersion = RemoteConfigManager.getCurrentVersion(this)

        val client = OkHttpClient()
        val request = Request.Builder()
            .url(configUrl)
            .header("Authorization", "Bearer $deviceToken")
            .header("If-None-Match", currentVersion)
            .build()

        client.newCall(request).execute().use { response ->
            when (response.code) {
                200 -> {
                    val json = JSONObject(response.body!!.string())
                    val newVersion = json.getString("version")

                    // Apply configuration
                    RemoteConfigManager.applyConfig(this, json)

                    // Report success
                    reportConfigApplied(newVersion, true)
                }
                304 -> {
                    // Config unchanged
                    Log.d(TAG, "Config unchanged")
                }
                else -> {
                    Log.w(TAG, "Config fetch failed: ${response.code}")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        pollingJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
```

### RemoteConfigManager

```kotlin
object RemoteConfigManager {
    fun applyConfig(context: Context, json: JSONObject) {
        // Parse and apply OTEL config
        val otelConfig = json.getJSONObject("otel_config")
        ConfigManager.saveProtocol(context, otelConfig.getString("protocol"))
        ConfigManager.saveAuthToken(context, otelConfig.getString("auth_token"))
        ConfigManager.saveDataset(context, otelConfig.getString("dataset"))
        // ... apply other settings

        // Apply environment variables
        val envVars = json.getJSONObject("environment_vars")
        envVars.keys().forEach { key ->
            EnvironmentVarManager.set(context, key, envVars.getString(key))
        }

        // Apply export policies
        val policies = json.getJSONArray("export_policies")
        PolicyManager.updatePolicies(context, policies)

        // Store version
        saveCurrentVersion(context, json.getString("version"))

        // Trigger config reload in MobileLoggerProvider
        MobileLoggerProvider.getInstance(context).reloadConfig()
    }
}
```

---

## Security Considerations

### Authentication

1. **Device Token**
   - Unique per device
   - Rotatable
   - Limited scope (read config only)

2. **Admin Token**
   - Role-based access control
   - Short-lived with refresh tokens
   - Audit all admin actions

### Configuration Signing

```kotlin
// Verify configuration signature
fun verifyConfigSignature(json: JSONObject, signature: String): Boolean {
    val publicKey = loadPublicKey()
    val hash = hashConfig(json)
    return verifySignature(publicKey, hash, signature)
}
```

### TLS Pinning

```kotlin
val certificatePinner = CertificatePinner.Builder()
    .add("config.example.com", "sha256/AAAAAAAAAA...")
    .build()
```

---

## Rollout Strategies

### 1. Immediate Rollout
- Push config to all devices in group immediately
- Use for critical fixes

### 2. Gradual Rollout
- Push to 10% → 25% → 50% → 100% over time
- Monitor metrics at each stage
- Auto-rollback on errors

### 3. Canary Deployment
- Deploy to canary group first
- Monitor for N hours
- Promote to production if healthy

### 4. A/B Testing
- Deploy different configs to different segments
- Compare metrics
- Promote winning config

---

## Monitoring & Observability

### Metrics to Track

1. **Configuration Fetch**
   - Success rate
   - Latency
   - Error types

2. **Configuration Application**
   - Success rate
   - Time to apply
   - Rollback frequency

3. **Policy Evaluation**
   - Policy match rate
   - Flush trigger frequency
   - Policy execution time

### Logging

```kotlin
logger.info("Config fetched", mapOf(
    "version" to newVersion,
    "device_id" to deviceId,
    "fetch_duration_ms" to duration
))
```

---

## Example Management UI Workflows

### Workflow 1: Update Collector Endpoint

1. Admin opens management UI
2. Selects device group: "production-mobile"
3. Updates collector endpoint to new value
4. Clicks "Deploy Configuration"
5. System creates new config version
6. Devices poll and apply new config within 5 minutes

### Workflow 2: Create New Export Policy

1. Admin navigates to Policies page
2. Clicks "Create Policy"
3. Fills in policy details (name, match conditions, actions)
4. Selects target device groups
5. Clicks "Deploy Policy"
6. Policy pushed to devices
7. Devices reload policy engine and start evaluating new policy

### Workflow 3: Emergency Rollback

1. Admin detects config causing issues
2. Clicks "Rollback to Previous Version"
3. System reverts to last known good config
4. Devices poll and apply rollback config

---

## Next Steps

1. **Implement Android Components**
   - ConfigFetchService
   - RemoteConfigManager
   - PolicyManager with dynamic reloading

2. **Build Management Server**
   - REST API (Node.js/Go/Python)
   - Configuration storage (PostgreSQL)
   - Device registration & tracking

3. **Create Management UI**
   - Web dashboard for config management
   - Policy editor
   - Device monitoring

4. **Testing**
   - Unit tests for config parsing
   - Integration tests for fetch/apply
   - E2E tests with mock server

5. **Documentation**
   - API reference
   - Management UI guide
   - Deployment guide

---

**Status**: ✅ Architecture Defined - Ready for Implementation
**Created**: January 21, 2026
**Next**: Implement ConfigFetchService and RemoteConfigManager
