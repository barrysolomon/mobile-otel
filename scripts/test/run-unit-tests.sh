#!/usr/bin/env bash
# Run all unit tests (no emulator needed).
# Usage:
#   ./run-unit-tests.sh              # all Android + Go
#   ./run-unit-tests.sh --android    # Android only
#   ./run-unit-tests.sh --go         # Go only
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEMO_APP="$REPO_ROOT/examples/demo-app"

log()  { echo -e "\n\033[1;36m▸ $*\033[0m"; }
ok()   { echo -e "\033[1;32m  ✓ $*\033[0m"; }

ANDROID=true; GO=true
for arg in "$@"; do
  case "$arg" in
    --android) GO=false ;;
    --go) ANDROID=false ;;
  esac
done

if [ "$ANDROID" = true ]; then
  log "Running Android unit tests (all 14 modules)"
  cd "$DEMO_APP"
  ./gradlew \
    :otel-android-mobile-core:testDebugUnitTest \
    :otel-android-mobile:testDebugUnitTest \
    :instrumentation-tap:testDebugUnitTest \
    :instrumentation-freeze:testDebugUnitTest \
    :instrumentation-back-press:testDebugUnitTest \
    :instrumentation-vitals:testDebugUnitTest \
    :instrumentation-screen:testDebugUnitTest \
    :instrumentation-scroll:testDebugUnitTest \
    :instrumentation-lifecycle:testDebugUnitTest \
    :instrumentation-errors:testDebugUnitTest \
    :instrumentation-screenshot:testDebugUnitTest \
    :instrumentation-wireframe:testDebugUnitTest \
    :instrumentation-compose-click:testDebugUnitTest \
    :instrumentation-screen-orientation:testDebugUnitTest
  ok "Android unit tests passed"
fi

if [ "$GO" = true ]; then
  log "Running Go processor tests"
  cd "$REPO_ROOT/collector-processor/mobilepolicyprocessor"
  go test -v -race ./...
  ok "Go tests passed"
fi

echo ""
ok "All unit tests passed"
