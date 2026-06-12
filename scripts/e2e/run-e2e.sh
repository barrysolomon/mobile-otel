#!/usr/bin/env bash
# =============================================================================
# run-e2e.sh — Zero-to-demo: backend, emulators, build, install, test
#
# Takes an unconfigured system and runs the full E2E stack:
#   1. Checks prerequisites (node, docker, Android SDK, emulator AVDs)
#   2. Starts the demo backend (Docker or local)
#   3. Starts Android emulators (creates AVD if missing)
#   4. Builds and installs the demo app
#   5. Runs unit tests
#   6. Runs E2E instrumented tests
#
# Usage:
#   ./run-e2e.sh                    # Full run (Docker backend + emulators + tests)
#   ./run-e2e.sh --local-backend    # Run backend locally instead of Docker
#   ./run-e2e.sh --skip-backend     # Skip backend (already running)
#   ./run-e2e.sh --skip-tests       # Build + install only, no tests
#   ./run-e2e.sh --headless         # Emulators without GUI (CI mode)
#   ./run-e2e.sh --one-emulator     # Single emulator instead of two
#   ./run-e2e.sh --teardown         # Stop everything started by this script
# =============================================================================

set -euo pipefail

# ── Project root ─────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BACKEND_DIR="$PROJECT_ROOT/examples/demo-backend"
APP_DIR="$PROJECT_ROOT/examples/demo-app"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
PIDFILE="/tmp/mobile-otel-e2e.pids"

# ── Colors ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m'

# ── Defaults ─────────────────────────────────────────────────────────────────
BACKEND_MODE="docker"         # docker | local | skip
RUN_TESTS=true
HEADLESS=false
EMU_COUNT=2
TEARDOWN=false
ALLOW_NO_DASH0=false          # --allow-no-dash0: missing token degrades to warning, not failure
# Overridable: machines differ in which AVDs exist (E2E_AVD_PRIMARY=Pixel_7a ./run-e2e.sh)
AVD_PRIMARY="${E2E_AVD_PRIMARY:-Pixel_7}"
AVD_SECONDARY="${E2E_AVD_SECONDARY:-Pixel_3a}"
BOOT_TIMEOUT=300              # 5 minutes max wait for emulator boot

# ── Parse args ───────────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case $1 in
        --local-backend)   BACKEND_MODE="local"; shift ;;
        --skip-backend)    BACKEND_MODE="skip"; shift ;;
        --skip-tests)      RUN_TESTS=false; shift ;;
        --headless)        HEADLESS=true; shift ;;
        --one-emulator)    EMU_COUNT=1; shift ;;
        --allow-no-dash0)  ALLOW_NO_DASH0=true; shift ;;
        --teardown)        TEARDOWN=true; shift ;;
        --help|-h)
            sed -n '/^# Usage:/,/^# =====/p' "$0" | head -n -1 | sed 's/^# //'
            exit 0
            ;;
        *) echo -e "${RED}Unknown option: $1${NC}"; exit 1 ;;
    esac
done

# ── Helpers ──────────────────────────────────────────────────────────────────
step()  { echo -e "\n${BLUE}${BOLD}▸ $1${NC}"; }
ok()    { echo -e "  ${GREEN}✓ $1${NC}"; }
warn()  { echo -e "  ${YELLOW}⚠ $1${NC}"; }
fail()  { echo -e "  ${RED}✗ $1${NC}"; }
die()   { fail "$1"; exit 1; }

save_pid() { echo "$1:$2" >> "$PIDFILE"; }

cleanup_pids() {
    if [[ ! -f "$PIDFILE" ]]; then return; fi
    while IFS=: read -r label pid; do
        if kill -0 "$pid" 2>/dev/null; then
            echo -e "  Stopping $label (PID $pid)..."
            kill "$pid" 2>/dev/null || true
        fi
    done < "$PIDFILE"
    rm -f "$PIDFILE"
}

wait_for_port() {
    local port=$1 timeout=${2:-30} elapsed=0
    while ! lsof -ti:"$port" >/dev/null 2>&1; do
        sleep 1
        elapsed=$((elapsed + 1))
        if [[ $elapsed -ge $timeout ]]; then return 1; fi
    done
}

wait_for_emulator_boot() {
    local serial=$1 elapsed=0
    echo -n "  Waiting for $serial to boot"
    while [[ $elapsed -lt $BOOT_TIMEOUT ]]; do
        local boot_complete
        boot_complete=$(adb -s "$serial" shell "getprop sys.boot_completed" 2>/dev/null | tr -d '\r' || echo "")
        if [[ "$boot_complete" == "1" ]]; then
            echo ""
            return 0
        fi
        echo -n "."
        sleep 5
        elapsed=$((elapsed + 5))
    done
    echo ""
    return 1
}

# ── Teardown mode ────────────────────────────────────────────────────────────
if [[ "$TEARDOWN" == true ]]; then
    step "Tearing down E2E environment"
    cleanup_pids

    # Stop Docker backend if running
    if docker compose -f "$BACKEND_DIR/docker-compose.yml" ps --quiet 2>/dev/null | grep -q . 2>/dev/null; then
        echo "  Stopping Docker backend..."
        docker compose -f "$BACKEND_DIR/docker-compose.yml" down 2>/dev/null || true
    fi

    # Kill any local backend on port 3001
    if lsof -ti:3001 >/dev/null 2>&1; then
        echo "  Stopping process on port 3001..."
        lsof -ti:3001 | xargs kill 2>/dev/null || true
    fi

    # Stop emulators
    for serial in $(adb devices 2>/dev/null | grep "emulator-" | awk '{print $1}' || true); do
        echo "  Stopping emulator $serial..."
        adb -s "$serial" emu kill 2>/dev/null || true
    done

    ok "Teardown complete"
    exit 0
fi

# ══════════════════════════════════════════════════════════════════════════════
echo -e "${BOLD}"
echo "╔══════════════════════════════════════════════════════════╗"
echo "║   Mobile OTel — End-to-End Demo Runner                  ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo -e "${NC}"
echo "  Backend:    $BACKEND_MODE"
echo "  Emulators:  $EMU_COUNT ($( [[ $HEADLESS == true ]] && echo "headless" || echo "windowed" ))"
echo "  Tests:      $( [[ $RUN_TESTS == true ]] && echo "yes" || echo "skip" )"
echo ""

# ── Step 0: Prerequisites ───────────────────────────────────────────────────
step "Checking prerequisites"

# Node.js
if command -v node >/dev/null 2>&1; then
    ok "Node.js $(node --version)"
else
    die "Node.js not found. Install via: brew install node (or nvm)"
fi

# npm
command -v npm >/dev/null 2>&1 || die "npm not found"

# Docker (only if needed)
if [[ "$BACKEND_MODE" == "docker" ]]; then
    if command -v docker >/dev/null 2>&1; then
        ok "Docker found"
        # Check if Docker daemon is running
        if ! docker info >/dev/null 2>&1; then
            warn "Docker daemon not running — starting Docker Desktop..."
            open -a Docker 2>/dev/null || die "Cannot start Docker. Please start Docker Desktop manually."
            echo -n "  Waiting for Docker daemon"
            for i in $(seq 1 60); do
                if docker info >/dev/null 2>&1; then echo ""; break; fi
                echo -n "."
                sleep 2
                if [[ $i -eq 60 ]]; then echo ""; die "Docker daemon did not start in 2 minutes"; fi
            done
            ok "Docker daemon started"
        else
            ok "Docker daemon running"
        fi
    else
        die "Docker not found. Install Docker Desktop: https://docker.com/products/docker-desktop"
    fi
fi

# Android SDK
if [[ -d "$ANDROID_HOME" ]]; then
    ok "Android SDK at $ANDROID_HOME"
else
    die "Android SDK not found at $ANDROID_HOME. Set ANDROID_HOME or install Android Studio."
fi

# Emulator binary
EMU_BIN="$ANDROID_HOME/emulator/emulator"
if [[ -x "$EMU_BIN" ]]; then
    ok "Emulator binary found"
else
    die "Emulator binary not found at $EMU_BIN"
fi

# adb
if command -v adb >/dev/null 2>&1; then
    ok "adb found"
elif [[ -x "$ANDROID_HOME/platform-tools/adb" ]]; then
    export PATH="$ANDROID_HOME/platform-tools:$PATH"
    ok "adb found at $ANDROID_HOME/platform-tools/adb"
else
    die "adb not found. Install Android SDK Platform-Tools."
fi

# Java / Gradle
if [[ -f "$APP_DIR/gradlew" ]]; then
    ok "Gradle wrapper found"
else
    die "Gradle wrapper not found at $APP_DIR/gradlew"
fi

# ── Step 1: Backend ─────────────────────────────────────────────────────────
step "Setting up demo backend"

if [[ "$BACKEND_MODE" == "skip" ]]; then
    if lsof -ti:3001 >/dev/null 2>&1; then
        ok "Backend already running on :3001"
    else
        warn "Backend not detected on :3001 — proceeding anyway"
    fi
elif [[ "$BACKEND_MODE" == "docker" ]]; then
    # Check for .env
    if [[ ! -f "$BACKEND_DIR/.env" ]]; then
        if [[ -f "$BACKEND_DIR/.env.example" ]]; then
            warn "No .env found — copying from .env.example (edit with real Dash0 creds for trace export)"
            cp "$BACKEND_DIR/.env.example" "$BACKEND_DIR/.env"
        else
            die "No .env or .env.example found in $BACKEND_DIR"
        fi
    fi

    # Stop existing container if running
    docker compose -f "$BACKEND_DIR/docker-compose.yml" down 2>/dev/null || true

    # Build and start
    echo "  Building and starting Docker container..."
    docker compose -f "$BACKEND_DIR/docker-compose.yml" up -d --build
    ok "Docker backend starting on :3001"

    # Wait for health check
    echo -n "  Waiting for backend health check"
    for i in $(seq 1 30); do
        if curl -sf http://localhost:3001/health >/dev/null 2>&1; then
            echo ""
            ok "Backend healthy"
            break
        fi
        echo -n "."
        sleep 1
        if [[ $i -eq 30 ]]; then
            echo ""
            warn "Backend did not respond to health check in 30s — check 'docker compose logs'"
        fi
    done
elif [[ "$BACKEND_MODE" == "local" ]]; then
    # Check for .env
    if [[ ! -f "$BACKEND_DIR/.env" ]]; then
        if [[ -f "$BACKEND_DIR/.env.example" ]]; then
            warn "No .env found — copying from .env.example"
            cp "$BACKEND_DIR/.env.example" "$BACKEND_DIR/.env"
        fi
    fi

    # Kill existing process on 3001
    lsof -ti:3001 | xargs kill 2>/dev/null || true
    sleep 1

    # Install deps if needed
    if [[ ! -d "$BACKEND_DIR/node_modules" ]]; then
        echo "  Installing backend dependencies..."
        (cd "$BACKEND_DIR" && npm install)
    fi

    # Start backend in background (tracing.ts is imported inline by index.ts)
    echo "  Starting backend locally..."
    (cd "$BACKEND_DIR" && npx tsx src/index.ts > /tmp/demo-backend.log 2>&1) &
    BACKEND_PID=$!
    save_pid "demo-backend" "$BACKEND_PID"

    echo -n "  Waiting for backend health check"
    for i in $(seq 1 20); do
        if curl -sf http://localhost:3001/health >/dev/null 2>&1; then
            echo ""
            ok "Backend healthy (PID $BACKEND_PID, log: /tmp/demo-backend.log)"
            break
        fi
        echo -n "."
        sleep 1
        if [[ $i -eq 20 ]]; then
            echo ""
            die "Backend failed to start. Check /tmp/demo-backend.log"
        fi
    done
fi

# ── Step 2: AVDs ────────────────────────────────────────────────────────────
step "Checking emulator AVDs"

AVDS_NEEDED=()
[[ $EMU_COUNT -ge 1 ]] && AVDS_NEEDED+=("$AVD_PRIMARY")
[[ $EMU_COUNT -ge 2 ]] && AVDS_NEEDED+=("$AVD_SECONDARY")

AVAILABLE_AVDS=$("$EMU_BIN" -list-avds 2>/dev/null)

for avd in "${AVDS_NEEDED[@]}"; do
    if echo "$AVAILABLE_AVDS" | grep -q "^${avd}$"; then
        ok "AVD '$avd' exists"
    else
        warn "AVD '$avd' not found — creating it..."
        SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
        if [[ ! -x "$SDKMANAGER" ]]; then
            SDKMANAGER="$ANDROID_HOME/tools/bin/sdkmanager"
        fi
        if [[ ! -x "$SDKMANAGER" ]]; then
            die "sdkmanager not found. Install Android SDK Command-line Tools via Android Studio > SDK Manager."
        fi

        # Install system image
        echo "  Downloading system image (this may take a while)..."
        "$SDKMANAGER" --install "system-images;android-36;google_apis;arm64-v8a" </dev/null || \
        "$SDKMANAGER" --install "system-images;android-34;google_apis;arm64-v8a" </dev/null || \
            die "Failed to download system image"

        # Determine which image was installed
        SYS_IMAGE="system-images;android-36;google_apis;arm64-v8a"
        if [[ ! -d "$ANDROID_HOME/system-images/android-36" ]]; then
            SYS_IMAGE="system-images;android-34;google_apis;arm64-v8a"
        fi

        # Create AVD
        AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
        if [[ ! -x "$AVDMANAGER" ]]; then
            AVDMANAGER="$ANDROID_HOME/tools/bin/avdmanager"
        fi
        echo "no" | "$AVDMANAGER" create avd -n "$avd" -k "$SYS_IMAGE" --device "pixel_7" --force
        ok "Created AVD '$avd'"
    fi
done

# ── Step 3: Start emulators ─────────────────────────────────────────────────
step "Starting emulators"

# Check if emulators are already running
RUNNING_EMUS=$(adb devices 2>/dev/null | grep -c "emulator-" || true)

if [[ $RUNNING_EMUS -ge $EMU_COUNT ]]; then
    ok "$RUNNING_EMUS emulator(s) already running — reusing"
else
    # Determine emulator flags
    EMU_FLAGS=(-no-snapshot-save)
    if [[ "$HEADLESS" == true ]]; then
        EMU_FLAGS+=(-no-window -no-audio)
    fi

    # Start needed emulators
    for i in $(seq 0 $((EMU_COUNT - 1))); do
        avd="${AVDS_NEEDED[$i]}"
        # Check if this specific AVD is already running
        echo "  Launching emulator: $avd"
        nohup "$EMU_BIN" -avd "$avd" "${EMU_FLAGS[@]}" > "/tmp/emulator-${avd}.log" 2>&1 &
        EMU_PID=$!
        save_pid "emulator-$avd" "$EMU_PID"
        ok "Emulator $avd started (PID $EMU_PID, log: /tmp/emulator-${avd}.log)"
        sleep 3  # Brief pause between launches to avoid port conflicts
    done

    # Wait for adb to see them
    echo -n "  Waiting for adb to detect emulators"
    for i in $(seq 1 30); do
        DETECTED=$(adb devices 2>/dev/null | grep -c "emulator-" || true)
        if [[ $DETECTED -ge $EMU_COUNT ]]; then
            echo ""
            break
        fi
        echo -n "."
        sleep 2
        if [[ $i -eq 30 ]]; then
            echo ""
            die "Emulators not detected by adb after 60s. Check /tmp/emulator-*.log"
        fi
    done

    # Wait for each emulator to fully boot
    for serial in $(adb devices 2>/dev/null | grep "emulator-" | awk '{print $1}' | head -n "$EMU_COUNT" || true); do
        if wait_for_emulator_boot "$serial"; then
            ok "$serial booted"
        else
            die "$serial did not boot within ${BOOT_TIMEOUT}s"
        fi
    done
fi

# ── Step 4: Build demo app ──────────────────────────────────────────────────
step "Building demo app"

cd "$APP_DIR"

# Check for otel-config.json
OTEL_CONFIG="android/src/debug/assets/otel-config.json"
OTEL_TEMPLATE="android/src/debug/assets/otel-config.json.template"
if [[ ! -f "$OTEL_CONFIG" ]] && [[ -f "$OTEL_TEMPLATE" ]]; then
    warn "No otel-config.json found — copying from template (edit with real Dash0 creds)"
    cp "$OTEL_TEMPLATE" "$OTEL_CONFIG"
fi

echo "  Building debug APK (this takes 1-2 minutes on first run)..."
./gradlew assembleDebug
ok "APK built"

# ── Step 5: Install on emulators ────────────────────────────────────────────
step "Installing demo app on emulators"

./gradlew installDebug
ok "Installed on all connected emulators"

# Launch the app on each emulator
for serial in $(adb devices 2>/dev/null | grep "emulator-" | awk '{print $1}' | head -n "$EMU_COUNT" || true); do
    adb -s "$serial" shell am start -n io.opentelemetry.android.demo/.SchedulingActivity 2>/dev/null
    ok "Launched app on $serial"
done

# ── Step 6: Run tests ───────────────────────────────────────────────────────
if [[ "$RUN_TESTS" == true ]]; then
    FAILURES=0
    # Captured BEFORE any suite runs: the Dash0 receipt gate only counts telemetry
    # emitted after this instant, so a previous run's data can't green this one.
    RUN_START_EPOCH=$(date +%s)

    # Unit tests
    step "Running unit tests"
    if ./gradlew :otel-android-mobile:testDebugUnitTest \
        :otel-android-mobile-core:testDebugUnitTest \
        :instrumentation-tap:testDebugUnitTest \
        :instrumentation-freeze:testDebugUnitTest \
        :instrumentation-back-press:testDebugUnitTest \
        :instrumentation-vitals:testDebugUnitTest; then
        ok "Unit tests passed"
    else
        fail "Unit tests failed"
        FAILURES=$((FAILURES + 1))
    fi

    # Backend tests
    step "Running backend tests"
    if (cd "$BACKEND_DIR" && npm test); then
        ok "Backend tests passed"
    else
        fail "Backend tests failed"
        FAILURES=$((FAILURES + 1))
    fi

    # SDK instrumented tests
    step "Running SDK instrumented tests"
    if ./gradlew :otel-android-mobile:connectedDebugAndroidTest; then
        ok "SDK instrumented tests passed"
    else
        fail "SDK instrumented tests failed"
        FAILURES=$((FAILURES + 1))
    fi

    # E2E scenario tests
    step "Running E2E scenario tests (this takes ~8 minutes)"
    if ./gradlew :android:connectedDebugAndroidTest; then
        ok "E2E scenario tests passed"
    else
        fail "E2E scenario tests failed"
        FAILURES=$((FAILURES + 1))
    fi

    # Real crash + recovery — the one signal the gradle suites CANNOT produce:
    # an instrumented test can't crash its own process. Without this step the
    # receipt gate's app.crash expectation can never be satisfied by this
    # script's own drivers (found 2026-06-12: the gate had only ever passed
    # because a human had driven a crash manually inside the window).
    # Phase 1 generates pre-crash events and dies (non-zero exit EXPECTED);
    # Phase 2 relaunches, which flushes the crash-mirrored events + app.crash.
    step "Driving real crash + recovery (app.crash for the receipt gate)"
    FIRST_SERIAL=$(adb devices 2>/dev/null | grep "emulator-" | awk '{print $1}' | head -n 1)
    adb -s "$FIRST_SERIAL" shell am instrument -w \
        -e class io.opentelemetry.android.demo.scenarios.RealCrashPhase1Test \
        io.opentelemetry.android.demo.test/androidx.test.runner.AndroidJUnitRunner \
        >/dev/null 2>&1 || true  # process death is the point
    sleep 3
    if adb -s "$FIRST_SERIAL" shell am instrument -w \
        -e class io.opentelemetry.android.demo.scenarios.RealCrashPhase2Test \
        io.opentelemetry.android.demo.test/androidx.test.runner.AndroidJUnitRunner \
        2>&1 | grep -q "OK (1 test)"; then
        ok "Crash + recovery driven (app.crash flush on relaunch)"
    else
        fail "Crash recovery phase failed — app.crash will be missing from the receipt gate"
        FAILURES=$((FAILURES + 1))
    fi

    # Dash0 receipt gate — tests are NOT green unless the telemetry the
    # scenarios drove (normal + crash + offline) actually arrived in Dash0.
    # REST-based (no `dash0` CLI needed); skipped only if no token is configured.
    if [[ -n "${DASH0_AUTH_TOKEN:-}" ]] || [[ -f "$APP_DIR/.env" ]]; then
        step "Verifying telemetry reached Dash0 (green = data in Dash0)"
        # RUN_START_EPOCH (set before the test suites ran) scopes the gate to THIS
        # run's telemetry — leftovers from a previous run can't green a broken SDK.
        if "$SCRIPT_DIR/verify-dash0.sh" android-native --window-min "${DASH0_WINDOW_MIN:-20}" \
              --since "${RUN_START_EPOCH:-0}" --retry-for "${DASH0_RETRY_FOR:-90}"; then
            ok "Dash0 telemetry verified"
        else
            fail "Dash0 telemetry verification FAILED — device tests passed but data did not arrive"
            FAILURES=$((FAILURES + 1))
        fi
    elif [[ "$ALLOW_NO_DASH0" == "true" ]]; then
        echo ""
        echo -e "${YELLOW}${BOLD}  ⚠ Dash0 receipt gate SKIPPED (--allow-no-dash0): end-to-end delivery NOT verified${NC}"
        echo ""
    else
        # A silently skipped gate is a gate that lies: without the receipt check the
        # suite can go green while no telemetry ever reaches Dash0. Skipping must be
        # an explicit, visible decision — not a side effect of a missing token.
        fail "Dash0 receipt gate cannot run: DASH0_AUTH_TOKEN is not set and no $APP_DIR/.env exists."
        echo "  End-to-end delivery was NOT verified. Either:"
        echo "    - export DASH0_AUTH_TOKEN (or create $APP_DIR/.env), or"
        echo "    - pass --allow-no-dash0 to explicitly accept skipping the gate"
        FAILURES=$((FAILURES + 1))
    fi

    # Summary
    echo ""
    echo -e "${BOLD}══════════════════════════════════════════════════════════${NC}"
    if [[ $FAILURES -eq 0 ]]; then
        echo -e "${GREEN}${BOLD}  All tests passed!${NC}"
    else
        echo -e "${RED}${BOLD}  $FAILURES test suite(s) failed${NC}"
    fi
    echo -e "${BOLD}══════════════════════════════════════════════════════════${NC}"
else
    echo ""
    echo -e "${BOLD}══════════════════════════════════════════════════════════${NC}"
    echo -e "${GREEN}${BOLD}  Setup complete — app running on emulators${NC}"
    echo -e "${BOLD}══════════════════════════════════════════════════════════${NC}"
fi

echo ""
echo "  Backend:     http://localhost:3001/health"
echo "  Backend API: http://localhost:3001/api/doctors"
echo "  Simulate:    curl -X POST localhost:3001/api/admin/simulate -H 'Content-Type: application/json' -d '{\"error\": true}'"
echo "  Reset:       curl -X DELETE localhost:3001/api/admin/simulate"
echo "  Teardown:    ./run-e2e.sh --teardown"
echo ""

# Propagate the verdict. Without this the script ends on an echo and exits 0
# even when suites failed — a CI caller would read failure as success.
if [[ "${RUN_TESTS}" == "true" && "${FAILURES:-0}" -gt 0 ]]; then
    exit 1
fi
exit 0
