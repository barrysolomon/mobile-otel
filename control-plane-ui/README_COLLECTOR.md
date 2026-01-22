# Collector Endpoint Management

The CollectorConfig component provides a user-friendly interface for managing OTEL collector endpoints and configuring mobile apps to send telemetry to Dash0 or custom collectors.

## Features

### 📡 Pre-configured Endpoints

**Dash0 Regions**:
- **Dash0 US**: `ingress.us.dash0.com:4317` (gRPC)
- **Dash0 EU**: `ingress.eu.dash0.com:4317` (gRPC)

**Local Development**:
- **Local OTEL Collector**: `localhost:4317` (gRPC)

**Custom**:
- Add your own collector endpoints with configurable hostname, port, and protocol

### 🔐 Dash0 Authentication

Configure authentication for Dash0 endpoints:

- **Auth Token**: Bearer token for Dash0 API authentication
  - Get your token from [dash0.com](https://dash0.com)
  - Automatically added as `Authorization: Bearer <token>` header

- **Dataset Name** (Optional): Logical grouping for telemetry data
  - Examples: `mobile-prod`, `mobile-staging`, `mobile-beta`
  - Added as `Dash0-Dataset: <name>` header

### ⚙️ Configuration Management

- **Single Active Endpoint**: Select one endpoint as active for mobile apps
- **Persistent Storage**: Configuration saved to browser localStorage
- **Export to Mobile**: Download JSON config for Android integration
- **Advanced Options**:
  - Export timeout
  - Max retry attempts
  - Config poll interval

## How to Use

### 1. Open Collector Config

In the Control Plane UI:
1. Click the **Configuration** tab
2. Click the **📡 Collector Endpoints** subtab

### 2. Configure Dash0 (Optional)

If using Dash0:
1. Enter your **Auth Token** from [dash0.com](https://dash0.com)
2. (Optional) Enter a **Dataset Name** for logical grouping
3. Select **Dash0 US** or **Dash0 EU** as the active endpoint

### 3. Configure Custom Endpoint (Optional)

For custom collectors:
1. Click **+ Add Custom Endpoint**
2. Enter endpoint name (e.g., "Staging Collector")
3. Enter hostname:port (e.g., `collector.example.com:4317`)
4. Select protocol (gRPC or HTTP)
5. Select as active endpoint (radio button)

### 4. Save Configuration

Click **💾 Save Configuration**
- Configuration saved to browser localStorage
- Mobile apps will need to restart to apply changes

### 5. Export for Mobile App

Click **📥 Export for Mobile App**
- Downloads `mobile-config.json`
- Contains complete MobileConfig for Android

## Integration with Mobile Apps

### Android Integration

The exported configuration can be used directly in your Android app:

```kotlin
val config = MobileConfig(
    serviceName = "mobile-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "https://ingress.us.dash0.com:4317",
    exportMode = ExportMode.CONDITIONAL,
    headers = mapOf(
        "Authorization" to "Bearer YOUR_TOKEN_HERE",
        "Dash0-Dataset" to "mobile-prod"
    )
)

val loggerProvider = MobileLoggerProvider.getInstance(context, config)
```

### Dynamic Configuration

Mobile apps can poll the control plane for configuration updates:

```kotlin
// In MobileConfig
val configPollIntervalSeconds: Long = 300  // Poll every 5 minutes

// Control plane serves config at:
// GET http://control-plane:8080/config
```

## Configuration Persistence

All configuration is stored in browser localStorage:

```json
{
  "endpoints": [
    {
      "name": "Dash0 US",
      "endpoint": "ingress.us.dash0.com:4317",
      "protocol": "grpc",
      "headers": {
        "Authorization": "Bearer YOUR_TOKEN",
        "Dash0-Dataset": "mobile-prod"
      },
      "enabled": true
    }
  ],
  "authToken": "YOUR_TOKEN",
  "dataset": "mobile-prod"
}
```

## Security Considerations

### Auth Token Storage
- Auth tokens are stored in browser localStorage
- Consider using secure token management for production
- Tokens are NOT transmitted to mobile apps automatically
- Mobile apps should retrieve tokens from secure backend

### Best Practices
1. **Use environment-specific tokens**: Different tokens for dev/staging/prod
2. **Rotate tokens regularly**: Update in both UI and mobile apps
3. **Use dataset names**: Separate data by environment
4. **Secure the Control Plane**: Require authentication for UI access

## Example Scenarios

### Scenario 1: Development Setup

**Goal**: Test with local OTEL collector

1. Select **Local OTEL Collector** as active
2. Ensure collector running: `docker-compose up otel-collector`
3. Mobile app connects to: `http://10.0.2.2:4317` (Android emulator)
4. Save and restart mobile app

### Scenario 2: Production with Dash0

**Goal**: Send production telemetry to Dash0 US region

1. Get auth token from [dash0.com](https://dash0.com)
2. Enter token in **Auth Token** field
3. Enter dataset name: `mobile-prod`
4. Select **Dash0 US** as active
5. Export config and integrate into mobile app
6. Mobile app sends to: `https://ingress.us.dash0.com:4317`

### Scenario 3: Multi-Region Setup

**Goal**: EU users send to Dash0 EU, US users to Dash0 US

1. Configure both Dash0 US and Dash0 EU with same token
2. Use geo-based logic in mobile app:
```kotlin
val endpoint = if (userRegion == "EU") {
    "https://ingress.eu.dash0.com:4317"
} else {
    "https://ingress.us.dash0.com:4317"
}

val config = MobileConfig(
    ...,
    collectorEndpoint = endpoint
)
```

### Scenario 4: Custom Enterprise Collector

**Goal**: Send to internal enterprise collector

1. Click **+ Add Custom Endpoint**
2. Name: "Enterprise Collector"
3. Endpoint: `otel.company.internal:4317`
4. Protocol: gRPC
5. Select as active
6. Export and integrate

## Troubleshooting

### Mobile App Not Connecting

**Check**:
1. Endpoint is reachable from mobile device
2. For Android emulator: Use `10.0.2.2` instead of `localhost`
3. For real device: Use actual IP address or hostname
4. Firewall allows gRPC (port 4317) or HTTP (port 4318)

### Auth Errors with Dash0

**Check**:
1. Token is valid and not expired
2. Token has correct permissions for the dataset
3. `Authorization` header is properly formatted: `Bearer <token>`
4. Dataset name matches configured dataset in Dash0

### Config Not Updating on Mobile

**Mobile apps don't automatically reload config**. You must:
1. Restart the mobile app after saving config
2. OR implement config polling in mobile app
3. OR use push notifications for config updates

## Related Documentation

- [Workflow System](../docs/WORKFLOW_SYSTEM.md) - Complete workflow architecture
- [Export Modes](../docs/EXPORT_MODES.md) - CONDITIONAL vs CONTINUOUS vs HYBRID
- [MobileConfig](../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/MobileConfig.kt) - Android configuration
- [Dash0 Documentation](https://dash0.com/docs) - Dash0 platform documentation

## API Reference

### CollectorConfig Props

```typescript
interface CollectorConfigProps {
  onSave?: (config: CollectorEndpoint[]) => void;
}

interface CollectorEndpoint {
  name: string;
  endpoint: string;  // hostname:port
  protocol: 'grpc' | 'http';
  headers?: Record<string, string>;
  enabled: boolean;
}
```

### Methods

**exportConfig()**
- Exports active endpoint as `mobile-config.json`
- Includes headers (auth token, dataset)
- Ready for Android integration

**handleSave()**
- Saves configuration to localStorage
- Applies headers to all Dash0 endpoints
- Notifies parent component via `onSave` callback
