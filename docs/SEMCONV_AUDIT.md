# Semantic-Conventions Audit — emitted telemetry vs OTel semconv

**Status:** Audited 2026-06-12 against `opentelemetry-semconv:1.39.0` (the
version the Android SDK depends on) — VERSIONING.md gate 3. Names are the
hardest thing to change after 1.0: dashboards, alerts, and Dash0 routing all
key on them. This document classifies every emitted name and freezes the
verdicts.

Classifications:
- **SEMCONV-STABLE** — matches a stable OTel convention; frozen.
- **SEMCONV-EXP** — matches a convention still experimental upstream; we
  track upstream (a rename upstream may be mirrored in a pre-1.0 minor, then
  never again without a deprecation cycle).
- **VENDOR** — Dash0/mobile-SDK-specific, no semconv equivalent; ours to
  govern, frozen at 1.0 like API surface.
- **MISALIGNED** — conflicts with semconv or with our own other platform;
  must converge in 0.4.0 (see action list).

---

## Resource attributes

| Attribute | Platforms | Verdict |
|---|---|---|
| `service.name`, `service.version` | all | SEMCONV-STABLE |
| `os.name`, `os.version`, `os.description`, `os.type` | Android/iOS | SEMCONV-EXP (registry) |
| `device.id`, `device.manufacturer`, `device.model.name`, `device.model.identifier` | Android/iOS | SEMCONV-EXP (registry) |
| `telemetry.sdk.name/version/language` | iOS | SEMCONV-STABLE |
| `telemetry.distro.name/version` | RN | SEMCONV-EXP |
| `dash0.resource.type` | all | VENDOR (properly namespaced; load-bearing for Dash0 Mobile-view routing — never rename) |
| `device.platform` | Android | ✅ dropped in 0.4.0 (was redundant with `os.name`) |

## Session & user enrichment (per-event)

| Attribute | Platforms | Verdict |
|---|---|---|
| `session.id` | iOS | SEMCONV-EXP (`session` registry) |
| `mobile.session.id` | Android, RN-via-Android | ✅ converged in 0.4.0: `session.id` (semconv) is emitted alongside; `mobile.session.id` is a transition alias dropped at 1.0. |
| `session.start_time`, `session.duration_ms`, `session.state`, `session.start_reason`, `session.termination_reason` | Android | VENDOR (extensions in the `session.*` namespace; semconv defines only `id`/`previous_id` today — collision risk accepted and tracked) |
| `user.id` | Android | SEMCONV-EXP (registry) |

## Events / logs

Semconv's mobile-events story (`device.app.lifecycle` with platform state
attributes) is experimental and shaped differently from ours (one event with
a state attribute vs. our flat names). Verdict: our flat names are VENDOR
conventions — they predate stable upstream guidance, Dash0 dashboards key on
them, and they stay. If upstream stabilizes mobile events post-1.0, a
mapping layer is a feature, not a rename.

| Event | Platforms | Verdict |
|---|---|---|
| `app.start`, `app.foreground`, `app.background` | Android (RN inherits) | VENDOR |
| `app.launch` | iOS (RN-iOS inherits) | **DEFERRED** — investigation (0.4.0) showed `app.launch` (LifecycleInstrumentation, once-per-install-call) and `app.start` (VitalsInstrumentation, start-timing vitals) are *different emitters* on iOS; a blind rename would double-count app starts. Needs a lifecycle-vs-vitals ownership decision before 1.0. |
| `app.crash`, `app.anr`, `app.memory_warning` | per platform | VENDOR |
| `app.error` (RN) with `exception.type/message/stacktrace` | RN | exception attrs SEMCONV-STABLE; event name VENDOR |
| `ui.tap`, `ui.scroll`, `ui.swipe`, `ui.text_input`, `ui.back_press`, `ui.screen_view` | per platform | VENDOR — ✅ converged: iOS `screen.view` renamed to `ui.screen_view` in 0.4.0 |
| `ui.jank`, `ui.freeze`, `ui.screenshot`, `ui.wireframe`, `ui.wireframe.ref` | per platform | VENDOR |
| `session.start`, `session.end` (iOS), `mobile.session.*` (Android) | per platform | VENDOR; naming-style mismatch tracked with the session-id convergence |
| `device.heartbeat`, `device.orientation` | Android | VENDOR |
| `prediction.cycle`, `prediction.high_risk_alert` | Android/iOS | VENDOR |
| `policy.config_applied`, `policy.config_fetch_failed` | iOS | VENDOR |
| `datastore.*` (8 names) | Android | VENDOR |
| `http.error` | all | VENDOR event name; carried `http.*` attrs are SEMCONV-STABLE |

## Spans

| Span | Platforms | Verdict |
|---|---|---|
| `page.<screenName>` | all | VENDOR (Dash0 page convention) |
| `screen.render` | Android | VENDOR |
| `app.start.cold/warm`, `app.startup`, `app.ttid` | per platform | VENDOR — note `app.startup` (iOS) vs `app.start.cold` (Android) naming drift; acceptable (different semantics), documented |
| `http.<METHOD>` spans (iOS/RN fetch) | iOS/RN | SEMCONV-STABLE shape (HTTP span name = method, `http.request.method`, `http.response.status_code`, `url.full`) |
| `journey.<name>` | Android/iOS | VENDOR (incubating feature) |
| `ui.tap/scroll/text_input` spans | Android (SPANS mode) | VENDOR |
| `file.read`, `file.write` | Android | VENDOR |

## Metrics

All `buffer.*`, `device.battery/memory/storage/thermal.*`, `prediction.*`,
`mobile.ui.frame.*`, `mobile.app.start.*`, `mobile.session.crash_free`,
`datastore.*`, `sdk.enabled`, `sdk.sample_rate`: **VENDOR**. Semconv has no
stable mobile-vitals metrics yet; ours are coherent and namespaced. Frozen
at 1.0 as-is. (`sdk.*` self-observability gauges are also the foundation for
the planned dropped-event counters — keep the namespace.)

---

## Action list (all scheduled for 0.4.0, mirrored in API_STABILITY.md)

1. ✅ **Session id convergence** (0.4.0) — Android emits `session.id` +
   `mobile.session.id`; alias drops at 1.0.
2. ⏸ **`app.launch` vs `app.start`** — deferred; see the events table (two
   different emitters on iOS; needs an ownership decision before 1.0).
3. ✅ **`screen.view` → `ui.screen_view`** on iOS (0.4.0, clean rename —
   BREAKING for dashboards filtering the old name; changelog carries it).
4. ✅ **`device.platform` dropped** (0.4.0).
5. Everything else: frozen as audited. New emitted names after this audit
   must be added to this document in the same PR (same discipline as
   API_STABILITY.md).
