# Maven Central — runbook to general availability

**Status (2026-07-01):** The groupId decision is MADE and the migration has
LANDED (v0.5.2-alpha). Signing, the CI job, the POM metadata, and the
Central-compatible coordinate are all wired. What remains is entirely the
project owner's accounts + ONE small gradle edit (the Central Portal plugin).

Today the native SDK publishes to a **public GitHub Pages Maven repo**
(`https://barrysolomon.github.io/mobile-otel/maven`, no PAT) — this already
removed the adoption friction that blocked the v0.5.1-alpha UAT. Maven Central
is the industry-standard destination and flips on the moment secrets exist.

> **One-time GitHub Pages setup (required for the public URL to serve).** The
> `publish-android-pages` job in `publish.yml` creates/updates the `gh-pages`
> branch (under `/maven`) on every `v*` tag. For `barrysolomon.github.io/mobile-otel`
> to actually serve it, enable Pages ONCE: repo **Settings → Pages → Source:
> Deploy from a branch → `gh-pages` / `(root)`**. Until then the URL 404s and
> consumers can't resolve the artifact. After the first tagged release + this
> setting, verify with `scripts/ci/smoke-rn-public-consumer.sh` (no `--repo` arg
> hits the live URL).

## The groupId decision: RESOLVED → `io.github.barrysolomon`

The old coordinates (`io.opentelemetry.android:mobile`) **cannot ship to Maven
Central**: the `io.opentelemetry.*` namespace belongs to the OpenTelemetry
project, not to this repo. As of v0.5.2-alpha the coordinate is migrated to
**`io.github.barrysolomon:mobile`** — the Central Portal auto-verifies
`io.github.<user>` against GitHub account ownership (zero external deps). The
Kotlin package namespace (`io.opentelemetry.android.mobile.*`) is unchanged.

The single source of truth is `examples/demo-app/gradle.properties`
(`sdkGroupId` / `sdkVersionName`); every publication reads it. If this ever
ships as a Dash0 product, a future migration to `com.dash0.*` (DNS TXT on a
dash0.com subdomain) is the same one-property change.

## Already done (this repo)

- ✅ **groupId migrated** to the Central-compatible `io.github.barrysolomon`
  (v0.5.2-alpha), single-sourced in `examples/demo-app/gradle.properties`.
- ✅ **Complete POM metadata** (name/description/url/licenses/scm/developers) on
  **every** publication — the umbrella, mobile-core, and all
  mobile-instrumentation-* modules. Central rejects artifacts without it.
- ✅ **Signing wired** — the `signing` plugin is applied and signs the `release`
  publication with `useInMemoryPgpKeys(SIGNING_KEY, SIGNING_PASSWORD)`, and it
  is a **no-op unless `SIGNING_KEY` is set in the environment** (see
  `otel-android-mobile/build.gradle.kts` + the subprojects block in
  `examples/demo-app/build.gradle.kts`). Local + Pages/GitHub-Packages builds
  stay unsigned; only CI-with-secrets signs.
- ✅ **CI job wired** — `publish.yml` has a `publish-android-central` job behind
  the same version-parity + CI-green gates, guarded by an `if:` that only fires
  when `MAVEN_CENTRAL_USERNAME` exists. It is dormant (skipped) until secrets
  are added, so it never breaks a release.
- ✅ Versioned, lockstep release mechanics with a CI-green publish gate and a
  version-parity guard (`scripts/ci/check-version-parity.sh`).

## Remaining steps (project owner) — this is ALL that's left

1. **Central Portal account** — https://central.sonatype.com, register, then
   claim `io.github.barrysolomon` (instant — verified against your GitHub account).
2. **Signing key** — `gpg --gen-key` (RSA 4096), publish the public key to
   `keyserver.ubuntu.com`, export the secret key: `gpg --export-secret-keys --armor <KEYID>`.
3. **Repo secrets** — add `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD`
   (portal user token, not login password), `SIGNING_KEY` (armored secret key),
   `SIGNING_PASSWORD`. Adding `MAVEN_CENTRAL_USERNAME` alone is what un-skips the CI job.
4. **ONE gradle edit** — add the Central Portal plugin so the
   `publishAllPublicationsToCentralPortal` task (already invoked by the CI job)
   exists. Recommended: the `com.gradleup.nmcp` aggregation plugin applied in
   `examples/demo-app/build.gradle.kts`. (Left out of this release deliberately:
   applying a portal plugin unconditionally would resolve on every build; adding
   it as part of enabling Central keeps normal builds clean. Everything else the
   plugin needs — signing, POMs, coordinate — is already in place.)
5. **Dry run** — publish a `-test` qualifier version to the portal's staging
   validation; Central's validator confirms POM completeness + signatures first.

## Sequencing note

The coordinate migration + public-repo move landed together in v0.5.2-alpha
(one breaking window, pre-1.0). GitHub Packages continues to publish for one
transition window so existing PAT-based 0.x consumers aren't stranded.
