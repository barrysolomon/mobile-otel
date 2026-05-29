#!/usr/bin/env bash
# Wrapper for `swift test` that injects the Swift Testing framework search
# paths needed when running on macOS with only the Swift Command Line Tools
# installed (i.e. no Xcode). Under a full Xcode install (DEVELOPER_DIR set
# or xcode-select pointing at Xcode), plain `swift test` finds Testing
# without help — and the explicit -F flags below would mis-resolve symbols
# against the CLT framework binaries when compile-time uses Xcode's, so
# we skip them.

set -euo pipefail

# Detect an active Xcode toolchain. If present, plain `swift test` is correct
# and the framework-injection flags would cause undefined-symbol linker errors.
ACTIVE_DEVELOPER_DIR="${DEVELOPER_DIR:-$(xcode-select -p 2>/dev/null || true)}"
if [[ -n "${ACTIVE_DEVELOPER_DIR}" && "${ACTIVE_DEVELOPER_DIR}" == */Xcode.app/* ]]; then
    exec swift test "$@"
fi

FRAMEWORKS_DIR="/Library/Developer/CommandLineTools/Library/Developer/Frameworks"

exec swift test \
    -Xswiftc -F -Xswiftc "${FRAMEWORKS_DIR}" \
    -Xlinker -F -Xlinker "${FRAMEWORKS_DIR}" \
    -Xlinker -rpath -Xlinker "${FRAMEWORKS_DIR}" \
    "$@"
