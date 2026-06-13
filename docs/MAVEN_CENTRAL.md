# Maven Central — runbook to general availability

**Status (2026-06-12):** Everything automatable is done; what remains needs
the project owner's accounts and ONE naming decision. Today the Android SDK
publishes to GitHub Packages, which forces every consumer to mint a PAT just
to resolve dependencies — fine for alpha partners, adoption friction at 1.0.

## The blocking decision: groupId

The current coordinates (`io.opentelemetry.android:mobile`) **cannot ship to
Maven Central**: the `io.opentelemetry.*` namespace belongs to the
OpenTelemetry project (verified via their DNS/registration), not to this
repo. Publishing to Central means a coordinate migration — a one-time
breaking change that MUST land before 1.0 (pre-1.0 minors may break;
post-1.0 a coordinate change is a fork-level event for consumers).

Options, in order of friction:

| groupId | Verification | Notes |
|---|---|---|
| `io.github.barrysolomon` | automatic (GitHub account proof via Central Portal) | zero external dependencies; works today; reads as a personal project |
| `com.testingalchemy` | DNS TXT record on testingalchemy.com | owner-controlled domain; neutral branding |
| `com.dash0.mobile` (or similar) | DNS TXT on a dash0.com subdomain + Dash0 sign-off | the right answer IF this ships as a Dash0 product — needs the org decision |

Whichever is chosen, the migration is mechanical (one sed over the
coordinates + a CHANGELOG BREAKING entry + dual-publish to GitHub Packages
for one minor as a transition).

## Already done (this repo)

- ✅ Complete POM metadata (name/description/url/licenses/scm/developers) on
  **every** publication — the umbrella, mobile-core, and all
  mobile-instrumentation-* modules. Central rejects artifacts without it;
  GitHub Packages never complained, which is why it was missing/wrong
  (the POMs used to point at the upstream opentelemetry-android-contrib repo).
- ✅ Versioned, lockstep release mechanics with a CI-green publish gate
  (publish.yml) that Central publishing slots into.

## Remaining manual steps (project owner)

1. **Central Portal account** — https://central.sonatype.com, register, then
   claim the chosen namespace (instant for `io.github.<user>`; DNS TXT for
   domains).
2. **Signing key** — Central requires PGP-signed artifacts:
   `gpg --gen-key` (RSA 4096, no expiry or 10y), publish the public key to
   `keyserver.ubuntu.com`, export the secret key for CI:
   `gpg --export-secret-keys --armor <KEYID>`.
3. **Repo secrets** — add `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD`
   (portal user token, not login password), `SIGNING_KEY` (armored secret
   key), `SIGNING_PASSWORD`.
4. **Gradle wiring** (small PR once 1-3 exist):
   - apply the `signing` plugin to the publishing convention; `sign(publications)`
     fed from the env secrets;
   - add the Central Portal publishing route (either the
     `com.gradleup.nmcp` / `tech.yanand.maven-central-publish` portal plugin,
     or the classic OSSRH staging if preferred);
   - extend `publish.yml` with a `publish-maven-central` job behind the same
     `verify-ci-green` gate.
5. **Dry run** — publish a `-test` qualifier version to the portal's staging
   validation; Central's validator confirms POM completeness + signatures
   before anything goes public.

## Sequencing note

Do the groupId migration in the **same release** as Loper's soak feedback
lands (one breaking window), and dual-publish to GitHub Packages for one
minor so existing 0.x consumers aren't stranded mid-transition.
