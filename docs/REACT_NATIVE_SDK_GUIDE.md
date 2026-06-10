# React Native SDK Guide

`@barrysolomon/mobile-react-native@0.2.1-alpha` — a native-first React Native bridge over the existing Android and iOS Dash0 Mobile SDKs.

## TL;DR

```ts
import { Dash0Mobile } from '@barrysolomon/mobile-react-native';

await Dash0Mobile.start({
  serviceName: 'my-shop-app',
  endpoint: 'https://ingress.eu-west-1.aws.dash0.com',
  authToken: 'auth_xxxxxxxx',
  dataset: 'production',
});

Dash0Mobile.log('cart.item_added', { 'product.sku': 'SKU-42', qty: 2 });
Dash0Mobile.recordMetric('cart.size', 3, 'gauge');
```

That's it. Network spans, JS errors, unhandled promise rejections, and app lifecycle events capture automatically. Everything flows through the native SDK's dual-tier buffer → OTLP/HTTP to Dash0. (Per-signal URLs are built as `<endpoint>/v1/{logs,traces,metrics}`, so pass the base ingress host without a `/v1/...` suffix or a gRPC port.)

## Architecture (native-first)

The JS layer is thin. All buffering, policy DSL evaluation, disk spill, OTLP export, and crash recovery happens in the native Kotlin + Swift SDKs. The RN package marshals calls across the RN native-module bridge (50 ms debounce batching).

See [docs/epics/REACT_NATIVE_EPIC.md](epics/REACT_NATIVE_EPIC.md) for the architectural decision record.

## Install

```bash
# A bare `npm install @barrysolomon/mobile-react-native` resolves the OLD
# 0.1.0-alpha — always pin the `alpha` dist-tag (or the exact version):
npm install @barrysolomon/mobile-react-native@alpha
# or: npm install @barrysolomon/mobile-react-native@0.2.1-alpha

cd ios && pod install
```

Autolinking picks up the native module via the podspec + `react-native.config.js`. No manual linking required on RN 0.60+.

This JS package **wraps** the native SDKs — install those too:

- **iOS** — add the Swift Package `https://github.com/barrysolomon/mobile-otel` at tag `v0.2.1-alpha` to your app target, then copy `OTelMobileCallSink.swift` (+ `BoundedLiveSpanStore.swift`) from this package into your app target and call `Dash0MobileModule.installSink { OTelMobileCallSink() }`. The pod excludes the sink because it depends on the SwiftPM SDK delivered on the app side.
- **Android** — `io.opentelemetry.android:mobile:0.2.1-alpha` from GitHub Packages (`https://maven.pkg.github.com/barrysolomon/mobile-otel`). As of 0.2.0-alpha the full module set (`mobile-core` + all `mobile-instrumentation-*` modules) publishes there, so the dependency tree resolves.

**Requirements:**
- RN 0.72+ (TurboModules / New Architecture recommended; 0.76+ defaults to New Arch)
- iOS 15+, Android API 26+
- React 18+

## API

### `Dash0Mobile.start(config)`

| Field | Type | Required | Purpose |
|-------|------|----------|---------|
| `serviceName` | `string` | yes | `service.name` resource attribute |
| `serviceVersion` | `string` | no | `service.version` resource attribute |
| `endpoint` | `string` | yes | OTLP base ingress host (e.g. `https://ingress…aws.dash0.com`); per-signal URLs built as `<endpoint>/v1/{logs,traces,metrics}` |
| `authToken` | `string` | yes for Dash0 | Bearer token |
| `dataset` | `string` | no | Dash0 dataset name |
| `sampling` | `SamplingConfig` | no (default `{ strategy: 'always_on' }`) | Trace sampling strategy — see [Sampling](#sampling) |
| `autoCapture.network` | `boolean` | no (default `true`) | HTTP capture: JS fetch/XHR shims + native interceptor |
| `autoCapture.errors` | `boolean` | no (default `true`) | Hook ErrorUtils + unhandledrejection |
| `bufferConfig.ramEvents` | `number` | no | RAM ring buffer capacity (native default) |
| `bufferConfig.diskBytes` | `number` | no | Disk spill cap (native default) |
| `enablePolicyPolling` | `boolean` | no | Poll the control plane for DSL updates |
| `extraResourceAttributes` | `Record<string,string>` | no | Extra resource attributes merged into the native resource |

> There is **no** `autoCapture.lifecycle` flag. App lifecycle (`app.foreground` / `app.background` / `app.start`) is always-on via native instrumentation (Android `ProcessLifecycleOwner`, iOS `NotificationCenter`). The remaining `autoCapture` flags — `tap`, `scroll`, `textInput`, `screen`, `freeze`, `vitals`, `deviceStats`, `screenshot`, `wireframe` — toggle native-only capture suites and default **off** on RN.

### Sampling

The RN bridge defaults to **`always_on`** (100% of spans) — unlike the native Android/iOS SDKs, which default to `dynamic(0.1)`. RN manual spans (`startSpan` / `span`) are root spans with arbitrary names, so a 10% baseline would silently drop ~90% of a user's very first span. For RN, sample/rate-limit in the collector, not on-device.

```ts
await Dash0Mobile.start({
  // ...
  // 100% (RN default — may be omitted):
  sampling: { strategy: 'always_on' },
  // Drop all spans:
  // sampling: { strategy: 'always_off' },
  // Native-style dynamic (10% baseline, 100% for high-priority spans):
  // sampling: { strategy: 'dynamic', normalRate: 0.1, highPriorityRate: 1.0 },
});
```

`SamplingConfig = { strategy: 'always_on' | 'always_off' | 'dynamic'; normalRate?: number; highPriorityRate?: number }`. `normalRate` / `highPriorityRate` apply only to `dynamic`. The native-only SDKs keep their `dynamic(0.1)` default; only the RN-bridged default changes.

### HTTP capture on Android is native (Expo SDK 52+)

On Android, network capture is owned by a native OkHttp interceptor (`OTelNetworkInterceptor`) installed before JS runs; it records native CLIENT spans and injects a W3C `traceparent` so Android mobile→backend traces stitch. The JS `XMLHttpRequest` shim is auto-gated **off** on Android to avoid double-counting. This is essential because **Expo SDK 52+ replaced the global `fetch` with `expo/fetch`, a non-XHR-backed client** — the JS shims wrap the JS globals and see none of that traffic; only the native interceptor (under OkHttp) captures it. iOS keeps the JS XHR shim (RN iOS `fetch` is XHR-backed; the native `URLProtocol` swizzle is opt-in/off by default for RN).

### Signals

```ts
Dash0Mobile.log(name, attributes?, severity?);   // severity: OTel enum, default 9 (INFO)
Dash0Mobile.recordMetric(name, value, 'counter'|'histogram'|'gauge', attrs?);
Dash0Mobile.startSpan(name, attrs?, kind?);      // returns SpanHandle
Dash0Mobile.span(name, async fn, attrs?);        // async helper; fn returns any
await Dash0Mobile.flushWindow(minutes);          // selective flush of buffered events
await Dash0Mobile.shutdown();                    // flush + tear down

// User Journey API (thin passthrough to native; native creates the journey
// span and triggers screenshot + wireframe captures at start/end boundaries):
const journeyId = await Dash0Mobile.startJourney('checkout'); // string | null
await Dash0Mobile.endJourney(journeyId);
await Dash0Mobile.captureScreenshot(trigger?); // trigger defaults to 'manual'
await Dash0Mobile.captureWireframe(trigger?);  // trigger defaults to 'manual'
```

### Opt-in helpers

```ts
// React Navigation: emits ui.screen_view log + page.<name> span per route change
import { installReactNavigationInstrumentation } from '@barrysolomon/mobile-react-native';

const navRef = useNavigationContainerRef();
useEffect(() => installReactNavigationInstrumentation(navRef), [navRef]);

// Touch telemetry: wrap any onPress handler
import { withTapTelemetry } from '@barrysolomon/mobile-react-native';

<Pressable onPress={withTapTelemetry('cart.checkout_button', handleCheckout)} />

// OTel API compat (use standard OTel API in JS libraries)
import { otel } from '@barrysolomon/mobile-react-native';

const tracer = otel.trace.getTracer('my-lib');
const span = tracer.startSpan('work', { attributes: { 'code.function': 'sync' } });
span.end();
```

## What's captured automatically

| Signal | Attributes | When |
|--------|-----------|------|
| HTTP CLIENT span (fetch/XHR) | `http.request.method`, `url.full`, `server.address`, `http.response.status_code` | Every `fetch()` or XHR |
| `app.error` log | `exception.type`, `exception.message`, `exception.stacktrace`, `exception.escaped` | Uncaught JS error or unhandled promise rejection |
| `app.foreground` / `app.background` / `app.start` log | `app.state` | Native lifecycle (Android `ProcessLifecycleOwner`, iOS `NotificationCenter`) |

All attribute names match the Android + iOS SDKs so a single Dash0 query surfaces events across all three platforms.

## Dedupe behavior

- **Errors** dedupe on `${name}::${message}` within 5 minutes (matches Android `ErrorInstrumentation`)
- **unhandledrejection** shares the same keyspace so sync + async twins collapse to one log

## Gotchas

- **New Architecture required for TurboModules.** The package targets the modern RN runtime; old-arch projects work via the legacy bridge but lose batching efficiency.
- **Hermes is the supported engine.** JSC works but is not validated in CI.
- **Expo:** works in the bare/dev-client workflow (native modules required). 0.2.0-alpha was hardened against a real Expo SDK 56 / RN 0.85 integration, including `expo/fetch` capture via native Android instrumentation. A managed-workflow config plugin (no prebuild) is tracked as EXPO-001.
- **Screenshot/wireframe** are bridged through to the native SDKs (`Dash0Mobile.captureScreenshot` / `captureWireframe`, and `autoCapture.screenshot` / `wireframe`), but the bridge currently carries only the on/off `enabled` bit — per-trigger flags must be set natively until the bridge contract grows them (see [RN_ANDROID_IOS_PARITY.md](RN_ANDROID_IOS_PARITY.md)).

## See also

- [HOW_TO_DEMO_RN.md](HOW_TO_DEMO_RN.md) — end-to-end demo runbook
- [RN_ANDROID_IOS_PARITY.md](RN_ANDROID_IOS_PARITY.md) — feature matrix across the three SDKs
- [epics/REACT_NATIVE_EPIC.md](epics/REACT_NATIVE_EPIC.md) — architectural decisions + scope
