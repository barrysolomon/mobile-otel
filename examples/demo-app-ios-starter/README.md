# Dash0 iOS Demo Starter

Minimal SwiftUI iOS app demonstrating the Dash0 Mobile Observability iOS SDK.

## Setup

1. Copy the config template and fill in your Dash0 credentials:
   ```
   cp StarterApp/otel-config.json.template StarterApp/otel-config.json
   ```
   Edit `StarterApp/otel-config.json` with:
   - `endpoint`: your Dash0 collector URL (e.g. `https://ingress.YOUR-DOMAIN.dash0.com:4318`)
   - `auth_token`: your Dash0 API token
   - `dataset`: your Dash0 dataset name (e.g. `otel-mobile`) — sent as the `Dash0-Dataset` header

2. Generate the Xcode project:
   ```
   xcodegen generate
   ```

3. Open `StarterApp.xcodeproj` in Xcode, or build from CLI:
   ```
   DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
     xcodebuild -scheme StarterApp \
     -destination "platform=iOS Simulator,name=iPhone 17" build
   ```

4. Run on the simulator:
   ```
   xcrun simctl boot "iPhone 17" 2>/dev/null || true
   open -a Simulator
   xcrun simctl install booted ~/Library/Developer/Xcode/DerivedData/StarterApp-*/Build/Products/Debug-iphonesimulator/StarterApp.app
   xcrun simctl launch booted com.dash0.mobile.demo.StarterApp
   ```

## What it does

On launch the app auto-emits 5 events (`app.start`, `user.tap`, `user.scroll`, `api.call`, `session.end`) then calls `forceFlush()`. After ~3 seconds, check your Dash0 dashboard — you should see 5 log records in the configured dataset.

The two buttons let you emit additional events manually and force-flush the buffer.

## Troubleshooting

- Status says "No otel-config.json found": the app didn't find `otel-config.json`. Copy the template and fill in credentials.
- Status says "placeholder values": you didn't replace `YOUR_` / `YOUR-` placeholders.
- Events emitted but nothing in Dash0: check the simulator's console logs via `xcrun simctl spawn booted log stream --predicate 'process == "StarterApp"'` for network errors.
