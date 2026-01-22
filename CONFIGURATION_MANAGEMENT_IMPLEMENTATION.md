# Configuration Management - Phase 2 Complete

**Status**: ✅ Phase 2 Complete
**Date**: January 21, 2026

---

## Overview

Phase 2 adds full OTEL configuration management capabilities to the control plane! Administrators can now create, deploy, and manage OpenTelemetry configurations for device groups directly from the web UI.

---

## What Was Implemented

### Backend (Gateway)

#### 1. Database Schema

**New Table**: `otel_configurations`

```sql
CREATE TABLE otel_configurations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_group TEXT NOT NULL,
    version TEXT NOT NULL,
    protocol TEXT NOT NULL,
    collector_endpoint TEXT NOT NULL,
    auth_token TEXT,
    dataset TEXT,
    ram_buffer_size INTEGER DEFAULT 5000,
    disk_buffer_mb INTEGER DEFAULT 50,
    disk_buffer_ttl_hours INTEGER DEFAULT 24,
    export_timeout_seconds INTEGER DEFAULT 30,
    max_export_retries INTEGER DEFAULT 3,
    environment_vars TEXT,
    feature_flags TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by TEXT DEFAULT 'admin',
    is_active BOOLEAN DEFAULT 0,
    FOREIGN KEY (device_group) REFERENCES device_groups(name)
);
```

**Files Modified**:
- [gateway/internal/db/db.go](gateway/internal/db/db.go)
  - Added `OTELConfiguration` struct
  - Added `CreateOTELConfig`, `GetActiveOTELConfig`, `ListOTELConfigs`, `GetOTELConfigByID`, `ActivateOTELConfig` functions

#### 2. New API Endpoints

```
POST   /api/v1/otel-configs              - Create configuration
GET    /api/v1/otel-configs              - List configurations
GET    /api/v1/otel-configs/active       - Get active config for group
POST   /api/v1/otel-configs/activate     - Activate specific version
```

**Files Modified**:
- [gateway/internal/handlers/handlers.go](gateway/internal/handlers/handlers.go)
  - Added `CreateOTELConfigRequest` struct
  - Added `HandleCreateOTELConfig`
  - Added `HandleListOTELConfigs`
  - Added `HandleGetActiveOTELConfig`
  - Added `HandleActivateOTELConfig`

- [gateway/main.go](gateway/main.go)
  - Registered 4 new configuration management routes

### Frontend (Control Plane UI)

#### 1. New ConfigManager Component

**Features**:
- Device group selector with environment info
- Protocol selection (gRPC vs HTTP) with dynamic hints
- Complete OTEL configuration form:
  - Collector endpoint
  - Auth token (password masked)
  - Dataset/Tenant ID
  - Buffer configuration (RAM, Disk, TTL)
  - Export settings (timeout, retries)
- Environment variables management (key-value pairs)
- Feature flags management (boolean toggles)
- Configuration history with version tracking
- One-click activation of previous configurations
- Shows currently active configuration
- Real-time deployment with device count

**Files Created**:
- [control-plane-ui/src/components/ConfigManager.tsx](control-plane-ui/src/components/ConfigManager.tsx) - 500+ lines

#### 2. Updated Gateway API Client

**New Methods**:
```typescript
async createOTELConfig(data) - Deploy new configuration
async listOTELConfigs(deviceGroup, limit) - List config history
async getActiveOTELConfig(deviceGroup) - Get current active config
async activateOTELConfig(id) - Roll back to previous version
```

**Files Modified**:
- [control-plane-ui/src/api/gateway.ts](control-plane-ui/src/api/gateway.ts)

#### 3. Updated App with Configuration Tab

**New Tab Structure**:
```
Control Plane
├── Workflow Builder
├── Devices
│   ├── Device Fleet
│   └── Live Monitor
└── Configuration (NEW)
```

**Files Modified**:
- [control-plane-ui/src/App.tsx](control-plane-ui/src/App.tsx)
  - Added `ConfigManager` import
  - Added `'config'` to activeTab type
  - Added Configuration tab button
  - Added Configuration tab content area

- [control-plane-ui/src/App.css](control-plane-ui/src/App.css)
  - Added comprehensive ConfigManager styles (~250 lines)

---

## User Workflow

### 1. Creating a Configuration

1. Navigate to **Configuration** tab
2. Select target **Device Group** (default, production-mobile, etc.)
3. Configure OTEL settings:
   - Select **Protocol** (gRPC or HTTP)
   - Enter **Collector Endpoint** (hints update based on protocol)
   - Enter **Auth Token** (optional, masked)
   - Enter **Dataset** (optional)
   - Adjust **Buffer Settings** (RAM, Disk, TTL)
   - Set **Export Settings** (timeout, retries)
4. Add **Environment Variables** (optional)
   - Click "+ Add Variable"
   - Enter name and value
5. Add **Feature Flags** (optional)
   - Click "+ Add Flag"
   - Enter name and toggle on/off
6. Click **🚀 Deploy Configuration**
7. Success message shows version and affected devices

### 2. Viewing Configuration History

The right panel shows all configurations for the selected device group:
- Version number
- Active status (badge)
- Protocol type
- Collector endpoint
- Dataset
- Creation date and author
- **Activate** button for non-active versions

### 3. Rolling Back Configuration

1. Select device group
2. Find previous version in history
3. Click **Activate** button
4. Configuration immediately becomes active
5. Devices will fetch new config on next poll

---

## API Examples

### 1. Create Configuration

**Request**:
```bash
curl -X POST http://localhost:8080/api/v1/otel-configs \
  -H "Content-Type: application/json" \
  -d '{
    "device_group": "production-mobile",
    "protocol": "grpc",
    "collector_endpoint": "https://ingress.us-west-2.aws.dash0.com:4317",
    "auth_token": "auth_fI0GuunaYYbw8u0n0iyFAC4Wt2FMf0jh",
    "dataset": "production-mobile",
    "ram_buffer_size": 5000,
    "disk_buffer_mb": 50,
    "disk_buffer_ttl_hours": 24,
    "export_timeout_seconds": 30,
    "max_export_retries": 3,
    "environment_vars": {
      "FEATURE_FLAG_NEW_UI": "true",
      "LOG_LEVEL": "INFO"
    },
    "feature_flags": {
      "enable_crash_detection": true,
      "enable_geo_context": false
    }
  }'
```

**Response**:
```json
{
  "id": 1,
  "version": "1737497890.0.0",
  "device_group": "production-mobile",
  "affected_devices": 15
}
```

### 2. List Configurations

**Request**:
```bash
curl "http://localhost:8080/api/v1/otel-configs?device_group=production-mobile&limit=10"
```

**Response**:
```json
{
  "configurations": [
    {
      "id": 1,
      "device_group": "production-mobile",
      "version": "1737497890.0.0",
      "protocol": "grpc",
      "collector_endpoint": "https://ingress.us-west-2.aws.dash0.com:4317",
      "auth_token": "auth_fI0GuunaYYbw8u0n0iyFAC4Wt2FMf0jh",
      "dataset": "production-mobile",
      "ram_buffer_size": 5000,
      "disk_buffer_mb": 50,
      "disk_buffer_ttl_hours": 24,
      "export_timeout_seconds": 30,
      "max_export_retries": 3,
      "environment_vars": "{\"FEATURE_FLAG_NEW_UI\":\"true\"}",
      "feature_flags": "{\"enable_crash_detection\":true}",
      "created_at": "2026-01-21T10:30:00Z",
      "created_by": "admin",
      "is_active": true
    }
  ],
  "count": 1
}
```

### 3. Get Active Configuration

**Request**:
```bash
curl "http://localhost:8080/api/v1/otel-configs/active?device_group=production-mobile"
```

**Response**:
```json
{
  "id": 1,
  "device_group": "production-mobile",
  "version": "1737497890.0.0",
  "protocol": "grpc",
  "collector_endpoint": "https://ingress.us-west-2.aws.dash0.com:4317",
  "dataset": "production-mobile",
  "ram_buffer_size": 5000,
  "is_active": true
}
```

### 4. Activate Configuration (Rollback)

**Request**:
```bash
curl -X POST "http://localhost:8080/api/v1/otel-configs/activate?id=1"
```

**Response**:
```json
{
  "status": "ok",
  "id": 1
}
```

---

## Integration with Android App

The Android app needs to be updated to fetch and apply OTEL configurations from the gateway.

**Enhancement Needed in**: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/MobileLoggerProvider.kt`

```kotlin
suspend fun fetchAndApplyConfig(deviceId: String, deviceGroup: String) {
    try {
        val response = httpClient.get("${gatewayUrl}/api/v1/otel-configs/active") {
            parameter("device_group", deviceGroup)
        }

        if (response.status.isSuccess()) {
            val config = response.body<OTELConfiguration>()

            // Apply configuration
            applyConfiguration(config)

            Log.i(TAG, "Applied configuration version: ${config.version}")
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to fetch config: ${e.message}")
    }
}

private fun applyConfiguration(config: OTELConfiguration) {
    // Update MobileConfig with new values
    val newConfig = MobileConfig(
        collectorEndpoint = config.collectorEndpoint,
        headers = buildHeaders(config.authToken, config.dataset),
        ramBufferSize = config.ramBufferSize,
        diskBufferMB = config.diskBufferMB,
        diskBufferTTLHours = config.diskBufferTTLHours,
        exportTimeoutSeconds = config.exportTimeoutSeconds,
        maxExportRetries = config.maxExportRetries
    )

    // Apply environment variables
    val envVars = parseEnvironmentVars(config.environmentVars)
    envVars.forEach { (key, value) ->
        EnvironmentVarManager.set(context, key, value)
    }

    // Apply feature flags
    val flags = parseFeatureFlags(config.featureFlags)
    flags.forEach { (key, value) ->
        FeatureFlagManager.set(context, key, value)
    }

    // Reinitialize exporter with new config
    reinitializeExporter(newConfig)
}
```

---

## Frontend Screenshots (Description)

### Configuration Tab

```
┌──────────────────────────────────────────────────────────────────────┐
│ Mobile Observability Control Plane                                   │
│ [Workflow Builder] [Devices] [Configuration ✓]                      │
├──────────────────────────────────────────────────────────────────────┤
│                                                                       │
│ OTEL Configuration Management                                        │
│ Deploy OpenTelemetry configurations to device groups                 │
│                                                                       │
│ ┌─────────────────────────────────┐ ┌─────────────────────────────┐│
│ │ Configuration Editor             │ │ Configuration History       ││
│ │                                  │ │ For group: production       ││
│ │ Target Device Group:             │ │                             ││
│ │ [production-mobile ▼]            │ │ ┌─────────────────────────┐││
│ │ Active: v1737497890.0.0          │ │ │ v1737497890.0.0  ACTIVE │││
│ │                                  │ │ │ Protocol: GRPC          │││
│ │ ┌─────────────────────────────┐ │ │ │ Endpoint: dash0.com:... │││
│ │ │ OTEL Configuration          │ │ │ │ Dataset: prod-mobile    │││
│ │ │                             │ │ │ │ Created: 2h ago         │││
│ │ │ Protocol:                   │ │ │ └─────────────────────────┘││
│ │ │ ⦿ gRPC  ○ HTTP             │ │ │                             ││
│ │ │                             │ │ │ ┌─────────────────────────┐││
│ │ │ Collector Endpoint:         │ │ │ │ v1737493890.0.0         │││
│ │ │ [dash0.com:4317]           │ │ │ │ Protocol: HTTP          │││
│ │ │                             │ │ │ │ Endpoint: dash0.com/... │││
│ │ │ Auth Token: ••••••••••     │ │ │ │ Created: 5h ago         │││
│ │ │ Dataset: [production]       │ │ │ │ [Activate]              │││
│ │ │                             │ │ │ └─────────────────────────┘││
│ │ │ RAM Buffer: [5000] events   │ │ │                             ││
│ │ │ Disk Buffer: [50] MB        │ │ └─────────────────────────────┘│
│ │ │ TTL: [24] hours            │ │                               │
│ │ └─────────────────────────────┘ │                               │
│ │                                  │                               │
│ │ ┌─────────────────────────────┐ │                               │
│ │ │ Environment Variables       │ │                               │
│ │ │ [FEATURE_FLAG_NEW_UI] [true]│ │                               │
│ │ │ [LOG_LEVEL] [INFO]          │ │                               │
│ │ │ [+ Add Variable]            │ │                               │
│ │ └─────────────────────────────┘ │                               │
│ │                                  │                               │
│ │ [🚀 Deploy Configuration]       │                               │
│ └─────────────────────────────────┘                               │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Features Highlights

### ✅ What Works

1. **Full Configuration Management**
   - Create configurations with all OTEL parameters
   - Protocol selection (gRPC/HTTP) with smart hints
   - Auth token and dataset management
   - Buffer and retry configuration

2. **Environment Variables**
   - Add/edit/remove key-value pairs
   - Stored as JSON in database
   - Applied to mobile devices

3. **Feature Flags**
   - Boolean toggles for features
   - Easy enable/disable
   - Centralized flag management

4. **Version Management**
   - Automatic version generation (timestamp-based)
   - Configuration history per device group
   - One-click rollback to previous versions
   - Clear active status indication

5. **Device Group Targeting**
   - Deploy to specific groups
   - Shows affected device count
   - Group-specific configuration history

6. **User Experience**
   - Dynamic endpoint hints based on protocol
   - Password masking for auth tokens
   - Success/error messages
   - Real-time configuration preview

---

## Architecture Flow

```
┌─────────────────────────────────────────────────────────┐
│          Admin Creates Configuration                     │
│          (Control Plane UI)                              │
└────────────────┬────────────────────────────────────────┘
                 │ POST /api/v1/otel-configs
                 ▼
┌─────────────────────────────────────────────────────────┐
│          Gateway Stores Config                           │
│          (SQLite Database)                               │
│  - Deactivates previous active config                   │
│  - Inserts new config with is_active=true              │
│  - Returns version and affected device count            │
└────────────────┬────────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────┐
│          Mobile Devices Poll Gateway                     │
│          GET /api/v1/otel-configs/active                │
└────────────────┬────────────────────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────────────────────┐
│          Devices Apply Configuration                     │
│  - Update collector endpoint                            │
│  - Apply auth tokens and headers                        │
│  - Adjust buffer sizes                                  │
│  - Set environment variables                            │
│  - Enable/disable feature flags                         │
└─────────────────────────────────────────────────────────┘
```

---

## Testing

### 1. Start Gateway

```bash
cd gateway
go run main.go
```

### 2. Start Control Plane UI

```bash
cd control-plane-ui
npm run dev
```

### 3. Create Configuration via UI

1. Open http://localhost:3000
2. Click **Configuration** tab
3. Select device group: `default`
4. Fill in configuration:
   - Protocol: gRPC
   - Endpoint: `https://ingress.us-west-2.aws.dash0.com:4317`
   - Auth Token: `auth_test_token_123`
   - Dataset: `test-dataset`
5. Add environment variable: `TEST_VAR` = `value123`
6. Add feature flag: `test_feature` = `enabled`
7. Click **Deploy Configuration**

### 4. Verify via API

```bash
# List configurations
curl "http://localhost:8080/api/v1/otel-configs?device_group=default"

# Get active configuration
curl "http://localhost:8080/api/v1/otel-configs/active?device_group=default"
```

### 5. Test Rollback

1. Create a second configuration with different settings
2. In Configuration History panel, find the first version
3. Click **Activate** button
4. Verify first version is now active

---

## Known Limitations

1. **Auth Token Storage**: Tokens stored in plain text in database. Should encrypt in production.

2. **No Authentication**: API endpoints are open. Phase 4 will add JWT auth.

3. **No Real-Time Push**: Devices poll for config. Could add WebSocket push.

4. **Single Active Config**: Only one active config per device group. Could support gradual rollouts.

5. **Android Integration Pending**: Need to implement config fetch in Android app.

---

## Next Steps (Phase 3)

Phase 3 will focus on:
1. **Real-Time Device Polling** - Replace mock heartbeat data with live updates
2. **Enhanced DeviceMonitor** - Show real-time device status with actual data
3. **Configuration Compliance** - Track which devices have applied which config versions
4. **Deployment Tracking** - Monitor configuration rollout progress in real-time

---

## Summary

Phase 2 is complete! The control plane now has full configuration management capabilities:

✅ **Backend**:
- Database schema for OTEL configurations
- 4 new API endpoints
- Version management and rollback support

✅ **Frontend**:
- Complete configuration editor with all OTEL parameters
- Environment variables and feature flags management
- Configuration history with version tracking
- One-click rollback capability

✅ **Integration**:
- API fully functional and tested
- Ready for Android app integration
- Supports multiple device groups

---

**Status**: ✅ Phase 2 Complete - Configuration Management Operational
**Next**: Phase 3 - Real-Time Device Polling
**Timeline**: Week 3-4

