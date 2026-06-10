# iOS Demo Runbook

Runbook for showing the Dash0 Mobile Observability iOS SDK live. Covers the Astronomy Shop demo (the primary iOS demo), an optional crash + recovery demo, and what to point at in Dash0.

**Total time: ~6 minutes** for the happy path, plus however long you spend narrating in Dash0.

## Prerequisites

Install once per machine:

- **Xcode 26+** (for the demo app's iOS 26 simulator runtime). Command Line Tools alone is enough for `swift test` on the SDK package but not for the demo app.
  ```bash
  xcode-select -p
  # Should print /Applications/Xcode.app/Contents/Developer. If not:
  sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
  ```
- **iOS 26 simulator runtime** (or whichever matches your Xcode). Verify:
  ```bash
  xcrun simctl list runtimes
  ```
  If the runtime is missing, install it from **Xcode → Settings → Components**, or via `xcrun simctl runtime add`.
- **XcodeGen** for the demo project generator:
  ```bash
  brew install xcodegen
  ```
- **Dash0 credentials** — endpoint, auth token, and dataset name. The app reads these from an `otel-config.json` file that is `.gitignored`. Copy the template and fill it in:
  ```bash
  cp examples/upstream-demo-app-ios/AstronomyShop/otel-config.json.template \
     examples/upstream-demo-app-ios/AstronomyShop/otel-config.json
  # Edit it to replace YOUR_COLLECTOR_ENDPOINT, YOUR_AUTH_TOKEN, YOUR_DATASET_NAME.
  ```

## Part 1 — Astronomy Shop (primary demo)

The Astronomy Shop is a SwiftUI port of the Android `upstream-demo-app/` telescopes-and-accessories store. Both apps share the same `products.json` catalog and product images so telemetry lands comparably in Dash0. See [upstream-demo-app-ios/README.md](../examples/upstream-demo-app-ios/README.md).

### One-liner — boot + build + install + launch + screenshot

```bash
scripts/demo/demo-control-center-ios.sh full
```

### Narrated, step by step

```bash
# 1. Boot the simulator (default: "iPhone 17"; override with IOS_SIM_NAME=...)
scripts/demo/demo-control-center-ios.sh boot

# 2. Generate the Xcode project (first time only, or after adding files)
(cd examples/upstream-demo-app-ios && xcodegen generate)

# 3. Build for the simulator
scripts/demo/demo-control-center-ios.sh build

# 4. Install the built .app onto the booted simulator
scripts/demo/demo-control-center-ios.sh install

# 5. Launch the app
scripts/demo/demo-control-center-ios.sh launch

# 6. Screenshot for your deck
scripts/demo/demo-control-center-ios.sh screenshot /tmp/astronomy-shop.png
```

Source: [demo-control-center-ios.sh](../scripts/demo/demo-control-center-ios.sh).

### Auto-drive the full user journey (for validation + dual-platform demo)

Launch with the `DASH0_AUTO_DEMO=1` env var set and the app loops through the full journey on its own — browse products → add to cart → checkout → repeat — producing deterministic telemetry every ~4 seconds:

```bash
# simctl has NO --env flag. Use the SIMCTL_CHILD_ prefix (simctl strips the
# prefix and forwards the rest as env to the launched process).
SIMCTL_CHILD_DASH0_AUTO_DEMO=1 xcrun simctl launch booted com.dash0.mobile.demo.AstronomyShop
```

This is the exact path exercised by [validate-ios-end-to-end.sh](../scripts/test/validate-ios-end-to-end.sh) and [run-dual-platform-demo.sh](../scripts/demo/run-dual-platform-demo.sh).

### Narration flow — what to do vs what to show in Dash0

| Step | UI action | Telemetry that appears |
| --- | --- | --- |
| 1 | Open app | `app.home_appeared` log. Plus a 4-span `shop.load_catalog` tree (read_bundle / decode / enrich). |
| 2 | Tap a product | 3-span `shop.view_product` tree (load_reviews, load_recommendations) + `shop.view_product.load_ms` histogram sample. |
| 3 | Tap **Add to Cart** | `cart.add_item` INFO log, `shop.cart.items_added` counter. Adding a large quantity (≥5) also emits a `cart.large_quantity_warning` WARN log. |
| 4 | Navigate to Cart, tap **Remove** | `cart.remove_item` INFO log. |
| 5 | Tap **Checkout** | 14-span 3-level deep `checkout` trace (validate_cart → inventory_check × N → calculate_totals × 3 → charge × 2 → send_confirmation × 2 → analytics.report) + `shop.checkout.duration_ms` histogram sample. |
| 6 | Background the app (⌘+H) | `app.background` log; `LifecycleInstrumentation` also emits a session-end signal. |

### What's shipped vs TODO on iOS

Auto-installed and live (the `.default` `AutoCaptureOptions` set): `NetworkInstrumentation` (URLProtocol swizzle), `LifecycleInstrumentation`, `ErrorsInstrumentation` (uncaught NSException + signal + crash marker), `ScreenInstrumentation` (SwiftUI `.trackScreen(_:)` ViewModifier), `FreezeInstrumentation` (main-thread watchdog), `VitalsInstrumentation` + `AppStartInstrumentation` (app.start, ui.jank, memory), and the `DeviceStatsCollector` gauge loop.

Opt-in (shipped, **off by default**): `ScreenshotInstrumentation` / `WireframeInstrumentation` — enable by adding `.screenshot` / `.wireframe` to `autoCaptureOptions` and supplying a `shouldCapture` consent gate (captures are redacted by default via `Dash0.redact(_:)` / `.dash0Redacted()`). See [IOS_CONFIGURATION.md](IOS_CONFIGURATION.md#capture-consent--redaction).

Still TODO: tap / scroll / text-input modules (placeholder flags in `AutoCaptureOptions`).

## Part 2 — Crash and recovery demo (optional)

Proves that the SDK survives real process death. The AstronomyShop doesn't currently have a dedicated "crash now" button — trigger a crash from the simulator menu (**Device → Simulate Memory Warning** won't crash; see below for a scripted approach) or build a fork with a test button wired to `fatalError(_:)`.

The mechanism: POSIX signal + `NSException` handlers write a marker file mid-crash then re-raise so the OS still records the crash. On the next launch `ErrorsInstrumentation` reads the marker and emits an `app.crash` log with severity `fatal` + `crash.kind`, `crash.name` (and `exception.stacktrace` on the NSException path — signal-path crashes carry no stack; see [IOS_CRASH_REPORTING.md](IOS_CRASH_REPORTING.md)). After emitting, the marker is deleted so a second relaunch is quiet.

Scripted crash (uses `kill -9` on the launched process after ~3 s):

```bash
# 1. Launch normally.
xcrun simctl launch booted com.dash0.mobile.demo.AstronomyShop

# 2. Grab the PID and kill it harshly — triggers the signal handler.
xcrun simctl spawn booted launchctl list \
    | awk '/com.dash0.mobile.demo.AstronomyShop/ {print $1}' \
    | xargs -I {} xcrun simctl spawn booted kill -11 {}

# 3. Relaunch — the `app.crash` log appears in Dash0 within seconds.
xcrun simctl launch booted com.dash0.mobile.demo.AstronomyShop
```

See [ErrorsInstrumentation.swift](../otel-ios-mobile/Sources/ErrorsInstrumentation/ErrorsInstrumentation.swift) for the mechanics.

## Part 3 — What to show in Dash0

Filter the firehose with:

```
os.name = "iOS"
service.name = "otel-ios-astronomy-shop"
```

Point these out, in order:

1. **Resource consistency.** Every signal shares the resource from [ResourceBuilder.swift](../otel-ios-mobile/Sources/OTelMobileSDK/Resource/ResourceBuilder.swift): `service.name`, `service.version`, `telemetry.sdk.name=io.dash0.mobile`, `telemetry.sdk.language=swift`, `os.name`, `os.version`, `device.manufacturer`, `device.model.name`, `device.model.identifier`, `device.id`. Show that you can filter by `device.model.identifier="iPhone17,3"` or slice traces by `os.version`.
2. **Multi-severity logs.** Filter by `otel.log.severity.text = WARN` and point at `cart.large_quantity_warning` — the app's natural signal for "something worth flagging." No debug flag, no toggle: just an app event.
3. **Deep trace structure.** Open a `checkout` span and expand the 3-level tree. That same tree is produced by user-driven checkout and the auto-demo loop — see [ShopTelemetry.swift](../examples/upstream-demo-app-ios/AstronomyShop/Shop/ShopTelemetry.swift).
4. **HTTP semconv.** For any `POST`/`GET <URL>` span, point out `http.request.method`, `url.full`, `http.response.status_code`, `server.address`. These are standard OTel semantic conventions — not Dash0-specific.
5. **Metrics.** Filter to `shop.cart.items_added` (sum), `shop.checkout.duration_ms` (histogram), `shop.view_product.load_ms` (histogram). The checkout histogram is p50/p95/p99-queryable.
6. **Crash recovery.** If you ran Part 2, show the `app.crash` log filtered to severity `fatal` with `crash.from_marker=true`. Expand the `exception.stacktrace` attribute. Note that Dash0 received this on the launch *after* the crash — no crash loop required.

## Talking points

Mirror what you'd say for Android, adapted for iOS specifics:

- **OTel-native.** The iOS SDK is a thin wrapper over `opentelemetry-swift` and `opentelemetry-swift-core`. No proprietary agent, no custom protocol — standard OTLP/HTTP to any collector or backend. Swap Dash0 for Jaeger in two lines.
- **One call to boot.** `OTelMobile.start(config:)` assembles all three signals (logs, traces, metrics) against a single `Resource` and auto-installs `NetworkInstrumentation`, `LifecycleInstrumentation`, and `ErrorsInstrumentation`. No `AppDelegate` edits, no Info.plist entries.
- **Crash survival.** POSIX signal + `NSException` handlers write a marker file mid-crash, then re-raise so the OS crash reporter still fires. Next launch reads the marker, emits the `app.crash` log, deletes the marker. Works without PLCrashReporter or KSCrash — though you can layer those on top for symbolication.
- **Privacy by default.** `PrivacyConfig.default` scrubs PII, buckets tap coordinates, and skips location capture. `NetworkConfig.default` strips query strings, refuses to capture `Authorization`/`Cookie` even if you ask it to, and captures only `Content-Type` by default. The OTLP endpoint host is auto-added to the network denylist so the SDK doesn't instrument its own exports.
- **Cross-platform parity.** The DSL v2 models on iOS ([DSLv2Models.swift](../otel-ios-mobile/Sources/OTelMobileSDK/Policy/DSLv2Models.swift)) are a direct port of Android's. Workflows authored in the control plane UI compile once and evaluate the same way on both platforms.
- **SwiftUI-safe auto-install.** Auto-instrumentation defers to the main queue's next tick after `start(config:)` returns, so `URLSessionConfiguration` swizzles + signal handlers don't race with SwiftUI scene setup.
- **Secure transport, on by default.** HTTPS is enforced — a cleartext endpoint is rejected, not silently shipped (`allowInsecureTransport` defaults `false`). Optional public-key pinning and HMAC-signed remote config are available.
- **Remote kill switch out of the box.** `enablePolicyPolling` defaults ON, so an operator can flip `sdk.enabled` / `sample_rate` from the control plane and the device honors it within a poll cycle. The `sdk.enabled` / `sdk.sample_rate` gauges stream even when a device is disabled, so kill-switch state is always observable.
- **Consent-gated visual capture.** Screenshots and wireframes are opt-in behind a `shouldCapture` consent gate and default-on redaction — privacy is the default posture, not an afterthought.

## Related Documentation

- [iOS SDK Integration Guide](IOS_SDK_GUIDE.md)
- [iOS Configuration Reference](IOS_CONFIGURATION.md)
- [Astronomy Shop demo README](../examples/upstream-demo-app-ios/README.md)
- [Android Demo Runbook](../HOW_TO_DEMO.md) — Sibling runbook for Android.
