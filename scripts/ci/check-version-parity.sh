#!/usr/bin/env bash
# check-version-parity.sh — assert the release version is identical across EVERY
# surface, so npm / native Maven / git tag / bridge-distro can never diverge.
#
# Background: the v0.5.1-alpha UAT failed partly because four version strings
# disagreed — the git tag said v0.5.1-alpha, package.json said 0.5.0-alpha, npm's
# highest was 0.4.1-alpha, and the RN bridge reported 0.4.1-alpha. This guard runs
# in the publish workflow (and can be run locally) and FAILS the release if any
# surface drifts.
#
# Usage:
#   scripts/ci/check-version-parity.sh                 # compare all surfaces to each other
#   scripts/ci/check-version-parity.sh v0.5.2-alpha    # ALSO require they equal this git tag
#
# Surfaces checked:
#   1. packages/react-native/package.json          "version"
#   2. examples/demo-app/gradle.properties         sdkVersionName   (native Maven)
#   3. packages/react-native/src/index.ts          DISTRO_VERSION   (RN bridge distro attr)
#   4. git tag (optional arg, with leading "v")
#
# bash 3.2 compatible (macOS default).
set -euo pipefail

# Resolve repo root from this script's location (scripts/ci/ -> repo root).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

fail() { echo "::error::$*" >&2; echo "VERSION PARITY: FAIL — $*" >&2; exit 1; }

# 1. npm package version
PKG_VERSION="$(node -p "require('$ROOT/packages/react-native/package.json').version" 2>/dev/null)" \
  || fail "could not read packages/react-native/package.json version"

# 2. native SDK version (single source of truth in gradle.properties)
GRADLE_VERSION="$(grep -E '^sdkVersionName=' "$ROOT/examples/demo-app/gradle.properties" | head -1 | cut -d= -f2 | tr -d '[:space:]')" \
  || true
[ -n "$GRADLE_VERSION" ] || fail "sdkVersionName not found in examples/demo-app/gradle.properties"

# 3. RN bridge distro version (grep the const literal)
DISTRO_VERSION="$(grep -E "^const DISTRO_VERSION" "$ROOT/packages/react-native/src/index.ts" | head -1 | sed -E "s/.*'([^']+)'.*/\1/")" \
  || true
[ -n "$DISTRO_VERSION" ] || fail "DISTRO_VERSION not found in packages/react-native/src/index.ts"

# 4. iOS SDK self-reported version (telemetry.sdk.version)
IOS_VERSION="$(grep -E "public static let sdkVersion" "$ROOT/otel-ios-mobile/Sources/OTelMobileSDK/Resource/ResourceBuilder.swift" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')" \
  || true
[ -n "$IOS_VERSION" ] || fail "sdkVersion not found in otel-ios-mobile ResourceBuilder.swift"

echo "package.json          = $PKG_VERSION"
echo "gradle sdkVersionName = $GRADLE_VERSION"
echo "src/index DISTRO      = $DISTRO_VERSION"
echo "iOS ResourceBuilder   = $IOS_VERSION"

[ "$PKG_VERSION" = "$GRADLE_VERSION" ] \
  || fail "npm ($PKG_VERSION) != native gradle sdkVersionName ($GRADLE_VERSION)"
[ "$PKG_VERSION" = "$DISTRO_VERSION" ] \
  || fail "npm ($PKG_VERSION) != RN bridge DISTRO_VERSION ($DISTRO_VERSION)"
[ "$PKG_VERSION" = "$IOS_VERSION" ] \
  || fail "npm ($PKG_VERSION) != iOS ResourceBuilder sdkVersion ($IOS_VERSION)"

# 4. Optional git tag comparison (publish workflow passes it).
if [ "${1:-}" != "" ]; then
  TAG="$1"
  EXPECTED="v$PKG_VERSION"
  echo "git tag               = $TAG (expected $EXPECTED)"
  [ "$TAG" = "$EXPECTED" ] \
    || fail "git tag ($TAG) != v<package.json version> ($EXPECTED). Tag a commit whose package.json/gradle/DISTRO all say ${TAG#v}."
fi

echo "VERSION PARITY: OK — all surfaces agree on $PKG_VERSION"
