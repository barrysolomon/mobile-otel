# Publish-Readiness Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Bring the mobile-otel repository to a publishable state by fixing license headers, credentials, linting, documentation, and test coverage.

**Architecture:** Purely maintenance work — no functional changes. Each task is independent and safe to do in any order. The most risk is in Task 2 (credentials) which requires gitignore changes.

**Tech Stack:** Kotlin, Gradle, ktlint, git, bash scripting

---

### Task 1: Add license headers to all Kotlin source files missing them

**Files:**
- Modify: all `.kt` files listed below (batch with a shell script)

**Background:** The Apache-2.0 header is required for OSS publication. Files that already have it should be skipped. The header is exactly:

```
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
```

**Step 1: Identify all Kotlin files missing the header**

Run:
```bash
cd /Users/barrysolomon/Projects/Dash0/mobile-otel
grep -rL "SPDX-License-Identifier" --include="*.kt" .
```

Expected: ~41 files printed. Record the list.

**Step 2: Write a script to prepend the header**

Create `/tmp/add_license.sh`:
```bash
#!/bin/bash
HEADER="// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
"
for f in "$@"; do
  # Skip if already present
  if grep -q "SPDX-License-Identifier" "$f"; then
    continue
  fi
  tmpfile=$(mktemp)
  echo "$HEADER" > "$tmpfile"
  cat "$f" >> "$tmpfile"
  mv "$tmpfile" "$f"
  echo "Patched: $f"
done
```

**Step 3: Run the script on all missing files**

```bash
chmod +x /tmp/add_license.sh
grep -rL "SPDX-License-Identifier" --include="*.kt" . | xargs /tmp/add_license.sh
```

**Step 4: Verify no files are missing the header**

Run:
```bash
grep -rL "SPDX-License-Identifier" --include="*.kt" .
```
Expected: no output (empty list).

**Step 5: Spot-check a few files**

Open 3–4 files from different modules and confirm the header appears at line 1–2.

**Step 6: Commit**

```bash
git add -A
git commit -m "chore: add Apache-2.0 license headers to all Kotlin source files"
```

---

### Task 2: Fix committed credentials in otel-config.json

**Files:**
- Modify: `examples/demo-app/android/src/debug/assets/otel-config.json`
- Modify: `.gitignore`

**Background:** `otel-config.json` contains a real Dash0 auth token `YOUR_AUTH_TOKEN_HERE`. The file is tracked in git. The `release/` variant is ignored but `debug/` is not. We must:
1. Add a committed `otel-config.json.template` with placeholder values
2. Add `debug/assets/otel-config.json` to `.gitignore`
3. Update the CLAUDE.md and ANDROID_SDK_GUIDE.md to say "copy otel-config.json.template → otel-config.json and fill in your credentials"

**Step 1: Create the template file**

Create `examples/demo-app/android/src/debug/assets/otel-config.json.template`:
```json
{
  "endpoint": "https://your-collector-endpoint:4317",
  "auth_token": "YOUR_AUTH_TOKEN_HERE",
  "dataset": "your-dataset-name"
}
```

(Read the actual `otel-config.json` first to match its exact JSON structure.)

**Step 2: Redact the real credentials file**

Replace `examples/demo-app/android/src/debug/assets/otel-config.json` with the placeholder version (same as template):
```json
{
  "endpoint": "https://your-collector-endpoint:4317",
  "auth_token": "YOUR_AUTH_TOKEN_HERE",
  "dataset": "your-dataset-name"
}
```

**Step 3: Add debug otel-config.json to .gitignore**

In `.gitignore`, after the existing `release/` entry, add:
```
# Local developer credentials — copy otel-config.json.template and fill in yours
examples/demo-app/android/src/debug/assets/otel-config.json
```

**Step 4: Update CLAUDE.md credentials section**

In CLAUDE.md, replace the `## Dash0 Credentials` section's auth token line with:
```
- Auth token: see examples/demo-app/android/src/debug/assets/otel-config.json.template (not committed)
```

**Step 5: Update docs/ANDROID_SDK_GUIDE.md**

Search for any mention of credentials/auth token setup and add a note:
```
Copy `otel-config.json.template` to `otel-config.json` and replace the placeholder values with your Dash0 endpoint and auth token.
```

**Step 6: Verify .gitignore works**

Run:
```bash
git status
```
Expected: `otel-config.json` should appear as untracked (not staged), `otel-config.json.template` as new file.

**Step 7: Commit**

```bash
git add examples/demo-app/android/src/debug/assets/otel-config.json.template .gitignore docs/ANDROID_SDK_GUIDE.md CLAUDE.md
git commit -m "security: redact committed credentials; add otel-config.json.template

The debug otel-config.json contained a real Dash0 auth token.
Added .template file with placeholder values.
Added debug/assets/otel-config.json to .gitignore."
```

---

### Task 3: Configure ktlint and run a clean pass

**Files:**
- Modify: `otel-android-mobile-core/build.gradle.kts` (or root `build.gradle.kts`)
- Modify: various `.kt` files (auto-fixed by ktlint)

**Background:** ktlint enforces the Kotlin coding style (same as Android/JetBrains standard). It can auto-fix most issues. The goal is to add it as a Gradle task so CI can enforce it.

**Step 1: Check current Gradle configuration**

Read `examples/demo-app/build.gradle.kts` and the root `build.gradle.kts` to understand the existing plugin setup.

**Step 2: Add ktlint plugin**

In the root `build.gradle.kts` (or each module's `build.gradle.kts`), add:
```kotlin
plugins {
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0"
}

ktlint {
    version.set("1.3.1")
    android.set(true)
    ignoreFailures.set(false)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
    }
}
```

**Step 3: Run ktlint auto-format**

```bash
cd examples/demo-app
./gradlew ktlintFormat
```

Expected: ktlint fixes formatting in-place. Some manual fixes may be needed for issues it cannot auto-fix.

**Step 4: Run ktlint check**

```bash
./gradlew ktlintCheck
```

Expected: passes with zero errors/warnings.

**Step 5: Commit**

```bash
git add -A
git commit -m "chore: add ktlint plugin and run clean formatting pass"
```

---

### Task 4: Update CLAUDE.md — remove outdated DiskLogBuffer stub note

**Files:**
- Modify: `CLAUDE.md` line 186

**Background:** CLAUDE.md line 186 says `DiskLogBuffer.toLogRecordData()` is a stub that throws `NotImplementedError`. The audit confirmed this is no longer true — it is fully implemented.

**Step 1: Read CLAUDE.md around line 186**

Look at the `## Known Issues & Gotchas` section.

**Step 2: Remove the outdated entry**

Delete the bullet:
```
- **`DiskLogBuffer.toLogRecordData()` is a stub** — Throws `NotImplementedError`. Disk events cannot be deserialized for export yet. This means `flushWindow()` only returns RAM-buffered events.
```

**Step 3: Update BACKLOG.md**

Search BACKLOG.md for any mention of `DiskLogBuffer` stub and mark/remove it as completed.

**Step 4: Verify the build and test suite still passes**

```bash
cd examples/demo-app
./gradlew :otel-android-mobile-core:test
```

Expected: green.

**Step 5: Commit**

```bash
git add CLAUDE.md BACKLOG.md
git commit -m "docs: remove outdated DiskLogBuffer stub note — fully implemented"
```

---

### Task 5: Write OTEP draft — Mobile Buffering Pattern

**Files:**
- Create: `docs/oteps/OTEP-mobile-buffering-pattern.md`

**Background:** Before upstreaming to opentelemetry-android, we should have OTEP-style design documents. This one covers the dual-tier ring buffer (RAM + SQLite), selective flush, and export policies. OTEPs follow the [OpenTelemetry OTEP template](https://github.com/open-telemetry/oteps/blob/main/0001-telemetry-without-a-pipeline.md).

**Step 1: Create the OTEP document**

```markdown
# OTEP: Mobile Buffering Pattern for OpenTelemetry Android

**Status:** Draft
**Authors:** [your name]
**Created:** 2026-03-09

## Motivation
Mobile apps face unique observability challenges: intermittent connectivity, battery constraints, and limited storage. The standard OTLP batch processor does not account for these constraints.

## Proposal
Introduce a dual-tier buffer architecture for mobile OTel SDKs:
- **Tier 1 (RAM):** ConcurrentLinkedQueue with configurable capacity (default 5000 events). Low-latency writes, volatile across process death.
- **Tier 2 (Disk):** SQLite/Room-backed durable buffer with configurable capacity (default 50MB) and TTL (default 24h). Survives process death and device restart.

### Selective Flush
`flushWindow(minutes: Int)` exports a time-windowed slice of the buffer. Enables pre-emptive export before anticipated connectivity loss.

### Export Policies
A DSL-based policy engine evaluates trigger conditions in real time. Policies specify when to flush (e.g., "on error", "on connectivity change", "when crash risk > 0.7").

## Open Questions
- Should the disk buffer be standardized as part of the OTel Android contrib spec?
- Should `flushWindow` be part of the public `OpenTelemetry` interface?

## Alternatives Considered
- Periodic export only (does not handle connectivity loss gracefully)
- Always-on export (high battery/data usage)
```

**Step 2: Commit**

```bash
git add docs/oteps/OTEP-mobile-buffering-pattern.md
git commit -m "docs: add OTEP draft for mobile dual-tier buffering pattern"
```

---

### Task 6: Write OTEP draft — Conditional Export for Mobile

**Files:**
- Create: `docs/oteps/OTEP-conditional-export-mobile.md`

**Step 1: Create the document** (similar structure to Task 5, focused on the policy DSL and conditional/hybrid export modes)

**Step 2: Commit**

```bash
git add docs/oteps/OTEP-conditional-export-mobile.md
git commit -m "docs: add OTEP draft for conditional export for mobile"
```

---

### Task 7: Android integration tests — end-to-end buffer flow

**Files:**
- Create or extend: `otel-android-mobile-core/src/androidTest/java/io/opentelemetry/android/mobile/BufferIntegrationTest.kt`

**Background:** There are unit tests but no end-to-end instrumented tests exercising the full path: emit log → RAM buffer → disk buffer → flush window → mock exporter. This requires a real Android process (Robolectric cannot simulate Room/SQLite reliably for this use case).

**Step 1: Read existing test structure**

Read any existing instrumented test in `src/androidTest/` to understand the test runner setup.

**Step 2: Write a failing test**

```kotlin
@RunWith(AndroidJUnit4::class)
class BufferIntegrationTest {
    @Test
    fun emittedLogAppearsInFlushWindow() {
        // Arrange: build SDK with in-memory exporter
        // Act: emit a log record
        // Act: call flushWindow(1)
        // Assert: exporter received exactly 1 record
        TODO("implement")
    }
}
```

**Step 3: Run to confirm it fails (compilation expected)**

```bash
cd examples/demo-app
./gradlew :otel-android-mobile-core:connectedDebugAndroidTest
```

**Step 4: Implement**

Fill in the test body using `InMemoryLogRecordExporter` from the OTel testing library.

**Step 5: Run again to confirm it passes**

```bash
./gradlew :otel-android-mobile-core:connectedDebugAndroidTest
```

Expected: PASS.

**Step 6: Commit**

```bash
git add otel-android-mobile-core/src/androidTest/
git commit -m "test: add integration test for end-to-end buffer flow"
```

---

## Post-Tasks

After all tasks are complete:
1. Run full test suite: `./run-tests.sh`
2. Final review: `git log --oneline -10`
3. Merge to main via PR or direct merge depending on team preference
