# @dash0/mobile-react-native

Dash0 Mobile Observability SDK for React Native. Bridges JS/TS into the
existing native Android (`otel-android-mobile`) and iOS (`otel-ios-mobile`)
SDKs — native owns buffering, policy evaluation, OTLP export, and crash
recovery. JS stays thin.

**Status:** Phase 19a.0 — scaffold + failing-first tests only. Nothing works
end-to-end yet. See [../../docs/epics/REACT_NATIVE_EPIC.md](../../docs/epics/REACT_NATIVE_EPIC.md).

## Quickstart (planned — not functional yet)

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

## Layout

```
src/
  bridge/
    types.ts           # cross-repo seam — DO NOT change without coordinating
    NativeBridge.ts    # (pending) debounced batching marshaller
  instrumentation/     # (pending) fetch / errors / AppState / navigation
  index.ts             # public API
android/               # (pending) Kotlin ReactContextBaseJavaModule
ios/                   # (pending) Swift/ObjC RCTDash0MobileModule
__tests__/             # Jest — failing-first per TDD discipline
```

## Test strategy

Three layers, all must pass on every PR (see epic §"TDD Discipline"):

1. **Jest** — `npm test` in this dir; contract + bridge + instrumentation tests
2. **Native unit** — `./gradlew :android:test` + `swift test` in `android/` and `ios/` respectively
3. **Real-app E2E** — `scripts/test/validate-rn-end-to-end.sh` boots RN AstronomyShop on sim+emulator, queries Dash0 MCP after 75s
