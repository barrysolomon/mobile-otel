# Matchy-matchy — RN Android (AstronomyShopRN on Android host) 🟡 TODO

**Service name:** `otel-rn-astronomy-shop` (same app as RN iOS,
different platform → device attributes identify the host)
**Last validated:** never against the four-gate bar
**Status:** 🟡 pending — Jest + demo APK green as of 2026-04-20e,
Dash0-side per-gate evidence not yet captured.

See the RN iOS runbook
([`rn-ios.md`](rn-ios.md)) for the reference shape — most of the
RN-specific architecture (JS-side shims as primary signal source,
`autoCaptureOptions: .none` in native, XHR-authoritative network
capture) applies identically.

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

# Baseline
dash0 -X logs query \
  --filter "service.name is otel-rn-astronomy-shop" \
  --from now-2m -o json | python3 -c 'import json,sys; print(len(json.load(sys.stdin).get("items", [])))'
```

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

## 3. Gate 3 — Crash 🟡 TODO

**Trigger:** Same red "Trigger Crash (Gate 3)" button from RN iOS.
`setTimeout(() => throw new Error(...), 0)` routes through
`errors.ts` → `Dash0Mobile.log(..., 21)` → `bridge.emitSync` →
native eager `forceFlush`.

**Critical check:** confirm that the Android native
`OTelMobileCallSink` equivalent (in the JNI layer) honors the
same eager-forceFlush-on-FATAL contract that
`0eed784` established for iOS. If not, Gate 3 may be red on
Android until a matching commit lands.

**Expected:** 1 `app.error` FATAL log with full exception semconv.

**Query:**

```bash
dash0 -X logs query \
  --filter "service.name is otel-rn-astronomy-shop and event.name is app.error" \
  --from now-5m
```

---

## 4. Gate 4 — Offline 🟡 TODO

**Note:** The span disk-persist gap in `BatchSpanProcessor` is
iOS-specific — Android's `MobileLogRecordProcessor` + disk buffer
handle both logs and spans via the dual-tier buffer (RAM
`ConcurrentLinkedQueue` + disk `DiskLogBuffer` Room/SQLite, 50MB /
24h TTL). So Gate 4 **should** be green on RN Android out of the
box.

Verify by:
1. Config-swap to unreachable endpoint, rebuild + install APK.
2. Drive UI ~30s.
3. `adb shell "run-as … sqlite3 …/disk_log_buffer.db 'SELECT COUNT(*)'"`
   → expect > 0.
4. Swap back, reinstall (APK install does NOT clear
   `/data/data/...`), relaunch.
5. Query for `app.recovery_start`.

---

## 5. Known failures / architectural gaps

- Gate 1 AppState shim — same root cause as RN iOS. Not
  RN-iOS-specific, likely affects RN Android too.
- Gate 3 eager-flush contract on Android native may or may not
  exist; verify before trusting.
- Gate 4 expected to work for free (Android buffer covers spans
  too, unlike iOS).

---

## 6. Session journal

- **2026-04-20e** — RN Android demo APK validated in Dash0 for
  basic telemetry flow (see memory
  `project_session_2026_04_20f.md`: "Android RN validated in Dash0,
  SDK gRPC-unified, distro attrs added"). **Not** a four-gate run.
- **TODO** — pick up this runbook in a dedicated RN Android
  validation session and replace this placeholder with evidence.
