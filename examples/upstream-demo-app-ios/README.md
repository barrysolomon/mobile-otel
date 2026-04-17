# Astronomy Shop — iOS port

SwiftUI port of `examples/upstream-demo-app/` (the Android Astronomy Shop
adapted from the upstream `opentelemetry-android` community demo). Both apps
share the same `products.json` catalog and product images so telemetry from
Android + iOS is directly comparable in Dash0.

## What's here

- SwiftUI navigation: product list → product detail → cart → checkout
- Bundled product catalog (10 telescopes/accessories)
- Full OTel SDK wiring: logs + traces + metrics + iOS resource attributes
- Auto-instrumentation: `NetworkInstrumentation`, `LifecycleInstrumentation`, `ErrorsInstrumentation`
- Cart actions emit `cart.add_item`, `cart.remove_item`, `cart.cleared` logs
- Checkout emits a `checkout` parent span with `validate`/`charge`/`confirm` children

## Setup

```bash
# 1. Fill in Dash0 credentials
cp AstronomyShop/otel-config.json.template AstronomyShop/otel-config.json
$EDITOR AstronomyShop/otel-config.json

# 2. Generate the Xcode project (first time + after adding files)
xcodegen generate

# 3. Build for a simulator
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild \
    -scheme AstronomyShop \
    -destination "platform=iOS Simulator,name=iPhone 17" \
    -derivedDataPath ./build build

# 4. Install + launch
xcrun simctl install booted ./build/Build/Products/Debug-iphonesimulator/AstronomyShop.app
xcrun simctl launch booted com.dash0.mobile.demo.AstronomyShop
```

Or use `scripts/demo/demo-control-center-ios.sh full` pointed at this project
(update the `DEMO_ROOT` variable if needed).

## What to expect in Dash0

Filter by `service.name="otel-ios-astronomy-shop"` or `os.name="iOS"`:

- **Traces**: `shop.load_catalog`, `GET <URL>` (for any images/network), `checkout` parent
  with nested `checkout.validate` / `charge` / `confirm`
- **Logs**: `app.home_appeared`, `app.foreground`/`app.background` (from Lifecycle),
  `cart.add_item`, `cart.remove_item`, `cart.cleared`, any `app.crash` from a
  prior run's crash marker
- **Metrics**: Nothing emitted by the app itself by default — toggle
  `mobile.deviceStats.start(...)` in code if you want memory/battery gauges

## Coverage vs Android parity

| Feature | Android | iOS | Notes |
|---|---|---|---|
| Product list | ✅ | ✅ | SwiftUI List + Image catalog |
| Product detail + qty | ✅ | ✅ | Stepper for quantity |
| Cart add/remove/clear | ✅ | ✅ | Logs each action |
| Checkout (sim'd) | ✅ | ✅ | Parent + 3 child spans |
| Recommendations | ✅ | TODO | Deferred to follow-on |
| Image loader span | ✅ | TODO | Defer — UIImage(named:) is synchronous for bundled resources |
| About + feature list | ✅ | TODO | |
| Crash popup | ✅ | TODO | `ErrorsInstrumentation.shared.recordError` works, demo button TBD |
| SdkInitializer `dash0` flavor | ✅ | N/A | iOS uses single `ShopBootstrap` path |
