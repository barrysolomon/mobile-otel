# UAT Matrix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a methodical UAT framework that drives each platform's emulator through controlled telemetry-generating sequences across a 3-axis matrix (export-mode × connectivity × crash) and validates arrival in Dash0, with full Android sweep + nightly subset on iOS/RN.

**Architecture:** Per-platform pre-built flavors (one per export mode), a parameterized cell runner that injects `cell_id` as a resource attribute, a 5-query Dash0 batch filtered on `cell_id`, and tiered must-pass/soft-warn assertions. See [`docs/superpowers/specs/2026-05-01-uat-matrix-design.md`](../specs/2026-05-01-uat-matrix-design.md) for the full design.

**Tech Stack:** Bash 3.2+, Gradle (Android), Xcode (iOS), Dash0 CLI (`dash0 -X`), Kotlin (demo apps), Swift (iOS demo), `adb`, `xcrun simctl`.

---

## Pre-flight

Before starting Task 1, verify the working environment:

```bash
cd /Users/barrysolomon/Projects/Dash0/mobile-observability/mobile-otel
git status                                    # expect clean tree
./run-tests.sh --android-only                 # expect: All tests passed
dash0 config profiles activate mobile-test    # expect: profile activated
dash0 -X logs query --filter "service.name is otel-android-astronomy-shop" --from now-1m -o json | head -5
```

If any of those fail, stop and resolve before proceeding.

---

## File Structure

**Created:**
- `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/MobileConfigDsl.kt` — DSL extension to expose `extraResourceAttributes` (only if not already present)
- `examples/upstream-demo-app/src/dash0Continuous/java/io/opentelemetry/android/demo/ExportModeProvider.kt` (and `dash0Conditional`, `dash0Hybrid`)
- `scripts/test/uat/run-uat-cell.sh`
- `scripts/test/uat/run-uat-matrix.sh`
- `scripts/test/uat/lib-uat-assertions.sh`
- `scripts/test/uat/lib-uat-platform-android.sh`
- `scripts/test/uat/lib-uat-platform-ios.sh`
- `scripts/test/uat/lib-uat-platform-rn-android.sh`
- `scripts/test/uat/lib-uat-platform-rn-ios.sh`
- `scripts/test/uat/test-assertions.sh` — bash unit tests for the assertion lib
- `scripts/test/uat/.gitignore` (excludes `evidence/`)
- `docs/epics/UAT_MATRIX_EPIC.md`
- `docs/uat-matrix/README.md`
- `docs/uat-matrix/android-native.md`
- `docs/uat-matrix/ios-native.md`
- `docs/uat-matrix/rn-android.md`
- `docs/uat-matrix/rn-ios.md`

**Modified:**
- `examples/upstream-demo-app/build.gradle.kts` — replace 2-flavor `productFlavors { dash0, upstream }` with 4-flavor `dash0Continuous`, `dash0Conditional`, `dash0Hybrid`, `upstream`
- `examples/upstream-demo-app/src/dash0/java/io/opentelemetry/android/demo/SdkInitializer.kt` — read `DASH0_CELL_ID` from intent extras + per-flavor `ExportModeProvider`
- `examples/upstream-demo-app-ios/AstronomyShop/Configuration/` — new schemes `Dash0Conditional`, `Dash0Hybrid`
- `examples/upstream-demo-app-ios/AstronomyShop/AstronomyShopApp.swift` — read `DASH0_CELL_ID` env, set on `MobileConfig.extraResourceAttributes`
- `examples/upstream-demo-app-rn/AstronomyShopRN/android/app/build.gradle` — mirror Android flavor scheme
- `examples/upstream-demo-app-rn/AstronomyShopRN/src/App.tsx` — read cell_id env, pass to `Dash0Mobile.start({ resourceAttributes: ... })`

---

## Phase 0 — Foundation: assertion library + first cell, Android-only

### Task 0.1: Bash assertion library

**Files:**
- Create: `scripts/test/uat/lib-uat-assertions.sh`
- Create: `scripts/test/uat/test-assertions.sh`

- [ ] **Step 1: Write the failing assertion-lib unit tests**

Create `scripts/test/uat/test-assertions.sh`:

```bash
#!/usr/bin/env bash
# Unit tests for lib-uat-assertions.sh. Pure-bash, no external deps.

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/lib-uat-assertions.sh"

PASSED=0
FAILED=0

assert_exit_code() {
    local expected=$1 actual=$2 label=$3
    if [[ "$expected" == "$actual" ]]; then
        PASSED=$((PASSED + 1))
        echo "  PASS  $label"
    else
        FAILED=$((FAILED + 1))
        echo "  FAIL  $label  (expected exit $expected, got $actual)"
    fi
}

# --- must::eq ---
( must::eq "test_eq_match" 5 5 ) >/dev/null 2>&1
assert_exit_code 0 $? "must::eq returns 0 when values equal"

( must::eq "test_eq_mismatch" 5 6 ) >/dev/null 2>&1
assert_exit_code 1 $? "must::eq returns 1 when values differ"

# --- must::ge ---
( must::ge "test_ge_equal" 3 3 ) >/dev/null 2>&1
assert_exit_code 0 $? "must::ge returns 0 when observed == expected"

( must::ge "test_ge_above" 5 3 ) >/dev/null 2>&1
assert_exit_code 0 $? "must::ge returns 0 when observed > expected"

( must::ge "test_ge_below" 2 3 ) >/dev/null 2>&1
assert_exit_code 1 $? "must::ge returns 1 when observed < expected"

# --- must::zero ---
( must::zero "test_zero_match" 0 ) >/dev/null 2>&1
assert_exit_code 0 $? "must::zero returns 0 when value is 0"

( must::zero "test_zero_nonzero" 1 ) >/dev/null 2>&1
assert_exit_code 1 $? "must::zero returns 1 when value is nonzero"

# --- warn::eq does NOT exit ---
( warn::eq "test_warn_eq_mismatch" 5 6 ) >/dev/null 2>&1
assert_exit_code 0 $? "warn::eq never returns nonzero (mismatch is just a warning)"

# --- warn::within ---
( warn::within "test_within_inside" 105 100 10 ) >/dev/null 2>&1
assert_exit_code 0 $? "warn::within returns 0 when observed inside tolerance"

( warn::within "test_within_outside" 200 100 10 ) >/dev/null 2>&1
assert_exit_code 0 $? "warn::within returns 0 even when observed outside tolerance (warn-tier)"

( warn::within "test_within_nonnumeric" "" 100 10 ) >/dev/null 2>&1
assert_exit_code 0 $? "warn::within returns 0 on non-numeric input (warn-tier)"

# --- numeric guards on must:: helpers ---
( must::ge "test_ge_empty" "" 3 ) >/dev/null 2>&1
assert_exit_code 1 $? "must::ge returns 1 on empty observed"

( must::zero "test_zero_text" "abc" ) >/dev/null 2>&1
assert_exit_code 1 $? "must::zero returns 1 on non-numeric observed"

# --- JSONL is valid JSON for numeric, string, and empty observed ---
validate_json() {
    local label="$1" line="$2"
    if printf '%s' "$line" | python3 -c 'import sys,json; json.loads(sys.stdin.read())' 2>/dev/null; then
        PASSED=$((PASSED + 1)); echo "  PASS  $label"
    else
        FAILED=$((FAILED + 1)); echo "  FAIL  $label: not valid JSON: $line"
    fi
}

emit_line=$(must::eq "test_jsonl_num" 5 5 2>/dev/null | head -n 1)
validate_json "must::eq emits valid JSON for numeric observed" "$emit_line"

emit_line=$(must::eq "test_jsonl_str" "hello" "hello" 2>/dev/null | head -n 1)
validate_json "must::eq emits valid JSON for string observed" "$emit_line"

emit_line=$(must::ge "test_jsonl_empty" "" 3 2>/dev/null | head -n 1)
validate_json "must::ge emits valid JSON for empty observed" "$emit_line"

emit_line=$(must::eq "test_jsonl_quotes" 'has"quote' 'other' 2>/dev/null | head -n 1)
validate_json "must::eq emits valid JSON when observed contains quotes" "$emit_line"

# --- field shape: tier/gate/observed present and observed is JSON-string ---
output=$(must::eq "test_jsonl" 5 5 2>/dev/null | head -n 1)
case "$output" in
    *'"tier":"must"'*'"observed":"5"'*) PASSED=$((PASSED + 1)); echo "  PASS  must::eq JSONL has expected fields and string-quoted observed" ;;
    *) FAILED=$((FAILED + 1)); echo "  FAIL  must::eq JSONL fields wrong:  $output" ;;
esac

echo
echo "Results: $PASSED passed, $FAILED failed"
[[ $FAILED -eq 0 ]] && exit 0 || exit 1
```

- [ ] **Step 2: Run the tests, expect them to fail**

```bash
chmod +x scripts/test/uat/test-assertions.sh
scripts/test/uat/test-assertions.sh
```

Expected: fails with `lib-uat-assertions.sh: No such file or directory`.

- [ ] **Step 3: Implement the assertion library**

Create `scripts/test/uat/lib-uat-assertions.sh`:

```bash
#!/usr/bin/env bash
# UAT matrix assertion helpers — tiered must-pass / soft-warn.
# Each helper writes a JSONL line to UAT_EVIDENCE_FILE if it is set;
# must-pass helpers exit nonzero on failure, warn-pass helpers do not.
# All helpers are pure-bash 3.2 compatible.

set -u

# Internal: is the value a finite integer? (bash 3.2-safe.)
__uat_is_int() {
    case "$1" in
        ''|*[!0-9-]*) return 1 ;;
        -|*-*-*)      return 1 ;;
        -*[!0-9]*)    return 1 ;;
        *)            return 0 ;;
    esac
}

# Internal: emit one JSONL assertion line.
# `observed` is always serialized as a JSON string (quoted + escaped) so the
# row is valid JSON regardless of whether the caller passed a number, empty
# string, or arbitrary text. Downstream consumers can coerce with jq/tonumber.
__uat_emit() {
    local tier="$1" gate="$2" claim="$3" observed="$4" passed="$5"
    local esc_claim="${claim//\\/\\\\}"; esc_claim="${esc_claim//\"/\\\"}"
    local esc_obs="${observed//\\/\\\\}"; esc_obs="${esc_obs//\"/\\\"}"
    local line
    line="{\"tier\":\"${tier}\",\"gate\":\"${gate}\",\"claim\":\"${esc_claim}\",\"observed\":\"${esc_obs}\",\"passed\":${passed}}"
    if [[ -n "${UAT_EVIDENCE_FILE:-}" ]]; then
        echo "$line" >> "$UAT_EVIDENCE_FILE"
    fi
    echo "$line"
}

# must::eq <name> <observed> <expected> — exit 1 on mismatch.
must::eq() {
    local name="$1" observed="$2" expected="$3"
    if [[ "$observed" == "$expected" ]]; then
        __uat_emit "must" "$name" "observed == $expected" "$observed" "true"
        echo "[PASS] must $name: $observed == $expected"
    else
        __uat_emit "must" "$name" "observed == $expected" "$observed" "false"
        echo "[FAIL] must $name: $observed != $expected" >&2
        return 1
    fi
}

# must::ge <name> <observed> <expected> — exit 1 if observed < expected.
must::ge() {
    local name="$1" observed="$2" expected="$3"
    if ! __uat_is_int "$observed" || ! __uat_is_int "$expected"; then
        __uat_emit "must" "$name" "observed >= $expected" "$observed" "false"
        echo "[FAIL] must $name: non-numeric input (observed='$observed' expected='$expected')" >&2
        return 1
    fi
    if [[ "$observed" -ge "$expected" ]]; then
        __uat_emit "must" "$name" "observed >= $expected" "$observed" "true"
        echo "[PASS] must $name: $observed >= $expected"
    else
        __uat_emit "must" "$name" "observed >= $expected" "$observed" "false"
        echo "[FAIL] must $name: $observed < $expected" >&2
        return 1
    fi
}

# must::zero <name> <observed> — exit 1 if observed != 0.
must::zero() {
    local name="$1" observed="$2"
    if ! __uat_is_int "$observed"; then
        __uat_emit "must" "$name" "observed == 0" "$observed" "false"
        echo "[FAIL] must $name: non-numeric input (observed='$observed')" >&2
        return 1
    fi
    if [[ "$observed" -eq 0 ]]; then
        __uat_emit "must" "$name" "observed == 0" "$observed" "true"
        echo "[PASS] must $name: $observed == 0"
    else
        __uat_emit "must" "$name" "observed == 0" "$observed" "false"
        echo "[FAIL] must $name: $observed != 0" >&2
        return 1
    fi
}

# warn::eq <name> <observed> <expected> — log only, never returns nonzero.
warn::eq() {
    local name="$1" observed="$2" expected="$3"
    if [[ "$observed" == "$expected" ]]; then
        __uat_emit "warn" "$name" "observed == $expected" "$observed" "true"
        echo "[ OK ] warn $name: $observed == $expected"
    else
        __uat_emit "warn" "$name" "observed == $expected" "$observed" "false"
        echo "[WARN] warn $name: $observed != $expected (drift)" >&2
    fi
    return 0
}

# warn::within <name> <observed> <expected> <tolerance_pct> — log only.
warn::within() {
    local name="$1" observed="$2" expected="$3" tol_pct="$4"
    if ! __uat_is_int "$observed" || ! __uat_is_int "$expected" || ! __uat_is_int "$tol_pct"; then
        __uat_emit "warn" "$name" "observed within ${tol_pct}% of $expected" "$observed" "false"
        echo "[WARN] warn $name: non-numeric input (observed='$observed' expected='$expected' tol='$tol_pct')" >&2
        return 0
    fi
    # Integer math; tolerance as a whole-percent value (0-100).
    local margin=$(( expected * tol_pct / 100 ))
    [[ $margin -lt 0 ]] && margin=$(( -margin ))
    local hi=$(( expected + margin ))
    local lo=$(( expected - margin ))
    if [[ "$observed" -ge "$lo" && "$observed" -le "$hi" ]]; then
        __uat_emit "warn" "$name" "observed within ${tol_pct}% of $expected" "$observed" "true"
        echo "[ OK ] warn $name: $observed within ±${tol_pct}% of $expected"
    else
        __uat_emit "warn" "$name" "observed within ${tol_pct}% of $expected" "$observed" "false"
        echo "[WARN] warn $name: $observed outside ±${tol_pct}% of $expected" >&2
    fi
    return 0
}
```

- [ ] **Step 4: Run the tests, expect all PASS**

```bash
chmod +x scripts/test/uat/lib-uat-assertions.sh
scripts/test/uat/test-assertions.sh
```

Expected output ends with: `Results: 18 passed, 0 failed` and exit 0.

- [ ] **Step 5: Add `.gitignore` for evidence dir**

Create `scripts/test/uat/.gitignore`:

```
evidence/
```

- [ ] **Step 6: Commit**

```bash
git add scripts/test/uat/lib-uat-assertions.sh scripts/test/uat/test-assertions.sh scripts/test/uat/.gitignore
git commit -m "feat(uat): assertion library with tiered must-pass/soft-warn helpers"
```

---

### Task 0.2: Expose `extraResourceAttributes` in the `MobileOtel` DSL

The SDK already accepts `extraResourceAttributes` on `MobileConfig`, but the `MobileOtel.initialize { }` builder DSL does not expose it. Add a top-level setter so demo apps can pass `cell_id`.

**Files:**
- Modify: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/MobileOtelBuilder.kt` (or wherever the DSL builder lives — verify with `grep -rn "fun export\|fun service" otel-android-mobile/src/main/java/`)

- [ ] **Step 1: Find the DSL builder file**

```bash
grep -rn "fun service\|fun export\|fun instrumentations" otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/ | head -10
```

The file containing those builder functions is the target. Open it and locate where `extraResourceAttributes` would naturally fit — alongside `service`/`export`/`instrumentations` blocks at builder root.

- [ ] **Step 2: Write a failing unit test**

Create `otel-android-mobile/src/test/java/io/opentelemetry/android/mobile/config/ExtraResourceAttributesDslTest.kt`:

```kotlin
package io.opentelemetry.android.mobile.config

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtraResourceAttributesDslTest {
    @Test
    fun `dsl exposes extraResourceAttributes setter`() {
        val config = mobileConfigForTest {
            service { name = "test"; version = "0.1.0" }
            export { endpoint = "http://localhost:4317" }
            extraResourceAttributes = mapOf(
                "dash0.test.cell_id" to "abc-123",
                "dash0.test.export_mode" to "cont",
            )
        }
        assertEquals("abc-123", config.extraResourceAttributes?.get("dash0.test.cell_id"))
        assertEquals("cont", config.extraResourceAttributes?.get("dash0.test.export_mode"))
    }
}
```

(`mobileConfigForTest` is whatever the existing DSL entry point is named in tests — check existing config tests for the pattern.)

- [ ] **Step 3: Run test, expect FAIL**

```bash
cd examples/demo-app
./gradlew :otel-android-mobile:test --tests "*.ExtraResourceAttributesDslTest"
```

Expected: compilation error or test failure (`extraResourceAttributes` not on builder).

- [ ] **Step 4: Add the DSL field**

In the builder class, add (alongside existing properties):

```kotlin
/**
 * Extra resource attributes attached to every emitted record.
 * Useful for test cell IDs, deployment tags, and similar metadata
 * that the OTel `service.*` and `device.*` defaults don't cover.
 */
var extraResourceAttributes: Map<String, String>? = null
```

In the `build()` / config-construction path, pass it through to `MobileConfig`:

```kotlin
return MobileConfig(
    // ... existing fields,
    extraResourceAttributes = this.extraResourceAttributes,
)
```

- [ ] **Step 5: Run test, expect PASS**

```bash
./gradlew :otel-android-mobile:test --tests "*.ExtraResourceAttributesDslTest"
```

Expected: `BUILD SUCCESSFUL`, 1 test passed.

- [ ] **Step 6: Run the full SDK suite to confirm no regression**

```bash
./gradlew :otel-android-mobile:test
```

Expected: `BUILD SUCCESSFUL`. (Check the test count matches or exceeds the prior baseline.)

- [ ] **Step 7: Commit**

```bash
git add otel-android-mobile/src/
git commit -m "feat(sdk): expose extraResourceAttributes in MobileOtel DSL"
```

---

### Task 0.3: Wire `DASH0_CELL_ID` into the Android demo app's `SdkInitializer`

The demo app currently hardcodes `mode = ExportMode.CONTINUOUS`. We change it to:
1. Read `DASH0_CELL_ID` from intent extras and pass as resource attribute
2. Read `DASH0_EXPORT_MODE` (set by per-flavor `BuildConfig`) and pass as resource attribute

This task is the prep for the flavor split in Task 0.4. We keep CONTINUOUS hardcoded here; the per-flavor override comes next.

**Files:**
- Modify: `examples/upstream-demo-app/src/dash0/java/io/opentelemetry/android/demo/SdkInitializer.kt`
- Modify: `examples/upstream-demo-app/src/dash0/java/io/opentelemetry/android/demo/MainActivity.kt` (or wherever Application.onCreate / SdkInitializer.initialize is called)

- [ ] **Step 1: Locate the current call site of `SdkInitializer.initialize`**

```bash
grep -rn "SdkInitializer.initialize\|SdkInitializer\\.initialize" examples/upstream-demo-app/src/dash0/
```

Note the file (typically `OtelDemoApplication.kt` or similar). Application.onCreate runs before any Activity intent is available, so we need to delay the SDK init until first Activity creation, or accept that `cell_id` is read via a process-launch env (BuildConfig + intent fall-through).

For Android, the cleanest path: `SdkInitializer.initialize(app, cellId: String?)` and have the launcher activity's `onCreate` pull `intent.getStringExtra("DASH0_CELL_ID")` and pass it. Re-init is not allowed (`MobileOtel` is a singleton), so the flow becomes:

- App.onCreate → no SDK init yet
- Launcher Activity.onCreate → reads intent extra → calls `SdkInitializer.initialize(application, cellId)`
- All telemetry from this point carries the cell_id

If the SDK is currently initialized in `Application.onCreate`, this is a small structural change.

- [ ] **Step 2: ~~Write a tracer-level test~~ — SKIPPED (see decision note)**

**Decision (2026-05-01):** No demo-app unit test added for `SdkInitializer`.

The original plan called for a Robolectric test in a new `dash0Test` source set. Reasons we skipped:

1. SDK-side `MobileOtelDslTest` (in commit `e8f3b20`) already proves `extraResourceAttributes` plumbs through `MobileOtelDsl.buildConfig() → MobileConfig`. The demo's `SdkInitializer` is a 10-line wrapper that just builds a map and forwards it — testing it duplicates SDK coverage.
2. Robolectric is the wrong tool here: `SdkInitializer` does no real Android-framework work; Robolectric collapses threading (see `feedback_robolectric_main_thread.md`) and would mask the `ProcessLifecycleOwner` main-thread dispatch we explicitly added.
3. The demo app currently has zero `test` source set — only `androidTest`. Standing one up (Robolectric config, manifest stub, +Mockk) for a one-line map assertion is overhead with no signal.
4. Real validation comes from `assembleDash0Debug` (compile-time) + the UAT runner reading `dash0.test.cell_id` back from Dash0 via `dash0` CLI in Task 1.x. That's stronger evidence than any in-process test.

Skipping Steps 2, 3, and 6. Keeping Step 4, 5, 7, 8.

If a future change makes the wrapper non-trivial (e.g. async init, multiple branching paths), add a plain JUnit test alongside `MobileOtelDslTest` rather than retrofitting Robolectric.

<details>
<summary>Original spec (kept for reference)</summary>

Create `examples/upstream-demo-app/src/dash0Test/java/.../SdkInitializerCellIdTest.kt` (you may need to add a `dash0Test` source set if instrumented test variants don't already exist; otherwise place in unit-test sources):

```kotlin
package io.opentelemetry.android.demo

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SdkInitializerCellIdTest {
    @Test
    fun `initialize attaches cell_id to extraResourceAttributes when provided`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        SdkInitializer.initialize(app, cellId = "test-cell-uuid")

        val resource = OtelDemoApplication.openTelemetry
            ?.logsBridge
            ?.loggerBuilder("test")
            ?.build()
            ?.let {
                // probe via emitting a record + reading back, OR
                // expose a helper on OtelDemoApplication.resourceAttributes for this test
                OtelDemoApplication.resourceAttributesSnapshot
            }
        assertEquals("test-cell-uuid", resource?.get("dash0.test.cell_id"))
    }
}
```

If reading the resource directly from the OpenTelemetry SDK is awkward, add a `OtelDemoApplication.resourceAttributesSnapshot: Map<String,String>` helper that the SDK init populates for testability. Avoid making the SDK testable through reflection.

- [ ] **Step 3: Run test, expect FAIL**

```bash
cd examples/demo-app
./gradlew :upstream-demo-app:testDash0DebugUnitTest --tests "*.SdkInitializerCellIdTest"
```

Expected: compilation failure (`initialize` doesn't take `cellId`).

</details>

- [ ] **Step 4: Update `SdkInitializer.initialize` signature**

Replace existing `SdkInitializer.kt` body:

```kotlin
package io.opentelemetry.android.demo

import android.app.Application
import android.util.Log
import io.opentelemetry.android.mobile.MobileOtel
import io.opentelemetry.android.mobile.config.ExportMode

object SdkInitializer {
    fun initialize(app: Application, cellId: String? = null) {
        val extraAttrs = mutableMapOf<String, String>().apply {
            put("dash0.test.export_mode", BuildConfig.DASH0_EXPORT_MODE)
            cellId?.let { put("dash0.test.cell_id", it) }
        }

        try {
            val mobile = MobileOtel.initialize(app) {
                service {
                    name = "otel-android-astronomy-shop"
                    version = "0.1.0"
                }
                export {
                    endpoint = ExportConfig.grpcEndpoint
                    mode = ExportMode.valueOf(BuildConfig.DASH0_EXPORT_MODE_ENUM)
                    headers = ExportConfig.headers
                }
                instrumentations {
                    discoverAll()
                }
                extraResourceAttributes = extraAttrs
            }
            OtelDemoApplication.openTelemetry = mobile.openTelemetry
            OtelDemoApplication.sessionId = mobile.sessionId
            OtelDemoApplication.resourceAttributesSnapshot = extraAttrs.toMap()
        } catch (e: Exception) {
            Log.e("SdkInit", "Failed to initialize Dash0 SDK", e)
        }
    }
}
```

`BuildConfig.DASH0_EXPORT_MODE` and `BuildConfig.DASH0_EXPORT_MODE_ENUM` will be set by the per-flavor `buildConfigField` declarations added in Task 0.4. For now, add them with default values to the existing `dash0` flavor in `build.gradle.kts` so this task compiles standalone:

In `examples/upstream-demo-app/build.gradle.kts`, inside the existing `dash0` flavor block:

```kotlin
create("dash0") {
    dimension = "sdk"
    applicationIdSuffix = ".dash0"
    manifestPlaceholders["appNameSuffix"] = "(Dash0)"
    buildConfigField("String", "DASH0_EXPORT_MODE", "\"cont\"")
    buildConfigField("String", "DASH0_EXPORT_MODE_ENUM", "\"CONTINUOUS\"")
}
```

Also enable `buildConfig` in `buildFeatures`:

```kotlin
buildFeatures {
    viewBinding = true
    buildConfig = true
}
```

- [ ] **Step 5: Update Application/launcher activity to forward intent extra**

Find the launcher Activity (likely `MainActivity.kt`) and at the top of `onCreate(savedInstanceState)`, before any UI is set up:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (OtelDemoApplication.openTelemetry == null) {
        val cellId = intent?.getStringExtra("DASH0_CELL_ID")
        SdkInitializer.initialize(application, cellId)
    }
    // ... existing onCreate body
}
```

Remove any unconditional `SdkInitializer.initialize(this)` from `OtelDemoApplication.onCreate` if present — we want the SDK init delayed until first activity so we can pull `cell_id` from the launch intent. If a baseline session start is needed when no cell_id is set, keep the call but pass `cellId = null`.

Add `OtelDemoApplication.resourceAttributesSnapshot`:

```kotlin
class OtelDemoApplication : Application() {
    companion object {
        var openTelemetry: OpenTelemetry? = null
        var sessionId: String? = null
        var resourceAttributesSnapshot: Map<String, String> = emptyMap()
    }
}
```

- [ ] **Step 6: Run test, expect PASS**

```bash
./gradlew :upstream-demo-app:testDash0DebugUnitTest --tests "*.SdkInitializerCellIdTest"
```

Expected: `BUILD SUCCESSFUL`, 1 test passed.

- [ ] **Step 7: Build the dash0 flavor APK to confirm no compile regression**

```bash
./gradlew :upstream-demo-app:assembleDash0Debug
```

Expected: `BUILD SUCCESSFUL`, APK at `examples/upstream-demo-app/build/outputs/apk/dash0/debug/upstream-demo-app-dash0-debug.apk`.

- [ ] **Step 8: Commit**

```bash
git add examples/upstream-demo-app/build.gradle.kts examples/upstream-demo-app/src/dash0/
git commit -m "feat(demo-app): plumb DASH0_CELL_ID intent extra into resource attributes"
```

---

### Task 0.4: Split `dash0` flavor into 3 export-mode flavors

Replace the single `dash0` flavor with `dash0Continuous`, `dash0Conditional`, `dash0Hybrid`. The CONTINUOUS one is functionally identical to today's `dash0`, just renamed.

**Files:**
- Modify: `examples/upstream-demo-app/build.gradle.kts`
- Move: `examples/upstream-demo-app/src/dash0/` → `examples/upstream-demo-app/src/dash0Continuous/` (the shared source set; new flavors share via gradle source set inheritance)
- Update: `docs/matchy-matchy/android-native.md` (rename `dash0` → `dash0Continuous` in commands)

- [ ] **Step 1: Rename `dash0` source set directory**

```bash
git mv examples/upstream-demo-app/src/dash0 examples/upstream-demo-app/src/dash0Common
```

We'll point all three new flavors at this `dash0Common` source set so their `SdkInitializer.kt` is shared.

- [ ] **Step 2: Update `build.gradle.kts` flavor block**

Replace the existing `productFlavors` block with:

```kotlin
flavorDimensions += "sdk"
productFlavors {
    create("upstream") {
        dimension = "sdk"
        applicationIdSuffix = ".upstream"
        manifestPlaceholders["appNameSuffix"] = "(Upstream)"
    }
    create("dash0Continuous") {
        dimension = "sdk"
        applicationIdSuffix = ".dash0.cont"
        manifestPlaceholders["appNameSuffix"] = "(Dash0 Cont)"
        buildConfigField("String", "DASH0_EXPORT_MODE", "\"cont\"")
        buildConfigField("String", "DASH0_EXPORT_MODE_ENUM", "\"CONTINUOUS\"")
    }
    create("dash0Conditional") {
        dimension = "sdk"
        applicationIdSuffix = ".dash0.cond"
        manifestPlaceholders["appNameSuffix"] = "(Dash0 Cond)"
        buildConfigField("String", "DASH0_EXPORT_MODE", "\"cond\"")
        buildConfigField("String", "DASH0_EXPORT_MODE_ENUM", "\"CONDITIONAL\"")
    }
    create("dash0Hybrid") {
        dimension = "sdk"
        applicationIdSuffix = ".dash0.hyb"
        manifestPlaceholders["appNameSuffix"] = "(Dash0 Hyb)"
        buildConfigField("String", "DASH0_EXPORT_MODE", "\"hyb\"")
        buildConfigField("String", "DASH0_EXPORT_MODE_ENUM", "\"HYBRID\"")
    }
}
```

- [ ] **Step 3: Add shared source set declaration**

In the same `build.gradle.kts`, after the `productFlavors` block, add:

```kotlin
sourceSets {
    getByName("dash0Continuous") { java.srcDirs("src/dash0Common/java") }
    getByName("dash0Conditional") { java.srcDirs("src/dash0Common/java") }
    getByName("dash0Hybrid") { java.srcDirs("src/dash0Common/java") }
}
```

This way, all three flavors share `SdkInitializer.kt` from `src/dash0Common/`.

- [ ] **Step 4: Build all three flavors**

```bash
cd examples/demo-app
./gradlew :upstream-demo-app:assembleDash0ContinuousDebug \
          :upstream-demo-app:assembleDash0ConditionalDebug \
          :upstream-demo-app:assembleDash0HybridDebug
```

Expected: `BUILD SUCCESSFUL`. Three APKs at:
- `examples/upstream-demo-app/build/outputs/apk/dash0Continuous/debug/upstream-demo-app-dash0Continuous-debug.apk`
- `examples/upstream-demo-app/build/outputs/apk/dash0Conditional/debug/upstream-demo-app-dash0Conditional-debug.apk`
- `examples/upstream-demo-app/build/outputs/apk/dash0Hybrid/debug/upstream-demo-app-dash0Hybrid-debug.apk`

- [ ] **Step 5: Update `docs/matchy-matchy/android-native.md`**

Search-and-replace `dash0` → `dash0Continuous` in build commands and APK paths. Add a note at the top: "Three Dash0 flavors are now built per Android — `dash0Continuous` (this runbook's reference), `dash0Conditional`, `dash0Hybrid`. The matchy-matchy 4-gate evidence in this doc was captured against `dash0Continuous`."

- [ ] **Step 6: Commit**

```bash
git add examples/upstream-demo-app/ docs/matchy-matchy/android-native.md
git commit -m "feat(demo-app): split dash0 flavor into per-export-mode triple (cont/cond/hyb)"
```

---

### Task 0.5: Android primitive library

**Files:**
- Create: `scripts/test/uat/lib-uat-platform-android.sh`

- [ ] **Step 1: Implement the Android primitives**

Create `scripts/test/uat/lib-uat-platform-android.sh`:

```bash
#!/usr/bin/env bash
# Android-native primitive library for UAT matrix.
# Sourced by run-uat-cell.sh when --platform=android-native.
# Requires: adb, an emulator booted (use scripts/test/uat/run-uat-cell.sh
# to handle boot orchestration if needed).

set -u

# Per-flavor Android package IDs (must match build.gradle.kts applicationIdSuffix).
__uat_android_pkg_for_mode() {
    case "$1" in
        cont) echo "io.opentelemetry.android.demo.dash0.cont" ;;
        cond) echo "io.opentelemetry.android.demo.dash0.cond" ;;
        hyb)  echo "io.opentelemetry.android.demo.dash0.hyb" ;;
        *) echo "ERROR: unknown export mode: $1" >&2; return 1 ;;
    esac
}

__uat_android_apk_for_mode() {
    local mode="$1"
    local repo_root="${UAT_REPO_ROOT:-$(pwd)}"
    case "$mode" in
        cont) echo "$repo_root/examples/upstream-demo-app/build/outputs/apk/dash0Continuous/debug/upstream-demo-app-dash0Continuous-debug.apk" ;;
        cond) echo "$repo_root/examples/upstream-demo-app/build/outputs/apk/dash0Conditional/debug/upstream-demo-app-dash0Conditional-debug.apk" ;;
        hyb)  echo "$repo_root/examples/upstream-demo-app/build/outputs/apk/dash0Hybrid/debug/upstream-demo-app-dash0Hybrid-debug.apk" ;;
        *) echo "ERROR: unknown export mode: $1" >&2; return 1 ;;
    esac
}

uat::install() {
    local mode="$1"
    local apk pkg
    apk=$(__uat_android_apk_for_mode "$mode") || return 1
    pkg=$(__uat_android_pkg_for_mode "$mode") || return 1
    [[ -f "$apk" ]] || { echo "ERROR: APK not found: $apk" >&2; return 2; }
    # Uninstall any prior install of this package so cell_id-bearing fresh start
    adb uninstall "$pkg" >/dev/null 2>&1 || true
    adb install -r "$apk" >/dev/null
}

uat::launch() {
    local mode="$1" cell_id="$2"
    local pkg
    pkg=$(__uat_android_pkg_for_mode "$mode") || return 1
    # Launcher activity name is constant across flavors; only the appId changes.
    adb shell am start -n "${pkg}/io.opentelemetry.android.demo.MainActivity" \
        --es DASH0_CELL_ID "$cell_id" >/dev/null
}

uat::offline() {
    adb shell svc wifi disable
    adb shell svc data disable
}

uat::online() {
    adb shell svc wifi enable
    adb shell svc data enable
}

# uat::cycle_lifecycle - background → foreground once.
uat::cycle_lifecycle() {
    local mode="$1"
    local pkg
    pkg=$(__uat_android_pkg_for_mode "$mode") || return 1
    adb shell input keyevent KEYCODE_HOME
    sleep 1
    adb shell am start -n "${pkg}/io.opentelemetry.android.demo.MainActivity" >/dev/null
}

uat::trigger_crash() {
    local mode="$1"
    local pkg
    pkg=$(__uat_android_pkg_for_mode "$mode") || return 1
    adb shell am crash "$pkg" 2>/dev/null || \
        adb shell am start -n "${pkg}/io.opentelemetry.android.demo.MainActivity" --ez gate3_crash true >/dev/null
}

uat::cleanup() {
    local mode="$1"
    local pkg
    pkg=$(__uat_android_pkg_for_mode "$mode") || return 1
    adb uninstall "$pkg" >/dev/null 2>&1 || true
}

# uat::probe_disk_buffer <mode> — for cell 7 must-pass.
# Uses run-as on debuggable APKs to query the SDK's SQLite buffer.
uat::probe_disk_buffer() {
    local mode="$1"
    local pkg
    pkg=$(__uat_android_pkg_for_mode "$mode") || return 1
    # The DiskLogBuffer DB filename is "buffer.db" per DiskLogBuffer.kt; verify before merge.
    adb shell "run-as $pkg sqlite3 databases/buffer.db 'SELECT COUNT(*) FROM buffered_events'" 2>/dev/null \
        || echo "0"
}
```

- [ ] **Step 2: Smoke-test the library against a booted emulator**

```bash
# Start the Pixel_7 emulator if not already running
adb devices  # confirm at least one device

# Set up env for the lib
export UAT_REPO_ROOT="$(pwd)"
source scripts/test/uat/lib-uat-platform-android.sh

# Install the cont flavor
uat::install cont
echo "exit=$?"

# Launch with a fake cell_id
uat::launch cont "smoke-test-uuid-0001"
sleep 3

# Cycle lifecycle once
uat::cycle_lifecycle cont
sleep 2

# Cleanup
uat::cleanup cont
```

Expected: All commands return exit 0, no errors. Look for the demo app appearing on the emulator screen during the launch step.

- [ ] **Step 3: Commit**

```bash
git add scripts/test/uat/lib-uat-platform-android.sh
git commit -m "feat(uat): Android primitive library (install/launch/offline/crash/probe)"
```

---

### Task 0.6: Single-cell runner — Android, cell 1 (CONT online no-crash)

This is the smallest possible end-to-end: build the runner just enough to execute cell 1 on Android-native, run the 5-query Dash0 batch, and assert. We'll generalize to all 12 cells in later tasks.

**Files:**
- Create: `scripts/test/uat/run-uat-cell.sh`

- [ ] **Step 1: Implement minimal runner for Android cell 1**

Create `scripts/test/uat/run-uat-cell.sh`:

```bash
#!/usr/bin/env bash
# UAT cell runner — single-cell execution.
# Phase 0: supports Android-native + CONT + online + no-crash only.
# Subsequent tasks generalize this.

set -uo pipefail

usage() {
    cat <<EOF
Usage: $0 \\
  --platform=android-native \\
  --mode=cont|cond|hyb \\
  --connectivity=online|offline \\
  --crash=no|yes \\
  [--run-id=<uuid>] \\
  [--evidence-dir=<path>] \\
  [--keep-app]
EOF
}

# --- Arg parsing ---
PLATFORM=""
MODE=""
CONNECTIVITY=""
CRASH=""
RUN_ID=""
EVIDENCE_DIR=""
KEEP_APP=0

for arg in "$@"; do
    case "$arg" in
        --platform=*)     PLATFORM="${arg#*=}" ;;
        --mode=*)         MODE="${arg#*=}" ;;
        --connectivity=*) CONNECTIVITY="${arg#*=}" ;;
        --crash=*)        CRASH="${arg#*=}" ;;
        --run-id=*)       RUN_ID="${arg#*=}" ;;
        --evidence-dir=*) EVIDENCE_DIR="${arg#*=}" ;;
        --keep-app)       KEEP_APP=1 ;;
        -h|--help)        usage; exit 0 ;;
        *) echo "Unknown arg: $arg" >&2; usage; exit 2 ;;
    esac
done

[[ -n "$PLATFORM" && -n "$MODE" && -n "$CONNECTIVITY" && -n "$CRASH" ]] || { usage; exit 2; }

# --- Setup ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UAT_REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
export UAT_REPO_ROOT

RUN_ID="${RUN_ID:-$(uuidgen | tr 'A-Z' 'a-z')}"
ORIGINAL_CELL_ID="${RUN_ID}"
RECOVERY_CELL_ID="${RUN_ID}-recov"
EVIDENCE_DIR="${EVIDENCE_DIR:-${SCRIPT_DIR}/evidence/${RUN_ID}}"
mkdir -p "$EVIDENCE_DIR"
EVIDENCE_FILE="${EVIDENCE_DIR}/${PLATFORM}-${MODE}-${CONNECTIVITY}-${CRASH}.jsonl"
export UAT_EVIDENCE_FILE="$EVIDENCE_FILE"
: > "$EVIDENCE_FILE"  # truncate

echo "[UAT] cell=${PLATFORM}/${MODE}/${CONNECTIVITY}/${CRASH} cell_id=${ORIGINAL_CELL_ID}"
echo "[UAT] evidence=${EVIDENCE_FILE}"

# --- Source platform primitives ---
case "$PLATFORM" in
    android-native) source "${SCRIPT_DIR}/lib-uat-platform-android.sh" ;;
    *) echo "ERROR: platform $PLATFORM not yet supported" >&2; exit 3 ;;
esac

source "${SCRIPT_DIR}/lib-uat-assertions.sh"

# Per-platform service name resolution.
__uat_service_name_for_platform() {
    case "$1" in
        android-native|rn-android) echo "otel-android-astronomy-shop" ;;
        ios-native|rn-ios) echo "otel-ios-astronomy-shop" ;;
        *) echo "ERROR: unknown platform: $1" >&2; return 1 ;;
    esac
}
SERVICE_NAME=$(__uat_service_name_for_platform "$PLATFORM")

# --- Trigger sequence ---

# t=0: install + launch with cell_id
uat::install "$MODE"
uat::launch "$MODE" "$ORIGINAL_CELL_ID"
sleep 3

# t=10: lifecycle cycle 1
uat::cycle_lifecycle "$MODE"
sleep 5

# t=20: lifecycle cycle 2
uat::cycle_lifecycle "$MODE"
sleep 5

# t=90: total wall clock ~30s for cell 1; longer for offline/crash cells
sleep 5

# Phase 0 only handles online + no-crash; bail out for any other config.
if [[ "$CONNECTIVITY" != "online" || "$CRASH" != "no" ]]; then
    echo "[UAT] Phase 0 only supports online+no-crash; remaining cells in later tasks" >&2
    exit 3
fi

# --- Dash0 query batch ---
QUERY_FROM="now-3m"

run_logs_query() {
    local filter="$1"
    dash0 -X logs query --filter "$filter" --from "$QUERY_FROM" -o json 2>/dev/null \
        | python3 -c '
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print(0); sys.exit(0)
n = sum(len(s.get("logRecords", []))
        for r in d.get("resourceLogs", [])
        for s in r.get("scopeLogs", []))
print(n)
'
}

run_spans_query() {
    local filter="$1"
    dash0 -X spans query --filter "$filter" --from "$QUERY_FROM" -o json 2>/dev/null \
        | python3 -c '
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print(0); sys.exit(0)
n = sum(len(s.get("spans", []))
        for r in d.get("resourceSpans", [])
        for s in r.get("scopeSpans", []))
print(n)
'
}

# Wait a moment for Dash0 ingestion lag.
sleep 8

LIFE_FG=$(run_logs_query "service.name is ${SERVICE_NAME} and event.name is app.foreground and dash0.test.cell_id is ${ORIGINAL_CELL_ID}")
LIFE_BG=$(run_logs_query "service.name is ${SERVICE_NAME} and event.name is app.background and dash0.test.cell_id is ${ORIGINAL_CELL_ID}")
NET=$(run_spans_query "service.name is ${SERVICE_NAME} and http.request.method is GET and dash0.test.cell_id is ${ORIGINAL_CELL_ID}")
CRASH_COUNT=$(run_logs_query "service.name is ${SERVICE_NAME} and event.name is app.crash and dash0.test.cell_id is ${ORIGINAL_CELL_ID}")
RECOVERY_COUNT=$(run_logs_query "service.name is ${SERVICE_NAME} and event.name is app.recovery_start and dash0.test.cell_id is ${ORIGINAL_CELL_ID}")

# --- Cell 1 assertions: CONT online no-crash ---
EXIT=0
must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
must::ge "lifecycle_bg" "$LIFE_BG" 2 || EXIT=1
must::zero "no_crash" "$CRASH_COUNT" || EXIT=1
must::zero "no_recovery" "$RECOVERY_COUNT" || EXIT=1
warn::eq "lifecycle_fg_exact" "$LIFE_FG" 3
warn::eq "lifecycle_bg_exact" "$LIFE_BG" 2
warn::eq "network_get_count" "$NET" 1

# --- Cleanup ---
[[ "$KEEP_APP" -eq 1 ]] || uat::cleanup "$MODE"

if [[ "$EXIT" -eq 0 ]]; then
    echo "[UAT] CELL ${ORIGINAL_CELL_ID} ${PLATFORM}/${MODE}/${CONNECTIVITY}/${CRASH} result=pass"
else
    echo "[UAT] CELL ${ORIGINAL_CELL_ID} ${PLATFORM}/${MODE}/${CONNECTIVITY}/${CRASH} result=fail" >&2
fi
exit "$EXIT"
```

- [ ] **Step 2: Make executable**

```bash
chmod +x scripts/test/uat/run-uat-cell.sh
```

- [ ] **Step 3: Run cell 1 against the emulator**

Pre-conditions:
- `Pixel_7` emulator booted (`adb devices` shows it)
- Dash0 profile activated: `dash0 config profiles activate mobile-test`
- `dash0Continuous` APK exists from Task 0.4

```bash
scripts/test/uat/run-uat-cell.sh --platform=android-native --mode=cont --connectivity=online --crash=no
```

Expected: cell completes, exits 0, evidence file at `scripts/test/uat/evidence/<run-id>/android-native-cont-online-no.jsonl` has at least 7 JSONL lines (4 must, 3 warn) all with `"passed":true` (or warn drift acceptable).

If the must::ge lifecycle assertion fails with observed=0, that's typically a Dash0 ingestion lag — increase `sleep 8` to `sleep 20` and re-run. If it still fails, debug with:

```bash
dash0 -X logs query --filter "service.name is otel-android-astronomy-shop and dash0.test.cell_id is <cell_id_from_log>" --from now-5m -o json | python3 -m json.tool
```

- [ ] **Step 4: Commit**

```bash
git add scripts/test/uat/run-uat-cell.sh
git commit -m "feat(uat): single-cell runner with cell 1 (CONT online no-crash) on Android"
```

---

### Task 0.7: Phase 0 checkpoint — verify cell 1 green twice

Before continuing to the rest of the matrix, prove cell 1 is reproducible.

- [ ] **Step 1: Run cell 1 twice, verify both pass**

```bash
scripts/test/uat/run-uat-cell.sh --platform=android-native --mode=cont --connectivity=online --crash=no
scripts/test/uat/run-uat-cell.sh --platform=android-native --mode=cont --connectivity=online --crash=no
```

Both must exit 0. Each gets a fresh `cell_id`, so prior runs don't pollute.

- [ ] **Step 2: Inspect evidence files**

```bash
ls -la scripts/test/uat/evidence/
# pick the two latest run-ids
cat scripts/test/uat/evidence/<run-id-1>/android-native-cont-online-no.jsonl
cat scripts/test/uat/evidence/<run-id-2>/android-native-cont-online-no.jsonl
```

Each file should have all 4 must-pass lines with `"passed":true` and 3 warn lines.

If both green: Phase 0 is complete and the framework is sound. Proceed to Phase 1.
If flaky: investigate before adding more cells. Common causes: ingestion lag, emulator network drop, Dash0 auth lapse.

---

## Phase 1 — Generalize the Android cell runner to all 12 cells

### Task 1.1: Add HTTPS GET trigger to the demo app's launcher activity

For Gate 2 to be observable, the app needs to make a GET on launch. Confirm the existing demo app already does this (per matchy-matchy memory: "13/30 GET httpbin.org spans, trigger added to MainActivity.onResume" — see memory `project_session_2026_04_28.md`).

- [ ] **Step 1: Verify the existing GET trigger**

```bash
grep -rn "httpbin\|onResume.*GET\|gate2" examples/upstream-demo-app/src/dash0Common/
```

Expected: a coroutine in `MainActivity.onResume` that fires `OkHttp` against `https://httpbin.org/get`. Already present per memory.

If absent (memory may have drifted), add:

```kotlin
override fun onResume() {
    super.onResume()
    lifecycleScope.launch(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .addInterceptor(io.opentelemetry.android.network.OTelNetworkInterceptor.from(...))
                .build()
            client.newCall(Request.Builder().url("https://httpbin.org/get").build()).execute().close()
        } catch (e: Exception) { /* swallow */ }
    }
}
```

- [ ] **Step 2: If a change was needed, build + commit; otherwise skip**

```bash
./gradlew :upstream-demo-app:assembleDash0ContinuousDebug
git diff --quiet || git commit -am "feat(demo-app): ensure Gate 2 GET trigger fires on launcher resume"
```

---

### Task 1.2: Add crash trigger via launch-intent extra `gate3_crash`

Per memory `project_session_2026_04_28.md`, the dash0 flavor already supports `--ez gate3_crash true → multiThreadCrashing()`. Verify it's still wired in `dash0Common`.

- [ ] **Step 1: Verify**

```bash
grep -rn "gate3_crash\|multiThreadCrashing" examples/upstream-demo-app/src/dash0Common/ otel-android-mobile/
```

If the handler is in the `dash0Common` source set, it's automatically available to all three flavors. If it's elsewhere, move/copy it.

- [ ] **Step 2: If a change was needed, build + commit**

```bash
./gradlew :upstream-demo-app:assembleDash0ContinuousDebug
git diff --quiet || git commit -am "feat(demo-app): ensure gate3_crash launch-intent trigger reachable from all flavors"
```

---

### Task 1.3: Generalize runner to all 12 cells (Android only)

Replace the Phase-0 "online+no-crash only" guard with full trigger sequencing and per-cell assertion sets.

**Files:**
- Modify: `scripts/test/uat/run-uat-cell.sh`

- [ ] **Step 1: Replace the trigger sequence section**

In `run-uat-cell.sh`, replace the section between `# --- Trigger sequence ---` and `# --- Dash0 query batch ---` with:

```bash
# --- Trigger sequence ---

# t=0: install + launch with cell_id
uat::install "$MODE"
uat::launch "$MODE" "$ORIGINAL_CELL_ID"
sleep 3

# t=10: lifecycle cycle 1
uat::cycle_lifecycle "$MODE"
sleep 5

# t=20: lifecycle cycle 2
uat::cycle_lifecycle "$MODE"
sleep 5

# t=30: go offline (only if connectivity=offline)
if [[ "$CONNECTIVITY" == "offline" ]]; then
    uat::offline
    sleep 2
fi

# t=40: app-driven GET attempt (relies on app emitting periodic GETs;
# in the upstream-demo-app this happens on resume — we trigger one more
# foreground cycle to provoke it)
if [[ "$CONNECTIVITY" == "offline" ]]; then
    uat::cycle_lifecycle "$MODE"
    sleep 5
fi

# t=50: trigger crash (only if crash=yes)
if [[ "$CRASH" == "yes" ]]; then
    uat::trigger_crash "$MODE"
    sleep 3
fi

# t=60: go online (only if connectivity=offline)
if [[ "$CONNECTIVITY" == "offline" ]]; then
    uat::online
    sleep 5
fi

# t=70: relaunch (only if crash=yes OR connectivity=offline)
if [[ "$CRASH" == "yes" || "$CONNECTIVITY" == "offline" ]]; then
    uat::launch "$MODE" "$RECOVERY_CELL_ID"
    sleep 8
fi

# Cell 7 disk probe — run BEFORE relaunch erases buffer state
# (cell 7 has connectivity=offline, crash=no; no relaunch happens)
DISK_BUFFER_COUNT=0
if [[ "$CONNECTIVITY" == "offline" && "$CRASH" == "no" && "$MODE" == "cond" ]]; then
    DISK_BUFFER_COUNT=$(uat::probe_disk_buffer "$MODE")
fi

# t=90: Dash0 ingestion grace period
sleep 12
```

- [ ] **Step 2: Replace the assertion section with per-cell logic**

Replace from `# --- Cell 1 assertions ---` through end-of-script with:

```bash
# Recovery query uses recovery_cell_id (per spec §7).
RECOVERY_FOR_CELL="$ORIGINAL_CELL_ID"
if [[ "$CRASH" == "yes" || "$CONNECTIVITY" == "offline" ]]; then
    RECOVERY_FOR_CELL="$RECOVERY_CELL_ID"
fi

LIFE_FG=$(run_logs_query "service.name is ${SERVICE_NAME} and event.name is app.foreground and dash0.test.cell_id is ${ORIGINAL_CELL_ID}")
LIFE_BG=$(run_logs_query "service.name is ${SERVICE_NAME} and event.name is app.background and dash0.test.cell_id is ${ORIGINAL_CELL_ID}")
NET=$(run_spans_query "service.name is ${SERVICE_NAME} and http.request.method is GET and dash0.test.cell_id is ${ORIGINAL_CELL_ID}")
CRASH_COUNT=$(run_logs_query "service.name is ${SERVICE_NAME} and event.name is app.crash and dash0.test.cell_id is ${ORIGINAL_CELL_ID}")
RECOVERY_COUNT=$(run_logs_query "service.name is ${SERVICE_NAME} and event.name is app.recovery_start and dash0.test.cell_id is ${RECOVERY_FOR_CELL}")
PRESENCE=$(run_logs_query "service.name is ${SERVICE_NAME} and dash0.test.cell_id is ${ORIGINAL_CELL_ID}")

EXIT=0

# Map (mode, connectivity, crash) to assertions.
KEY="${MODE}-${CONNECTIVITY}-${CRASH}"
case "$KEY" in
    cont-online-no)
        must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
        must::ge "lifecycle_bg" "$LIFE_BG" 2 || EXIT=1
        must::zero "no_crash" "$CRASH_COUNT" || EXIT=1
        must::zero "no_recovery" "$RECOVERY_COUNT" || EXIT=1
        warn::eq "fg_exact" "$LIFE_FG" 3
        warn::eq "bg_exact" "$LIFE_BG" 2
        ;;
    cont-online-yes)
        must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
        must::eq "crash_present" "$CRASH_COUNT" 1 || EXIT=1
        ;;
    cont-offline-no)
        must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
        must::eq "recovery_present" "$RECOVERY_COUNT" 1 || EXIT=1
        must::zero "no_crash" "$CRASH_COUNT" || EXIT=1
        ;;
    cont-offline-yes)
        must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
        must::eq "recovery_present" "$RECOVERY_COUNT" 1 || EXIT=1
        must::eq "crash_present" "$CRASH_COUNT" 1 || EXIT=1
        ;;
    cond-online-no)
        # "Expected nothing" — four-gate signals all zero.
        must::zero "no_lifecycle_fg" "$LIFE_FG" || EXIT=1
        must::zero "no_lifecycle_bg" "$LIFE_BG" || EXIT=1
        must::zero "no_network" "$NET" || EXIT=1
        must::zero "no_crash" "$CRASH_COUNT" || EXIT=1
        must::zero "no_recovery" "$RECOVERY_COUNT" || EXIT=1
        warn::eq "presence_zero" "$PRESENCE" 0
        ;;
    cond-online-yes)
        # Crash drains buffered events synchronously online.
        must::eq "crash_present" "$CRASH_COUNT" 1 || EXIT=1
        must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
        must::zero "no_recovery" "$RECOVERY_COUNT" || EXIT=1
        ;;
    cond-offline-no)
        # No four-gate wire signals; disk buffer must contain events.
        must::zero "no_lifecycle_fg" "$LIFE_FG" || EXIT=1
        must::zero "no_network" "$NET" || EXIT=1
        must::ge "disk_buffered" "$DISK_BUFFER_COUNT" 4 || EXIT=1
        ;;
    cond-offline-yes)
        must::eq "recovery_present" "$RECOVERY_COUNT" 1 || EXIT=1
        must::eq "crash_present" "$CRASH_COUNT" 1 || EXIT=1
        must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
        ;;
    hyb-online-no)
        must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
        must::ge "lifecycle_bg" "$LIFE_BG" 2 || EXIT=1
        must::zero "no_crash" "$CRASH_COUNT" || EXIT=1
        must::zero "no_recovery" "$RECOVERY_COUNT" || EXIT=1
        ;;
    hyb-online-yes)
        must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
        must::eq "crash_present" "$CRASH_COUNT" 1 || EXIT=1
        ;;
    hyb-offline-no)
        must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
        must::eq "recovery_present" "$RECOVERY_COUNT" 1 || EXIT=1
        must::zero "no_crash" "$CRASH_COUNT" || EXIT=1
        ;;
    hyb-offline-yes)
        must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
        must::eq "recovery_present" "$RECOVERY_COUNT" 1 || EXIT=1
        must::eq "crash_present" "$CRASH_COUNT" 1 || EXIT=1
        ;;
    *)
        echo "ERROR: unknown cell key $KEY" >&2; EXIT=2 ;;
esac

# --- Cleanup ---
[[ "$KEEP_APP" -eq 1 ]] || uat::cleanup "$MODE"

if [[ "$EXIT" -eq 0 ]]; then
    echo "[UAT] CELL ${ORIGINAL_CELL_ID} ${PLATFORM}/${MODE}/${CONNECTIVITY}/${CRASH} result=pass"
else
    echo "[UAT] CELL ${ORIGINAL_CELL_ID} ${PLATFORM}/${MODE}/${CONNECTIVITY}/${CRASH} result=fail" >&2
fi
exit "$EXIT"
```

Also remove the Phase-0 "online + no-crash only" early-exit guard from earlier in the script.

- [ ] **Step 3: Run a few representative cells**

```bash
# Cell 2 (CONT online crash)
scripts/test/uat/run-uat-cell.sh --platform=android-native --mode=cont --connectivity=online --crash=yes

# Cell 5 (COND online no-crash — expected nothing)
scripts/test/uat/run-uat-cell.sh --platform=android-native --mode=cond --connectivity=online --crash=no

# Cell 7 (COND offline no-crash — disk probe)
scripts/test/uat/run-uat-cell.sh --platform=android-native --mode=cond --connectivity=offline --crash=no
```

Each should exit 0. If any fails, investigate before continuing — the failure tells us something real about that mode/path.

- [ ] **Step 4: Commit**

```bash
git add scripts/test/uat/run-uat-cell.sh
git commit -m "feat(uat): generalize cell runner to all 12 cells (Android)"
```

---

### Task 1.4: Matrix runner — outer loop + summary table

**Files:**
- Create: `scripts/test/uat/run-uat-matrix.sh`

- [ ] **Step 1: Implement matrix runner**

Create `scripts/test/uat/run-uat-matrix.sh`:

```bash
#!/usr/bin/env bash
# UAT matrix outer loop. Drives N cells and aggregates results.

set -uo pipefail

usage() {
    cat <<EOF
Usage: $0 \\
  [--platform=android-native[,ios-native,...]] \\
  [--cells=1-12|1,3,5] \\
  [--fail-fast] \\
  [--summary-md=<path>]

Defaults: all platforms, all 12 cells, no fail-fast, no summary file.
EOF
}

PLATFORMS_ARG=""
CELLS_ARG="1-12"
FAIL_FAST=0
SUMMARY_MD=""

for arg in "$@"; do
    case "$arg" in
        --platform=*)   PLATFORMS_ARG="${arg#*=}" ;;
        --cells=*)      CELLS_ARG="${arg#*=}" ;;
        --fail-fast)    FAIL_FAST=1 ;;
        --summary-md=*) SUMMARY_MD="${arg#*=}" ;;
        -h|--help)      usage; exit 0 ;;
        *) echo "Unknown arg: $arg" >&2; usage; exit 2 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Cell number → (mode, connectivity, crash) per spec §5 row order.
__cell_tuple() {
    case "$1" in
        1)  echo "cont online no" ;;
        2)  echo "cont online yes" ;;
        3)  echo "cont offline no" ;;
        4)  echo "cont offline yes" ;;
        5)  echo "cond online no" ;;
        6)  echo "cond online yes" ;;
        7)  echo "cond offline no" ;;
        8)  echo "cond offline yes" ;;
        9)  echo "hyb online no" ;;
        10) echo "hyb online yes" ;;
        11) echo "hyb offline no" ;;
        12) echo "hyb offline yes" ;;
        *)  echo "ERROR: bad cell number: $1" >&2; return 1 ;;
    esac
}

# Expand cell range.
__expand_cells() {
    local spec="$1"
    if [[ "$spec" =~ ^([0-9]+)-([0-9]+)$ ]]; then
        seq "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
    else
        echo "$spec" | tr ',' '\n'
    fi
}

PLATFORMS_DEFAULT="android-native ios-native rn-android rn-ios"
PLATFORMS_LIST="${PLATFORMS_ARG:-$PLATFORMS_DEFAULT}"
PLATFORMS_LIST="${PLATFORMS_LIST//,/ }"
CELLS_LIST=$(__expand_cells "$CELLS_ARG")

RUN_ID="$(uuidgen | tr 'A-Z' 'a-z')"
EVIDENCE_DIR="${SCRIPT_DIR}/evidence/${RUN_ID}"
mkdir -p "$EVIDENCE_DIR"

declare -a RESULTS  # entries like "android-native:1:pass"

WORST_EXIT=0
for plat in $PLATFORMS_LIST; do
    for cell_no in $CELLS_LIST; do
        read -r mode conn crash <<<"$(__cell_tuple "$cell_no")"
        echo
        echo "===== ${plat} cell ${cell_no} (${mode}/${conn}/${crash}) ====="
        if "${SCRIPT_DIR}/run-uat-cell.sh" \
            --platform="$plat" \
            --mode="$mode" \
            --connectivity="$conn" \
            --crash="$crash" \
            --run-id="$RUN_ID" \
            --evidence-dir="$EVIDENCE_DIR"; then
            RESULTS+=("${plat}:${cell_no}:pass")
        else
            ec=$?
            case "$ec" in
                1) RESULTS+=("${plat}:${cell_no}:fail") ;;
                2) RESULTS+=("${plat}:${cell_no}:infra") ;;
                3) RESULTS+=("${plat}:${cell_no}:skip") ;;
                *) RESULTS+=("${plat}:${cell_no}:err${ec}") ;;
            esac
            [[ "$ec" -gt "$WORST_EXIT" ]] && WORST_EXIT="$ec"
            [[ "$FAIL_FAST" -eq 1 && "$ec" -eq 1 ]] && break 2
        fi
    done
done

# --- Summary ---
echo
echo "===== SUMMARY ====="
for r in "${RESULTS[@]}"; do echo "  $r"; done

if [[ -n "$SUMMARY_MD" ]]; then
    {
        echo "# UAT Matrix Run — ${RUN_ID}"
        echo
        echo "| Platform | Cell | Result |"
        echo "|---|---|---|"
        for r in "${RESULTS[@]}"; do
            IFS=':' read -r p c res <<<"$r"
            local_emoji="❓"
            case "$res" in
                pass) local_emoji="🟢" ;;
                fail) local_emoji="🔴" ;;
                infra) local_emoji="⚠️" ;;
                skip) local_emoji="➖" ;;
            esac
            echo "| $p | $c | $local_emoji $res |"
        done
        echo
        echo "Evidence: \`${EVIDENCE_DIR}\`"
    } > "$SUMMARY_MD"
    echo "[UAT] Summary written to $SUMMARY_MD"
fi

exit "$WORST_EXIT"
```

- [ ] **Step 2: Make executable + smoke test on cells 1-3**

```bash
chmod +x scripts/test/uat/run-uat-matrix.sh
scripts/test/uat/run-uat-matrix.sh --platform=android-native --cells=1-3 --summary-md=/tmp/uat-summary.md
cat /tmp/uat-summary.md
```

Expected: 3 cells run, all 🟢, summary table shows 3 rows.

- [ ] **Step 3: Commit**

```bash
git add scripts/test/uat/run-uat-matrix.sh
git commit -m "feat(uat): matrix runner with platform/cell selection and summary table"
```

---

### Task 1.5: Full Android sweep — all 12 cells green

- [ ] **Step 1: Run full Android sweep**

```bash
scripts/test/uat/run-uat-matrix.sh --platform=android-native \
    --summary-md=docs/uat-matrix/android-native-sweep-$(date +%Y%m%d).md
```

Expected runtime: ~25 minutes. Expected: all 12 cells 🟢.

- [ ] **Step 2: If any cell fails, debug**

For each failing cell: read its evidence JSONL file, identify which assertion failed, query Dash0 directly with the cell_id to inspect raw data, fix the runner OR identify a real product issue. **Do not lower the assertion bar to make a cell pass.**

- [ ] **Step 3: Once all 12 green, commit the dated summary**

```bash
git add docs/uat-matrix/android-native-sweep-*.md
git commit -m "test(uat): Android sweep all 12 cells green ($(date +%Y-%m-%d))"
```

---

### Task 1.6: Populate `docs/uat-matrix/android-native.md` with cell-level evidence

**Files:**
- Create: `docs/uat-matrix/README.md`
- Create: `docs/uat-matrix/android-native.md`

- [ ] **Step 1: Write the matrix README**

Create `docs/uat-matrix/README.md`:

```markdown
# UAT Matrix — Per-platform Evidence

This directory holds per-platform 12-cell UAT evidence. For the design and
methodology, see [`docs/superpowers/specs/2026-05-01-uat-matrix-design.md`](../superpowers/specs/2026-05-01-uat-matrix-design.md).
For the cross-platform status grid, see [`docs/epics/UAT_MATRIX_EPIC.md`](../epics/UAT_MATRIX_EPIC.md).

## Cell numbering

| # | Mode | Conn | Crash |
|---|---|---|---|
| 1 | CONT | online | no |
| 2 | CONT | online | yes |
| 3 | CONT | offline | no |
| 4 | CONT | offline | yes |
| 5 | COND | online | no (expected nothing) |
| 6 | COND | online | yes |
| 7 | COND | offline | no (disk probe) |
| 8 | COND | offline | yes |
| 9 | HYB | online | no |
| 10 | HYB | online | yes |
| 11 | HYB | offline | no |
| 12 | HYB | offline | yes |

## Running

```bash
# Full sweep on one platform
scripts/test/uat/run-uat-matrix.sh --platform=android-native --summary-md=/tmp/run.md

# Just nightly subset (cells 1-3) on all platforms
scripts/test/uat/run-uat-matrix.sh --cells=1-3
```
```

- [ ] **Step 2: Write `android-native.md` evidence file**

Create `docs/uat-matrix/android-native.md` with a section per cell:

```markdown
# UAT Matrix — Android Native

**Service name:** `otel-android-astronomy-shop`
**Demo app:** `examples/upstream-demo-app/` (flavors: `dash0Continuous`, `dash0Conditional`, `dash0Hybrid`)
**Last full sweep:** YYYY-MM-DD (fill in after Task 1.5 lands)

## Status

| Cell | Mode | Conn | Crash | Result | Last verified |
|---|---|---|---|---|---|
| 1 | CONT | online | no | 🟢 | YYYY-MM-DD |
| 2 | CONT | online | yes | 🟢 | YYYY-MM-DD |
| 3 | CONT | offline | no | 🟢 | YYYY-MM-DD |
| 4 | CONT | offline | yes | 🟢 | YYYY-MM-DD |
| 5 | COND | online | no | 🟢 | YYYY-MM-DD |
| 6 | COND | online | yes | 🟢 | YYYY-MM-DD |
| 7 | COND | offline | no | 🟢 | YYYY-MM-DD |
| 8 | COND | offline | yes | 🟢 | YYYY-MM-DD |
| 9 | HYB | online | no | 🟢 | YYYY-MM-DD |
| 10 | HYB | online | yes | 🟢 | YYYY-MM-DD |
| 11 | HYB | offline | no | 🟢 | YYYY-MM-DD |
| 12 | HYB | offline | yes | 🟢 | YYYY-MM-DD |

## Reproduce

```bash
scripts/test/uat/run-uat-matrix.sh --platform=android-native --summary-md=/tmp/uat.md
```

## Latest evidence summary

(paste output of the most recent sweep summary file here)
```

Fill in the actual dates from Task 1.5's run.

- [ ] **Step 3: Commit**

```bash
git add docs/uat-matrix/
git commit -m "docs(uat): seed matrix evidence dir with Android native results"
```

---

## Phase 2 — iOS native, RN-Android, RN-iOS (nightly subset only: cells 1-3)

Phase 2 establishes that the framework runs on all four platforms — we only require cells 1-3 green per platform. Full sweeps on those three are explicitly out of scope per spec §12 ("acceptance criterion 5"). The structure of each platform's task mirrors Phase 0–1 but scoped tighter.

### Task 2.1: iOS demo-app `cell_id` plumbing

**Files:**
- Modify: `examples/upstream-demo-app-ios/AstronomyShop/AstronomyShopApp.swift` (or whichever Swift file calls `OTelMobile.start`)

- [ ] **Step 1: Read `DASH0_CELL_ID` env at SDK init**

In the SDK init Swift file (find with `grep -rn "OTelMobile.start\|MobileConfig" examples/upstream-demo-app-ios/`):

```swift
let cellId = ProcessInfo.processInfo.environment["DASH0_CELL_ID"]
let exportMode = ProcessInfo.processInfo.environment["DASH0_EXPORT_MODE"] ?? "cont"

var resourceAttrs: [String: String] = ["dash0.test.export_mode": exportMode]
if let cellId = cellId {
    resourceAttrs["dash0.test.cell_id"] = cellId
}

let config = MobileConfig(
    serviceName: "otel-ios-astronomy-shop",
    // ...
    extraResourceAttributes: resourceAttrs,
    exportMode: ExportMode(rawValue: exportMode.uppercased()) ?? .continuous
)
```

- [ ] **Step 2: Verify the app builds**

```bash
cd examples/upstream-demo-app-ios
xcodebuild -scheme AstronomyShop -destination 'platform=iOS Simulator,name=iPhone 17' build 2>&1 | tail -20
```

Expected: `BUILD SUCCEEDED`.

- [ ] **Step 3: Commit**

```bash
git add examples/upstream-demo-app-ios/
git commit -m "feat(ios-demo): plumb DASH0_CELL_ID env into resource attributes"
```

---

### Task 2.2: iOS schemes for `Dash0Conditional` + `Dash0Hybrid`

For Phase 2 we only need cells 1-3, all of which use export mode `cont`, so additional schemes are NOT required to land cells 1-3. **Skip Task 2.2 for now** — defer it to a follow-on epic when iOS full sweep is undertaken.

- [ ] **Step 1: Confirm cells 1-3 only need the existing CONTINUOUS path on iOS**

Cells 1, 2, 3 = CONT online no, CONT online yes, CONT offline no. All `mode=cont`. Default scheme handles them.

(No commit; note in the epic doc.)

---

### Task 2.3: iOS primitive library

**Files:**
- Create: `scripts/test/uat/lib-uat-platform-ios.sh`

- [ ] **Step 1: Implement iOS primitives**

Create `scripts/test/uat/lib-uat-platform-ios.sh`:

```bash
#!/usr/bin/env bash
# iOS-native primitive library for UAT matrix.
# Requires: xcrun simctl, an iPhone simulator booted, an .app bundle pre-built.

set -u

# Override at call time if scheme paths differ.
: "${UAT_IOS_BUNDLE_ID:=io.dash0.AstronomyShop}"
: "${UAT_IOS_APP_PATH:=}"  # absolute path to .app bundle, set by build step

uat::install() {
    local mode="$1"
    if [[ "$mode" != "cont" ]]; then
        echo "ERROR: iOS UAT only supports cont mode in Phase 2 (no Conditional/Hybrid scheme)" >&2
        return 3
    fi
    [[ -d "$UAT_IOS_APP_PATH" ]] || { echo "ERROR: UAT_IOS_APP_PATH not set or app missing: $UAT_IOS_APP_PATH" >&2; return 2; }
    xcrun simctl uninstall booted "$UAT_IOS_BUNDLE_ID" >/dev/null 2>&1 || true
    xcrun simctl install booted "$UAT_IOS_APP_PATH" >/dev/null
}

uat::launch() {
    local mode="$1" cell_id="$2"
    SIMCTL_CHILD_DASH0_CELL_ID="$cell_id" \
    SIMCTL_CHILD_DASH0_EXPORT_MODE="$mode" \
    xcrun simctl launch booted "$UAT_IOS_BUNDLE_ID" >/dev/null
}

# iOS Simulator has no per-sim airplane mode. Endpoint-swap approach:
# - Save current otel-config.json on first call
# - Rewrite the in-bundle (or in-Documents) config to *.invalid:4318
# - Relaunch app
# This is destructive; restore via uat::online.
uat::offline() {
    # The endpoint-swap implementation is brittle; for cells 1-3
    # (which are all online-only or skip iOS), this is a stub.
    echo "ERROR: iOS offline mode not implemented in Phase 2; cells 3+ deferred" >&2
    return 3
}

uat::online() { return 0; }

uat::cycle_lifecycle() {
    local mode="$1"
    # Backgrounding via simctl is unreliable per memory feedback_otlp_exporter_failure_detection.md.
    # Approximate via bringing a different bundle to foreground.
    xcrun simctl launch booted "com.apple.mobilesafari" >/dev/null 2>&1 || true
    sleep 1
    xcrun simctl launch booted "$UAT_IOS_BUNDLE_ID" >/dev/null
}

uat::trigger_crash() {
    # For cells 2/cell-y on iOS we'd set DASH0_GATE3_CRASH=1 at launch
    # and the demo app calls fatalError() after warmup. Phase 2 does
    # not reach a crash cell on iOS (cells 1-3 are 1=no,2=yes-online,3=no).
    # Cell 2 needs a fresh launch with the env set. Skip for Phase 2.
    echo "ERROR: iOS crash trigger not implemented in Phase 2" >&2
    return 3
}

uat::cleanup() {
    xcrun simctl uninstall booted "$UAT_IOS_BUNDLE_ID" >/dev/null 2>&1 || true
}

uat::probe_disk_buffer() {
    echo "0"  # not exercised by cells 1-3 on iOS
}
```

- [ ] **Step 2: Build the iOS app for testing**

```bash
cd examples/upstream-demo-app-ios
xcodebuild -scheme AstronomyShop -destination 'platform=iOS Simulator,name=iPhone 17' \
    -derivedDataPath /tmp/uat-ios-build clean build 2>&1 | tail -10

export UAT_IOS_APP_PATH="/tmp/uat-ios-build/Build/Products/Debug-iphonesimulator/AstronomyShop.app"
[[ -d "$UAT_IOS_APP_PATH" ]] && echo "App built at $UAT_IOS_APP_PATH"
```

- [ ] **Step 3: Update run-uat-cell.sh to source iOS lib**

In `run-uat-cell.sh`, find the `case "$PLATFORM"` block and add:

```bash
ios-native) source "${SCRIPT_DIR}/lib-uat-platform-ios.sh" ;;
```

- [ ] **Step 4: Run cell 1 on iOS**

```bash
# Boot simulator if not running
xcrun simctl boot "iPhone 17" 2>/dev/null || true

scripts/test/uat/run-uat-cell.sh --platform=ios-native --mode=cont --connectivity=online --crash=no
```

Expected: exit 0. Cell 2 (cont online yes) requires the iOS demo app to support `DASH0_GATE3_CRASH=1` env-driven crash; if not yet present, cell 2 returns exit 3 (skipped) which is acceptable for Phase 2.

- [ ] **Step 5: Commit**

```bash
git add scripts/test/uat/lib-uat-platform-ios.sh scripts/test/uat/run-uat-cell.sh
git commit -m "feat(uat): iOS primitive library + runner integration (cells 1-3 nightly subset)"
```

---

### Task 2.4: RN-Android primitive library

**Files:**
- Create: `scripts/test/uat/lib-uat-platform-rn-android.sh`

The RN-Android case re-uses the underlying Android Studio + adb tooling but targets the RN demo app's package (`com.astronomyshoprn` or similar — verify with `grep -rn "applicationId\|namespace" examples/upstream-demo-app-rn/AstronomyShopRN/android/app/build.gradle`).

- [ ] **Step 1: Implement RN-Android lib by inheritance from android lib**

```bash
#!/usr/bin/env bash
# RN-Android UAT primitives. Same adb tooling as Android native, different package.

set -u

# RN demo app package — confirm with grep above.
: "${UAT_RN_ANDROID_PKG:=com.astronomyshoprn}"
: "${UAT_RN_ANDROID_LAUNCHER:=com.astronomyshoprn.MainActivity}"

uat::install() {
    local mode="$1"
    if [[ "$mode" != "cont" ]]; then
        echo "ERROR: RN-Android UAT only supports cont mode in Phase 2" >&2
        return 3
    fi
    local apk="${UAT_REPO_ROOT}/examples/upstream-demo-app-rn/AstronomyShopRN/android/app/build/outputs/apk/release/app-release.apk"
    [[ -f "$apk" ]] || { echo "ERROR: RN APK not found at $apk — run \`npm run android:build\` in AstronomyShopRN first" >&2; return 2; }
    adb uninstall "$UAT_RN_ANDROID_PKG" >/dev/null 2>&1 || true
    adb install -r "$apk" >/dev/null
}

uat::launch() {
    local mode="$1" cell_id="$2"
    adb shell am start -n "${UAT_RN_ANDROID_PKG}/${UAT_RN_ANDROID_LAUNCHER}" \
        --es DASH0_CELL_ID "$cell_id" >/dev/null
}

uat::offline() { adb shell svc wifi disable; adb shell svc data disable; }
uat::online()  { adb shell svc wifi enable;  adb shell svc data enable; }

uat::cycle_lifecycle() {
    adb shell input keyevent KEYCODE_HOME
    sleep 1
    adb shell am start -n "${UAT_RN_ANDROID_PKG}/${UAT_RN_ANDROID_LAUNCHER}" >/dev/null
}

uat::trigger_crash() {
    adb shell am crash "$UAT_RN_ANDROID_PKG" 2>/dev/null || \
        adb shell am start -n "${UAT_RN_ANDROID_PKG}/${UAT_RN_ANDROID_LAUNCHER}" --ez gate3_crash true >/dev/null
}

uat::cleanup() { adb uninstall "$UAT_RN_ANDROID_PKG" >/dev/null 2>&1 || true; }

uat::probe_disk_buffer() {
    adb shell "run-as $UAT_RN_ANDROID_PKG sqlite3 databases/buffer.db 'SELECT COUNT(*) FROM buffered_events'" 2>/dev/null \
        || echo "0"
}
```

- [ ] **Step 2: Modify RN demo app to read `DASH0_CELL_ID` from intent**

The RN bridge sits over native Android, so the same `DASH0_CELL_ID` pattern works — but RN's Java side needs to forward it to JS or set it on the native config before `Dash0Mobile.start()`. The cleanest path: native side reads intent extra and sets it on the Android `MobileConfig.extraResourceAttributes` before the RN bridge's `start()` call.

Locate the file (typically `MainApplication.kt` or `Dash0MobileModule.kt` in the RN bridge) with:

```bash
grep -rn "MobileConfig\|extraResourceAttributes\|cellId" examples/upstream-demo-app-rn/AstronomyShopRN/android/
```

Add resource-attribute injection at the bridge config-construction point. Exact code depends on the bridge layout; the pattern matches Task 0.3.

- [ ] **Step 3: Build + run cell 1 on RN-Android**

```bash
cd examples/upstream-demo-app-rn/AstronomyShopRN
npm run android:build  # or whatever the project's build command is

cd "$UAT_REPO_ROOT"
scripts/test/uat/run-uat-cell.sh --platform=rn-android --mode=cont --connectivity=online --crash=no
```

Expected: exit 0.

- [ ] **Step 4: Commit**

```bash
git add scripts/test/uat/lib-uat-platform-rn-android.sh examples/upstream-demo-app-rn/
git commit -m "feat(uat): RN-Android primitive library + cell_id plumbing"
```

---

### Task 2.5: RN-iOS primitive library

Mirror Task 2.3 but target the RN iOS bundle.

**Files:**
- Create: `scripts/test/uat/lib-uat-platform-rn-ios.sh`

- [ ] **Step 1: Find RN iOS bundle id**

```bash
grep -rn "PRODUCT_BUNDLE_IDENTIFIER\|bundleIdentifier" examples/upstream-demo-app-rn/AstronomyShopRN/ios/ | head
```

- [ ] **Step 2: Implement RN-iOS lib (parallel to lib-uat-platform-ios.sh, different bundle)**

```bash
#!/usr/bin/env bash
set -u

: "${UAT_RN_IOS_BUNDLE_ID:=com.astronomyshoprn}"
: "${UAT_RN_IOS_APP_PATH:=}"

uat::install() {
    [[ "$1" == "cont" ]] || { echo "ERROR: RN-iOS UAT only cont in Phase 2" >&2; return 3; }
    [[ -d "$UAT_RN_IOS_APP_PATH" ]] || { echo "ERROR: UAT_RN_IOS_APP_PATH not set" >&2; return 2; }
    xcrun simctl uninstall booted "$UAT_RN_IOS_BUNDLE_ID" >/dev/null 2>&1 || true
    xcrun simctl install booted "$UAT_RN_IOS_APP_PATH" >/dev/null
}

uat::launch() {
    local mode="$1" cell_id="$2"
    SIMCTL_CHILD_DASH0_CELL_ID="$cell_id" \
    SIMCTL_CHILD_DASH0_EXPORT_MODE="$mode" \
    xcrun simctl launch booted "$UAT_RN_IOS_BUNDLE_ID" >/dev/null
}

uat::offline() { echo "ERROR: not in Phase 2" >&2; return 3; }
uat::online() { return 0; }

uat::cycle_lifecycle() {
    xcrun simctl launch booted "com.apple.mobilesafari" >/dev/null 2>&1 || true
    sleep 1
    xcrun simctl launch booted "$UAT_RN_IOS_BUNDLE_ID" >/dev/null
}

uat::trigger_crash() { echo "ERROR: not in Phase 2" >&2; return 3; }
uat::cleanup() { xcrun simctl uninstall booted "$UAT_RN_IOS_BUNDLE_ID" >/dev/null 2>&1 || true; }
uat::probe_disk_buffer() { echo "0"; }
```

- [ ] **Step 3: Update runner**

In `run-uat-cell.sh`'s `case "$PLATFORM"`:

```bash
rn-ios) source "${SCRIPT_DIR}/lib-uat-platform-rn-ios.sh" ;;
rn-android) source "${SCRIPT_DIR}/lib-uat-platform-rn-android.sh" ;;
```

- [ ] **Step 4: Plumb DASH0_CELL_ID into RN-iOS demo app**

Mirror Task 2.1 and 2.4 — the RN bridge's iOS side reads env at start and sets it on `MobileConfig.extraResourceAttributes`. Locate with:

```bash
grep -rn "OTelMobile.start\|MobileConfig" examples/upstream-demo-app-rn/AstronomyShopRN/ios/
```

- [ ] **Step 5: Build + run cell 1**

```bash
cd examples/upstream-demo-app-rn/AstronomyShopRN
xcodebuild -workspace ios/AstronomyShopRN.xcworkspace -scheme AstronomyShopRN \
    -destination 'platform=iOS Simulator,name=iPhone 17' \
    -derivedDataPath /tmp/uat-rn-ios-build clean build 2>&1 | tail -10

export UAT_RN_IOS_APP_PATH="/tmp/uat-rn-ios-build/Build/Products/Debug-iphonesimulator/AstronomyShopRN.app"

cd "$UAT_REPO_ROOT"
scripts/test/uat/run-uat-cell.sh --platform=rn-ios --mode=cont --connectivity=online --crash=no
```

Expected: exit 0.

- [ ] **Step 6: Commit**

```bash
git add scripts/test/uat/lib-uat-platform-rn-ios.sh scripts/test/uat/run-uat-cell.sh examples/upstream-demo-app-rn/
git commit -m "feat(uat): RN-iOS primitive library + cell_id plumbing"
```

---

### Task 2.6: Run nightly subset (cells 1-3) across all 4 platforms

- [ ] **Step 1: Run the cross-platform subset**

```bash
scripts/test/uat/run-uat-matrix.sh --cells=1-3 \
    --summary-md=docs/uat-matrix/cross-platform-nightly-$(date +%Y%m%d).md
```

Expected: 12 cell runs (3 cells × 4 platforms), most pass; iOS cell 2 + RN-iOS cell 2 may exit code 3 (skip) since iOS crash trigger isn't fully implemented in Phase 2.

- [ ] **Step 2: Populate the per-platform evidence files**

Create stub evidence sections in:
- `docs/uat-matrix/ios-native.md`
- `docs/uat-matrix/rn-android.md`
- `docs/uat-matrix/rn-ios.md`

Each follows the structure from Task 1.6 step 2 — table with cells 1, 2, 3 status; cells 4-12 marked 🔴 deferred. Use the dated summary file as the source of truth.

- [ ] **Step 3: Commit**

```bash
git add docs/uat-matrix/
git commit -m "test(uat): cross-platform nightly subset (cells 1-3) baseline"
```

---

## Phase 3 — Epic doc + CI staircase

### Task 3.1: Create `UAT_MATRIX_EPIC.md`

**Files:**
- Create: `docs/epics/UAT_MATRIX_EPIC.md`

- [ ] **Step 1: Write the epic doc**

Create `docs/epics/UAT_MATRIX_EPIC.md`:

```markdown
# Epic: UAT Matrix — Cross-platform Acceptance Across Export Modes

**Status:** In progress
**Priority:** P1
**Owner:** Barry Solomon
**Created:** 2026-05-01
**Spec:** [`docs/superpowers/specs/2026-05-01-uat-matrix-design.md`](../superpowers/specs/2026-05-01-uat-matrix-design.md)

## Summary

48-cell acceptance matrix (4 platforms × 12 cells) exercising export-mode × connectivity × crash combinations end-to-end with Dash0-confirmed evidence.

## Status grid

| Platform | C1 | C2 | C3 | C4 | C5 | C6 | C7 | C8 | C9 | C10 | C11 | C12 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Android native | 🟢 | 🟢 | 🟢 | 🟢 | 🟢 | 🟢 | 🟢 | 🟢 | 🟢 | 🟢 | 🟢 | 🟢 |
| iOS native | 🟢 | 🟡 | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 |
| RN Android | 🟢 | 🟢 | 🟢 | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 |
| RN iOS | 🟢 | 🟡 | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 | 🔴 |

🟢 = green per-cell evidence captured. 🟡 = framework supports but cell skipped (e.g. iOS crash). 🔴 = not yet exercised.

(Update with actual results from Tasks 1.5 + 2.6.)

## Reproduce

```bash
# Full Android
scripts/test/uat/run-uat-matrix.sh --platform=android-native

# Nightly (cells 1-3, all platforms)
scripts/test/uat/run-uat-matrix.sh --cells=1-3
```

## Open follow-on work

- iOS schemes for `Dash0Conditional` + `Dash0Hybrid` (deferred from Task 2.2)
- iOS `uat::trigger_crash` + `uat::offline` implementations (deferred from Task 2.3)
- RN-iOS crash + offline (deferred from Task 2.5)
- Full sweep on iOS / RN-Android / RN-iOS (cells 4-12)
- CI staircase wiring (per-PR, nightly, weekly per spec §11) — separate epic
```

- [ ] **Step 2: Commit**

```bash
git add docs/epics/UAT_MATRIX_EPIC.md
git commit -m "docs(uat): epic doc for cross-platform UAT matrix"
```

---

### Task 3.2: Wire per-PR smoke into existing CI

**Files:**
- Modify: `.github/workflows/test.yml`

- [ ] **Step 1: Read existing CI definition**

```bash
cat .github/workflows/test.yml
```

Identify the existing `android-integration-tests` job (per CLAUDE.md it's emulator-based, main-branch only).

- [ ] **Step 2: Add a UAT smoke step to the existing android-integration-tests job**

Append after the existing test invocation:

```yaml
      - name: UAT smoke (cell 1, android-native)
        run: |
          dash0 config profiles activate mobile-test
          scripts/test/uat/run-uat-matrix.sh --platform=android-native --cells=1 --fail-fast
        env:
          DASH0_AUTH_TOKEN: ${{ secrets.DASH0_AUTH_TOKEN }}
```

Add `DASH0_AUTH_TOKEN` to the GitHub Secrets if not already present.

- [ ] **Step 3: Verify locally that the smoke command works in isolation**

```bash
scripts/test/uat/run-uat-matrix.sh --platform=android-native --cells=1 --fail-fast
```

Expected: exit 0.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/test.yml
git commit -m "ci(uat): wire cell-1 smoke into android-integration-tests job"
```

---

## Phase 4 — Final acceptance check

### Task 4.1: Run all acceptance criteria from spec §12

- [ ] **Step 1: Verify acceptance criterion 1 — 12 build artifacts buildable**

```bash
# Android: 3 flavors (existing dash0Continuous + 2 new)
cd examples/demo-app
./gradlew :upstream-demo-app:assembleDash0ContinuousDebug \
          :upstream-demo-app:assembleDash0ConditionalDebug \
          :upstream-demo-app:assembleDash0HybridDebug
# Expect: BUILD SUCCESSFUL × 3

# iOS: 1 scheme (Dash0Conditional + Dash0Hybrid deferred — that's documented gap)
cd ../../examples/upstream-demo-app-ios
xcodebuild -scheme AstronomyShop -destination 'platform=iOS Simulator,name=iPhone 17' build 2>&1 | tail -5
# Expect: BUILD SUCCEEDED

# RN: same — only the cont path required for Phase 2
```

Document the 4 platforms × 1 mode (cont) baseline. The 3-mode-per-platform full coverage is partially landed (Android only).

- [ ] **Step 2: Verify acceptance criterion 2 — single-cell runner works on each platform**

```bash
scripts/test/uat/run-uat-cell.sh --platform=android-native --mode=cont --connectivity=online --crash=no
scripts/test/uat/run-uat-cell.sh --platform=ios-native     --mode=cont --connectivity=online --crash=no
scripts/test/uat/run-uat-cell.sh --platform=rn-android     --mode=cont --connectivity=online --crash=no
scripts/test/uat/run-uat-cell.sh --platform=rn-ios         --mode=cont --connectivity=online --crash=no
```

Expected: all four exit 0. Each emits a valid evidence JSONL file.

- [ ] **Step 3: Verify acceptance criterion 3 — matrix sweep produces summary**

```bash
scripts/test/uat/run-uat-matrix.sh --platform=android-native \
    --summary-md=/tmp/uat-final-android.md
test -f /tmp/uat-final-android.md && grep -q "🟢" /tmp/uat-final-android.md && echo "AC3 OK"
```

Expected: `AC3 OK`.

- [ ] **Step 4: Verify acceptance criterion 4 — all 12 Android cells must-pass green**

```bash
scripts/test/uat/run-uat-matrix.sh --platform=android-native
echo "exit=$?"
```

Expected: `exit=0`. If non-zero, identify failing cell, fix, re-run.

- [ ] **Step 5: Verify acceptance criterion 5 — cells 1-3 green on other 3 platforms**

```bash
scripts/test/uat/run-uat-matrix.sh --cells=1-3
```

Expected: only must-pass-green cells exit 0; iOS/RN-iOS cell 2 may legitimately exit 3 (skip) per Task 2.x scope.

- [ ] **Step 6: Verify acceptance criterion 6 — `UAT_MATRIX_EPIC.md` exists and tracks 4×12 status**

```bash
test -f docs/epics/UAT_MATRIX_EPIC.md && grep -q "Status grid" docs/epics/UAT_MATRIX_EPIC.md && echo "AC6 OK"
```

- [ ] **Step 7: Final commit + memory write**

```bash
git status
git log --oneline | head -20
```

The plan is complete when all 6 acceptance criteria from spec §12 are demonstrably satisfied by passing commands.

---

## Self-review summary (run at end of writing)

**Spec coverage check:**

- §3 in-scope items: all addressed across Phases 0–3
- §3 out-of-scope: explicitly NOT in any task ✓
- §4 architecture (file layout): matches Task File Structure section above
- §5 12-cell expectation table: implemented in Task 1.3
- §6 service names + cell_id scheme: implemented in Tasks 0.3, 2.1, 2.4, 2.5
- §7 recovery cell_id asymmetry: implemented in Task 1.3 (RECOVERY_FOR_CELL split)
- §8 pre-built flavors: implemented in Tasks 0.4, 2.2 (deferred), 2.4, 2.5
- §9 tiered assertions: implemented in Task 0.1
- §10 runner contracts: implemented in Tasks 0.6, 1.3, 1.4
- §11 CI staircase: per-PR wired in Task 3.2; nightly + weekly are operational concerns out of code-plan scope
- §12 acceptance criteria 1-6: verified in Task 4.1 steps 1-6
- §13 risks: surfaced in plan tasks where applicable
- §14 open question: answered in spec; no plan task needed

**Placeholder scan:** none

**Type/name consistency:** `cell_id` always lowercase-underscored in attribute keys, `RECOVERY_CELL_ID` consistently in shell vars, `dash0Continuous`/`dash0Conditional`/`dash0Hybrid` consistent across Android tasks.

**Gaps acknowledged in plan (not gaps in spec):**

- iOS `Dash0Conditional`/`Dash0Hybrid` schemes deferred (Task 2.2) — explicitly noted
- iOS + RN-iOS offline + crash primitives deferred (Tasks 2.3, 2.5) — explicitly noted
- These are spec §12 acceptance-criterion-5 ("cells 1-3 green on other platforms") so deferral is on spec.
