# Matchy-matchy — Android native (demo-app / upstream-demo-app) 🟡 TODO

**Service name:** `otel-android-astronomy-shop`
**Last validated:** never against the four-gate bar
**Status:** 🟡 pending — broader `HOW_TO_DEMO.md` runbook exists but is
not keyed to the four gates; needs alignment.

Related existing runbook:
[`../../HOW_TO_DEMO.md`](../../HOW_TO_DEMO.md) covers the full 12-min
demo across two emulators (18 scenario tests × 2 = 36 test runs).
That runbook is deeper in scope but doesn't produce the single,
comparable four-gate evidence this matchy-matchy series wants.

Once the picker-upper runs this, update
[`../epics/VALIDATION_MATRIX_EPIC.md`](../epics/VALIDATION_MATRIX_EPIC.md)
row 1 from 🟡 to 🟢 with commit references.

See the iOS native runbook
([`ios-native.md`](ios-native.md)) for the reference shape. Android
mapping notes below.

---

## 0. Pre-flight

```bash
# Boot a representative emulator
nohup emulator -avd Pixel_7 -no-snapshot-save > /tmp/emu1.log 2>&1 &
adb wait-for-device
until adb shell "getprop sys.boot_completed" 2>/dev/null | grep -q 1; do sleep 5; done

# Dash0 profile
dash0 config profiles activate mobile-test

# Baseline
dash0 -X logs query \
  --filter "service.name is otel-android-astronomy-shop" \
  --from now-2m -o json | python3 -c 'import json,sys; print(len(json.load(sys.stdin).get("items", [])))'
```

### Demo app configuration

- `examples/upstream-demo-app/` uses OTelMobile 1.2.0-alpha (see
  memory `project_session_2026_04_09b.md`). The Android equivalent
  of iOS's `samplingConfig: .alwaysOn()` and `pokeBackend()` may
  need to be added; this session's TODO.
- Gate 3 crash trigger on Android is typically a `RuntimeException`
  from an app action (see `demo-control-center.sh`) or a matching
  launch-arg mechanism if you want it automated.

---

## 1. Gate 1 — Lifecycle 🟡 TODO

**Trigger (draft):** Activity lifecycle — cold launch + two
background↔foreground cycles via `adb`:

```bash
# Launch
adb shell am start -n io.opentelemetry.android.demo/.SchedulingActivity
# Background
adb shell input keyevent KEYCODE_HOME
# Foreground (brings back to front)
adb shell am start -n io.opentelemetry.android.demo/.SchedulingActivity
# Repeat...
```

**Expected:** 3×`app.foreground` + 2×`app.background` + launch.

**Query:**

```bash
dash0 -X logs query \
  --filter "service.name is otel-android-astronomy-shop and event.name is app.foreground" \
  --from now-5m
```

**Evidence:** `TODO — capture after run`.

**Mapping notes:** Android's `LifecycleInstrumentation` (Kotlin) is
structurally different from iOS's NSLock-reentrant-bug version — the
iOS `d1eb755` fix is iOS-only. Android likely green "for free" but
unverified against this bar.

---

## 2. Gate 2 — Network 🟡 TODO

**Trigger:** The demo uses `OTelNetworkInterceptor` on an OkHttp
client (see `otel-android-mobile/instrumentation/network/`). Add a
`pokeBackend` equivalent to AstronomyShop Android that fires
`GET https://httpbin.org/get` on home-view appearance.

**Expected:** 1 span, `name=GET`, `kind=CLIENT`, same attributes as
iOS native (`http.request.method`, `server.address`,
`http.response.status_code`, `url.full`), scope `io.dash0.mobile`.

**Query:**

```bash
dash0 -X spans query \
  --filter "service.name is otel-android-astronomy-shop and http.request.method is GET" \
  --from now-5m
```

**Evidence:** `TODO`.

**Mapping notes:** Android's network capture is OkHttp-interceptor
based (user-wired), not swizzle based. The install-order bug that
caused iOS `25d47b6` doesn't apply. But confirm attribute
key/casing parity — Android uses the same semconv.

---

## 3. Gate 3 — Crash 🟡 TODO

**Trigger:** Uncaught `RuntimeException` via the existing
crash-demo button, or a new launch-arg hook mirroring iOS's
`-DASH0_CRASH_NOW`. See `scripts/test/demo-control-center.sh` —
the existing crash recovery demo tests a broader surface but not
the specific four-gate shape.

**Expected:** 1 `app.crash` FATAL log with `crash.from_marker=true`
on the recovery launch.

**Query:**

```bash
dash0 -X logs query \
  --filter "service.name is otel-android-astronomy-shop and event.name is app.crash" \
  --from now-5m
```

**Evidence:** `TODO`.

**Mapping notes:** Android `ErrorInstrumentation` has dedup (5-min
window) and rate-limiting (10/min). Ensure test trigger doesn't
get swallowed by rate limiter between successive runs.

---

## 4. Gate 4 — Offline 🟡 TODO

**Procedure:** Config-swap `otel-config.json` to an unreachable
endpoint, walk the app, swap back, query for `app.recovery_start`.
Same pattern as iOS native.

**Expected:** 1 `app.recovery_start` with
`dash0.recovery.event_count = N`, N events with original
timestamps, post-recovery disk row count = 0.

**Mapping notes:** Android's dual-tier buffer is RAM
`ConcurrentLinkedQueue` (5000 events) + disk `DiskLogBuffer`
(Room/SQLite v4, 50MB, 24h TTL). The iOS `1a69c7e` fail-persist +
auto-forceFlush-on-background design is **already** how Android
works — Android was the reference, iOS needed to catch up. So
Gate 4 is likely green for free. Verify.

Check the disk buffer directly:

```bash
adb shell "run-as io.opentelemetry.android.demo sqlite3 \
  /data/data/io.opentelemetry.android.demo/databases/disk_log_buffer.db \
  'SELECT COUNT(*) FROM buffered_events'"
```

---

## 5. Known failures / architectural gaps

None documented. This is a fresh validation run — gaps will emerge
only during the run.

---

## 6. Session journal

- **TODO** — this runbook is a placeholder. Fill in after next
  Android four-gate validation session. Update epic row 1 and
  matrix README status on completion.
