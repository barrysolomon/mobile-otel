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

## Current status: Jest path works; device path is pending RN-003 scaffolding

The Jest + typecheck validation is production-ready:

```bash
cd mobile-otel
./scripts/test/validate-rn-end-to-end.sh --mode=jest
# → 70 RN package tests + 13 demo tests green
```

Device mode is stubbed out until the AstronomyShopRN host Xcode workspace + Android Gradle wrapper land (see RN-003 in the epic):

```bash
./scripts/test/validate-rn-end-to-end.sh --mode=device
# → Fails with clear "RN-003 host projects not yet scaffolded" message
```

## Planned device-mode runbook (when RN-003 is complete)

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
xcrun simctl launch --setenv DASH0_AUTO_DEMO=1 booted com.dash0.AstronomyShopRN
```

**Step 3 — Install + launch Android**
```bash
cd examples/upstream-demo-app-rn/AstronomyShopRN/android
./gradlew installDebug
adb shell am start -e DASH0_AUTO_DEMO 1 com.dash0.astronomyshoprn/.MainActivity
```

**Step 4 — Watch telemetry land in Dash0**
- Filter `service.name = "otel-rn-astronomy-shop"` (or regex to see all three platforms: `service.name =~ "otel-.*-astronomy-shop"`)
- Group by `os.name` to see RN traces side-by-side with iOS + Android
- Expect: 14-span checkout trees, `shop.load_catalog` root spans, `cart.size` histogram

**Step 5 — Show cross-platform consistency**
- Same span tree shape on all three platforms
- Same attribute names (`http.request.method`, `url.full`, `exception.type`, etc.)
- Same severity numbers, same OTel semantic conventions

## AutoDemoDriver

The RN demo ships with `AutoDemoDriver` — an 800 ms-cadence state machine that drives 5 phases of synthetic user interaction:

1. **Browse** — catalog load + scroll through products
2. **Inspect** — open 3 random products (emits `shop.view_product` trees)
3. **Cart** — add 2 items with qty variation
4. **Checkout** — full 14-span checkout tree
5. **Error** — deliberate network failure to exercise the error path

Phases repeat for 75 s, giving Dash0 enough events to show real histograms.

See [AutoDemoDriver.ts](../examples/upstream-demo-app-rn/AstronomyShopRN/src/demo/AutoDemoDriver.ts).

## Talking points during the demo

- **Native-first bridge:** the JS layer is thin; all buffering + OTLP export runs in the same Kotlin/Swift SDK you saw in the Android/iOS demos.
- **OTel parity:** attribute names and span shapes are identical — no RN-specific schema drift.
- **Auto-capture opt-outs:** `autoCapture: { network: false }` / `{ errors: false }` / `{ lifecycle: false }` disable each subsystem for customers who want finer control.
- **Opt-in helpers:** React Navigation screen tracking and Touchable tap telemetry are one-liners — mirrors Android `ScreenViewInstrumentation` + `TapInstrumentation` behavior.
- **OTel API compat shim:** existing OTel-instrumented JS libraries can use `otel.trace.getTracer(...)` with no dependency on the full `@opentelemetry/api` package.

## See also

- [REACT_NATIVE_SDK_GUIDE.md](REACT_NATIVE_SDK_GUIDE.md) — integration guide
- [RN_ANDROID_IOS_PARITY.md](RN_ANDROID_IOS_PARITY.md) — feature matrix
- [epics/REACT_NATIVE_EPIC.md](epics/REACT_NATIVE_EPIC.md) — architecture + scope
