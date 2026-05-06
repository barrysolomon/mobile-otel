# Solutions Architect Learning Guide — Dash0 Mobile Observability

**Audience:** A solutions architect (you) preparing to demo, defend, and teach this product to customers, engineering teams, and prospects. Assumes you understand HTTP, OTel concepts at a 30,000-foot level, and Android/iOS/React Native at a "I know what these are" level.

**Goal:** By the end of Module 5 you can confidently answer: *"How does this thing work? Why did you build it this way? How do I demo it? How do I troubleshoot when it breaks?"*

---

## How to use this guide

Each module is **~30–60 minutes**, self-paced, and ends with **defend-it questions** you should be able to answer cold. Skip around — but Module 1 is foundational; everything else assumes it.

| Module | Focus | When to read |
|---|---|---|
| 1. The 5-minute mental model | The whole system in one diagram | First. Always. |
| 2. Defend the design | Why this product exists, key tradeoffs | Before any sales call |
| 3. Code tour | How a single telemetry record flows through the SDK | When you want to teach a customer |
| 4. Demo runbook | Step-by-step, with talking points | Day before a demo |
| 5. Troubleshooting playbook | "Telemetry isn't flowing, what's wrong?" | When something breaks live |
| 6. Going deeper | Pointers into the existing docs | Reference, not linear reading |

---

## Module 1 — The 5-minute mental model

### What this product *is*

**Dash0 Mobile Observability** is an SDK + collector pipeline that captures user behavior, errors, network calls, and device health from mobile apps and forwards them to the Dash0 backend in OTel-native format. Three platforms today: native Android, native iOS, React Native (which wraps the natives).

### The single architecture diagram

```
┌─────────────────────────────────────────────────────────────┐
│  Mobile app (Android / iOS / React Native)                  │
│                                                             │
│  ┌─────────────────────────┐    ┌──────────────────────┐   │
│  │ 11 instrumentation      │ →  │ Dual-tier buffer     │   │
│  │ modules                 │    │ (RAM + SQLite/disk)  │   │
│  │ tap, scroll, lifecycle, │    │                      │   │
│  │ network, errors,        │    │ - 5000 RAM events    │   │
│  │ vitals, screen, ...     │    │ - 50MB disk, 24h TTL │   │
│  └─────────────────────────┘    └──────────┬───────────┘   │
│                                            │                │
│                                  ┌─────────▼────────────┐  │
│                                  │ Policy DSL evaluator │  │
│                                  │ (when to flush)      │  │
│                                  └─────────┬────────────┘  │
│                                            │                │
│                                  ┌─────────▼────────────┐  │
│                                  │ OTLP/gRPC exporter   │  │
│                                  └─────────┬────────────┘  │
└────────────────────────────────────────────┼────────────────┘
                                             │
                                       OTLP over gRPC :4317
                                             │
                ┌────────────────────────────▼──────────────────┐
                │  OTEL Collector + mobilepolicyprocessor       │
                │  (server-side policy enforcement, redaction)  │
                └────────────────────────────┬──────────────────┘
                                             │
                ┌────────────────────────────▼──────────────────┐
                │  Dash0 backend (logs / spans / metrics UI)    │
                └───────────────────────────────────────────────┘

  ┌──────────────────────────────────────┐
  │ Control Plane (separate repo)        │
  │ - React UI for policy editor         │
  │ - Go gateway: stores DSL, serves to  │
  │   SDKs via /v1/policies/<deviceId>   │
  └──────────────────────────────────────┘
                │
                │ SDKs poll for new policies
                ▼ (every N minutes)
  back to the SDK on each app
```

### The four words you need to know

| Word | Means |
|---|---|
| **Telemetry** | A unit of observability data. Three kinds: **logs** (events with attributes), **spans** (timed operations like HTTP calls), **metrics** (numbers like memory used). |
| **Buffer** | The on-device store of telemetry waiting to be exported. Two tiers: RAM (fast, lost on crash) → disk (slow, survives crash). |
| **Policy** | A rule that says *when* to flush the buffer. Default policies fire on `app.crash`, `ui.freeze`, `http.error`. |
| **Export mode** | A top-level setting that controls *how* the buffer drains. Three modes — see Module 2. |

### What makes this different

There are three things this product does that nobody else in the mobile observability space does the same way. **You will be asked about all three.**

1. **OTel-native end to end.** Every record is OTLP/gRPC. No proprietary format. No vendor lock-in at the wire format. Datadog and Splunk MRUM emit proprietary formats and bridge to OTel only at the backend.
2. **Selective flush via policy DSL.** Unlike "always send" (Datadog) or "send on shake" (Sentry), you can define *what conditions* trigger an export window. Battery-efficient — you can run continuous instrumentation without continuous network.
3. **Crash-survivable disk buffer.** Events are mirrored RAM → SQLite every 2 seconds. A crash takes the process out, but the next launch finds the disk events and emits an `app.recovery_start` marker so you can correlate. Datadog/Sentry achieve this; the difference is ours is the *same* buffer that the policy engine drains, not a separate crash pipeline.

### Defend-it questions

- *Q: Why OTel-native? What if a customer doesn't use OTel anywhere else?*
  A: OTLP is one of the few wire formats that lives outside any one vendor. Even if the customer uses Splunk today, going OTel-native means their mobile data isn't trapped in our format if they migrate. It's a portability story, not a "you must adopt OTel" story.
- *Q: Why is the buffer dual-tier? Why not just disk?*
  A: SQLite writes cost ~1 ms; RAM is ~100 ns. For a flush of 100 events, that's the difference between 100 ms (noticeable jank) and ~10 µs. RAM is the hot path; disk is the safety net.
- *Q: What if the device runs out of disk?*
  A: 50 MB hard cap (configurable) and 24 h TTL. Old events drop on FIFO when full. We never block the app on disk pressure.

---

## Module 2 — Defend the design

This module is the rationale layer. Read once. Re-read before any prospect call.

### Why three export modes?

| Mode | Behavior | When to use it |
|---|---|---|
| **CONTINUOUS** | Periodic flush every 30 s (logs) / configurable for spans/metrics | High-traffic apps where you want a near-real-time stream and battery cost is acceptable |
| **CONDITIONAL** | Buffer everything; flush only when a policy matches (`app.crash`, `ui.freeze`, `http.error` by default) | Default for most apps. Battery-friendly. You see *every* event around an interesting moment, but not the boring 90% of normal usage. |
| **HYBRID** | CONDITIONAL + a periodic 30 s `device.heartbeat` ticker, immediately exported | Apps where you want "is the SDK still alive?" liveness signal even if no policy fired. Adds ~1 record per 30 s. |

**The trade you're defending:**
- "Why isn't the default CONTINUOUS?" → Because most mobile users hate batteries draining for telemetry. Selective flush gets you the *interesting* events at a fraction of the radio cost.
- "What if I want everything?" → Set `exportMode = CONTINUOUS`. The choice is exposed to the customer in the SDK config.
- "What about the data I miss in CONDITIONAL?" → Nothing is missed *on device* — everything is buffered. You just decide when to drain. If a customer complains about a session, the next policy match (or the next crash) drains the whole window.

→ Deep dive: [`docs/EXPORT_MODES.md`](../EXPORT_MODES.md), [`docs/BUFFERING_AND_TAIL_SAMPLING.md`](../BUFFERING_AND_TAIL_SAMPLING.md)

### Why the policy DSL?

A policy is a JSON shape like:

```json
{
  "id": "ui-freeze-detector",
  "match": {
    "logicalOperator": "and",
    "attributes": { "event.name": { "equals": "ui.freeze" } }
  },
  "actions": { "flushWindowMinutes": 2 }
}
```

That fires when a `ui.freeze` log arrives, and exports the **last 2 minutes** of buffered events as context. There are 21 matcher types (equals, regex, range, geo, etc.) and 10 action types. The full schema is in `mobile-otel-control-plane/docs/DSL_V2_SCHEMA.md`.

**Three things to defend about the DSL:**

1. **Why on-device evaluation?** Network costs + latency. A device deciding "this looks like a problem" → flushing 50 events is one round trip. Sending all 50 events to a server to decide there is the same network cost but with more latency *and* the privacy of sending data the user might not want exported. Edge evaluation is faster, private, and battery-cheaper.
2. **Why a DSL and not code?** The customer's ops/security team writes policies. They don't want to ship app updates to change "when do we flush." The DSL is configured remotely via the control plane and pulled by the SDK on a schedule.
3. **Why isn't this just "log levels"?** Log level says *what to record*. Policy DSL says *what to keep* and *when to send it*. They're orthogonal — a customer might record everything (`autoCapture.errors = true`) but only flush the surrounding window when something interesting happens.

→ Deep dive: [`mobile-otel-control-plane/docs/DSL_V2_SCHEMA.md`](../../../mobile-otel-control-plane/docs/DSL_V2_SCHEMA.md)

### Why a single SDK across Android/iOS/RN?

Two layers:

- **Native SDKs** (`otel-android-mobile/`, `otel-ios-mobile/`) own the buffer, policy evaluator, OTLP exporter. These are the source of truth. Both platforms must be at parity for any cross-cutting feature — see memory `feedback_no_platform_drift`.
- **React Native SDK** (`packages/react-native/`) is a thin JS facade. It marshals JS calls to the native SDK on the same device. RN does **not** re-implement buffering or export. This is the Datadog model, not the Sentry model.

**Why thin?** Sentry maintains a separate JS-only RN SDK that re-implements crash capture, breadcrumb buffer, and export queue. That's two codebases that drift. We share one buffer, one policy engine, one exporter — RN gets the same feature set for free.

→ Deep dive: [`docs/RN_ANDROID_IOS_PARITY.md`](../RN_ANDROID_IOS_PARITY.md), [`docs/REACT_NATIVE_SDK_GUIDE.md`](../REACT_NATIVE_SDK_GUIDE.md)

### Why a custom OTel Collector processor?

`mobilepolicyprocessor` (Go, lives in `collector-processor/`) does **server-side** policy evaluation. The same DSL that runs on-device runs in the Collector — same matcher/action types, same JSON shape. This lets a customer:

1. Apply additional redaction or sampling at the Collector before data hits Dash0
2. Run a "shadow policy" — see what *would* match on the server side without changing app behavior
3. Standardize policy across multiple apps that all forward to the same Collector

→ Deep dive: [`collector-processor/mobilepolicyprocessor/README.md`](../../collector-processor/mobilepolicyprocessor/README.md)

### Defend-it questions

- *"Datadog has a mobile SDK. Why should I use yours?"*
  Three answers stacked. (1) **OTel-native at the wire** — your data is portable. (2) **Selective flush** — battery is a real cost, our default doesn't drain it. (3) **One SDK across platforms** — you don't pay 3× to maintain three SDKs (we do).
- *"Sentry crash reporting is good. What's the gap?"*
  Sentry is great at *crashes*. We capture the **whole context around the crash** via the dual-tier buffer — not just the stack trace. With `flushWindowMinutes = 5`, you get every tap, every screen view, every network call from the 5 minutes before the crash. Sentry's breadcrumbs do this for some signals, but ours is uniform OTel telemetry that you can query in any OTel-compatible backend.
- *"Why not just use the OpenTelemetry-Android upstream project?"*
  We extend it. `MobileInstrumentation extends AndroidInstrumentation` — we are upstream-compatible and contribute back. The differences: dual-tier buffer (upstream is RAM-only), policy DSL (upstream has none), Dash0 ingestion attributes (`dash0.resource.type=mobile`).

---

## Module 3 — Code tour

This module follows a single `app.foreground` log record from emission through to Dash0 ingestion. By the end you can teach this from the screen.

### Setup: where the SDK lives

| Path | Purpose |
|---|---|
| `otel-android-mobile/` | Android SDK (Kotlin, JDK 17, API 26+) |
| `otel-android-mobile-core/` | Shared instrumentation primitives (semconv, registry) |
| `instrumentation/lifecycle/` | The lifecycle module — emits `app.foreground` / `app.background` |
| `otel-ios-mobile/` | iOS SDK (Swift 5.9, iOS 15+) — mirror of Android |
| `packages/react-native/` | RN bridge (TypeScript) |
| `collector-processor/mobilepolicyprocessor/` | Server-side policy processor (Go) |

### The trace: 9 hops

**Hop 1: User backgrounds the app** → `ProcessLifecycleOwner` fires `onStop`.

**Hop 2: `LifecycleInstrumentation` (in `instrumentation/lifecycle/.../LifecycleInstrumentation.kt`) catches it.**
```kotlin
private fun emitBackground() {
    emitLog(MobileSemconv.APP_BACKGROUND, Severity.INFO)
}
```
That single call hides the policy semantics. `MobileSemconv.APP_BACKGROUND = "app.background"` — that's the body of the log.

**Hop 3: `emitLog` builds an OTLP `LogRecord`.**
```kotlin
private fun emitLog(name: String, severity: Severity, extra: Attributes = ...) {
    logger?.logRecordBuilder()
        ?.setBody(name)
        ?.setSeverity(severity)
        ?.setAllAttributes(...)
        ?.emit()
}
```
The `event.name` attribute is set here too (recently — commit `42e34e2`) so backend filters can match `event.name is app.background`. This was a Phase 0 fix; before it, the body was the only signal.

**Hop 4: The OTel SDK routes the record to processors. Our processor is `MobileLogRecordProcessor` (in `otel-android-mobile/.../buffering/MobileLogRecordProcessor.kt`).**

`onEmit()` runs synchronously on the calling thread. First thing it does (also a Phase 0 fix) is stamp `extraResourceAttributes` onto the record:

```kotlin
config.extraResourceAttributes?.forEach { (key, value) ->
    if (key.isNotBlank()) {
        logRecord.setAttribute(AttributeKey.stringKey(key), value)
    }
}
```

This is *the* line that makes `dash0.test.cell_id` round-trip through Dash0. Resource-level attributes get dropped server-side; per-record stays.

**Hop 5: The record is added to the RAM buffer.**
```kotlin
val bufferedEvent = BufferedEvent(logRecordData)
ramBuffer.offer(bufferedEvent)  // ConcurrentLinkedQueue
```
Each `BufferedEvent` carries a monotonic `seqId` for dedup, and a `monotonicMs` timestamp.

**Hop 6: A crash-mirror runs every 2 s, copying new RAM events to disk.**
That's why a crash mid-emission still gets the record on the next launch.

**Hop 7: Export decision — depends on `exportMode`.**

- **CONTINUOUS** — a `ScheduledExecutor` fires every 30 s and calls `flushAll()`, which exports everything in RAM via `exporter.export(...)`.
- **CONDITIONAL** — `evaluatePolicies(logRecord)` is called. If a policy matches (e.g., `event.name=app.crash`), `flushWindow(matchResult.flushWindowMinutes)` exports the last N minutes of RAM + disk events.
- **HYBRID** — same as CONDITIONAL, plus the 30 s `device.heartbeat` and `prediction.cycle` records get *immediately* exported (bypass the buffer).

**Hop 8: The exporter chain.**
`exporter` is actually a wrap stack:
```
LoggingHttpExporter (debug logs)
  → RetryableExporter (3× exponential backoff)
    → OtlpGrpcLogRecordExporter (the real network call)
```

The OTLP/gRPC call goes to the configured endpoint — for Android that's `:4317`, for iOS it's `:4318` (HTTP/protobuf). See memory `feedback_rn_transport_asymmetry`.

**Hop 9: Server side — Collector → Dash0.**
- The Collector's `mobilepolicyprocessor` can drop, redact, or re-route based on server-side policies.
- Records arrive at Dash0 with the resource attributes we set: `service.name`, `device.id`, `os.version`, `dash0.resource.type=mobile`.
- They show up in the Logs / Spans / Metrics views, queryable via PromQL or OTLP filters.

### Where the customer integrates

In their app's `Application.onCreate` or equivalent:

```kotlin
val mobile = MobileOtel.initialize(application) {
    service { name = "their-app" ; version = "2.4.1" }
    export {
        endpoint = "https://ingress.us-west-2.aws.dash0.com:4317"
        mode = ExportMode.HYBRID
        headers = mapOf("Authorization" to "Bearer YOUR_TOKEN")
    }
    instrumentations { discoverAll() }  // wires all 11 modules
}
```

That's it. ~10 lines. Auto-instrumentation kicks in for all 11 modules unless they explicitly disable some.

→ Deep dive: [`docs/ANDROID_SDK_GUIDE.md`](../ANDROID_SDK_GUIDE.md), [`docs/AUTO_INSTRUMENTATION.md`](../AUTO_INSTRUMENTATION.md)

### Defend-it questions

- *"What if the network is down for 10 minutes?"* → Events go to RAM (5000 cap). When RAM overflows, oldest events spill to disk (50 MB). When the network comes back, the next flush trigger drains everything in order. We never lose data unless disk fills up *and* RAM fills up — and the customer can size both.
- *"What about PII?"* → Privacy presets (`network.privacyMode = MINIMAL | DEFAULT | DEBUG | PRODUCTION`). MINIMAL strips request bodies, query strings, and headers other than `:status`. Screenshots have a redaction layer. Wireframes are structural only. Location is opt-in via `captureLocation` flag, defaults to false.
- *"What's the SDK overhead?"* → APK size: ~250 KB before tree-shaking. Memory: ~2 MB for buffers at default sizing. Battery: dominated by network — CONDITIONAL is roughly free until a policy fires. CPU: instrumentation is ~30 µs per event in microbenchmarks. See [`docs/SDK_SAFETY.md`](../SDK_SAFETY.md).

---

## Module 4 — Demo runbook

Two demo paths. Pick based on audience.

### Path A: 10-minute "show me what it does" demo

For prospects who want to see live data flowing.

**Pre-flight (do this before the call):**
```bash
# Boot a Pixel_7 emulator with a window so the prospect can see the app
nohup emulator -avd Pixel_7 -no-snapshot-save > /tmp/emu.log 2>&1 &

# Start demo backend (the appointment-booking API the demo app calls)
cd examples/demo-backend && npm run dev &

# Confirm Dash0 CLI auth works
dash0 -X logs query --filter "service.name is otel-android-astronomy-shop" --from now-5m
```

**The 10 minutes:**

| Time | Action | What you say |
|---|---|---|
| 0:00 | Show the demo app on emulator (`installDebug`) | "This is a demo e-commerce app instrumented with our SDK. ~10 lines of integration code." |
| 1:00 | Tap around — browse, add to cart, navigate | "Every tap is being captured. Every screen view. Every network call to the backend." |
| 3:00 | Switch to Dash0 UI, filter `service.name=otel-android-astronomy-shop` | "Within 30 seconds the events are queryable. Notice the spans — that's a network call traced end to end. The `ui.tap` log right before it shows what the user did." |
| 5:00 | Run `./scripts/test/demo-control-center.sh` → trigger a crash | "Now I'm going to crash the app on purpose. Watch the Dash0 dashboard." |
| 6:30 | Re-launch app | "App's back up. Now look at Dash0 — I see `app.recovery_start` with `dash0.recovery.event_count=23`. That's 23 events the SDK had buffered before the crash, recovered on next launch and exported as a flush window." |
| 8:00 | Filter on the 5-min window before the crash | "This is the dual-tier buffer doing its job. None of these events were in flight when the crash happened — they survived because the SDK mirrors RAM to disk every 2 seconds." |
| 9:30 | Wrap | "The whole pipeline: SDK → OTel Collector → Dash0. OTLP/gRPC at every hop. No proprietary format. Customer can swap any layer." |

→ Full runbook with talking points: [`docs/HOW_IT_BUILDS.md`](../HOW_IT_BUILDS.md), [`docs/QUICK_START.md`](../QUICK_START.md)

### Path B: 30-minute "teach me the architecture" demo

For engineering customers who want depth.

Add to Path A:

| Add at | Action |
|---|---|
| 5:00 | Open `MobileLogRecordProcessor.kt` side-by-side with logcat. Filter logcat to `MobileLogRecordProcessor`. Tap something. Show the logcat line `Force flush: exporting 1 buffered events` correlate to a Dash0 record arriving 1–3 s later. |
| 10:00 | Open the control plane UI (`http://localhost:3000` after `npm run dev` in `mobile-otel-control-plane/control-plane-ui/`). Show a policy graph. Click "Compile" and show the DSL JSON output. |
| 15:00 | Switch export mode in the demo (`MobileConfig(exportMode = ExportMode.CONTINUOUS)`), rebuild, re-run. Show how the periodic flush vs. policy-triggered flush look different in Dash0. |
| 20:00 | Open a real crash via `gate3_crash` extra (`adb shell am start -n .../MainActivity --ez gate3_crash true`). Walk through the recovery marker. |
| 25:00 | Show the UAT matrix dashboard (`docs/uat-matrix/android-native.md`). "Every cell here is a real end-to-end test against Dash0. This is how we know the SDK works in offline + crash + buffered scenarios." |

### Defend-it questions during a demo

- *"Why is my event taking 30 seconds to show up?"* → CONTINUOUS mode flushes every 30 s by default. Drop the interval (`traceExportIntervalSeconds`, `metricExportIntervalSeconds`) for a more responsive demo, but explain the battery tradeoff.
- *"Can I see this on a real device, not an emulator?"* → Yes. Same APK installs on real Android. iOS demo (`examples/upstream-demo-app-ios/`) runs on Simulator and real devices. See [`docs/IOS_REAL_DEVICE_VALIDATION.md`](../IOS_REAL_DEVICE_VALIDATION.md).
- *"What's that Failed to fetch config: 415 line in the logs?"* → The control plane is optional — if the app can't reach it, it falls back to the default policies (`ui-freeze-detector`, `crash-recovery`, `http-error-detector`). Not a runtime issue. We may want to demote that log to debug-level.

---

## Module 5 — Troubleshooting playbook

When telemetry isn't flowing, work down this list. Each step has a one-line check and the failure pattern that tells you "the problem is here, stop."

### The 7-step diagnostic

| # | Check | Failure pattern means |
|---|---|---|
| 1 | App is actually running: `adb shell pidof <pkg>` | Empty PID = app crashed or never launched. Check logcat for `FATAL EXCEPTION`. |
| 2 | SDK initialized: logcat for `MobileLogRecordProcessor: Initialized:` | Missing = `MobileOtel.initialize()` not called or threw. Check `SdkInit` errors in logcat. |
| 3 | OTel records being emitted: logcat for the instrumentation tag (e.g. `LifecycleInstrumentation`) | No records = instrumentation not wired. Check `instrumentations { discoverAll() }` is called. |
| 4 | Records reach the processor: logcat for `Force flush: exporting N buffered events` (CONT mode) | Zero events = buffer is empty even though emission happened. Likely an export-mode mismatch (you're in CONDITIONAL but expect CONTINUOUS). |
| 5 | Export attempt happens: logcat for `LoggingHttpExporter: === EXPORT ATTEMPT ===` | Missing = the exporter chain isn't wired. Check the endpoint config + headers. |
| 6 | Export succeeds on the wire: logcat for `✅ Export successful to <url> (N logs)` | `❌ Export failed` = network problem (firewall, proxy, wrong endpoint, expired auth token). |
| 7 | Records queryable in Dash0: `dash0 -X logs query --filter "service.name is <svc>" --from now-5m` | Returns zero = either ingestion lag (wait 30 s and retry), wrong dataset, or attribute drop (see below). |

### Common gotchas

#### "My custom Resource attribute disappeared"

Dash0 ingestion strips Resource attributes outside its known schema. Symptoms: SDK logs say the attribute was set; Dash0 query for it returns 0.
**Fix:** put it on the LogRecord, not the Resource. The SDK's `extraResourceAttributes` config does this automatically since commit `42e34e2`. See [`docs/solutions/`](../solutions/) (when documented) and memory `feedback_dash0_resource_attribute_drop`.

#### "My filter `--filter "A and B"` returns 0 even though A and B both have hits separately"

The dash0 CLI's in-string `and` is silently broken. Use multiple `--filter` flags (each is implicitly ANDed):
```bash
# ✅ correct
dash0 -X logs query --filter "service.name is foo" --filter "event.name is app.foreground"
# ❌ silently zero
dash0 -X logs query --filter "service.name is foo and event.name is app.foreground"
```

#### "`event.name` filter returns 0"

Some events use `event.name` as a regular attribute; OTel also has a first-class field exposed as `otel.event.name`. Dash0 distinguishes them. If your filter on `event.name` fails, try `otel.event.name`.

#### "I tried to uninstall the demo app and got `DELETE_FAILED_INTERNAL_ERROR`"

The app is still running. `adb shell am force-stop <pkg>` first, then `adb uninstall <pkg>`. Our UAT runner does this automatically since `7bb3e1d`.

#### "My ProcessLifecycleOwner cycles aren't producing fg/bg events"

700 ms debounce. If you HOME → re-foreground in less than ~1 s, the system collapses the transition. Add ≥ 2 s sleep between HOME and the next launch. See `lib-uat-platform-android.sh:cycle_lifecycle`.

#### "iOS exporter says `.success` but nothing reaches Dash0"

Known upstream bug in opentelemetry-swift. The OTLP exporter returns `.success` synchronously before the HTTP call completes. Intercept at the `HTTPClient` layer instead of via a SpanExporter decorator. See [`docs/solutions/ios-otlp-decorator-trap.md`](../solutions/ios-otlp-decorator-trap.md).

### Customer-side checklist (for support tickets)

When a customer reports "telemetry not flowing":

1. **Get them to send logcat / device logs.** Not screenshots — actual text. `adb logcat -d | grep -E "MobileLogRecord|LoggingHttpExporter|MobileOtel"` for Android.
2. **Have them confirm the endpoint + auth token.** 90% of "telemetry not flowing" is an expired token or a wrong region (us-west-2 vs eu-west-1).
3. **Have them check their network policy.** Corporate VPN often blocks `:4317` outbound. Workaround: Collector at `:443/HTTP` instead of `:4317/gRPC`.
4. **Confirm they're querying the right dataset.** `dash0 -X` defaults to the user's primary dataset. Customer might have a separate `mobile` dataset.

→ Deep dive: [`docs/TROUBLESHOOTING_GUIDE.md`](../TROUBLESHOOTING_GUIDE.md), [`docs/OPERATIONS_GUIDE.md`](../OPERATIONS_GUIDE.md)

---

## Module 6 — Going deeper

Pointers into the existing docs. Reference, not linear reading.

### By topic

| Topic | Doc |
|---|---|
| Full configuration reference (every knob) | [`docs/CONFIGURATION.md`](../CONFIGURATION.md), [`docs/CONFIGURATION_GUIDE.md`](../CONFIGURATION_GUIDE.md) |
| Architecture deep-dive (component diagrams, threading) | [`docs/reference/ARCHITECTURE.md`](../reference/ARCHITECTURE.md) |
| Buffering and tail sampling internals | [`docs/BUFFERING_AND_TAIL_SAMPLING.md`](../BUFFERING_AND_TAIL_SAMPLING.md) |
| Export modes (CONT / COND / HYB) deep dive | [`docs/EXPORT_MODES.md`](../EXPORT_MODES.md) |
| Auto-instrumentation modules (the 11) | [`docs/AUTO_INSTRUMENTATION.md`](../AUTO_INSTRUMENTATION.md) |
| Battle cards (vs. Datadog, vs. Splunk) | [`docs/BATTLE_CARD.md`](../BATTLE_CARD.md), [`docs/BATTLE_CARD_VS_DATADOG.md`](../BATTLE_CARD_VS_DATADOG.md), [`docs/BATTLE_CARD_VS_SPLUNK.md`](../BATTLE_CARD_VS_SPLUNK.md) |
| iOS-specific (parity, crash reporting) | [`docs/IOS_SDK_GUIDE.md`](../IOS_SDK_GUIDE.md), [`docs/IOS_CRASH_REPORTING.md`](../IOS_CRASH_REPORTING.md) |
| React Native specifics | [`docs/REACT_NATIVE_SDK_GUIDE.md`](../REACT_NATIVE_SDK_GUIDE.md), [`docs/RN_ANDROID_IOS_PARITY.md`](../RN_ANDROID_IOS_PARITY.md) |
| Testing strategy | [`docs/guides/TESTING_STRATEGY.md`](../guides/TESTING_STRATEGY.md), [`docs/TESTING_GUIDE.md`](../TESTING_GUIDE.md) |
| Demo runbooks (other platforms) | [`docs/HOW_TO_DEMO_IOS.md`](../HOW_TO_DEMO_IOS.md), [`docs/HOW_TO_DEMO_RN.md`](../HOW_TO_DEMO_RN.md) |
| UAT matrix (acceptance tests) | [`docs/uat-matrix/README.md`](../uat-matrix/README.md), [`docs/epics/UAT_MATRIX_EPIC.md`](../epics/UAT_MATRIX_EPIC.md) |
| Sampling | [`docs/SAMPLING.md`](../SAMPLING.md) |
| Talking points (sales-oriented) | [`docs/TALKING_POINTS.md`](../TALKING_POINTS.md) |
| Spec for the policy DSL v2 | [`mobile-otel-control-plane/docs/DSL_V2_SCHEMA.md`](../../../mobile-otel-control-plane/docs/DSL_V2_SCHEMA.md) |

### By role

- **You're explaining "why this is OTel-native" to a skeptical OTel-aware engineer:** Module 2 + [`docs/ARCHITECTURE_OVERVIEW.md`](../ARCHITECTURE_OVERVIEW.md)
- **You're explaining "why selective flush" to a battery-anxious mobile lead:** Module 2 § Why three export modes + [`docs/EXPORT_MODES.md`](../EXPORT_MODES.md)
- **You're explaining "why a custom OTel processor" to a security/compliance reviewer:** Module 2 § Why a custom OTel Collector processor + [`collector-processor/mobilepolicyprocessor/README.md`](../../collector-processor/mobilepolicyprocessor/README.md)
- **You're explaining "what's different from upstream OTel-Android" to an open-source-policy team:** Module 2 final question + [`docs/upstream/`](../upstream/)

### When you're stuck on a real customer issue

1. Read [`docs/TROUBLESHOOTING_GUIDE.md`](../TROUBLESHOOTING_GUIDE.md) — has known-issue patterns
2. Search [`docs/solutions/`](../solutions/) — captured learnings from past incidents
3. Search the auto-memory index for the workspace (in your Claude session): `feedback_*` and `project_session_*` files capture what's been tried and what worked
4. If still stuck, the architecture deep-dive in [`docs/reference/ARCHITECTURE.md`](../reference/ARCHITECTURE.md) shows component-level threading and ownership

---

## Module 7 — Self-paced exercises

Do these in order. Each one takes 30–60 min. By the end of #5, you can run a customer demo cold.

1. **Boot the demo end-to-end.** Follow the runbook in `mobile-otel/CLAUDE.md` § Demo Runbook. Get to step 7: telemetry visible in Dash0. Note any place you got stuck — that's a doc bug worth filing.
2. **Trace one event by hand.** Pick `app.foreground`. Read its emission point in `LifecycleInstrumentation.emitForeground()`. Trace through `MobileLogRecordProcessor.onEmit()`. Find where it leaves the device (the `OtlpGrpcLogRecordExporter` call). Match each Kotlin line to a hop in Module 3.
3. **Switch export modes.** Change the demo app's `MobileConfig(exportMode = ...)` between CONT, COND, HYB. Rebuild. Observe the difference in Dash0 — what records arrive when, how long after the trigger.
4. **Author a custom policy.** Open the control plane UI. Build a graph that flushes when `event.name = ui.tap` AND `mobile.screen.name = checkout`. Compile to DSL. Push to a running app. Verify it triggers when you tap on the checkout screen.
5. **Reproduce a customer issue.** Pick the iOS OTLP decorator trap from `docs/solutions/ios-otlp-decorator-trap.md`. Read the doc. Then write a one-paragraph summary of the bug, the wrong fix, the right fix, and how you'd diagnose it in a customer ticket — in your own words.

---

## Appendix: cheat sheet

### One-line architecture pitch

> "OTel-native mobile observability with on-device policy-driven selective flush, dual-tier crash-survivable buffering, and a single SDK across Android, iOS, and React Native."

### One-line export-mode pitch

> "CONT for streaming, COND for battery efficiency, HYB for COND with a heartbeat. Default is COND; we don't drain customer batteries."

### One-line "why us, not Datadog/Sentry" pitch

> "Wire-format portability (OTLP), battery-aware export by default, and a unified policy engine that runs on-device and at the Collector with the same DSL."

### "I just want to see telemetry flow" pitch

> "Add ten lines of SDK init, set the endpoint and auth token, install the app. Within 30 seconds you'll see your first log records in Dash0. Within 5 minutes you'll have spans, metrics, and the full instrumentation envelope."

---

**Last updated:** 2026-05-05. Captured Phase-0 closure of the UAT matrix, the Resource→LogRecord attribute migration, and the dash0 CLI multi-filter gotcha. Future revisions: when Phase 1 closes (12-cell sweep green), Phase 2 (iOS + RN cells), and the iOS Gate 4 closure.
