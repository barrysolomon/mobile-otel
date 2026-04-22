# RN Bridge FATAL-Severity Bypass

**Status:** Draft — ready for review
**Date:** 2026-04-22
**Owner:** Barry Solomon
**Scope:** `packages/react-native/` — JS bridge layer only

---

## Summary

When a React Native app throws an unhandled JS error, the bridge layer
loses the resulting `app.error` FATAL log because the emit path queues
the payload and waits 50ms before draining to native. The process dies
inside that window.

This design adds a narrow bypass: FATAL-severity logs kick off an
immediate `bridge.flush()`, so the payload crosses the RN bridge in
the same JS tick. From there, the iOS SDK's existing `willTerminate`
auto-flush (commit `1a69c7e`) drains it to disk and attempts OTLP
export before process exit.

## Problem — why this fix is needed

Gate 3 of the 2026-04-22 RN iOS validation sweep failed because:

1. JS throw → `ErrorUtils.setGlobalHandler` fires our handler
2. Handler calls `Dash0Mobile.log('app.error', attrs, SEVERITY_FATAL)`
3. `Dash0Mobile.log()` calls `bridge.emit(payload)`
4. `NativeBridge.emit()` pushes into a JS array, schedules a 50ms
   `setTimeout` to drain
5. Handler returns to `ErrorUtils.setGlobalHandler`'s chain
6. Previous handler (RN's default redbox / fatal reporter) **terminates
   the process** before the 50ms timer fires
7. The payload is still sitting in a JS array — never crossed the bridge

The iOS SDK's `willTerminate` observer and `forceFlushBuffered()` +
disk-persist from commit `1a69c7e` are correct and complete — they
handle everything *once the payload is on the native side*. The gap
is purely in the JS queue.

## Approach — what changes

One condition in `Dash0Mobile.log()` (file `packages/react-native/src/index.ts`):

```typescript
log(name: string, attributes: Attributes = {}, severity: SeverityNumber = 9): void {
  if (!started || !bridge) return;
  bridge.emit({
    kind: 'log',
    name,
    severity,
    attributes,
    timeUnixNano: nowUnixNano(),
  });
  // FATAL-severity logs (21) bypass the 50ms debounce. The payload
  // needs to cross the RN bridge synchronously because the process
  // is about to die — a debounce timer racing a crash handler loses.
  // Once the payload is on the native side, the iOS SDK's
  // willTerminate auto-forceFlush (commit 1a69c7e) takes over to
  // persist + drain. `void` signals that we kick off the flush and
  // do not await its Promise — the RN bridge marshal of the native
  // call is synchronous; only the Promise resolution is async, and
  // we don't care about it on the crash path.
  if (severity >= 21) {
    void bridge.flush();
  }
}
```

**Why `severity >= 21`**: the SeverityNumber type in `bridge/types.ts`
caps at 21 (FATAL). Using `>=` is future-proof if OTel adds FATAL2/
FATAL3/FATAL4 (they exist in the semconv spec as 22-24). Nothing emits
those today; if a caller ever does, it should also bypass debounce.

**Why only in `log()` and not `startSpan`/`endSpan`/`recordMetric`**:

- Spans have no severity. RN's ErrorUtils path is log-only.
- Spans already have a different offline-persistence problem (Gate 4)
  that requires a different fix on the native side (`BatchSpanProcessor`
  has no fail-to-disk).
- Metrics have no severity either and are not produced on the crash path.

## Alternatives considered

### Alternative (b): JS annotates FATAL payloads with `isFatal: true`; native side calls `instance.forceFlush()` eagerly

Rejected. `forceFlush()` is already called by the iOS native
`willTerminate` observer; duplicating that call from the sink's
emitLog path would run the synchronous export twice during the
crash window, burning the available time budget on a redundant
network attempt.

### Alternative (c): JS-side crash marker, direct-write to DiskLogBuffer

Rejected as over-engineering for the current problem. Would reach
across the abstraction boundary between the bridge pod and the
native SDK's internal buffer, bypassing the processor. The native
SDK's existing marker pattern (`CrashInstrumentation` on iOS, mirror
on Android) is the right place for this if the `willTerminate`
fallback proves unreliable in practice — and that's a native-side
design problem, not an RN-bridge one.

### Alternative: sync XHR from JS

Rejected. Not portable across RN's new arch, and doesn't solve the
problem — OTLP endpoints require real OTLP encoding which we don't
have in JS.

## Why option (a) is correct

1. **Minimal surface area**. One condition in one file. No new
   protocols, no new native code, no new bridge methods.
2. **Correct abstraction boundary**. The RN bridge's job is to get
   payloads to the native side. Everything downstream of that —
   disk persistence, retry on next launch, OTLP export — is owned
   by the native SDK and was already solved in commit `1a69c7e`.
3. **Leverages existing guarantees**. The `willTerminate` observer
   already fires on `UIApplication.willTerminateNotification` and
   `UIScene.didEnterBackgroundNotification`. Both fire before the
   process terminates in the iOS Simulator under JS-throw-induced
   RN termination.
4. **Symmetric with iOS native**. iOS native AstronomyShop's Gate 3
   passed because the payload reached the native layer before the
   crash. RN iOS should match — same guarantee, same mechanism, just
   that the payload originates from JS.

## Risk / failure modes

**Risk 1: `willTerminate` doesn't fire on JS-throw-induced termination.**

Hypothesis going in: `willTerminate` is driven by UIKit's app
delegate lifecycle, and a JS-throw that reaches `previous(error,
isFatal=true)` in RN's default handler lands in RedBox, which in
non-dev builds terminates the app with `exit()` — which *does* trigger
`applicationWillTerminate`. In dev builds the RedBox may hold the app
alive waiting for the developer to dismiss. We're testing Release
builds, so this should work.

If it doesn't, the fallback is option (c) — not pre-built; we'll
decide after device validation.

**Risk 2: `bridge.flush()` awaits the Promise; the handler's
continuation blocks the crash path.**

`bridge.flush()` is async — but the `void` prefix drops the Promise.
The synchronous work inside flush is limited to: clear the debounce
timer, set `this.queue = []`, build the batch array, and call
`native.emitBatch(batch)`. Per the RN bridge contract, calling a
native method synchronously marshals the arguments across the
bridge; the returned Promise resolves asynchronously once the
native side signals completion. We never await that Promise, so
the handler returns immediately after the payload is on the native
queue. If RN's bridge implementation changes in a future release
such that argument marshaling becomes asynchronous, this fix would
need revisiting — guard test: the device validation either sees
the FATAL log land in Dash0 or it doesn't.

**Risk 3: FATAL log storms block the handler.**

Unlikely given typical volumes (the JS bridge queue at any instant
holds 0–10 payloads), but defensive: if someone emits 10k FATAL
logs in a loop the handler pays linear cost on each. Not addressing
here — RN already has a 10k MAX_QUEUE cap and errors.ts already
has 5-minute dedupe, so this is bounded.

## Tests

New file: `packages/react-native/__tests__/bridge/fatal-bypass.test.ts`.

1. **FATAL emit triggers immediate `emitBatch`.** Jest fake timers.
   Emit one FATAL log. Assert `native.emitBatch` is called synchronously
   (before advancing fake timers). Assert the batch contains exactly the
   one payload.
2. **Non-FATAL emit does NOT trigger immediate `emitBatch`.** Emit one
   ERROR (severity 17). Assert `native.emitBatch` is not yet called.
   Advance fake timers by 50ms. Assert it's now called.

Existing tests unchanged:

- `bridge/nativeBridge.test.ts` debounce/retry/queue-cap — `flush()`
  still has the same contract; new callers just don't add new
  scenarios.
- `instrumentation/errors.test.ts` — the errors.ts shim already
  exercises SEVERITY_FATAL; now its integration surface gets one
  more guarantee (debounce-bypass), covered by the new test.

## Device validation

Procedure (mirrors Gate 3 from the Validation Matrix Epic):

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
SIM=65F2CFA7-B5AA-4C37-8A39-A9235CA4FBFE
cd examples/upstream-demo-app-rn/AstronomyShopRN
/opt/homebrew/bin/node node_modules/react-native/cli.js bundle --platform ios --dev false --entry-file index.js --bundle-output ios/main.jsbundle --assets-dest ios
cd ios
xcodebuild -workspace AstronomyShopRN.xcworkspace -scheme AstronomyShopRN \
  -configuration Release -sdk iphonesimulator \
  -destination "platform=iOS Simulator,id=$SIM" \
  -derivedDataPath build build
APP=build/Build/Products/Release-iphonesimulator/AstronomyShopRN.app
xcrun simctl install "$SIM" "$APP"
xcrun simctl launch "$SIM" org.reactjs.native.example.AstronomyShopRN

# Wait for app to render, then tap the red crash button.
open -a Simulator --args -CurrentDeviceUDID "$SIM"
osascript -e 'tell application "Simulator" to activate'
osascript -e 'tell application "System Events" to tell process "Simulator" to click at {200, 835}'

# Wait 30s for Dash0 indexing
sleep 30

# Query
dash0 -X logs query \
  --filter "service.name is otel-rn-astronomy-shop and severity_text is FATAL" \
  --from now-5m -o json
```

Expected output: exactly 1 log record with:

- `body.stringValue == "app.error"`
- `severityNumber == 21`, `severityText == "FATAL"`
- `attributes` contains `exception.type == "Error"`,
  `exception.message == "Dash0 RN iOS Gate 3 test crash"`, and a
  non-empty `exception.stacktrace`
- `scope.name == "io.dash0.mobile"`

## Epic doc updates

After validation passes:

- RN iOS Gate 3 column: 🔴 → 🟢 with commit SHA
- Gate 3 matchy-matchy section: replace the "Actual: 0" paragraph
  with a post-fix result block
- Work items checklist: mark "Gate 3 fix" complete

## Rollback / revert strategy

One-line git revert. The new test file is independent. The epic-doc
update is in its own follow-up commit for clean bisect.

## Non-goals

- Does NOT fix Gate 4 offline span persistence — different failure
  mode, different layer, separate design.
- Does NOT fix Gate 1 lifecycle — architectural, not a bug.
- Does NOT fix Android. Android bridge has a parallel `NativeBridge`
  with its own debounce; same fix applies there if Android hits the
  same crash-loss pattern on Gate 3 validation, but we're not
  pre-emptively touching it.
- Does NOT add a user-facing config to tune bypass severity. `>= 21`
  is the bright line between "recoverable" and "unrecoverable."
