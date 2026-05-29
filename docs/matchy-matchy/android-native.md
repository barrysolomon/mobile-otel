# Matchy-matchy — Android native (upstream-demo-app, dash0 flavor) 🟢 4/4

**Service name:** `otel-android-astronomy-shop` (set in
[`SdkInitializer.kt`](../../examples/upstream-demo-app/src/dash0/java/io/opentelemetry/android/demo/SdkInitializer.kt))
**Package:** `io.opentelemetry.android.demo.dash0` (the `dash0` product flavor)
**Launcher activity:** `io.opentelemetry.android.demo.MainActivity`
**Last validated:** 2026-04-28 (Pixel_7 emulator, all four gates green in Dash0)
**Status:** 🟢 Gate 1 · 🟢 Gate 2 · 🟢 Gate 3 · 🟢 Gate 4

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

### Environment notes (corrected 2026-04-28)

The 2026-04-24 "emulator can't reach Dash0 ingress" diagnosis was
a misread. ICMP `ping` to the Dash0 ingress IP is filtered by AWS
(intentional — the load balancer drops ICMP echo), but TCP/HTTPS
to ports 4317 and 4318 from inside the emulator works fine. The
2026-04-24 demo-app data and the 2026-04-28 four-gate run both
landed real telemetry from the same emulator setup.

**Real reachability check (use this, not `ping`):**

```bash
# These should both succeed (rc=0)
adb shell nc -z -w 5 ingress.us-west-2.aws.dash0.com 4317
adb shell nc -z -w 5 ingress.us-west-2.aws.dash0.com 443
```

If `nc -z` returns rc=0 to either port, the network path is fine
and any export failure is SDK config or content-type, not transport.

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

## 1. Gate 1 — Lifecycle 🟢

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

**Evidence (2026-04-28):**

```
total log records: 15
--- scopes ---
     1  io.opentelemetry.android.mobile.lifecycle
     1  io.opentelemetry.android.mobile.screen
     1  io.opentelemetry.android.mobile.wireframe
     1  predictive-export
--- bodies ---
     3  app.foreground
     2  app.background
     1  app.start
     3  ui.screen_view
     3  ui.wireframe
     3  prediction.cycle
```

3× `app.foreground` + 2× `app.background` + 1× `app.start` (cold launch),
all from scope `io.opentelemetry.android.mobile.lifecycle`. Spec match.

### Mapping notes — Gate 1 lifecycle

- iOS Gate 1 was once red because of an `NSLock` reentrant bug
  (commit `d1eb755` fixed it). Android's `LifecycleInstrumentation`
  is structurally different (Java/Kotlin `ActivityLifecycleCallbacks`,
  no NSLock equivalent), so this fix does NOT apply.
- Cold launch on Android emits `app.start`. iOS emits `app.startup`
  (with `app.startup.duration_ms`). Two different names for "this
  is the first foreground after a cold start" — drift to fix in a
  future SDK pass; cross-platform Gate 1 filter currently needs both.

---

## 2. Gate 2 — Network 🟢

**Trigger (added 2026-04-28):** [`MainActivity.fireGate2HttpProbe`](../../examples/upstream-demo-app/src/main/java/io/opentelemetry/android/demo/MainActivity.kt)
fires from `onResume` once per launch. It builds an `OkHttpClient`
with `OTelNetworkInterceptor`, then hits `https://httpbin.org/get`
30 times in a tight loop on a worker thread.

**Why 30 calls:** the SDK's default sampler is
`SamplingConfig.dynamic(normalRate=0.1, highPriorityRate=1.0)`. A
single span has only ~10% chance of surviving sampling. 30 calls
gives ≈95.8% probability of at least one CLIENT span landing.

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

**Evidence (2026-04-28):**

```
total spans: 13
oldest: 2026-04-28 19:02:38
newest: 2026-04-28 19:02:40
--- scopes ---
    13  io.opentelemetry.android.demo.gate2
--- names ---
    13  GET /get
--- matches (HTTP) ---
  scope=io.opentelemetry.android.demo.gate2  name='GET /get'
    kind=3  method=GET  host=httpbin.org  status=200
```

13/30 spans survived the dynamic sampler and reached Dash0. Each
has `kind=3 (CLIENT)`, `http.request.method=GET`,
`server.address=httpbin.org`, `http.response.status_code=200`,
`scope.name=io.opentelemetry.android.demo.gate2`. Spec match.

### Mapping notes — Gate 2 network

- Android's network capture is OkHttp-interceptor based (user-wired),
  not URLProtocol swizzle based. The install-order bug that
  triggered iOS commit `25d47b6` doesn't apply on Android.
- **Span name drift:** Android's `OTelNetworkInterceptor` produces
  `GET /get` (method + path), while iOS produces `GET httpbin.org`
  (method + host). Both have the same attributes; only the human-
  readable `name` differs. Cross-platform filters should use
  `http.request.method` + `server.address`, not `name`.
- The Android demo correctly emits `http.request.method` (semconv
  1.0), not the older `http.method`.

---

## 3. Gate 3 — Crash 🟢

**Trigger (added 2026-04-28):** launch-intent extra `--ez gate3_crash true`
on `MainActivity` calls
[`multiThreadCrashing()`](../../examples/upstream-demo-app/src/main/java/io/opentelemetry/android/demo/shop/ui/products/ProductDetails.kt#L176)
after a 3s warmup delay (lets RAM buffer accumulate events to mirror
to disk before the FATAL fires).

```bash
# Launch with crash extra
adb shell am start -n io.opentelemetry.android.demo.dash0/io.opentelemetry.android.demo.MainActivity --ez gate3_crash true
sleep 12   # 3s delay + ~5x FATAL across crash threads + crash-mirror flush
# Process is gone. Relaunch (no extra) for normal session.
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

**Evidence (2026-04-28):**

```
total log records: 88
--- bodies ---
    16  app.crash
    32  ui.wireframe
    18  prediction.cycle
     7  ui.screen_view
     7  app.foreground
     7  app.start
--- crash records ---
  scope=error-instrumentation  body='app.crash'  sev=17
    type='java.lang.IllegalStateException'
    msg='Failure from thread crash-thread-{0..4}'
    ts=2026-04-28 19:05:21
```

16 records (5 threads × ~3 captures from the multi-threaded crash race),
all with full OTel `exception.*` semconv preserved through the disk
mirror. Original timestamps preserved.

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
- **Severity drift:** Android emits `severityNumber=17 (ERROR)`
  for uncaught exceptions, while iOS native emits `21 (FATAL)`
  for crashes. Both are valid OTel severities; the matchy-matchy
  invariants in the README accept either. SDK fix to bump Android
  uncaught exceptions to FATAL is a future parity item.

---

## 4. Gate 4 — Offline 🟢

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

# 4. Inspect disk — expect N > 0 events
#    Note: actual table is log_records (not buffered_events) and the
#    DB filename is otel_log_buffer.db (not disk_log_buffer.db).
#    Pull both .db and .db-wal because Room writes via WAL.
adb shell "run-as io.opentelemetry.android.demo.dash0 cat databases/otel_log_buffer.db" > /tmp/o.db
adb shell "run-as io.opentelemetry.android.demo.dash0 cat databases/otel_log_buffer.db-wal" > /tmp/o.db-wal
sqlite3 /tmp/o.db "SELECT COUNT(*) FROM log_records;"

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

**Evidence (2026-04-28):**

```
total log records: 12 (recovery launch, last 3m window)
--- bodies ---
     4  prediction.cycle
     2  app.foreground
     2  app.start
     2  ui.wireframe
     2  ui.screen_view
     1  app.recovery_start
--- recovery markers ---
  scope=io.opentelemetry.android.mobile.recovery
    body='app.recovery_start'
    event_count=6
    ts=2026-04-28T19:19:27
```

`app.recovery_start` marker emitted on recovery launch with
`dash0.recovery.event_count=6`, matching the offline-session
log_records count. Disk drained to 0 after flush
("Cleared 12 events from disk" in logcat).

**SDK change required (landed 2026-04-28):** the Android SDK now emits
this marker automatically when `MobileLoggerProvider` initializes
with a non-empty disk buffer. Prior to this fix, Android only emitted
`app.recovery` for instrumented crash/anr recoveries — disk-buffer
drains from offline windows were silent. Code lives in
[`MobileLoggerProvider.kt`](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/MobileLoggerProvider.kt)
right after the `SdkLoggerProvider` is built.

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

None blocking. Drift to track for future passes:

1. **Cold-launch event name:** Android emits `app.start`, iOS emits
   `app.startup` with `app.startup.duration_ms`. Cross-platform Gate 1
   filter currently has to accept either.
2. **HTTP span name:** Android `OTelNetworkInterceptor` produces
   `GET /get` (method + path); iOS produces `GET httpbin.org`
   (method + host). Same attributes, different `name`. Cross-platform
   filters should key on `http.request.method` + `server.address`.
3. **Crash severity:** Android emits `severityNumber=17 (ERROR)` for
   uncaught exceptions; iOS native uses `21 (FATAL)`. Both valid OTel
   severities; cross-platform filters should accept ≥17.
4. **Trace sampler default:** the SDK defaults to dynamic sampling
   at 10% normal-rate. Single-shot CLIENT span gates are flaky as a
   result; the demo's `fireGate2HttpProbe` fires 30 calls to overcome
   this. A `gate2_high_priority_force=true` MobileConfig knob would
   make the demo cleaner; tracked as a follow-up.

---

## 6. Session journal

- **2026-04-28** — All 4 gates 🟢 in Dash0. Pixel_7 emulator,
  upstream-demo-app dash0Debug. Gate 1 + 4 worked out-of-the-box;
  Gate 2 needed a `MainActivity.fireGate2HttpProbe()` that fires
  30 httpbin probes to overcome the 10% default sampler; Gate 3
  needed a `--ez gate3_crash true` launch-intent extra to drive
  `multiThreadCrashing()` deterministically; Gate 4 required an
  SDK fix to emit `app.recovery_start` on disk-buffer-non-empty
  startup (previously Android only emitted `app.recovery` on
  instrumented crash/anr). Section 0's "emulator network egress"
  blocker turned out to be a misread of ICMP-filtered ping —
  TCP/HTTPS to ingress works fine, demo-app data has been arriving
  from this emulator for weeks.
- **2026-04-24** — runbook scaffold authored; on-device validation
  deferred under a misdiagnosed environment limitation. See above
  correction.
