# @dash0/mobile-react-native

Dash0 Mobile Observability SDK for React Native. Bridges JS/TS into the
existing native Android (`otel-android-mobile`) and iOS (`otel-ios-mobile`)
SDKs — native owns buffering, policy evaluation, OTLP export, and crash
recovery. JS stays thin.

**Status:** Complete and validated end-to-end in Dash0. Both Android and iOS
builds produce real binaries (140 MB APK, 239 MB .app) and telemetry lands in
Dash0 within ~3 s. All 4 platforms (Android native, iOS native, RN Android,
RN iOS) have a UAT matrix of 12/12 cells green. 83 Jest tests pass.

## Quickstart

```ts
import { Dash0Mobile } from '@dash0/mobile-react-native';

await Dash0Mobile.start({
  serviceName: 'my-rn-app',
  endpoint: 'https://ingress.us-west-2.aws.dash0.com/v1/logs',
  authToken: process.env.DASH0_AUTH_TOKEN,
  dataset: 'otel-mobile',
});

Dash0Mobile.log('cart.add_item', { 'shop.item_id': 'abc', qty: 2 });

await Dash0Mobile.span('checkout', async () => {
  await doCheckout();
});
```

## Build & test

```bash
# Install dependencies (first time)
npm install

# Jest — bridge contract + auto-instrumentation (83 tests)
npm test

# Type-check
npx tsc --noEmit

# End-to-end (package + AstronomyShopRN demo, Jest mode — no simulator)
../../scripts/test/validate-rn-end-to-end.sh --mode=jest
```

## Layout

```text
src/
  bridge/
    types.ts           # cross-repo seam — DO NOT change without coordinating
    NativeBridge.ts    # debounced batching marshaller (50 ms batch window)
  instrumentation/
    fetch.ts           # fetch/XHR span auto-capture
    xhr.ts             # XHR span auto-capture
    errors.ts          # JS error log auto-capture
    unhandledRejection.ts  # unhandled promise rejection capture
    navigation.ts      # React Navigation screen-view auto-capture
    touch.ts           # tap event auto-capture
  otel-compat.ts       # OTel-API shim (third-party JS libs flow through bridge)
  index.ts             # public API
android/               # Kotlin ReactContextBaseJavaModule + BridgeCallSink
ios/                   # Swift RCTDash0MobileModule + BridgeCallSink
__tests__/             # Jest — 83 tests across bridge + instrumentation
```

## Auto-instrumentation

Enabled by default when `Dash0Mobile.start()` is called. Opt out per signal:

```ts
await Dash0Mobile.start({
  // ...
  autoCapture: {
    network: false,   // disable fetch/XHR spans
    errors: false,    // disable JS error + rejection logs
    lifecycle: false, // disable AppState fg/bg
  },
});
```

## Sampling

**The RN bridge defaults to `always_on` (100% of spans)** — unlike the native
Android/iOS SDKs, which default to `dynamic(0.1)` (10% baseline). RN manual
spans (`Dash0Mobile.startSpan()` / `Dash0Mobile.span()`) are root spans with
arbitrary names, so a 10% baseline would silently drop ~90% of a user's very
first span — a terrible first-run experience (and on iOS a dropped span is a
non-recording span whose `.end()` is a silent no-op). For RN architectures,
sampling and rate-limiting belong in the collector, not the on-device SDK.

Opt into on-device sampling explicitly:

```ts
await Dash0Mobile.start({
  // ...
  // 100% (RN default — may be omitted):
  sampling: { strategy: 'always_on' },
  // Disable tracing entirely:
  // sampling: { strategy: 'always_off' },
  // Native-style dynamic sampling (10% baseline, 100% for high-priority spans):
  // sampling: { strategy: 'dynamic', normalRate: 0.1, highPriorityRate: 1.0 },
});
```

The native-only Android/iOS SDKs keep their `dynamic(0.1)` default — only the
RN-bridged default changes.

## Architecture

The JS layer is a thin marshaller with a 50 ms batching window. All buffering,
policy evaluation, export scheduling, and crash recovery happen inside the
native SDK on each platform:

```text
JS (fetch/XHR/errors/nav/tap)
  ↓  50 ms batch window
NativeBridge.ts  →  NativeDash0Mobile  →  Android: OTelMobile (gRPC :4317)
                                      →  iOS:     OTelMobile (HTTP :4318)
                                                ↓
                                          OTLP Collector → Dash0
```

**Transport note:** Android uses OTLP/gRPC on port 4317; iOS uses OTLP/HTTP on
port 4318. The shared `otel-config.json` uses a per-platform port rewrite at
startup — do not assume a single endpoint works for both.

## Test strategy

Three layers, all must pass on every PR:

1. **Jest** — `npm test` in this directory; contract + bridge + instrumentation
2. **Native unit** — `./gradlew :android:test` (Android) + `swift test` (iOS)
3. **Real-app E2E** — `scripts/test/validate-rn-end-to-end.sh` boots
   AstronomyShopRN on iOS Simulator + Android emulator, queries Dash0 after 75 s

## Known limitations

- **Expo** — not supported without eject (bare workflow only). An Expo config
  plugin is planned as a follow-up.
- **Realm / Amplify DataStore** — scoped in a separate epic.
- **Web / desktop RN targets** — not supported.
