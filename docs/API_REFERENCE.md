# API Reference

> **Note:** The Gateway API has moved to the
> [mobile-otel-control-plane](https://github.com/barrysolomon/mobile-otel-control-plane) repository.
> See that repo for the full gateway API documentation (event ingestion, configuration, admin endpoints, etc.).

This document is retained as a reference for the export policy DSL format used by the Android SDK.

## Export Policy DSL

The Android SDK evaluates export policies defined in JSON. These can be bundled with the app
in `assets/otel-config.json` or fetched from a remote endpoint.

See [Bundled Configuration](./BUNDLED_CONFIG.md) for the complete configuration format.

### Policy DSL Structure

```json
{
  "workflows": [
    {
      "id": "unique-id",
      "enabled": true,
      "trigger": {
        "all": [
          {
            "event": "event.name",
            "where": [
              { "attr": "attribute", "op": ">", "value": 1000 }
            ]
          }
        ]
      },
      "actions": [
        { "type": "flush_window", "minutes": 2, "scope": "session" }
      ]
    }
  ]
}
```

### Trigger Operators

| Operator | Description | Example |
| -------- | ----------- | ------- |
| `==` | Equals | `{"attr": "status", "op": "==", "value": "error"}` |
| `!=` | Not equals | `{"attr": "status", "op": "!=", "value": "ok"}` |
| `>` | Greater than | `{"attr": "duration_ms", "op": ">", "value": 1000}` |
| `>=` | Greater or equal | `{"attr": "status_code", "op": ">=", "value": 400}` |
| `<` | Less than | `{"attr": "duration_ms", "op": "<", "value": 100}` |
| `<=` | Less or equal | `{"attr": "status_code", "op": "<=", "value": 399}` |
| `contains` | String contains | `{"attr": "route", "op": "contains", "value": "/api"}` |
| `regex` | Regex match | `{"attr": "error_msg", "op": "regex", "value": ".*timeout.*"}` |

### Action Types

| Action | Description | Parameters |
| ------ | ----------- | ---------- |
| `flush_window` | Flush time window | minutes (number), scope (string) |
| `annotate` | Add metadata | trigger_id (string), reason (string) |
| `set_sampling` | Adjust sampling | rate (0.0-1.0), duration_minutes (number) |

## Related Documentation

- [Bundled Configuration](./BUNDLED_CONFIG.md) - Configuration format
- [Export Modes](./EXPORT_MODES.md) - CONDITIONAL vs CONTINUOUS vs HYBRID
- [Android SDK Guide](./ANDROID_SDK_GUIDE.md) - SDK integration
- [Gateway API](https://github.com/barrysolomon/mobile-otel-control-plane) - Full gateway API (sister repo)
