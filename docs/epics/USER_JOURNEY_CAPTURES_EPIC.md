# Epic: User Journey + Screen/Wireframe Captures

**Status:** Phase 1 in progress
**Priority:** P0 (next epic after Production Readiness)
**Owner:** Barry Solomon
**Created:** 2026-05-07
**Target:** Phase 1 demoable Monday 2026-05-11

## Summary

Tie the existing journey API, screenshot capture, and wireframe capture into a
**coherent visual replay experience**. Today these are independent pieces:
`OTelMobile.startJourney()` returns a span, `ScreenshotInstrumentation` captures
on its own triggers, `WireframeInstrumentation` captures on its own triggers.
A journey is just a span; nothing automatically captures the visual state at
journey boundaries, and the control plane has no way to stitch journey timeline
+ screenshots + wireframes together.

This epic ties the pieces together end-to-end.

## Goals

1. **Journey-aware captures on Android** — `startJourney()` and `endJourney()`
   automatically trigger screenshot + wireframe captures. Captures emitted
   while a journey span is current carry the journey's `trace_id` so the
   control plane can stitch them.
2. **Capture on error inside a journey** — uncaught errors during a journey
   trigger a capture so the support team can see what the user was looking
   at when the app failed.
3. **Coherent replay payload** — screenshots, wireframes, breadcrumbs, and
   page spans for a journey can be queried together by `trace_id`.
4. **Cross-platform parity** for the journey API (already mostly there).
5. **Control plane renderer** — journey timeline view that shows captures
   inline.

## Phased Plan

### Phase 1: Android End-to-End (Demoable Monday)

| ID | Title | Files | Status |
|----|-------|-------|--------|
| UJ-001 | Journey-aware screenshot trigger | `ScreenshotInstrumentation.kt`, `OTelMobile.kt` | TODO |
| UJ-002 | Journey-aware wireframe trigger | `WireframeInstrumentation.kt`, `OTelMobile.kt` | TODO |
| UJ-003 | Thread journey span context into capture log attributes (trace_id, span_id) | both instrumentation modules | TODO |
| UJ-004 | Capture on error within active journey | `ErrorInstrumentation.kt` ↔ screenshot/wireframe | TODO |
| UJ-005 | `endJourney()` captures final state | `OTelMobile.kt` | TODO |
| UJ-006 | Demo app journey integration (booking flow) | `examples/demo-app/android/` | TODO |
| UJ-007 | Tests for journey-aware capture wiring | new test files | TODO |

### Phase 2: iOS Capture Instrumentation (post-Monday)

| ID | Title | Notes |
|----|-------|-------|
| UJ-010 | iOS `ScreenshotInstrumentation` | UIGraphicsImageRenderer / Metal layer capture |
| UJ-011 | iOS `WireframeInstrumentation` | UIView hierarchy walk, JSON serialization |
| UJ-012 | iOS journey-aware capture wiring | mirror Android Phase 1 |
| UJ-013 | iOS capture-on-error integration | mirror Android UJ-004 |
| UJ-014 | iOS privacy redaction strategy | UITextField/SecureField text redaction (mirror Android `redactTextViews`) |

### Phase 3: React Native Bridge

| ID | Title | Notes |
|----|-------|-------|
| UJ-020 | RN bridge passes journey API through to native | thin marshalling, both platforms |
| UJ-021 | RN auto-capture toggle for journey events | `autoCapture.journey` config |

### Phase 4: Control Plane Renderer

| ID | Title | Notes |
|----|-------|-------|
| UJ-030 | Journey timeline view component | groups by `trace_id` |
| UJ-031 | Screenshot carousel | paged by capture sequence |
| UJ-032 | Wireframe overlay viewer | renders the wireframe JSON tree |
| UJ-033 | Breadcrumb timeline integration | merge into journey view |

## Cross-Platform Status

| Feature | Android | iOS | RN |
|---------|---------|-----|-----|
| Journey API (`startJourney`/`endJourney`) | ✅ GA | ✅ GA | ✅ GA |
| Screen tracking (`page.X` span + `ui.screen_view`) | ✅ GA | ✅ GA | ❌ |
| Screenshot capture | ⚠️ Incubating | ❌ Phase 2 | ❌ Phase 3 |
| Wireframe capture | ⚠️ Incubating | ❌ Phase 2 | ❌ Phase 3 |
| Journey-aware auto-capture | ❌ Phase 1 | ❌ Phase 2 | ❌ Phase 3 |
| Control plane renderer | ❌ Phase 4 | ❌ Phase 4 | ❌ Phase 4 |

## OTel-Native Constraint

Per the no-drift rule, all event/log/attribute names must follow existing OTel
or already-shipped SDK conventions:

- **Existing log attributes** (keep using):
  - `mobile.screenshot.data_url` — base64-encoded screenshot
  - `mobile.wireframe.data` — JSON-serialized view hierarchy
  - `screen.name`, `mobile.session.id`, `mobile.view.id`
- **Linking captures to journeys** — use OTel `trace_id` + `span_id` from the
  current `Context` rather than inventing a `mobile.journey.id` attribute.
  Captures emitted inside a journey span automatically carry these via
  standard OTel `LogRecordBuilder.setContext(Context.current())`.
- **No new event-name prefixes.** No `mobile.journey.*` or `journey.*`
  namespace inventions. Journeys remain spans named after the caller's
  `name` argument.

## Demo Flow (Phase 1)

1. User opens scheduling demo app
2. App calls `OTelMobile.startJourney("book_appointment")`
3. → screenshot + wireframe captured (auto), tagged with journey trace_id
4. User navigates Calendar → Book → enters details → submits
5. → page spans, taps, wireframe captures all carry journey trace_id
6. App calls `OTelMobile.endJourney(journey)`
7. → final screenshot + wireframe captured
8. In Dash0: query by trace_id → full visual timeline of the journey

## Out of Scope

- Video capture (screenshot bursts only)
- Heatmaps / aggregated UX analytics
- Journey funnel analytics in the control plane
- Network request bodies in the journey replay payload
