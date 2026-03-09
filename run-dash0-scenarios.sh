#!/usr/bin/env bash
# run-dash0-scenarios.sh — Run Dash0 demo scenario instrumented tests on Android emulators.
#
# USAGE
#   ./run-dash0-scenarios.sh [OPTIONS]
#
# SUITE OPTIONS (pick one; default: --all)
#   --all                 Run every scenario class
#   --journeys            UserJourneyScenarios   (happy-path, browse, error, directions)
#   --faults              FaultScenarios         (jank, memory, ANR, crash+recovery)
#   --conditional         ConditionalFlushScenarios (buffer accumulation + policy flush)
#   --stress              EmulatorStressScenarios  (battery drain, thermal, network loss, etc.)
#
# TEST SELECTION (within a suite; can combine with suite flags)
#   --test <name>         Run a single test method (e.g. --test batteryDrain)
#   --class <FQN>         Run a fully-qualified class directly
#
# DEVICE OPTIONS
#   --device <serial>     Run on a specific device/emulator (e.g. emulator-5554)
#                         Default: run on ALL connected devices in parallel
#   --list-devices        Print attached devices and exit
#
# BUILD OPTIONS
#   --dry-run             Build APK only; do not install or run tests
#   --no-build            Skip build; re-use last installed APK (fastest re-run)
#   --install-only        Build and install APK, then exit (no test execution)
#
# OUTPUT OPTIONS
#   --verbose             Stream full Gradle output (default: condensed summary)
#   --report              Open HTML test report in browser after run
#   --run-id <id>         Tag telemetry with a custom demo.run_id (default: timestamp)
#
# REPEAT / TIMING
#   --repeat <n>          Run the selected suite N times in sequence (default: 1)
#   --wait <seconds>      Pause between test classes (default: 2s; lets OTel flush)
#
# EXAMPLES
#   ./run-dash0-scenarios.sh --all
#   ./run-dash0-scenarios.sh --stress --test batteryDrain --verbose
#   ./run-dash0-scenarios.sh --journeys --faults --device emulator-5554
#   ./run-dash0-scenarios.sh --all --repeat 3 --run-id "load_test_1"
#   ./run-dash0-scenarios.sh --dry-run
#   ./run-dash0-scenarios.sh --list-devices

set -euo pipefail

# ── Colours ────────────────────────────────────────────────────────────────────
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEMO_DIR="$ROOT_DIR/examples/demo-app"
GRADLEW="$DEMO_DIR/gradlew"

# ── Defaults ───────────────────────────────────────────────────────────────────
SUITES=()
SINGLE_TEST=""
SINGLE_CLASS=""
TARGET_DEVICE=""
DRY_RUN=false
NO_BUILD=false
INSTALL_ONLY=false
VERBOSE=false
OPEN_REPORT=false
RUN_ID="$(date +%Y%m%d_%H%M%S)"
REPEAT=1
INTER_CLASS_WAIT=2

# ── Package / class map ────────────────────────────────────────────────────────
PKG="io.opentelemetry.android.demo.scenarios"
declare -A SUITE_CLASS=(
  [journeys]="${PKG}.UserJourneyScenarios"
  [faults]="${PKG}.FaultScenarios"
  [conditional]="${PKG}.ConditionalFlushScenarios"
  [stress]="${PKG}.EmulatorStressScenarios"
)

# ── Helpers ────────────────────────────────────────────────────────────────────
log()    { echo -e "${BOLD}[dash0]${NC} $*"; }
ok()     { echo -e "${GREEN}✓${NC} $*"; }
warn()   { echo -e "${YELLOW}⚠${NC} $*"; }
fail()   { echo -e "${RED}✗${NC} $*"; }
header() { echo -e "\n${CYAN}══════════════════════════════════════════${NC}"; echo -e "${CYAN}  $*${NC}"; echo -e "${CYAN}══════════════════════════════════════════${NC}\n"; }

usage() {
  sed -n '/^# USAGE/,/^[^#]/{ /^[^#]/d; s/^# \{0,2\}//; p }' "$0"
  exit 0
}

list_devices() {
  header "Connected Android devices"
  adb devices -l | tail -n +2 | grep -v "^$" || echo "  (none)"
  echo ""
  exit 0
}

die() { fail "$*"; exit 1; }

# ── Argument parsing ───────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --all)           SUITES=(journeys faults conditional stress); shift ;;
    --journeys)      SUITES+=("journeys");    shift ;;
    --faults)        SUITES+=("faults");      shift ;;
    --conditional)   SUITES+=("conditional"); shift ;;
    --stress)        SUITES+=("stress");      shift ;;
    --test)          SINGLE_TEST="$2";        shift 2 ;;
    --class)         SINGLE_CLASS="$2";       shift 2 ;;
    --device)        TARGET_DEVICE="$2";      shift 2 ;;
    --list-devices)  list_devices ;;
    --dry-run)       DRY_RUN=true;            shift ;;
    --no-build)      NO_BUILD=true;           shift ;;
    --install-only)  INSTALL_ONLY=true;       shift ;;
    --verbose)       VERBOSE=true;            shift ;;
    --report)        OPEN_REPORT=true;        shift ;;
    --run-id)        RUN_ID="$2";             shift 2 ;;
    --repeat)        REPEAT="$2";             shift 2 ;;
    --wait)          INTER_CLASS_WAIT="$2";   shift 2 ;;
    --help|-h)       usage ;;
    *) die "Unknown option: $1 (use --help for usage)" ;;
  esac
done

# Default to --all if nothing selected and no explicit --class/--test
if [[ ${#SUITES[@]} -eq 0 && -z "$SINGLE_CLASS" && -z "$SINGLE_TEST" ]]; then
  SUITES=(journeys faults conditional stress)
fi

# ── Validate environment ───────────────────────────────────────────────────────
[[ -f "$GRADLEW" ]] || die "Gradle wrapper not found at $GRADLEW"
command -v adb &>/dev/null || die "adb not found — install Android SDK platform-tools"

DEVICES=()
if [[ -n "$TARGET_DEVICE" ]]; then
  DEVICES=("$TARGET_DEVICE")
else
  while IFS= read -r line; do
    serial=$(echo "$line" | awk '{print $1}')
    [[ -n "$serial" ]] && DEVICES+=("$serial")
  done < <(adb devices | tail -n +2 | grep -v "^$" | grep -v "offline")
fi

[[ ${#DEVICES[@]} -gt 0 ]] || die "No connected devices found. Start an emulator or connect a device."

# ── Build instruction runner args ──────────────────────────────────────────────
build_runner_args() {
  local suite="$1"
  local args=()

  if [[ -n "$SINGLE_CLASS" ]]; then
    if [[ -n "$SINGLE_TEST" ]]; then
      args+=("-Pandroid.testInstrumentationRunnerArguments.class=${SINGLE_CLASS}#${SINGLE_TEST}")
    else
      args+=("-Pandroid.testInstrumentationRunnerArguments.class=${SINGLE_CLASS}")
    fi
  elif [[ -n "$SINGLE_TEST" && -n "$suite" ]]; then
    local class="${SUITE_CLASS[$suite]}"
    args+=("-Pandroid.testInstrumentationRunnerArguments.class=${class}#${SINGLE_TEST}")
  elif [[ -n "$suite" ]]; then
    local class="${SUITE_CLASS[$suite]}"
    args+=("-Pandroid.testInstrumentationRunnerArguments.class=${class}")
  else
    args+=("-Pandroid.testInstrumentationRunnerArguments.package=${PKG}")
  fi

  # Pass run ID so telemetry can be correlated in Dash0
  args+=("-Pandroid.testInstrumentationRunnerArguments.runId=${RUN_ID}")

  echo "${args[@]}"
}

# ── Gradle invocation ──────────────────────────────────────────────────────────
run_gradle() {
  local task="$1"; shift
  local extra_args=("$@")
  local gradle_args=("$task" "${extra_args[@]}")

  if [[ -n "$TARGET_DEVICE" ]]; then
    gradle_args+=("-Pandroid.testInstrumentationRunnerArguments.serial=${TARGET_DEVICE}")
  fi

  [[ "$VERBOSE" == false ]] && gradle_args+=("--quiet")
  gradle_args+=("--no-configuration-cache")

  if [[ "$VERBOSE" == true ]]; then
    "$GRADLEW" -p "$DEMO_DIR" "${gradle_args[@]}"
  else
    "$GRADLEW" -p "$DEMO_DIR" "${gradle_args[@]}" 2>&1 | \
      grep -E "PASSED|FAILED|ERROR|SKIPPED|Task.*FAILED|tests were|BUILD" || true
  fi
}

# ── Print plan ─────────────────────────────────────────────────────────────────
header "Dash0 Scenario Test Runner"
log "Run ID       : ${BOLD}${RUN_ID}${NC}"
log "Devices      : ${BOLD}${DEVICES[*]}${NC}"
log "Suites       : ${BOLD}${SUITES[*]:-<from --class/--test>}${NC}"
[[ -n "$SINGLE_TEST"  ]] && log "Single test  : ${BOLD}${SINGLE_TEST}${NC}"
[[ -n "$SINGLE_CLASS" ]] && log "Single class : ${BOLD}${SINGLE_CLASS}${NC}"
log "Repeat       : ${BOLD}${REPEAT}x${NC}"
log "Dry run      : ${BOLD}${DRY_RUN}${NC}"
echo ""
log "Telemetry → Dash0 endpoint: https://your-collector-endpoint:4317 (dataset: otel-mobile)"
echo ""

# ── Dry run: build only ────────────────────────────────────────────────────────
if [[ "$DRY_RUN" == true ]]; then
  header "Dry run — building test APK only"
  run_gradle ":android:assembleDebug" ":android:assembleDebugAndroidTest"
  ok "APK built. Not installing or running tests (--dry-run)."
  exit 0
fi

# ── Install only ───────────────────────────────────────────────────────────────
if [[ "$INSTALL_ONLY" == true ]]; then
  header "Installing APK on ${DEVICES[*]}"
  run_gradle ":android:installDebug"
  ok "Installed on ${DEVICES[*]}."
  exit 0
fi

# ── Skip build ─────────────────────────────────────────────────────────────────
if [[ "$NO_BUILD" == false ]]; then
  header "Building test APK"
  run_gradle ":android:assembleDebug" ":android:assembleDebugAndroidTest"
  ok "Build complete."
fi

# ── Run suites ─────────────────────────────────────────────────────────────────
FAILURES=0
TOTAL_TESTS=0
START_TIME=$(date +%s)

# Determine the effective list of things to run
EFFECTIVE_SUITES=("${SUITES[@]}")
if [[ ${#EFFECTIVE_SUITES[@]} -eq 0 ]]; then
  EFFECTIVE_SUITES=("__custom__")  # single class or package run
fi

for run in $(seq 1 "$REPEAT"); do
  [[ "$REPEAT" -gt 1 ]] && header "Run $run / $REPEAT"

  for suite in "${EFFECTIVE_SUITES[@]}"; do
    if [[ "$suite" == "__custom__" ]]; then
      DISPLAY_NAME="${SINGLE_CLASS:-${PKG}}"
    else
      DISPLAY_NAME="$suite (${SUITE_CLASS[$suite]##*.})"
    fi

    header "Suite: $DISPLAY_NAME"

    # Build runner arguments for this suite
    if [[ "$suite" == "__custom__" ]]; then
      RUNNER_ARGS=($(build_runner_args ""))
    else
      RUNNER_ARGS=($(build_runner_args "$suite"))
    fi

    log "Runner args: ${RUNNER_ARGS[*]}"
    echo ""

    if "$GRADLEW" -p "$DEMO_DIR" \
        :android:connectedDebugAndroidTest \
        "${RUNNER_ARGS[@]}" \
        $( [[ "$VERBOSE" == false ]] && echo "--quiet" ) \
        --no-configuration-cache 2>&1 | \
      tee >(grep -E "PASSED|FAILED|ERROR|SKIPPED|tests were|BUILD|Exception" || true); then
      ok "Suite '${DISPLAY_NAME}' PASSED"
    else
      fail "Suite '${DISPLAY_NAME}' FAILED"
      FAILURES=$((FAILURES + 1))
    fi

    # Inter-class pause — lets OTel export pipeline flush before next suite
    if [[ "${#EFFECTIVE_SUITES[@]}" -gt 1 && "$suite" != "${EFFECTIVE_SUITES[-1]}" ]]; then
      log "Waiting ${INTER_CLASS_WAIT}s before next suite (--wait to adjust)..."
      sleep "$INTER_CLASS_WAIT"
    fi
  done
done

# ── Summary ────────────────────────────────────────────────────────────────────
END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))
ELAPSED_FMT=$(printf '%dm%02ds' $((ELAPSED/60)) $((ELAPSED%60)))

header "Results"
log "Duration : ${ELAPSED_FMT}"
log "Run ID   : ${RUN_ID}"
log "Devices  : ${DEVICES[*]}"
echo ""

if [[ $FAILURES -eq 0 ]]; then
  ok "All suites PASSED"
  echo ""
  echo -e "  Search in Dash0 for telemetry from this run:"
  echo -e "  ${CYAN}demo.run_id = \"${RUN_ID}\"${NC}"
  echo ""
else
  fail "$FAILURES suite(s) FAILED"
  echo ""
fi

# ── Open HTML report ──────────────────────────────────────────────────────────
REPORT_PATH="$DEMO_DIR/android/build/reports/androidTests/connected/debug/index.html"
if [[ "$OPEN_REPORT" == true && -f "$REPORT_PATH" ]]; then
  log "Opening test report..."
  open "$REPORT_PATH" 2>/dev/null || xdg-open "$REPORT_PATH" 2>/dev/null || \
    log "Report: $REPORT_PATH"
elif [[ -f "$REPORT_PATH" ]]; then
  log "Test report: $REPORT_PATH"
fi

exit $FAILURES
