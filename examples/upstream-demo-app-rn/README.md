# AstronomyShopRN — React Native demo app

**Status:** Host app builds and runs on both Android and iOS. Telemetry lands
in Dash0 end-to-end. UAT matrix 12/12 green on both RN Android and RN iOS.

This is the canonical demo and E2E validation target for
`@dash0/mobile-react-native`. It matches the telemetry palette of the iOS
(`examples/upstream-demo-app-ios/`) and Android (`examples/upstream-demo-app/`)
native demos.

## What this demo proves

- `@dash0/mobile-react-native` works end-to-end on both iOS and Android
- Telemetry lands in Dash0 within ~3 s via OTLP (gRPC on Android, HTTP on iOS)
- Auto-instrumentation: fetch spans, JS errors, AppState lifecycle, screen views
- Service identity: `service.name=otel-rn-astronomy-shop`, dataset `otel-mobile`

## Build

### Android

```bash
cd AstronomyShopRN

# Publish SDK to Maven Local first (from repo root)
cd ../../.. && ./examples/demo-app/gradlew publishToMavenLocal && cd examples/upstream-demo-app-rn/AstronomyShopRN

npm install
npx react-native run-android
```

### iOS

```bash
cd AstronomyShopRN
npm install
cd ios && pod install && cd ..
npx react-native run-ios
```

## Telemetry configuration

Copy the template and fill in your Dash0 credentials:

```bash
cp otel-config.json.template otel-config.json
# Edit: set COLLECTOR_ENDPOINT, AUTH_TOKEN, DATASET
```

**Transport:** Android uses OTLP/gRPC on port 4317; iOS uses OTLP/HTTP on
port 4318. The app rewrites the port at startup based on platform — both
platforms share the same `otel-config.json`.

## Tests

```bash
# Jest (from AstronomyShopRN/)
npm test

# Full end-to-end (from repo root)
./scripts/test/validate-rn-end-to-end.sh --mode=jest
```

## What's not here yet

- **AstronomyShop screens** (RN-040–RN-042) — the 5 shop screens (Home,
  ProductList, ProductDetail, Cart, Checkout) with the 14-span checkout tree
  are deferred. The host app boots and emits lifecycle/auto-capture telemetry
  but does not yet replicate the full shopping flow of the native demos.
- **AutoDemoDriver.ts** (RN-041) — the JS equivalent of `AutoDemoDriver.swift`
  that drives a scripted traffic loop is not yet implemented.

## Non-goals

- Expo (planned follow-up — bare workflow only for now)
- Realm / Amplify DataStore (separate epic)
- Web / desktop RN targets
