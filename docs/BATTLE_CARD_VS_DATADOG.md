# Dash0 Mobile Observability vs Datadog Mobile RUM — Battle Card

> **Audience:** Sales engineering, product marketing, field teams
> **Last updated:** 2026-07-04
> **Status gaps:** session replay *viewer* (capture primitives ship today; the viewer is in development). iOS SDK and crash symbolication now ship.
> **Threat level:** High — Datadog has the most complete mobile RUM product in market. Win on architecture and economics.

---

## Elevator Pitch (30 seconds)

"Datadog collects everything, uploads it all, and charges you per session. Dash0 puts intelligence on the device — a policy engine that decides what to export in real time. You get deeper instrumentation, crash-safe buffering, and selective flush that sends only the context around incidents. Same visibility, fraction of the data volume, no vendor lock-in. And unlike Datadog, we export standard OTLP — your telemetry isn't trapped."

---

## Honest Assessment

Datadog Mobile RUM is a strong, mature product. They have iOS parity, session replay, crash symbolication, NDK support, and deep platform integration. We don't win by claiming they're bad — we win on:

1. **Architecture** — on-device intelligence vs collect-everything
2. **Economics** — cost scales with incidents, not users
3. **Openness** — OTLP native vs proprietary lock-in
4. **Signal depth** — more instrumentation modules with richer data

---

## Head-to-Head Feature Matrix

### Instrumentation Coverage

| Signal | Dash0 | Datadog | Edge |
|--------|-------|---------|------|
| Crash reporting (JVM) | Uncaught + coroutine + RxJava, dedup, rate-limited | Automatic, full symbolication | **Datadog** (symbolication maturity) |
| Crash reporting (NDK/native) | Not yet | `NdkCrashReports.enable()` + symbol upload | **Datadog** |
| ANR detection | Yes (via vitals module) | Fatal (API 30+) + non-fatal (any version) | **Datadog** (broader coverage) |
| App start (cold/warm/hot) | Yes (TTID) | Yes (TTID + TTFD) | Parity |
| Slow/frozen rendering | Jank + freeze detection | Slow (>16ms) + frozen (>700ms) + refresh rate | Parity |
| Network requests | OkHttp interceptor, no build plugin | OkHttp, Cronet, URLSession, Alamofire, Apollo GraphQL | **Datadog** (more clients) |
| User actions (tap) | Tap + long-press + swipe + gesture | Taps, swipes, scrolls, back gestures | Parity |
| Scroll tracking | RecyclerView, throttled, direction | Counted as user action (less detail) | **Dash0** (richer) |
| Text input | EditText focus/blur | Not auto-instrumented | **Dash0 only** |
| Back press | Hardware + gesture nav | Back gesture tracked as action | Parity |
| Compose click | Semantics tree walker, testTag identity | Supported via Session Replay | **Dash0** (deeper identity) |
| Screen orientation | Rotation tracking | Not auto-instrumented | **Dash0 only** |
| Screen transitions | Activity/fragment with page spans | Activity/Fragment/UIViewController + SwiftUI | Parity |
| Database queries | Room/SQLite spans | Not auto-instrumented | **Dash0 only** |
| File I/O | File operation spans | Not auto-instrumented | **Dash0 only** |
| System events | Battery, power, thermal, storage | Not auto-instrumented | **Dash0 only** |
| Timber logging bridge | OTel log integration | Datadog Logs SDK (separate product) | **Dash0** (unified) |
| Screenshot capture | Pixel capture, text redaction | Via Session Replay (wireframe-based) | Different approach |
| Wireframe capture | View hierarchy JSON | Via Session Replay (wireframe reconstruction) | Different approach |
| Debug widget | Live in-app overlay | None | **Dash0 only** |
| Device health metrics | OTel gauges (memory, battery, thermal, storage) | CPU ticks/sec, memory utilization per view | **Dash0** (richer) |
| Journey breadcrumbs | 50-entry circular buffer on crash/freeze | Error context via RUM session timeline | **Dash0** (on-device) |
| Long tasks | Via freeze detection | Main-thread blocking >100ms threshold | Parity |
| Resource timing | Network spans with status/size | DNS, TLS, connect, first byte, download | **Datadog** (more granular) |
| **Total auto-instrumented modules** | **19** | **~14** | |

### Where Datadog Wins Today

| Capability | Datadog | Dash0 Status |
|------------|---------|--------------|
| **iOS SDK** | Shipping (Swift/ObjC + SwiftUI + tvOS) | Shipping (Swift + SwiftUI), E2E-validated in Dash0 |
| **Session Replay** | Wireframe-based, privacy masking, Compose support | Capture primitives shipped, viewer in development |
| **Crash symbolication** | ProGuard/R8 auto-upload, NDK symbols, dSYM CLI | Shipping — build-id tagging + symbol-upload CLI (NDK native symbols still on roadmap) |
| **NDK/native crash** | C++ crash reporting | Not planned for initial release |
| **APM trace correlation** | W3C traceparent + Datadog headers, 1-click in UI | Collector processor exists, UX less polished |
| **GraphQL instrumentation** | Apollo client interceptor | Not yet |
| **Kotlin Multiplatform** | Supported | Not yet |
| **React Native** | Full SDK + Session Replay | Shipping — native-first bridge, complete on Android + iOS, E2E-validated |
| **Production maturity** | Years in production, massive customer base | Pre-release, demo-validated |

### Architecture

| Capability | Dash0 | Datadog |
|------------|-------|---------|
| **On-device policy engine** | Full DSL: 21 matchers, 10 actions, remote-updatable | **None** — flat sample rate at init, static event mappers |
| **Export modes** | 3 modes: Conditional (<0.5% battery) / Continuous / Hybrid | Continuous only — all events batched and uploaded |
| **Selective flush** | Time-window flush (e.g., "last 2 min around crash") | Not possible — data sent in arrival order |
| **Predictive flush** | Crash risk + network loss risk monitoring, pre-emptive export | None |
| **Dynamic sampling** | Remote policy update, no app release needed (E2E validation in progress) | Requires app redeployment to change sample rate |
| **Buffering** | Dual-tier: RAM (5K events, lock-free) + SQLite (50MB, 24h TTL, crash-safe seqId dedup) | Single-tier: TLV batch files in app cache, backpressure at 1024 items |
| **Crash recovery** | Survives process death, deduplicates via monotonic seqId | Crash reports survive, sent on next launch |
| **Visual config editor** | React Flow graph → DSL compiler → remote push (E2E validation in progress) | None — code-level config only |
| **Server-side filtering** | N/A (filtered on-device before export) | "RUM Without Limits" — capture all, filter server-side, pay for ingest + retained |
| **OTLP export** | Native — standard OTLP to any backend | **Not supported** from RUM SDK. Proprietary intake format only |
| **Backend OTel support** | N/A | Datadog Agent accepts OTLP ingest (backend only) |
| **Build system impact** | Zero — runtime interceptors | Datadog Gradle plugin for ProGuard/R8 mapping upload |

### Commercial

| Factor | Dash0 | Datadog |
|--------|-------|---------|
| **Pricing model** | TBD (Dash0 platform) | ~$1.50/1K sessions + Session Replay add-on + "RUM Without Limits" ingest fees |
| **Cost transparency** | TBD | Notoriously opaque — surprise bills common at scale |
| **Data efficiency** | Conditional mode = near-zero bandwidth when healthy | All data shipped always, filtered server-side (after you've paid for ingest) |
| **Cost at scale (1M DAU)** | Fraction of Datadog's volume | ~$1.5K/day for sessions alone, before replay/retention |
| **Lock-in risk** | Zero — OTLP to any backend | High — proprietary RUM format, no OTLP export path |
| **License** | Apache 2.0, vendor-neutral | Open-source SDK, proprietary backend |

---

## Strategic Differentiators

### 1. On-Device Intelligence (No Competitor Has This)

This is the core architectural advantage over every competitor, including Datadog.

Datadog's model: Set a flat sample rate at init → collect everything from sampled sessions → upload all of it → filter server-side via "RUM Without Limits" → pay for both ingest AND retained data.

Dash0's model: Capture everything into a dual-tier buffer → evaluate policies on-device in real-time → export only when conditions match → pay for signal, not noise.

**Why this matters:** Datadog's "RUM Without Limits" sounds powerful, but you're still paying for 100% ingest before applying server-side filters. With Dash0, the filtering happens on-device — data that doesn't matter never leaves the phone.

### 2. Data Economics (The Enterprise CFO Argument)

For a 1M DAU app with 2.5 sessions/user/day:
- **Datadog**: 2.5M sessions/day × $1.50/1K = ~$3,750/day = ~$112K/month (before replay, retention extras)
- **Dash0 Conditional**: Near-zero export during normal operation; selective flush windows around incidents only

Even if Dash0's per-unit pricing were identical, the volume reduction from conditional export makes the total cost dramatically lower.

### 3. Vendor Independence (The CTO Argument)

Datadog's mobile SDK sends data in a proprietary format. There is no OTLP export path. Once you instrument with Datadog RUM, your mobile telemetry is locked in.

Dash0 exports standard OTLP. Point it at Dash0, Grafana, Jaeger, or any OTLP-compatible backend. Switch backends without re-instrumenting your app.

### 4. Remote Policy Control (The SRE Argument)

Datadog: To change your mobile sampling rate, you redeploy the app. To add conditional logic, you write custom `EventMapper` code and redeploy.

Dash0: Push a new policy from the visual control plane UI. Change geo-fences, device matchers, sampling rates, flush windows — all without an app release. During an incident, increase telemetry capture for affected user segments in seconds, not sprint cycles.

---

## Gaps to Acknowledge (Closing Shortly)

| Gap | Status | How to Handle |
|-----|--------|---------------|
| **iOS SDK** | Shipping (Swift + SwiftUI), E2E-validated | "Full iOS SDK ships today — same policy DSL, buffering, and control plane as Android, validated end-to-end in Dash0." |
| **Crash symbolication** | Shipping — build-id tagging + symbol-upload CLI (NDK native on roadmap) | "Build-id tagging and the symbol-upload CLI ship today for R8/ProGuard and dSYM. NDK native symbols are on the roadmap." |
| **Session Replay viewer** | Capture primitives shipped, viewer in development | "We capture screenshots and wireframes today. The replay viewer is in development. Our journey breadcrumbs already give you the crash context replay is designed to provide." |
| **NDK/native crashes** | Not planned for initial release | "We focus on JVM + Kotlin first. NDK is on the roadmap for apps with significant native code." |
| **React Native / KMP** | React Native shipping (Android + iOS); KMP not yet | "React Native ships today on both platforms via a native-first bridge. KMP is on the roadmap." |
| **Production maturity** | Demo-validated, 28 automated scenarios, security hardened | "We've completed security hardening (10/10 audit items) and have 28 automated validation scenarios. Production readiness (CI/CD, publishing) is the next phase." |

---

## Objection Handling

### "Datadog is the market leader in observability. Why would we bet on Dash0 for mobile?"

> "Datadog is excellent for backend APM. But their mobile architecture is the same collect-everything model from 2018 — flat sampling, continuous upload, server-side filtering after you've paid for ingest. We built mobile-first: on-device policy evaluation, selective flush, crash-safe dual-tier buffering. It's a generational architecture difference, not a feature gap."

### "We already use Datadog for everything — APM, logs, infra. Adding their RUM is natural."

> "We export standard OTLP. Your Datadog Agent already accepts OTLP. So our mobile telemetry flows into your existing Datadog dashboards — but with less data volume, lower cost, and no lock-in. You get the integration without the premium."

### "Datadog has Session Replay. That's a must-have for our mobile team."

> "Two points. First, we ship screenshot and wireframe capture today — the replay viewer is in development. Second, our journey breadcrumbs give you what replay is actually used for: understanding what the user did before a crash. A structured timeline of every tap, scroll, screen transition, and API call — as OTel spans you can query, not a video you have to scrub through."

### "What about their 'RUM Without Limits' — capture everything, filter later?"

> "That's marketing for 'pay for ingest, then pay again for retention.' You're uploading 100% of sessions from the device, paying Datadog's ingest fee, then applying server-side filters to decide what to keep. With Dash0, the filtering happens on-device. Data you don't need never leaves the phone, never hits the wire, never costs you a cent."

### "Datadog pricing is predictable per session."

> "Ask any Datadog customer at scale if their bill was predictable. Session-based pricing with add-ons for replay, retention, custom metrics, and 'without limits' ingest creates compounding costs. Our conditional export model means cost scales with incidents, not user count."

### "You don't have iOS yet."

> "iOS ships today with on-device intelligence from day one — something Datadog's iOS SDK still doesn't have. Same policy engine, buffering model, DSL, and control plane as Android, validated end-to-end in Dash0."

### "Datadog has native NDK crash reporting. You don't."

> "Correct — NDK crash reporting is on our roadmap but not in the initial release. For apps that are primarily Kotlin/Java, our crash reporting is actually richer: coroutine error capture, 5-minute deduplication, rate limiting, and automatic selective flush of the buffer window around the crash. For apps with significant C++ native code, this is a genuine gap we'll close."

---

## Demo Talking Points (5-Minute Competitive Pitch)

1. **Architecture difference**: "Datadog's SDK is a pipe — collect, batch, upload, filter in the cloud. Ours is a brain — evaluate policies on-device, decide what matters, export selectively. Same underlying OTel, fundamentally different approach."

2. **Conditional export live demo**: "I'm using the demo app — browsing, tapping, scrolling. Check the export stats: zero events sent. The buffer is accumulating everything locally. Now I trigger a crash... and the SDK flushes exactly the last 2 minutes of context. Datadog would have been shipping all of this continuously, whether a crash happened or not."

3. **Policy engine demo**: "From the control plane UI, I push a new policy: increase sampling for users on low-memory devices in the US. No app release. Takes effect on next config poll. Show me how to do this with Datadog without redeploying."

4. **Cost math**: "1M DAU, 2.5 sessions each. That's $3,750/day to Datadog for RUM sessions alone — before replay or retention. With conditional export, we send a fraction of that data volume. The math isn't close."

5. **Lock-in**: "Our SDK exports OTLP. Datadog's doesn't — and can't. If your CTO asks 'can we switch backends without re-instrumenting every app?' — with Datadog, the answer is no."

---

## Key Numbers

| Metric | Dash0 | Datadog |
|--------|-------|---------|
| Instrumentation modules | 19 | ~14 |
| Policy matcher types | 21 | 0 (flat sample rate only) |
| Export modes | 3 (Conditional/Continuous/Hybrid) | 1 (Continuous) |
| Battery impact (conditional) | <0.5% | Not published (continuous only) |
| Dynamic config update | Remote push, no app release | Requires redeployment |
| RAM buffer capacity | 5,000 events | N/A (direct to disk) |
| Disk buffer capacity | 50 MB, 24h TTL | App cache, backpressure at 1024 |
| Crash recovery | seqId dedup, survives force-kill | Crash reports sent on next launch |
| Build plugin required | No | Yes (Datadog Gradle plugin) |
| OTLP export | Native, standard | Not available from RUM SDK |
| iOS SDK | Shipping (Swift/SwiftUI), E2E-validated | Shipping (Swift/ObjC/SwiftUI/tvOS) |
| Session Replay | Capture primitives shipped, viewer in dev | Full wireframe replay + privacy masking |
| Crash symbolication | Build-id tagging + symbol-upload CLI (shipping); NDK native on roadmap | ProGuard/R8 auto-upload, NDK, dSYM |
| Pricing (1M DAU est.) | Fraction of DD volume costs | ~$112K/month (sessions only) |
