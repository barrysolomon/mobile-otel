# Dash0 Mobile Observability SDK — Tutorial

> **For:** Learning what we built, how to explain it to mobile devs, and how to run every piece end-to-end.
> **Read time:** ~45 minutes if you skim. ~3 hours if you actually do every step.
> **Not for git.** Stays gitignored at the workspace root.

---

## How to use this guide

This doc has two halves and you don't need to read them in order:

- **Tutorial (Parts T0–T6)** — sequential. Each part assumes the previous part ran. By the end of T6 you have telemetry from Android, iOS, RN, and the control plane landing in Dash0 side by side.
- **Reference (Parts R1–R8)** — non-sequential. Pitch language, architecture deep-dive, comparisons, glossary, file map. Jump in when a customer asks something specific.

If you only have one hour: do **T0 + T1 + T6**. That's "setup → first telemetry → see it in Dash0" on Android. Everything else is the same idea on a different runtime.

---

# Tutorial

## T0 — Day 0 setup (one time per machine, ~30 min)

Goal: have every binary you'll touch installed and on `PATH`.

### T0.1 — Clone the workspace (if not already)

The workspace lives at `~/Projects/Dash0/mobile-observability/` and holds **two** git repos:

```
mobile-observability/
├── mobile-otel/                  # Data plane: SDKs (Android/iOS/RN), processor, demo apps
└── mobile-otel-control-plane/    # Management plane: visual policy editor + gateway
```

Each has its own `CLAUDE.md` with module-by-module build notes. The top-level `CLAUDE.md` is just a coordinator. **Read the sub-repo `CLAUDE.md` before working in that repo** — it's authoritative for build commands.

### T0.2 — Install the toolchains

Pick the platforms you'll actually run. You don't need all four to do the tutorial.

| Tool | Version | Used by | Install |
|------|---------|---------|---------|
| **JDK 17** | 17.x | Android SDK + demo app | `brew install --cask temurin@17` |
| **Android Studio** | Latest | AVD manager, `adb`, emulator | https://developer.android.com/studio |
| **Xcode** | 26+ | iOS SDK + demo app | App Store |
| **XcodeGen** | latest | iOS demo project generator | `brew install xcodegen` |
| **Node.js** | 20+ | RN demo, demo backend, control plane UI | `brew install node@20` |
| **Go** | 1.24+ | Collector processor, gateway | `brew install go` |
| **Docker** | Latest | Local OTEL Collector + Jaeger | `brew install --cask docker` |
| **Dash0 CLI** | 1.11+ | Verify telemetry without opening the UI | `brew install dash0hq/tap/dash0` |

Add Android tools to your shell rc (zsh):

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

Verify everything in one shot — there's an actual script:

```bash
cd ~/Projects/Dash0/mobile-observability/mobile-otel
./scripts/setup/verify-setup.sh
```

### T0.3 — Configure Dash0 credentials

Dash0 reads telemetry from gitignored config files in each demo app. Each platform has its own:

| Demo app | Config path |
|----------|-------------|
| Android (custom)   | `mobile-otel/examples/demo-app/android/src/debug/assets/otel-config.json` |
| Android (upstream) | (uses same config as `demo-app`) |
| iOS (Astronomy)    | `mobile-otel/examples/upstream-demo-app-ios/AstronomyShop/otel-config.json` |
| iOS (Schedulr)     | `mobile-otel/examples/demo-app-ios/Schedulr/otel-config.json` |
| iOS (Starter)      | `mobile-otel/examples/demo-app-ios-starter/StarterApp/otel-config.json` |
| RN (Astronomy)     | `mobile-otel/examples/upstream-demo-app-rn/AstronomyShopRN/otel-config.json` |

For each platform you'll use:

```bash
cp <path>.template <path>
# Edit to fill in:
#   YOUR_COLLECTOR_ENDPOINT  (e.g. ingress.us-west-2.aws.dash0.com:4318 for HTTP/protobuf — the default; :4317 for gRPC)
#   YOUR_AUTH_TOKEN          (Auth Token from your Dash0 org)
#   YOUR_DATASET_NAME        (use "otel-mobile" so this guide's filters work)
```

> **0.2.0 change:** Android's default OTLP protocol is now **HTTP/protobuf** (was gRPC), matching iOS — so a single `:4318` endpoint works for both platforms and traverses HTTPS-terminating proxies / managed ingress. Restore gRPC with `MobileConfig.protocol = OtlpProtocol.GRPC` (typically `:4317`). The earlier per-platform port asymmetry (`feedback_rn_transport_asymmetry.md`) no longer applies by default.

### T0.4 — Set up the Dash0 CLI

```bash
dash0 auth login              # browser-based OAuth
dash0 profile use mobile-test # if you have a saved profile; otherwise create one
dash0 logs query --dataset otel-mobile --limit 5
```

If the last command returns rows you've already validated end-to-end at some point. If it returns nothing, that's fine — you haven't sent any telemetry yet.

> **Gotcha:** Use `--filter "key is value"`, not `key=value`. `--filter "A and B"` silently matches 0 rows — use repeated `--filter` flags for AND. See memory `feedback_dash0_cli_filter_syntax.md`.

---

## T1 — First telemetry (Android, ~15 min)

Goal: install the custom demo app on an emulator, tap around, and see events arrive in Dash0.

### T1.1 — Boot an emulator

```bash
emulator -list-avds              # Pixel_7 should be there if you used Android Studio's wizard
emulator -avd Pixel_7 &          # boot it
adb devices                      # should show "emulator-5554   device"
```

> **Gotcha:** If `adb` doesn't see the emulator, the build will fail with "No connected devices!" — fix the device, not the build.

### T1.2 — Build and install the demo app

The Android SDK has **no standalone `gradlew`** — always build through `examples/demo-app/` which includes the wrapper via `settings.gradle.kts`.

```bash
cd ~/Projects/Dash0/mobile-observability/mobile-otel/examples/demo-app
./gradlew :otel-android-mobile:test          # SDK unit tests (~30s)
./gradlew installDebug                       # builds + installs ~140 MB APK
```

The demo app is called **Schedulr** — a fake medical scheduling app with bookings, screens, navigation, and a deliberately-busted backend that lets you trigger HTTP errors.

### T1.3 — Start the demo backend

The app needs a backend to talk to. Keep this running in a second terminal.

```bash
cd ~/Projects/Dash0/mobile-observability/mobile-otel
./scripts/demo/run-demo-backend.sh start
# Listening on http://localhost:3001
```

### T1.4 — Drive the app

Open Schedulr on the emulator. Try:

1. **Tap around the home screen** → tap events with coordinates land in the buffer
2. **Scroll the providers list** → scroll spans arrive throttled
3. **Tap a provider → "Book"** → page-span hierarchy emitted (home → provider → booking-form)
4. **Enable airplane mode → tap Book** → POST fails, error log lands in buffer, no export yet
5. **Disable airplane mode** → within ~3s the error log appears in Dash0 (network-restored flush!)
6. **Use the Demo Control Center notification** to trigger a crash → app dies, you relaunch it, ~5 minutes of pre-crash context exports automatically

### T1.5 — Verify in Dash0

Two ways. CLI first (faster, scriptable):

```bash
# Last hour of events from this app:
dash0 logs query \
  --dataset otel-mobile \
  --filter "service.name is otel-android-schedulr" \
  --from now-1h --limit 20

# Just the crashes:
dash0 logs query \
  --dataset otel-mobile \
  --filter "service.name is otel-android-schedulr" \
  --filter "event.name is app.recovery_start" \
  --from now-1h
```

> **Gotcha:** `dash0 logs query --from now-Xm` filters by **device event time**, not ingestion time. For offline/crash cells use a wide window — see memory `feedback_dash0_query_event_time.md`. And the JSON output uses base64 `trace_id`s; convert to hex before pasting into the Dash0 UI search bar — see `feedback_dash0_traceid_hex_vs_base64.md`.

Then the UI: https://app.dash0.com → set dataset `otel-mobile` → trace search → filter `service.name = otel-android-schedulr`. Click a tap event to see the parent page span.

### T1.6 — Run the curated demo scripts

When you actually present this to someone, don't drive it manually — use the runbook scripts:

```bash
cd ~/Projects/Dash0/mobile-observability/mobile-otel
./scripts/demo/run-demo-quick.sh                          # 1 emulator, backend, build+launch (~5 min)
./scripts/demo/run-demo-full.sh                           # 2 emulators, all 28 scenarios (~12 min)
./scripts/demo/run-demo-single.sh crashFlush              # just one scenario
./scripts/test/demo-control-center.sh        # interactive crash + airplane menu
```

The full demo doc is `mobile-otel/HOW_TO_DEMO.md`. Read it before showing this to anyone.

---

## T2 — iOS (~20 min, requires Xcode 26 + iOS 26 simulator runtime)

Goal: same demo, on a SwiftUI port called **AstronomyShop**, on iOS.

> **Branch note:** iOS work happens on the `iPhone` branch in `mobile-otel/`. Do NOT fast-forward `iPhone` into `main` without explicit approval. The main branch's iOS SDK reflects whatever has been validated.

### T2.1 — Verify the iOS toolchain

```bash
xcode-select -p
# Should print /Applications/Xcode.app/Contents/Developer
# If it shows /Library/Developer/CommandLineTools, switch:
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer

xcrun simctl list runtimes | grep iOS
# Need iOS 26.x at minimum. Install via Xcode → Settings → Components if missing.
```

### T2.2 — One-liner demo

```bash
cd ~/Projects/Dash0/mobile-observability/mobile-otel
./scripts/demo/demo-control-center-ios.sh full
```

This boots the simulator, generates the Xcode project via XcodeGen, builds the SDK + app, installs it, launches it, and takes a screenshot. Takes about 6 minutes the first time, ~90s on incremental runs.

### T2.3 — Drive AstronomyShop

It's the same telescopes-and-accessories store as the Android upstream demo, sharing `products.json` and product images. Same UX flow: home → product → cart → checkout. Browse, tap a product, add to cart, check out.

### T2.4 — Verify in Dash0

```bash
dash0 logs query \
  --dataset otel-mobile \
  --filter "service.name is otel-ios-astronomy-shop" \
  --from now-1h --limit 20
```

You should see a 14-span checkout trace tree (matches Android's shape for cross-platform comparison).

> **Gotcha:** iOS uses `forceFlushBuffered()` (RAM + disk drain) where Android uses `flushWindow(60)` (windowed). Different mechanisms, same promise. See `feedback_ios_forceflush_two_methods.md`.

### T2.5 — Optional: crash + recovery on iOS

```bash
./scripts/demo/demo-control-center-ios.sh crash
```

The script wires a 15-second delayed signal handler, then triggers a SIGSEGV. App dies. On relaunch the pre-crash events export. The 15s delay is necessary because the signal handler installs from JS, not AppDelegate — see `feedback_rn_ios_crash_timing.md`.

---

## T3 — React Native (~15 min)

Goal: prove the platform-parity claim — same telemetry shape from a JS codebase.

RN is a **thin JS facade** over the native Android + iOS SDKs (Datadog-style, not Sentry-style). All buffering, policy evaluation, and OTLP export happen in the native modules. The bridge only marshals calls. So if T1 and T2 worked, T3 mostly just tests the bridge.

### T3.1 — Run the Jest path first (always works, no devices needed)

```bash
cd ~/Projects/Dash0/mobile-observability/mobile-otel
./scripts/test/validate-rn-end-to-end.sh --mode=jest
# Expect: 70 RN package tests + 13 demo tests green
```

This validates the JS surface (XHR/fetch interception, AppState lifecycle, navigation taps, unhandled-rejection handler, OTel shim) without needing a simulator.

### T3.2 — Run on Android device

```bash
cd ~/Projects/Dash0/mobile-observability/mobile-otel/examples/upstream-demo-app-rn/AstronomyShopRN

# First time: install JS deps and start Metro
npm install
npx react-native start &

# Build + install (in another terminal):
npx react-native run-android
```

If you edited `otel-config.json` and a rebuild doesn't pick it up:

```bash
touch index.js
npx react-native run-android
```

Gradle's `createBundleReleaseJsAndAssets` doesn't track `require()`d JSON. See `feedback_rn_gradle_bundle_cache.md`.

### T3.3 — Run on iOS simulator

```bash
cd ~/Projects/Dash0/mobile-observability/mobile-otel/examples/upstream-demo-app-rn/AstronomyShopRN
cd ios && pod install && cd ..
npx react-native run-ios
```

> **Gotcha:** iOS 26 simulator needs `UIApplicationSceneManifest` in Info.plist or the RN root window never paints. The demo app already has it — but if you scaffold a new RN host project remember this. See `project_ios_rn_uiscene.md`.

### T3.4 — Verify in Dash0

```bash
dash0 logs query --dataset otel-mobile --filter "service.name is otel-rn-astronomy-shop" --from now-1h
```

Three platforms, same trace shape. **This is the platform-parity payoff. Open all three side by side in the UI and watch a customer's eyes go wide.**

```bash
# All three platforms in one query:
dash0 logs query --dataset otel-mobile \
  --filter "service.name matches otel-.*-astronomy-shop" \
  --from now-1h
```

---

## T4 — Control plane (visual policy editor, ~20 min)

Goal: change what the SDK exports without rebuilding the app.

### T4.1 — Boot the control plane

```bash
cd ~/Projects/Dash0/mobile-observability/mobile-otel-control-plane

# Terminal 1: local OTEL Collector + Jaeger
docker compose -f k8s/docker-compose.yml up -d

# Terminal 2: Gateway (Go, requires CGo for sqlite3)
cd gateway && go build -o gateway . && ./gateway
# → listening on :8080

# Terminal 3: Control Plane UI (React + Vite)
cd control-plane-ui && npm install && npm run dev
# → http://localhost:3000
```

### T4.2 — Build an export policy in the UI

Open http://localhost:3000.

1. Click **+ New Policy**
2. Drag a **Crash** trigger node onto the canvas
3. Drag a **Flush Buffer** action node
4. Connect them (drag from the trigger's right port to the action's left port)
5. Set the action's window to 5 minutes
6. Click **Compile** — watch the DSL JSON appear in the right panel
7. Click **Deploy** — gateway stores it, SDKs will pick it up on next 5-minute poll

### T4.3 — Verify the SDK got it

Restart the Android demo app to force a config refresh (or wait 5 min):

```bash
adb shell am force-stop com.dash0.schedulr
adb shell am start -n com.dash0.schedulr/.MainActivity
adb logcat -d | grep PolicyEvaluator | tail -20
# Look for "Loaded N policies, version=2" with your new policy name
```

### T4.4 — The DSL is the cross-repo contract

This is the integration seam between the two repos:

- **Producer:** `mobile-otel-control-plane/control-plane-ui/src/utils/graphToDSLv2.ts` compiles graphs to DSL v2 JSON
- **Consumer:** `mobile-otel/otel-android-mobile/.../policy/PolicyEvaluator.kt` and the iOS equivalent parse and evaluate it
- **Schema:** `mobile-otel-control-plane/docs/DSL_V2_SCHEMA.md` — 21 matcher types, 10 action types
- **Discipline:** any new matcher/action type **must** be added on both sides in the same PR. There's a `dsl-schema-sync-check` skill that catches drift.

---

## T5 — Tests (the full matrix)

Goal: prove nothing is broken before you commit.

### T5.1 — Default test suite (Android + Go, ~3 min)

```bash
cd ~/Projects/Dash0/mobile-observability/mobile-otel
./scripts/ci/run-tests.sh
```

### T5.2 — Platform-specific suites

```bash
./scripts/ci/run-tests.sh --android-only         # Android SDK + 11 instrumentation modules (~2 min)
./scripts/ci/run-tests.sh --go-only              # Collector processor (~30s)
./scripts/ci/run-tests.sh --ios                  # iOS SDK on Simulator (~4 min, needs Xcode)
./scripts/ci/run-tests.sh --rn                   # RN bridge: Jest + typecheck (~2 min)
./scripts/ci/run-tests.sh --all                  # Everything above (~12 min, needs full toolchain)
./scripts/ci/run-tests.sh --integration          # Add emulator-based instrumented tests (~10 min)
```

### T5.3 — Per-scenario validators (UAT matrix)

The `scripts/test/validate-us*` family runs one user story at a time. Useful for debugging a single regression:

```bash
./scripts/test/validate-us063-crash-flush.sh        # Android crash recovery
./scripts/test/validate-ios-us063-crash-flush.sh    # iOS crash recovery
./scripts/test/validate-us076-hybrid-mode.sh        # hybrid mode end-to-end
```

There are ~28 of these — one per cell in the UAT matrix. See `mobile-otel/scripts/test/uat/` for the orchestrator. The full UAT matrix is 48 cells (12 scenarios × 4 platforms) and currently all green.

### T5.4 — Acceptance tests (control plane)

```bash
cd ~/Projects/Dash0/mobile-observability/mobile-otel-control-plane/acceptance
npm install
npm run test:acceptance
# Boots gateway + UI, runs Playwright + simulated SDK, validates 5 user-facing scenarios
```

---

## T6 — Verification checklist (Dash0)

After running anything, here are the queries that prove the system is healthy.

| Want to see | Query |
|-------------|-------|
| All three platforms emitting | `dash0 logs query --dataset otel-mobile --filter "service.name matches otel-.*-astronomy-shop" --from now-1h` |
| Crashes captured | `--filter "event.name is app.recovery_start" --from now-24h` |
| HTTP errors flushed | `--filter "event.name is http.error" --from now-1h` |
| Lifecycle stream | `--filter "event.name matches app.foreground\|app.background" --from now-1h` |
| Selective flush vs continuous | `--filter "exporter is selective" --from now-1h` (look at burst-then-quiet pattern) |

Then in the Dash0 UI: dashboards → **Mobile Fleet** (if your dataset has the saved dashboard) shows session counts, crash rates, export bytes/minute per platform.

**Cross-platform fan-out moment to show a customer:** open three browser tabs, one per `service.name`, all filtered to the same `session.id` (which RN bridges via the native SDK). Watch the trace tree light up the same way regardless of runtime.

---

# Reference

## R1 — The 30-second pitch

We built an OpenTelemetry-native mobile SDK (Android, iOS, React Native) that captures mobile telemetry (taps, crashes, network calls, device health — 19 signal types) into a **crash-safe local buffer**, then uses an **on-device policy engine** to decide what to export and when. Instead of shipping everything to the cloud like Datadog/Splunk, we selectively flush only the relevant 2-5 minute window around incidents. Result: same (or better) visibility, fraction of the data volume, fraction of the battery drain.

## R2 — The 60-second pitch (what to add)

- **Dual-tier buffer**: RAM (5,000 events, lock-free queue) + SQLite (50MB, 24-hour TTL). Survives app crashes, process kills, even force-quit. When you relaunch the app, all the buffered events are still there.
- **Policy DSL**: A JSON-based language describing when to export. "When the app crashes, flush the last 5 minutes." "When HTTP status >= 500, flush 2 minutes." Evaluated on-device in real-time.
- **3 export modes**: CONDITIONAL (only export when policy triggers — <0.5% battery), CONTINUOUS (periodic like everyone else), HYBRID (heartbeat + conditional). **HYBRID is the default** (as of 0.2.0, on both Android and iOS).
- **Visual control plane**: React Flow graph editor where you draw workflows → compiles to DSL → pushes to devices. Change what gets exported without an app release.
- **Cross-platform parity**: Android, iOS, RN all emit the same trace shapes. Same `service.name` pattern, same `session.id`, same span hierarchy.
- **OTel-native**: Standard OTLP export to any backend. No vendor lock-in. No Gradle plugin required.

## R3 — What mobile devs care about (lead with these)

1. **"Zero build plugin."** Datadog and Splunk require ByteBuddy Gradle plugins that can break your build. Ours is runtime-only.
2. **"Battery efficient."** Conditional mode uses near-zero battery. The radio only fires when something interesting happens.
3. **"Crash-safe."** Your buffer survives process death. When the user opens the app again, the pre-crash context exports automatically.
4. **"OTel standard."** Point it at any OTLP backend — Jaeger, Grafana, Honeycomb, Dash0.
5. **"19 modules, pick what you want."** Each module is independent. Don't want screenshots? Don't include the module.
6. **"Three SDKs, one shape."** Same trace tree from Kotlin, Swift, or TypeScript.

## R4 — Architecture deep-dive

### Workspace layout

| Directory | Role | Stack |
|-----------|------|-------|
| `mobile-otel/` | **Data plane** — Android SDK, iOS SDK, RN bridge, instrumentation modules, OTEL Collector processor, demo apps & backend | Kotlin (JDK 17), Swift 5.9, TypeScript (RN 0.76), Go 1.24, Node.js |
| `mobile-otel-control-plane/` | **Management plane** — Visual workflow editor, Go gateway API, K8s/Docker deployment | React 18 + TypeScript, Go 1.24 |

### Data flow

```
User taps / crashes / navigates
         │
         ▼
[19 Instrumentation Modules]
  tap, scroll, errors, network, vitals, etc.
         │
         ▼
[MobileLogRecordProcessor]
  ├── RAM Buffer (ConcurrentLinkedQueue, 5000 events)
  ├── Disk Buffer (Room/SQLite, 50MB, 24h TTL)
  └── Policy Evaluator (evaluates every event)
         │
         ▼ (when policy matches)
[Selective Flush]
  "Export events from the last N minutes"
         │
         ▼
[OTLP/gRPC or HTTP Export]
         │
         ▼
[OTEL Collector] → [Dash0 / Jaeger / Any Backend]
```

### Platform matrix (data plane)

Every change to shared behavior (DSL semantics, bridge contract, buffering policy) must be mirrored across all four targets — **zero drift** (memory: `feedback_no_platform_drift.md`).

| Platform | Location | Language | Tests | Validator |
|----------|----------|----------|-------|-----------|
| Android native | `mobile-otel/otel-android-mobile/` | Kotlin, JDK 17, API 26+ | JUnit 4 + Robolectric + Mockk | `./scripts/ci/run-tests.sh --android-only` |
| iOS native | `mobile-otel/otel-ios-mobile/` | Swift 5.9, iOS 15+ | Swift Testing (SwiftPM + Simulator) | `./scripts/ci/run-tests.sh --ios` |
| React Native | `mobile-otel/packages/react-native/` + `examples/upstream-demo-app-rn/` | TypeScript (RN 0.76 bare) | Jest + ts-jest + typecheck | `./scripts/ci/run-tests.sh --rn` |
| Collector processor | `mobile-otel/collector-processor/mobilepolicyprocessor/` | Go 1.24 | `go test -race` | `./scripts/ci/run-tests.sh --go-only` |

### The 19 instrumentation modules

**Production (10)** — fully tested, demo-proven:

| Module | What It Captures | How It Works |
|--------|------------------|--------------|
| **lifecycle** | Activity/fragment creation, pause, resume, destroy | ActivityLifecycleCallbacks |
| **tap** | Taps, long-press, swipes with coordinates | Window.Callback interception |
| **scroll** | RecyclerView scroll direction, distance | OnScrollListener, throttled |
| **text-input** | EditText focus/blur, field names | Focus change listener |
| **back-press** | Hardware/gesture back navigation | KeyEvent interception |
| **screen** | Screen transitions, page spans | Fragment/Activity lifecycle |
| **errors** | Uncaught exceptions, coroutine errors | Thread.setDefaultUncaughtExceptionHandler |
| **freeze** | ANR detection (main thread blocked >5s) | Looper watchdog on background thread |
| **vitals** | Memory, battery, jank, app-start timing | OTel Meter gauges/histograms |
| **network** | HTTP request/response spans | OkHttp Interceptor (user-wired) |

**Incubating (5)** — tested, opt-in via config:

| Module | What It Captures | How It Works |
|--------|------------------|--------------|
| **screenshot** | Screen capture with text redaction | PixelCopy API or View.draw |
| **wireframe** | View hierarchy as JSON (~1-5 KB) | View tree traversal |
| **compose-click** | Jetpack Compose click events | Semantics tree walker |
| **screen-orientation** | Device rotation changes | Configuration change listener |
| **debug-widget** | Live in-app overlay (buffer stats, export status) | WindowManager overlay |

**Incubating (4)** — newly shipped, tested:

| Module | What It Captures | How It Works |
|--------|------------------|--------------|
| **database** | Room/SQLite query spans | QueryCallback |
| **file-io** | File read/write spans | Traced wrapper pattern |
| **timber** | Timber log bridge to OTel | Timber.Tree subclass |
| **system-events** | Battery, power, airplane mode, storage | BroadcastReceiver |

### The policy engine (our #1 differentiator)

1. On app start, the SDK loads a config (bundled JSON or fetched from gateway)
2. The config contains policies — rules like "when event.name = app.crash, flush 5 minutes"
3. Every event entering the buffer is evaluated against all active policies
4. When a policy matches, the SDK exports events from the specified time window
5. Between matches: zero export (CONDITIONAL) or periodic heartbeat (HYBRID)

**Example policy JSON:**

```json
{
  "version": 2,
  "workflows": [{
    "id": "crash-handler",
    "enabled": true,
    "initial_state": "default",
    "states": [{
      "id": "default",
      "matchers": [{"type": "crash", "config": {}}],
      "on_match": {
        "actions": [{"type": "flush_buffer", "config": {"minutes": 5}}]
      }
    }]
  }]
}
```

**21 matcher types:** crash, ui_freeze, http_match, exception_pattern, metric_threshold, slow_operation, frame_drop, network_loss, network_restored, low_memory, battery_drain, thermal_throttle, storage_low, predictive_risk, anr, event_match, log_severity, slow_request, app_lifecycle, resource_snapshot, field_presence, field_absence.

**2 config formats:** V1 (flat trigger/action) and V2 (state-machine FSM). SDK auto-detects.

### Control plane

```
[React UI] → Visual graph editor (29 node types)
     │
     ▼ (compiles to DSL JSON)
[Go Gateway] → SQLite (configs, devices), serves to devices
     │
     ▼ (HTTP polling every 5 min)
[SDK PolicyEvaluator] → Parses v1 or v2, evaluates on-device
```

The SDK requests `?dsl_version=2` by default. If the gateway returns v1, it handles that too.

## R5 — Integration cheat sheet (for a customer)

### Step 1: Add the dependency

**Android** — published to a public Maven repo on GitHub Pages. No PAT / no authentication required:

```kotlin
// settings.gradle.kts repositories
maven { url = uri("https://barrysolomon.github.io/mobile-otel/maven") }

// build.gradle.kts
dependencies {
    implementation("io.github.barrysolomon:mobile:0.9.0-beta")
}
```

> **Legacy / transition (optional):** existing consumers may still pull the SDK from GitHub Packages, which requires a GitHub PAT with `read:packages`. This path is being phased out — prefer the public repo above.
>
> ```kotlin
> // settings.gradle.kts repositories — legacy GitHub Packages path
> maven {
>     url = uri("https://maven.pkg.github.com/barrysolomon/mobile-otel")
>     credentials {
>         username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
>         password = providers.gradleProperty("gpr.token").orNull ?: System.getenv("GITHUB_TOKEN")
>     }
> }
> ```

**iOS** — SwiftPM, repo `https://github.com/barrysolomon/mobile-otel` at tag `v0.9.0-beta`, product `OTelMobileSDK`.

**React Native** — npm:

```bash
npm install @barrysolomon/mobile-react-native   # or pin @0.9.0-beta
```

### Step 2: Initialize in `Application.onCreate()`

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        OTelMobile.start(this, MobileConfig(
            serviceName = "my-app",
            serviceVersion = "1.0.0",
            // Default protocol is OTLP HTTP/protobuf (POSTs to <endpoint>/v1/{logs,traces,metrics}).
            collectorEndpoint = "https://collector.dash0.com:4318",
            exportMode = ExportMode.HYBRID  // HYBRID is the default
        ))
    }
}
```

Auto-wired by `OTelMobile.start()`: lifecycle, crashes, sessions, predictive health, policy evaluation with built-in defaults.

> **Gotcha:** init is idempotent — first caller wins in dual-init races (RN). See `feedback_mobileotel_idempotency.md`.

### Step 3: Wire the network interceptor (recommended)

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(OTelNetworkInterceptor())
    .build()
```

### Step 4: Optional per-module config

```kotlin
OTelMobile.start(this, MobileConfig(
    serviceName = "my-app",
    collectorEndpoint = "https://collector.dash0.com:4318",
    // ...
    screenshotConfig = ScreenshotConfig(enabled = true, redactTextViews = true, quality = 50),
    errorConfig = ErrorConfig(rateLimit = 10, deduplicateWindowMs = 300_000)
))
```

> Sub-config field names are verified against `MobileConfig.kt` and the instrumentation modules. Note `ScreenshotConfig` is `@Incubating` and defaults to `enabled = false`; `ErrorConfig` uses `rateLimit` (per minute) and `deduplicateWindowMs`. Per-tap behaviour is controlled by the tap module's own `TapConfig`, which is wired via the modular builder rather than a `MobileConfig` field.

### Free with zero config

Lifecycle spans, taps/scroll/text/back, crashes with auto-flush, ANR/freeze, device health, sessions, dual-tier buffer, policy evaluator with defaults (crash → 5 min flush, freeze → 2 min, HTTP error → 2 min).

## R6 — Competitive positioning

### vs Splunk Mobile RUM

| Category | Us | Them |
|----------|-----|------|
| Modules | 19 | ~11 |
| On-device policy engine | Yes (21 matchers) | No |
| Export modes | 3 (Conditional / Continuous / Hybrid) | 1 (Continuous) |
| Battery (conditional) | <0.5% | 3–5% (continuous) |
| Build plugin required | No | Yes (ByteBuddy) |
| OTLP export | Native, current | Deprecated Feb 2025 |
| Crash recovery | seqId dedup, survives kill | Disk buffer, retry undocumented |
| Selective flush | Time-window around incidents | Everything always |
| Remote policy updates | Visual editor + remote push | None |

### vs Datadog Mobile RUM

Same advantages plus: Datadog has no OTLP. They're fully proprietary. We're fully open.

### vs Sentry

Sentry is crash-focused, not full observability. We capture crashes AND network AND UI AND device health AND use policy-driven selective export.

## R7 — Tough questions

**"But you don't have iOS."**
> "We do — iOS native is on the `iPhone` branch, validated end-to-end with AstronomyShop. RN iOS is also green. The full UAT matrix (12 scenarios × 4 platforms = 48 cells) is currently passing."

**"How mature is this? Can I run it in production?"**
> "Android SDK has 975+ unit tests across every config combo. iOS has 403 tests. We've validated on emulators with real crash recovery, offline resilience, and 28 automated scenario scripts. Security audit complete (10/10). What we haven't done yet is fleet-scale deploy — that's the next phase."

**"What about ProGuard/R8 symbolication?"**
> "Near-term roadmap. Debug builds work today. ProGuard mapping upload is in the next development cycle."

**"We already use [Datadog/Splunk/NewRelic]."**
> "Our SDK exports standard OTLP. It works alongside your existing tools — point it at your current collector and see mobile telemetry next to backend traces. No rip-and-replace."

**"What's the data model?"**
> "All three OTel signal types. **Traces (spans)**: page views, taps, network, DB queries — parent-child trees (journey → page → tap). **Logs**: crashes, system events, policy matches. **Metrics**: device health gauges and counters. The dual-tier buffer holds log records; spans go through the standard OTel span pipeline."

## R8 — Key files & glossary

### File cheat sheet

| What | Where |
|------|-------|
| Android entry point | `mobile-otel/otel-android-mobile/.../OTelMobile.kt` |
| iOS entry point | `mobile-otel/otel-ios-mobile/Sources/.../OTelMobile.swift` |
| RN entry point | `mobile-otel/packages/react-native/src/index.ts` |
| Buffer (the core) | `mobile-otel/otel-android-mobile/.../buffering/MobileLogRecordProcessor.kt` |
| Policy engine (Android) | `mobile-otel/otel-android-mobile/.../policy/PolicyEvaluator.kt` |
| Policy engine (iOS) | `mobile-otel/otel-ios-mobile/Sources/.../PolicyEvaluator.swift` |
| Config model | `mobile-otel/otel-android-mobile/.../config/MobileConfig.kt` |
| Instrumentation modules | `mobile-otel/instrumentation/<name>/src/.../` |
| Collector processor (Go) | `mobile-otel/collector-processor/mobilepolicyprocessor/` |
| DSL v2 producer | `mobile-otel-control-plane/control-plane-ui/src/utils/graphToDSLv2.ts` |
| DSL v2 schema | `mobile-otel-control-plane/docs/DSL_V2_SCHEMA.md` |
| Gateway API | `mobile-otel-control-plane/gateway/` |
| Battle cards | `mobile-otel/docs/BATTLE_CARD_VS_SPLUNK.md`, `…_DATADOG.md` |
| Roadmap | `mobile-otel/docs/project/ROADMAP.md` |
| Backlog | `mobile-otel/BACKLOG.md` |
| Demo runbooks | `mobile-otel/HOW_TO_DEMO.md`, `docs/HOW_TO_DEMO_IOS.md`, `docs/HOW_TO_DEMO_RN.md` |

### Glossary

| Term | Definition |
|------|------------|
| **Export policy** | Rule that defines when to flush buffered events. Not "workflow" (legacy). |
| **Selective flush** | Exporting a time window of events, not everything. Not "replay." |
| **Dual-tier buffer** | RAM (volatile) + SQLite (crash-safe). Events live in both. |
| **seqId** | Sequence ID on each event. Prevents double-export when RAM and disk both hold copies. |
| **CONDITIONAL mode** | Export only when a policy triggers. Most battery-efficient. |
| **CONTINUOUS mode** | Periodic export like traditional SDKs. Familiar but expensive. |
| **HYBRID mode** | Periodic heartbeat + conditional flush. Default on both Android and iOS (as of 0.2.0). |
| **DSL v1** | Flat trigger/action JSON. Produced by `graphToDSL.ts`. |
| **DSL v2** | State-machine (FSM) JSON. Produced by `graphToDSLv2.ts`. Supports transitions and timeouts. |
| **WindowEventHub** | Fan-out dispatcher: sends touch/key events to all registered modules. |
| **MobileInstrumentation** | Interface every module implements (`install()` / `uninstall()`). |
| **InstrumentationContext** | DI container passed to modules at install time. |
| **ContextSnapshot** | Privacy-safe device-state snapshot (country, network, battery) used during policy evaluation. |
| **OTel Collector** | Standalone process that receives/processes/exports telemetry. We ship a custom processor plugin. |
| **OTLP** | OpenTelemetry Protocol. Wire format. gRPC (`:4317`) or HTTP (`:4318`). |

---

## Appendix — Troubleshooting

### Android: `installDebug` fails with "No connected devices!"

The build succeeded; `adb` doesn't see a device.

```bash
adb devices                         # what does adb see?
emulator -list-avds                 # available AVDs
emulator -avd Pixel_7 &             # boot one
./gradlew :android:installDebug     # retry once status is "device"
```

If `adb` / `emulator` aren't on PATH:

```bash
export PATH="$HOME/Library/Android/sdk/platform-tools:$HOME/Library/Android/sdk/emulator:$PATH"
```

Physical device: data-capable USB cable, Developer Options → USB debugging ON, tap "Allow" on the RSA prompt, status `device` (not `unauthorized`/`offline`).

### iOS: signing or scheme errors after `xcodegen`

```bash
cd mobile-otel/examples/upstream-demo-app-ios
rm -rf AstronomyShop.xcodeproj
xcodegen generate
open AstronomyShop.xcodeproj
# Project → Signing & Capabilities → set Team to "None" for Simulator-only builds
```

### iOS 26 Simulator: RN root window blank

Add `UIApplicationSceneManifest` to `Info.plist`. The Astronomy and Schedulr demo apps already have it.

### RN: edited `otel-config.json` but it's not picked up

```bash
touch index.js && npx react-native run-android
```

Gradle's bundler doesn't track external `require()`d JSON files.

### Dash0 CLI: returns 0 rows but UI shows data

Three usual suspects:
1. Wrong filter syntax — use `key is value`, not `key=value`, and repeated `--filter` flags for AND
2. Wrong time window — `--from now-Xm` filters by **device event time**, so offline/crash cells need a wide window
3. Wrong namespace — use `event.name`, NOT `otel.event.name`

See `feedback_dash0_cli_filter_syntax.md`, `feedback_dash0_query_event_time.md`, `feedback_dash0_filter_event_name_namespace.md`.

### Dash0 strips `dash0.test.*` resource attributes

Move test/cell IDs to LogRecord attributes, not Resource attributes. See `feedback_dash0_resource_attribute_drop.md`.

### Reachability check fails but service is up

AWS load balancers filter ICMP echo. Use `nc -z <host> <port>`, not `ping`.

### Robolectric tests fail on JDK 17

Pin Robolectric to SDK 28 via `robolectric.properties` in the module. SDK 36 needs JDK 21. See `feedback_robolectric_sdk36_jdk21.md`.
