# Cross-platform contracts

This directory documents the SDK invariants that must hold identically across Android, iOS, React Native, and the Go collector processor. Each document names the concept, the public surface, the platforms that implement it, and the verbatim file:line references where the implementation lives.

These are not aspirational specs — they describe the code as it is today, on `main`. When you change one platform's implementation of a contract, update the same line refs here in the same commit. CI will flag any contract doc whose referenced files moved without the doc updating (see `scripts/ci/check-contract-drift.sh`).

## Index

1. [DSL matchers](dsl-matchers.md) — the 21 matcher types parsed from policy DSL v2, what attributes each one reads.
2. [Condition operators](dsl-conditions.md) — the 8 operators (`equals`, `gt`, `regex`, …) that conditions support.
3. [Action types](dsl-actions.md) — what a matched policy can do; today, only `flushWindowMinutes`.
4. [Default seeded policies](default-policies.md) — what fires before a remote config lands.
5. [Error coalescer](error-coalescer.md) — how the per-platform coalescer keys duplicate events.
6. [Offline policy](offline-policy.md) — what gets buffered when the device is offline.
7. [Network-restored flush](network-restored-flush.md) — what fires on LOST→AVAILABLE transitions.
8. [Buffer drain surface](buffer-drain-surface.md) — every public method that drains the buffer, what it drains, what it returns.

## Why these exist

Memory entry `feedback_no_platform_drift` is the discipline. These docs are the artifact that makes the discipline machine-checkable. Before this directory existed, drift was caught at code review or — worse — in production. With them, drift is caught when CI sees a contract-referenced file change without the contract doc updating in the same commit.

When in doubt, prefer adding a new contract doc over letting an invariant live only in a code comment.
