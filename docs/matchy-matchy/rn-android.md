# Matchy-matchy — RN Android (AstronomyShopRN on Android host) 🟡 SCAFFOLD

**Service name:** `otel-rn-astronomy-shop` (same app as RN iOS,
different platform → device attributes identify the host)
**Package:** `com.dash0.mobile.demo.astronomyshoprn`
**Last validated:** never end-to-end against the four-gate bar
**Status:** 🟡 scaffold — Jest + demo APK build verified
2026-04-20e; on-device Dash0 validation deferred for the same
emulator-network reason documented in [`android-native.md`](android-native.md#-environment-limitation-discovered-2026-04-24).

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

### ⚠ Environment limitation (shared with android-native)

The Android emulator on Barry's machine cannot reach the Dash0
ingress IP from the emulator NAT, even though the host (where
`dash0` CLI runs fine and iOS Simulator works end-to-end) and
8.8.8.8 are both reachable. This is a per-emulator network-config
problem rather than infrastructure — see
[`android-native.md` §0](android-native.md#-environment-limitation-discovered-2026-04-24)
for the full diagnosis and workaround steps. Same workarounds apply
to RN Android validation.

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

## 1. Gate 1 — Lifecycle 🟡 TODO

**Note:** same architectural question as RN iOS —
`AstronomyShopRN/src/App.tsx:38` disables `autoCapture.lifecycle`
at the JS layer. RN Android's `nativeAutoCapture` tokens may
differ from iOS; investigate whether Android native's
`LifecycleInstrumentation` can be safely enabled in parallel with
the JS shim without double-emission.

**Expected:** 3×`app.foreground` + 2×`app.background`. **Actual:**
TODO.

**Query:**

```bash
dash0 -X logs query \
  --filter "service.name is otel-rn-astronomy-shop and event.name is app.foreground" \
  --from now-5m
```

---

## 2. Gate 2 — Network 🟡 TODO

**Trigger:** Same as RN iOS — `ProductListScreen` fires a delayed
`fetch('https://httpbin.org/get')`.

**Expected:** 1 span only (not 2), from the XHR shim, named
`GET httpbin.org`. Dedup fix in `ba558c2` applies equally to RN
Android — `navigator.product === 'ReactNative'` is true on both
platforms.

**Query:**

```bash
dash0 -X spans query \
  --filter "service.name is otel-rn-astronomy-shop" \
  --from now-5m
```

---

## 3. Gate 3 — Crash 🟡 TODO (likely 🟢 after 2026-04-24)

**Trigger:** Same red "Trigger Crash (Gate 3)" button from RN iOS.
`setTimeout(() => throw new Error(...), 0)` routes through
`errors.ts` → `Dash0Mobile.log(..., 21)` → `bridge.emitSync` →
native eager `forceFlush`.

**Cross-platform note (2026-04-24):** the eager-forceFlush-on-FATAL
contract is now LIBRARY-LEVEL on iOS — commit `39bd258` moved it
out of the per-sink `emitLog` body and into
`Dash0MobileBridgeDispatcher`, which calls `sink.forceFlush()`
after every severity-21 emit. The protocol method has a default
no-op extension, so production sinks override and
test/lightweight sinks inherit safe behavior.

The RN Android bridge dispatcher should mirror this: after
`sink.emitLog(...)` for FATAL (severity ≥ 21), call
`sink.forceFlush()` before continuing the batch. **Verify this is
in the Java/Kotlin equivalent of `Dash0MobileBridgeDispatcher` on
the Android side; if not, that's the parity work to land before
Gate 3 can pass on RN Android.**

Look for: `packages/react-native/android/.../Dash0MobileModule.kt`
or similar. The Android sink protocol is `BridgeCallSink` (Kotlin
interface). Add a `fun forceFlush()` with default `= Unit` and
have the production `OTelMobileCallSink` override it to call
`OTelMobile.forceFlush()`.

**Expected (after parity work):** 1 `app.error` FATAL log with
full exception semconv (`exception.type`, `exception.message`,
`exception.stacktrace`).

**Query:**

```bash
dash0 -X logs query \
  --filter "service.name is otel-rn-astronomy-shop" \
  --filter 'otel.log.body is "app.error"' \
  --from now-5m -o json --limit 5 | python3 -m json.tool
```

---

## 4. Gate 4 — Offline 🟡 TODO (likely 🟢 for free)

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

### Environment limitations

1. Emulator network egress to Dash0 ingress IP — see §0.

### Architectural gaps to verify (not yet known to fail)

1. Gate 1 AppState shim — same root cause as RN iOS. Not
   RN-iOS-specific, likely affects RN Android too. Investigation
   tracked in the iPhone-branch session journal under "Gate 1
   unblock investigation."
2. Gate 3 dispatcher-level eager-flush parity — iOS dispatcher
   gained `sink.forceFlush()` invocation in `39bd258`; Android
   needs the matching change in its Java/Kotlin
   `Dash0MobileBridgeDispatcher` equivalent before Gate 3 will
   pass on RN Android.
3. Gate 4 expected to work for free (Android dual-tier buffer
   covers both logs and spans, unlike iOS BSP).

---

## 6. Session journal

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
