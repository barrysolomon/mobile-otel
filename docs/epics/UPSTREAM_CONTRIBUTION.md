# Upstream Contribution Epic — opentelemetry-android

> **Goal:** Now that `opentelemetry-android` 1.5.0 marked the **Instrumentation API stable**, evaluate and stage a contribution of our `MobileInstrumentation` + `WindowEventHub` layer upstream, so the broader community gets UI-event auto-instrumentation and we reduce our long-term fork-maintenance surface.
> **Created:** 2026-06-25
> **Status:** PROPOSAL — no upstream PR opened yet. This document scopes what we would propose and the gaps a clean PR must close. For review.
> **Trigger:** Upstream dep bump 1.2.0-alpha → 1.5.0 (see [SEMCONV_AUDIT.md](../SEMCONV_AUDIT.md) refresh 2026-06-25). The bump migrated our adapter layer to the new `install(Context, OpenTelemetryRum)` signature (interface changed in 1.3.0) via a thin `MobileOpenTelemetryRum` shim.

---

## Why now

1.5.0 is the first release where `AndroidInstrumentation` (the interface our entire
modular instrumentation system extends) is a **stable** API surface — pre-1.5.0 it
was fair game for breaking changes (and did break between 1.2.0 and 1.3.0:
`install(InstallationContext)` → `install(Context, OpenTelemetryRum)`). A stable
extension point is the precondition for proposing additive instrumentation upstream
without chasing a moving target.

## What we would propose

A reusable **window-event fan-out** primitive plus the UI-interaction instrumentations
built on it — the part of our SDK that is generically useful and not Dash0-specific:

| Component | What it is | Upstream value |
|---|---|---|
| `WindowEventHub` + `WindowEventHubInstaller` | One `Window.Callback` wrapper per Activity, fanning touch/key events to N listeners via `CopyOnWriteArrayList` (no per-listener window wrapping) | No upstream module captures raw touch/key today; `view-click` swizzles view listeners. A hub is a cleaner, lower-overhead base. |
| `TapInstrumentation` | tap / long-press / swipe via `GestureDetector` on the hub | Overlaps upstream `view.click` (which expanded to long-press/scroll/fling in 1.4/1.5) — would need reconciliation, not duplication. |
| `ScrollInstrumentation`, `BackPressInstrumentation`, `TextInputInstrumentation` | scroll / back / text-input interaction events | No upstream equivalent. |

## What stays proprietary (not proposed)

The differentiators stay in our SDK — they are the product, and several are
Dash0-coupled or beyond OTel's current mobile scope:

- Dual-tier RAM+SQLite ring buffer & crash-safe persistence
- On-device **policy DSL** evaluator + conditional/predictive export
- `MobileLogRecordProcessor` choke-point enrichment (session-id / screen-name convergence)
- Screenshot / wireframe journey-replay capture
- Visual control-plane integration

## Mapping onto the stable interface

Our `MobileInstrumentation` already **extends** upstream `AndroidInstrumentation`:

```
AndroidInstrumentation (upstream, stable @ 1.5.0)
  fun install(context: Context, openTelemetryRum: OpenTelemetryRum)
  fun uninstall(context: Context, openTelemetryRum: OpenTelemetryRum)
  val name: String
        ▲
        │ (our bridge: maps Context+OpenTelemetryRum → Application+InstrumentationContext)
MobileInstrumentation (ours)
  fun install(application: Application, context: InstrumentationContext)
```

The bridge default-methods in `MobileInstrumentation.kt` insulate our ~11 concrete
modules from the upstream signature; the `UpstreamInstrumentationAdapter` +
`MobileOpenTelemetryRum` shim run the reverse direction (wrap our state as an
`OpenTelemetryRum` so upstream modules install through our registry).

## Gaps a clean PR must close

1. **`InstrumentationContext` is ours, not upstream's.** Upstream hands instrumentations
   `(Context, OpenTelemetryRum)`. Our richer context (`WindowEventHub`, `MobileSessionProvider`,
   `BreadcrumbManager`, `uiTelemetryMode`) has no upstream home. A contribution would either
   pass the hub via a new upstream extension point or keep the hub as a self-contained
   installer the instrumentation owns.
2. **`@Supersedes` is our concept.** Upstream has no superseding/dedup mechanism; contributed
   modules must not collide with existing ones (e.g. `view.click`). Either reconcile
   `TapInstrumentation` with `view.click` or propose the supersession mechanism itself.
3. **Semantic conventions.** Our UI events (`ui.tap`, `ui.scroll`, …) are VENDOR names today
   (see SEMCONV_AUDIT). Upstream contribution would require aligning to (or proposing) OTel
   mobile UI-event semconv — the `app.screen.name` convergence (this epic's sibling work) is
   the first step of that alignment.
4. **`WindowEventHub` is `@Incubating`.** Promote/stabilize our own API before offering it.

## Sequencing (proposed, not started)

1. Reconcile `TapInstrumentation` vs upstream `view.click` — decide merge vs. distinct module.
2. Open an upstream issue proposing the `WindowEventHub` primitive; gather maintainer feedback.
3. Align UI-event names with any emerging OTel mobile semconv before code PR.
4. PR the hub + one instrumentation (tap) as a vertical slice; iterate.

No upstream PR is opened by this epic — it is the scoping artifact for that decision.
