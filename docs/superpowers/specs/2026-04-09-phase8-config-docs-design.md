# Phase 8: Configuration Documentation + Runtime Config — Design Specification

**Date:** 2026-04-09
**Status:** Approved
**Scope:** User-facing config guide, technical API reference, fix validated tests via runtime config, seed config CLI tool in control plane repo.
**Parent Epic:** `docs/epics/UPSTREAM_SUPERSESSION_EPIC.md` (Phase 8, US-045 through US-048)

---

## 1. User-Facing Configuration Guide

**File:** `docs/CONFIGURATION_GUIDE.md`

Written for a developer integrating the SDK into their app. No assumptions about familiarity with OTel internals.

### Structure

```markdown
# Configuration Guide

## Quick Start
- Copy otel-config.json.template, fill in credentials
- Or use the Kotlin DSL (recommended)

## Configuration Methods (priority order)
1. Runtime override (SharedPreferences) — highest, for testing/debugging
2. Kotlin DSL (MobileOtel.initialize(context) { })  — primary
3. MobileConfig builder — programmatic alternative
4. otel-config.json (bundled asset) — fallback for demo apps

## otel-config.json Reference
- Every field with type, default, valid range, description
- Example configs for: Dash0 cloud, local collector, development

## Export Modes
- CONDITIONAL: when to use, battery impact, how policies trigger flush
- CONTINUOUS: when to use, interval tuning
- HYBRID: balanced approach, heartbeat + conditional

## Buffer Tuning
- RAM buffer: ramBufferSize (default 5000, max 100K)
- Disk buffer: diskBufferMb (default 50, max 500), ttlHours (default 24)
- When to increase/decrease each

## Instrumentation Module Configuration
- Screenshot: enabled, quality, resolution, text redaction
- Wireframe: enabled, capture interval
- Network: privacy presets (minimal, default, debug, production)
- Error: captureUncaught, captureCoroutine, scrubStackTraces
- Tap: swipeMinDistancePx, captureLongPress

## Runtime Config Override
- How to change config on a running app without rebuilding
- adb command for SharedPreferences override
- Useful for: testing, debugging, switching endpoints
- The config CLI tool (mobile-otel-control-plane)

## Sampling Configuration
- Dynamic sampling: normalRate, highPriorityRate
- Session-based sampling
```

## 2. Technical API Reference

**File:** `docs/API_REFERENCE.md`

### Structure

```markdown
# API Reference

## MobileOtel (entry point)
- initialize(context, config): MobileLoggerProvider
- initialize(context, config, customizers): MobileLoggerProvider
- initialize(context) { DSL }: OpenTelemetryMobile
- identify(user), clearIdentity(), terminateSession()
- sendEvent(), reportError()
- forceFlush(), getBufferStats()
- shutdown()

## OpenTelemetryMobile (DSL return type)
- openTelemetry: OpenTelemetry
- sessionId: String
- getTracer(scope), getLogger(scope), getMeter(scope)
- forceFlush(), flushWindow(minutes), shutdown()

## MobileConfig (all fields)
| Field | Type | Default | Range | Description |
For every field in the data class.

## MobileConfig.Builder
- Every setter method
- addLogExporterCustomizer(), addSpanExporterCustomizer(), addMetricExporterCustomizer()
- buildWithCustomizers(): Pair<MobileConfig, ExporterCustomizers>

## Kotlin DSL
- mobileOtel { } top-level
- service { name, version }
- export { endpoint, mode, headers, timeoutSeconds, maxRetries, traceIntervalSeconds, metricIntervalSeconds }
- buffering { ramSize, diskMb, ttlHours }
- session { renewalMinutes }
- exportCustomizers { log {}, span {}, metric {} }
- instrumentations { discoverAll(), discoverOwn(), add() }
- uiTelemetryMode

## ExporterCustomizers
- Container class
- Builder class
- Chain order: first registered = innermost

## MobileInstrumentation (module interface)
- instrumentationName, instrumentationVersion
- install(application, context), uninstall()
- @Supersedes annotation

## InstrumentationContext
- openTelemetry, sessionProvider, windowEventHub, application
- clock (optional)
- tracer(), logger(), meter() convenience methods

## All Instrumentation Modules
Table of all 22 modules with: name, @Supersedes, signal type, key config
```

## 3. Fix Validated Tests — Runtime Config via adb

### The Problem

The `run-validated-tests.sh` script swaps `otel-config.json` before building, but Gradle caches the APK and doesn't detect the asset change. The app runs with the old Dash0 endpoint.

### The Fix

Use `adb shell` to write to SharedPreferences AFTER installing the APK. `ConfigManager.loadConfig()` reads SharedPreferences with highest priority — no rebuild needed.

In `run-validated-tests.sh`, replace the asset swap with:

```bash
# Write config override to SharedPreferences on the device
# ConfigManager reads SharedPreferences first (highest priority)
configure_device_endpoint() {
  local serial=${1:-}
  local endpoint=$2
  local serial_flag=""
  [ -n "$serial" ] && serial_flag="-s $serial"

  adb $serial_flag shell "run-as io.opentelemetry.android.demo sh -c '
    mkdir -p shared_prefs
    cat > shared_prefs/otel_config.xml << PREFS_EOF
<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>
<map>
  <string name=\"collector_endpoint\">$endpoint</string>
  <string name=\"export_mode\">CONTINUOUS</string>
  <string name=\"service_name\">validated-test</string>
  <string name=\"service_version\">1.0.0</string>
  <string name=\"config_loaded_from_bundle\">true</string>
</map>
PREFS_EOF
  '"
}
```

Call this AFTER `installDebug` and BEFORE running scenarios. The app reads the overridden endpoint on next launch.

After tests complete, restore by deleting the prefs override:

```bash
restore_device_config() {
  local serial=${1:-}
  local serial_flag=""
  [ -n "$serial" ] && serial_flag="-s $serial"

  adb $serial_flag shell "run-as io.opentelemetry.android.demo sh -c '
    rm -f shared_prefs/otel_config.xml
  '"
}
```

### Updated run-validated-tests.sh flow

1. Start local collector (Docker, ports 14317/14318)
2. Build + install demo APK (normal build, no config swap)
3. **Write SharedPreferences override** pointing to `http://10.0.2.2:14317`
4. Force-stop app (so it picks up new prefs on relaunch)
5. Run scenarios
6. Wait for collector flush
7. **Delete SharedPreferences override** (restore original config)
8. Validate received telemetry
9. Stop collector

## 4. Config CLI Tool (mobile-otel-control-plane)

**Location:** `mobile-otel-control-plane/scripts/device/otel-device.sh`

A seed CLI for device configuration management. Wraps `adb shell` SharedPreferences operations. Designed to evolve into a proper control plane CLI that talks to the gateway API.

### Commands

```bash
# Set endpoint
otel-device config set --endpoint http://10.0.2.2:14317

# Set endpoint with auth
otel-device config set --endpoint https://ingress.dash0.com:4317 \
  --auth-token TOKEN --dataset otel-mobile

# Set export mode
otel-device config set --mode CONTINUOUS

# Set multiple values
otel-device config set --endpoint http://localhost:14317 --mode CONTINUOUS --service-name test

# Show current config
otel-device config show

# Reset to defaults (delete SharedPreferences override)
otel-device config reset

# Show all connected devices
otel-device list

# Target a specific device
otel-device -s emulator-5554 config show
```

### Implementation

```bash
#!/usr/bin/env bash
# otel-device — CLI for managing OTel SDK configuration on Android devices.
# Seed for a full control plane CLI. Currently wraps adb SharedPreferences operations.
#
# Future: will support gateway API calls for remote config management.

PKG="${OTEL_DEVICE_PACKAGE:-io.opentelemetry.android.demo}"  # override with --package or env var
PREFS_FILE="shared_prefs/otel_config.xml"
```

Each `config set` flag maps to a SharedPreferences key:
- `--endpoint` → `collector_endpoint`
- `--auth-token` → `auth_token`
- `--dataset` → `dataset`
- `--mode` → `export_mode` (CONDITIONAL/CONTINUOUS/HYBRID)
- `--service-name` → `service_name`
- `--service-version` → `service_version`

`config show` reads the current SharedPreferences XML and formats it.

`config reset` deletes the SharedPreferences file, falling back to bundled defaults.

### Future Evolution

This CLI is the seed. The roadmap for the full control plane CLI:
- `otel-device policy push` — push export policies from gateway to device
- `otel-device policy list` — list active policies on device
- `otel-device telemetry flush` — trigger immediate flush
- `otel-device telemetry stats` — buffer stats, export counts
- `otel-device session terminate` — force new session
- All commands eventually talk to the Go gateway API instead of direct adb

## 5. Testing

- Validated test script: run with local collector, verify telemetry arrives
- Config CLI: manual test — `set`, `show`, `reset` cycle
- Docs: no automated testing (reviewed by reading)

## 6. Files

### New Files (mobile-otel)
| File | Purpose |
|------|---------|
| `docs/CONFIGURATION_GUIDE.md` | User-facing config guide |
| `docs/API_REFERENCE.md` | Technical API reference |

### Modified Files (mobile-otel)
| File | Change |
|------|--------|
| `scripts/test/run-validated-tests.sh` | Replace asset swap with SharedPreferences override |

### New Files (mobile-otel-control-plane)
| File | Purpose |
|------|---------|
| `scripts/device/otel-device.sh` | Config CLI tool |

## 7. What's NOT in Scope

- Gateway API integration for the CLI (future roadmap)
- Auto-generated docs from KDoc (future)
- Config validation in the CLI (the SDK validates on init)
- iOS config equivalent (Phase 4 iOS spec update)
