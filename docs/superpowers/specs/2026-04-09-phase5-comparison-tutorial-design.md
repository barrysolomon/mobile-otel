# Phase 5: Comparison Tutorial — Design Specification

**Date:** 2026-04-09
**Status:** Approved
**Scope:** Side-by-side comparison of upstream `opentelemetry-android` vs Dash0 mobile-otel SDK using the upstream's own demo app with Gradle product flavors.
**Parent Epic:** `docs/epics/UPSTREAM_SUPERSESSION_EPIC.md` (Phase 5, US-032 through US-036)

---

## 1. Overview

Instrument the upstream OpenTelemetry Android demo app (Compose-based astronomy shop) with both SDKs using Gradle product flavors. Same app code, different SDK initialization, side-by-side telemetry comparison. Produces a working comparison app, a battle card, and presentation-ready documentation for OTel SIG meetings and the merge proposal.

## 2. App Architecture

### Directory Structure

```
examples/upstream-demo-app/
├── build.gradle.kts              # Standalone, direct plugin IDs, product flavors
├── gradle/libs.versions.toml     # Local version catalog (extracted from upstream)
├── gradle.properties             # Gradle settings
├── src/
│   ├── main/                     # Shared: Compose UI, shop logic, thin Application
│   │   ├── AndroidManifest.xml
│   │   ├── assets/               # Product images from upstream demo
│   │   └── java/io/opentelemetry/android/demo/
│   │       ├── OtelDemoApplication.kt  # Thin — delegates to SdkInitializer
│   │       ├── MainActivity.kt
│   │       ├── shop/             # Compose shop UI (unchanged from upstream)
│   │       ├── about/            # About screens (unchanged)
│   │       └── theme/            # Material theme (unchanged)
│   ├── upstream/                 # Upstream flavor: SDK initialization
│   │   └── java/io/opentelemetry/android/demo/
│   │       └── SdkInitializer.kt
│   └── dash0/                    # Dash0 flavor: SDK initialization
│       └── java/io/opentelemetry/android/demo/
│           └── SdkInitializer.kt
```

### Build Integration

Registered in our existing Gradle build at `examples/demo-app/settings.gradle.kts`:

```kotlin
include(":upstream-demo-app")
project(":upstream-demo-app").projectDir = file("../upstream-demo-app")
```

This allows the dash0 flavor to reference `project(":otel-android-mobile")` directly as a dependency. No mavenLocal publish needed.

### Product Flavors

```kotlin
// build.gradle.kts
android {
    flavorDimensions += "sdk"
    productFlavors {
        create("upstream") {
            dimension = "sdk"
            applicationIdSuffix = ".upstream"
            manifestPlaceholders["appNameSuffix"] = "(Upstream)"
        }
        create("dash0") {
            dimension = "sdk"
            applicationIdSuffix = ".dash0"
            manifestPlaceholders["appNameSuffix"] = "(Dash0)"
        }
    }
}
```

Different `applicationIdSuffix` allows both APKs installed side-by-side on the same device.

### Dependencies

```kotlin
dependencies {
    // Shared deps (Compose, AndroidX, etc.)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.material3)
    // ...

    // Upstream flavor — published Maven artifacts only
    "upstreamImplementation"("io.opentelemetry.android:android-agent:0.11.0-alpha") {
        // Exclude api-incubator to avoid ExtendedAttributeKey conflict
        // (same exclusion as our core module in Phase 1)
        exclude(group = "io.opentelemetry", module = "opentelemetry-api-incubator")
    }
    "upstreamImplementation"("io.opentelemetry.android.instrumentation:sessions:0.11.0-alpha") {
        exclude(group = "io.opentelemetry", module = "opentelemetry-api-incubator")
    }
    // Note: compose-click NOT included (not published to Maven Central)

    // Dash0 flavor — our SDK via project reference
    "dash0Implementation"(project(":otel-android-mobile"))
}
```

### minSdk

Set to 26 for the entire app. Upstream SDK supports 23+ but works at 26. Our SDK requires 26.

## 3. Shared Application (main/)

`OtelDemoApplication` moves to `main/` with SDK-specific types removed:

```kotlin
// main/java/.../OtelDemoApplication.kt
class OtelDemoApplication : Application() {
    companion object {
        var openTelemetry: OpenTelemetry? = null
        var sessionId: String = ""

        fun tracer(name: String): Tracer? = openTelemetry?.getTracer(name)
        fun logger(name: String): Logger? = openTelemetry?.logsBridge?.get(name)
        fun meter(name: String): Meter? = openTelemetry?.getMeter(name)
    }

    override fun onCreate() {
        super.onCreate()
        ExportConfig.load(this)
        SdkInitializer.initialize(this)
    }
}
```

Only standard OTel API types (`OpenTelemetry`, `Tracer`, `Logger`, `Meter`) — no SDK-specific imports. Each flavor's `SdkInitializer` sets these fields.

### SessionId Composable

The upstream demo has a `SessionId.kt` composable that reads `OtelDemoApplication.rum?.getRumSessionId()`. Replace with `OtelDemoApplication.sessionId` which each flavor sets.

## 4. Flavor-Specific Initialization

### upstream/SdkInitializer.kt

```kotlin
object SdkInitializer {
    fun initialize(app: Application) {
        try {
            val rum = OpenTelemetryRumInitializer.initialize(app) {
                httpExport {
                    baseUrl = ExportConfig.endpoint
                    ExportConfig.headers.forEach { (k, v) -> baseHeaders[k] = v }
                }
            }
            OtelDemoApplication.openTelemetry = rum.openTelemetry
            OtelDemoApplication.sessionId = rum.getRumSessionId()
        } catch (e: Exception) {
            Log.e("SdkInit", "Failed to initialize upstream SDK", e)
        }
    }
}
```

### dash0/SdkInitializer.kt

```kotlin
object SdkInitializer {
    fun initialize(app: Application) {
        try {
            val mobile = MobileOtel.initialize(app) {
                service {
                    name = "astronomy-shop"
                    version = "1.0.0"
                }
                export {
                    endpoint = ExportConfig.endpoint
                    mode = ExportMode.CONDITIONAL
                    headers = ExportConfig.headers
                }
                instrumentations {
                    discoverAll()
                }
            }
            OtelDemoApplication.openTelemetry = mobile.openTelemetry
            OtelDemoApplication.sessionId = mobile.sessionId
        } catch (e: Exception) {
            Log.e("SdkInit", "Failed to initialize Dash0 SDK", e)
        }
    }
}
```

## 5. Export Configuration

Shared `ExportConfig` object in `main/` reads from `assets/otel-config.json` (same template pattern as our existing demo app). Falls back to local collector at `http://10.0.2.2:4318` if config file not present.

```kotlin
// main/java/.../ExportConfig.kt
object ExportConfig {
    lateinit var endpoint: String
    var headers: Map<String, String> = emptyMap()

    fun load(context: Context) {
        try {
            val json = context.assets.open("otel-config.json").bufferedReader().readText()
            val config = JSONObject(json)
            endpoint = config.getString("endpoint")
            val headerObj = config.optJSONObject("headers")
            headers = headerObj?.keys()?.asSequence()
                ?.associateWith { headerObj.getString(it) } ?: emptyMap()
        } catch (e: Exception) {
            // Fallback to local collector
            endpoint = "http://10.0.2.2:4318"
            headers = emptyMap()
        }
    }
}
```

Note: upstream uses HTTP/protobuf on port 4318, our SDK uses gRPC on port 4317. For Dash0 cloud endpoints, both protocols work on the same URL. For local collector fallback, `ExportConfig` provides both:
- `endpoint` = `http://10.0.2.2:4318` (HTTP, for upstream flavor)
- `grpcEndpoint` = `http://10.0.2.2:4317` (gRPC, for dash0 flavor)

The dash0 `SdkInitializer` uses `ExportConfig.grpcEndpoint`. When `otel-config.json` is present (Dash0 cloud), both fields use the same configured URL.

## 6. Build Rewiring Details

### Plugins

Replace upstream's `alias(rootLibs.plugins.androidApp)` and `alias(libs.plugins.compose.compiler)` with direct plugin IDs:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android") version "2.3.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10"
}
```

Note: Kotlin version must match the Compose compiler plugin version, which must be compatible with the Compose BOM. Using `2.3.10` (from upstream's catalog) ensures compatibility with Compose BOM `2026.03.01`. Our core SDK modules stay at their own Kotlin version — this module has its own plugin declarations.

### Version Catalog

Create `gradle/libs.versions.toml` with all needed versions extracted from the upstream demo's catalog. Pin to exact versions for reproducibility.

### What's Removed from Upstream

- `includeBuild("..")` — replaced by Maven artifacts + project references
- `rootLibs` catalog reference — replaced by local catalog
- `compose-click` dep in upstream flavor — not published, omitted intentionally
- `sessions` dep in upstream flavor — use Maven artifact `io.opentelemetry.android.instrumentation:sessions:0.11.0-alpha` (or omit — `android-agent` transitively includes most instrumentations)

## 7. Deliverables

### 7.1 Working Comparison App

Two APKs buildable from `examples/upstream-demo-app/`:
```bash
cd examples/demo-app
./gradlew :upstream-demo-app:assembleUpstreamDebug   # Upstream SDK APK
./gradlew :upstream-demo-app:assembleDash0Debug       # Dash0 SDK APK
./gradlew :upstream-demo-app:installUpstreamDebug :upstream-demo-app:installDash0Debug  # Both on emulator
```

### 7.2 Battle Card (`docs/BATTLE_CARD.md`)

One-page competitive comparison, meeting-ready. Structure:

```markdown
# Dash0 Mobile SDK vs upstream opentelemetry-android — Battle Card

## One-Line Summary
Dash0's SDK is a strict superset: every upstream module works in our framework,
plus 12 additional capabilities they don't have.

## Signal Coverage (22 vs 9 auto-installed)
| Signal | Upstream | Dash0 |
| Tap / click | view-click | view.click + compose.click |
| Scroll | -- | ui.scroll |
| Text input | -- | ui.text_input |
| Back press | -- | ui.back_press |
| Freeze/ANR | anr + slowrendering | freeze + vitals (combined) |
| Screen orientation | not published | device.orientation |
| Database queries | -- | db.query spans |
| File I/O | -- | file.io spans |
| System events | -- | battery, power, storage |
| Wireframe replay | -- | ui.wireframe |
| Screenshot capture | -- | ui.screenshot |
| ... | ... | ... |

## Architecture Advantages
- Conditional export (vs always-on) — battery-efficient
- Dual-tier buffering (RAM + SQLite) — survives crashes
- Policy DSL engine — selective flush on error/crash
- Visual control plane (React Flow editor)

## Developer Experience
- Kotlin DSL config matching upstream's pattern
- @Supersedes prevents duplicate telemetry
- Exporter customizer chain
- OpenTelemetryMobile return type

## Merge Proposal Readiness
- Compatible superset: all upstream modules run via adapter
- Interface convergence planned (Phase 4)
- OTEPs in progress for buffering + conditional export
```

### 7.3 Comparison Tutorial (`docs/COMPARISON_TUTORIAL.md`)

Step-by-step walkthrough:

```markdown
# Side-by-Side SDK Comparison Tutorial

## Prerequisites
- Android emulator (API 26+)
- Dash0 account (optional — local collector works too)

## Quick Start (5 min)
1. Build both APKs
2. Install both on emulator
3. Run the same user flow in each

## What You See
### Upstream APK
- 9 auto-installed signal types (activity lifecycle, crash, ANR, slow rendering,
  startup, sessions, network changes, fragment lifecycle + view-click if added)
- OkHttp + HttpURLConnection require ByteBuddy Gradle plugin (not included)
- Always-on export — telemetry flows continuously
- No compose click detection (module not published)
- No screen orientation (module not published)

### Dash0 APK
- 22 signal types (everything upstream has, plus 12 more)
- Conditional export — zero bandwidth until policy triggers
- Compose click detection via semantics tree
- Screen orientation tracking
- Breadcrumb trail for journey reconstruction
- ui.scroll, ui.text_input, ui.back_press
- System events (battery, power, storage)

## The Code Diff
[Show the two SdkInitializer files side by side]

## Dash0 Dashboard Comparison
[Guide for what to filter/look at in Dash0]

## Local Collector Setup
[Docker compose for people without Dash0]
```

### 7.4 Presentation Talking Points (`docs/TALKING_POINTS.md`)

Bullet points for OTel SIG meetings:

```markdown
# Talking Points for OTel Android SIG

## The Pitch (30 seconds)
"We built a compatible superset of opentelemetry-android. Every upstream
module runs in our framework unmodified. We add 12 capabilities upstream
doesn't have — conditional export, dual-tier buffering, 22 instrumentation
modules. Here's the same app instrumented with both SDKs."

## Demo Script (5 minutes)
1. Show both APKs installed side-by-side
2. Same user flow: browse → add to cart → checkout
3. Switch to Dash0: "Here's what upstream captured. Here's what we captured."
4. Highlight: scroll events, compose click identity, orientation changes
5. Show conditional export: "Upstream sent 47 events. We sent 0 — until
   this crash, which flushed 2 minutes of context."

## Objection Handling
- "Why not contribute upstream directly?"
  → "We are. The adapter layer and @Supersedes make that path smooth.
     Phase 4 converges the interfaces."
- "Isn't this just a vendor SDK?"
  → "It's Apache 2.0, OTel-native, exports standard OTLP.
     The policy engine and buffering are OTEPs in progress."
```

## 8. Testing

- Build both flavors: `assembleUpstreamDebug` + `assembleDash0Debug`
- Install both on emulator, run same user flow
- Verify upstream APK generates telemetry (basic signals)
- Verify dash0 APK generates richer telemetry (all 22 modules)
- Manual comparison in Dash0 or local collector output

No automated comparison tests — this is a demo/presentation tool, not a CI pipeline.

## 9. What's NOT in Scope

- Modifying the upstream app's Compose UI or business logic
- Adding our debug toolbar (Phase 6)
- Automated telemetry diff tool
- Publishing our SDK to Maven Central (uses project reference)
- Running compose-click in the upstream flavor (not published)
