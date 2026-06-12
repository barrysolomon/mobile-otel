# @barrysolomon/mobile-react-native

Dash0 Mobile Observability SDK for React Native. Bridges JS/TS into the
existing native Android (`otel-android-mobile`) and iOS (`otel-ios-mobile`)
SDKs — native owns buffering, policy evaluation, OTLP export, and crash
recovery. JS stays thin.

**Version:** `0.4.0-alpha`, published under the `alpha` dist-tag.

**Status:** Validated end-to-end in Dash0. All 4 platforms (Android native,
iOS native, RN Android, RN iOS) have a UAT matrix of 12/12 cells green.

## Install

```bash
# Install the alpha (a bare `npm install @barrysolomon/mobile-react-native`
# resolves the OLD 0.1.0-alpha — always pin the dist-tag or version):
npm install @barrysolomon/mobile-react-native@alpha
# or pin the exact version:
# npm install @barrysolomon/mobile-react-native@0.4.0-alpha

cd ios && pod install
```

This JS package wraps the native SDKs; install those too:

- **iOS** — add the Swift Package `https://github.com/barrysolomon/mobile-otel`
  at tag `v0.4.0-alpha` to your app target, then copy
  `OTelMobileCallSink.swift` (+ `BoundedLiveSpanStore.swift`) from this package
  into your app target and call
  `Dash0MobileModule.installSink { OTelMobileCallSink() }`. The pod intentionally
  excludes the sink because it depends on the SwiftPM SDK delivered on the app
  side.
- **Android** — `io.opentelemetry.android:mobile:0.4.0-alpha` from GitHub
  Packages (`https://maven.pkg.github.com/barrysolomon/mobile-otel`). As of
  0.2.0-alpha the full module set (`mobile-core` + all
  `mobile-instrumentation-*` modules) publishes there, so the dependency tree
  resolves.

## Quickstart

```ts
import { Dash0Mobile } from '@barrysolomon/mobile-react-native';

await Dash0Mobile.start({
  serviceName: 'my-rn-app',
  // Base ingress host — per-signal URLs are built as
  // `<endpoint>/v1/{logs,traces,metrics}`; do NOT append `/v1/...` yourself.
  endpoint: 'https://ingress.us-west-2.aws.dash0.com',
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

# Jest — bridge contract + auto-instrumentation
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
    fetch.ts           # fetch span shim (non-RN/web; off on RN — fetch is XHR-backed)
    xhr.ts             # XHR span shim (authoritative JS layer; gated off on Android)
    errors.ts          # JS error log auto-capture
    unhandledRejection.ts  # unhandled promise rejection capture
    navigation.ts      # React Navigation screen-view auto-capture (opt-in)
    touch.ts           # tap event auto-capture (opt-in via withTapTelemetry)
  otel-compat.ts       # OTel-API shim (third-party JS libs flow through bridge)
  index.ts             # public API
android/               # Kotlin ReactContextBaseJavaModule + OkHttp net interceptor
ios/                   # Swift RCTDash0MobileModule + OTelMobileCallSink
__tests__/             # Jest — bridge contract + instrumentation suites
```

## Auto-instrumentation

`network` and `errors` are enabled by default when `Dash0Mobile.start()` is
called. Opt out per signal:

```ts
await Dash0Mobile.start({
  // ...
  autoCapture: {
    network: false,   // disable HTTP capture (JS fetch/XHR shims + native net)
    errors: false,    // disable JS error + unhandled-rejection logs
  },
});
```

App lifecycle (`app.foreground` / `app.background` / `app.start`) is **always
on via native instrumentation** (Android `ProcessLifecycleOwner`, iOS
`NotificationCenter`) — there is no JS or per-flag knob for it. The other
`autoCapture` flags (`tap`, `scroll`, `textInput`, `screen`, `freeze`,
`vitals`, `deviceStats`, `screenshot`, `wireframe`) toggle native-only capture
suites and default **off** on RN.

### HTTP capture on Android is native (Expo SDK 52+)

On Android, network capture is owned by a native OkHttp interceptor
(`OTelNetworkInterceptor`) installed before JS runs. It records native CLIENT
spans and injects a W3C `traceparent` from the real native span context, so
Android mobile→backend traces stitch. The JS `XMLHttpRequest` shim is
auto-gated **off** on Android to avoid double-counting.

This matters because **Expo SDK 52+ replaced the global `fetch` with
`expo/fetch`, a non-XHR-backed client.** The JS `fetch`/XHR shims wrap the JS
globals and see nothing there — only the native interceptor (which sits under
OkHttp) captures `expo/fetch` traffic. On iOS the JS XHR shim is kept (RN iOS
`fetch` is XHR-backed and the native `URLProtocol` swizzle is opt-in/off by
default for RN).

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
NativeBridge.ts  →  NativeDash0Mobile  →  Android: OTelMobile (OTLP/HTTP)
                                      →  iOS:     OTelMobile (OTLP/HTTP)
                                                ↓
                                          OTLP Collector → Dash0
```

**Transport note:** As of 0.2.0-alpha both platforms default to OTLP/HTTP
(protobuf) against a single endpoint, with per-signal URLs built as
`<endpoint>/v1/{logs,traces,metrics}`. Android changed from gRPC to HTTP so
exports traverse HTTPS-terminating proxies / managed ingress that can't forward
HTTP/2 gRPC. gRPC-only collector? Restore it on Android with
`MobileConfig.protocol = OtlpProtocol.GRPC`.

## Test strategy

Three layers, all must pass on every PR:

1. **Jest** — `npm test` in this directory; contract + bridge + instrumentation
2. **Native unit** — `./gradlew :android:test` (Android) + `swift test` (iOS)
3. **Real-app E2E** — `scripts/test/validate-rn-end-to-end.sh` (use `--mode=jest`
   for the package + AstronomyShopRN demo tests; `--mode=device` boots
   AstronomyShopRN on iOS Simulator + Android emulator and queries Dash0)

## Known limitations

- **Expo** — works in the bare/dev-client workflow (native modules required).
  0.2.0-alpha was hardened against a real Expo SDK 56 / RN 0.85 integration,
  including the Expo SDK 52+ `expo/fetch` capture via native Android
  instrumentation (see above). A managed-workflow config plugin (no prebuild)
  is tracked as a follow-up (EXPO-001).
- **Realm / Amplify DataStore** — scoped in a separate epic.
- **Web / desktop RN targets** — not supported.
