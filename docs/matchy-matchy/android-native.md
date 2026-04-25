# Matchy-matchy — Android native (upstream-demo-app, dash0 flavor) 🟡 SCAFFOLD

**Service name:** `otel-android-astronomy-shop` (set in
[`SdkInitializer.kt`](../../examples/upstream-demo-app/src/dash0/java/io/opentelemetry/android/demo/SdkInitializer.kt))
**Package:** `io.opentelemetry.android.demo.dash0` (the `dash0` product flavor)
**Launcher activity:** `io.opentelemetry.android.demo.MainActivity`
**Last validated:** never end-to-end against the four-gate bar
**Status:** 🟡 scaffold — runbook is fully fleshed but on-device
validation deferred pending an emulator with working egress to
the Dash0 ingress IP. See §0 *Environment limitation* below.

Related existing runbook: [`../../HOW_TO_DEMO.md`](../../HOW_TO_DEMO.md)
covers the full 12-min demo across two emulators (18 scenario
tests × 2 = 36 test runs). Deeper in scope but not keyed to the
four gates. This file produces the single, comparable four-gate
evidence the matchy-matchy series wants.

After running this and getting all 4 green, update
[`../epics/VALIDATION_MATRIX_EPIC.md`](../epics/VALIDATION_MATRIX_EPIC.md)
row 1 from 🟡 to 🟢 with commit references.

Reference shape: [`ios-native.md`](ios-native.md) (canonical 4/4 🟢
runbook). Android-specific mapping notes in each gate section.

---

## 0. Pre-flight

```bash
# Boot the emulator (Pixel_7 is the canonical AVD; CLAUDE.md options)
nohup emulator -avd Pixel_7 -no-snapshot-save > /tmp/emu1.log 2>&1 &
adb wait-for-device
until adb shell "getprop sys.boot_completed" 2>/dev/null | grep -q 1; do sleep 5; done

# Dash0 profile + auth
dash0 config profiles activate mobile-test

# Baseline — confirm zero spans for our service before launch
dash0 -X spans query \
  --filter "service.name is otel-android-astronomy-shop" \
  --from now-2m -o json | python3 -c '
import json, sys
d = json.load(sys.stdin)
n = sum(len(s.get("spans", [])) for r in d.get("resourceSpans", []) for s in r.get("scopeSpans", []))
print(f"baseline spans: {n}")'
```

### ⚠ Environment limitation discovered 2026-04-24

The first attempt at running this matchy-matchy was blocked by
emulator network egress: the booted emulator could `ping 8.8.8.8`
but could NOT reach the Dash0 ingress IP (`35.160.215.182`) — the
gRPC exporter timed out on every export attempt. The host machine
(where `dash0` CLI runs fine and iOS Simulator works end-to-end)
is unaffected, so this is a per-emulator network-config problem
rather than infrastructure.

**Symptoms to watch for** (these tell you the same thing is happening):

```bash
adb logcat | grep GrpcExporter
# E GrpcExporter: Failed to export <signal>. The request could not be executed. Error message: timeout
# E GrpcExporter: java.io.InterruptedIOException: timeout

adb shell ping -c 1 35.160.215.182   # times out (host pings fine)
adb shell ping -c 1 8.8.8.8          # works (basic Internet OK)
```

**Workarounds to try in order:**

1. Restart adb + reboot the emulator with explicit DNS:
   `emulator -avd Pixel_7 -no-snapshot-save -dns-server 8.8.8.8`
2. Disable Pixel_7's snapshot AVD network state and retry from cold.
3. Try `Medium_Phone_API_36.1` AVD instead (different network config).
4. If only the SPECIFIC IP is filtered (not all AWS), try a non-AWS
   Dash0 region or the `prod` profile.
5. Last resort: build with the `upstream` flavor pointed at a local
   collector forwarder and validate against that — checks the SDK
   path even if the live Dash0 ingest leg is uncovered.

When this is unblocked, run §1–4 below in order and capture the
evidence inline. Each gate has its expected query shape and
mapping note already filled in; the only TODO is "post-run
evidence" snippets.

### Demo app configuration (pre-wired)

The dash0 flavor already sets:

- `service.name = otel-android-astronomy-shop` ([`SdkInitializer.kt:23`](../../examples/upstream-demo-app/src/dash0/java/io/opentelemetry/android/demo/SdkInitializer.kt))
- `service.version = 0.1.0`
- `endpoint = ExportConfig.grpcEndpoint` (gRPC, port 4317)
- `mode = ExportMode.CONTINUOUS`
- `headers = ExportConfig.headers` (Bearer auth + `Dash0-Dataset: otel-mobile`)
- `instrumentations { discoverAll() }` — every auto-capture enabled

Endpoint + auth come from
[`src/main/assets/otel-config.json`](../../examples/upstream-demo-app/src/main/assets/otel-config.json).

### Build

```bash
cd examples/demo-app
./gradlew :upstream-demo-app:assembleDash0Debug
# APK lands at examples/upstream-demo-app/build/outputs/apk/dash0/debug/upstream-demo-app-dash0-debug.apk
adb -s <serial> install -r ../upstream-demo-app/build/outputs/apk/dash0/debug/upstream-demo-app-dash0-debug.apk
```

The `dash0` flavor uses the project-reference path to our SDK, so
fixes to `otel-android-mobile/` flow into this APK. The `upstream`
flavor uses published Maven artifacts only — useful for a
"baseline upstream behavior" comparison run if a gate disagrees.

---

## 1. Gate 1 — Lifecycle 🟡 TODO (likely 🟢 for free)

**Trigger:** cold launch + two background↔foreground cycles via
`adb`. The Android SDK's `LifecycleInstrumentation` auto-installs
via `instrumentations { discoverAll() }` and emits on every
Activity lifecycle transition.

```bash
adb shell am start -n io.opentelemetry.android.demo.dash0/io.opentelemetry.android.demo.MainActivity
sleep 3
adb shell input keyevent KEYCODE_HOME           # background
sleep 2
adb shell am start -n io.opentelemetry.android.demo.dash0/io.opentelemetry.android.demo.MainActivity   # foreground
sleep 2
adb shell input keyevent KEYCODE_HOME           # background again
sleep 2
adb shell am start -n io.opentelemetry.android.demo.dash0/io.opentelemetry.android.demo.MainActivity   # final foreground
```

**Expected:** ≥3× `app.foreground` + ≥2× `app.background` log
records. Cold launch also produces an `app.startup` log on iOS
with `app.startup.duration_ms`; Android's equivalent is in
`VitalsInstrumentation` and emits an `app.start` log with cold/warm
distinction.

**Query:**

```bash
dash0 -X logs query \
  --filter "service.name is otel-android-astronomy-shop" \
  --from now-5m -o json --limit 50 \
  | python3 -c '
import json, sys
from collections import Counter
d = json.load(sys.stdin)
events = Counter()
for r in d.get("resourceLogs", []):
    for s in r.get("scopeLogs", []):
        for lr in s.get("logRecords", []):
            for a in lr.get("attributes", []):
                if a["key"] == "event.name":
                    events[a["value"].get("stringValue","?")] += 1
            body = lr.get("body",{}).get("stringValue","")
            if body in ("app.foreground", "app.background", "app.startup", "app.start"):
                events[body] += 1
for e, c in events.most_common():
    print(f"  {c:3d}  {e}")'
```

**Evidence:** TODO — capture after run.

### Mapping notes — Gate 1 lifecycle

- iOS Gate 1 was once red because of an `NSLock` reentrant bug
  (commit `d1eb755` fixed it). Android's `LifecycleInstrumentation`
  is structurally different (Java/Kotlin `ActivityLifecycleCallbacks`,
  no NSLock equivalent), so this fix does NOT apply.
- Likely 🟢 for free, but unverified against this exact shape.

---

## 2. Gate 2 — Network 🟡 TODO (likely 🟢 for free)

**Trigger:** the dash0 flavor's `NetworkInstrumentation` is wired
via OkHttp interceptor. The demo's `ShopTelemetry` calls (or any
HTTP poke) produces a CLIENT span.

If a `pokeBackend()` equivalent doesn't exist on Android: easiest
is to add a one-liner OkHttp call to `MainActivity.onResume`
hitting `https://httpbin.org/get`. iOS RN does this in
[`App.tsx:fetch('https://httpbin.org/get')`](../../examples/upstream-demo-app-rn/AstronomyShopRN/src/App.tsx);
the Android equivalent should match for cross-platform comparison.

**Expected:** 1 span. `name = GET httpbin.org` (matches iOS shape),
`kind = CLIENT`, `http.request.method = GET`, `server.address = httpbin.org`,
`http.response.status_code = 200`, `url.full = https://httpbin.org/get`,
scope `io.dash0.mobile`.

**Query:**

```bash
dash0 -X spans query \
  --filter "service.name is otel-android-astronomy-shop" \
  --filter "http.request.method is GET" \
  --from now-5m -o json --limit 5 \
  | python3 -c '
import json, sys
d = json.load(sys.stdin)
for r in d.get("resourceSpans", []):
    for s in r.get("scopeSpans", []):
        scope = s.get("scope", {}).get("name", "?")
        for sp in s.get("spans", []):
            attrs = {a["key"]: a["value"].get("stringValue") or a["value"].get("intValue") for a in sp.get("attributes", [])}
            print(f"name={sp.get(\"name\")!r} kind={sp.get(\"kind\",\"?\")} scope={scope} status={attrs.get(\"http.response.status_code\")}")'
```

**Evidence:** TODO — capture after run.

### Mapping notes — Gate 2 network

- Android's network capture is OkHttp-interceptor based (user-wired),
  not URLProtocol swizzle based. The install-order bug that
  triggered iOS commit `25d47b6` doesn't apply on Android.
- Verify attribute keys/casing match iOS — both should use the same
  semconv (`http.request.method`, `server.address`, etc.).
- If the Android demo emits the older `http.method` (semconv pre-1.0)
  instead of `http.request.method`, treat that as a separate
  follow-up — mark this gate 🟡 with the rationale.

---

## 3. Gate 3 — Crash 🟡 TODO

**Trigger:** the existing crash-demo flow (see
[`scripts/test/demo-control-center.sh`](../../scripts/test/demo-control-center.sh))
fires a `RuntimeException` via the in-app crash button. For the
matchy-matchy procedure, a deterministic launch-arg hook would be
preferable — mirror iOS's `-DASH0_CRASH_NOW` mechanism. If that
hook doesn't exist yet, the manual UI tap flow works:

```bash
# Launch
adb shell am start -n io.opentelemetry.android.demo.dash0/io.opentelemetry.android.demo.MainActivity
sleep 3
# Tap the "Trigger Crash" button (UI-driven; coordinates depend on screen)
# OR use uiautomator: adb shell uiautomator runtest ... (overkill for this; manual is fine)

# After crash, relaunch — recovery should fire
adb shell am force-stop io.opentelemetry.android.demo.dash0   # cleanup just in case
adb shell am start -n io.opentelemetry.android.demo.dash0/io.opentelemetry.android.demo.MainActivity
```

**Expected:**

- 1 `app.crash` FATAL log on the **next** launch carrying the
  recovered crash payload, with `crash.from_marker = true`.
- The exception attributes are present: `exception.type`,
  `exception.message`, `exception.stacktrace`.
- `crash.timestamp` matches the time of the original tap.

**Query:**

```bash
dash0 -X logs query \
  --filter "service.name is otel-android-astronomy-shop" \
  --filter 'otel.log.body is "app.crash"' \
  --from now-10m -o json --limit 5 | python3 -m json.tool
```

**Evidence:** TODO — capture after run.

### Mapping notes — Gate 3 crash

- Android `ErrorInstrumentation` has 5-min dedup window and
  10/min rate limiting. Successive matchy-matchy runs within 5
  minutes might see the second crash dropped — wait or use a
  different exception message between runs.
- The iOS Gate 3 fix chain (`4399e7a` JS emitSync + `0eed784`
  native eager forceFlush) was for **RN-specific** abort()/_exit()
  termination paths. On Android native, the crash path is
  uncaught-exception handler → write crash marker to disk →
  next launch reads marker → emits `app.crash`. No analogous
  bridge race.
- If the demo's crash button uses `Thread.currentThread().interrupt()`
  or another non-throwing termination, the FATAL log path may not
  fire. Confirm the trigger actually throws.

---

## 4. Gate 4 — Offline 🟡 TODO (likely 🟢 for free)

**Procedure:** swap `otel-config.json` to an unreachable endpoint,
walk the app for 30s, terminate, swap back, relaunch, query for
`app.recovery_start`. Same shape as iOS native.

```bash
# 1. Snapshot real config + write invalid variant
cp examples/upstream-demo-app/src/main/assets/otel-config.json /tmp/android-otel-real.json
python3 -c "
import json
c = json.load(open('/tmp/android-otel-real.json'))
c['endpoint'] = 'https://ingress-offline-test.invalid:4317'
json.dump(c, open('/tmp/android-otel-invalid.json', 'w'), indent=2)"

# 2. Swap to invalid + rebuild + reinstall
cp /tmp/android-otel-invalid.json examples/upstream-demo-app/src/main/assets/otel-config.json
( cd examples/demo-app && ./gradlew :upstream-demo-app:assembleDash0Debug )
adb install -r examples/upstream-demo-app/build/outputs/apk/dash0/debug/upstream-demo-app-dash0-debug.apk

# 3. Launch + drive UI for ~30s
adb shell am start -n io.opentelemetry.android.demo.dash0/io.opentelemetry.android.demo.MainActivity
sleep 30   # in real session, drive UI to trigger lifecycle, scrolls, taps
adb shell am force-stop io.opentelemetry.android.demo.dash0

# 4. Inspect disk — expect N > 0 buffered events
adb shell "run-as io.opentelemetry.android.demo.dash0 sqlite3 \
  /data/data/io.opentelemetry.android.demo.dash0/databases/disk_log_buffer.db \
  'SELECT COUNT(*) FROM buffered_events'"

# 5. Swap back + rebuild + relaunch + query Dash0
cp /tmp/android-otel-real.json examples/upstream-demo-app/src/main/assets/otel-config.json
( cd examples/demo-app && ./gradlew :upstream-demo-app:assembleDash0Debug )
adb install -r examples/upstream-demo-app/build/outputs/apk/dash0/debug/upstream-demo-app-dash0-debug.apk
adb shell am start -n io.opentelemetry.android.demo.dash0/io.opentelemetry.android.demo.MainActivity
sleep 30   # let recovery fire + indexing settle

dash0 -X logs query \
  --filter "service.name is otel-android-astronomy-shop" \
  --filter 'otel.log.body is "app.recovery_start"' \
  --from now-10m -o json --limit 5 | python3 -m json.tool
```

**Expected:**

- 1 `app.recovery_start` log on the recovery launch with
  `dash0.recovery.event_count = N` (matches step 4's sqlite count).
- Post-recovery: same sqlite query returns 0.
- Original event timestamps preserved on the recovered logs.

**Evidence:** TODO — capture after run.

### Mapping notes — Gate 4 offline

- Android's dual-tier buffer (RAM `ConcurrentLinkedQueue` 5000 +
  Room/SQLite 50MB / 24h) was the **reference design** for iOS's
  `1a69c7e` fail-persist + auto-forceFlush-on-background. Both
  platforms now share the same model.
- iOS commit `1a69c7e` was specifically about LOG fail-persist;
  iOS spans needed the separate Gate 4 redesign (commits
  `55cb5d9` HTTPClient interceptor + `4a62d59` current-config
  routing). On Android, **both logs and spans use the same
  buffer pipeline already**, so Gate 4 is likely 🟢 for free.
- The iOS `2026-04-24` redesign lesson (replay routes through
  current config, not captured-at-failure) DOES apply to Android
  if/when Android adds span-level fail-persist with a similar
  HTTPClient hook. The `MobileLogRecordProcessor` on Android
  already uses the current exporter at flush time; check that the
  exporter's endpoint comes from the live config object, not a
  snapshot taken at process start.

---

## 5. Known failures / architectural gaps

### Environment limitations

1. **Emulator network egress to Dash0 ingress IP.** Surfaced
   2026-04-24. Workaround attempts documented in §0. Until
   resolved, on-device validation cannot complete.

### Architectural gaps to verify (not yet known to fail)

1. Gate 2 attribute parity (semconv 1.0 `http.request.method` vs
   pre-1.0 `http.method`). Confirm during run.
2. Gate 3 dedup window may swallow rapid successive runs. Document
   the pattern that works during run.

---

## 6. Session journal

- **2026-04-24 (this session)** — runbook fully scaffolded with
  Android-specific package name, build commands, gate triggers,
  expected attribute shapes, and platform-difference mapping notes
  vs. iOS native + iOS RN. On-device validation deferred:
  emulator could not reach Dash0 ingress IP from the emulator NAT
  even though the host (where `dash0` CLI works) and 8.8.8.8 ping
  both succeed. Documented as an environment issue in §0 with
  workaround steps. When unblocked, fill in §1–4 evidence and
  flip the matrix epic row to 🟢.
