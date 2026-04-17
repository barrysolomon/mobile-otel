# iOS Real-Device Validation Playbook

The iOS SDK has been validated end-to-end on the iOS Simulator. Before merging the `iPhone` branch to `main`, it must be validated on real iPhone hardware — the simulator doesn't exercise several code paths that matter on device:

- **Real signal handlers** — the simulator runs under the host macOS kernel. Signal delivery, async-signal-safety on non-mach-task threads, and crash behavior all differ on real devices.
- **Battery/thermal/memory instruments** — `DeviceStatsCollector` reads values that are static or synthesised in the simulator.
- **Network stack** — cellular radio paths, VPN, TLS pinning, URLProtocol chain with other SDKs (analytics, A/B testing, crash reporters) are all untested in sim.
- **SwiftUI rendering on real ProMotion displays** — `FreezeInstrumentation`'s 250 ms threshold is more meaningful on a 120 Hz display than the simulator's fixed refresh rate.
- **Code signing + entitlements** — the sim doesn't enforce provisioning, so entitlement failures only surface on device.
- **Battery impact** — not measurable in sim.

## Prerequisites

- A physical iPhone running iOS 16.0+ (our `deploymentTarget`)
- Apple Developer account with a team ID Xcode can sign with (free tier works for personal-device installs)
- The device paired with your Mac via USB or wifi (Xcode → Window → Devices and Simulators)
- Dash0 credentials filled into `examples/upstream-demo-app-ios/AstronomyShop/otel-config.json` (the one already used for simulator validation)

## Step 1 — Build for a real device

```bash
cd examples/upstream-demo-app-ios
xcodegen generate   # if you don't already have the project

# Open in Xcode so you can set the signing team:
open AstronomyShop.xcodeproj
```

In Xcode:
1. Select the `AstronomyShop` target.
2. Under **Signing & Capabilities**, set **Team** to your Apple Developer team.
3. Change the **Bundle Identifier** if needed (`com.dash0.mobile.demo.AstronomyShop` may conflict with someone else's provisioning — prefix with your org if so).
4. At the top of the Xcode window, select your physical device from the scheme picker.
5. **Product → Run** (⌘R).

Alternatively, from the command line if you've already signed once via Xcode:

```bash
DEVICE_UDID=$(xcrun xctrace list devices 2>&1 | grep -v Simulator | awk -F'[()]' '/iPhone/ {print $(NF-1); exit}')
xcodebuild \
    -scheme AstronomyShop \
    -destination "platform=iOS,id=$DEVICE_UDID" \
    -configuration Debug \
    build
xcrun devicectl device install app --device "$DEVICE_UDID" \
    build/Build/Products/Debug-iphoneos/AstronomyShop.app
xcrun devicectl device process launch --device "$DEVICE_UDID" \
    com.dash0.mobile.demo.AstronomyShop
```

## Step 2 — Exercise the full flow

With the app running on the device:

1. **Launch sanity** — the product list should appear within 2 s. Confirm `app.home_appeared` INFO log shows up in Dash0 within ~5 s.
2. **Browse + add to cart** — tap 3 products, add each. Confirm one `shop.view_product` 3-span trace per tap + `shop.cart.items_added` counter increments.
3. **Large-quantity WARN** — add 5+ of a single product. Confirm `cart.large_quantity_warning` WARN log.
4. **Checkout** — open cart, tap Checkout. Confirm the 14-span 3-level deep `checkout` trace lands (drill into the trace view in Dash0 and expand the tree).
5. **Background/foreground** — lock the device screen for 30 s, then wake it. Confirm `app.background` + `app.foreground` logs.
6. **Kill from app switcher** — swipe the app out of the app-switcher. Relaunch. Confirm no `app.crash` log (clean exit).
7. **Crash recovery** — trigger a crash (simplest: a debug build with a `fatalError("manual test crash")` wired to a hidden button, or trip a deliberate array out-of-bounds from a button). Relaunch. Confirm `app.crash` log with severity `fatal` and `exception.stacktrace`.

## Step 3 — Verify in Dash0

Run the same Dash0 queries that `validate-ios-end-to-end.sh` runs, but with the filter extended to your device identifier to exclude any residual simulator traffic:

```
service.name = "otel-ios-astronomy-shop"
device.model.identifier = "iPhone17,3"          # your device model
```

Expected within a 10-minute window of real interaction:

- ≥ 20 logs across INFO and WARN severities
- ≥ 50 spans including at least 2 full `checkout` trees
- All 3 custom metrics (`shop.cart.items_added`, `shop.checkout.duration_ms`, `shop.view_product.load_ms`) with > 0 data points
- Resource attributes: `os.name=iOS`, `device.manufacturer=Apple`, `device.model.identifier` matching your device, `telemetry.sdk.name=io.dash0.mobile`

## Step 4 — Record findings

Post results under a new heading in this file:

```markdown
## Validation run — YYYY-MM-DD

- Device: iPhone 15 Pro Max, iOS 17.5.1
- Build: commit <sha>
- Duration: 12 minutes interactive
- Logs observed: 47
- Spans observed: 134 (6 checkout trees)
- Metrics: all 3 present
- Issues: <none | describe>
```

If any of the expected signals are missing, or if new issues appear that don't reproduce in the simulator, file them before merging to `main`.

## Known things that differ from simulator

- **Initial launch is slower** — first launch on a real device spends more time in `DYLD_PRINT_STATISTICS` territory. `app.start` duration will be higher than you've seen in the sim.
- **Memory warnings are real** — `app.memory_warning` logs may appear under memory pressure. In the sim they only fire via the Simulate Memory Warning menu.
- **CADisplayLink on ProMotion (120 Hz)** — `ui.jank` threshold calibration may need adjusting from the sim defaults. Note the base frame interval in emitted `ui.jank` attributes and consider tuning `FreezeInstrumentation.pingIntervalMs` if too many false positives fire.
