# Mobile OTel SDK — What We Built and Why It Matters

## The Problem

The official OpenTelemetry Android SDK (`opentelemetry-android`) provides foundational mobile observability — activity lifecycle spans, crash reporting, slow render detection, and network tracing. But it was designed with the same assumptions as server-side OTel: always-on export, flat span hierarchies, and no awareness of mobile-specific constraints like battery life, intermittent connectivity, or process death.

Mobile is fundamentally different. Devices go offline. Batteries die. Apps crash without warning. Users interact through touches, swipes, and gestures — not HTTP requests. The official SDK doesn't address any of this.

## What We Built

An OpenTelemetry-native Android SDK that treats mobile as a first-class environment — not a smaller server.

### 1. Dual-Tier Buffering (Crash-Survivable Telemetry)

**Official SDK**: Standard `BatchSpanProcessor` holds events in memory. App crash or process death = data loss. Offline periods = data loss.

**This SDK**: Two-tier ring buffer — RAM (5,000 events, lock-free `ConcurrentLinkedQueue`) with overflow to disk (Room/SQLite, 50MB, 24h TTL). Events survive crashes, force-quits, and offline periods. On next launch, the SDK detects crash/ANR markers and flushes the pre-crash buffer with full context.

**Why it matters**: The telemetry you need most — what happened right before a crash — is exactly what the standard approach loses.

### 2. Policy-Driven Export (Battery-Efficient by Design)

**Official SDK**: Continuous export only. Every span and log is batched and sent on a fixed timer. Estimated battery impact: 3-5%.

**This SDK**: Three export modes:

| Mode | Behavior | Battery Impact |
|------|----------|----------------|
| **CONDITIONAL** | Export only when policy triggers fire | <0.5% |
| **CONTINUOUS** | Standard periodic export | 3-5% |
| **HYBRID** | Periodic baseline + trigger-based burst | 1-2% |

In CONDITIONAL mode, the SDK silently buffers all telemetry locally. When something goes wrong — a crash, a freeze, an error spike — the export policy fires and flushes a targeted time window. Zero bandwidth when nothing is wrong.

**Why it matters**: Mobile users notice battery drain. An observability SDK that costs 3-5% battery won't survive in production. CONDITIONAL mode gives you full observability context at <0.5%.

### 3. Selective Flush (Export What Matters)

**Official SDK**: No concept of time-windowed export. It's all or nothing.

**This SDK**: `flushWindow(minutes)` exports only events from the specified time window. A crash can trigger export of just the 2-minute window leading up to it — not the entire buffer.

```kotlin
// Flush only the last 2 minutes of buffered events
MobileOtel.forceFlush(windowMinutes = 2)
```

**Why it matters**: When debugging a crash, you need the 2 minutes before it happened — not 24 hours of idle telemetry. Selective flush gives you surgical precision with minimal bandwidth.

### 4. Export Policy DSL (Declarative Trigger Rules)

**Official SDK**: No policy engine. All events are exported unconditionally.

**This SDK**: A DSL engine evaluates trigger conditions in real-time against incoming events:

```json
{
  "trigger": {
    "any": [
      { "event": "ui.freeze" },
      { "event": "ui.jank", "where": [{"attr": "duration_ms", "op": ">", "value": 2000}] }
    ]
  },
  "actions": [
    { "type": "flush_window", "minutes": 2, "scope": "session" }
  ]
}
```

Policies can be bundled with the app or updated remotely. A visual policy builder (React Flow) lets non-engineers author and deploy policies without code changes.

**Why it matters**: Different apps need different export strategies. A banking app cares about error rates; a media app cares about jank. Policies let you define "what matters" declaratively.

### 5. Full UI Interaction Auto-Instrumentation

**Official SDK auto-instruments:**
- Activity/Fragment lifecycle (start, resume, pause, stop)
- Slow renders (Choreographer frame timing)
- ANR detection (watchdog thread)
- Crash reporting (uncaught exception handler)

**This SDK auto-instruments all of the above, plus:**

| Module | Events Captured | Notes |
|--------|----------------|-------|
| **Tap** | `ui.tap`, `ui.long_press`, `ui.swipe` | View identity (resource ID, class, label), swipe direction |
| **Scroll** | `ui.scroll` | RecyclerView, direction + distance bucket, throttled |
| **Text Input** | `ui.text_input` | Focus-leave trigger, char count, is_set (content opt-in only) |
| **Back Press** | `ui.back_press` | Hardware and gesture back |
| **Screen View** | `ui.screen_view` + `page.<Name>` span | Parent span that nests all child interactions |
| **Freeze** | `ui.freeze` (>2s), `app.anr` (>5s) | Watchdog mechanism, emits after recovery |
| **Lifecycle** | `app.start`, `app.foreground`, `app.background` | Background duration tracking |
| **Errors** | Uncaught, coroutine, RxJava exceptions | 5-min dedup, 10/min rate limit, stack scrubbing |
| **Vitals** | Memory, battery, jank, app-start metrics | OTel Meter gauges |
| **Network** | HTTP request/response spans | OkHttp interceptor (requires user wiring) |

All UI modules are auto-discovered via Java SPI (`ServiceLoader`) and wired through a central `WindowEventHub` that wraps each Activity's `Window.Callback`.

**Why it matters**: The official SDK tells you which screens users visited. This SDK tells you what they did on each screen — every tap, scroll, text input, and gesture — automatically, with zero manual instrumentation code.

### 6. Hierarchical Span Nesting (Journey > Page > Interaction)

**Official SDK**: Emits flat, disconnected spans. A screen view and a button tap have no parent-child relationship.

**This SDK**: Automatic three-level nesting:

```
journey.booking                          (journey span)
  |-- page.SearchFragment                (page span, auto-started on screen view)
  |     |-- ui.tap [search_button]       (child span, auto-nested)
  |     |-- ui.text_input [query_field]  (child span, auto-nested)
  |     |-- ui.scroll [results_list]     (child span, auto-nested)
  |
  |-- page.DetailFragment
  |     |-- ui.tap [book_button]
  |
  |-- page.ConfirmationFragment
        |-- ui.tap [confirm_button]
```

Page spans are automatically made current on the main thread. Any interaction span created while a page span is active is nested as a child. Journey spans group multiple pages into a user flow.

**Why it matters**: Flat spans require manual correlation. Hierarchical spans let you trace a user's exact path through your app — which screen, which button, in what order — without writing a single line of instrumentation code.

### 7. Predictive Export (Flush Before You Crash)

**Official SDK**: No predictive capabilities.

**This SDK**: `PredictiveExportPolicy` monitors device health signals (memory pressure, battery drain rate, thermal state, network quality) and computes risk scores (0.0-1.0) for crash, network loss, performance degradation, and battery drain. When crash risk or network loss risk exceeds 0.7, the SDK pre-emptively flushes buffered telemetry.

**Why it matters**: If the SDK can see a crash coming — memory filling up, thermal throttling, battery critical — it can flush telemetry before the crash happens. You get the data even when the crash prevents normal export.

### 8. Privacy by Default

**Official SDK**: Basic attribute filtering. No built-in PII handling.

**This SDK** (always-on, no opt-in required):
- Email addresses hashed (SHA-256)
- URL query parameters scrubbed
- Path UUIDs/IDs replaced with `{id}` placeholders
- Stack traces scrubbed (user-specific paths removed)
- PII regex detection (emails, phones, credit cards, SSNs)
- Text input content capture is **off by default** — requires explicit allowlist
- `captureLocation = false` by default
- Network privacy presets: default, minimal, debug, production

**Why it matters**: GDPR, CCPA, and app store policies require careful handling of user data. Privacy-by-default means you can ship the SDK without a compliance review for every integration.

### 9. Configurable Output Mode

**Official SDK**: Spans only.

**This SDK**: `UiTelemetryMode` lets consumers choose their signal type:

| Mode | Output | Best For |
|------|--------|----------|
| **EVENTS** | OTel log records | Log-based backends (Loki, Elasticsearch) |
| **SPANS** | Zero-duration child spans | Trace-based backends (Jaeger, Tempo) |
| **BOTH** | Log records + spans | Full observability |

**Why it matters**: Different backends and teams prefer different signal types. The SDK adapts to the consumer instead of forcing a single output format.

## Architecture

```
Android SDK ── OTLP/gRPC :4317 ──> OTEL Collector ──> Backends (Dash0, Jaeger, etc.)
```

The SDK is 100% OTel-native:
- Uses official OTel SDK interfaces (`LoggerProvider`, `TracerProvider`, `MeterProvider`)
- Exports via standard OTLP/gRPC (no proprietary protocols)
- Follows OTel semantic conventions for all attributes
- Apache-2.0 licensed, vendor-neutral
- Designed for upstream contribution to `opentelemetry-android`

## Summary

| Capability | Official OTel Android | This SDK |
|-----------|----------------------|----------|
| Buffering | In-memory only (lost on crash) | RAM + SQLite (survives crashes) |
| Export mode | Continuous only | Conditional, Continuous, Hybrid |
| Selective flush | No | Yes (`flushWindow(minutes)`) |
| Export policies | No | DSL engine + visual builder |
| UI auto-instrumentation | Lifecycle + slow render | 10 modules: tap, scroll, text input, back press, freeze, screen, lifecycle, errors, vitals, network |
| Span hierarchy | Flat | Journey > Page > Interaction |
| Predictive export | No | Crash/network risk prediction |
| Privacy | Basic filtering | PII scrubbing, text redaction, privacy presets |
| Output modes | Spans | Events, Spans, or Both |
| Battery impact | 3-5% | <0.5% (CONDITIONAL mode) |
| Offline resilience | None | Full (disk buffer, crash recovery) |

The official SDK answers: "Did the app crash?" This SDK answers: "What was the user doing, on which screen, in what sequence, and what went wrong — without draining their battery or leaking their data."
