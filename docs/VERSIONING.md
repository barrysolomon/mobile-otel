# Versioning & API Stability Policy

## Current state

- **API tiers:** every public symbol is assigned a tier in [API_STABILITY.md](API_STABILITY.md) (gate 2, reviewed 2026-06-12); emitted telemetry names are frozen per [SEMCONV_AUDIT.md](SEMCONV_AUDIT.md) (gate 3).
- **Version:** `0.9.0-beta` across all three artifacts (npm `@barrysolomon/mobile-react-native`, Android Maven `io.github.barrysolomon:mobile` + siblings, iOS SwiftPM tag `v0.9.0-beta`).
- **Stability:** **beta** as of `0.9.0-beta` — the API surface is feature-complete and 1.0-candidate, but the full SemVer guarantee begins at `1.0.0`. Un-annotated public symbols are treated as stable-intent and will follow SemVer from 1.0 (breaking change ⇒ MAJOR bump + deprecation cycle); symbols annotated `@Incubating` (Kotlin) / documented as experimental (Swift/TS) may still change in a MINOR — see the tier table below and [API_STABILITY.md](API_STABILITY.md).

## Semantic Versioning (effective at 1.0)

From `1.0.0` onward the project follows [SemVer 2.0](https://semver.org/):

- **MAJOR** — breaking change to a *stable* public API.
- **MINOR** — backward-compatible additions (new config, new instrumentation, new optional params with defaults).
- **PATCH** — backward-compatible bug fixes.

Pre-1.0 (`0.x.y`): **minor (`0.x`) bumps may break**; patch (`0.x.y`) are fixes only. The three artifacts are released in lockstep on a shared version number.

## Stability tiers

Every public symbol belongs to one tier. The tier is the contract.

| Tier | Meaning | Marker |
|---|---|---|
| **Stable** | Covered by SemVer; won't break without a MAJOR bump (+ deprecation cycle). | (default at 1.0; no annotation) |
| **Incubating** | Shipping and supported, but the signature/behavior may change in a MINOR. | `@Incubating` (Kotlin); "Experimental" doc note (Swift/TS) |
| **Internal** | Not API. May change/disappear anytime. | `internal` / not exported |

**Emitted telemetry** (attribute names, semantic conventions) has its own stability: it tracks the upstream OpenTelemetry semantic-convention stability for each signal. Mobile-specific attributes not yet in a stable semconv are **experimental** until the convention stabilizes.

### Tiering adopted at 1.0
- **Stable:** `OTelMobile.start`/builder core, `MobileConfig` core fields (serviceName, endpoint, auth, exportMode, sampling, protocol), `start`/`shutdown`/`flushWindow`, manual `startSpan`/`log`/`recordMetric`, the RN `StartConfig` core, network/lifecycle/crash auto-instrumentation.
- **Incubating (stay experimental past 1.0):** screenshot/wireframe capture + consent API, the policy DSL / predictive export, the remote kill-switch wire schema, transport pinning/HMAC config, debug-widget.

## The 1.0 gate review (closed 2026-07-08 — all five gates satisfied)

This section originally recommended **against** cutting 1.0 at `0.2.0-alpha`, behind five gates. Status at the `0.9.0-beta` cut (all five gates green; 1.0 is deferred only for post-release CI soak, not for any open gate):

1. **External-integration soak** — ✅ the `0.2.x` surface shipped unchanged through `0.9.0-beta`; consumed externally by the kiosk-demo integration (resolving the published Pages Maven artifact) and a clean-room React Native → Android UAT.
2. **Stable set deliberately frozen** — ✅ executed 2026-06-12, see [API_STABILITY.md](API_STABILITY.md).
3. **Semconv audit** — ✅ executed 2026-06-12, see [SEMCONV_AUDIT.md](SEMCONV_AUDIT.md).
4. **Deprecation policy in force** — ✅ below.
5. **CI green on all platforms** — ✅ `ci.yml` (Android, minified, AAR-size, Go, RN, RN-android) + `ios-ci.yml` (iOS SDK, RN-iOS) per push, `device-tests.yml` (Android emulator + iOS simulator suites) nightly/on-tag.

## Deprecation policy (gate 4 — in force now)

Mechanics, per platform:

- **Kotlin/Android:** `@Deprecated(message, ReplaceWith(...), level = WARNING)`.
  Precedent: `OTelMobile.restartPageSpan` → `screenView`.
- **Swift/iOS:** `@available(*, deprecated, message: "... use X")`.
- **TypeScript/RN:** `@deprecated` JSDoc (surfaces in editors + API extractors).

Rules:

1. A deprecated symbol keeps WORKING for **at least one MINOR release** after
   the release that deprecates it. Pre-1.0 that means: deprecate in `0.x`,
   remove no earlier than `0.x+1`. Post-1.0: deprecate in a MINOR, remove
   only at the next MAJOR.
2. Every deprecation names its replacement in the annotation AND gets a
   **Deprecated** section entry in CHANGELOG.md with a migration snippet.
3. Deprecating a STABLE-AT-1.0 symbol (per API_STABILITY.md) additionally
   requires updating API_STABILITY.md in the same PR.
4. Telemetry names follow the same cycle via dual-emit: the old name and the
   new name are BOTH emitted for one MINOR (so dashboards keep working),
   then the old name is dropped — see the session-id convergence plan in
   SEMCONV_AUDIT.md for the template.

## Release mechanics

- Bump the shared version in: `packages/react-native/package.json`, `otel-android-mobile/build.gradle.kts`, the `examples/demo-app/build.gradle.kts` publishing convention, and tag the iOS SwiftPM release.
  - **Not** a separate edit: the RN-android native module (`packages/react-native/android`) **and** the upstream RN demo app (`examples/upstream-demo-app-rn/AstronomyShopRN/android/app/build.gradle`) both derive the SDK version they consume from `packages/react-native/package.json` (above). Do **not** re-pin the version in `gradle.properties` or in the demo app — a hardcoded pin silently drifted from the published version twice and broke the `react-native-android` CI job.
- Tagging `v*` triggers `.github/workflows/publish.yml` → npm publish + **all** Android modules to GitHub Packages (fixed in 0.2.0-alpha).
- Update `CHANGELOG.md` with the release section + an Upgrading note for any BREAKING (behavior) change.
