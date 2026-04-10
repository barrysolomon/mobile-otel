# Side-by-Side SDK Comparison Tutorial

Compare the upstream `opentelemetry-android` SDK with the Dash0 Mobile SDK using the same Compose app (upstream's astronomy shop demo) instrumented with both SDKs via Gradle product flavors.

---

## Prerequisites

- Android emulator (API 26+): `emulator -avd Medium_Phone_API_36.1`
- **Option A**: Dash0 account with `otel-config.json` credentials
- **Option B**: Docker for local OTel Collector (no account needed)

## Quick Start (5 min)

### 1. Build both APKs

```bash
cd mobile-otel/examples/demo-app
./gradlew :upstream-demo-app:assembleUpstreamDebug :upstream-demo-app:assembleDash0Debug
```

### 2. Configure export destination

**Option A — Dash0 (recommended for compelling comparison):**
```bash
cp examples/upstream-demo-app/src/main/assets/otel-config.json.template \
   examples/upstream-demo-app/src/main/assets/otel-config.json
# Edit: replace YOUR_COLLECTOR_ENDPOINT and YOUR_AUTH_TOKEN
```

**Option B — Local collector:**
Skip the config file. Both flavors fall back to `localhost` (emulator alias `10.0.2.2`).
```bash
cd examples/upstream-demo-app
docker compose up -d  # Starts OTel Collector on ports 4317/4318
```

### 3. Install both on emulator

```bash
./gradlew :upstream-demo-app:installUpstreamDebug :upstream-demo-app:installDash0Debug
```

Both apps install side-by-side (different `applicationId` suffixes).

### 4. Run the same user flow in each

1. Open "OTel Demo (Upstream)" app
2. Browse telescopes, tap a product, add to cart, go to checkout
3. Switch to "OTel Demo (Dash0)" app
4. Same flow: browse, tap, add to cart, checkout

---

## What You See in Dash0

### Upstream APK telemetry

Filter: `service.name = "opentelemetry-android-demo"` (or whatever the upstream defaults to)

- **Activity lifecycle spans**: activity created/resumed/paused
- **Crash spans**: uncaught exceptions
- **ANR events**: application not responding
- **Slow rendering**: frame timing metrics
- **Startup timing**: app launch duration
- **Session events**: session start/end
- **Network connectivity**: wifi/cellular changes
- **Fragment lifecycle**: fragment state changes
- Always-on export: events arrive continuously, ~30s batches

### Dash0 APK telemetry

Filter: `service.name = "astronomy-shop"`

Everything upstream captures, PLUS:

- **`ui.tap`** with element identity (`ui.element.id`, `ui.element.class`, `ui.element.label`)
- **`ui.tap` with Compose identity** (`ui.element.framework=compose`, `ui.element.test_tag`, `ui.element.role`)
- **`ui.scroll`** events with direction and velocity
- **`ui.text_input`** events on form fields
- **`ui.back_press`** events
- **`ui.screen_view`** with `page.<ScreenName>` parent spans
- **`device.orientation`** changes with previous/current values
- **`device.battery.low`**, **`device.power.connected`** system events
- **Breadcrumb trail** for journey reconstruction
- **Conditional export**: zero bandwidth until policy triggers (crash, error, freeze)

### The Key Diff

| What to look for | Upstream | Dash0 |
|-----------------|----------|-------|
| Total event types | ~9 | ~22 |
| Compose tap identity | Not captured | `testTag`, `role`, `contentDescription` |
| Scroll events | None | Per-RecyclerView with direction |
| Export pattern | Continuous stream | Silent until crash triggers flush |
| Journey context on crash | Lost (only crash event) | Full 2-minute context window |

---

## The Code Diff

The entire difference between the two SDKs is in the initialization. Same app code, different `SdkInitializer`:

**Upstream** (`src/upstream/java/.../SdkInitializer.kt`):
```kotlin
val rum = OpenTelemetryRum.builder(app)
    .addSpanExporterCustomizer { /* OTLP HTTP exporter */ }
    .build()
OtelDemoApplication.openTelemetry = rum.openTelemetry
```

**Dash0** (`src/dash0/java/.../SdkInitializer.kt`):
```kotlin
val mobile = MobileOtel.initialize(app) {
    service { name = "astronomy-shop"; version = "1.0.0" }
    export { endpoint = ExportConfig.grpcEndpoint; mode = CONDITIONAL }
    instrumentations { discoverAll() }
}
OtelDemoApplication.openTelemetry = mobile.openTelemetry
```

Same pattern, richer capabilities. 22 modules auto-discovered vs upstream's 9.

---

## Local Collector Setup (Docker)

If you don't have a Dash0 account:

```bash
cd examples/upstream-demo-app
docker compose up -d
```

This starts an OTel Collector configured to:
- Receive OTLP/HTTP on port 4318 (upstream flavor)
- Receive OTLP/gRPC on port 4317 (dash0 flavor)
- Log all received telemetry to stdout

View telemetry:
```bash
docker compose logs -f collector
```

Compare the output between the two app sessions — you'll see significantly more event types from the Dash0 flavor.

---

## See Also

- [Battle Card](BATTLE_CARD.md) — One-page competitive comparison
- [Talking Points](TALKING_POINTS.md) — For OTel SIG meetings
- [Design Spec](superpowers/specs/2026-04-09-upstream-supersession-design.md) — Full technical design
