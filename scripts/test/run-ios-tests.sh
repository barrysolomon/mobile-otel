#!/usr/bin/env bash
# Run the iOS SDK test suite on both the macOS host (via swift test) and an
# iOS simulator (via xcodebuild test). Mirrors scripts/test/run-unit-tests.sh
# for the Android side.
#
# Usage:
#   scripts/test/run-ios-tests.sh              # host tests only (fast: ~1s)
#   scripts/test/run-ios-tests.sh --simulator  # add iOS simulator tests (~20s)
#   scripts/test/run-ios-tests.sh --all        # host + simulator
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
IOS_ROOT="$REPO_ROOT/otel-ios-mobile"

INCLUDE_SIMULATOR=0
case "${1:-}" in
    --simulator|--ios-sim) INCLUDE_SIMULATOR=1 ;;
    --all) INCLUDE_SIMULATOR=1 ;;
    --help|-h)
        head -12 "$0" | sed 's/^# \?//'
        exit 0
        ;;
esac

if [[ ! -d "$IOS_ROOT" ]]; then
    echo "ERROR: $IOS_ROOT does not exist — is this a mobile-otel checkout?" >&2
    exit 1
fi

echo "== iOS SDK tests (host: swift test via run-tests.sh wrapper) =="
cd "$IOS_ROOT"
./run-tests.sh

if [[ "$INCLUDE_SIMULATOR" == "1" ]]; then
    echo ""
    echo "== iOS SDK tests (iPhone 17 Simulator, iOS SDK) =="
    if [[ -z "${DEVELOPER_DIR:-}" && -d /Applications/Xcode.app ]]; then
        export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
    fi
    xcodebuild test \
        -scheme OTelMobile-Package \
        -destination "platform=iOS Simulator,name=iPhone 17" \
        2>&1 | grep -E '^(Test |✔|✘|\*\*|error:|failed:)' | tail -20
fi

echo ""
echo "Done."
