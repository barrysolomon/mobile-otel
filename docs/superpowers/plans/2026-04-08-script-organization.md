# Script Organization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reorganize ~18 scattered shell scripts into categorized `scripts/` trees with thin root forwarders preserving all existing invocation paths.

**Architecture:** Move scripts via `git mv` into `scripts/{lib,demo,ci,e2e,test,setup}` (mobile-otel) and `scripts/{gateway,deploy}` (control-plane). Fix path computations in moved scripts. Replace original locations with one-line forwarders.

**Tech Stack:** Bash, git

**Spec:** `docs/superpowers/specs/2026-04-08-script-organization-design.md`

---

### Task 1: Create directory structure and move shared library

**Files:**
- Create dirs: `mobile-otel/scripts/{lib,demo,ci,e2e,test,setup}`
- Move: `mobile-otel/scripts/demo-common.sh` -> `mobile-otel/scripts/lib/demo-common.sh`
- Modify: `mobile-otel/scripts/lib/demo-common.sh:5` (REPO_ROOT fix)

- [ ] **Step 1: Create the directory tree**

```bash
cd mobile-otel
mkdir -p scripts/{lib,demo,ci,e2e,test,setup}
```

- [ ] **Step 2: Move demo-common.sh to lib/**

```bash
cd mobile-otel
git mv scripts/demo-common.sh scripts/lib/demo-common.sh
```

- [ ] **Step 3: Fix REPO_ROOT computation in demo-common.sh**

In `scripts/lib/demo-common.sh`, change line 5 from:
```bash
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[1]:-$0}")/.." && pwd)"
```
to:
```bash
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
```

This anchors REPO_ROOT to where `demo-common.sh` lives (`scripts/lib/`), walking up two levels to the repo root. Works regardless of which script sources it.

- [ ] **Step 4: Commit**

```bash
cd mobile-otel
git add scripts/
git commit -m "chore: create scripts/ tree and move demo-common.sh to scripts/lib/

Move the shared helper library to scripts/lib/ and fix REPO_ROOT
to use BASH_SOURCE[0] (self-referencing) instead of BASH_SOURCE[1]
(caller-referencing) for more robust path resolution."
```

---

### Task 2: Move demo scripts and fix source paths

**Files:**
- Move: `mobile-otel/run-demo-full.sh` -> `mobile-otel/scripts/demo/run-demo-full.sh`
- Move: `mobile-otel/run-demo-quick.sh` -> `mobile-otel/scripts/demo/run-demo-quick.sh`
- Move: `mobile-otel/run-demo-single.sh` -> `mobile-otel/scripts/demo/run-demo-single.sh`
- Move: `mobile-otel/run-demo-backend.sh` -> `mobile-otel/scripts/demo/run-demo-backend.sh`
- Move: `mobile-otel/run-demo-scenarios.sh` -> `mobile-otel/scripts/demo/run-demo-scenarios.sh`
- Move: `mobile-otel/run-dash0-scenarios.sh` -> `mobile-otel/scripts/demo/run-dash0-scenarios.sh`
- Modify: source line in 5 of these (all except run-dash0-scenarios.sh which doesn't use demo-common.sh)
- Modify: `run-dash0-scenarios.sh:55` (ROOT_DIR fix)

- [ ] **Step 1: Move all 6 scripts**

```bash
cd mobile-otel
git mv run-demo-full.sh scripts/demo/
git mv run-demo-quick.sh scripts/demo/
git mv run-demo-single.sh scripts/demo/
git mv run-demo-backend.sh scripts/demo/
git mv run-demo-scenarios.sh scripts/demo/
git mv run-dash0-scenarios.sh scripts/demo/
```

- [ ] **Step 2: Fix source line in 5 demo scripts**

In each of these files, change:
```bash
source "$(dirname "$0")/scripts/demo-common.sh"
```
to:
```bash
source "$(dirname "$0")/../lib/demo-common.sh"
```

Files to edit:
- `scripts/demo/run-demo-full.sh` (line 8)
- `scripts/demo/run-demo-quick.sh` (line 10)
- `scripts/demo/run-demo-scenarios.sh` (line 11)
- `scripts/demo/run-demo-single.sh` (line 11)
- `scripts/demo/run-demo-backend.sh` (line 9)

- [ ] **Step 3: Fix ROOT_DIR in run-dash0-scenarios.sh**

In `scripts/demo/run-dash0-scenarios.sh`, change line 55 from:
```bash
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
```
to:
```bash
ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
```

- [ ] **Step 4: Create root forwarders for all 6 scripts**

Write each forwarder file with the pattern `#!/usr/bin/env bash` followed by the forwarding line:

`mobile-otel/run-demo-full.sh`:
```bash
#!/usr/bin/env bash
"$(dirname "$0")/scripts/demo/run-demo-full.sh" "$@"
```

`mobile-otel/run-demo-quick.sh`:
```bash
#!/usr/bin/env bash
"$(dirname "$0")/scripts/demo/run-demo-quick.sh" "$@"
```

`mobile-otel/run-demo-single.sh`:
```bash
#!/usr/bin/env bash
"$(dirname "$0")/scripts/demo/run-demo-single.sh" "$@"
```

`mobile-otel/run-demo-backend.sh`:
```bash
#!/usr/bin/env bash
"$(dirname "$0")/scripts/demo/run-demo-backend.sh" "$@"
```

`mobile-otel/run-demo-scenarios.sh`:
```bash
#!/usr/bin/env bash
"$(dirname "$0")/scripts/demo/run-demo-scenarios.sh" "$@"
```

`mobile-otel/run-dash0-scenarios.sh`:
```bash
#!/usr/bin/env bash
"$(dirname "$0")/scripts/demo/run-dash0-scenarios.sh" "$@"
```

Make them all executable:
```bash
chmod +x mobile-otel/run-demo-full.sh mobile-otel/run-demo-quick.sh mobile-otel/run-demo-single.sh mobile-otel/run-demo-backend.sh mobile-otel/run-demo-scenarios.sh mobile-otel/run-dash0-scenarios.sh
```

- [ ] **Step 5: Verify source resolution**

```bash
cd mobile-otel
for f in scripts/demo/run-demo-full.sh scripts/demo/run-demo-quick.sh scripts/demo/run-demo-scenarios.sh scripts/demo/run-demo-single.sh scripts/demo/run-demo-backend.sh; do
  bash -n "$f" && echo "OK: $f" || echo "FAIL: $f"
done
```

Expected: all OK.

- [ ] **Step 6: Commit**

```bash
cd mobile-otel
git add scripts/demo/ run-demo-full.sh run-demo-quick.sh run-demo-single.sh run-demo-backend.sh run-demo-scenarios.sh run-dash0-scenarios.sh
git commit -m "chore: move demo scripts to scripts/demo/ with root forwarders

Move 6 demo/scenario scripts to scripts/demo/, fix source paths
to point to scripts/lib/demo-common.sh, fix ROOT_DIR in
run-dash0-scenarios.sh. Root forwarders preserve ./run-demo-*.sh UX."
```

---

### Task 3: Move CI scripts and fix paths

**Files:**
- Move: `mobile-otel/run-tests.sh` -> `mobile-otel/scripts/ci/run-tests.sh`
- Move: `mobile-otel/run-demo-ci.sh` -> `mobile-otel/scripts/ci/run-demo-ci.sh`
- Modify: `scripts/ci/run-tests.sh` (add REPO_ROOT, fix 8 `cd` calls)
- Modify: `scripts/ci/run-demo-ci.sh:8` (fix source line)

- [ ] **Step 1: Move both scripts**

```bash
cd mobile-otel
git mv run-tests.sh scripts/ci/
git mv run-demo-ci.sh scripts/ci/
```

- [ ] **Step 2: Fix run-demo-ci.sh source line**

In `scripts/ci/run-demo-ci.sh`, change line 8 from:
```bash
source "$(dirname "$0")/scripts/demo-common.sh"
```
to:
```bash
source "$(dirname "$0")/../lib/demo-common.sh"
```

- [ ] **Step 3: Fix run-tests.sh path computations**

In `scripts/ci/run-tests.sh`, add REPO_ROOT after the `set -e` line (line 9):

```bash
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
```

Then make these replacements throughout the file:

| Old | New |
|-----|-----|
| `cd examples/demo-app` | `cd "$REPO_ROOT/examples/demo-app"` |
| `cd collector-processor/mobilepolicyprocessor` | `cd "$REPO_ROOT/collector-processor/mobilepolicyprocessor"` |
| `cd ../..` | `cd "$REPO_ROOT"` |

There are 3 occurrences of `cd examples/demo-app`, 1 of `cd collector-processor/mobilepolicyprocessor`, and 4 of `cd ../..`.

- [ ] **Step 4: Create root forwarders**

`mobile-otel/run-tests.sh`:
```bash
#!/usr/bin/env bash
"$(dirname "$0")/scripts/ci/run-tests.sh" "$@"
```

`mobile-otel/run-demo-ci.sh`:
```bash
#!/usr/bin/env bash
"$(dirname "$0")/scripts/ci/run-demo-ci.sh" "$@"
```

```bash
chmod +x mobile-otel/run-tests.sh mobile-otel/run-demo-ci.sh
```

- [ ] **Step 5: Verify**

```bash
cd mobile-otel
bash -n scripts/ci/run-tests.sh && echo "OK: run-tests.sh" || echo "FAIL"
bash -n scripts/ci/run-demo-ci.sh && echo "OK: run-demo-ci.sh" || echo "FAIL"
```

- [ ] **Step 6: Commit**

```bash
cd mobile-otel
git add scripts/ci/ run-tests.sh run-demo-ci.sh
git commit -m "chore: move CI scripts to scripts/ci/ with root forwarders

Move run-tests.sh and run-demo-ci.sh to scripts/ci/. Add REPO_ROOT
anchoring to run-tests.sh (replaces 8 relative cd calls). Fix source
path in run-demo-ci.sh."
```

---

### Task 4: Move E2E scripts and fix paths

**Files:**
- Move: `mobile-otel/run-e2e.sh` -> `mobile-otel/scripts/e2e/run-e2e.sh`
- Move: `mobile-otel/e2e-validation/validate-demo-telemetry.sh` -> `mobile-otel/scripts/e2e/validate-demo-telemetry.sh`
- Move: `mobile-otel/e2e-validation/dash0-e2e-test.sh` -> `mobile-otel/scripts/e2e/dash0-e2e-test.sh`
- Modify: `scripts/e2e/run-e2e.sh:29` (PROJECT_ROOT fix)

- [ ] **Step 1: Move all 3 scripts**

```bash
cd mobile-otel
git mv run-e2e.sh scripts/e2e/
git mv e2e-validation/validate-demo-telemetry.sh scripts/e2e/
git mv e2e-validation/dash0-e2e-test.sh scripts/e2e/
```

Remove the now-empty directory if git didn't already:
```bash
rmdir e2e-validation 2>/dev/null || true
```

- [ ] **Step 2: Fix PROJECT_ROOT in run-e2e.sh**

In `scripts/e2e/run-e2e.sh`, change line 29 from:
```bash
PROJECT_ROOT="$SCRIPT_DIR"
```
to:
```bash
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
```

`BACKEND_DIR` and `APP_DIR` (lines 30-31) derive from `PROJECT_ROOT`, so no further changes needed.

- [ ] **Step 3: Create root forwarder for run-e2e.sh**

`mobile-otel/run-e2e.sh`:
```bash
#!/usr/bin/env bash
"$(dirname "$0")/scripts/e2e/run-e2e.sh" "$@"
```

```bash
chmod +x mobile-otel/run-e2e.sh
```

Note: `validate-demo-telemetry.sh` and `dash0-e2e-test.sh` were in `e2e-validation/`, not the repo root. No root forwarders needed — they're only called explicitly.

- [ ] **Step 4: Verify**

```bash
cd mobile-otel
bash -n scripts/e2e/run-e2e.sh && echo "OK" || echo "FAIL"
bash -n scripts/e2e/validate-demo-telemetry.sh && echo "OK" || echo "FAIL"
bash -n scripts/e2e/dash0-e2e-test.sh && echo "OK" || echo "FAIL"
```

- [ ] **Step 5: Commit**

```bash
cd mobile-otel
git add scripts/e2e/ run-e2e.sh
git rm -r --cached e2e-validation/ 2>/dev/null || true
git commit -m "chore: move E2E scripts to scripts/e2e/

Move run-e2e.sh, validate-demo-telemetry.sh, dash0-e2e-test.sh.
Fix PROJECT_ROOT in run-e2e.sh to walk up from scripts/e2e/ to
repo root. Remove e2e-validation/ directory."
```

---

### Task 5: Move test scripts (monkey-test consolidation + integration test)

**Files:**
- Move: `mobile-otel/examples/demo-app/monkey-test.sh` -> `mobile-otel/scripts/test/monkey-test.sh`
- Modify: `mobile-otel/examples/demo-app/monkey-test.sh` (becomes forwarder)
- Modify: `mobile-otel/examples/demo-app-starter/monkey-test.sh` (becomes forwarder)
- Move: `mobile-otel/collector-processor/integration_test/integration_test.sh` -> `mobile-otel/scripts/test/integration-test.sh`
- Modify: `scripts/test/integration-test.sh` (fix SCRIPT_DIR, COLLECTOR_DIR, test-config path)

- [ ] **Step 1: Move monkey-test.sh to canonical location**

```bash
cd mobile-otel
git mv examples/demo-app/monkey-test.sh scripts/test/monkey-test.sh
```

- [ ] **Step 2: Create forwarders for both example apps**

`examples/demo-app/monkey-test.sh`:
```bash
#!/usr/bin/env bash
"$(dirname "$0")/../../scripts/test/monkey-test.sh" "$@"
```

`examples/demo-app-starter/monkey-test.sh` — replace the full file content with:
```bash
#!/usr/bin/env bash
"$(dirname "$0")/../../scripts/test/monkey-test.sh" "$@"
```

```bash
chmod +x examples/demo-app/monkey-test.sh examples/demo-app-starter/monkey-test.sh
```

- [ ] **Step 3: Move integration_test.sh**

```bash
cd mobile-otel
git mv collector-processor/integration_test/integration_test.sh scripts/test/integration-test.sh
```

- [ ] **Step 4: Fix paths in integration-test.sh**

In `scripts/test/integration-test.sh`, change lines 25-26 from:
```bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COLLECTOR_DIR="$(dirname "$SCRIPT_DIR")"
```
to:
```bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
COLLECTOR_DIR="$REPO_ROOT/collector-processor"
```

Then change the Docker volume mount (around line 124) from:
```bash
  -v "${SCRIPT_DIR}/test-config.yaml:/app/config.yaml:ro" \
```
to:
```bash
  -v "$REPO_ROOT/collector-processor/integration_test/test-config.yaml:/app/config.yaml:ro" \
```

- [ ] **Step 5: Verify**

```bash
cd mobile-otel
bash -n scripts/test/monkey-test.sh && echo "OK: monkey" || echo "FAIL"
bash -n scripts/test/integration-test.sh && echo "OK: integration" || echo "FAIL"
bash -n examples/demo-app/monkey-test.sh && echo "OK: demo-app forwarder" || echo "FAIL"
bash -n examples/demo-app-starter/monkey-test.sh && echo "OK: starter forwarder" || echo "FAIL"
```

- [ ] **Step 6: Commit**

```bash
cd mobile-otel
git add scripts/test/ examples/demo-app/monkey-test.sh examples/demo-app-starter/monkey-test.sh
git commit -m "chore: consolidate test scripts into scripts/test/

Consolidate identical monkey-test.sh into single canonical copy.
Move integration_test.sh with fixed COLLECTOR_DIR and test-config
path. Both example app locations become forwarders."
```

---

### Task 6: Move setup script and fix paths

**Files:**
- Move: `mobile-otel/verify-setup.sh` -> `mobile-otel/scripts/setup/verify-setup.sh`
- Modify: `scripts/setup/verify-setup.sh` (add REPO_ROOT, fix CWD anchoring)

- [ ] **Step 1: Move the script**

```bash
cd mobile-otel
git mv verify-setup.sh scripts/setup/
```

- [ ] **Step 2: Fix path computations**

In `scripts/setup/verify-setup.sh`, change lines 8-9 from:
```bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
```
to:
```bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"
```

Then replace all `cd "$SCRIPT_DIR"` occurrences (lines ~164 and ~196) with `cd "$REPO_ROOT"`.

- [ ] **Step 3: Create root forwarder**

`mobile-otel/verify-setup.sh`:
```bash
#!/usr/bin/env bash
"$(dirname "$0")/scripts/setup/verify-setup.sh" "$@"
```

```bash
chmod +x mobile-otel/verify-setup.sh
```

- [ ] **Step 4: Verify**

```bash
cd mobile-otel
bash -n scripts/setup/verify-setup.sh && echo "OK" || echo "FAIL"
```

- [ ] **Step 5: Commit**

```bash
cd mobile-otel
git add scripts/setup/ verify-setup.sh
git commit -m "chore: move verify-setup.sh to scripts/setup/

Add REPO_ROOT anchoring so CWD is set to repo root instead of
SCRIPT_DIR. Root forwarder preserves ./verify-setup.sh invocation."
```

---

### Task 7: Move control-plane scripts and fix CWD

**Files:**
- Create dirs: `mobile-otel-control-plane/scripts/{gateway,deploy}`
- Move: `mobile-otel-control-plane/gateway/build.sh` -> `mobile-otel-control-plane/scripts/gateway/build.sh`
- Move: `mobile-otel-control-plane/gateway/verify.sh` -> `mobile-otel-control-plane/scripts/gateway/verify.sh`
- Move: `mobile-otel-control-plane/k8s/deploy-native.sh` -> `mobile-otel-control-plane/scripts/deploy/deploy-native.sh`
- Modify: all 3 moved scripts (add `cd` to original directory)

- [ ] **Step 1: Create directories**

```bash
cd mobile-otel-control-plane
mkdir -p scripts/{gateway,deploy}
```

- [ ] **Step 2: Move all 3 scripts**

```bash
cd mobile-otel-control-plane
git mv gateway/build.sh scripts/gateway/
git mv gateway/verify.sh scripts/gateway/
git mv k8s/deploy-native.sh scripts/deploy/
```

- [ ] **Step 3: Add CWD fix to build.sh**

In `scripts/gateway/build.sh`, add after `set -e` (line 2):

```bash
cd "$(dirname "$0")/../../gateway"
```

- [ ] **Step 4: Add CWD fix to verify.sh**

In `scripts/gateway/verify.sh`, add after `set -e` (line 2):

```bash
cd "$(dirname "$0")/../../gateway"
```

- [ ] **Step 5: Add CWD fix to deploy-native.sh**

In `scripts/deploy/deploy-native.sh`, add after `set -e` (line 4):

```bash
cd "$(dirname "$0")/../../k8s"
```

- [ ] **Step 6: Create forwarders at original locations**

`gateway/build.sh`:
```bash
#!/usr/bin/env bash
"$(dirname "$0")/../scripts/gateway/build.sh" "$@"
```

`gateway/verify.sh`:
```bash
#!/usr/bin/env bash
"$(dirname "$0")/../scripts/gateway/verify.sh" "$@"
```

`k8s/deploy-native.sh`:
```bash
#!/usr/bin/env bash
"$(dirname "$0")/../scripts/deploy/deploy-native.sh" "$@"
```

```bash
chmod +x gateway/build.sh gateway/verify.sh k8s/deploy-native.sh
```

- [ ] **Step 7: Verify**

```bash
cd mobile-otel-control-plane
bash -n scripts/gateway/build.sh && echo "OK: build" || echo "FAIL"
bash -n scripts/gateway/verify.sh && echo "OK: verify" || echo "FAIL"
bash -n scripts/deploy/deploy-native.sh && echo "OK: deploy" || echo "FAIL"
```

- [ ] **Step 8: Commit**

```bash
cd mobile-otel-control-plane
git add scripts/ gateway/build.sh gateway/verify.sh k8s/deploy-native.sh
git commit -m "chore: move scripts to scripts/{gateway,deploy}/ with forwarders

Move build.sh, verify.sh, deploy-native.sh into categorized
scripts/ tree. Add cd to original directory at script top so
CWD-dependent commands (go, kubectl, docker) continue to work.
Forwarders at original locations preserve existing invocations."
```

---

### Task 8: Update documentation

**Files:**
- Modify: `mobile-otel/CLAUDE.md`
- Modify: `mobile-otel/HOW_TO_DEMO.md`
- Modify: `CLAUDE.md` (workspace root)

- [ ] **Step 1: Add scripts directory note to mobile-otel/CLAUDE.md**

In the "Cross-Project" section of `mobile-otel/CLAUDE.md` (around line 158), add a note after the `./run-tests.sh` commands:

```markdown
> **Note:** All scripts live canonically in `scripts/` (organized into `demo/`, `ci/`, `e2e/`, `test/`, `setup/`, `lib/`). The root-level `./run-*.sh` and `./verify-setup.sh` are thin forwarders that invoke the real scripts -- both invocation styles work.
```

- [ ] **Step 2: Add scripts directory note to HOW_TO_DEMO.md**

In `mobile-otel/HOW_TO_DEMO.md`, after the quick reference table (around line 20), add:

```markdown
> Scripts live in `scripts/demo/`, `scripts/ci/`, etc. The root-level `./run-*.sh` commands are forwarders -- both paths work.
```

- [ ] **Step 3: Add scripts note to workspace CLAUDE.md**

In the workspace root `CLAUDE.md`, in the "Quick Reference" section, add a similar note that scripts are organized under `scripts/` in each repo.

- [ ] **Step 4: Commit**

```bash
git add mobile-otel/CLAUDE.md mobile-otel/HOW_TO_DEMO.md CLAUDE.md
git commit -m "docs: document scripts/ directory organization

Add notes to CLAUDE.md and HOW_TO_DEMO.md explaining the
scripts/ tree structure and that root-level scripts are forwarders."
```

---

### Task 9: Final smoke test

- [ ] **Step 1: Verify all canonical scripts parse cleanly**

```bash
cd mobile-otel
echo "=== All canonical scripts syntax check ==="
for f in scripts/lib/demo-common.sh scripts/demo/*.sh scripts/ci/*.sh scripts/e2e/*.sh scripts/test/*.sh scripts/setup/*.sh; do
  bash -n "$f" && echo "  OK: $f" || echo "  FAIL: $f"
done
```

Expected: all OK.

- [ ] **Step 2: Verify forwarder --help passes through**

```bash
cd mobile-otel
./run-tests.sh --help
./run-dash0-scenarios.sh --help
./run-demo-single.sh --list
```

All should produce their original help text, confirming the forwarders resolve correctly.

- [ ] **Step 3: Verify control-plane scripts parse cleanly**

```bash
cd mobile-otel-control-plane
for f in scripts/gateway/build.sh scripts/gateway/verify.sh scripts/deploy/deploy-native.sh; do
  bash -n "$f" && echo "OK: $f" || echo "FAIL: $f"
done
```

- [ ] **Step 4: Verify example-app forwarders**

```bash
cd mobile-otel
bash -n examples/demo-app/monkey-test.sh && echo "OK" || echo "FAIL"
bash -n examples/demo-app-starter/monkey-test.sh && echo "OK" || echo "FAIL"
```
