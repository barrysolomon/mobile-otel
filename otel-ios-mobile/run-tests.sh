#!/usr/bin/env bash
# Wrapper for `swift test` that injects the Swift Testing framework search
# paths needed when running on macOS with only the Swift Command Line Tools
# installed (i.e. no Xcode). Under a full Xcode install this script is not
# required — plain `swift test` will work.
#
# Why this is needed:
#   * XCTest is not shipped with the Command Line Tools.
#   * The Swift Testing framework ships with the toolchain but is not on the
#     default framework search path, so the test bundle cannot find it at
#     build or run time.

set -euo pipefail

FRAMEWORKS_DIR="/Library/Developer/CommandLineTools/Library/Developer/Frameworks"

exec swift test \
    -Xswiftc -F -Xswiftc "${FRAMEWORKS_DIR}" \
    -Xlinker -F -Xlinker "${FRAMEWORKS_DIR}" \
    -Xlinker -rpath -Xlinker "${FRAMEWORKS_DIR}" \
    "$@"
