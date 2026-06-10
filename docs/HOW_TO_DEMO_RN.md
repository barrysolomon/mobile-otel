# How to Demo: React Native

Companion to [HOW_TO_DEMO.md](../HOW_TO_DEMO.md) (Android) and HOW_TO_DEMO_IOS.md. Shows the Dash0 Mobile SDK running inside a React Native app — proves cross-platform parity from a single codebase.

## What you'll show

- The same AstronomyShop UX (home → product → cart → checkout) implemented in React Native
- Identical span / log / metric shapes to the native iOS and Android demos
- Auto-captured `fetch` + XHR spans, `app.error` logs, `app.foreground` / `app.background` lifecycle logs
- 14-span checkout trace tree matching the iOS `CartViewModel.swift` shape

All three demos emit `service.name` matching `otel-.*-astronomy-shop`, so one Dash0 filter surfaces the cross-platform event stream side by side.

## Prereqs

- Node 20+
- Xcode 15+ (for iOS simulator)
- Android Studio with an API 34+ AVD booted
- Dash0 credentials in `examples/upstream-demo-app-rn/AstronomyShopRN/otel-config.json` (copy `.template`, fill in)

## Validation modes

The Jest + typecheck path runs without a simulator:

```bash
cd mobile-otel
./scripts/test/validate-rn-end-to-end.sh --mode=jest
# → RN package + AstronomyShopRN demo Jest suites
```

Device mode drives the `AstronomyShopRN` host app on a simulator/emulator and asserts telemetry in Dash0:

```bash
./scripts/test/validate-rn-end-to-end.sh --mode=device
```

> The AstronomyShopRN host projects are scaffolded (`examples/upstream-demo-app-rn/AstronomyShopRN/{ios,android}` exist). The device-mode install/launch automation is still being wired into the script; until then, run the manual runbook below — its steps are real and runnable against the scaffolded host app.

## Device-mode runbook

**Step 1 — Boot simulators**
```bash
xcrun simctl boot "iPhone 17"
emulator -avd Pixel_7 -no-snapshot-save &
```

**Step 2 — Install + launch iOS**
```bash
cd examples/upstream-demo-app-rn/AstronomyShopRN/ios
pod install
xcodebuild -workspace AstronomyShopRN.xcworkspace -scheme AstronomyShopRN \
  -destination "platform=iOS Simulator,name=iPhone 17" build install
# Bundle id is the RN default org.reactjs.native.example.AstronomyShopRN
xcrun simctl launch --setenv DASH0_AUTO_DEMO=1 booted org.reactjs.native.example.AstronomyShopRN
```

**Step 3 — Install + launch Android**
```bash
cd examples/upstream-demo-app-rn/AstronomyShopRN/android
# The app has product flavors (upstream / dash0Continuous / dash0Conditional /
# dash0Hybrid). Pick the SDK-export-mode flavor you want to demo, e.g.:
./gradlew installDash0HybridDebug
# applicationId = com.astronomyshoprn + flavor suffix (dash0Hybrid → .dash0.hyb):
adb shell am start -e DASH0_AUTO_DEMO 1 com.astronomyshoprn.dash0.hyb/com.astronomyshoprn.MainActivity
```

**Step 4 — Watch telemetry land in Dash0**
- Filter `service.name = "otel-rn-astronomy-shop"` (or regex to see all three platforms: `service.name =~ "otel-.*-astronomy-shop"`)
- Group by `os.name` to see RN traces side-by-side with iOS + Android
- Expect: 14-span checkout trees, `shop.load_catalog` root spans, `shop.checkout.duration_ms` histogram (carries a `shop.cart_size` attribute)

**Step 5 — Show cross-platform consistency**
- Same span tree shape on all three platforms
- Same attribute names (`http.request.method`, `url.full`, `exception.type`, etc.)
- Same severity numbers, same OTel semantic conventions

## AutoDemoDriver

The RN demo ships with `AutoDemoDriver` — an 800 ms-cadence (`AUTO_DEMO_CADENCE_MS`) state machine that drives synthetic user interaction. Each cycle is:

1. **3 × browse** — for each, view a product then add it to the cart (`viewProduct` + `addToCart`)
2. **1 × checkout** — full checkout trace tree
3. **1 × idle** — a quiet tick

The driver loops indefinitely (no fixed stop), rotating the catalog start index by 2 each cycle so Dash0 accumulates varied events and real histograms. Stop it via the driver's `stop()`.

See [AutoDemoDriver.ts](../examples/upstream-demo-app-rn/AstronomyShopRN/src/shop/AutoDemoDriver.ts).

## Talking points during the demo

- **Native-first bridge:** the JS layer is thin; all buffering + OTLP export runs in the same Kotlin/Swift SDK you saw in the Android/iOS demos.
- **OTel parity:** attribute names and span shapes are identical — no RN-specific schema drift.
- **Auto-capture opt-outs:** `autoCapture: { network: false }` / `{ errors: false }` disable each subsystem for customers who want finer control. (App lifecycle is always-on via native instrumentation — there's no `lifecycle` flag.)
- **Native-owned Android network + traceparent:** on Android the OkHttp interceptor captures HTTP (including Expo SDK 52+ `expo/fetch`, which bypasses the JS `fetch`/XHR globals) and injects W3C `traceparent`, so mobile→backend traces stitch. The JS XHR shim is auto-gated off on Android; iOS keeps it.
- **Opt-in helpers:** React Navigation screen tracking and Touchable tap telemetry are one-liners — mirrors Android `ScreenViewInstrumentation` + `TapInstrumentation` behavior.
- **OTel API compat shim:** existing OTel-instrumented JS libraries can use `otel.trace.getTracer(...)` with no dependency on the full `@opentelemetry/api` package.

## See also

- [REACT_NATIVE_SDK_GUIDE.md](REACT_NATIVE_SDK_GUIDE.md) — integration guide
- [RN_ANDROID_IOS_PARITY.md](RN_ANDROID_IOS_PARITY.md) — feature matrix
- [epics/REACT_NATIVE_EPIC.md](epics/REACT_NATIVE_EPIC.md) — architecture + scope
