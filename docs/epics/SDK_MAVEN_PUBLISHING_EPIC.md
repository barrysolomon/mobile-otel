# SDK Maven Publishing & Toolchain Alignment Epic

> **Status:** SHIPPED 2026-04-20 (the basics). Proper Maven coordinates + AAR publications now emit for every library sub-module when you run `publishToMavenLocal` from `examples/demo-app/`. RN demo consumes the SDK successfully — first external Maven consumer working end-to-end. Remaining work: decide whether to publish to Maven Central and what the release cadence looks like.
> **Created:** 2026-04-20
> **Parent:** [REACT_NATIVE_EPIC.md](REACT_NATIVE_EPIC.md)

## Problem

The otel-android-mobile SDK is usable as a project-path include within its own multi-project build, but cannot be consumed as a Maven artifact by external apps — including our own React Native demo (`examples/upstream-demo-app-rn/`).

When attempting RN-003 host scaffolding with `@dash0/mobile-react-native` autolinking, Gradle surfaces four distinct packaging defects:

1. **Sub-module coordinates are unpublished.** The SDK's own `publishing { }` block is only on the top-level `:otel-android-mobile` module. Its dependencies on `:otel-android-mobile-core` and the 20+ `:instrumentation-*` sub-modules get published in the POM as `groupId=OTel Mobile Demo, version=unspecified` because those sub-modules never had publishing blocks.

2. **rootProject.name contains a space.** `rootProject.name = "OTel Mobile Demo"` becomes the fallback `groupId` when sub-modules lack explicit coordinates. Spaces are invalid in Maven group IDs — any consumer trying to resolve the dep fails with `Could not find OTel Mobile Demo:<module>:unspecified`.

3. **Sub-modules don't declare `singleVariant("release")`.** Even with a publishing block, `from(components["release"])` silently publishes only a POM with no AAR attached unless the Android library module declares `android.publishing { singleVariant("release") { } }`.

4. **AGP 9.0 + Kotlin 2.2 toolchain bundling.** AGP 9.0's bundled androidx.* deps transitively force `kotlin-stdlib:2.2.x` even when the parent declares `kotlin("android") version "1.9.24"`. This drags Kotlin 2.2 metadata into every published AAR. RN 0.76's ecosystem is pinned to Kotlin 1.9, so the 2.2 metadata makes the SDK unconsumable from RN (the 1.9 compiler can't read 2.2 metadata).

## Evidence

From the RN-003 scaffolding session (2026-04-20):
- RN 0.76 template generated cleanly into `examples/upstream-demo-app-rn/AstronomyShopRN/`
- Autolinking (`react-native config`) detects `@dash0/mobile-react-native`
- Gradle build progresses through configuration, then fails at `:dash0_mobile-react-native:compileDebugKotlin` with:
  ```
  Class 'io.opentelemetry.android.mobile.OTelMobile' was compiled with an incompatible
  version of Kotlin. The actual metadata version is 2.2.0, but the compiler version
  1.9.0 can read versions up to 2.0.0.
  ```

This has nothing to do with the RN bridge code itself — it's a cross-cutting consumability defect in how the SDK packages itself.

## Scope

### In scope
- Add `android.publishing { singleVariant("release") { } }` + a Maven publication to every library module: `:otel-android-mobile-core`, all `:instrumentation-*` (~21 modules)
- Rename `rootProject.name` to `otel-mobile-demo` (no spaces — compatible with Maven groupId rules)
- Decide and document the canonical toolchain for published SDK artifacts. Two options:
  - **Option A:** Downgrade SDK to AGP 8.9 + Kotlin 1.9.24 so published AARs are consumable by the RN 0.76 ecosystem (and anything else stuck on Kotlin 1.9). Requires converting `kotlin { compilerOptions { } }` → `kotlinOptions { }` DSL across 23 files. Also need to drop `androidx.core:core-ktx:1.17.0` (needs AGP 8.9.1+) to a compatible version.
  - **Option B:** Keep AGP 9 + Kotlin 2.2 and document that consumers must be on Kotlin 2.2+. Blocks RN 0.76 (the 2026-current LTS) — consumers would need RN 0.77+ that supports Kotlin 2.
- Produce a `publishToMavenLocal` CI job that verifies external consumption works (e.g. a minimal test Gradle project that pulls from mavenLocal)
- Decide whether to publish to Maven Central under `io.opentelemetry.android:mobile:*` or keep mavenLocal-only for now

### Out of scope
- Refactoring the SDK's internal architecture
- Cross-platform parity changes (iOS SDK has its own packaging story via SwiftPM)

## Why this blocks RN

The RN JS epic (83 Jest tests) is code-complete. Native bridge Kotlin code is syntactically correct and compiles under the demo-app build. But the RN demo cannot boot on a simulator until this packaging gap is closed, because:

- `@dash0/mobile-react-native`'s Android module is installed into the RN demo's `node_modules/`
- RN autolinking wires it into the RN demo's Gradle build
- The RN demo must resolve `io.opentelemetry.android:mobile` from somewhere — either mavenLocal (this epic fixes) or by replicating the entire demo-app settings.gradle.kts topology with ~22 `project(":otel-android-mobile-*")` includes (fragile, error-prone, duplicates ownership of SDK structure)

## Related

- [REACT_NATIVE_EPIC.md](REACT_NATIVE_EPIC.md) — RN epic, blocked on this for device-mode validation
- [examples/upstream-demo-app-rn/AstronomyShopRN/android/](../../examples/upstream-demo-app-rn/AstronomyShopRN/android/) — RN host project scaffolded from RN 0.76 CLI template, ready to consume SDK once artifacts are publishable

## Sequencing

Once this epic lands:
1. Re-run `./gradlew :app:assembleDebug` in `examples/upstream-demo-app-rn/AstronomyShopRN/android/` — should produce a real APK
2. Add a similar `OTelMobile.podspec` for iOS SDK Swift Package
3. Flesh out `scripts/test/validate-rn-end-to-end.sh --mode=device` with real boot + Dash0 MCP assertions
