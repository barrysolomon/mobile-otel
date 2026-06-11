# scripts/

Canonical home for all automation in this repo. Bash 3.2 compatible (works on stock macOS).

## Layout

| Folder | What's inside |
|--------|---------------|
| [`ci/`](ci/) | `run-tests.sh`, `run-demo-ci.sh` — drivers used by GitHub Actions (and locally) |
| [`demo/`](demo/) | `run-demo-{full,quick,scenarios,single,backend}.sh`, `run-dash0-scenarios.sh`, `run-dual-platform-demo.sh`, `demo-control-center-ios.sh` |
| [`test/`](test/) | Unit/integration/E2E runners, the `validate-us*` UAT family (~28 scenarios), `demo-control-center.sh` (interactive crash menu), `uat/` orchestrator |
| [`e2e/`](e2e/) | `run-e2e.sh` plus end-to-end orchestration helpers, and the **Dash0 receipt gate** (`verify-dash0.sh` + `dash0_assert.py`) — asserts telemetry actually arrived in Dash0 (REST, no `dash0` CLI needed) so tests are green only when data lands |
| [`setup/`](setup/) | `verify-setup.sh` — sanity-check your machine has the right toolchains |
| [`lib/`](lib/) | Shared bash helpers sourced by the runners |
| [`test/lib/`](test/lib/) and [`test/lib-ios/`](test/lib-ios/) | Shared test helpers, split per platform |
| [`test/collector/`](test/collector/) | Local OTEL Collector configs used by integration tests |

## Common entry points

```bash
# Sanity-check your toolchains
./scripts/setup/verify-setup.sh

# Quick demo, one emulator (~5 min)
./scripts/demo/run-demo-quick.sh

# Full demo, two emulators + all scenarios (~12 min)
./scripts/demo/run-demo-full.sh

# Headless CI run
./scripts/ci/run-demo-ci.sh

# All tests (Android + Go)
./scripts/ci/run-tests.sh
./scripts/ci/run-tests.sh --all     # + iOS + RN

# Single UAT scenario
./scripts/test/validate-us063-crash-flush.sh

# Interactive crash + airplane demo
./scripts/test/demo-control-center.sh
```

See [HOW_TO_DEMO.md](../HOW_TO_DEMO.md) for the full demo runbook and [docs/TESTING_GUIDE.md](../docs/TESTING_GUIDE.md) for the test reference.

## Conventions

- Every script accepts `--help`
- Every script is idempotent — safe to re-run after partial failure
- Scripts that boot emulators clean up on exit (trap-on-INT/EXIT)
- Bash 3.2 compatible: no associative arrays, no `[[ =~ ]]` regex captures beyond `BASH_REMATCH`
