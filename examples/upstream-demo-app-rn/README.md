# AstronomyShop-RN — React Native port of the upstream demo

**Status:** Phase 19a.0 — scaffold only. The app does not build yet.

This directory will hold the React Native port of AstronomyShop, matching the
iOS (`examples/upstream-demo-app-ios/`) and Android (`examples/upstream-demo-app/`)
variants. It is the canonical demo and E2E validation target for
`@dash0/mobile-react-native`.

## What this demo will prove (when done)

- `@dash0/mobile-react-native` works end-to-end on both iOS and Android
- The same telemetry palette lands in Dash0 as the native demos:
  - Logs: `cart.add_item`, `shop.view_product`, `cart.large_quantity_warning`
  - Spans: 14-span checkout tree, 3-span product view, 4-span catalog load
  - Metrics: `shop.cart.items_added` counter, `shop.checkout.duration_ms` + `shop.view_product.load_ms` histograms
- Service identity: `service.name=otel-rn-astronomy-shop`, dataset `otel-mobile`
- AutoDemoDriver (JS) drives traffic when `DASH0_AUTO_DEMO=1` env is set via `SIMCTL_CHILD_` prefix (iOS) or `am start` extras (Android).

## Sequencing

1. **RN-003** (this file + bare RN 0.76 template init) — scaffold
2. **RN-040** — port the 5 shop screens (Home, ProductList, ProductDetail, Cart, Checkout)
3. **RN-041** — AutoDemoDriver.ts mirroring `AutoDemoDriver.swift`
4. **RN-042** — rich trace shapes matching the iOS trace tree
5. **RN-050** — `scripts/test/validate-rn-end-to-end.sh` validates against Dash0 MCP

See [../../docs/epics/REACT_NATIVE_EPIC.md](../../docs/epics/REACT_NATIVE_EPIC.md) for the full epic.

## Non-goals

- Expo (will be a follow-up — `packages/react-native` must work bare first)
- Realm / Amplify (follow-up epics)
- Web / desktop RN targets
