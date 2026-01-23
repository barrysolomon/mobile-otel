# Dash0 HTTP Export Debugging Notes

## Issue Summary
Switched OpenTelemetry mobile SDK from gRPC to HTTP/protobuf exporters to support Dash0 ingestion.

## Changes Made (2026-01-22)

### SDK Modifications
Modified `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/MobileLoggerProvider.kt`:

**Line 15-19**: Changed imports from gRPC to HTTP exporters
```kotlin
// Before:
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter

// After:
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
```

**Line 84**: Changed log exporter builder
```kotlin
val otlpExporter = OtlpHttpLogRecordExporter.builder()
```

**Line 113**: Changed trace exporter builder
```kotlin
val traceExporter = OtlpHttpSpanExporter.builder()
```

**Line 153**: Changed metric exporter builder
```kotlin
val metricExporter = OtlpHttpMetricExporter.builder()
```

### Configuration Updates
Updated both debug and main config files:
- `examples/demo-app/android/src/debug/assets/otel-config.json`
- `examples/demo-app/android/src/main/assets/otel-config.json`

**Endpoint changed:**
```json
// Before (gRPC):
"collectorEndpoint": "https://ingress.us-west-2.aws.dash0.com:4317"

// After (HTTP):
"collectorEndpoint": "https://ingress.us-west-2.aws.dash0.com"
```

**Authentication headers (unchanged):**
```json
"headers": {
  "Authorization": "Bearer auth_fI0GuunaYYbw8u0n0iyFAC4Wt2FMf0jh",
  "Dash0-Dataset": "otel-mobile"
}
```

## Current Status

### Progress
✅ SDK successfully switched from gRPC to HTTP exporters
✅ HTTP connections are being made (no more "gRPC status code 2" errors)
✅ Authentication headers are being sent correctly
✅ All three signal types attempting export (logs, traces, metrics)

### Current Issue: HTTP 404 Errors

**Logcat output:**
```
W HttpExporter: Failed to export logs. Server responded with HTTP status code 404
W HttpExporter: Failed to export spans. Server responded with HTTP status code 404
W HttpExporter: Failed to export metrics. Server responded with HTTP status code 404
```

### Analysis
The OTLP HTTP exporters automatically append standard paths to the base endpoint:
- Logs: `/v1/logs`
- Traces: `/v1/traces`
- Metrics: `/v1/metrics`

This means the SDK is attempting to POST to:
- `https://ingress.us-west-2.aws.dash0.com/v1/logs`
- `https://ingress.us-west-2.aws.dash0.com/v1/traces`
- `https://ingress.us-west-2.aws.dash0.com/v1/metrics`

The HTTP 404 responses indicate these paths don't exist at the Dash0 endpoint.

## Possible Solutions

### 1. Different Endpoint Path
Dash0 may use custom paths instead of standard OTLP paths. Need to verify:
- Is there a different base URL for HTTP ingestion?
- Does Dash0 use custom paths like `/otlp/v1/logs` or `/api/v1/logs`?

### 2. HTTP Ingestion Not Enabled
The US West 2 endpoint may only support gRPC (port 4317), not HTTP.
- Check if there's a different regional endpoint for HTTP
- Verify HTTP ingestion is enabled for the account

### 3. Custom Exporter Configuration
May need to configure the HTTP exporters with custom paths:
```kotlin
OtlpHttpLogRecordExporter.builder()
    .setEndpoint("https://ingress.us-west-2.aws.dash0.com/custom/path")
    // or possibly
    .setEndpoint("https://different-endpoint.dash0.com")
```

## Next Steps

1. **Check Dash0 Documentation**: Look for HTTP/protobuf ingestion endpoint specifications
2. **Contact Dash0 Support**: Verify correct endpoint URL and paths for HTTP ingestion in US West 2
3. **Test Alternative Endpoints**: Try different base URLs if provided by Dash0
4. **Consider Staying with gRPC**: If HTTP not supported, may need to use gRPC with port 4317

## Environment
- Device: Pixel_7(AVD) - Android 16
- SDK: OpenTelemetry Android Mobile (custom)
- Export Mode: CONDITIONAL
- Region: US West 2 (aws)
- Protocol: HTTP/protobuf (switched from gRPC)

## Previous Error (Resolved)
**Old Issue:** `GrpcExporter: Server responded with gRPC status code 2`
**Resolution:** Switched to HTTP exporters (this document)
