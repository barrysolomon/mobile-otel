# Protocol Selection Feature

**Added**: January 21, 2026
**Status**: ✅ Implemented and tested

---

## Overview

The OpenTelemetry Android demo app now includes a protocol selector that allows users to choose between **gRPC** and **HTTP** OTLP protocols directly from the Settings UI.

### Key Features

1. **Radio button selector** in Settings for protocol choice
2. **Dynamic endpoint hints** that update based on selected protocol
3. **Persistent storage** of protocol preference
4. **Unified authentication** - same Bearer token works for both protocols
5. **In-app documentation** explaining protocol differences

---

## User Interface

### Settings Screen

**Protocol Selection** (new section above endpoint):
```
Protocol:
( ) gRPC (port 4317)
( ) HTTP (path /v1/signal)

Collector Endpoint:
[hint changes based on protocol selection]
```

**Behavior**:
- Selecting "gRPC" updates hint to show `:4317` port example
- Selecting "HTTP" updates hint to show `/v1/logs` path example
- Selection is saved to SharedPreferences
- Preference persists across app restarts

---

## Protocol Comparison

### gRPC (Recommended)

**Advantages**:
- ✅ Binary Protobuf encoding (more efficient)
- ✅ Lower latency and better performance
- ✅ Single endpoint for all signals (logs, traces, metrics)
- ✅ Built-in streaming support
- ✅ Better compression

**Configuration**:
```
Protocol: gRPC (port 4317)
Endpoint: https://ingress.us-west-2.aws.dash0.com:4317
Auth Token: auth_fI0GuunaYYbw8u0n0iyFAC4Wt2FMf0jh
```

**How Authentication Works**:
- Token included in gRPC metadata
- Key: `authorization`
- Value: `Bearer auth_fI0GuunaYYbw8u0n0iyFAC4Wt2FMf0jh`

**Use Cases**:
- Default choice for most deployments
- Mobile apps with good connectivity
- High-volume telemetry
- Real-time monitoring

### HTTP (Alternative)

**Advantages**:
- ✅ JSON encoding (human-readable)
- ✅ Better firewall/proxy compatibility
- ✅ Standard HTTPS port (443)
- ✅ Easier debugging with curl/browser tools
- ✅ Works in restricted networks

**Configuration**:
```
Protocol: HTTP (path /v1/signal)
Endpoint: https://ingress.us-west-2.aws.dash0.com/v1/logs
Auth Token: auth_fI0GuunaYYbw8u0n0iyFAC4Wt2FMf0jh
```

**How Authentication Works**:
- Token included in HTTP headers
- Header: `Authorization: Bearer auth_fI0GuunaYYbw8u0n0iyFAC4Wt2FMf0jh`

**Use Cases**:
- Restricted network environments
- Corporate proxies/firewalls
- Debugging and testing
- HTTP-only infrastructure

---

## Implementation Details

### ConfigManager

**New Methods**:
```kotlin
// Save protocol preference
fun saveProtocol(context: Context, protocol: String)

// Get protocol preference ("grpc" or "http")
fun getProtocol(context: Context): String
```

**Storage**:
- Key: `protocol`
- Default: `grpc`
- Values: `"grpc"` or `"http"`

### SettingsActivity

**New UI Elements**:
```kotlin
private lateinit var radioGroupProtocol: RadioGroup
private lateinit var radioGrpc: RadioButton
private lateinit var radioHttp: RadioButton
```

**Dynamic Hint Update**:
```kotlin
radioGroupProtocol.setOnCheckedChangeListener { _, checkedId ->
    when (checkedId) {
        R.id.radioGrpc -> {
            editCollectorEndpoint.hint = "e.g., https://ingress.us-west-2.aws.dash0.com:4317"
        }
        R.id.radioHttp -> {
            editCollectorEndpoint.hint = "e.g., https://ingress.us-west-2.aws.dash0.com/v1/logs"
        }
    }
}
```

### Layout (activity_settings.xml)

**Protocol Selector**:
```xml
<RadioGroup
    android:id="@+id/radioGroupProtocol"
    android:orientation="horizontal">

    <RadioButton
        android:id="@+id/radioGrpc"
        android:text="gRPC (port 4317)"
        android:checked="true" />

    <RadioButton
        android:id="@+id/radioHttp"
        android:text="HTTP (path /v1/signal)" />
</RadioGroup>
```

---

## Authentication Clarification

### Important: Same Token for Both Protocols

The **same Bearer token** is used for both gRPC and HTTP. The app automatically formats it correctly:

**gRPC**:
- Included in gRPC metadata
- OpenTelemetry SDK handles metadata attachment
- Protobuf-encoded requests

**HTTP**:
- Included in HTTP header: `Authorization: Bearer {token}`
- JSON-encoded requests
- Standard REST API format

**User Experience**:
- Enter token once in Settings
- Works for both protocols
- No need to change token when switching protocols
- Just update the endpoint format

---

## Endpoint Examples

### Dash0 Endpoints

| Protocol | Endpoint | Notes |
|----------|----------|-------|
| gRPC | `https://ingress.us-west-2.aws.dash0.com:4317` | All signals (logs, traces, metrics) |
| HTTP Logs | `https://ingress.us-west-2.aws.dash0.com/v1/logs` | Logs only |
| HTTP Traces | `https://ingress.us-west-2.aws.dash0.com/v1/traces` | Traces only |
| HTTP Metrics | `https://ingress.us-west-2.aws.dash0.com/v1/metrics` | Metrics only |

### Local Collector

| Protocol | Endpoint | Notes |
|----------|----------|-------|
| gRPC | `http://10.0.2.2:4317` | Emulator localhost |
| HTTP | `http://10.0.2.2:4318/v1/logs` | Emulator localhost |

### Generic Cloud

| Protocol | Example | Notes |
|----------|---------|-------|
| gRPC | `https://collector.example.com:4317` | Standard gRPC port |
| HTTP | `https://collector.example.com/v1/logs` | Standard HTTP path |

---

## Testing

### Test gRPC with curl (using grpcurl)

```bash
grpcurl \
  -H "authorization: Bearer auth_fI0GuunaYYbw8u0n0iyFAC4Wt2FMf0jh" \
  -d '{"resource_logs":[...]}' \
  ingress.us-west-2.aws.dash0.com:4317 \
  opentelemetry.proto.collector.logs.v1.LogsService/Export
```

### Test HTTP with curl

```bash
curl https://ingress.us-west-2.aws.dash0.com/v1/logs \
  -X POST \
  -H "Authorization: Bearer auth_fI0GuunaYYbw8u0n0iyFAC4Wt2FMf0jh" \
  -H "Dash0-Dataset: pi5-k3s" \
  -H "Content-Type: application/json" \
  -d '{"resourceLogs":[...]}'
```

---

## Troubleshooting

### "Export failed" with gRPC

**Possible Causes**:
1. Wrong port (should be 4317)
2. Firewall blocking gRPC port
3. Token expired/invalid

**Solutions**:
- Verify endpoint ends with `:4317`
- Test with `telnet ingress.dash0.com 4317`
- Regenerate token

### "Export failed" with HTTP

**Possible Causes**:
1. Wrong path (should include `/v1/logs`)
2. Missing signal type in path
3. Token expired/invalid

**Solutions**:
- Verify endpoint includes `/v1/logs` (or `/v1/traces`, `/v1/metrics`)
- Test with curl command above
- Check HTTP status code in logs

### Protocol Mismatch

**Problem**: Using gRPC endpoint with HTTP selected (or vice versa)

**Symptoms**:
- gRPC endpoint with HTTP: 404 Not Found
- HTTP endpoint with gRPC: Connection refused

**Solution**:
- Match protocol selection to endpoint format
- gRPC → ends with `:4317`
- HTTP → includes `/v1/{signal}`

---

## Migration Guide

### Upgrading from Previous Version

**Old Configuration** (no protocol selector):
```
Endpoint: https://ingress.us-west-2.aws.dash0.com:4317
```

**New Configuration** (after upgrade):
```
Protocol: gRPC (port 4317)  ← defaults to gRPC
Endpoint: https://ingress.us-west-2.aws.dash0.com:4317
```

**No action required** - existing configurations default to gRPC and continue working.

---

## User Documentation

### Help Screen

The in-app Help screen now includes a "Protocol Selection" section explaining:
- Differences between gRPC and HTTP
- When to use each protocol
- Authentication compatibility
- Example endpoints

### Settings UI

The Settings screen includes:
- Protocol radio buttons with descriptive labels
- Dynamic endpoint hints that change based on selection
- Helper text: "gRPC: Use :4317 port / HTTP: Use /v1/logs path"

---

## Build Information

**Build Status**: ✅ BUILD SUCCESSFUL
**APK Size**: 8.2 MB (unchanged)
**New UI Elements**: 1 RadioGroup, 2 RadioButtons
**New Methods**: 2 (saveProtocol, getProtocol)
**Modified Files**: 4

---

## Future Enhancements

Potential improvements for future versions:

1. **Automatic Protocol Detection**
   - Parse endpoint and suggest protocol
   - Validate endpoint format matches protocol

2. **Protocol-Specific Settings**
   - gRPC: Connection pool size, keepalive settings
   - HTTP: Batch size, compression level

3. **Performance Metrics**
   - Track success rate per protocol
   - Show latency comparison

4. **Smart Fallback**
   - Try gRPC first
   - Automatically fall back to HTTP on failure

5. **Network Test**
   - "Test Connection" button
   - Verify protocol and auth before saving

---

## Summary

The protocol selector feature provides:
- ✅ User-friendly protocol selection (gRPC vs HTTP)
- ✅ Dynamic UI hints based on selection
- ✅ Unified authentication for both protocols
- ✅ Persistent preference storage
- ✅ In-app documentation
- ✅ No breaking changes to existing configurations

**Users can now easily switch between protocols based on their network environment and requirements, all while using the same authentication credentials!**

---

**Created**: January 21, 2026
**Status**: ✅ Complete and tested
**APK**: examples/demo-app/android/build/outputs/apk/debug/android-debug.apk
