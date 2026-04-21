# React Native SDK Guide

`@dash0/mobile-react-native` — a native-first React Native bridge over the existing Android and iOS Dash0 Mobile SDKs.

## TL;DR

```ts
import { Dash0Mobile } from '@dash0/mobile-react-native';

await Dash0Mobile.start({
  serviceName: 'my-shop-app',
  endpoint: 'https://ingress.eu-west-1.aws.dash0.com:4317',
  authToken: 'auth_xxxxxxxx',
  dataset: 'production',
});

Dash0Mobile.log('cart.item_added', { 'product.sku': 'SKU-42', qty: 2 });
Dash0Mobile.recordMetric('cart.size', 3, 'gauge');
```

That's it. Network spans, JS errors, unhandled promise rejections, and app lifecycle events capture automatically. Everything flows through the native SDK's dual-tier buffer → OTLP/gRPC to Dash0.

## Architecture (native-first)

The JS layer is thin. All buffering, policy DSL evaluation, disk spill, OTLP export, and crash recovery happens in the native Kotlin + Swift SDKs. The RN package marshals calls across the RN native-module bridge (50 ms debounce batching).

See [docs/epics/REACT_NATIVE_EPIC.md](epics/REACT_NATIVE_EPIC.md) for the architectural decision record.

## Install

```bash
npm install @dash0/mobile-react-native
cd ios && pod install
```

Autolinking picks up the native module via `Dash0Mobile.podspec` + `react-native.config.js`. No manual linking required on RN 0.60+.

**Requirements:**
- RN 0.72+ (TurboModules / New Architecture recommended; 0.76+ defaults to New Arch)
- iOS 15+, Android API 26+
- React 18+

## API

### `Dash0Mobile.start(config)`

| Field | Type | Required | Purpose |
|-------|------|----------|---------|
| `serviceName` | `string` | yes | `service.name` resource attribute |
| `endpoint` | `string` | yes | OTLP/gRPC endpoint (e.g. `https://ingress…:4317`) |
| `authToken` | `string` | yes for Dash0 | Bearer token |
| `dataset` | `string` | no | Dash0 dataset name |
| `autoCapture.network` | `boolean` | no (default `true`) | Wrap fetch + XHR |
| `autoCapture.errors` | `boolean` | no (default `true`) | Hook ErrorUtils + unhandledrejection |
| `autoCapture.lifecycle` | `boolean` | no (default `true`) | AppState foreground/background logs |
| `buffering.ramEvents` | `number` | no | RAM ring buffer capacity (native default: 5000) |
| `buffering.diskBytes` | `number` | no | SQLite spill cap (native default: 50 MB) |
| `enablePolicyPolling` | `boolean` | no | Poll the control plane for DSL updates |

### Signals

```ts
Dash0Mobile.log(name, attributes?, severity?);   // severity: OTel enum, default 9 (INFO)
Dash0Mobile.recordMetric(name, value, 'counter'|'histogram'|'gauge', attrs?);
Dash0Mobile.startSpan(name, attrs?, kind?);      // returns SpanHandle
Dash0Mobile.span(name, async fn, attrs?);        // async helper; fn returns any
await Dash0Mobile.flushWindow(minutes);          // selective flush of buffered events
await Dash0Mobile.shutdown();                    // flush + tear down
```

### Opt-in helpers

```ts
// React Navigation: emits ui.screen_view log + page.<name> span per route change
import { installReactNavigationInstrumentation } from '@dash0/mobile-react-native';

const navRef = useNavigationContainerRef();
useEffect(() => installReactNavigationInstrumentation(navRef), [navRef]);

// Touch telemetry: wrap any onPress handler
import { withTapTelemetry } from '@dash0/mobile-react-native';

<Pressable onPress={withTapTelemetry('cart.checkout_button', handleCheckout)} />

// OTel API compat (use standard OTel API in JS libraries)
import { otel } from '@dash0/mobile-react-native';

const tracer = otel.trace.getTracer('my-lib');
const span = tracer.startSpan('work', { attributes: { 'code.function': 'sync' } });
span.end();
```

## What's captured automatically

| Signal | Attributes | When |
|--------|-----------|------|
| HTTP CLIENT span (fetch/XHR) | `http.request.method`, `url.full`, `server.address`, `http.response.status_code` | Every `fetch()` or XHR |
| `app.error` log | `exception.type`, `exception.message`, `exception.stacktrace`, `exception.escaped` | Uncaught JS error or unhandled promise rejection |
| `app.foreground` / `app.background` / `app.inactive` log | `app.state` | `AppState` change |

All attribute names match the Android + iOS SDKs so a single Dash0 query surfaces events across all three platforms.

## Dedupe behavior

- **Errors** dedupe on `${name}::${message}` within 5 minutes (matches Android `ErrorInstrumentation`)
- **unhandledrejection** shares the same keyspace so sync + async twins collapse to one log
- **AppState** suppresses consecutive duplicates on the same state value

## Gotchas

- **New Architecture required for TurboModules.** The package targets the modern RN runtime; old-arch projects work via the legacy bridge but lose batching efficiency.
- **Hermes is the supported engine.** JSC works but is not validated in CI.
- **Expo:** requires eject today. An Expo config plugin is tracked as EXPO-001.
- **No screenshot/wireframe** in RN — pending a cross-platform privacy design (same gate as iOS).

## See also

- [HOW_TO_DEMO_RN.md](HOW_TO_DEMO_RN.md) — end-to-end demo runbook
- [RN_ANDROID_IOS_PARITY.md](RN_ANDROID_IOS_PARITY.md) — feature matrix across the three SDKs
- [epics/REACT_NATIVE_EPIC.md](epics/REACT_NATIVE_EPIC.md) — architectural decisions + scope
