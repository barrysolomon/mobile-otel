# iOS Demo Runbook

Runbook for showing the Dash0 Mobile Observability iOS SDK live. Covers the Starter happy path, the Astronomy Shop walkthrough, a crash + recovery demo, and what to point at in Dash0.

**Total time: ~10 minutes** for Parts 1-3, plus however long you spend narrating in Dash0.

## Prerequisites

Install once per machine:

- **Xcode 26+** (for the demo apps' iOS 18 / iOS 17 target runtimes). Command Line Tools alone is enough for `swift test` on the SDK package but not for the demo apps.
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
- **XcodeGen** for the demo project generators:
  ```bash
  brew install xcodegen
  ```
- **Dash0 credentials** — endpoint, auth token, and dataset name. Both demo apps read these from an `otel-config.json` file that is `.gitignored`. Copy the template and fill it in:
  ```bash
  cp examples/demo-app-ios-starter/StarterApp/otel-config.json.template \
     examples/demo-app-ios-starter/StarterApp/otel-config.json
  cp examples/upstream-demo-app-ios/AstronomyShop/otel-config.json.template \
     examples/upstream-demo-app-ios/AstronomyShop/otel-config.json
  # Edit each file to replace YOUR_COLLECTOR_ENDPOINT, YOUR_AUTH_TOKEN, YOUR_DATASET_NAME.
  ```

## Part 1 — Starter demo (happy path)

The Starter app is a minimal SwiftUI app that emits five events on launch and exposes buttons to emit additional events and to force-flush. It's the cleanest demo for showing the raw SDK pipeline without any application noise.

The one-liner runs the full happy path — boot simulator, generate Xcode project, build, install, launch, screenshot:

```bash
scripts/demo/demo-control-center-ios.sh full
```

If you prefer to narrate each step, the script also supports subcommands:

```bash
# 1. Boot the simulator (default: "iPhone 17"; override with IOS_SIM_NAME=...)
scripts/demo/demo-control-center-ios.sh boot

# 2. Generate the Xcode project (first time only, or after adding files)
(cd examples/demo-app-ios-starter && xcodegen generate)

# 3. Build for the simulator
scripts/demo/demo-control-center-ios.sh build

# 4. Install the built .app onto the booted simulator
scripts/demo/demo-control-center-ios.sh install

# 5. Launch the app
scripts/demo/demo-control-center-ios.sh launch

# 6. Screenshot for your deck
scripts/demo/demo-control-center-ios.sh screenshot /tmp/starter.png
```

Source: [demo-control-center-ios.sh](../scripts/demo/demo-control-center-ios.sh).

On launch the Starter auto-emits:

- `app.start` — custom log
- `user.tap` — custom log
- `user.scroll` — custom log
- `api.call` — custom log
- `session.end` — custom log

Then it calls `mobile.forceFlush()`. Within ~3 seconds those five log records should appear in your Dash0 dataset. The two buttons in the app let you emit additional events and re-trigger the flush — useful for showing the batch behavior live.

Tail the app's logs in a second terminal for narration:

```bash
scripts/demo/demo-control-center-ios.sh log
```

## Part 2 — Astronomy Shop demo

The Astronomy Shop is a SwiftUI port of the Android `upstream-demo-app/` telescopes-and-accessories store. Both apps share the same `products.json` catalog and product images so telemetry lands comparably in Dash0. See [upstream-demo-app-ios/README.md](../examples/upstream-demo-app-ios/README.md).

Build and launch (update `DEMO_ROOT` inside the control-center script, or build by hand):

```bash
cd examples/upstream-demo-app-ios
xcodegen generate
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild \
    -scheme AstronomyShop \
    -destination "platform=iOS Simulator,name=iPhone 17" \
    -derivedDataPath ./build build
xcrun simctl install booted ./build/Build/Products/Debug-iphonesimulator/AstronomyShop.app
xcrun simctl launch booted com.dash0.mobile.demo.AstronomyShop
```

Narration flow — what to do on the simulator vs what to show in Dash0:

| Step | UI action | Telemetry that appears |
| --- | --- | --- |
| 1 | Open app | `app.launch`, `app.foreground` logs from `LifecycleInstrumentation`. Plus a `page.HomeView` span if `ScreenInstrumentation` is wired (manual install). |
| 2 | Scroll the product list | Bundled catalog — no network. Spans appear only if the list fetches a remote catalog in your fork. |
| 3 | Tap a product | `app.home_appeared` log, screen transition to detail view. |
| 4 | Tap **Add to Cart** (stepper for quantity) | `cart.add_item` log with `cart.item_sku`, `cart.qty` attributes. |
| 5 | Navigate to Cart, tap **Remove** | `cart.remove_item` log. |
| 6 | Tap **Checkout** | `checkout` parent span with three child spans: `checkout.validate`, `checkout.charge`, `checkout.confirm`. |
| 7 | Background the app (⌘+H) | `app.background` log, the `app.foreground_session` span closes. |

What's bundled vs what's TODO on iOS today (from the port's parity table): product list, detail, cart add/remove/clear, and checkout are implemented. Recommendations, image loader spans, about page, and the crash popup button are still TODO — the `ErrorsInstrumentation.shared.recordError(_:)` path works, but the in-app button hasn't been wired.

## Part 3 — Crash and recovery demo

This is the "wow" moment — proves that the SDK survives real process death. The Starter app includes a **Crash Now** button wired to `fatalError()`; after the app dies you relaunch it and Dash0 shows the `app.crash` log from the *previous* run.

```bash
# 1. With the Starter running and a fresh session, tap "Crash Now" in the app.
#    Or: simulate from the command line.
xcrun simctl launch booted com.dash0.mobile.demo.StarterApp
# (interact with the UI — tap the Crash button)

# 2. Confirm the simulator shows the crash dialog and the app is no longer running.
xcrun simctl list | grep StarterApp

# 3. Relaunch. On install `ErrorsInstrumentation` reads the crash marker written
#    during the handler and emits an `app.crash` log with severity=fatal.
xcrun simctl launch booted com.dash0.mobile.demo.StarterApp
```

Narration cue: "Note the severity — `fatal` — and the `crash.kind`, `crash.name`, and `exception.stacktrace` attributes. The marker file is at `~/Library/Developer/CoreSimulator/Devices/<UDID>/data/Containers/Data/Application/<bundle-id>/Library/Caches/io.dash0.mobile.crash-marker` between crash and recovery; we delete it after emitting, so a second relaunch shows nothing new." See [ErrorsInstrumentation.swift](../otel-ios-mobile/Sources/ErrorsInstrumentation/ErrorsInstrumentation.swift) for the mechanics.

## Part 4 — What to show in Dash0

Filter the firehose with:

```
os.name = "iOS"
service.name = "otel-ios-astronomy-shop"
# or service.name = "dash0-ios-demo-starter" for Part 1
```

Point these out, in order:

1. **Resource consistency.** Every signal shares the resource from [ResourceBuilder.swift](../otel-ios-mobile/Sources/OTelMobileSDK/Resource/ResourceBuilder.swift): `service.name`, `service.version`, `telemetry.sdk.name=io.dash0.mobile`, `telemetry.sdk.language=swift`, `os.name`, `os.version`, `device.manufacturer`, `device.model.name`, `device.model.identifier`, `device.id`. Show that you can filter by `device.model.identifier="iPhone17,3"` or slice traces by `os.version`.
2. **Log vs trace separation.** Lifecycle and custom events are logs; network calls and checkout steps are spans. This mirrors Android — the same queries work across both SDKs.
3. **Span structure.** Drill into the `checkout` parent span and expand its children (`validate`, `charge`, `confirm`). If you enabled `ScreenInstrumentation` manually, show the `page.<ScreenName>` bracket spans.
4. **HTTP semconv.** For any `GET <URL>` span from the upstream demo, point out `http.request.method`, `url.full`, `http.response.status_code`, `server.address`. These are standard OTel semantic conventions — not Dash0-specific.
5. **Crash recovery.** Show the `app.crash` log filtered to severity `fatal` with `crash.from_marker=true`. Expand the `exception.stacktrace` attribute. Note that Dash0 received this on the launch *after* the crash — no crash loop required.
6. **Selective flush.** If you call `mobile.flushWindow(minutes: 5)` from the Starter's second button, watch the log count jump: that's the buffer draining the last 5 minutes of events in one batch rather than the steady 2 s dribble of the periodic exporters.

## Talking points

Mirror what you'd say for Android, adapted for iOS specifics:

- **OTel-native.** The iOS SDK is a thin wrapper over `opentelemetry-swift` and `opentelemetry-swift-core`. No proprietary agent, no custom protocol — standard OTLP/HTTP to any collector or backend. Swap Dash0 for Jaeger in two lines.
- **One call to boot.** `OTelMobile.start(config:)` assembles all three signals (logs, traces, metrics) against a single `Resource` and auto-installs `NetworkInstrumentation`, `LifecycleInstrumentation`, and `ErrorsInstrumentation`. No `AppDelegate` edits, no Info.plist entries.
- **Crash survival.** POSIX signal + `NSException` handlers write a marker file mid-crash, then re-raise so the OS crash reporter still fires. Next launch reads the marker, emits the `app.crash` log, deletes the marker. Works without PLCrashReporter or KSCrash — though you can layer those on top for symbolication.
- **Privacy by default.** `PrivacyConfig.default` scrubs PII, buckets tap coordinates, and skips location capture. `NetworkConfig.default` strips query strings, refuses to capture `Authorization`/`Cookie` even if you ask it to, and captures only `Content-Type` by default.
- **Cross-platform parity.** The DSL v2 models on iOS ([DSLv2Models.swift](../otel-ios-mobile/Sources/OTelMobileSDK/Policy/DSLv2Models.swift)) are a direct port of Android's. Workflows authored in the control plane UI compile once and evaluate the same way on both platforms.
- **SwiftUI-safe auto-install.** Auto-instrumentation defers to the main queue's next tick after `start(config:)` returns, so `URLSessionConfiguration` swizzles + signal handlers don't race with SwiftUI scene setup. This was the fix for the "blank launch screen" bug — see the comment in [OTelMobile.swift](../otel-ios-mobile/Sources/OTelMobileSDK/OTelMobile.swift).
- **What's still coming.** Be upfront: tap, scroll, text-input, freeze, vitals, screenshot, and wireframe are placeholder flags in `AutoCaptureOptions` today. `ScreenInstrumentation` ships working code but isn't auto-installed pending a safer SwiftUI integration. The disk tier of the buffer is parsed but not enforced. Everything else — including the crash recovery path — is shipping.

## Related Documentation

- [iOS SDK Integration Guide](IOS_SDK_GUIDE.md)
- [iOS Configuration Reference](IOS_CONFIGURATION.md)
- [Starter demo README](../examples/demo-app-ios-starter/README.md)
- [Astronomy Shop demo README](../examples/upstream-demo-app-ios/README.md)
- [Android Demo Runbook](../HOW_TO_DEMO.md) — Sibling runbook for Android.
