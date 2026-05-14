# Telemetry Signals Reference

Complete inventory of all OTel events, spans, and metrics emitted by the SDK.

---

## Events (Log Records)

| Event Name | Module | Description |
|---|---|---|
| `ui.tap` | tap | Tap on interactive element |
| `ui.long_press` | tap | Long-press on interactive element |
| `ui.swipe` | tap | Swipe gesture (directional) |
| `ui.scroll` | scroll | RecyclerView scroll |
| `ui.text_input` | text-input | EditText focus-leave |
| `ui.back_press` | back-press | Back button press |
| `ui.screen_view` | screen | Activity/Fragment resume |
| `ui.freeze` | freeze | Main thread freeze (jank > threshold) |
| `app.anr` | freeze | Freeze exceeds ANR threshold (5000ms default) |
| `app.start` | lifecycle | First Activity.onCreate in session |
| `app.foreground` | lifecycle | App transitions to foreground |
| `app.background` | lifecycle | App transitions to background |
| `app.crash` | errors | Uncaught exception / coroutine error |
| `screen.render` | screen | First OnPreDraw fires after screen transition |
| `http.error` | network | HTTP status >= errorStatusThreshold (default 400) |
| `ui.screenshot` | screenshot (incubating) | Pixel capture as base64 data URL |
| `ui.wireframe` | wireframe (incubating) | View-hierarchy JSON tree |
| `demo.step` | demo-app (test only) | Marks each step in an Espresso demo scenario |

Event names are defined in [MobileSemconv.kt](../../otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/MobileSemconv.kt).

---

## Spans

| Span Name | Module | Kind | Description |
|---|---|---|---|
| `page.<ScreenName>` | screen | INTERNAL | Page-level span for activity/fragment; parent for all UI interactions on that screen |
| `screen.render` | screen | INTERNAL | Ends when first OnPreDraw fires |
| `ui.tap` | tap | INTERNAL | Zero-duration child of page span (when `UiTelemetryMode.SPANS`) |
| `ui.long_press` | tap | INTERNAL | Child of page span |
| `ui.swipe` | tap | INTERNAL | Child of page span |
| `ui.scroll` | scroll | INTERNAL | Child of page span |
| `ui.text_input` | text-input | INTERNAL | Child of page span |
| `ui.back_press` | back-press | INTERNAL | Child of page span |
| `{METHOD} {PATH}` | network | CLIENT | HTTP request span (e.g. `GET /api/appointments`); propagates W3C trace context |
| `user.navigate` | demo-app | INTERNAL | Navigation between fragments (demo app custom) |
| `booking.submit` | demo-app | INTERNAL | Appointment booking workflow (demo app custom) |

---

## Metrics (Gauges)

All metrics are from [VitalsCollector.kt](../../instrumentation/vitals/src/main/java/io/opentelemetry/android/mobile/vitals/VitalsCollector.kt). All are `@Incubating` — aligned with OTel Mobile SIG proposals but not yet standardized.

| Metric Name | Unit | Type | Description |
|---|---|---|---|
| `mobile.app.start.cold` | s | double gauge | Cold start time |
| `mobile.app.start.warm` | s | double gauge | Warm start time |
| `mobile.app.ttid` | s | double gauge | Time to initial display |
| `mobile.ui.jank.count` | {events} | long gauge | Total jank events |
| `mobile.ui.jank.severe.count` | {events} | long gauge | Severe jank events (> severeJankThresholdMs) |
| `mobile.ui.jank.rate` | % | double gauge | Percentage of frames with jank |
| `mobile.input.latency.avg` | s | double gauge | Average touch-to-response latency |
| `mobile.anr.risk` | s | double gauge | Main thread block time |
| `mobile.memory.available` | By | long gauge | Available system memory |
| `mobile.memory.threshold` | By | long gauge | Low memory threshold |
| `mobile.thermal.state` | {state} | long gauge | Thermal status (0=none … 6=shutdown) |

---

## Attribute Keys

### Session & Navigation

| Key | Type | Description |
|---|---|---|
| `mobile.session.id` | string | Session UUID |
| `mobile.view.id` | string | View/visitor UUID |
| `mobile.session.renewed` | boolean | Session renewed on foreground |
| `mobile.screen.name` | string | Current screen name |
| `ui.previous_screen` | string | Previous screen name |
| `ui.time_on_screen_ms` | long | Time on previous screen |
| `mobile.background_duration_ms` | long | Duration app was backgrounded |
| `mobile.recovery_type` | string | Recovery/restart type |

### UI Element (tap, long-press, swipe, text-input)

| Key | Type | Description |
|---|---|---|
| `ui.element.resource_id` | string | Android resource ID (e.g. `btn_book`) |
| `ui.element.class` | string | View class name (e.g. `Button`) |
| `ui.element.label` | string | Visible text or content description |
| `ui.element.enabled` | boolean | View is enabled |
| `ui.element.clickable` | boolean | View is clickable |
| `ui.swipe.direction` | string | `up`, `down`, `left`, `right` |

### Text Input

| Key | Type | Description |
|---|---|---|
| `ui.element.char_count` | long | Character count |
| `ui.element.is_set` | boolean | Has content |
| `ui.element.text` | string | Text content (if allowlisted) |

### Scroll

| Key | Type | Description |
|---|---|---|
| `ui.scroll.direction` | string | `up`, `down`, `left`, `right` |
| `ui.scroll.distance_bucket` | string | `small`, `medium`, `large` |

### Errors

| Key | Type | Description |
|---|---|---|
| `exception.type` | string | Full exception class name (standard semconv) |
| `exception.message` | string | Exception message (standard semconv) |
| `exception.stacktrace` | string | Stack trace (standard semconv) |
| `mobile.exception.origin` | string | `uncaught`, `coroutine`, `rxjava`, `manual` |
| `mobile.error.fingerprint` | string | Hash for deduplication |
| `mobile.error.context` | string | Thread/coroutine info |
| `mobile.exception.summary` | string | Human-readable summary |

### Freeze / ANR

| Key | Type | Description |
|---|---|---|
| `mobile.freeze.duration_ms` | long | Freeze duration |

### Screenshot (incubating)

| Key | Type | Description |
|---|---|---|
| `mobile.screenshot.trigger` | string | `manual`, `error`, `screen_view` (requires `captureOnScreenView=true`), `policy_<id>` (e.g. `policy_crash-recovery`) |
| `mobile.screenshot.format` | string | `png` or `jpeg` |
| `mobile.screenshot.width` | long | Scaled image width (px) |
| `mobile.screenshot.height` | long | Scaled image height (px) |
| `mobile.screenshot.size_bytes` | long | Compressed payload size |
| `mobile.screenshot.data_url` | string | Base64 data URL |
| `mobile.screenshot.redacted` | boolean | TextView text was redacted |

### Wireframe (incubating)

Two event-name bodies, depending on whether dedup applied to the capture:

- `ui.wireframe` — full wireframe JSON payload (first capture, or content
  changed since the last emit). Carries all the attributes below including
  `mobile.wireframe.data`.
- `ui.wireframe.ref` — lightweight reference emitted when content-hash dedup
  matches. Carries `mobile.wireframe.id` pointing at the previously emitted
  full wireframe, plus `trigger` / `sequence` / `screen.name` / session id.
  Omits `data` / `size_bytes` / `node_count`.

| Key | Type | Description |
|---|---|---|
| `mobile.wireframe.id` | string | SHA-256 hex of the captured JSON. Stable across `ui.wireframe.ref` records that refer to the same payload. Use as the join key to reconstruct journey replays. |
| `mobile.wireframe.trigger` | string | `manual`, `screen_view`, `tap`, `error`, `policy_<id>` (e.g. `policy_crash-recovery`) |
| `mobile.wireframe.sequence` | long | Ordering sequence number (monotonic within a session, increments on every emit including refs) |
| `mobile.wireframe.size_bytes` | long | JSON payload size — present only on `ui.wireframe` |
| `mobile.wireframe.node_count` | long | Nodes in view tree — present only on `ui.wireframe` |
| `mobile.wireframe.data` | string | JSON wireframe structure — present only on `ui.wireframe` |

### Network

| Key | Type | Description |
|---|---|---|
| `http.request.method` | string | HTTP method (standard semconv) |
| `http.response.status_code` | long | HTTP status code (standard semconv) |
| `url.full` | string | Full URL (standard semconv) |
| `server.address` | string | Hostname (standard semconv) |
| `server.port` | long | Port (standard semconv) |
| `network.connection.type` | string | `wifi`, `cellular`, `ethernet`, `bluetooth`, `none`, `unknown` |
| `http.response.duration_ms` | long | Request duration |

### Vitals (metric attributes)

| Key | Type | Description |
|---|---|---|
| `mobile.start.type` | string | `cold` or `warm` |
| `mobile.start.slow` | boolean | Exceeds start threshold |
| `mobile.ttid.slow` | boolean | Exceeds TTID threshold |
| `mobile.latency.high` | boolean | Exceeds input latency threshold |
| `mobile.anr.risk.high` | boolean | Exceeds ANR risk threshold |
| `mobile.memory.low` | boolean | System low memory |
| `mobile.memory.critical` | boolean | Below criticalMb threshold |
| `mobile.thermal.state` | string | `none`, `light`, `moderate`, `severe`, `critical`, `emergency`, `shutdown` |
