# Versioning & API Stability Policy

## Current state

- **Version:** `0.3.0-alpha` across all three artifacts (npm `@barrysolomon/mobile-react-native`, Android Maven `io.opentelemetry.android:mobile` + siblings, iOS SwiftPM tag `v0.3.0-alpha`).
- **Stability:** pre-1.0. Public entry points are annotated `@Incubating` (Kotlin) / documented as experimental (Swift/TS). **APIs may change between `0.x` releases.**

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

### Target tiering for 1.0 (proposed)
- **Stable:** `OTelMobile.start`/builder core, `MobileConfig` core fields (serviceName, endpoint, auth, exportMode, sampling, protocol), `start`/`shutdown`/`flushWindow`, manual `startSpan`/`log`/`recordMetric`, the RN `StartConfig` core, network/lifecycle/crash auto-instrumentation.
- **Incubating (stay experimental past 1.0):** screenshot/wireframe capture + consent API, the policy DSL / predictive export, the remote kill-switch wire schema, transport pinning/HMAC config, debug-widget.

## When to cut 1.0 (recommendation: not yet)

`0.2.0-alpha` just **expanded** the public surface substantially — `protocol`, `sampling` (RN), `shouldCapture` consent, transport `allowInsecureTransport`/`pinning`/`configSigningKey`, the kill-switch wire schema, Android `encryptDiskBufferAtRest` + RAM byte caps. **Freezing the API now would commit us to surfaces that are days old.** Recommended gates before `1.0.0`:

1. The `0.2.x` surface soaks through ≥1 more external integration without signature churn.
2. The **stable** set above is reviewed and each member's signature is deliberately frozen; everything else is explicitly `@Incubating`.
3. Emitted attribute names audited against current OTel mobile semconv; experimental ones documented as such.
4. A deprecation policy is in place (one MINOR with `@Deprecated` before removal).
5. CI green on all platforms incl. the iOS + RN-android jobs added in `0.2.0-alpha`.

Until then, continue `0.x` with the "minor may break" caveat, and keep `@Incubating` honest (annotate anything that might move; don't annotate what we intend to freeze).

## Release mechanics

- Bump the shared version in: `packages/react-native/package.json`, `otel-android-mobile/build.gradle.kts`, the `examples/demo-app/build.gradle.kts` publishing convention, and tag the iOS SwiftPM release.
- Tagging `v*` triggers `.github/workflows/publish.yml` → npm publish + **all** Android modules to GitHub Packages (fixed in 0.2.0-alpha).
- Update `CHANGELOG.md` with the release section + an Upgrading note for any BREAKING (behavior) change.
