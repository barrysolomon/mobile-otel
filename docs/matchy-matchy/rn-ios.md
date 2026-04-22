# Matchy-matchy — RN iOS (AstronomyShopRN)

**Service name:** `otel-rn-astronomy-shop`
**Last validated:** 2026-04-22 (branch `iPhone`, HEAD at that time: `2954774`)
**Status:** 🟢 Gate 2 · 🟢 Gate 3 · 🔴 Gate 1 · 🔴 Gate 4

RN iOS is the hardest platform to pass the four-gate bar because its
architecture inverts several iOS-native assumptions: native
auto-capture defaults to OFF, lifecycle emission is disabled in the
demo app, and primary telemetry flows as **spans** (not logs), so the
offline-drain fixes from `1a69c7e` don't cover it. Gate 3 needed two
follow-up commits beyond the initial design — see Gate 3 for the
v1→v2→v3 correction chain.

See [`../epics/VALIDATION_MATRIX_EPIC.md`](../epics/VALIDATION_MATRIX_EPIC.md)
for the shared gate definitions.

---

## 0. Pre-flight

```bash
# Xcode
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer

# iPhone 17 simulator (UDID will differ locally — grab yours with `xcrun simctl list devices`)
SIM=65F2CFA7-B5AA-4C37-8A39-A9235CA4FBFE
xcrun simctl boot "$SIM" 2>/dev/null || true

# Dash0
dash0 config profiles activate mobile-test

# Baseline query — should return 0 before any gate runs
dash0 -X logs query \
  --filter "service.name is otel-rn-astronomy-shop" \
  --from now-2m -o json | python3 -c 'import json,sys; print(len(json.load(sys.stdin).get("items", [])))'
```

### Gotchas to skip past

- **Host Node is 18** (no `Array.prototype.toReversed`) → use
  `/opt/homebrew/bin/node` (v25) for Metro / bundling. The
  `AstronomyShopRN/ios/.xcode.env.local` file pins `NODE_BINARY`
  accordingly (commit `5eb0965`).
- **OrbStack holds port 8081** → either run Metro on `--port 8082`
  or (simpler for gate validation) do a Release build with the bundle
  embedded via `react-native bundle` and skip Metro entirely.
- **Native `autoCaptureOptions` defaults to `.none`** — see
  `ios/AstronomyShopRN/OTelMobileCallSink.swift:39-46`. This is
  intentional: RN's JS event loop collides with the iOS
  URLProtocol swizzle and NSException/signal handlers. Signals come
  from JS-side shims instead.
- **`simctl uninstall` wipes the disk buffer.** For Gate 4's
  swap-back step, use `terminate + install` (no uninstall) to
  preserve state.
- **Dash0 queries: use `--from now-5m`** relative windows, not
  absolute timestamps. Dataset indexing latency makes absolute
  timestamp filters flaky.

### Build commands used

```bash
cd examples/upstream-demo-app-rn/AstronomyShopRN

# JS bundle (Release)
/opt/homebrew/bin/node node_modules/.bin/react-native bundle \
  --platform ios --dev false \
  --entry-file index.js \
  --bundle-output ios/main.jsbundle \
  --assets-dest ios

# Pods (first time / after Podfile changes)
cd ios && pod install && cd ..

# Build + install
xcodebuild -workspace ios/AstronomyShopRN.xcworkspace \
  -scheme AstronomyShopRN -configuration Release \
  -destination "platform=iOS Simulator,id=$SIM" \
  -derivedDataPath ios/build build

xcrun simctl install "$SIM" \
  ios/build/Build/Products/Release-iphonesimulator/AstronomyShopRN.app
xcrun simctl launch "$SIM" com.dash0.mobile.demo.AstronomyShopRN
```

---

## 1. Gate 1 — Lifecycle 🔴

**Architectural gap, not a bug.**

**Trigger:** Launch clean + two background↔foreground cycles (via
`xcrun simctl` home-button sim or Simulator.app menu).

**Expected:** `app.launch` + 3×`app.foreground` + 2×`app.background`.

**Actual:** 0 results.

**Why:**
- `AstronomyShopRN/src/App.tsx:38` disables `autoCapture.lifecycle`
  with a comment about RN 0.85 new-arch + AppState init-order race.
- `ios/AstronomyShopRN/OTelMobileCallSink.swift:39-46` defaults native
  `autoCaptureOptions` to `.none` because the iOS URLProtocol
  swizzle and NSException/signal handlers collide with RN's JS
  event loop.

Result: neither the JS-side `installAppStateInstrumentation` nor the
native iOS `LifecycleInstrumentation` emits lifecycle logs.

**Query:**

```bash
dash0 -X logs query \
  --filter "service.name is otel-rn-astronomy-shop and event.name is app.foreground" \
  --from now-5m
```

Expected: ≥3. Actual: 0.

**Remediation (tracked in epic follow-ups):** either fix RN's
AppState init race so the JS shim is reliable, or add an opt-in flag
for native `lifecycle` capability that is known-safe with the RN
new-arch event loop.

---

## 2. Gate 2 — Network 🟢

**Trigger:** `AstronomyShopRN/src/screens/ProductListScreen.tsx`
fires a 2-second-delayed `fetch('https://httpbin.org/get')` from
`useEffect`. The delay is required because `Dash0Mobile.start()`
installs the fetch shim inside an async `useEffect`, so a synchronous
fetch on the same mount would race the installer.

**Expected:** ≥1 span with:

```
name:                  GET
kind:                  3 (CLIENT)
http.request.method:   GET
http.response.status_code: 200
server.address:        httpbin.org
url.full:              https://httpbin.org/get
scope.name:            io.dash0.mobile
```

**Query:**

```bash
dash0 -X spans query \
  --filter "service.name is otel-rn-astronomy-shop" \
  --from now-30m
```

**Actual (post-`ba558c2`):** 1 span, named `GET httpbin.org` from
the XHR shim.

**Why XHR and not fetch:** RN's `fetch` is implemented on top of XHR.
On 2026-04-22's first run, 2 spans landed per request (fetch +
XHR, both shims intercepting the same request). Fixed by detecting
`navigator.product === 'ReactNative'` in `index.ts`'s install path
and skipping the fetch shim on RN only. XHR is authoritative on RN
because it also catches direct XHR callers (axios, Apollo) that
would bypass the fetch wrapper. On non-RN JS environments both
shims still install (fetch is native and independent of XHR there).
Commit: `ba558c2`.

**Filter-DSL gotcha:**
`http.request.method is GET` returns 0 results even though the
attribute is present on the span. Use name-based or span-level
filters (`service.name is X and name is GET`) until we understand
why attribute-based filtering doesn't index RN-origin spans.
Tracked in epic follow-ups.

---

## 3. Gate 3 — Crash 🟢

**Trigger:** tap the red **"Trigger Crash (Gate 3)"** button added to
`ProductListScreen`. `onPress` fires

```js
setTimeout(() => { throw new Error('Dash0 RN iOS Gate 3 test crash'); }, 0);
```

so the throw escapes React's commit phase and reaches RN's
`ErrorUtils` global handler, which `instrumentation/errors.ts` has
chained into.

**Flow post-fix:**

1. `errors.ts` emits `Dash0Mobile.log('app.error', attrs, 21)`.
2. `Dash0Mobile.log` sees severity ≥ 21 and routes through
   `bridge.emitSync(payload)` — **not** the debounced `bridge.emit`.
   `emitSync` calls `native.emitBatch([...queue, payload]).catch(...)`
   with no `await` anywhere; per the RN bridge contract, argument
   marshaling is synchronous, so the payload crosses the bridge on
   the current stack frame before the handler returns. Commit
   `4399e7a`.
3. Native `OTelMobileCallSink.emitLog` receives the payload via the
   dispatcher, builds the log record, then — on severity ≥ 21 —
   calls `instance.forceFlush()` synchronously. `forceFlush` drains
   the RAM buffer through `SynchronousLogRecordExporter`, and on
   export failure persists to disk via the fail-persist path from
   commit `1a69c7e`. Commit `0eed784`.
4. Log lands in Dash0 with the original timestamp and full OTel
   `exception.*` semconv.

**Query:**

```bash
dash0 -X logs query \
  --filter "service.name is otel-rn-astronomy-shop" \
  --from now-5m
```

**Actual (2026-04-22):**

```
body:                  app.error
severityNumber:        21 (FATAL)
scope.name:            io.dash0.mobile
timestamp:             1776873856 (= crash tap to the second)
exception.type:        Error
exception.message:     Dash0 RN iOS Gate 3 test crash
exception.stacktrace:  Error: Dash0 RN iOS Gate 3 test crash
                       at anonymous (...RN JS bundle...)
```

### Why two separate fixes were needed

- **Window A (JS queue → native): closed by `emitSync`.** Initial
  attempt using `void bridge.flush()` failed because `bridge.flush`
  is async and `await this.drain()` placed `native.emitBatch` on the
  microtask queue; the JS handler's synchronous continuation into
  RN's fatal reporter won the race. `emitSync` avoids any
  microtask boundary.
- **Windows B + C (native RAM → OTLP / disk): closed by eager
  `forceFlush` in `OTelMobileCallSink`.** Initial attempt relied on
  the `willTerminate` auto-forceFlush from commit `1a69c7e`, but
  that notification never fires on JS-throw-induced RN termination
  — RN's fatal reporter calls `abort()`/`exit()` and skips
  `UIApplication` lifecycle teardown. `forceFlush` on the hot path
  is the reliable alternative.

### Cross-platform naming drift

`errors.ts` emits `app.error`, **not** `app.crash` like iOS
native's `CrashInstrumentation`. A future cross-platform Gate 3
filter needs both event names OR an alias. Not a blocker — the RN
JS path is inherently distinct from the native signal-handler
path, and both names are meaningful.

---

## 4. Gate 4 — Offline 🔴

**Procedure:**

```bash
# 1. Swap otel-config to unreachable endpoint
cp otel-config.json otel-config.json.online
jq '.endpoint = "https://invalid.dash0.invalid:4318"' otel-config.json.online > otel-config.json

# 2. Rebuild JS bundle + Release .app (see pre-flight)

# 3. Terminate + install (NOT uninstall — preserves disk buffer)
xcrun simctl terminate "$SIM" com.dash0.mobile.demo.AstronomyShopRN
xcrun simctl install "$SIM" <path-to-.app>
xcrun simctl launch "$SIM" com.dash0.mobile.demo.AstronomyShopRN

# 4. Drive UI for ~35s — scroll, tap, trigger fetch
# 5. Terminate
xcrun simctl terminate "$SIM" com.dash0.mobile.demo.AstronomyShopRN

# 6. Swap back
mv otel-config.json.online otel-config.json
# Rebuild + relaunch

# 7. Query
dash0 -X logs query \
  --filter "service.name is otel-rn-astronomy-shop and event.name is app.recovery_start" \
  --from now-5m
```

**Result during offline window:**

- App runs, fetch + tap span activity flows through bridge → native.
- SQLite `buffered_events` row count stays at **0** throughout and
  post-terminate:

```bash
# During offline window:
sqlite3 "$(xcrun simctl get_app_container "$SIM" com.dash0.mobile.demo.AstronomyShopRN data)/Library/Application Support/io.dash0.mobile/buffer.db" \
  "SELECT COUNT(*) FROM buffered_events"
# → 0
```

**Root cause:** commit `1a69c7e`'s fail-to-disk persist is wired to
`MobileLogRecordProcessor`. **Spans use `BatchSpanProcessor`** (see
`otel-ios-mobile/Sources/OTelMobileSDK/OTelMobile.swift:182` —
`"For traces, a single BatchSpanProcessor exports to OTLP"`) which
has no disk-persist on export failure.

RN iOS primary telemetry under `autoCaptureOptions: .none` is:

- HTTP spans (fetch/XHR shims) — spans, **lost**
- Tap / screen_view spans (`ShopTelemetry.emit*`) — spans, **lost**
- JS error logs (`errors.ts`) — logs, but dependent on Gate 3's
  hot path

Query result: 0. Expected: 1 marker with
`dash0.recovery.event_count ≥ 1`.

### Remediation sketch (tracked in epic follow-ups)

1. Apply the same RAM→disk fail-persist wrapper to the span
   pipeline (`BatchSpanProcessor` with a custom `SpanExporter`
   decorator that persists on `.failure`).
2. Land a `recoverFromDisk` hook at `OTelMobile.start()` that
   re-emits persisted spans with their original timestamps + emits
   a `span.recovery_start` marker (mirror the log marker).
3. Until (1) + (2) land, the RN iOS offline-drain story is
   "JS-side logs via FATAL emit only."

Spec and implementation plan exist at commits `d9ffd01`,
`9d6d340`, `2954774` (branch `iPhone`).

---

## 5. Known failures / architectural gaps

| Gate | Status | Root cause | Tracking |
|---|---|---|---|
| 1 | 🔴 | AppState JS shim disabled (RN 0.85 init race) + native `.none` default | `[App.tsx:38](../../examples/upstream-demo-app-rn/AstronomyShopRN/src/App.tsx#L38)`, `[OTelMobileCallSink.swift:39-46](../../examples/upstream-demo-app-rn/AstronomyShopRN/ios/AstronomyShopRN/OTelMobileCallSink.swift#L39-L46)` |
| 2 | 🟢 | Dedup fix in `ba558c2` | See Gate 2 above |
| 3 | 🟢 | Two-sided fix in `4399e7a` + `0eed784` | See Gate 3 above |
| 4 | 🔴 | `BatchSpanProcessor` has no disk-persist on failure | Spec at `d9ffd01`/`9d6d340`/`2954774` |

**What's green today despite the two reds:** the bridge contract
works end-to-end under normal online conditions. RN iOS can produce
the same spans shape as iOS native (same scope, same attributes,
proper waterfall parent/child), and the install sequence
(`AppDelegate` → `installSink` → `RCTDash0MobileModule` init → JS
start → native sink → `OTelMobile.start`) runs deterministically.
The four-gate reds are all *reliability under adversity* problems —
crash path, offline path, and lifecycle — that compound with RN's
architectural choices.

---

## 6. Session journal

- **2026-04-22** — Gates 2+3 shipped 🟢 (commits `ba558c2`,
  `4399e7a`, `0eed784`). Gates 1+4 reds with documented root
  causes. This runbook is extracted from that session's matrix epic
  entry.
- **2026-04-21** — iOS native 4/4 gates 🟢 (commits `d1eb755`,
  `25d47b6`, `1a69c7e`). The three iOS SDK fix commits auto-flow
  through to RN iOS via the local SwiftPM reference.
