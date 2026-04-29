# Matchy-matchy — RN Android (AstronomyShopRN on Android host) 🟢 4/4

**Service name:** `otel-rn-astronomy-shop` (same app as RN iOS,
different platform → device attributes identify the host)
**Package:** `com.astronomyshoprn`
**Last validated:** 2026-04-29 (Pixel_7 emulator) — full Dash0
round-trip green for **all four gates** after Gate 1 closure via
ProcessLifecycleOwner migration + late-init synthesis (commits
`853b3c1` + `919ca39`); Gates 2/3/4 green via the earlier
[`otelEndpoint.ts`](../../examples/upstream-demo-app-rn/AstronomyShopRN/src/otelEndpoint.ts)
port-mismatch fix.
**Status:** 🟢 Gate 1 · 🟢 Gate 2 · 🟢 Gate 3 · 🟢 Gate 4 (4/4 verified 2026-04-29)

See the RN iOS runbook ([`rn-ios.md`](rn-ios.md)) for the reference
shape — most of the RN-specific architecture (JS-side shims as
primary signal source, `autoCaptureOptions: .none` in native,
XHR-authoritative network capture) applies identically.

Once the picker-upper runs this, update
[`../epics/VALIDATION_MATRIX_EPIC.md`](../epics/VALIDATION_MATRIX_EPIC.md)
row 3 and the matrix README status.

---

## 0. Pre-flight

```bash
# Boot an Android emulator for RN
nohup emulator -avd Pixel_7 -no-snapshot-save > /tmp/emu1.log 2>&1 &
adb wait-for-device
until adb shell "getprop sys.boot_completed" 2>/dev/null | grep -q 1; do sleep 5; done

dash0 config profiles activate mobile-test

# Baseline — confirm zero spans for our service before launch
dash0 -X spans query \
  --filter "service.name is otel-rn-astronomy-shop" \
  --from now-2m -o json | python3 -c '
import json, sys
d = json.load(sys.stdin)
n = sum(len(s.get("spans", [])) for r in d.get("resourceSpans", []) for s in r.get("scopeSpans", []))
print(f"baseline spans: {n}")'
```

### Environment notes (corrected 2026-04-28)

The 2026-04-24 "emulator network blocked" claim was a misread of
ICMP-filtered ping — TCP/HTTPS to Dash0 ingress works fine from
the emulator (proven by Android native running 4/4 🟢 from the
same emulator). See [`android-native.md` §0](android-native.md#environment-notes-corrected-2026-04-28)
for the corrected reachability check (`nc -z`, not `ping`).

### Build-side gotchas

1. **Node version.** Metro requires Node 20+ (uses
   `Array.prototype.toReversed`). The system `node` symlink may
   point at older Node 18. Pin via `nodeExecutableAndArgs` in
   `android/app/build.gradle`:
   ```
   nodeExecutableAndArgs = ["/opt/homebrew/bin/node"]
   ```
2. **Bundle is gradle-cached.** Editing `otel-config.json` does NOT
   invalidate the `createBundleReleaseJsAndAssets` task — the
   external JSON isn't tracked as a gradle input. `touch index.js`
   before rebuilding, or use `--rerun-tasks`.
3. **Endpoint port.** Android SDK speaks OTLP/gRPC (`:4317`); iOS
   SDK speaks OTLP/HTTP (`:4318`). The shared `otel-config.json`
   carries one endpoint, so [`src/otelEndpoint.ts`](../../examples/upstream-demo-app-rn/AstronomyShopRN/src/otelEndpoint.ts)
   substitutes the right port per platform — user can paste either
   port in the config file. If you ever see `gRPC status code 2`
   with empty error, suspect `endpointForPlatform` got bypassed.

### Build commands (Release, Metro-less)

```bash
cd examples/upstream-demo-app-rn/AstronomyShopRN

# JS bundle for Android
/opt/homebrew/bin/node node_modules/.bin/react-native bundle \
  --platform android --dev false \
  --entry-file index.js \
  --bundle-output android/app/src/main/assets/index.android.bundle \
  --assets-dest android/app/src/main/res

# Gradle release assemble
cd android
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.dash0.mobile.demo.astronomyshoprn/.MainActivity
```

**Why Release:** on Android the Release path also avoids the Metro
port collision (OrbStack on :8081 on Barry's machine). Bundle +
Release build is the reproducible test path.

---

## 1. Gate 1 — Lifecycle 🟢 verified 2026-04-29

**Closure:** root cause was an Android SDK install-time race —
`LifecycleInstrumentation` counted `Application.ActivityLifecycleCallbacks`
from zero, but RN init runs from JS `useEffect` after the host Activity is
already started, so the counter ran 0→-1 on first stop and never satisfied
the emit predicates. Fix: migrated to `androidx.lifecycle.ProcessLifecycleOwner`
which observes process-level state with built-in at-attach replay.
Late-init now synthesizes `app.start (instrumentation_late)` at install
time, and the observer's at-attach `onStart` provides `app.foreground`
without manual synthesis. Plus a threading fix dispatching `addObserver()`
to the main thread (LifecycleRegistry has an `assertMainThread()` guard
that was throwing silently from the JS bridge thread). Spec:
[`docs/superpowers/specs/2026-04-29-gate1-rn-lifecycle-design.md`](../superpowers/specs/2026-04-29-gate1-rn-lifecycle-design.md).

**Verified 2026-04-29 (Pixel_7):**

```text
events: {'app.start': 1, 'app.foreground': 3, 'app.background': 2}
app.start.type: instrumentation_late
app.start.duration_ms: 699
mobile.background_duration_ms (per fg event): [3384, 3356, 0]
```

The 0ms-bg-duration `app.foreground` is the at-attach replay from
`addObserver()` on the cold launch; the other two are the explicit
bg/fg cycles with realistic ~3.3s gaps.

**Query:**

```bash
dash0 -X logs query \
  --filter "service.name is otel-rn-astronomy-shop and event.name is app.foreground" \
  --from now-5m
```

---

## 2. Gate 2 — Network 🟢 verified 2026-04-29

**Trigger:** Same as RN iOS — `ProductListScreen` fires a delayed
`fetch('https://httpbin.org/get')`.

**Expected:** 1 span only (not 2), from the XHR shim, named
`GET httpbin.org`. Dedup fix in `ba558c2` applies equally to RN
Android — `navigator.product === 'ReactNative'` is true on both
platforms.

**Verified 2026-04-29 (Pixel_7):** 1 span, scope
`io.dash0.mobile.reactnative`, name `GET httpbin.org`,
`http.request.method=GET`, `url.full=https://httpbin.org/get`,
`http.response.status_code=200`. Single span (no XHR/fetch dup) ✓.

**Query:**

```bash
dash0 -X spans query \
  --filter "service.name is otel-rn-astronomy-shop" \
  --from now-5m
```

---

## 3. Gate 3 — Crash 🟢 verified 2026-04-29

**Trigger:** Same red "Trigger Crash (Gate 3)" button from RN iOS.
`setTimeout(() => throw new Error(...), 0)` routes through
`errors.ts` → `Dash0Mobile.log(..., 21)` → `bridge.emitSync` →
native eager `forceFlush`.

**Cross-platform parity (landed 2026-04-25):** the eager-forceFlush-
on-FATAL contract is library-level on both platforms now.

- iOS: commit `39bd258` moved it out of the per-sink `emitLog`
  body into `Dash0MobileBridgeDispatcher`, which calls
  `sink.forceFlush()` after every severity-21 emit.
- Android: commit `60375bd` mirrored the same change in
  `Dash0MobileModule.dispatch`. `BridgeCallSink` interface
  gained `fun forceFlush() = Unit` (default no-op so existing
  implementations compile unchanged); `OTelMobileCallSink`
  overrides to call
  `OTelMobile.getLoggerProvider().getMobileProcessor().forceFlush()`,
  wrapped in try/catch so a flush failure never throws out of
  the dispatcher.

5 unit tests in each platform's dispatcher test class assert the
same invariants: FATAL severity ≥ 21 triggers flush, ERROR (17)
does not, ordering is `emit → forceFlush → next payload`, multiple
FATALs in a batch each get their own flush, and severity range
21..24 all qualify.

**Expected:** 1 `app.error` FATAL log with full exception semconv
(`exception.type`, `exception.message`, `exception.stacktrace`).

**Query:**

```bash
dash0 -X logs query \
  --filter "service.name is otel-rn-astronomy-shop" \
  --filter 'otel.log.body is "app.error"' \
  --from now-5m -o json --limit 5 | python3 -m json.tool
```

---

## 4. Gate 4 — Offline 🟢 verified 2026-04-29 (Dash0 round-trip)

**SDK-side validation 2026-04-28:** logcat shows
`MobileLoggerProvider: Emitted app.recovery_start marker with event_count=N`
firing on the recovery launch after an offline-then-real-endpoint
cycle. The marker emission was added to the Android SDK in
`MobileLoggerProvider.kt` this session and propagated to RN Android
via mavenLocal — same code path that lit Gate 4 green on Android
native. The marker reliably fires, with the count matching the
disk row count.

The Dash0 round-trip (verifying the marker arrives via `dash0 -X logs query`)
was blocked by the export-port mismatch — root-caused 2026-04-29
and fixed in [`endpointForPlatform`](../../examples/upstream-demo-app-rn/AstronomyShopRN/src/otelEndpoint.ts).
On the next re-run of the build commands in §0, this gate's full
evidence should close out automatically because the SDK-level
emission was already proven on 2026-04-28.

**Note:** The span disk-persist gap in `BatchSpanProcessor` was
iOS-specific (and required two design corrections this week —
commits `55cb5d9` HTTPClient interceptor + `4a62d59` current-config
routing). Android's `MobileLogRecordProcessor` + disk buffer
already handle both logs and spans via the dual-tier buffer
(RAM `ConcurrentLinkedQueue` + disk `DiskLogBuffer` Room/SQLite,
50MB / 24h TTL). So Gate 4 **should** be green on RN Android out
of the box, no parity work needed.

Verification procedure (mirrors `android-native.md` Gate 4):

```bash
# 1. Snapshot real config + write invalid variant
cp examples/upstream-demo-app-rn/AstronomyShopRN/otel-config.json /tmp/rn-android-real.json
python3 -c "
import json
c = json.load(open('/tmp/rn-android-real.json'))
c['endpoint'] = 'https://ingress-offline-test.invalid:4317'
json.dump(c, open('/tmp/rn-android-invalid.json','w'), indent=2)"

# 2. Swap to invalid + rebuild bundle + APK + install
cp /tmp/rn-android-invalid.json examples/upstream-demo-app-rn/AstronomyShopRN/otel-config.json
( cd examples/upstream-demo-app-rn/AstronomyShopRN \
    && /opt/homebrew/bin/node node_modules/.bin/react-native bundle \
       --platform android --dev false --entry-file index.js \
       --bundle-output android/app/src/main/assets/index.android.bundle \
       --assets-dest android/app/src/main/res )
( cd examples/upstream-demo-app-rn/AstronomyShopRN/android && ./gradlew :app:assembleRelease )
adb install -r examples/upstream-demo-app-rn/AstronomyShopRN/android/app/build/outputs/apk/release/app-release.apk

# 3. Launch + drive UI ~30s
adb shell am start -n com.dash0.mobile.demo.astronomyshoprn/.MainActivity
sleep 30   # in real session, drive UI to trigger lifecycle, scrolls, taps
adb shell am force-stop com.dash0.mobile.demo.astronomyshoprn

# 4. Inspect disk
adb shell "run-as com.dash0.mobile.demo.astronomyshoprn sqlite3 \
  /data/data/com.dash0.mobile.demo.astronomyshoprn/databases/disk_log_buffer.db \
  'SELECT COUNT(*) FROM buffered_events'"
# Expected: > 0

# 5. Swap back, rebuild, reinstall (preserves /data/data/...), relaunch
cp /tmp/rn-android-real.json examples/upstream-demo-app-rn/AstronomyShopRN/otel-config.json
# repeat steps 2 build commands without uninstall
adb shell am start -n com.dash0.mobile.demo.astronomyshoprn/.MainActivity
sleep 30   # let recovery fire + indexing settle

dash0 -X logs query \
  --filter "service.name is otel-rn-astronomy-shop" \
  --filter 'otel.log.body is "app.recovery_start"' \
  --from now-10m -o json --limit 5 | python3 -m json.tool
```

**Expected:** 1 `app.recovery_start` log with
`dash0.recovery.event_count = N` (matches step 4's sqlite count);
post-recovery sqlite returns 0; recovered logs carry their
original timestamps.

### Mapping notes — Gate 4 RN Android

- The iOS Gate 4 redesigns this week (HTTPClient interceptor +
  current-config routing) DO NOT apply to Android. Android's
  buffer is on the log-processor path which already exports
  through the live exporter at flush time (current config
  semantics for free).
- iOS RN had to add `DiskSpanBuffer` because BSP drops on failure;
  Android's BSP-equivalent goes through the same dual-tier buffer
  and does NOT have that problem.
- See `feedback_replay_routing.md` for the captured-vs-current
  routing lesson if Android ever adds an HTTPClient-level hook.

---

## 5. Known failures / architectural gaps

### Active issues

1. ~~**RN→Dash0 export `gRPC status code 2`.**~~ **Resolved 2026-04-29.**
   Root cause was *transport/port mismatch*, not serialization. The
   shared `otel-config.json` was set to `:4318` (Dash0's OTLP/HTTP
   port) but the **Android SDK exports OTLP/gRPC** via
   `OtlpGrpcLogRecordExporter` and friends — see
   [`MobileLoggerProvider.kt:113`](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/MobileLoggerProvider.kt#L113).
   A gRPC client hitting Dash0's HTTP/protobuf port gets back a
   non-gRPC HTTP response that the gRPC frame layer can't parse, so
   it surfaces as `UNKNOWN` (status code 2) with empty error string.
   The 2026-04-28 hypothesis ("missing/wrong serialization field")
   was wrong — same payload works fine on `:4317`.

   The asymmetry is intentional: **Android SDK speaks OTLP/gRPC
   (`:4317`), iOS SDK speaks OTLP/HTTP (`:4318`)** — see
   [`OTLPExporterFactory.swift:81`](../../otel-ios-mobile/Sources/OTelMobileSDK/Export/OTLPExporterFactory.swift#L81).
   The single shared `otel-config.json` therefore needs per-platform
   port handling. Fix landed in
   [`src/otelEndpoint.ts`](../../examples/upstream-demo-app-rn/AstronomyShopRN/src/otelEndpoint.ts)
   and [`src/App.tsx:33`](../../examples/upstream-demo-app-rn/AstronomyShopRN/src/App.tsx#L33):
   `endpointForPlatform(otelConfig.endpoint)` strips the port the
   user typed and substitutes the right one for the runtime
   platform. Covered by 9 Jest tests in
   [`__tests__/otelEndpoint.test.ts`](../../examples/upstream-demo-app-rn/AstronomyShopRN/__tests__/otelEndpoint.test.ts).
   Gates 2 + 3 should now flip green once the runbook is re-run on
   device — see §2 + §3.

### Architectural gaps

1. Gate 1 AppState shim — same root cause as RN iOS (RN 0.85
   new-arch TurboModule init race). `App.tsx:38` opts out of
   lifecycle auto-capture. Three untried unblock ideas listed in
   `rn-ios.md` Gate 1 — same fixes would apply to both.
2. Gate 3 dispatcher-level eager-flush parity — DONE 2026-04-25.
   iOS landed `39bd258`, Android landed `60375bd`. Both
   `BridgeCallSink` interfaces and dispatchers carry the same
   contract; both have 5 dispatcher unit tests asserting it.
3. Gate 4 SDK marker validated 2026-04-28 (this session). Code
   added to `MobileLoggerProvider.init` to emit `app.recovery_start`
   on disk-buffer-non-empty startup. Replaces Android's prior
   crash/anr-only `app.recovery` semantics.

---

## 6. Session journal

- **2026-04-29 (later same day)** — Gate 1 closed 🟢. ProcessLifecycleOwner
  migration in `instrumentation/lifecycle/.../LifecycleInstrumentation.kt`
  (commit `853b3c1`) plus a follow-up threading fix (`919ca39`) dispatching
  `addObserver()` to main. RN Android matchy-matchy now 4/4 🟢. Same code
  base also closes Gate 1 on RN iOS (verified separately). The threading
  fix surfaced from on-device validation — Robolectric ran the unit tests
  fine because it collapses thread distinctions, but real devices saw the
  `assertMainThread()` IllegalStateException silently terminate the install
  loop. Lesson captured in `feedback_robolectric_main_thread.md`.
- **2026-04-29** — Gates 2 + 3 + 4 all flipped to 🟢 with full Dash0
  round-trip evidence on Pixel_7. Sequence: cleared disk-full block,
  rebuilt RN bundle (`touch index.js` after the `endpointForPlatform`
  fix landed), built+installed Release APK, ran the runbook. Logcat
  confirmed all exports went to `:4317` (vs. the broken `:4318` from
  before the fix). Dash0 evidence: Gate 2 — 1 `GET httpbin.org` span,
  scope `io.dash0.mobile.reactnative`, status 200; Gate 3 —
  `app.crash` log from `error-instrumentation` scope at sev 17 with
  `exception.message: Error: Dash0 RN iOS Gate 3 test crash` plus
  paired `app.error` at sev 21 (FATAL) from RN bridge scope; Gate 4 —
  `app.recovery_start` log with `dash0.recovery.event_count=91` from
  scope `io.opentelemetry.android.mobile.recovery` plus drained
  pre-crash batches via `Crash-mirror: persisted N new RAM events to
  disk → ✅ Export successful (3 logs)`. Single fix
  (`endpointForPlatform`) closed all three gates in one pass; the
  prior session's serialization-bug hypothesis was wrong, port
  mismatch was the only blocker.
- **2026-04-28** — Gate 4 SDK marker emission validated end-to-end
  on RN consumer: `MobileLoggerProvider: Emitted app.recovery_start
  marker with event_count=N` confirmed in logcat after offline →
  recovery cycle. SDK fix in `MobileLoggerProvider.kt` flowed through
  via mavenLocal publish; same code that lit Gate 4 green on Android
  native. Dash0 round-trip blocked by separate `gRPC status code 2`
  RN-export issue (now logged in §5). Section 0's "emulator network
  blocked" claim corrected — the blocker was misdiagnosed ICMP
  filter, not real network breakage. Build-side gradle gotchas
  (Node version pin, JS bundle cache invalidation) added to §0.
- **2026-04-25** — Gate 3 parity work landed: commit `60375bd`
  added `forceFlush()` to the Android `BridgeCallSink` interface
  with a default no-op, dispatcher invokes it after every
  severity ≥ 21 emit, and `OTelMobileCallSink` overrides to call
  `MobileLogRecordProcessor.forceFlush()`. 5 new dispatcher
  tests pass (17/17 module tests total). Closes the iOS-Android
  drift in this code path; on-device Gate 3 validation still
  deferred for the emulator-network reason.
- **2026-04-24** — runbook refreshed: linked the
  android-native.md environment-limitation section, replaced
  speculative `0eed784` JNI reference with concrete `39bd258`
  cross-platform parity work pointer, expanded Gate 4 with the
  exact command sequence mirroring android-native, and noted that
  the iOS Gate 4 redesigns (`55cb5d9`, `4a62d59`) do NOT propagate
  here — Android's buffer model is already correct. On-device
  validation deferred (same emulator-network reason as
  android-native).
- **2026-04-20e** — RN Android demo APK validated in Dash0 for
  basic telemetry flow (see memory
  `project_session_2026_04_20f.md`: "Android RN validated in Dash0,
  SDK gRPC-unified, distro attrs added"). **Not** a four-gate run.
