#!/usr/bin/env bash
# CI pipeline — headless, all tests (unit + instrumented), no manual interaction.
# Usage:
#   ./run-demo-ci.sh                 # full CI run
#   ./run-demo-ci.sh --skip-emu      # emulators already running (e.g., GitHub Actions)
#   ./run-demo-ci.sh --unit-only     # skip emulator tests
set -euo pipefail
source "$(dirname "$0")/../lib/demo-common.sh"

SKIP_EMU=false; UNIT_ONLY=false
for arg in "$@"; do
  case "$arg" in
    --skip-emu)  SKIP_EMU=true ;;
    --unit-only) UNIT_ONLY=true ;;
  esac
done

FAILURES=0

# 1. Start emulators (headless) unless skipping
if [ "$UNIT_ONLY" = false ] && [ "$SKIP_EMU" = false ]; then
  start_emulators true
elif [ "$UNIT_ONLY" = false ]; then
  require_emulators
fi

# 2. Unit tests
log "Running unit tests"
cd "$DEMO_APP"
if ./gradlew \
  :otel-android-mobile:testDebugUnitTest \
  :otel-android-mobile-core:testDebugUnitTest \
  :instrumentation-tap:testDebugUnitTest \
  :instrumentation-freeze:testDebugUnitTest \
  :instrumentation-back-press:testDebugUnitTest \
  :instrumentation-vitals:testDebugUnitTest \
  :instrumentation-screenshot:testDebugUnitTest \
  :instrumentation-wireframe:testDebugUnitTest; then
  ok "Unit tests passed"
else
  err "Unit tests failed"
  FAILURES=$((FAILURES + 1))
fi

# 3. Lint
log "Running lint"
cd "$DEMO_APP"
if ./gradlew :otel-android-mobile:lint --quiet; then
  ok "Lint passed"
else
  err "Lint failed"
  FAILURES=$((FAILURES + 1))
fi

# 4. Build
log "Building"
cd "$DEMO_APP"
if ./gradlew :otel-android-mobile:build --quiet; then
  ok "Build passed"
else
  err "Build failed"
  FAILURES=$((FAILURES + 1))
fi

if [ "$UNIT_ONLY" = true ]; then
  log "Skipping emulator tests (--unit-only)"
else
  # 5. Build & install
  build_and_install

  # 6. SDK instrumented tests
  log "Running SDK instrumented tests"
  cd "$DEMO_APP"
  if ./gradlew :otel-android-mobile:connectedDebugAndroidTest; then
    ok "SDK instrumented tests passed"
  else
    err "SDK instrumented tests failed"
    FAILURES=$((FAILURES + 1))
  fi

  # 7. Demo scenarios
  log "Running demo scenarios"
  cd "$DEMO_APP"
  if ./gradlew :android:connectedDebugAndroidTest; then
    ok "Demo scenarios passed"
  else
    err "Demo scenarios failed"
    FAILURES=$((FAILURES + 1))
  fi
fi

# 8. Go tests
log "Running Go tests (collector processor)"
cd "$REPO_ROOT/collector-processor/mobilepolicyprocessor"
if go test -v -race ./... 2>&1 | tail -5; then
  ok "Go tests passed"
else
  err "Go tests failed"
  FAILURES=$((FAILURES + 1))
fi

# Summary
echo ""
if [ $FAILURES -eq 0 ]; then
  ok "All CI checks passed"
  exit 0
else
  err "$FAILURES check(s) failed"
  exit 1
fi
