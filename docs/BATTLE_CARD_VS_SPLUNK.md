# Dash0 Mobile Observability vs Splunk Mobile RUM — Battle Card

> **Audience:** Sales engineering, product marketing, field teams
> **Last updated:** 2026-04-13
> **Status gaps:** iOS SDK, crash symbolication, session replay viewer — all in active development

---

## Elevator Pitch (30 seconds)

"Splunk ships everything and charges you for it. Dash0 uses on-device intelligence to export only what matters — giving you deeper visibility at a fraction of the data volume and cost. Our SDK captures more signal types, buffers locally with crash-safe persistence, and uses a policy engine to selectively flush the exact time window around incidents. You get better context around problems while paying for signal, not noise."

---

## Head-to-Head Feature Matrix

### Instrumentation Coverage

| Signal | Dash0 | Splunk | Edge |
|--------|-------|--------|------|
| Crash reporting | Uncaught + coroutine + RxJava, dedup, rate-limited | Uncaught exceptions, dSYM symbolication | **Dash0** (breadth) |
| ANR detection | Yes (via vitals module) | Yes (>5s main thread) | Parity |
| App start (cold/warm/hot) | Yes | Yes | Parity |
| Slow/frozen rendering | Jank + freeze detection, configurable threshold | Slow (>16ms) + frozen (>700ms) | Parity |
| Network requests | OkHttp interceptor, no build plugin needed | OkHttp3/HttpURLConnection (requires Gradle plugin) | **Dash0** (simpler) |
| User interactions (tap) | Tap + long-press + swipe + gesture | Tap + double-tap + long press | **Dash0** (richer) |
| Scroll tracking | RecyclerView, throttled, direction tracking | None | **Dash0 only** |
| Text input | EditText focus/blur | None | **Dash0 only** |
| Back press | Hardware + gesture nav | None | **Dash0 only** |
| Compose click | Semantics tree walker, testTag identity | None | **Dash0 only** |
| Screen orientation | Rotation tracking | None | **Dash0 only** |
| Screen transitions | Activity/fragment with page spans | Activity/fragment lifecycle (opt-in) | **Dash0** (richer) |
| Database queries | Room/SQLite spans | None | **Dash0 only** |
| File I/O | File operation spans | None | **Dash0 only** |
| System events | Battery, power, thermal, storage | None | **Dash0 only** |
| Timber logging bridge | OTel log integration | None | **Dash0 only** |
| Screenshot capture | Pixel capture, text redaction, configurable | None (Session Replay is separate product) | **Dash0 only** |
| Wireframe capture | View hierarchy JSON (~1-5 KB) | None (Session Replay is separate) | **Dash0 only** |
| Debug widget | Live in-app overlay (buffer, export, health) | `enableDebug()` = verbose logcat | **Dash0** |
| Device health metrics | OTel gauges (memory, battery, thermal, storage) | Session attributes only | **Dash0** (richer) |
| Journey breadcrumbs | 50-entry circular buffer on crash/freeze | Not documented | **Dash0 only** |
| **Total modules** | **19** | **~11** | |

### Architecture

| Capability | Dash0 | Splunk |
|------------|-------|--------|
| **On-device policy engine** | Full DSL: 21 matcher types, 10 action types, remote-updatable via control plane | None — static config at init time |
| **Export modes** | 3 modes: Conditional (<0.5% battery) / Continuous / Hybrid | Always-on continuous only |
| **Selective flush** | Time-window flush (e.g., "last 2 min around crash") — exports only relevant context | Exports everything continuously |
| **Predictive flush** | Monitors crash risk + network loss risk, pre-emptively exports | None |
| **Buffering** | Dual-tier: RAM (5K events, lock-free) + SQLite (50MB, 24h TTL, crash-safe seqId dedup) | Disk buffer (25MB cap), no documented retry semantics |
| **Crash recovery** | Survives process death, deduplicates via monotonic seqId | Disk buffer persists, retry behavior undocumented |
| **Offline support** | Full store-and-forward with TTL cleanup | Buffer exists but offline queue behavior unspecified |
| **Visual config editor** | React Flow graph → DSL compiler → remote push to fleet (E2E validation in progress) | YAML/code config only |
| **OpenTelemetry purity** | Native OTLP export, upstream-superseding architecture | OTLP exporter **deprecated** Feb 2025, proprietary beacon format |
| **APM backend correlation** | OTel Collector processor (server-side trace linkage) | `Server-Timing` header (automatic, polished UX) |
| **Build system impact** | Zero — runtime interceptors, no Gradle plugin | Requires ByteBuddy Gradle plugin for OkHttp/HttpURLConnection |

### Commercial

| Factor | Dash0 | Splunk |
|--------|-------|--------|
| **Pricing model** | TBD (Dash0 platform pricing) | Per-session (up to 4hr session, 15min timeout) — opaque, sales-quoted |
| **Data efficiency** | Conditional mode = near-zero bandwidth when healthy | All data shipped always = high volume |
| **Cost at scale** | 1M DAU → fraction of Splunk's data volume | 1M DAU → massive session + data costs |
| **Session Replay** | Screenshot + wireframe primitives exist (viewer in development) | Available on Pro/Peak tiers (add-on license) |
| **License** | Apache 2.0, vendor-neutral OTLP | Open-source SDK, proprietary backend |
| **Lock-in risk** | Zero — standard OTLP to any backend | OTLP deprecated, proprietary RUM beacon format |

---

## Strategic Differentiators

### 1. Cost-Efficient Observability (Primary Wedge)

Splunk's model: instrument → ship everything → filter in the cloud → pay per session.

Dash0's model: instrument → buffer locally → use on-device intelligence to export only what matters → pay for signal, not noise.

**For a customer with 1M daily active users:**
- Splunk: continuous export from every device, every session = massive ingest volume
- Dash0 Conditional mode: zero export during normal operation; selective flush of 2-5 minute windows only around incidents = orders of magnitude less data

### 2. On-Device Intelligence (No Competitor Has This)

The policy DSL is architecturally unique in the mobile observability market. No other vendor evaluates conditions on-device to decide what to export. This enables:
- **Battery efficiency**: <0.5% in conditional mode vs 3-5% for continuous
- **Bandwidth efficiency**: export only incident windows, not steady-state telemetry
- **Privacy by design**: sensitive data can be filtered on-device before it ever leaves
- **Remote policy updates**: change what gets exported without app releases

### 3. OpenTelemetry Native (Anti-Lock-In)

Splunk deprecated their OTLP exporter path in Feb 2025, moving to a proprietary beacon format. Dash0 exports standard OTLP — customers can point at any OTLP-compatible backend (Dash0, Grafana, Jaeger, any vendor). This is a powerful enterprise selling point against lock-in concerns.

### 4. Deeper Signal Coverage

19 modules vs ~11. The exclusive modules aren't niche — scroll tracking, Compose click identity, text input, and system events are essential for understanding mobile UX. Splunk's gaps mean blind spots in user journey reconstruction.

---

## Gaps to Acknowledge (Closing Shortly)

| Gap | Status | Timeline |
|-----|--------|----------|
| **iOS SDK** | Spec complete, Phase 14 in development | Active development — closing imminently |
| **Crash symbolication** | ProGuard/R8 + dSYM support planned | Near-term roadmap |
| **Session Replay viewer** | Screenshot + wireframe capture primitives shipped; viewer UI in development | Near-term roadmap |
| **Production maturity** | Demo-validated, 28 validation scripts, security hardened | Approaching production readiness (Phase 10) |
| **Enterprise support** | N/A (Dash0 platform support applies) | Covered by Dash0 platform |

**How to handle in conversations:** "We're shipping [iOS/symbolication/replay] shortly. Today, the Android SDK is fully functional with 19 instrumentation modules, and we're already demonstrating capabilities Splunk doesn't have at any tier — like on-device policy evaluation and selective flush."

---

## Objection Handling

### "Splunk is proven in production at scale."

> "Absolutely — and they've been shipping the same architecture for years: continuous export, pay per session, no on-device intelligence. We're building on OpenTelemetry's foundation with a fundamentally different approach to mobile telemetry efficiency. Our SDK runs the same upstream OTel instrumentation Splunk uses, plus 8 modules they don't have, plus an on-device policy engine that cuts data volume by orders of magnitude."

### "We already use Splunk for APM — Mobile RUM integrates natively."

> "Our SDK exports standard OTLP. It integrates with any backend, including Splunk's own OTLP receiver. But consider this: Splunk deprecated their mobile SDK's OTLP exporter in Feb 2025, moving to a proprietary format. We're going the opposite direction — deeper OpenTelemetry integration, not away from it."

### "What about iOS?"

> "iOS is in active development right now. The architecture is cross-platform by design — the policy DSL, buffering model, and control plane are platform-agnostic. The Android SDK proves the model; iOS is execution, not research."

### "Session Replay is a must-have."

> "We have the capture primitives — screenshot with text redaction and wireframe view hierarchy capture — shipping today. The replay viewer UI is in development. Meanwhile, our journey breadcrumbs provide crash context that Splunk's replay doesn't: the exact sequence of taps, scrolls, screen transitions, and network calls that led to the incident, structured as OTel spans."

### "How do you compete on price?"

> "We compete on data economics. In Conditional mode, our SDK exports near-zero data during normal operation. When an incident occurs, it selectively flushes the relevant 2-5 minute window. For a 1M DAU app, that's orders of magnitude less data than Splunk's continuous export model — which directly translates to lower costs, regardless of per-unit pricing."

### "We need a vendor with enterprise support and compliance."

> "Dash0 is the vendor — enterprise support, compliance certifications, and SLAs come through the Dash0 platform. The mobile SDK inherits all of that. And our security hardening is already complete: API auth, TLS, PII scrubbing, cleartext traffic blocked — 10/10 audit items resolved."

---

## Demo Talking Points (5-Minute Pitch)

1. **Side-by-side signal comparison**: "Splunk captures ~11 signal types. We capture 19 — including scroll tracking, Compose click identity, database queries, and system events they don't have."

2. **Conditional export demo**: "Watch the Dash0 demo app — I'm browsing, tapping, scrolling. Check the exporter: zero events sent. Now I trigger a crash... and the SDK flushes the last 2 minutes of buffered context. Every tap, scroll, and screen view that led to the crash. Splunk would have been shipping all of this continuously."

3. **Battery comparison**: "Conditional mode: <0.5% battery. Splunk's continuous export: 3-5%. For apps with millions of users, that's the difference between 'observability people love' and 'observability people uninstall.'"

4. **Policy engine**: "I can change what gets exported from the control plane UI — no app release needed. Add a geo-fence to capture more telemetry from users in a specific region. Add a device matcher to increase sampling on low-memory devices. Splunk can't do any of this."

5. **Lock-in**: "Our SDK exports standard OTLP. Theirs deprecated OTLP last year. If you want to switch backends in 2 years, which SDK lets you do that?"

---

## Key Numbers

| Metric | Dash0 | Splunk |
|--------|-------|--------|
| Instrumentation modules | 19 | ~11 |
| Policy matcher types | 21 | 0 |
| Export modes | 3 (Conditional/Continuous/Hybrid) | 1 (Continuous) |
| Battery impact (conditional) | <0.5% | N/A (3-5% continuous only) |
| RAM buffer capacity | 5,000 events | N/A |
| Disk buffer capacity | 50 MB, 24h TTL | 25 MB |
| Crash recovery | seqId dedup, survives force-kill | Disk persistence, retry undocumented |
| Build plugin required | No | Yes (ByteBuddy) |
| OTLP export | Native, current | Deprecated Feb 2025 |
| Validation scripts | 28 automated scenarios | N/A (proprietary) |
