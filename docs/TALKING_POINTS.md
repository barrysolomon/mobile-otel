# Talking Points for OTel Android SIG

## The Pitch (30 seconds)

"We built a compatible superset of `opentelemetry-android`. Every upstream module runs in our framework unmodified via an adapter. We add 13 capabilities upstream doesn't have: conditional export, dual-tier crash-safe buffering, 22 instrumentation modules including Compose click detection, scroll tracking, and wireframe session replay. Here's the same app instrumented with both SDKs — same code, different telemetry depth."

## Demo Flow (5 minutes)

1. **Show both APKs** installed side-by-side on the emulator — same astronomy shop app, different SDK badges
2. **Same user flow** in each: browse telescopes, tap a product, add to cart, go to checkout
3. **Switch to Dash0 dashboard**: "Here's what upstream captured — 9 signal types, continuous export. Here's what we captured — 22 signal types, zero export until I triggered a crash."
4. **Highlight the gaps**:
   - Scroll events: "Upstream has none. We captured every RecyclerView scroll with direction."
   - Compose click identity: "Upstream has no compose-click module published. We resolved the tapped composable via semantics tree — here's the `testTag` and `Role`."
   - Screen orientation: "Upstream doesn't publish this module. We captured the rotation and used it as a breadcrumb."
5. **Conditional export payoff**: "Upstream sent 47 events over 3 minutes of browsing. We sent zero — until this crash triggered a selective flush of the last 2 minutes. That flush included the full journey context: every tap, scroll, screen view, and orientation change that led to the crash."

## Architecture Talking Points

- **Compatible superset**: `@Supersedes("crash")` on our `ErrorInstrumentation` means when both are on the classpath, ours wins — no duplicate telemetry
- **Bidirectional adapter**: upstream modules plug into our registry, our modules can plug into their framework (for merge validation)
- **Kotlin DSL matches upstream's pattern**: `MobileOtel.initialize(context) { }` mirrors `OpenTelemetryRumInitializer.initialize(context) { }`
- **No ByteBuddy**: upstream requires a Gradle build plugin for OkHttp, HttpURLConnection, and android-log instrumentation. We use runtime interceptors and reflection — zero build system changes for consumers.
- **Dual-tier buffering**: RAM ring buffer (5,000 events, lock-free) overflows to SQLite (50MB, 24h TTL). Survives process death. Crash-safety mirror deduplicates via `seqId`.
- **Policy DSL**: 21 matcher types, 10 action types. Evaluates on every event. Triggers selective flush windows. Authored visually in a React Flow control plane UI.

## Objection Handling

**"Why not contribute to upstream directly?"**
> We are. The adapter layer and `@Supersedes` annotation were specifically designed to make the merge path smooth. Phase 4 of our roadmap converges the interfaces so our modules directly extend `AndroidInstrumentation`. OTEPs for the buffering pattern and conditional export are in progress. This comparison demonstrates that our architecture is strictly superior — everything they have, plus more.

**"Isn't this just a vendor SDK?"**
> Apache 2.0 licensed, exports standard OTLP, uses the OpenTelemetry Java SDK directly. The policy engine, buffering, and instrumentation modules are all vendor-neutral. The visual control plane is the only Dash0-specific piece — and it's optional. An app using our SDK works with any OTLP-compatible backend.

**"Why conditional export? Isn't continuous telemetry better for observability?"**
> For mobile, bandwidth and battery are the constraints, not storage. Continuous export at 30-second intervals costs 3-5% battery on a typical device. Conditional export costs <0.5%. When something goes wrong — crash, freeze, error — we flush the last 2-5 minutes of buffered context. You get MORE context around problems (the full journey) while sending LESS data overall.

**"What about the 22 vs 9 module count? Aren't many of those niche?"**
> The core signal gap is 5 categories that matter for every mobile app: scroll tracking (UX flow), text input (form analytics), Compose click identity (modern UI), screen orientation (layout context), and system events (device health). The remaining modules (database, file-io, wireframe, screenshot) are opt-in but demonstrate the platform's extensibility. And our modules are richer even where upstream has equivalent coverage — our tap instrumentation captures swipe and long-press, our crash reporting includes coroutine errors with dedup and rate limiting.

## Numbers to Cite

- 22 instrumentation modules (vs upstream's 9 auto-installed)
- Dual-tier buffer: 5,000 events in RAM + 50MB on disk
- <0.5% battery in CONDITIONAL mode (vs 3-5% for continuous)
- 21 matcher types in the policy DSL
- 100% backward compatible: upstream modules run unmodified via adapter
- 72 files, ~10,500 lines shipped in the supersession epic so far
