# Auto-Instrumentation Reference

This document describes every signal the SDK captures automatically — without any manual `MobileOtel.sendEvent()` calls — when you initialize via `OTelMobile.start()` or `MobileOtel.initialize()`.

> **Screen-name convergence (1.5.0):** Every `mobile.screen.name` attribute below is also emitted as **`app.screen.name`** — the name upstream opentelemetry-android adopted in 1.5.0. The SDK's log processor mirrors it at one choke point, so query either; the legacy `mobile.screen.name` alias is removed at 1.0. See [SEMCONV_AUDIT.md](SEMCONV_AUDIT.md).

---

## Table of Contents

- [Entry Points](#entry-points)
- [Trace Hierarchy: Page Spans](#trace-hierarchy-page-spans)
- [UI Interactions](#ui-interactions)
  - [Taps](#taps)
  - [Long Press](#long-press)
  - [Swipe](#swipe)
  - [Scroll](#scroll)
  - [Text Input](#text-input)
  - [Back Press](#back-press)
- [Screen Navigation](#screen-navigation)
- [App Lifecycle](#app-lifecycle)
- [Freeze & ANR Detection](#freeze--anr-detection)
- [Crash & Error Capture](#crash--error-capture)
- [Network Instrumentation](#network-instrumentation)
- [Performance Vitals](#performance-vitals)
- [Predictive Export](#predictive-export)
- [Ring Buffer Metrics](#ring-buffer-metrics)
- [Sampling Strategy](#sampling-strategy)
- [Privacy Controls](#privacy-controls)
- [Configuration Reference](#configuration-reference)

---

## Entry Points

| Entry Point | Auto-Capture | Core SDK |
|---|---|---|
| `OTelMobile.start(app, config)` | Yes — also starts `AutoCaptureManager` | Yes |
| `MobileOtel.initialize(app, config)` | No — core SDK only | Yes |

`OTelMobile.start()` is the drop-in entry point. It calls `MobileOtel.initialize()` internally and then activates `AutoCaptureManager`, which hooks into the Window callback chain to observe every touch event.

---

## Trace Hierarchy: Page Spans

Every screen transition creates a **page span** that becomes the root of the trace waterfall for all interactions on that screen.

```
page.BookFragment             ← root, always sampled (DynamicSampler)
├── ui.tap                    ← auto-captured child span (TapCapture)
├── ui.tap
├── booking.submit            ← manually created child span in BookFragment
│   └── POST /api/appointments  ← OkHttp child span (OTelNetworkInterceptor)
└── ui.swipe
```

**How it works:**

1. `AutoCaptureManager` registers `Application.ActivityLifecycleCallbacks` and `FragmentManager.FragmentLifecycleCallbacks`.
2. On every `Fragment.onResume()`, `startPageSpan(screenName)` creates a span named `page.<screenName>` and calls `makeCurrent()`, installing it into the active OTel `Context`.
3. `TapCapture` captures the current `Context` at `ACTION_UP` on the main thread. When the parent span is valid and sampled, each tap/swipe/scroll is emitted as a **zero-duration child span** (path A). Without a valid parent, they fall back to log records (path B).
4. `DynamicSampler` **always samples** `page.*` and `app.startup` spans by name, regardless of the baseline sampling rate. This ensures path A is always taken for normal interactions — dropping a page span would break every child span on that screen.
5. On `Fragment.onPause()`, `endPageSpan()` ends the span.

**Span attributes added to every page span:**

| Attribute | Value |
|---|---|
| `mobile.session.id` | Current session UUID |
| `mobile.view.id` | Current view UUID |
| `mobile.screen.name` | Fragment simple class name |

---

## UI Interactions

### Taps

**OTel signal:** Span (`ui.tap`) or Log (`ui.tap`)

`TapCapture` wraps the `Window.Callback` via `WindowCallbackWrapper` and intercepts `MotionEvent.ACTION_UP`. Rapid consecutive taps on the same target are coalesced into a single event (default window: 800 ms).

**Attributes:**

| Attribute | Description |
|---|---|
| `mobile.session.id` | Session UUID |
| `mobile.view.id` | View UUID |
| `mobile.screen.name` | Current screen |
| `interaction.source` | `"auto.window"` |
| `element.confidence` | `"exact"` / `"parent"` / `"none"` |
| `ui.element.class` | View class name |
| `ui.element.resource_id` | `res/id/button_submit` |
| `ui.element.enabled` | Whether element is enabled |
| `ui.element.clickable` | Whether element is clickable |
| `ui.element.content_desc_hash` | SHA-256 of content description (STRICT mode) |
| `ui.element.text_hash` | SHA-256 of text label (STRICT mode) |
| `ui.tap.bucket_row` | Grid bucket row (privacy-safe coordinate) |
| `ui.tap.bucket_col` | Grid bucket column |
| `ui.tap.grid_size` | Bucket grid size (default: 3×3) |
| `ui.tap.count` | Coalesced tap count (when > 1) |
| `ui.tap.window_ms` | Coalesce window used |

**RELAXED privacy mode** captures raw `content_description` and `text` instead of hashes.

### Long Press

**OTel signal:** Span (`ui.long_press`) or Log (`ui.long_press`)

Detected when `event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()`. Same attributes as tap. Enabled via `captureLongPress = true`.

### Swipe

**OTel signal:** Span (`ui.swipe`) or Log (`ui.swipe`)

Detected when the distance between `ACTION_DOWN` and `ACTION_UP` exceeds `swipeMinDistancePx` (default: 50 px). Direction is one of `up`, `down`, `left`, `right`.

**Extra attributes:**

| Attribute | Value |
|---|---|
| `ui.swipe.direction` | `up` / `down` / `left` / `right` |

### Scroll

**OTel signal:** Span or Log (`ui.scroll`)

`ScrollCapture` throttles scroll events (default: 500 ms). Scroll distance and direction are bucketed.

### Text Input

**OTel signal:** Log (`ui.text_input`)

`TextInputCapture` detects focus changes on `EditText` views. The field's resource ID is captured; field content is **never** captured. Enabled via `captureTextInput = true`.

### Back Press

**OTel signal:** Log (`ui.back_press`)

`BackPressCapture` wraps the `Window.Callback.onBackPressed()` hook. Emits one event per back-press with `mobile.screen.name`. Enabled via `captureBackPress = true`.

---

## Screen Navigation

**OTel signal:** Log (`ui.screen_view`)

`AutoCaptureManager` emits `ui.screen_view` on each `Fragment.onResume()`.

| Attribute | Value |
|---|---|
| `mobile.screen.name` | Fragment simple class name |
| `screen.class` | Fully-qualified class name |
| `mobile.session.id` | Session UUID |

`NavigationInstrumentation` additionally tracks Activity launches, deep links, and screen-to-screen transitions. Navigation events are stored as **breadcrumbs** in `BreadcrumbManager` and attached to the next error report.

---

## App Lifecycle

**OTel signals:** Log events and spans

| Event | Signal type | Body / span name |
|---|---|---|
| App cold start | Span | `app.startup` |
| First frame drawn | Span end | `app.startup` ends when first `ViewTreeObserver.onPreDraw()` fires |
| Screen render time | Span | `screen.render` |
| App foreground | Log | `app.foreground` |
| App background | Log | `app.background` |

`AppStartInstrumentation` uses `Application.registerActivityLifecycleCallbacks` and `ViewTreeObserver` to measure cold-start time (process start → first frame).

---

## Freeze & ANR Detection

**OTel signals:** Log (`ui.freeze`), Log (`anr.detected`), Log (`anr.recovered`)

`FreezeDetector` posts a watchdog `Runnable` to the main thread `Handler` at fixed intervals. If the main thread doesn't respond within `freezeThresholdMs` (default: 2000 ms), a `ui.freeze` event is emitted with the measured duration. If the delay exceeds `anrThresholdMs` (default: 5000 ms), an `anr.detected` event fires.

`RecoveryTracker` persists ANR markers to `SharedPreferences`. On the next app start, it emits `anr.recovered` if a marker exists.

A `ui.freeze` event triggers the **default `ui-freeze-detector` export policy**, which flushes the preceding 2-minute event window.

| Attribute | Description |
|---|---|
| `freeze.duration_ms` | How long the main thread was blocked |
| `freeze.threshold_ms` | Configured threshold |
| `mobile.screen.name` | Current screen |

---

## Crash & Error Capture

**OTel signal:** Log (`app.crash`)

`ErrorInstrumentation` installs a global `Thread.UncaughtExceptionHandler` and optionally hooks Kotlin coroutine and RxJava error pipelines.

Features:
- **Deduplication:** The same stack trace within a 5-minute window is not reported twice.
- **Rate limiting:** Max 10 errors per minute.
- **Stack trace scrubbing:** PII patterns are stripped via `PiiScrubber`.
- **Breadcrumb attachment:** The last N breadcrumbs are attached to the crash event.
- **Automatic flush:** On uncaught exception, `onFlush()` is called immediately — before the process dies — to push all buffered events.

An `app.crash` event triggers the **default `crash-recovery` export policy**, flushing the preceding 5-minute window.

| Attribute | Description |
|---|---|
| `exception.type` | Exception class name |
| `exception.message` | Exception message (scrubbed) |
| `exception.stacktrace` | Trimmed stack trace |
| `exception.origin` | `"uncaught"` / `"coroutine"` / `"rxjava"` |
| `mobile.session.id` | Session UUID |

---

## Network Instrumentation

**OTel signal:** Span per HTTP request

`OTelNetworkInterceptor` is an OkHttp `Interceptor` that **must be added manually** to your `OkHttpClient`. It is not registered automatically.

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(OTelNetworkInterceptor.create(context, networkConfig, tracer, propagator))
    .build()
```

Each request becomes a `CLIENT` span named `"METHOD /path"` using OTel semantic conventions.

**Span attributes (OTel semconv):**

| Attribute | Description |
|---|---|
| `http.request.method` | `GET`, `POST`, etc. |
| `url.full` | Full URL (query params scrubbed by default) |
| `server.address` | Hostname |
| `server.port` | Port |
| `http.response.status_code` | HTTP status code |
| `network.connection.type` | `"wifi"` / `"cellular"` / `"ethernet"` |
| `http.request.body.size` | Bucketed request body size |
| `http.response.body.size` | Bucketed response body size |

**W3C trace context propagation:** `traceparent` and `tracestate` headers are injected into every instrumented request, linking server-side traces to the mobile session.

**HTTP errors** (status >= 400 by default) trigger the **`http-error-detector` export policy**, flushing the preceding 5-minute window.

**Privacy defaults:** Query parameters are stripped, `Authorization` and similar headers are scrubbed. Body capture is off by default.

---

## Performance Vitals

### Frame Rendering (Jank)

**OTel signal:** Log (`UI jank detected`, `event.name = "mobile.ui.jank"`)

`JankDetector` registers a `Choreographer.FrameCallback` and measures the elapsed time between consecutive frames.

| Severity | Condition |
|---|---|
| Minor | Frame > 16 ms (1 dropped frame, ~60 fps) |
| Moderate | Frame > 32 ms (2 dropped frames) |
| Severe | Frame > 100 ms (UI freeze territory) |

A 1-second cooldown prevents log spam. Reports to `VitalsCollector` as metrics.

| Attribute | Description |
|---|---|
| `jank.frame_time_ms` | Actual frame duration |
| `jank.dropped_frames` | Estimated dropped frames |
| `jank.severity` | `"minor"` / `"moderate"` / `"severe"` |
| `jank.consecutive_count` | Consecutive jank frames |
| `jank.severe` | Boolean |

### App Start Time

**OTel signal:** Span (`app.startup`)

`AppStartInstrumentation` measures the duration from `Application.onCreate()` to the first drawn frame. The span carries `app.start.type = "cold"` and `app.start.duration_ms`.

---

## Predictive Export

`PredictiveExportPolicy` runs a background `DeviceHealthMonitor` that samples 9–14 device health signals every 30 seconds (battery, memory, thermal, storage). `OnDevicePredictor` combines these into two risk scores:

- **Crash risk ≥ 0.7** → immediate buffer flush (pre-emptive, before the app crashes)
- **Network loss risk ≥ 0.7** → immediate buffer flush (before connectivity is lost)

Prediction scores are emitted as OTel logs (`mobile.prediction`) with all health attributes attached. This is initialized automatically via `MobileOtel.initialize()`.

---

## Ring Buffer Metrics

Three OTel async gauges are registered by `MobileLogRecordProcessor` and reported on every metrics collection cycle:

| Metric name | Unit | Description |
|---|---|---|
| `buffer.ram.events` | `{events}` | Current events in the RAM ring buffer (max 5000) |
| `buffer.ram.capacity` | `{events}` | Configured RAM buffer capacity |
| `buffer.disk.events` | `{events}` | Current events in the SQLite disk buffer |

These appear in Dash0 as gauge metrics and can be used to build dashboard panels showing buffer backpressure over time.

---

## Sampling Strategy

`DynamicSampler` is the default sampler. It uses OTel's trace-ID ratio algorithm and adds mobile-specific rules:

| Span type | Sampling decision |
|---|---|
| `page.*` | **Always sampled** (force-sampled by name) |
| `app.startup` | **Always sampled** |
| All other spans | Baseline rate (default: 10%) |

The baseline rate can be raised temporarily after errors:

```kotlin
dynamicSampler.setSamplingRate(1.0, durationMinutes = 10)
```

This reverts automatically after the specified duration.

**Why `page.*` must always be sampled:** TapCapture checks `parentSpan.isSampled` at event time. If a page span is dropped, all child interactions fall back to flat, disconnected log records (path B). Manually-created child spans like `booking.submit` would start new disconnected root traces. Forcing `page.*` to 100% sampling keeps the waterfall intact regardless of the baseline rate.

---

## Privacy Controls

### UI auto-capture privacy

Two modes, configured via `AutoCaptureOptions.privacyMode`:

| Mode | Behavior |
|---|---|
| `STRICT` (default) | Text labels and `contentDescription` are SHA-256 hashed. Coordinates are bucketed into a 3×3 grid. |
| `RELAXED` | Raw text and coordinates captured. Not recommended for production. |

Additional controls:
- `denylistedViewClasses` — class names that will not be attributed (e.g., password fields)
- `denylistedResourceIds` — resource IDs to skip
- `allowlistedViewClasses` / `allowlistedResourceIds` — restrict capture to only these elements

### Network privacy

- URL query parameters stripped by default (`scrubUrls = true`)
- `Authorization`, `Cookie`, and similar request headers scrubbed (`scrubHeaders = true`)
- Request/response body capture disabled by default (`captureRequestBody = false`)
- Host allowlist/blocklist available for scoping instrumentation

### Stack trace scrubbing

`PiiScrubber` runs regex patterns over stack traces before emission, stripping common PII patterns (emails, phone numbers, URLs).

---

## Configuration Reference

### `AutoCaptureOptions` (deprecated — use individual module configs)

| Property | Default | Description |
|---|---|---|
| `captureLifecycle` | `true` | App/Activity lifecycle events |
| `captureScreens` | `true` | Fragment screen views |
| `captureTaps` | `true` | Tap events |
| `captureLongPress` | `true` | Long press events |
| `captureSwipe` | `true` | Swipe gestures |
| `captureScroll` | `true` | Scroll events |
| `captureTextInput` | `true` | Text field focus |
| `captureBackPress` | `true` | Back press |
| `captureFragments` | `true` | Fragment lifecycle |
| `freezeDetectorEnabled` | `true` | Freeze/ANR detection |
| `tapCoalesceWindowMs` | `800` | Rapid-tap coalescing window (ms) |
| `swipeMinDistancePx` | `50` | Minimum pixels to classify as swipe |
| `freezeThresholdMs` | `2000` | Main-thread block time before `ui.freeze` |
| `anrThresholdMs` | `5000` | Main-thread block time before `anr.detected` |
| `scrollThrottleMs` | `500` | Minimum ms between scroll events |
| `bucketGridSize` | `3` | N×N coordinate bucket grid |
| `maxHitTestDepth` | `12` | View tree depth for hit-testing |
| `sessionRenewalMs` | `1800000` | Session ID rotation interval (30 min) |
| `privacyMode` | `STRICT` | `STRICT` or `RELAXED` |

### Default export policies

Three policies are active by default in `PolicyEvaluator`:

| Policy ID | Trigger (log body) | Action |
|---|---|---|
| `ui-freeze-detector` | `"ui.freeze"` | `flushWindow(2 minutes)` |
| `crash-recovery` | `"app.crash"` | `flushWindow(5 minutes)` |
| `http-error-detector` | `"http.error"` | `flushWindow(5 minutes)` |
