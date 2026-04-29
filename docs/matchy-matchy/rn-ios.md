# Matchy-matchy — RN iOS (AstronomyShopRN) 🟢 4/4

**Service name:** `otel-rn-astronomy-shop`
**Last validated:** 2026-04-29 (iPhone 17 Pro Simulator, all four gates 🟢
after Gate 1 closure via iOS late-init synthesis + bridge
`autoCaptureOptions: [.lifecycle]` default — commits `764b67b` + `4b91e12`).
Earlier 2026-04-22 (HEAD `2954774`) validated Gates 2/3/4.
**Status:** 🟢 Gate 1 · 🟢 Gate 2 · 🟢 Gate 3 · 🟢 Gate 4 (4/4 verified 2026-04-29)

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

## 1. Gate 1 — Lifecycle 🟢 verified 2026-04-29

**Closure:** the previous architectural gap is gone.

**What changed:**

1. **iOS SDK** got a late-init synthesis path (commit `764b67b`):
   `LifecycleInstrumentation.install()` now checks
   `UIApplication.shared.applicationState` after registering observers
   and synthesizes an initial `app.foreground` if the app is `.active`
   at install time. This is the iOS analog of Android's at-attach
   replay — necessary because NotificationCenter has no built-in
   replay (unlike `androidx.lifecycle.LifecycleRegistry.addObserver`).
   Synthesized events are tagged `app.foreground.type = "instrumentation_late"`;
   natural `didBecomeActive`/`didActivateNotification` callbacks tag
   `"natural"`.
2. **iOS RN bridge** defaults `autoCaptureOptions: [.lifecycle]` (commit
   `4b91e12`). NotificationCenter observers don't touch the JS event
   loop, so they're safe with RN's new-arch dispatch. URLProtocol
   swizzle, NSException/signal handlers, and screen swizzle still
   default to off (those genuinely conflict with RN).
3. **JS-side AppState shim deleted** (commit `00526ed`) along with
   the `autoCapture.lifecycle` flag and the demo's `App.tsx` opt-out.

Spec: [`docs/superpowers/specs/2026-04-29-gate1-rn-lifecycle-design.md`](../superpowers/specs/2026-04-29-gate1-rn-lifecycle-design.md).

**Verified 2026-04-29 (iPhone 17 Pro Simulator):**

```text
events: {'app.launch': 1, 'app.foreground': 3, 'app.background': 2}
app.foreground.type breakdown:
  - 1× instrumentation_late (cold-launch synthesis)
  - 2× natural (the bg/fg returns)
```

**Query:**

```bash
dash0 -X logs query \
  --filter "service.name is otel-rn-astronomy-shop and event.name is app.foreground" \
  --from now-5m
```

### 2026-04-24 unblock investigation (DID NOT WORK — kept for record)

Tried flipping `App.tsx:38` to remove `autoCapture: { lifecycle: false }` and
relying on the existing 100ms defer in
[`appstate.ts:71`](../../packages/react-native/src/instrumentation/appstate.ts#L71)
to dodge the TurboModule init race. **Still redboxed** on iPhone 17
Simulator iOS 26.4 + RN 0.85 + Hermes.

Symptom (captured in `/tmp/gate1-app.log`):

```text
React: { Invariant Violation: TurboModuleRegistry.getEnforcing(...): 'PlatformConstants'
  could not be found. Verify that a module by this name is registered in the native binary. }
React: Unhandled JS Exception: Invariant Violation: ...
CoreFoundation: *** Terminating app due to uncaught exception 'RCTFatalException' ...
  stack:
    invariant@68105:25
    getEnforcing@68576:27
    ...
    get AppState@67469:24
    resolveAppState@111842:<…>
```

Then bumped the defer from 100ms to 1500ms. Still redboxed — the
Invariant Violation fires BEFORE the deferred setTimeout callback
runs. Diagnosis: RN's `RCTFatal` catches the JS throw and converts
it to a native fatal exception that bypasses our try/catch around
`require('react-native')`. The defer is delaying *when* we touch
AppState; it isn't preventing whatever asynchronous tick triggers
the throw. This is upstream RN, not a defer-tuning problem.

Reverted both changes. `lifecycle: false` stays. The 100ms defer
is retained as defensive code in case a future RN version makes
the race narrower.

**Future-attempt ideas** (none tried yet, none proven):

1. Defer until first `requestAnimationFrame` callback, then
   `setTimeout(0)`. Empirically "after first paint" — should mean
   all TurboModules are wired.
2. Subscribe via a non-AppState mechanism — e.g. use the iOS
   `UIApplication.willResignActiveNotification` from the bridge's
   native side, surfacing as a synthetic JS-emit. Trade-off: native
   event riding through bridge → JS → bridge round-trip.
3. Wait for upstream RN to fix the new-arch init order. Filed as
   GitHub issue search starting point: `react-native turbomodule
   PlatformConstants getEnforcing useEffect`. Several open issues
   exist in RN repo.

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

## 4. Gate 4 — Offline 🟢

**Procedure:**

```bash
# 1. Swap otel-config to unreachable endpoint
cp otel-config.json /tmp/otel-config.real.json
python3 -c "import json; c=json.load(open('/tmp/otel-config.real.json')); c['endpoint']='https://ingress-offline-test.invalid:4318'; json.dump(c, open('otel-config.json','w'), indent=2)"

# 2. Rebuild JS bundle + Release .app (see pre-flight)

# 3. Install + launch
xcrun simctl install "$SIM" <path-to-.app>
xcrun simctl launch "$SIM" org.reactjs.native.example.AstronomyShopRN
open -a Simulator

# 4. Drive UI for ~30s — scroll, tap, trigger fetch
# 5. Terminate
xcrun simctl terminate "$SIM" org.reactjs.native.example.AstronomyShopRN

# 6. Inspect disk — expect N > 0 persisted trace requests
DATA=$(xcrun simctl get_app_container "$SIM" org.reactjs.native.example.AstronomyShopRN data)
sqlite3 "$DATA/Library/Application Support/io.dash0.mobile/span-buffer.db" \
  "SELECT COUNT(*) FROM buffered_span_requests"
# → N (validated 2026-04-23: N=7, 7595 bytes total)

# 7. Swap back, rebuild, relaunch
cp /tmp/otel-config.real.json otel-config.json
# Rebuild + install + launch

# 8. Query
dash0 -X logs query \
  --filter "service.name is otel-rn-astronomy-shop" \
  --filter 'otel.log.body is "app.recovery_start"' \
  --from now-10m -o json
# → 1 marker with dash0.recovery.span_count=N
```

**Result (2026-04-23 run, N=7):**

Offline window:

- App runs, all OTLP/HTTP trace POSTs fail with DNS NoSuchRecord
  for `ingress-offline-test.invalid`.
- `PersistingTraceHTTPClient` captures each failed request's
  body + current session id to `buffered_span_requests`.
  Endpoint + headers are NOT persisted — replays route to the
  user's CURRENT `MobileConfig` at recovery time.
- Post-terminate disk state: N rows.

Reconnect launch:

- `OTelMobile.start` emits `app.recovery_start` with
  `dash0.recovery.span_count=N`, `dash0.recovery.span_bytes_pending=…`.
- `OTelMobile.recoverSpanRequests(from:endpoint:headers:httpClient:)`
  replays each row via `BaseHTTPClient`, posting to the URL built
  from `config.endpoint` with headers built from `config.authToken` +
  `config.extraHeaders` (via `OTelMobile.buildReplayHeaders`).
  2xx → row deleted, 5xx/429/network → leave on disk + stop batch,
  4xx-non-429 → drop row. Latest device validation: 2 rows
  persisted offline, both 2xx on replay, disk drained to 0.
- Dash0 receives the original spans (the body bytes are
  byte-identical OTLP protobuf, so the collector can't tell a
  replayed payload from a live one).

### Architectural lesson (keep this, it's not obvious)

**The obvious-looking design is dead code.** A `SpanExporter`
decorator that watches for `.failure` from the OTLP exporter will
never trigger — upstream's `OtlpHttpTraceExporter.export()`
returns `.success` synchronously BEFORE the HTTP call completes.
Failures surface only via an internal
`httpClient.send { result in ... }` callback, invisible to a
SpanExporter-level decorator.

**Fix: intercept at the HTTPClient layer.** The upstream exporter's
init takes an `httpClient: HTTPClient` parameter; inject a custom
`PersistingTraceHTTPClient` that wraps `BaseHTTPClient` and
persists the raw `URLRequest.httpBody` on failure modes (network
error, 5xx, 429). Works because `HTTPClient.send` is async-by-
callback and the callback sees the real outcome.

**Schema:** persist bytes, not decoded spans. At the HTTPClient
layer, serialization has already happened — the body is
pre-gzipped protobuf. Store those bytes in
`buffered_span_requests(request_key, body, session_id, size_bytes,
created_at)` and replay by POSTing the original bytes. Byte-
identical collector input = perfect idempotency.

**Routing:** the persisted row does NOT carry the original
endpoint or headers. Recovery routes to the URL + headers built
from the user's CURRENT `MobileConfig`. This is the right
default — token rotation, region migration, dataset rename, and
typo fixes all do the right thing automatically because the
caller's intent on the recovery launch is what counts. (The
earlier design that captured endpoint/headers had to be reworked
on the same day it shipped — see session memory
`feedback_replay_routing.md`.)

**Replay behavior per status:** 2xx → delete row. 5xx or 429 →
leave on disk and stop the batch (don't hammer a dead collector).
4xx non-429 → drop row (client error won't succeed later; prevents
perma-bad rows from filling the disk). Network error → leave on
disk (same rationale as 5xx).

**When this lesson applies elsewhere:** the same SpanExporter-
decorator pitfall exists on Android if we ever try a decorator
there — but Android's `MobileLogRecordProcessor` already uses a
RAM→disk model that's structurally different. This trap is
iOS-SDK-specific because opentelemetry-swift's OTLP exporter
returns .success unconditionally; the Android equivalent
(`OtlpHttpSpanExporter` in opentelemetry-java) blocks on the HTTP
result and returns the real code.

---

## 5. Known failures / architectural gaps

| Gate | Status | Root cause | Tracking |
|---|---|---|---|
| 1 | 🔴 | AppState JS shim disabled (RN 0.85 init race) + native `.none` default | `[App.tsx:38](../../examples/upstream-demo-app-rn/AstronomyShopRN/src/App.tsx#L38)`, `[OTelMobileCallSink.swift:39-46](../../examples/upstream-demo-app-rn/AstronomyShopRN/ios/AstronomyShopRN/OTelMobileCallSink.swift#L39-L46)` |
| 2 | 🟢 | Dedup fix in `ba558c2` | See Gate 2 above |
| 3 | 🟢 | Two-sided fix in `4399e7a` + `0eed784` | See Gate 3 above |
| 4 | 🟢 | Fixed via `PersistingTraceHTTPClient` (HTTPClient-layer interceptor, not SpanExporter decorator — see Gate 4 section) | Validated 2026-04-23: 7 req persisted → 7 replayed → 99 spans in Dash0 |

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

- **2026-04-23** — Gate 4 shipped 🟢. Mid-session redesign: the
  original SpanExporter-decorator design (Tasks 1–8 of the
  2026-04-22 plan) was discovered to be dead code because upstream
  `OtlpHttpTraceExporter.export()` returns `.success` synchronously
  before the HTTP call completes. Pivoted to a custom
  `HTTPClient` (`PersistingTraceHTTPClient`) that intercepts at the
  layer where failures are actually observable. Validated on-device:
  7 persisted OTLP requests → 7 replayed on reconnect → 99 spans
  land in Dash0 with full ShopTelemetry waterfall intact. RN iOS is
  now 3/4 🟢 (Gate 1 remains an architectural choice, not a bug).
- **2026-04-22** — Gates 2+3 shipped 🟢 (commits `ba558c2`,
  `4399e7a`, `0eed784`). Gates 1+4 reds with documented root
  causes. This runbook is extracted from that session's matrix epic
  entry.
- **2026-04-21** — iOS native 4/4 gates 🟢 (commits `d1eb755`,
  `25d47b6`, `1a69c7e`). The three iOS SDK fix commits auto-flow
  through to RN iOS via the local SwiftPM reference.
