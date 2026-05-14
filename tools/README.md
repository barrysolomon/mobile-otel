# tools/

Developer-facing utilities that are not part of the shipping SDK.

## Contents

| Tool | Purpose |
|------|---------|
| [`dcc-tui/`](dcc-tui/) | Demo Control Center — Ink + React TUI for driving scenarios across multiple devices in parallel. Companion to `scripts/test/demo-control-center.sh`. |

## When to add something here

Anything that helps you build, debug, or demo the SDK but doesn't belong in the SDK itself. Examples that could land here later: a config-DSL validator CLI, a buffer inspector, a span-tree dumper.

Anything that *is* part of the shipping SDK or its tests belongs in `otel-android-mobile/`, `otel-ios-mobile/`, `packages/`, `instrumentation/`, or `scripts/`.
