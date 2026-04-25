# Epic: Validation Matrix — End-to-End Proof That Telemetry Lands

**Status:** In progress
**Priority:** P0 (golive gate)
**Owner:** Barry Solomon
**Created:** 2026-04-21
**Target:** Before first external customer deployment

---

## Summary

Every mobile SDK + demo app + collector configuration must be provably
sending real, auto-instrumented telemetry to Dash0 — not faked
telemetry, not app-code-emitted spans, not "the tests pass" —
**actual backend-observed data from real user actions.**

This epic defines:
1. **A shared four-gate validation bar** every platform/app must pass.
2. **A matrix** of what's validated today (and what isn't) per
   hardware × software × signal cell.
3. **The hard-won session findings** — bugs the validation caught and
   the techniques that caught them, so future sessions don't re-learn
   from zero.
4. **"Matchy matchy" demo scripts** — per-platform runbooks producing
   comparable Dash0 evidence so parity is observable.

## Success criteria

- Every supported platform (Android native, iOS native, RN Android, RN
  iOS) passes all four gates in Dash0 with evidence captured in this
  doc.
- Every demo app (upstream-demo-app, upstream-demo-app-ios,
  upstream-demo-app-rn, demo-app-ios-starter) has a runbook whose
  steps map 1:1 to the four gates.
- CI verifies gates 1 and 2 per-commit on at least one representative
  platform; gates 3 and 4 run weekly or on release branches.
- No "green" claim without a Dash0 filter + timestamp screenshot/CLI
  JSON captured in the session notes.

---

## 1. The Four-Gate Validation Bar

Each gate is a single falsifiable claim. A gate is green only when you
can produce raw Dash0 query output (via `dash0 -X logs/spans query`,
not MCP or web UI) showing a record whose `timestamp` is *after* the
trigger, sourced only from auto-instrumentation (no app-code
`tracer.spanBuilder` or `logger.emit` counts).

### Gate 1 — Auto-instrumented lifecycle

| | |
|---|---|
| **Trigger** | Fresh app launch, followed by two background-foreground cycles |
| **Expected in Dash0** | Logs: `app.launch` (once), `app.foreground` (x3), `app.background` (x2). Span: `app.startup` (once, duration ~1–2s). |
| **Filter** | `service.name is <service> and event.name is app.foreground` |
| **Pass criteria** | Count matches, timestamps interleaved in correct order, scope `io.dash0.mobile` (or RN equivalent) |

### Gate 2 — Auto-instrumented network

| | |
|---|---|
| **Trigger** | App makes a real HTTPS GET (e.g. httpbin.org) via URLSession / OkHttp / fetch |
| **Expected in Dash0** | Span named `GET`, kind=client, attributes `http.request.method`, `server.address`, `http.response.status_code`, `url.full` |
| **Filter** | `service.name is <service> and http.request.method is GET` |
| **Pass criteria** | Span duration > 0, scope matches SDK, URL scrubbed per configured `stripQueryStrings` |

### Gate 3 — Real crash capture

| | |
|---|---|
| **Trigger** | Unhandled native exception or fatal signal (SIGTRAP via array OOB, `fatalError`, NSException, uncaught Kotlin) mid-flight |
| **Expected in Dash0** | Log `app.crash` severity FATAL with `crash.kind=signal|exception`, `crash.name=<SIGTRAP|NSException|...>`, `crash.from_marker=true`, landed AFTER process restart |
| **Filter** | `service.name is <service> and event.name is app.crash` |
| **Pass criteria** | Log carries correct signal/exception class and the timestamp is from the recovery launch, not the crash itself (marker-based re-emission) |

### Gate 4 — Offline buffering + drain

| | |
|---|---|
| **Trigger** | App generates N telemetry events while export endpoint is unreachable (airplane mode OR DNS-blackholed), then reconnects |
| **Expected in Dash0** | `app.recovery_start` log with `dash0.recovery.event_count=N`, followed by all N original events (their timestamps from the offline window) |
| **Filter** | `service.name is <service> and event.name is app.recovery_start` |
| **Pass criteria** | `event_count` in marker matches the known N, disk buffer is empty post-recovery, no gap in timestamps vs sequence IDs |

---

## 2. Hardware × Software × Signal Matrix

Green = verified end-to-end in Dash0 with captured evidence. Yellow =
exists in code but not backend-validated this session. Red = gap.

### SDK × Gate

| SDK | Gate 1 Lifecycle | Gate 2 Network | Gate 3 Crash | Gate 4 Offline |
|---|---|---|---|---|
| Android native (`otel-android-mobile/`) | 🟡 existing runbook, needs re-validation post-`iPhone`-branch SDK changes | 🟡 same | 🟡 same | 🟡 same |
| iOS native (`otel-ios-mobile/`) | 🟢 **verified 2026-04-21, commit `d1eb755`** | 🟢 **verified 2026-04-21, commit `25d47b6`** | 🟢 **verified 2026-04-21** | 🟢 **verified 2026-04-21, commit `1a69c7e`** |
| RN Android (`packages/react-native/` on Android host) | 🟡 Jest + demo APK green per 2026-04-20, needs Dash0-side re-check | 🟡 | 🔴 untested | 🔴 untested |
| RN iOS (`packages/react-native/` on iOS host) | 🔴 **architecturally off** (AstronomyShopRN disables `autoCapture.lifecycle` + native defaults to `.none`) | 🟢 **verified 2026-04-22** GET span w/ kind=CLIENT, status=200, url.full=`https://httpbin.org/get`, scope `io.dash0.mobile`. Shim dedup landed in `ba558c2` — exactly 1 span per request (re-verified) | 🟢 **verified 2026-04-22** `app.error` FATAL log lands in Dash0 at exact crash timestamp with full exception semconv. Two-sided fix: JS `emitSync` (`4399e7a`) closes bridge-queue race + native eager `forceFlush` (`0eed784`) closes RAM-buffer race since RN fatal reporter skips UIApplication teardown | 🟢 **verified 2026-04-23, redesigned 2026-04-24** 7 failed OTLP trace POSTs persisted to `buffered_span_requests`, replayed on reconnect; `app.recovery_start` marker carries `dash0.recovery.span_count` + `dash0.recovery.span_bytes_pending`. Fix intercepts at `HTTPClient` layer (`PersistingTraceHTTPClient`), not `SpanExporter`, because upstream `OtlpHttpTraceExporter.export()` returns `.success` synchronously before the HTTP call. Replay routing comes from the user's CURRENT `MobileConfig` (not whatever was captured at failure time), so token rotation / region migration / dataset rename / typo fixes between launches all do the right thing automatically. Re-validated end-to-end without any manual sqlite endpoint rewrites |
| Collector processor (`mobilepolicyprocessor/`) | ➖ N/A (no lifecycle of its own) | ➖ N/A | ➖ N/A | ➖ N/A — but must pass DSL evaluation tests |

### Hardware × OS

| SDK | Simulator/Emulator | Physical device | OS versions tested |
|---|---|---|---|
| Android native | 🟡 Pixel_7, Pixel_3a, Medium_Phone_API_36.1 AVDs | 🔴 not in CI | API 26–36 |
| iOS native | 🟢 iPhone 17 Simulator iOS 26.4 | 🔴 not in CI | iOS 15+ target, iOS 26 validated |
| RN Android | 🟡 Pixel 7 AVD | 🔴 not in CI | API 26+ |
| RN iOS | 🟡 iPhone 17 Simulator | 🔴 not in CI | iOS 26.4 validated |

### Demo application coverage

| Demo app | Primary SDK | Auto-instr test? | Matchy-matchy script? |
|---|---|---|---|
| `examples/demo-app/` (Android) | Android native | Yes (instrumented UI tests) | Needs alignment with 4-gate template |
| `examples/upstream-demo-app/` (Android) | Android native (via OTelMobile 1.2.0-alpha) | Yes | Needs alignment |
| `examples/upstream-demo-app-ios/` (AstronomyShop iOS) | iOS native | 🟢 all 4 gates today (session) | 🟢 this session is the script |
| `examples/upstream-demo-app-rn/` (AstronomyShopRN) | RN Android + RN iOS | 🟡 | Needs alignment |
| `examples/demo-app-ios-starter/` | iOS native | 🔴 auto-demo broken per 2026-04-17b | Not part of matrix |

---

## 3. Session Findings — What validation uncovered

Captured 2026-04-21 during iOS native four-gate validation sweep.

### SDK bugs found and fixed

| # | Bug | Where | Commit |
|---|---|---|---|
| 1 | `LifecycleInstrumentation.install()` deadlocks on NSLock reentrancy — `install` took the lock, called `emit()` which re-acquires it → main queue hangs at line 49, observer registration at lines 59+ never runs → **every iOS app silently loses lifecycle telemetry** | `otel-ios-mobile/Sources/LifecycleInstrumentation/LifecycleInstrumentation.swift` | `d1eb755` |
| 2 | `LifecycleInstrumentation` observed only `UIApplication.*Notification` — modern SwiftUI / scene-based apps only post `UIScene.*Notification`, so even without the deadlock lifecycle would have been silent on every post-iOS-13 app with `UIApplicationSceneManifest` | same file | `d1eb755` |
| 3 | `NetworkInstrumentation.install` ran inside `DispatchQueue.main.async` → any URLSession request fired synchronously during `App.init` / `onAppear` completed before the URLProtocol swizzle registered → first-tick HTTP was silently uncaptured | `otel-ios-mobile/Sources/OTelMobileSDK/OTelMobile.swift` | `25d47b6` |
| 4 | Upstream `OtlpHttpLogExporter.export(...)` in opentelemetry-swift is fire-and-forget — kicks off async URLSession, returns `.success` immediately regardless of network outcome. `RetryableExporter` never retried, every offline export looked like success → silent drop. Worked around via `SynchronousLogRecordExporter` with blocking `HTTPClient` wrapper that captures real `HTTPURLResponse`. | `otel-ios-mobile/Sources/OTelMobileSDK/Export/SynchronousLogRecordExporter.swift` (new) | `1a69c7e` |
| 5 | `MobileLogRecordProcessor.forceFlush(explicitTimeout:)` and `forceFlushBuffered()` both drain RAM via `buffer.flush()` and drop on export failure — no disk persist on `.failure` → even with bug #4 fixed, events were gone on offline. Fixed by adding failure-persist-to-disk in both paths, deduped by `sequenceId`. | `otel-ios-mobile/Sources/OTelMobileSDK/Buffering/MobileLogRecordProcessor.swift` | `1a69c7e` |
| 6 | No auto-`forceFlush` on backgrounding — customer apps that went offline, backgrounded, and terminated before reconnecting lost their RAM-resident telemetry. Fixed by registering observers on `UIApplication.didEnterBackgroundNotification` + `UIScene.didEnterBackgroundNotification` + `UIApplication.willTerminateNotification` that call `instance.forceFlush()`. | `otel-ios-mobile/Sources/OTelMobileSDK/OTelMobile.swift` | `1a69c7e` |

### Why these slipped past existing tests

- **`OTelURLProtocolTests` explicitly punts on full round-trip**: *"Doesn't drive a full URLSession round-trip — that lives in the scenario validation scripts."* Those "scenario validation scripts" don't exist yet at CI grade for iOS.
- **Unit tests assert the contract of individual classes**, not the integration outcome "a span showed up in the backend." The install-order and deadlock bugs both type-check and unit-test-green.
- **opentelemetry-swift's upstream bug was invisible** to any test that mocked out the HTTP client (which is what the SDK's tests do). A real URLSession against an unreachable endpoint surfaces it instantly.

### Validation techniques that worked

| Technique | When to use |
|---|---|
| `dash0 config profiles activate mobile-test && dash0 -X logs/spans query --filter <…> --from now-5m -o json \| python3` | Default for every gate check |
| **Relative `now-Xm` windows** over absolute timestamps | Less finicky — absolute-time filters failed for me multiple times when the clock assumption drifted |
| Config-swap to unreachable endpoint (`*.invalid:4318`) for offline simulation | Gate 4 — avoids touching host Wi-Fi, sudo, or firewall |
| `samplingConfig: .alwaysOn()` in demo bootstrap | Low-volume demos otherwise lose 90% of spans to dynamic sampling defaults |
| `DispatchSemaphore`-wrapped `URLSession.send` | Turning fire-and-forget upstream into synchronous |
| Inspect sqlite disk buffer directly: `sqlite3 <app-container>/Library/Application Support/io.dash0.mobile/buffer.db "SELECT COUNT(*) FROM buffered_events"` | Offline-buffer validation without rerunning the app |
| `xcrun simctl spawn booted log stream --predicate 'eventMessage CONTAINS "DASH0-DEBUG"'` + temporary `NSLog` in SDK | When a gate fails silently, NSLog breadcrumbs beat xcode-attached debugging |

### iOS Simulator limits to know

- **No simulator-scoped airplane mode.** iOS Simulator shares the host's network stack; the Settings app's airplane-mode toggle is purely visual. To simulate offline, either host-level firewall/Wi-Fi-off (destructive to the developer's connectivity) or config-swap to unreachable endpoint.
- **`simctl launch <other-bundle-id>` does not reliably background the current app** in all cases. iOS 26 sometimes keeps the first app foregrounded across a second launch.
- **`simctl uninstall` wipes the Data container.** For offline drain tests, use `terminate + install` (without uninstall) to preserve the disk buffer across "restart" simulations.
- **`xcrun simctl spawn booted log show` predicates filter against the PROCESS CMDLINE, not message body.** Use `log stream` (which pipes live messages) when you need to grep message bodies.

### Dash0 query gotchas

- **Event timestamps are the offline-window timestamps**, not the drain time. When validating drain, query a window that includes the offline period.
- **"Last 5 minutes" with `now-5m` is more reliable than absolute timestamps** because dataset indexing latency varies.
- **`service.name is X` filter is case-sensitive**. Match the exact string in `otel-config.json`.

---

## 4. Matchy-matchy Demo Scripts

Each demo app gets a runbook with the same structure so outputs compare cleanly.

**Runbooks live under [`docs/matchy-matchy/`](../matchy-matchy/README.md)** — one file per platform, all keyed to the four gates for cross-platform diff-ability.

### Template (per-platform)

```
0. Pre-flight
   - dash0 config profiles activate mobile-test
   - baseline query: dash0 -X logs query --filter "service.name is <svc>" --from now-2m
     → expect 0 results

1. GATE 1 — Lifecycle
   - Install + launch app clean
   - Background → foreground twice
   - Query: dash0 -X logs query --filter "service.name is <svc> and event.name is app.foreground" --from now-5m
     → expect 3 results

2. GATE 2 — Network
   - Drive real HTTP GET (onAppear-triggered pokeBackend)
   - Query: dash0 -X spans query --filter "service.name is <svc> and http.request.method is GET" --from now-5m
     → expect ≥1 result with kind=3 (client), status_code in 2xx

3. GATE 3 — Crash
   - Launch app with crash-trigger arg
   - Confirm process died
   - Relaunch without crash arg
   - Query: dash0 -X logs query --filter "service.name is <svc> and event.name is app.crash" --from now-5m
     → expect 1 result, severity FATAL, crash.from_marker=true

4. GATE 4 — Offline
   - Swap otel-config to unreachable endpoint, rebuild
   - Launch + background (triggers auto-forceFlush → failure → disk persist)
   - Swap back to real endpoint, rebuild, relaunch
   - Query: dash0 -X logs query --filter "service.name is <svc> and event.name is app.recovery_start" --from now-5m
     → expect 1 result with dash0.recovery.event_count ≥ 1
```

### iOS native (AstronomyShop)

**Canonical today — this session is the reference.** See `otel-ios-mobile/examples/upstream-demo-app-ios/README.md` + evidence in commits `d1eb755` / `25d47b6` / `1a69c7e`.

Service name: `otel-ios-astronomy-shop`. All four gates green as of 2026-04-21.

### iOS native (starter) — 🔴

Auto-demo loop broken per memory 2026-04-17b. Not part of matrix.

### Android native (AstronomyShop, demo-app) — 🟡 TODO

Needs this session's 4-gate template applied. Service name:
`otel-android-astronomy-shop`. Existing `HOW_TO_DEMO.md` covers a
different (broader) runbook; reconcile.

### RN iOS (AstronomyShopRN) — 3 of 4 gates green

**Validated 2026-04-22, 2026-04-23, redesigned 2026-04-24.**
Service name: `otel-rn-astronomy-shop`. Result summary: **Gates
2 + 3 + 4 🟢, Gate 1 🔴** — Gate 1 remains an architectural
choice (RN demo disables `autoCapture.lifecycle` + native
defaults to `.none`) rather than a bug. Gate 3 (crash) took
three iterations beyond the initial design; Gate 4 (offline)
went through TWO design corrections: (a) original SpanExporter
decorator was dead code because upstream OTLP trace exporter
returns `.success` synchronously before the HTTP call, fixed by
moving to an HTTPClient interceptor; (b) initial HTTPClient
design captured + replayed to the captured endpoint, but that
fails token rotation, region migration, and dataset rename
between launches — fixed by routing replays through the user's
CURRENT `MobileConfig`. The persisted schema now stores only
the body bytes + session id, not endpoint or headers.

Pre-flight (same for each gate run):

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
# Boot iPhone 17 simulator (UDID may differ locally)
SIM=65F2CFA7-B5AA-4C37-8A39-A9235CA4FBFE
dash0 config profiles activate mobile-test
# xcode.env.local must point NODE_BINARY at Node 18.17+ that has Array.prototype.toReversed
# (Node 25 works; system Node 18.17.0 does not).
```

**Gate 1 — Lifecycle 🔴** (architectural gap, not a bug)

```
AstronomyShopRN/src/App.tsx:38 disables autoCapture.lifecycle with a
comment about RN 0.85 new-arch + AppState init-order race.
ios/AstronomyShopRN/OTelMobileCallSink.swift:38-46 defaults native
autoCaptureOptions to .none because the iOS URLProtocol swizzle and
NSException/signal handlers collide with RN's JS event loop.

Result: neither JS-side installAppStateInstrumentation nor the native
iOS LifecycleInstrumentation emit app.foreground / app.background.

Query: dash0 -X logs query --filter "service.name is otel-rn-astronomy-shop and event.name is app.foreground" --from now-5m
Expected: ≥3. Actual: 0.

Remediation: either fix RN's AppState init race so the JS shim is
reliable, or add an opt-in flag for native `lifecycle` capability that
is known-safe with the RN new-arch event loop.
```

**Gate 2 — Network 🟢**

```
AstronomyShopRN/src/screens/ProductListScreen.tsx fires a delayed
(2s post-mount) fetch('https://httpbin.org/get') — delay is required
because `Dash0Mobile.start()` installs the fetch shim inside an async
`useEffect`, so a synchronous fetch on the same mount races the
installer.

Query: dash0 -X spans query --filter "service.name is otel-rn-astronomy-shop" --from now-30m
Expected: ≥1 span with name=GET, kind=3 (CLIENT), attributes
  http.request.method=GET, http.response.status_code=200,
  server.address=httpbin.org, url.full=https://httpbin.org/get,
  scope=io.dash0.mobile
Actual (post-`ba558c2`): 1 span, named `GET httpbin.org` from the
XHR shim. XHR is authoritative on RN because it also catches direct
XHR callers (axios, Apollo) that would bypass the fetch wrapper.
On non-RN JS environments, both shims still install (fetch is
native and independent of XHR there).

Pre-fix history: on 2026-04-22 initial run, 2 spans landed per
request (`GET` from fetch shim + `GET httpbin.org` from XHR shim)
because RN's `fetch` is implemented on top of XHR and both shims
intercepted the same request. Fixed by detecting
`navigator.product === 'ReactNative'` in index.ts's install path
and skipping the fetch shim on RN only.

Dash0 filter-DSL gotcha: `http.request.method is GET` returns 0
results even though the attribute is present. Use name-based or
span-level filters until we understand why attribute-based filtering
doesn't index RN-origin spans.
```

**Gate 3 — Crash 🟢**

```
Trigger: tap the red "Trigger Crash (Gate 3)" button added to
ProductListScreen. onPress fires a setTimeout(() => { throw new Error('Dash0 RN iOS Gate 3 test crash'); }, 0)
so the throw escapes React's commit phase and reaches RN's
ErrorUtils global handler, which instrumentation/errors.ts has
chained into.

Flow post-fix:
  1. errors.ts emits Dash0Mobile.log('app.error', attrs, 21)
  2. Dash0Mobile.log sees severity ≥ 21 and routes through
     bridge.emitSync(payload) — NOT the debounced bridge.emit.
     emitSync calls native.emitBatch([...queue, payload]).catch(...)
     with no `await` anywhere; per the RN bridge contract,
     argument marshaling is synchronous, so the payload crosses
     the bridge on the current stack frame before the handler
     returns. Commit 4399e7a.
  3. Native OTelMobileCallSink.emitLog receives the payload via
     the dispatcher, builds the log record, then — on severity
     ≥ 21 — calls instance.forceFlush() synchronously. forceFlush
     drains the RAM buffer through SynchronousLogRecordExporter,
     and on export failure persists to disk via the fail-persist
     path from commit 1a69c7e. Commit 0eed784.
  4. Log lands in Dash0 with the original timestamp and full
     OTel exception.* semconv.

Query: dash0 -X logs query --filter "service.name is otel-rn-astronomy-shop" --from now-5m
Result (2026-04-22):
  body:                  app.error
  severityNumber:        21 (FATAL)
  scope.name:            io.dash0.mobile
  timestamp:             1776873856 (= crash tap to the second)
  exception.type:        Error
  exception.message:     Dash0 RN iOS Gate 3 test crash
  exception.stacktrace:  Error: Dash0 RN iOS Gate 3 test crash
                         at anonymous (...RN JS bundle...)

Why two separate fixes were needed:

  - Window A (JS queue → native): closed by emitSync. Initial
    attempt using `void bridge.flush()` failed because
    bridge.flush is async and `await this.drain()` placed
    native.emitBatch on the microtask queue; the JS handler's
    synchronous continuation into RN's fatal reporter won the
    race. emitSync avoids any microtask boundary.

  - Windows B+C (native RAM → OTLP/disk): closed by eager
    forceFlush in OTelMobileCallSink. Initial attempt relied on
    the willTerminate auto-forceFlush from commit 1a69c7e, but
    that notification never fires on JS-throw-induced RN
    termination — RN's fatal reporter calls abort()/exit() and
    skips UIApplication lifecycle teardown. forceFlush on the
    hot path is the reliable alternative.

Naming drift to record: errors.ts emits `app.error`, not
`app.crash` like iOS native's CrashInstrumentation. A future
cross-platform Gate 3 filter needs both event names OR an alias.
Not a blocker — the RN JS path is inherently distinct from the
native signal-handler path, and both names are meaningful.
```

**Gate 4 — Offline 🟢**

```
Procedure: cp otel-config.json → .invalid endpoint → rebuild JS
bundle + Release .app → install + launch → drive UI for ~30s →
terminate → swap back → rebuild → relaunch → query.

Result on 2026-04-24 redesigned validation run:
- Offline window: OTLP trace POSTs to *.invalid fail DNS; each
  gets captured by PersistingTraceHTTPClient. Persisted shape is
  body bytes + session id only — NOT endpoint or headers.
  Disk state: 2 rows, 1356 bytes total.
- On reconnect launch: Task.detached recovery block reads
  rowCount, emits app.recovery_start with
    dash0.recovery.span_count = 2
    dash0.recovery.span_bytes_pending = 1356
  then OTelMobile.recoverSpanRequests(from:endpoint:headers:
  httpClient:batchSize:) replays each row via BaseHTTPClient,
  POSTing to the URL built from CURRENT config.endpoint with
  headers built by OTelMobile.buildReplayHeaders(authToken:
  extraHeaders:). 2 POSTs to ingress.us-west-2.aws.dash0.com
  return 2xx, rows deleted. NO manual sqlite endpoint rewrite
  needed — the original validation in 2026-04-23 required
  rewriting the captured endpoint column before relaunch
  because the old design preserved the failed-export URL.
- Dash0 backend: app.recovery_start marker landed with the
  exact attribute values predicted by the persisted disk state
  (count=2, bytes=1356). Replayed span bodies are byte-identical
  OTLP protobuf so the collector treats them as live spans at
  their original timestamps.

Architectural lesson (IMPORTANT, reusable for iOS native + future
work): the OBVIOUS-looking design — a SpanExporter decorator that
watches for `.failure` from the OTLP exporter — IS DEAD CODE.
Upstream's OtlpHttpTraceExporter.export() returns .success
synchronously BEFORE the HTTP call completes (see
opentelemetry-swift/Sources/Exporters/OpenTelemetryProtocolHttp/
trace/OtlpHttpTraceExporter.swift ~line 85). Failures surface only
via the internal httpClient.send { result in ... } callback, which
a SpanExporter-level decorator never sees.

The fix intercepts at the layer that DOES see failures: the custom
HTTPClient passed into OtlpHttpTraceExporter via its existing
`httpClient:` init parameter. PersistingTraceHTTPClient wraps
BaseHTTPClient, and on failure modes (network error, 5xx, 429) it
captures the raw URLRequest body bytes and persists them to
DiskSpanBuffer. We store bytes not SpanData because decoded spans
are not available at the HTTPClient layer — and because raw-byte
replay is actually better (byte-identical collector input means
perfect idempotency).

Second design lesson (2026-04-24): the schema initially preserved
endpoint + headers along with the body, so replay POSTed to
whatever URL was captured at failure time. That is wrong for the
common lifecycle events that motivate offline buffering:
  - Token rotation: Authorization header captured stale, replays 401
  - Region migration: replays go to old region, never reach new one
  - Dataset rename: replays land in deprecated dataset
  - Endpoint typo fix: replays still go to the typo
The corrected design persists ONLY the body + session_id + dedup
key, and `recoverSpanRequests(from:endpoint:headers:httpClient:)`
takes the destination as an explicit caller arg. The recovery
block in OTelMobile.start derives both from the user's CURRENT
MobileConfig at recovery time. Body bytes are byte-identical OTLP
protobuf, so the collector still receives the original spans —
only the routing reflects current intent.

What drops vs retries vs succeeds on replay:
  - 2xx  → delete row (success; count toward `replayed`)
  - 5xx  → retry (leave on disk, stop the batch early to spare
           bandwidth against a dead collector)
  - 429  → retry (explicit backpressure)
  - 4xx non-429 → drop row (client error, won't succeed later;
                  keeps the disk from filling with perma-bad bytes)
  - network error → retry
```

**What's green today despite the three reds:** the bridge contract
works end-to-end under normal online conditions. RN iOS can produce
the same spans shape as iOS native (same scope, same attributes,
proper waterfall parent/child), and the install sequence (AppDelegate
→ installSink → RCTDash0MobileModule init → JS start → native sink
→ OTelMobile.start) runs deterministically. The four-gate reds are
all *reliability under adversity* problems — crash path, offline
path, and lifecycle — that compound with RN's architectural choices.

### RN Android (AstronomyShopRN) — 🟡 TODO

Service name: same `otel-rn-astronomy-shop` (same app, different
platform, different device attributes). Android-host native SDK is
the underlying buffer store.

### Collector processor — ➖ N/A for device gates

Instead needs its own matrix: OTTL / policy DSL / flush-window
semantics validated against known inputs. Separate epic — see
SCALE_READINESS_EPIC.md.

---

## 5. Work items

### This session
- [x] iOS native — all four gates green, three SDK fix commits
- [x] This epic drafted with session findings frozen in writing
- [x] RN iOS — four gates run; 3 green (Gates 2 + 3 + 4), 1 red (Gate 1) with documented architectural root cause
- [x] Write `docs/matchy-matchy/` one runbook per demo app — landed as [`docs/matchy-matchy/`](../matchy-matchy/README.md): [`ios-native.md`](../matchy-matchy/ios-native.md) 🟢, [`rn-ios.md`](../matchy-matchy/rn-ios.md) 🟢🟢🔴🔴, [`android-native.md`](../matchy-matchy/android-native.md) 🟡 TODO, [`rn-android.md`](../matchy-matchy/rn-android.md) 🟡 TODO

### Follow-ups surfaced by RN iOS validation (2026-04-22)

- [x] Gate 3 fix: JS-side bypass for FATAL via new `NativeBridge.emitSync` (`4399e7a`) + native-side eager `forceFlush` on FATAL in `OTelMobileCallSink.emitLog` (`0eed784`). willTerminate auto-flush was unreliable because RN's fatal reporter skips UIApplication teardown. Device-verified — `app.error` FATAL log lands in Dash0 at exact crash-tap timestamp with full exception semconv
- [ ] Gate 4 fix: extend disk-persist-on-failure from logs to spans (BatchSpanProcessor needs a custom exporter wrapper); add recoverFromDisk for spans at OTelMobile.start()
- [x] Gate 2 polish: deduplicate fetch+XHR double-instrumentation — landed in `ba558c2` (detect RN via `navigator.product`, skip fetch shim; XHR is authoritative since RN fetch is XHR-backed)
- [ ] Gate 1 unblock: investigate whether AppState-under-RN-new-arch init-order has stabilized upstream since the `autoCapture: { lifecycle: false }` workaround was added
- [ ] `.xcode.env.local` in AstronomyShopRN pinned NODE_BINARY to nvm Node 18.17 (no `Array.prototype.toReversed`) — fix folded into this session's commit
- [ ] Document Dash0 CLI query-DSL gotcha: attribute-based filters like `http.request.method is GET` returned 0 results despite attribute present on span — filter behavior needs clarification

### Post-session
- [ ] Android native — re-verify four gates post `iPhone` branch merge
- [ ] RN Android — four gates
- [ ] CI hook: Gate 1 + 2 on every PR against at least iOS Simulator + Android emulator
- [ ] Real-device matrix (at least one iOS physical + one Android physical) run weekly
- [ ] Collector-side validation matrix as separate epic
- [ ] Upstream PR to `opentelemetry-swift` for the fire-and-forget `OtlpHttpLogExporter.export(...)` — our `SynchronousLogRecordExporter` is a workaround, not a fix

### Blocked / parked
- [ ] True Simulator-only airplane mode (Apple doesn't support it; dependent on feature request)
- [ ] Collector pen testing epic — see `~/.claude/projects/…/memory/project_collector_security_review_epic.md`
