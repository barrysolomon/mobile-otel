# Script Organization Design

**Date**: 2026-04-08
**Status**: Draft
**Goal**: Organize scattered shell scripts into a categorized `scripts/` tree with thin root forwarders preserving all existing paths for CI and developer habits.

---

## Problem

Both repos have scripts scattered across the directory tree with no consistent organization:

- **mobile-otel/** has 10 `run-*.sh` / `verify-*.sh` scripts at the repo root, 1 shared library in `scripts/`, 2 E2E validators in `e2e-validation/`, 2 duplicate monkey-test scripts in example app dirs, and 1 integration test script nested in the collector processor.
- **mobile-otel-control-plane/** has scripts embedded in `gateway/` and `k8s/` with no central location.

## Approach

**Option A: Canonical `scripts/` tree with thin root forwarders.**

Move all scripts into a categorized `scripts/` directory. Leave one-line `exec` forwarders at the original paths so CI, docs, and muscle memory continue to work.

---

## Target Structure

### mobile-otel/

```
scripts/
  lib/
    demo-common.sh              # shared helpers (sourced, never executed)
  demo/
    run-demo-full.sh            # 2 emulators, backend, all tests
    run-demo-quick.sh           # 1 emulator, fastest path
    run-demo-single.sh          # single scenario by short name
    run-demo-backend.sh         # start/stop demo backend
    run-demo-scenarios.sh       # Espresso scenario suites
    run-dash0-scenarios.sh      # Dash0-specific scenario runner
  ci/
    run-tests.sh                # all tests (Android + Go)
    run-demo-ci.sh              # headless CI pipeline
  e2e/
    run-e2e.sh                  # zero-to-demo E2E stack
    validate-demo-telemetry.sh  # post-test Dash0 query validation
    dash0-e2e-test.sh           # OTLP round-trip E2E test
  test/
    monkey-test.sh              # consolidated from both example apps
    integration-test.sh         # collector processor Docker integration test
  setup/
    verify-setup.sh             # environment/SDK verification
```

### mobile-otel-control-plane/

```
scripts/
  gateway/
    build.sh                    # build + deploy gateway
    verify.sh                   # gateway build verification
  deploy/
    deploy-native.sh            # K8s OTEL-native deployment
```

---

## Forwarder Pattern

### Root forwarders (mobile-otel/)

Each original root-level script becomes a one-liner:

```bash
#!/usr/bin/env bash
exec "$(dirname "$0")/scripts/<category>/<script>.sh" "$@"
```

Full list of root forwarders:

| Original path | Forwards to |
|---|---|
| `run-tests.sh` | `scripts/ci/run-tests.sh` |
| `run-demo-full.sh` | `scripts/demo/run-demo-full.sh` |
| `run-demo-quick.sh` | `scripts/demo/run-demo-quick.sh` |
| `run-demo-ci.sh` | `scripts/ci/run-demo-ci.sh` |
| `run-demo-scenarios.sh` | `scripts/demo/run-demo-scenarios.sh` |
| `run-demo-single.sh` | `scripts/demo/run-demo-single.sh` |
| `run-demo-backend.sh` | `scripts/demo/run-demo-backend.sh` |
| `run-dash0-scenarios.sh` | `scripts/demo/run-dash0-scenarios.sh` |
| `run-e2e.sh` | `scripts/e2e/run-e2e.sh` |
| `verify-setup.sh` | `scripts/setup/verify-setup.sh` |

### Example-app forwarders (monkey-test)

```bash
#!/usr/bin/env bash
exec "$(dirname "$0")/../../scripts/test/monkey-test.sh" "$@"
```

Applied to both:
- `examples/demo-app/monkey-test.sh`
- `examples/demo-app-starter/monkey-test.sh`

### Control-plane forwarders

| Original path | Forwards to |
|---|---|
| `gateway/build.sh` | `scripts/gateway/build.sh` |
| `gateway/verify.sh` | `scripts/gateway/verify.sh` |
| `k8s/deploy-native.sh` | `scripts/deploy/deploy-native.sh` |

---

## Path Fixups Required

### 1. `demo-common.sh` source line in 6 scripts

Six scripts source the shared library via:
```bash
source "$(dirname "$0")/scripts/demo-common.sh"
```

After the move to `scripts/demo/`, update to:
```bash
source "$(dirname "$0")/../lib/demo-common.sh"
```

Affected scripts:
- `run-demo-full.sh`
- `run-demo-quick.sh`
- `run-demo-ci.sh`
- `run-demo-scenarios.sh`
- `run-demo-single.sh`
- `run-demo-backend.sh`

### 2. `demo-common.sh` REPO_ROOT computation

Current:
```bash
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[1]:-$0}")/.." && pwd)"
```

This uses `BASH_SOURCE[1]` (the caller's path) + `/..`. After the move to `scripts/lib/`, the caller is in `scripts/demo/` or `scripts/ci/`, so the `..` walks up to `scripts/` — not to the repo root.

Fix: use `BASH_SOURCE[0]` (demo-common.sh itself) and walk up two levels from `scripts/lib/`:
```bash
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
```

This is more robust — REPO_ROOT is always relative to where `demo-common.sh` lives, regardless of which script sources it.

### 3. Self-referencing scripts (mobile-otel/)

These scripts compute their own working directory and use relative paths. Each needs a `REPO_ROOT` anchor after the move.

- **`run-e2e.sh`**: Uses `SCRIPT_DIR` + `PROJECT_ROOT="$SCRIPT_DIR"`. After move to `scripts/e2e/`:
  - Change `PROJECT_ROOT` to `"$(cd "$SCRIPT_DIR/../.." && pwd)"` (resolve to absolute)
  - `BACKEND_DIR`, `APP_DIR` already derive from `PROJECT_ROOT` — no further changes

- **`run-tests.sh`**: Uses bare `cd examples/demo-app` relative to CWD (6 `cd` calls total: 3 forward into subdirs + 3 `cd ../..` returns, plus `cd collector-processor/mobilepolicyprocessor` and its return). After move to `scripts/ci/`:
  - Add `REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"` at the top
  - Replace all `cd examples/demo-app` (lines 82, 129, ~154) with `cd "$REPO_ROOT/examples/demo-app"`
  - Replace all `cd ../..` returns (lines 96, 123, 147) with `cd "$REPO_ROOT"`
  - Replace `cd collector-processor/mobilepolicyprocessor` (line 103) with `cd "$REPO_ROOT/collector-processor/mobilepolicyprocessor"`

- **`run-dash0-scenarios.sh`**: `ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"` then `DEMO_DIR="$ROOT_DIR/examples/demo-app"`. After move to `scripts/demo/`:
  - Change to `ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"`

- **`verify-setup.sh`**: Uses `SCRIPT_DIR` then `cd "$SCRIPT_DIR"` to anchor CWD, then bare relative paths (`dir_exists "otel-android-mobile"`, `cd examples/demo-app`, etc.). After move to `scripts/setup/`:
  - Add `REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"`
  - Change `cd "$SCRIPT_DIR"` to `cd "$REPO_ROOT"` (sets CWD to repo root)
  - All `cd "$SCRIPT_DIR"` returns (lines 164, 196) become `cd "$REPO_ROOT"`
  - Relative paths like `dir_exists "otel-android-mobile"` continue to work since CWD is now the repo root

- **`integration-test.sh`**: Uses `SCRIPT_DIR` and `COLLECTOR_DIR="$(dirname "$SCRIPT_DIR")"`. After move to `scripts/test/`:
  - Add `REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"`
  - Change `COLLECTOR_DIR` to `"$REPO_ROOT/collector-processor"`
  - Change `${SCRIPT_DIR}/test-config.yaml` (line 124, Docker volume mount) to `"$REPO_ROOT/collector-processor/integration_test/test-config.yaml"` (YAML stays in place; only the `.sh` moves)

### 3b. CWD-dependent scripts (mobile-otel-control-plane/)

All three control-plane scripts rely on CWD being their original containing directory. They use bare relative paths for kubectl manifests, go commands, and file existence checks. After the move, each needs an explicit `cd` to its original directory at the top.

- **`gateway/build.sh`**: Runs `go mod tidy`, `go test ./...`, `docker build .`, `kubectl apply -f ../k8s/otel-gateway.yaml` — all expect CWD = `gateway/`. After move to `scripts/gateway/`:
  - Add `cd "$(dirname "$0")/../../gateway"` at the top (after set -e)

- **`gateway/verify.sh`**: Checks `[ ! -f "go.mod" ]`, runs `go mod download`, `go build ./...`, `go test ./...` — all expect CWD = `gateway/`. After move to `scripts/gateway/`:
  - Add `cd "$(dirname "$0")/../../gateway"` at the top

- **`k8s/deploy-native.sh`**: Runs `kubectl apply -f otel-collector-native.yaml`, `kubectl delete -f otel-gateway.yaml` — expects CWD = `k8s/`. After move to `scripts/deploy/`:
  - Add `cd "$(dirname "$0")/../../k8s"` at the top

### 4. No CI YAML references

No `.yml`/`.yaml` files reference any scripts — no pipeline changes needed.

### 5. Documentation references

These files reference script paths and need updates to mention the `scripts/` tree:

- `mobile-otel/CLAUDE.md` (Quick Reference section)
- `mobile-otel/HOW_TO_DEMO.md`
- `mobile-otel/CONTRIBUTING.md`
- `CLAUDE.md` (workspace root)
- `docs/project/TEST_DASHBOARD.md`
- `mobile-otel-control-plane/CLAUDE.md`
- `mobile-otel-control-plane/gateway/README.md`
- `mobile-otel-control-plane/k8s/README.md`

Note: since root forwarders preserve the `./run-*.sh` invocation pattern, most doc references continue to work as-is. Update docs to mention the `scripts/` tree as the canonical location while noting the root aliases still work.

---

## Monkey Test Consolidation

The two `monkey-test.sh` files (`examples/demo-app/` and `examples/demo-app-starter/`) are **identical** (diff produces no output). The canonical copy moves to `scripts/test/monkey-test.sh`. Both example-app locations become exec forwarders.

---

## What Does NOT Change

- **Script logic**: Zero behavior changes — only file locations and path computations
- **Script contents**: No feature additions, no cleanup of internal helpers
- **CI pipelines**: No YAML references to update (none exist)
- **Root invocation UX**: `./run-tests.sh`, `./run-demo-full.sh` etc. continue to work from repo root
- **Collector integration test data files**: Only the `.sh` moves; Go test files and `test-config.yaml` stay in `collector-processor/integration_test/`

---

## Category Rationale

| Category | Purpose | Scripts |
|----------|---------|---------|
| `demo/` | Interactive demo workflows — assume human is watching | 6 scripts |
| `ci/` | Automated CI — headless, exit codes, no interaction | 2 scripts |
| `e2e/` | End-to-end validation against live Dash0 | 3 scripts |
| `test/` | Standalone test utilities (monkey, integration) | 2 scripts |
| `setup/` | Environment verification | 1 script |
| `lib/` | Shared code sourced by other scripts | 1 file |
| `gateway/` | Control-plane gateway build/verify | 2 scripts |
| `deploy/` | K8s deployment | 1 script |

---

## Execution Order

1. Create directory structure (`scripts/{lib,demo,ci,e2e,test,setup}` in mobile-otel, `scripts/{gateway,deploy}` in control-plane)
2. Move scripts to new locations via `git mv`
3. Update path computations in moved scripts
4. Replace original locations with thin forwarders
5. Consolidate monkey-test (delete duplicate, create forwarders)
6. Update documentation to reference new canonical paths
7. Smoke test: `bash -n` syntax check on every moved script (catches broken `source` paths at parse time), then run each forwarder with `--help` or dry-run to verify path resolution
