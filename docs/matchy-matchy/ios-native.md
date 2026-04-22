# Matchy-matchy — iOS native (AstronomyShop)

**Service name:** `otel-ios-astronomy-shop`
**Last validated:** 2026-04-21 (branch `iPhone`)
**Status:** 🟢 all four gates

Canonical reference runbook — this session is what every other
platform must match. Three SDK fix commits landed here:
[`d1eb755`](https://github.com/…) (Gate 1),
[`25d47b6`](https://github.com/…) (Gate 2),
[`1a69c7e`](https://github.com/…) (Gate 4).

See [`../epics/VALIDATION_MATRIX_EPIC.md`](../epics/VALIDATION_MATRIX_EPIC.md)
section "SDK bugs found and fixed" for the full bug → fix table.

---

## 0. Pre-flight

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer

SIM=$(xcrun simctl list devices available | grep "iPhone 17 " | head -1 | grep -oE '[A-F0-9-]{36}')
xcrun simctl boot "$SIM" 2>/dev/null || true

dash0 config profiles activate mobile-test

# Baseline — should be 0
dash0 -X logs query \
  --filter "service.name is otel-ios-astronomy-shop" \
  --from now-2m -o json | python3 -c 'import json,sys; print(len(json.load(sys.stdin).get("items", [])))'
```

### Demo app configuration (pre-wired)

- [`ShopBootstrap.swift:60`](../../examples/upstream-demo-app-ios/AstronomyShop/ShopBootstrap.swift#L60)
  sets `samplingConfig: .alwaysOn()` — default dynamic sampler drops
  90% of low-volume spans which hides Gate 2.
- [`AstronomyShopApp.swift:33`](../../examples/upstream-demo-app-ios/AstronomyShop/AstronomyShopApp.swift#L33)
  fires `RootState.pokeBackend()` (`GET https://httpbin.org/get`)
  from home-view `onAppear` — Gate 2 trigger.
- [`AstronomyShopApp.swift:84-90`](../../examples/upstream-demo-app-ios/AstronomyShop/AstronomyShopApp.swift#L84-L90)
  reads `-DASH0_CRASH_NOW` launch arg and schedules a SIGTRAP via
  array OOB — Gate 3 trigger.

### Build

```bash
cd examples/upstream-demo-app-ios
xcodegen generate   # first time / after adding files

xcodebuild -scheme AstronomyShop \
  -destination "platform=iOS Simulator,id=$SIM" \
  -derivedDataPath ./build build
```

---

## 1. Gate 1 — Lifecycle 🟢

**Trigger:**

```bash
APP_PATH=./build/Build/Products/Debug-iphonesimulator/AstronomyShop.app
BUNDLE_ID=com.dash0.mobile.demo.AstronomyShop

xcrun simctl install "$SIM" "$APP_PATH"
xcrun simctl launch "$SIM" "$BUNDLE_ID"

# Two bg↔fg cycles via a secondary app launch
for i in 1 2; do
  sleep 2
  xcrun simctl launch "$SIM" com.apple.Preferences  # → backgrounds AstronomyShop
  sleep 2
  xcrun simctl launch "$SIM" "$BUNDLE_ID"           # → foregrounds AstronomyShop
done
```

**Expected:** `app.launch` (1) + `app.foreground` (3) + `app.background` (2)
+ `app.start` + `app.startup` span, all scope `io.dash0.mobile`.

**Query:**

```bash
dash0 -X logs query \
  --filter "service.name is otel-ios-astronomy-shop and event.name is app.foreground" \
  --from now-5m
```

**Evidence:** `app.launch` + 2×`app.foreground` + 2×`app.background` +
`app.start` + `app.startup` span + `ui.jank` logs, all scope
`io.dash0.mobile`. Interleave order matches expected sequence.

### Why this gate was once red

Two separate bugs in
`otel-ios-mobile/Sources/LifecycleInstrumentation/LifecycleInstrumentation.swift`,
both fixed in commit `d1eb755`:

1. **NSLock reentrancy deadlock**: `install()` took the lock, called
   `emit()` which re-acquires it → main queue hangs → observer
   registration never runs → every iOS app silently lost lifecycle
   telemetry.
2. **UIScene blindness**: observed only `UIApplication.*Notification`
   — modern SwiftUI / scene-based apps only post
   `UIScene.*Notification`, so even without the deadlock, lifecycle
   would have been silent on every post-iOS-13 app with
   `UIApplicationSceneManifest`.

### Known limit

`simctl launch <other-bundle-id>` does not reliably background the
current app in all cases; iOS 26 sometimes keeps the first app
foregrounded across a second launch. A more reliable mechanism
(Simulator.app Home-button menu via AppleScript, or UI automation)
is on the post-session follow-up list.

---

## 2. Gate 2 — Network 🟢

**Trigger:** home view `onAppear` → `RootState.pokeBackend()` →
`URLSession.shared.dataTask(with: URL(string: "https://httpbin.org/get")!)`.

**Expected:** 1 span with

```
name:                      GET
kind:                      3 (CLIENT)
http.request.method:       GET
http.response.status_code: 200
server.address:            httpbin.org
url.full:                  https://httpbin.org/get
scope.name:                io.dash0.mobile
```

**Query:**

```bash
dash0 -X spans query \
  --filter "service.name is otel-ios-astronomy-shop and http.request.method is GET" \
  --from now-5m
```

**Evidence:** 1 span, all attributes present, status 200, duration
~200ms. Scope `io.dash0.mobile`.

### Why this gate was once red

`NetworkInstrumentation.install` ran inside `DispatchQueue.main.async`
→ any `URLSession` request fired synchronously during `App.init` /
`.onAppear` completed before the `URLProtocol` swizzle registered →
first-tick HTTP was silently uncaptured. Fixed by hoisting install
out of the main-queue defer. Commit `25d47b6`
(`otel-ios-mobile/Sources/OTelMobileSDK/OTelMobile.swift`).

---

## 3. Gate 3 — Crash 🟢

**Trigger:**

```bash
xcrun simctl terminate "$SIM" "$BUNDLE_ID"
# Launch with crash arg — SIGTRAP via arr[42] on a 4-element array
xcrun simctl launch "$SIM" "$BUNDLE_ID" -DASH0_CRASH_NOW
# Confirm dead
xcrun simctl spawn "$SIM" launchctl list | grep -i AstronomyShop
# Relaunch clean (no crash arg) — CrashInstrumentation drains the marker on startup
xcrun simctl launch "$SIM" "$BUNDLE_ID"
```

**Expected:** 1 `app.crash` FATAL log with

```
severityNumber:      21 (FATAL)
crash.kind:          signal
crash.name:          SIGTRAP
crash.signal:        5
crash.from_marker:   true
```

Timestamp is from the **recovery launch**, not the crash itself —
the marker carries the crash time forward because the process
couldn't drain OTLP during the fault.

**Query:**

```bash
dash0 -X logs query \
  --filter "service.name is otel-ios-astronomy-shop and event.name is app.crash" \
  --from now-5m
```

**Evidence:** 1 result with all expected attributes.
`crash.from_marker=true` proves the marker-based re-emission path
(vs. an in-process log that managed to squeak out before the fault).

---

## 4. Gate 4 — Offline 🟢

**Procedure:**

```bash
# 1. Swap otel-config to unreachable endpoint
cp AstronomyShop/otel-config.json /tmp/otel-config.json.online
jq '.endpoint = "https://invalid.dash0.invalid:4318"' \
  /tmp/otel-config.json.online > AstronomyShop/otel-config.json

# 2. Rebuild + install + launch (see pre-flight)
# 3. Walk around the app for ~30s (generates ~15-20 events)
# 4. Home-button out (auto-forceFlush fires via backgrounding observer)
xcrun simctl launch "$SIM" com.apple.Preferences

# 5. Confirm events persisted to disk
APP_DATA=$(xcrun simctl get_app_container "$SIM" "$BUNDLE_ID" data)
sqlite3 "$APP_DATA/Library/Application Support/io.dash0.mobile/buffer.db" \
  "SELECT COUNT(*) FROM buffered_events"
# → expect N > 0

# 6. Swap back, rebuild, terminate-install (NOT uninstall), relaunch
cp /tmp/otel-config.json.online AstronomyShop/otel-config.json
# ... rebuild ...
xcrun simctl terminate "$SIM" "$BUNDLE_ID"
xcrun simctl install "$SIM" "$APP_PATH"
xcrun simctl launch "$SIM" "$BUNDLE_ID"

# 7. Query for the recovery marker
dash0 -X logs query \
  --filter "service.name is otel-ios-astronomy-shop and event.name is app.recovery_start" \
  --from now-5m
```

**Expected:** 1 `app.recovery_start` log with

```
dash0.recovery.event_count:  N
dash0.recovery.bytes_pending: M
```

Followed by all N original events (with timestamps from the offline
window, not the drain time).

**Evidence:** 19 events persisted during unreachable-endpoint
window. `app.recovery_start` landed on reconnect with
`dash0.recovery.event_count=19` + `dash0.recovery.bytes_pending=17978`.
Post-recovery disk row count = 0 (drained, no loss, no dupes by
`sequenceId`).

### Why this gate was once red — three separate bugs

All fixed in commit `1a69c7e`:

1. **Upstream `OtlpHttpLogExporter.export(...)` is fire-and-forget**
   — kicks off async `URLSession`, returns `.success` immediately
   regardless of network outcome. `RetryableExporter` never
   retried because every offline export looked like success. Worked
   around via new `SynchronousLogRecordExporter` with blocking
   `HTTPClient` wrapper (`DispatchSemaphore` + real `HTTPURLResponse`
   capture). Local patch; upstream PR is on the post-session list.
2. **`MobileLogRecordProcessor.forceFlush*` dropped on failure** —
   both paths drained RAM via `buffer.flush()` and dropped events
   when export failed. Fixed by adding fail-persist-to-disk in both
   paths, deduped by `sequenceId`.
3. **No auto-`forceFlush` on backgrounding** — customer apps that
   went offline, backgrounded, and terminated before reconnecting
   lost their RAM-resident telemetry. Fixed by registering
   observers on `UIApplication.didEnterBackgroundNotification` +
   `UIScene.didEnterBackgroundNotification` +
   `UIApplication.willTerminateNotification` that call
   `instance.forceFlush()` automatically.

### iOS Simulator offline-test gotchas

- **No simulator-scoped airplane mode.** iOS Simulator shares the
  host's network stack; the Settings app's airplane-mode toggle is
  purely visual. `*.invalid:4318` config swap is the right primitive.
- **`simctl uninstall` wipes the Data container.** Use
  `terminate + install` (no uninstall) for step 6 to preserve the
  disk buffer across the "restart" simulation.

---

## 5. Validation techniques worth reusing

| Technique | When to use |
|---|---|
| `dash0 -X … --from now-5m -o json \| python3` | Default for every gate check — relative windows beat absolute timestamps |
| `samplingConfig: .alwaysOn()` in demo bootstrap | Low-volume demos otherwise lose 90% of spans to dynamic sampling defaults |
| `DispatchSemaphore`-wrapped `URLSession.send` | Turn fire-and-forget upstream into synchronous — how the offline-drain exporter works |
| `sqlite3 <app-container>/…/buffer.db "SELECT COUNT(*) FROM buffered_events"` | Offline-buffer validation without rerunning the app |
| `xcrun simctl spawn booted log stream --predicate 'eventMessage CONTAINS "DASH0-DEBUG"'` + temporary `NSLog` | When a gate fails silently, NSLog breadcrumbs beat Xcode-attached debugging |
| `log show` filters by **process CMDLINE**, not message body — use `log stream` for message-body grepping | Common footgun |

---

## 6. Session journal

- **2026-04-21** — All four gates 🟢. Three SDK fix commits
  (`d1eb755` / `25d47b6` / `1a69c7e`) + epic docs commit
  (`22c818b`). This runbook is extracted from that session's matrix
  epic entry and the session memory
  `project_session_2026_04_21b.md`.
