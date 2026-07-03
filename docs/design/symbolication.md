# Design: Crash Symbolication

**Status:** Phase 1 SHIPPED 2026-07-03 (`app.build.id` on all three platforms — see below). Phases 2–4 (upload tooling + backend symbolicator) remain proposed.

## Why this is the #1 competitive gap

Per the competitive analysis, an OTel-native SDK *replaces* the CNCF mobile SDKs easily but does **not** beat Datadog / Sentry / Embrace until it closes two product moats — **session replay** and **crash symbolication**. Symbolication is the higher-leverage of the two and the more tractable.

Today this SDK captures crashes but ships them **unsymbolicated**:
- **iOS** signal crashes produce essentially *no usable stack* without a third-party reporter (the async-signal-safe handler writes a minimal marker by design).
- **Android** native (NDK) crashes and R8/ProGuard-minified JVM stacks are unreadable.
- **React Native** JS stacks are Hermes-bytecode / minified.

DD/Sentry/Embrace all provide turnkey mapping upload + server-side symbolication (Embrace even **re-symbolicates** old crashes when a late dSYM arrives). A raw stack in Dash0 is not actionable; symbolication is what makes mobile crash telemetry worth adopting.

This is **mostly build-tooling + backend** work, not SDK capability. The SDK's only job is to make every crash *matchable* to the right mapping.

## Architecture

```
build time:  app build ──► [upload tool] ──► mapping store (keyed by build-id + platform + version)
                 │                                   ▲
                 └─ stamps build-id into the app     │ resolve
run time:    crash ──► SDK tags event with build-id ─┘
backend:     unsymbolicated stack + build-id ──► symbolicator ──► readable stack
```

Three independent pieces, each shippable on its own:
1. **SDK build-id tagging** (this repo, small) — every crash/error/span carries the identifiers needed to find its mapping.
2. **Mapping upload tooling** (build plugins/CLI) — push dSYM / R8 mapping / NDK symbols / JS source-maps to the store at build time.
3. **Server-side symbolicator** (Dash0 backend) — resolve frames using the stored mapping; ideally retroactively.

## Per-platform identifiers & mappings

| Platform | Build identifier the SDK must emit | Mapping artifact to upload | Notes |
|---|---|---|---|
| **iOS** | Mach-O **UUID** (`debug-id`) per image + `CFBundleShortVersionString`/build | **dSYM** (`*.dSYM`, keyed by UUID) | For *real* signal-crash stacks, evaluate bundling/optionally integrating **PLCrashReporter** — the current handler is intentionally minimal. Symbolication needs frame addresses + the image UUID. |
| **Android (JVM)** | R8 **mapping id** (the `pg_map_id` / build UUID R8 embeds) + versionCode | **`mapping.txt`** (R8/ProGuard) | R8 can embed a mapping UUID into the app; emit it so the backend picks the exact mapping. |
| **Android (NDK)** | per-`.so` **build-id** (GNU build-id / ELF note) | NDK **symbol tables** (unstripped `.so` / Breakpad `.sym`) | `ndk-stack`/Breakpad-style. Hardest platform. |
| **React Native** | JS bundle hash / **Hermes source-map id** + app version | **source-map** (`.map`, Hermes `.hbc.map`) | Tie the JS stack to the bundle that produced it. |

## Phase plan

### Phase 1 — SDK build-id tagging ✅ SHIPPED 2026-07-03
`app.build.id` is emitted as a **resource attribute** on every signal (so it rides along with crashes/errors automatically):

- **iOS** — `BuildIdReader` (`otel-ios-mobile/Sources/OTelMobileSDK/Resource/BuildIdReader.swift`) reads the main executable's Mach-O `LC_UUID` at runtime and `ResourceBuilder` stamps it unconditionally (canonical lowercase UUID). The dSYM carries the same UUID by construction, so no build tooling is needed.
- **Android** — the R8 mapping id is *not* runtime-readable, so `BuildId` (`otel-android-mobile/.../config/BuildId.kt`) reads a build-time manifest stamp and `MobileResource` emits it when present:
  ```xml
  <meta-data android:name="io.dash0.mobile.BUILD_ID" android:value="${dash0BuildId}" />
  ```
  ```kotlin
  // app build.gradle.kts — one UUID per build; use the SAME value to key the
  // mapping.txt upload (Phase 2)
  android.defaultConfig.manifestPlaceholders["dash0BuildId"] =
      java.util.UUID.randomUUID().toString()
  ```
  Unstamped apps simply omit the attribute. `extraResourceAttributes["app.build.id"]` overrides the stamp.
- **React Native** — `Dash0Mobile.start({ buildId })` forwards the JS-bundle/source-map id as `app.build.id` (wins over `extraResourceAttributes` on collision). The native layers still self-stamp where they can, so an RN iOS app's native crashes stay dSYM-matchable.

Deferred out of Phase 1 into later phases: NDK per-image build-ids on native crash frames, and the `app.version` / `app.build` consistency sweep.

### Phase 2 — Mapping upload tooling
- **iOS:** an Xcode build phase / Fastlane action / CLI that finds the dSYMs (incl. bitcode-recompiled ones from App Store Connect) and uploads them keyed by UUID.
- **Android:** a Gradle task hooked into the R8 task to upload `mapping.txt` (+ NDK symbols) keyed by mapping id, on release builds.
- **RN:** a Metro/Hermes post-build step to upload the source-map keyed by bundle id.
- Shared: a small uploader (auth to Dash0, dedupe by id, idempotent). Could be one CLI with per-platform adapters.

### Phase 3 — Server-side symbolicator (Dash0 backend)
- Resolve frames at ingest or query time using the mapping store. iOS: `atos`/symbolicator-style; Android: R8 retrace + `ndk-stack`/minidump_stackwalk; RN: source-map lookup.
- This is the backend investment that turns the SDK telemetry into a product.

### Phase 4 — Retroactive re-symbolication (Embrace parity)
- When a mapping arrives *after* crashes referencing its build-id, re-resolve the stored unsymbolicated stacks. A clear differentiator.

## Recommended starting point

**Implement Phase 1 now** (SDK build-id tagging on all platforms) — it's small, has no external dependency, is verifiable with unit tests, and is the hard prerequisite for Phases 2–4. Phase 2 (upload tooling) is the next increment and can begin per-platform in parallel. Phases 3–4 are Dash0 backend tracks scoped separately.
