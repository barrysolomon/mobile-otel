#!/usr/bin/env bash
# smoke-rn-public-consumer.sh — reproduce the v0.5.1-alpha UAT build blocker as a
# guard. A clean-room RN consumer's Android build failed with:
#   "Could not find io.opentelemetry.android:mobile:..."
# because the native SDK lived only on authenticated GitHub Packages. This guard
# spins up a THROWAWAY Gradle project whose ONLY repositories are PUBLIC
# (google, mavenCentral, and the project's public GitHub Pages Maven repo) — NO
# mavenLocal, NO GitHub Packages, NO PAT — and resolves the full native SDK tree.
# If the artifact isn't publicly resolvable, this fails exactly like the UAT did.
#
# Usage:
#   scripts/ci/smoke-rn-public-consumer.sh
#       resolve io.github.barrysolomon:mobile:<package.json version> from the LIVE
#       public repo (https://barrysolomon.github.io/mobile-otel/maven).
#   scripts/ci/smoke-rn-public-consumer.sh --repo file:///abs/path/to/pages-maven
#       resolve from a local Pages Maven dir (pre-release validation before the
#       gh-pages deploy exists).
#
# bash 3.2 compatible (macOS default).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

REPO_URL="https://barrysolomon.github.io/mobile-otel/maven"
if [ "${1:-}" = "--repo" ] && [ -n "${2:-}" ]; then
  REPO_URL="$2"
fi

GROUP="$(grep -E '^sdkGroupId=' "$ROOT/examples/demo-app/gradle.properties" | head -1 | cut -d= -f2 | tr -d '[:space:]')"
VERSION="$(node -p "require('$ROOT/packages/react-native/package.json').version")"
COORD="${GROUP}:mobile:${VERSION}"

echo "Smoke test: resolve ${COORD}"
echo "Public repo (only): ${REPO_URL}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/settings.gradle" <<EOF
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url '${REPO_URL}' }   // PUBLIC — no auth. The ONLY project repo.
    }
}
rootProject.name = 'rn-public-consumer-smoke'
EOF

cat > "$WORK/build.gradle" <<EOF
configurations { smoke }
dependencies { smoke '${COORD}' }
tasks.register('resolveSmoke') {
    doLast {
        def files = configurations.smoke.resolve()
        println "Resolved \${files.size()} artifacts for ${COORD}"
        assert files.any { it.name.contains('mobile') } : 'umbrella artifact missing'
    }
}
EOF

# Use the repo's gradle wrapper (examples/demo-app owns it).
GRADLE="$ROOT/examples/demo-app/gradlew"
if [ ! -x "$GRADLE" ]; then
  echo "::error::gradle wrapper not found at $GRADLE"; exit 1
fi

if "$GRADLE" -p "$WORK" resolveSmoke --console=plain --no-daemon; then
  echo "SMOKE OK — ${COORD} resolves from PUBLIC repos only (no PAT)."
else
  echo "::error::SMOKE FAIL — ${COORD} did NOT resolve from public repos. This is the v0.5.1-alpha UAT failure mode."
  exit 1
fi
