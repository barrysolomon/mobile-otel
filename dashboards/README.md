# Dash0 Dashboards

Observability dashboards for the Mobile OTel system, defined as Dash0-native JSON (Perses format).

## Dashboards

**[mobile-fleet-overview.json](mobile-fleet-overview.json)** — `/mobile-monitoring`
Top-level fleet health: active devices, avg battery, error rate, memory/thermal trends.

**[mobile-device-health.json](mobile-device-health.json)** — `/mobile-monitoring`
Per-device deep dive: battery level & drain rate, memory usage, thermal state, storage.

**[mobile-performance-alerts.json](mobile-performance-alerts.json)** — `/mobile-monitoring`
UI jank, P95 response time, error rate, predictive risk indicators (crash, battery, network).

**[device-thermal-monitoring.json](device-thermal-monitoring.json)** — `/mobile-monitoring`
Dedicated thermal state monitoring with gauge + per-state device counts.

## Import into Dash0

**Via Dash0 UI:**

1. Go to [app.dash0.com](https://app.dash0.com) → Dashboards → Import
2. Paste the JSON content or upload the file

**Via Dash0 API:**

```bash
curl -X POST https://api.dash0.com/api/dashboards \
  -H "Authorization: Bearer $DASH0_AUTH_TOKEN" \
  -H "Content-Type: application/json" \
  --data-binary @<dashboard-file>.json
```

**Via kubectl (if using Perses CRDs):**

```bash
kubectl apply -f dashboards/
```

## Telemetry Sources

These dashboards query telemetry produced by the Android SDK (`otel-android-mobile/`) and forwarded
through the gateway to the OTEL Collector. Key signal sources:

- **Spans**: `page.*`, `ui.tap`, `ui.scroll`, `ui.swipe`, `ui.back_press`, `ui.text_input`
- **Logs**: screen view events, crash reports, export policy evaluations
- **Metrics**: buffer fill level, memory, battery, jank, app-start time
- **Attributes**: `demo.run_id`, `session.id`, `device.model`, `os.version`, `app.version`
