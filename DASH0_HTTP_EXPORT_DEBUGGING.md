# Dash0 Export Configuration - Final Resolution

## Final Solution (2026-01-22)

**Use gRPC on port 4317** - This is the standard OTLP protocol fully supported by Dash0.

## Root Cause Analysis

### Problem Discovery
Through systematic curl testing, we discovered that Dash0's endpoints support different protocols:

1. **Port 4318 (HTTP)**: Only accepts JSON format (`application/json`)
   ```bash
   # ✅ Works with JSON
   curl -X POST "https://ingress.us-west-2.aws.dash0.com:4318/v1/logs" \
     -H "Content-Type: application/json" \
     -H "Authorization: Bearer TOKEN" \
     -d '{"resourceLogs": [...]}'
   # Returns: HTTP 200
   ```

2. **Port 4318 (HTTP)**: Rejects protobuf format (`application/x-protobuf`)
   ```bash
   # ❌ Fails with protobuf
   curl -X POST "https://ingress.us-west-2.aws.dash0.com:4318/v1/logs" \
     -H "Content-Type: application/x-protobuf" \
     -H "Authorization: Bearer TOKEN"
   # Returns: HTTP 400/404
   ```

3. **Port 4317 (gRPC)**: Standard OTLP gRPC protocol
   ```bash
   # ✅ Works with gRPC
   ```

### Why HTTP Exporters Failed
OpenTelemetry HTTP exporters default to **protobuf format**, not JSON. Since Dash0's port 4318 only accepts JSON, the HTTP exporters were receiving HTTP 404/400 errors.

## Current Configuration

### SDK (MobileLoggerProvider.kt)
Uses gRPC exporters:
```kotlin
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter

val otlpExporter = OtlpGrpcLogRecordExporter.builder()
    .setEndpoint(config.collectorEndpoint)
    .setTimeout(config.exportTimeoutSeconds, TimeUnit.SECONDS)
    .apply {
        config.headers?.forEach { (key, value) ->
            addHeader(key, value)
        }
    }
    .build()
```

### Configuration Files
Both config files now use gRPC port 4317:
- `examples/demo-app/android/src/debug/assets/otel-config.json`
- `examples/demo-app/android/src/main/assets/otel-config.json`

```json
{
  "collectorEndpoint": "https://ingress.us-west-2.aws.dash0.com:4317",
  "headers": {
    "Authorization": "Bearer auth_fI0GuunaYYbw8u0n0iyFAC4Wt2FMf0jh",
    "Dash0-Dataset": "otel-mobile"
  }
}
```

### Default Values (ConfigManager.kt)
```kotlin
private const val DEFAULT_COLLECTOR_ENDPOINT = "http://10.0.2.2:4317"
private const val DEFAULT_PROTOCOL = "grpc"
```

## Verification

### Success Logs
```
D LoggingHttpExporter: === EXPORT ATTEMPT ===
D LoggingHttpExporter: Endpoint: https://ingress.us-west-2.aws.dash0.com:4317
D LoggingHttpExporter: Log count: 2
I LoggingHttpExporter: ✅ Export successful to https://ingress.us-west-2.aws.dash0.com:4317/v1/logs (2 logs)
```

## Key Learnings

1. **Dash0 Protocol Support**:
   - Port 4317: gRPC (protobuf) ✅ **Use this**
   - Port 4318: HTTP (JSON only) ⚠️ Not compatible with OTel HTTP exporters

2. **OpenTelemetry HTTP Exporters**:
   - Default to protobuf format, not JSON
   - Cannot be easily configured to use JSON
   - For Dash0, use gRPC exporters instead

3. **Configuration Bug Fixed**:
   - ConfigManager.kt wasn't persisting headers to SharedPreferences
   - Fixed to extract and save Authorization and Dash0-Dataset headers

## Debugging Features Added

### LoggingHttpExporter
New wrapper class that logs all export attempts:
```kotlin
class LoggingHttpExporter(
    private val delegate: LogRecordExporter,
    private val endpoint: String
) : LogRecordExporter {
    // Logs export attempts with full URL and results
    // Provides callback to display status in app UI
}
```

### Real-Time Export Status
MainActivity now displays export results in the status text:
```kotlin
LoggingHttpExporter.onExportResult = { success, message ->
    runOnUiThread {
        val exportStatus = "\n\n📡 Last Export:\n$message"
        updateStatus(currentStatus + exportStatus)
    }
}
```

## Final Status

✅ **RESOLVED**: Telemetry successfully exports to Dash0 using gRPC on port 4317
✅ All configurations updated to use gRPC
✅ Headers properly configured and persisted
✅ Comprehensive logging for debugging
✅ Real-time export status in app UI
