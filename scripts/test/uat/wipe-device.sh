#!/usr/bin/env bash
# Inter-phase wipe — uninstall every demo / RN package on a device or simulator
# so the next platform's matrix starts on a clean slate.
#
# Usage:
#   wipe-device.sh android <adb-serial>      # uninstall demo + RN packages, clear local data
#   wipe-device.sh ios <simulator name>      # uninstall Schedulr + AstronomyShop bundles
#
# Idempotent — package not present returns success.

set -uo pipefail

PLATFORM="${1:-}"
TARGET="${2:-}"

if [[ -z "$PLATFORM" || -z "$TARGET" ]]; then
    echo "usage: $0 android <serial> | ios <sim-name>" >&2
    exit 1
fi

case "$PLATFORM" in
    android)
        echo "wiping demo + RN packages on $TARGET"
        for pkg in \
            io.opentelemetry.android.demo \
            io.opentelemetry.android.demo.test \
            io.opentelemetry.android.mobile.test \
            io.opentelemetry.android.demo.dash0Continuous \
            io.opentelemetry.android.demo.dash0Conditional \
            io.opentelemetry.android.demo.dash0Hybrid \
            com.astronomyshoprn \
            com.astronomyshoprn.dash0.cont \
            com.astronomyshoprn.dash0.cond \
            com.astronomyshoprn.dash0.hyb ; do
            adb -s "$TARGET" uninstall "$pkg" >/dev/null 2>&1 && echo "  - removed $pkg" || true
        done
        adb -s "$TARGET" shell "rm -rf /data/local/tmp/* 2>/dev/null" || true
        adb -s "$TARGET" shell "df /data" | tail -1
        ;;
    ios)
        DEV_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
        echo "wiping iOS bundles on $TARGET"
        for bundle in \
            com.dash0.mobile.demo.Schedulr \
            org.opentelemetry.demo.astronomy-shop \
            com.astronomyshoprn \
            com.dash0.mobile.demo.Schedulr.uitest ; do
            DEVELOPER_DIR="$DEV_DIR" xcrun simctl uninstall "$TARGET" "$bundle" 2>/dev/null && echo "  - removed $bundle" || true
        done
        echo "iOS wipe complete"
        ;;
    *)
        echo "unknown platform: $PLATFORM" >&2
        exit 1
        ;;
esac
