# Gate 1 — RN Lifecycle Unblock Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the Gate 1 🔴 cells in the Validation Matrix Epic for RN iOS and RN Android by fixing the Android `LifecycleInstrumentation` install-time race via ProcessLifecycleOwner, adding an `applicationState`-check synthesis path on iOS, defaulting `.lifecycle` on in the iOS RN bridge, and deleting the obsolete JS-side AppState shim.

**Architecture:** Three SDK-side fixes plus cleanup. Android uses `androidx.lifecycle.ProcessLifecycleOwner` — its built-in at-attach replay handles late init for free. iOS gets an `applicationStateProvider` closure (testable via internal init) that checks `UIApplication.shared.applicationState == .active` at install time and synthesizes an `app.foreground` log if so. The iOS RN bridge's `parseAutoCaptureOptions` defaults to `[.lifecycle]`. JS-side `installAppStateInstrumentation` and the now-meaningless `autoCapture.lifecycle` field are deleted.

**Tech Stack:** Kotlin (JDK 17, AGP 9.0, Robolectric for Android lifecycle tests), Swift 5.9 (SwiftPM, Swift Testing framework), TypeScript (RN 0.85).

**Spec:** [`docs/superpowers/specs/2026-04-29-gate1-rn-lifecycle-design.md`](../specs/2026-04-29-gate1-rn-lifecycle-design.md)

---

## File map

| File | Status | Responsibility |
| --- | --- | --- |
| `instrumentation/lifecycle/build.gradle.kts` | Modify | Add `lifecycle-process` dep |
| `instrumentation/lifecycle/consumer-rules.pro` | Modify | Add R8 keep rules |
| `instrumentation/lifecycle/.../LifecycleInstrumentation.kt` | Refactor | Migrate to ProcessLifecycleOwner; add late-init synthesis |
| `instrumentation/lifecycle/.../LifecycleInstrumentationTest.kt` | Refactor | Drive ProcessLifecycleOwner; add 4 new tests; rewrite 2 existing fg/bg tests |
| `otel-ios-mobile/Sources/LifecycleInstrumentation/LifecycleInstrumentation.swift` | Modify | Add `applicationStateProvider` seam + late-init synthesis |
| `otel-ios-mobile/Tests/LifecycleInstrumentationTests/LifecycleInstrumentationTests.swift` | Create | New test target — 3 late-init tests |
| `otel-ios-mobile/Package.swift` | Modify | Add `LifecycleInstrumentationTests` test target |
| `examples/upstream-demo-app-rn/AstronomyShopRN/ios/AstronomyShopRN/OTelMobileCallSink.swift` | Modify | `parseAutoCaptureOptions` defaults to `[.lifecycle]` |
| `examples/upstream-demo-app-rn/AstronomyShopRN/ios/AstronomyShopRN/AppDelegate.swift` | Modify | Add `-DASH0_CRASH_NOW` launch-arg crash hook (Phase 5.5) |
| `examples/upstream-demo-app-rn/AstronomyShopRN/android/app/src/main/java/com/astronomyshoprn/MainActivity.kt` | Modify | Override `onCreate` + add `gate3_crash` intent-extra crash hook (Phase 5.5) |
| `examples/upstream-demo-app-rn/AstronomyShopRN/src/App.tsx` | Modify | Remove `autoCapture: { lifecycle: false }` opt-out |
| `packages/react-native/src/index.ts` | Modify | Remove AppState install + token plumbing |
| `packages/react-native/src/bridge/types.ts` | Modify | Remove `lifecycle?: boolean` field |
| `packages/react-native/src/instrumentation/appstate.ts` | Delete | JS shim removed |
| `packages/react-native/__tests__/instr/appstate.test.ts` | Delete | Test for deleted shim |
| `docs/matchy-matchy/rn-android.md` | Modify | Status flip after manual validation |
| `docs/matchy-matchy/rn-ios.md` | Modify | Status flip after manual validation |
| `docs/matchy-matchy/README.md` | Modify | Index status row |
| `docs/epics/VALIDATION_MATRIX_EPIC.md` | Modify | Matrix cells |

---

## Phase 1 — Android: ProcessLifecycleOwner migration

### Task 1: Add `lifecycle-process` dependency

**Files:**
- Modify: `instrumentation/lifecycle/build.gradle.kts:43-53` (the `dependencies` block)

- [ ] **Step 1: Add the dependency line**

In `instrumentation/lifecycle/build.gradle.kts`, change the dependencies block from:

```kotlin
dependencies {
    api(project(":otel-android-mobile-core"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.20")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.58.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
```

to:

```kotlin
dependencies {
    api(project(":otel-android-mobile-core"))
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.20")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.58.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
```

- [ ] **Step 2: Verify dependency resolves**

Run: `(cd examples/demo-app && ./gradlew :instrumentation-lifecycle:dependencies --configuration releaseRuntimeClasspath)`
Expected: output includes `androidx.lifecycle:lifecycle-process:2.8.7` (with a transitive `lifecycle-runtime`).

- [ ] **Step 3: Commit**

```bash
git add instrumentation/lifecycle/build.gradle.kts
git commit -m "build(lifecycle): add androidx.lifecycle:lifecycle-process dep"
```

### Task 2: Add R8 keep rules to `consumer-rules.pro`

**Files:**
- Modify: `instrumentation/lifecycle/consumer-rules.pro` (currently empty)

- [ ] **Step 1: Write the keep rules**

Replace the contents of `instrumentation/lifecycle/consumer-rules.pro` with:

```proguard
# Keep ProcessLifecycleOwner so LifecycleInstrumentation can resolve it
# at runtime even under aggressive R8 minification. NoClassDefFoundError
# from a stripped Lifecycle class would silently kill all foreground/
# background telemetry — fail loudly via this rule instead.
-keep class androidx.lifecycle.ProcessLifecycleOwner { *; }
-keep class androidx.lifecycle.ProcessLifecycleInitializer { *; }
```

- [ ] **Step 2: Commit**

```bash
git add instrumentation/lifecycle/consumer-rules.pro
git commit -m "build(lifecycle): R8 keep rules for ProcessLifecycleOwner"
```

### Task 3: Write the failing test for late-init `app.start` + `app.foreground` synthesis

**Files:**
- Modify: `instrumentation/lifecycle/src/test/java/io/opentelemetry/android/mobile/instrumentation/LifecycleInstrumentationTest.kt`

- [ ] **Step 1: Add the new test class header for Robolectric**

In `LifecycleInstrumentationTest.kt`, after the existing imports and before the `class LifecycleInstrumentationTest` line, add the Robolectric runner annotation. Replace:

```kotlin
class LifecycleInstrumentationTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()
```

with:

```kotlin
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33])
class LifecycleInstrumentationTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()
```

The `sdk = [33]` matches the project's testOptions target. Robolectric provides a real `Application` and a working `androidx.lifecycle.ProcessLifecycleOwner`.

- [ ] **Step 2: Add the failing late-init test**

Append to the test class (before the closing `}`):

```kotlin
    @Test fun `install when process already started emits app start late and foreground`() {
        // Bring ProcessLifecycleOwner to STARTED before install — simulates the
        // RN useEffect / deferred-init scenario where SDK initialization runs
        // after the host Activity is already foregrounded.
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val processLifecycle = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle as androidx.lifecycle.LifecycleRegistry
        processLifecycle.currentState = androidx.lifecycle.Lifecycle.State.STARTED

        val inst = LifecycleInstrumentation()
        inst.install(app, makeContext(app))

        // Drain any pending main-thread work so observer at-attach replays land.
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val bodies = otelRule.logRecords.map { it.bodyValue?.asString() }
        assertEquals(
            listOf(MobileSemconv.APP_START, MobileSemconv.APP_FOREGROUND),
            bodies.filter { it == MobileSemconv.APP_START || it == MobileSemconv.APP_FOREGROUND },
            "Expected app.start (late) followed by app.foreground (at-attach replay), got: $bodies"
        )

        val startLog = otelRule.logRecords.first { it.bodyValue?.asString() == MobileSemconv.APP_START }
        val typeAttr = startLog.attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey("app.start.type"))
        assertEquals("instrumentation_late", typeAttr)

        val durationAttr = startLog.attributes.get(io.opentelemetry.api.common.AttributeKey.longKey("app.start.duration_ms"))
        assertTrue(durationAttr != null && durationAttr >= 0L,
            "Expected non-negative app.start.duration_ms, got $durationAttr")
    }
```

- [ ] **Step 3: Run the test — verify it fails**

Run: `(cd examples/demo-app && ./gradlew :instrumentation-lifecycle:test --tests "*install_when_process_already_started_emits_app_start_late_and_foreground*")`
Expected: FAIL — either no `app.start` log, wrong type attribute, or compile error against `instrumentation_late`. The current implementation uses `"cold"` / `"unknown"` for `app.start.type` and emits nothing on at-attach.

### Task 4: Implement the late-init synthesis path in `LifecycleInstrumentation.kt`

**Files:**
- Modify: `instrumentation/lifecycle/src/main/java/io/opentelemetry/android/mobile/instrumentation/LifecycleInstrumentation.kt`

- [ ] **Step 1: Replace the install/uninstall implementation**

Replace the entire `LifecycleInstrumentation` class body (everything from line 28 `class LifecycleInstrumentation : MobileInstrumentation {` through line 142 `}`) with:

```kotlin
@Incubating
@Supersedes("activity", "fragment")
class LifecycleInstrumentation : MobileInstrumentation {

    override val instrumentationName = "io.opentelemetry.android.mobile.lifecycle"

    private var logger: Logger? = null
    private var sessionProvider: MobileSessionProvider? = null
    private var instrumentationContext: InstrumentationContext? = null
    private var application: Application? = null
    private var activityCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var lifecycleObserver: androidx.lifecycle.DefaultLifecycleObserver? = null

    @Volatile private var firstStartLogged = false
    @Volatile private var lastBackgroundAtMs = 0L
    private var installTimeMs = 0L

    override fun install(application: Application, context: InstrumentationContext) {
        this.application = application
        this.logger = context.logger(instrumentationName)
        this.sessionProvider = context.sessionProvider
        this.instrumentationContext = context
        this.installTimeMs = System.currentTimeMillis()

        // app.start synthesis for late-init: if the process is already past
        // INITIALIZED at install-time, an Activity already exists. Emit
        // app.start with type="instrumentation_late" so the session has a
        // start event regardless of when start() was called. Sets
        // firstStartLogged so onActivityCreated below doesn't re-emit.
        emitAppStartIfLateInstall()

        // ProcessLifecycleOwner has at-attach replay: if the lifecycle is
        // already STARTED when addObserver runs, onStart fires synchronously
        // before addObserver returns. That gives late-init sessions their
        // app.foreground for free. Subsequent transitions fire as usual.
        val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                emitForeground()
            }
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                emitBackground()
            }
        }
        lifecycleObserver = observer
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(observer)

        // onActivityCreated remains the cold-start app.start signal (the only
        // event ProcessLifecycleOwner doesn't cover). For native consumers
        // calling start() from Application.onCreate, this fires when the
        // first Activity creates and emits app.start with type="cold".
        // firstStartLogged dedups against the late-install path.
        val cb = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                emitAppStartIfFirstSeen(a)
            }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        activityCallbacks = cb
        application.registerActivityLifecycleCallbacks(cb)
    }

    override fun uninstall() {
        lifecycleObserver?.let {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.removeObserver(it)
        }
        activityCallbacks?.let { application?.unregisterActivityLifecycleCallbacks(it) }
        lifecycleObserver = null
        activityCallbacks = null
        application = null
        logger = null
        sessionProvider = null
        instrumentationContext = null
    }

    private fun emitAppStartIfLateInstall() {
        if (firstStartLogged) return
        val state = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
        if (!state.isAtLeast(androidx.lifecycle.Lifecycle.State.CREATED)) return

        firstStartLogged = true
        // Process.getStartUptimeMillis is API 24+; project minSdk = 26.
        val processStart = android.os.Process.getStartUptimeMillis()
        val durationMs = (installTimeMs - processStart).coerceAtLeast(0L)
        emitLog(
            MobileSemconv.APP_START, Severity.INFO,
            Attributes.builder()
                .put("app.start.duration_ms", durationMs)
                .put("app.start.type", "instrumentation_late")
                .build()
        )
        instrumentationContext?.addBreadcrumb(
            JourneyBreadcrumb.lifecycle(
                screen = "<late_install>",
                action = MobileSemconv.APP_START,
                attributes = mapOf("duration_ms" to durationMs.toString())
            )
        )
    }

    private fun emitAppStartIfFirstSeen(a: Activity) {
        if (firstStartLogged) return
        firstStartLogged = true
        val durationMs = System.currentTimeMillis() - installTimeMs
        emitLog(
            MobileSemconv.APP_START, Severity.INFO,
            Attributes.builder()
                .put("app.start.duration_ms", durationMs)
                .put("app.start.type", "cold")
                .build()
        )
        instrumentationContext?.addBreadcrumb(
            JourneyBreadcrumb.lifecycle(
                screen = a.javaClass.simpleName,
                action = MobileSemconv.APP_START,
                attributes = mapOf("duration_ms" to durationMs.toString())
            )
        )
    }

    private fun emitForeground() {
        val ctx = instrumentationContext ?: return
        val now = System.currentTimeMillis()
        val bgDuration = if (lastBackgroundAtMs > 0L) now - lastBackgroundAtMs else 0L
        val renewed = ctx.sessionProvider.onAppForeground(now)
        emitLog(
            MobileSemconv.APP_FOREGROUND, Severity.INFO,
            Attributes.builder()
                .put(MobileSemconv.SESSION_RENEWED, renewed)
                .put(MobileSemconv.BACKGROUND_DURATION_MS, bgDuration)
                .build()
        )
        ctx.addBreadcrumb(
            JourneyBreadcrumb.lifecycle(
                screen = "<process>",
                action = MobileSemconv.APP_FOREGROUND,
                attributes = mapOf("background_duration_ms" to bgDuration.toString())
            )
        )
    }

    private fun emitBackground() {
        val ctx = instrumentationContext ?: return
        lastBackgroundAtMs = System.currentTimeMillis()
        ctx.sessionProvider.onAppBackground(lastBackgroundAtMs)
        emitLog(MobileSemconv.APP_BACKGROUND, Severity.INFO)
        ctx.addBreadcrumb(
            JourneyBreadcrumb.lifecycle(
                screen = "<process>",
                action = MobileSemconv.APP_BACKGROUND
            )
        )
    }

    private fun emitLog(name: String, severity: Severity, extra: Attributes = Attributes.empty()) {
        val sp = sessionProvider ?: return
        logger?.logRecordBuilder()
            ?.setBody(name)
            ?.setSeverity(severity)
            ?.setAllAttributes(
                Attributes.builder()
                    .put(MobileSemconv.SESSION_ID, sp.getSessionId())
                    .put(MobileSemconv.VIEW_ID, sp.getViewId())
                    .putAll(extra)
                    .build()
            )
            ?.emit()
    }
}
```

- [ ] **Step 2: Run the late-init test — verify it passes**

Run: `(cd examples/demo-app && ./gradlew :instrumentation-lifecycle:test --tests "*install_when_process_already_started_emits_app_start_late_and_foreground*")`
Expected: PASS.

### Task 5: Rewrite the existing foreground/background tests for ProcessLifecycleOwner

**Files:**
- Modify: `instrumentation/lifecycle/src/test/java/io/opentelemetry/android/mobile/instrumentation/LifecycleInstrumentationTest.kt`

- [ ] **Step 1: Replace the existing fg/bg tests**

In `LifecycleInstrumentationTest.kt`, find the two existing tests that drive activity callbacks directly:

```kotlin
    @Test fun `app foreground log emitted when first activity starts`() {
        val app = mockk<Application>(relaxed = true)
        val callbackSlot = slot<Application.ActivityLifecycleCallbacks>()
        every { app.registerActivityLifecycleCallbacks(capture(callbackSlot)) } just runs

        val inst = LifecycleInstrumentation()
        inst.install(app, makeContext(app))
        callbackSlot.captured.onActivityStarted(mockk(relaxed = true))

        val logs = otelRule.logRecords
        assertTrue(logs.any { it.bodyValue?.asString() == MobileSemconv.APP_FOREGROUND },
            "Expected app.foreground log")
    }

    @Test fun `app background log emitted when last activity stops`() {
        val app = mockk<Application>(relaxed = true)
        val callbackSlot = slot<Application.ActivityLifecycleCallbacks>()
        every { app.registerActivityLifecycleCallbacks(capture(callbackSlot)) } just runs

        val inst = LifecycleInstrumentation()
        inst.install(app, makeContext(app))

        val cb = callbackSlot.captured
        cb.onActivityStarted(mockk(relaxed = true))    // activeActivities = 1
        cb.onActivityStopped(mockk(relaxed = true))   // activeActivities = 0 → background
        val logs = otelRule.logRecords
        assertTrue(logs.any { it.bodyValue?.asString() == MobileSemconv.APP_BACKGROUND },
            "Expected app.background log")
    }
```

Replace them with these two ProcessLifecycleOwner-driven tests:

```kotlin
    @Test fun `process foreground emits app foreground`() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val processLifecycle = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle as androidx.lifecycle.LifecycleRegistry
        // Start in CREATED so addObserver doesn't replay onStart.
        processLifecycle.currentState = androidx.lifecycle.Lifecycle.State.CREATED

        val inst = LifecycleInstrumentation()
        inst.install(app, makeContext(app))
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        // Drain any logs emitted by install (none expected — process is CREATED, not STARTED).
        otelRule.logRecords.clear()

        // Drive the foreground transition.
        processLifecycle.currentState = androidx.lifecycle.Lifecycle.State.STARTED
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val fgLogs = otelRule.logRecords.filter { it.bodyValue?.asString() == MobileSemconv.APP_FOREGROUND }
        assertEquals(1, fgLogs.size, "Expected exactly 1 app.foreground")
    }

    @Test fun `process background emits app background`() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val processLifecycle = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle as androidx.lifecycle.LifecycleRegistry
        processLifecycle.currentState = androidx.lifecycle.Lifecycle.State.STARTED

        val inst = LifecycleInstrumentation()
        inst.install(app, makeContext(app))
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        otelRule.logRecords.clear()

        processLifecycle.currentState = androidx.lifecycle.Lifecycle.State.CREATED
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val bgLogs = otelRule.logRecords.filter { it.bodyValue?.asString() == MobileSemconv.APP_BACKGROUND }
        assertEquals(1, bgLogs.size, "Expected exactly 1 app.background")
    }
```

- [ ] **Step 2: Run all lifecycle tests — verify all pass**

Run: `(cd examples/demo-app && ./gradlew :instrumentation-lifecycle:test)`
Expected: PASS — all 8 tests (5 retained, 2 rewritten, 1 new from Task 3) green.

### Task 6: Add `uninstall_removes_observer` test

**Files:**
- Modify: `instrumentation/lifecycle/src/test/java/io/opentelemetry/android/mobile/instrumentation/LifecycleInstrumentationTest.kt`

- [ ] **Step 1: Append the test**

Add to the test class:

```kotlin
    @Test fun `uninstall removes ProcessLifecycleOwner observer`() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val processLifecycle = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle as androidx.lifecycle.LifecycleRegistry
        processLifecycle.currentState = androidx.lifecycle.Lifecycle.State.CREATED

        val inst = LifecycleInstrumentation()
        inst.install(app, makeContext(app))
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        inst.uninstall()
        otelRule.logRecords.clear()

        // Driving lifecycle after uninstall should produce no logs.
        processLifecycle.currentState = androidx.lifecycle.Lifecycle.State.STARTED
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val fgLogs = otelRule.logRecords.filter { it.bodyValue?.asString() == MobileSemconv.APP_FOREGROUND }
        assertEquals(0, fgLogs.size, "uninstall() should remove the observer; got $fgLogs")
    }
```

- [ ] **Step 2: Run test — verify it passes**

Run: `(cd examples/demo-app && ./gradlew :instrumentation-lifecycle:test --tests "*uninstall_removes_ProcessLifecycleOwner_observer*")`
Expected: PASS.

- [ ] **Step 3: Commit Phase 1**

```bash
git add instrumentation/lifecycle/src/main/java/io/opentelemetry/android/mobile/instrumentation/LifecycleInstrumentation.kt \
        instrumentation/lifecycle/src/test/java/io/opentelemetry/android/mobile/instrumentation/LifecycleInstrumentationTest.kt
git commit -m "fix(android-lifecycle): migrate to ProcessLifecycleOwner, fix late-init race

The activity-counter pattern in LifecycleInstrumentation broke when
OTelMobile.start() was invoked after Application.onCreate (e.g., RN's
useEffect): the host Activity was already started by the time the
ActivityLifecycleCallbacks registered, so the counter ran 0 → -1 on
first stop and -1 → 0 on first start, never satisfying the == 0 / == 1
predicates that gate emission.

Migrate to androidx.lifecycle.ProcessLifecycleOwner, which observes
process-level foreground/background state with a built-in 700ms debounce
and at-attach replay. When the lifecycle is already STARTED at install
time, addObserver synchronously replays onCreate/onStart on the observer,
giving late-init sessions their app.foreground for free.

The cold-start app.start path (onActivityCreated) is retained as-is —
ProcessLifecycleOwner has no equivalent for 'first activity created'.
Late-init synthesizes app.start with type=\"instrumentation_late\" anchored
to Process.getStartUptimeMillis() so each session has exactly one
app.start regardless of when start() was called.

Closes Gate 1 for RN Android."
```

---

## Phase 2 — iOS: late-init synthesis

### Task 7: Create the iOS test target

**Files:**
- Modify: `otel-ios-mobile/Package.swift` (add `LifecycleInstrumentationTests` test target)
- Create: `otel-ios-mobile/Tests/LifecycleInstrumentationTests/LifecycleInstrumentationTests.swift` (placeholder so SwiftPM can resolve)

- [ ] **Step 1: Add the test target to Package.swift**

In `otel-ios-mobile/Package.swift`, find the test targets section (the existing `.testTarget(name: "ErrorsInstrumentationTests", ...)` is the template). Add a new test target before the closing `]` of the targets array:

```swift
        .testTarget(
            name: "LifecycleInstrumentationTests",
            dependencies: [
                "LifecycleInstrumentation",
                .product(name: "OpenTelemetrySdk", package: "opentelemetry-swift-core"),
            ]
        ),
```

- [ ] **Step 2: Create a placeholder test file**

Create `otel-ios-mobile/Tests/LifecycleInstrumentationTests/LifecycleInstrumentationTests.swift` with:

```swift
import Foundation
import Testing
@testable import LifecycleInstrumentation
import OpenTelemetryApi
import OpenTelemetrySdk

#if canImport(UIKit) && (os(iOS) || os(tvOS))
import UIKit

@Suite("LifecycleInstrumentation late-init")
struct LifecycleInstrumentationLateInitTests {
    // Tests will be added in Tasks 9-11.
}
#endif
```

- [ ] **Step 3: Verify SwiftPM accepts the new target**

Run: `(cd otel-ios-mobile && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift package describe 2>&1 | grep -A1 "LifecycleInstrumentationTests")`
Expected: shows the target name + its dependencies in the package layout.

Then: `(cd otel-ios-mobile && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift build)`
Expected: builds the whole package (including the new test target's main module) without errors.

- [ ] **Step 4: Commit**

```bash
git add otel-ios-mobile/Package.swift otel-ios-mobile/Tests/LifecycleInstrumentationTests/LifecycleInstrumentationTests.swift
git commit -m "test(ios-lifecycle): scaffold LifecycleInstrumentationTests target"
```

### Task 8: Write the failing late-init synthesis test

**Files:**
- Modify: `otel-ios-mobile/Tests/LifecycleInstrumentationTests/LifecycleInstrumentationTests.swift`

- [ ] **Step 1: Add a recording-logger helper and the first failing test**

Replace the file contents with:

```swift
import Foundation
import Testing
@testable import LifecycleInstrumentation
import OpenTelemetryApi
import OpenTelemetrySdk

#if canImport(UIKit) && (os(iOS) || os(tvOS))
import UIKit

/// Captures emitted log records so tests can assert on shape.
final class RecordingLogger: Logger, @unchecked Sendable {
    private let lock = NSLock()
    private var _records: [(body: String, attributes: [String: AttributeValue])] = []
    var records: [(body: String, attributes: [String: AttributeValue])] {
        lock.lock(); defer { lock.unlock() }
        return _records
    }

    func logRecordBuilder() -> LogRecordBuilder {
        return RecordingLogRecordBuilder(sink: self)
    }

    fileprivate func record(body: String, attributes: [String: AttributeValue]) {
        lock.lock(); defer { lock.unlock() }
        _records.append((body, attributes))
    }
}

final class RecordingLogRecordBuilder: LogRecordBuilder {
    private weak var sink: RecordingLogger?
    private var body: String = ""
    private var attributes: [String: AttributeValue] = [:]
    private var severity: Severity = .info

    init(sink: RecordingLogger) { self.sink = sink }

    func setTimestamp(_ timestamp: Date) -> Self { self }
    func setObservedTimestamp(_ timestamp: Date) -> Self { self }
    func setSpanContext(_ spanContext: SpanContext) -> Self { self }
    func setSeverity(_ severity: Severity) -> Self { self.severity = severity; return self }
    func setBody(_ body: AttributeValue) -> Self {
        if case .string(let s) = body { self.body = s }
        return self
    }
    func setAttributes(_ attributes: [String: AttributeValue]) -> Self {
        self.attributes = attributes; return self
    }
    func setAttribute(_ key: String, _ value: AttributeValue) -> Self {
        attributes[key] = value; return self
    }
    func emit() {
        sink?.record(body: body, attributes: attributes)
    }
}

@Suite("LifecycleInstrumentation late-init")
struct LifecycleInstrumentationLateInitTests {

    @Test("install when app already active emits late foreground")
    func installWhenAppAlreadyActiveEmitsLateForeground() async throws {
        let logger = RecordingLogger()
        let inst = LifecycleInstrumentation(applicationStateProvider: { .active })
        inst.install(tracer: nil, logger: logger)

        // Allow the DispatchQueue.main.async synthesis path to run.
        try await Task.sleep(nanoseconds: 50_000_000) // 50ms

        let foregrounds = logger.records.filter { $0.body == "app.foreground" }
        #expect(foregrounds.count == 1, "Expected 1 app.foreground, got \(foregrounds.count)")
        #expect(foregrounds.first?.attributes["app.foreground.type"] == .string("instrumentation_late"))

        inst.uninstall()
    }
}
#endif
```

- [ ] **Step 2: Run test — verify it fails**

Run: `(cd otel-ios-mobile && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter LifecycleInstrumentationLateInitTests.installWhenAppAlreadyActiveEmitsLateForeground)`
Expected: FAIL — either compile error (no `init(applicationStateProvider:)` exists yet, no `tracer: nil` overload, or `app.foreground.type` attribute missing).

### Task 9: Implement the iOS late-init synthesis

**Files:**
- Modify: `otel-ios-mobile/Sources/LifecycleInstrumentation/LifecycleInstrumentation.swift`

- [ ] **Step 1: Add the test seam + late-init logic**

Replace the entire file contents with:

```swift
import Foundation
import OpenTelemetryApi
import OTelMobileCore
#if canImport(UIKit)
import UIKit
#endif

/// Auto-instruments app lifecycle transitions.
///
/// Emits OTel log records (plus spans for foreground/background windows) for:
/// - `app.launch` — first install call
/// - `app.foreground` — UIApplication.didBecomeActive (or synthesized if installed
///   while app is already active; see `applicationStateProvider`)
/// - `app.background` — UIApplication.didEnterBackground
/// - `app.will_terminate` — UIApplication.willTerminate (best-effort)
/// - `app.memory_warning` — UIApplication.didReceiveMemoryWarning
///
/// Usage:
/// ```swift
/// let mobile = try OTelMobile.start(config: config)
/// if let tracer = mobile.tracer, let logger = mobile.logger {
///     LifecycleInstrumentation.shared.install(tracer: tracer, logger: logger)
/// }
/// ```
public final class LifecycleInstrumentation: @unchecked Sendable {
    public static let shared = LifecycleInstrumentation()

#if canImport(UIKit) && (os(iOS) || os(tvOS))
    typealias ApplicationStateProvider = @Sendable () -> UIApplication.State
    private let applicationStateProvider: ApplicationStateProvider
#endif

    private let lock = NSLock()
    private var installed = false
    private var tracer: Tracer?
    private var logger: Logger?
    private var observers: [NSObjectProtocol] = []
    private var foregroundSpan: Span?
    // See comment on the original implementation — both UIApplication.* and
    // UIScene.* notifications are observed; this flag dedups across them
    // AND across the late-init synthesis path.
    private var foregroundActive = false

    private init() {
#if canImport(UIKit) && (os(iOS) || os(tvOS))
        self.applicationStateProvider = { @Sendable in
            // Read on main; this initializer doesn't run on main, so wrap.
            // Production callers always use this default which dispatches
            // to main inside install() before invoking.
            UIApplication.shared.applicationState
        }
#endif
    }

#if canImport(UIKit) && (os(iOS) || os(tvOS))
    /// Test-only init. Internal so it's accessible from `@testable import`
    /// in test targets but not from external consumers.
    internal init(applicationStateProvider: @escaping ApplicationStateProvider) {
        self.applicationStateProvider = applicationStateProvider
    }
#endif

    public func install(tracer: Tracer?, logger: Logger) {
        lock.lock()
        if installed {
            lock.unlock()
            return
        }
        installed = true
        self.tracer = tracer
        self.logger = logger
        lock.unlock()

        // IMPORTANT: emit OUTSIDE the lock. emit() re-acquires the lock to
        // read self.logger; NSLock is non-reentrant on Darwin.
        emit(event: "app.launch")

        #if canImport(UIKit) && (os(iOS) || os(tvOS))
        let nc = NotificationCenter.default
        observers.append(nc.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil, queue: nil
        ) { [weak self] _ in self?.handleForeground() })

        observers.append(nc.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil, queue: nil
        ) { [weak self] _ in self?.handleBackground() })

        observers.append(nc.addObserver(
            forName: UIScene.didActivateNotification,
            object: nil, queue: nil
        ) { [weak self] _ in self?.handleForeground() })

        observers.append(nc.addObserver(
            forName: UIScene.didEnterBackgroundNotification,
            object: nil, queue: nil
        ) { [weak self] _ in self?.handleBackground() })

        observers.append(nc.addObserver(
            forName: UIApplication.willTerminateNotification,
            object: nil, queue: nil
        ) { [weak self] _ in self?.emit(event: "app.will_terminate") })

        observers.append(nc.addObserver(
            forName: UIApplication.didReceiveMemoryWarningNotification,
            object: nil, queue: nil
        ) { [weak self] _ in self?.emit(event: "app.memory_warning", severity: .warn) })

        // Late-init synthesis: NotificationCenter has no at-attach replay,
        // so if the app is already foregrounded when install() runs (RN
        // useEffect case, or any deferred init), we must synthesize the
        // initial foreground event. Dispatched to main because
        // UIApplication state must be read on the main thread.
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            if self.applicationStateProvider() == .active {
                self.handleForeground(lateInstall: true)
            }
        }
        #endif
    }

    public func uninstall() {
        lock.lock(); defer { lock.unlock() }
        installed = false
        let nc = NotificationCenter.default
        for o in observers { nc.removeObserver(o) }
        observers.removeAll()
        foregroundSpan?.end()
        foregroundSpan = nil
        foregroundActive = false
    }

    // MARK: - Handlers

    private func handleForeground(lateInstall: Bool = false) {
        lock.lock()
        if foregroundActive {
            lock.unlock()
            return
        }
        foregroundActive = true
        let t = tracer
        lock.unlock()
        emit(
            event: "app.foreground",
            attributes: ["app.foreground.type": .string(lateInstall ? "instrumentation_late" : "natural")]
        )
        if let tracer = t {
            let span = tracer.spanBuilder(spanName: "app.foreground_session")
                .setSpanKind(spanKind: .internal)
                .startSpan()
            lock.lock()
            foregroundSpan?.end()
            foregroundSpan = span
            lock.unlock()
        }
    }

    private func handleBackground() {
        lock.lock()
        if !foregroundActive {
            lock.unlock()
            return
        }
        foregroundActive = false
        let span = foregroundSpan
        foregroundSpan = nil
        lock.unlock()
        emit(event: "app.background")
        span?.end()
    }

    private func emit(event: String, severity: Severity = .info, attributes: [String: AttributeValue] = [:]) {
        lock.lock()
        let logger = self.logger
        lock.unlock()
        guard let logger = logger else { return }
        var attrs: [String: AttributeValue] = ["event.name": .string(event)]
        for (k, v) in attributes { attrs[k] = v }
        logger.logRecordBuilder()
            .setBody(AttributeValue.string(event))
            .setSeverity(severity)
            .setAttributes(attrs)
            .emit()
    }
}
```

- [ ] **Step 2: Run the late-init test — verify it passes**

Run: `(cd otel-ios-mobile && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter LifecycleInstrumentationLateInitTests.installWhenAppAlreadyActiveEmitsLateForeground)`
Expected: PASS.

### Task 10: Add the dedup test

**Files:**
- Modify: `otel-ios-mobile/Tests/LifecycleInstrumentationTests/LifecycleInstrumentationTests.swift`

- [ ] **Step 1: Append the dedup test inside the `@Suite` struct**

Add inside `LifecycleInstrumentationLateInitTests`:

```swift
    @Test("late install does not double-emit when natural didBecomeActive arrives")
    func lateInstallDoesNotDoubleEmitOnNaturalDidBecomeActive() async throws {
        let logger = RecordingLogger()
        let inst = LifecycleInstrumentation(applicationStateProvider: { .active })
        inst.install(tracer: nil, logger: logger)
        try await Task.sleep(nanoseconds: 50_000_000)

        // Synthesis already emitted. Now post a natural didBecomeActive.
        await MainActor.run {
            NotificationCenter.default.post(
                name: UIApplication.didBecomeActiveNotification, object: nil
            )
        }
        try await Task.sleep(nanoseconds: 50_000_000)

        let foregrounds = logger.records.filter { $0.body == "app.foreground" }
        #expect(foregrounds.count == 1, "foregroundActive dedup should suppress the natural didBecomeActive after late-init; got \(foregrounds.count)")

        // Now do a real bg → fg cycle and confirm one more foreground (natural).
        await MainActor.run {
            NotificationCenter.default.post(
                name: UIApplication.didEnterBackgroundNotification, object: nil
            )
            NotificationCenter.default.post(
                name: UIApplication.didBecomeActiveNotification, object: nil
            )
        }
        try await Task.sleep(nanoseconds: 50_000_000)

        let foregrounds2 = logger.records.filter { $0.body == "app.foreground" }
        #expect(foregrounds2.count == 2, "After bg→fg cycle: expected 2 total foregrounds, got \(foregrounds2.count)")
        #expect(foregrounds2.last?.attributes["app.foreground.type"] == .string("natural"))

        inst.uninstall()
    }
```

- [ ] **Step 2: Run test — verify it passes**

Run: `(cd otel-ios-mobile && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter LifecycleInstrumentationLateInitTests.lateInstallDoesNotDoubleEmitOnNaturalDidBecomeActive)`
Expected: PASS.

### Task 11: Add the inactive-app test

**Files:**
- Modify: `otel-ios-mobile/Tests/LifecycleInstrumentationTests/LifecycleInstrumentationTests.swift`

- [ ] **Step 1: Append the inactive-state test**

Add inside `LifecycleInstrumentationLateInitTests`:

```swift
    @Test("install when app inactive does not emit foreground", arguments: [UIApplication.State.inactive, UIApplication.State.background])
    func installWhenAppInactiveDoesNotEmitForeground(state: UIApplication.State) async throws {
        let logger = RecordingLogger()
        let inst = LifecycleInstrumentation(applicationStateProvider: { state })
        inst.install(tracer: nil, logger: logger)
        try await Task.sleep(nanoseconds: 50_000_000)

        let foregrounds = logger.records.filter { $0.body == "app.foreground" }
        #expect(foregrounds.count == 0, "Expected no app.foreground when applicationState=\(state); got \(foregrounds.count)")

        // app.launch should still fire regardless.
        let launches = logger.records.filter { $0.body == "app.launch" }
        #expect(launches.count == 1, "app.launch should fire unconditionally")

        inst.uninstall()
    }
```

- [ ] **Step 2: Run all iOS lifecycle tests — verify all pass**

Run: `(cd otel-ios-mobile && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test --filter LifecycleInstrumentationLateInitTests)`
Expected: PASS — all 3 late-init tests green (the inactive test runs as 2 parameterized cases).

- [ ] **Step 3: Run the full iOS test suite — verify nothing else regressed**

Run: `(cd otel-ios-mobile && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test)`
Expected: PASS — all 309+ tests + the new 3 tests green.

- [ ] **Step 4: Commit Phase 2**

```bash
git add otel-ios-mobile/Sources/LifecycleInstrumentation/LifecycleInstrumentation.swift \
        otel-ios-mobile/Tests/LifecycleInstrumentationTests/LifecycleInstrumentationTests.swift
git commit -m "fix(ios-lifecycle): synthesize app.foreground when installed in active state

iOS NotificationCenter has no at-attach replay (unlike Android's
LifecycleRegistry). UIApplication.didBecomeActiveNotification fires once
per foreground transition; if the SDK installs after that notification
already fired (RN's useEffect case, or any deferred init), no initial
app.foreground lands.

Add an applicationStateProvider closure (default reads
UIApplication.shared.applicationState; tests inject) and synthesize the
initial app.foreground if the app is .active at install time. The
existing foregroundActive flag dedups against a near-simultaneous natural
didBecomeActive, so at most one foreground per session-start lands.

Tag emissions with app.foreground.type='instrumentation_late' for
synthesized events vs 'natural' for the observer-driven path."
```

---

## Phase 3 — iOS RN bridge: enable `.lifecycle` by default

### Task 12: Update `parseAutoCaptureOptions` in the RN bridge

**Files:**
- Modify: `examples/upstream-demo-app-rn/AstronomyShopRN/ios/AstronomyShopRN/OTelMobileCallSink.swift`

- [ ] **Step 1: Find the existing `parseAutoCaptureOptions`**

Locate the function in `OTelMobileCallSink.swift` (around line 240-260 per spec). It currently looks like:

```swift
private static func parseAutoCaptureOptions(_ tokens: [String]) -> AutoCaptureOptions {
    var opts: AutoCaptureOptions = []
    for tok in tokens {
        switch tok {
        case "tap":         opts.insert(.tap)
        case "scroll":      opts.insert(.scroll)
        case "lifecycle":   opts.insert(.lifecycle)
        case "screen":      opts.insert(.screen)
        case "network":     opts.insert(.network)
        case "errors":      opts.insert(.errors)
        case "freeze":      opts.insert(.freeze)
        case "vitals":      opts.insert(.vitals)
        default: break
        }
    }
    return opts
}
```

- [ ] **Step 2: Change the default and the comment block above it**

Replace the function (and update the comment block at lines ~63-71 that explains why the default was `.none`) so that:
1. The function defaults to `[.lifecycle]` instead of empty.
2. The block comment is updated to reflect that lifecycle is on-by-default because NotificationCenter observers are safe with the RN event loop.

The replacement:

```swift
// Translate the bridge's string tokens into AutoCaptureOptions.
//
// Lifecycle is on by default — it observes UIApplication / UIScene
// notifications via NotificationCenter, which doesn't touch the JS
// event loop, doesn't swizzle anything, and doesn't chain fatal
// handlers. Safe in RN.
//
// Other capabilities (network URLProtocol swizzle, NSException/signal
// handlers, screen swizzle) DO collide with the RN new-arch event loop
// and remain off-by-default. Apps that want them must opt in per
// capability from JS via autoCapture: { network: true } etc.
private static func parseAutoCaptureOptions(_ tokens: [String]) -> AutoCaptureOptions {
    var opts: AutoCaptureOptions = [.lifecycle]
    for tok in tokens {
        switch tok {
        case "tap":         opts.insert(.tap)
        case "scroll":      opts.insert(.scroll)
        case "lifecycle":   opts.insert(.lifecycle)   // explicit token is no-op now (already set)
        case "screen":      opts.insert(.screen)
        case "network":     opts.insert(.network)
        case "errors":      opts.insert(.errors)
        case "freeze":      opts.insert(.freeze)
        case "vitals":      opts.insert(.vitals)
        default: break
        }
    }
    return opts
}
```

- [ ] **Step 3: Commit**

```bash
git add examples/upstream-demo-app-rn/AstronomyShopRN/ios/AstronomyShopRN/OTelMobileCallSink.swift
git commit -m "feat(rn-ios): default native .lifecycle on in OTelMobileCallSink

NotificationCenter observers don't touch the JS event loop and don't
swizzle anything, so they're safe with the RN new-arch dispatch.
Network URLProtocol swizzle, NSException/signal handlers, and screen
swizzle still default to .none (those genuinely conflict with RN).
Closes the iOS half of Gate 1 for RN."
```

---

## Phase 4 — JS shim cleanup + demo + types

### Task 13: Remove the JS-side AppState shim from index.ts

**Files:**
- Modify: `packages/react-native/src/index.ts`

- [ ] **Step 1: Remove the import**

In `packages/react-native/src/index.ts`, find and delete this import line near the top:

```ts
import { installAppStateInstrumentation } from './instrumentation/appstate';
```

- [ ] **Step 2: Remove the install call**

Find the block (around line 213 per the spec):

```ts
    if (auto.lifecycle !== false) {
      autoInstrUninstallers.push(installAppStateInstrumentation());
    }
```

Delete the entire `if` block.

- [ ] **Step 3: Remove `'lifecycle'` from `NATIVE_AUTO_CAPTURE_FLAGS`**

Find the array (around line 374):

```ts
const NATIVE_AUTO_CAPTURE_FLAGS: ReadonlyArray<[AutoCaptureFlag, string]> = [
  ['network', 'network'],
  ['errors', 'errors'],
  ['lifecycle', 'lifecycle'],
  ['tap', 'tap'],
  ['scroll', 'scroll'],
  ['textInput', 'textInput'],
  ['screen', 'screen'],
  ['freeze', 'freeze'],
  ['vitals', 'vitals'],
  ['deviceStats', 'deviceStats'],
];
```

Remove the `['lifecycle', 'lifecycle'],` row.

### Task 14: Remove the `lifecycle?` field from `bridge/types.ts`

**Files:**
- Modify: `packages/react-native/src/bridge/types.ts`

- [ ] **Step 1: Find and remove the `lifecycle` field**

In `packages/react-native/src/bridge/types.ts`, find the `autoCapture` object type (around line 48 per the spec). Remove the `lifecycle?: boolean;` field. Also remove any documentation comment that references it.

The field is described as e.g.:

```ts
  autoCapture?: {
    network?: boolean;
    errors?: boolean;
    lifecycle?: boolean;     // ← remove this line + its leading comment if any
    // ... rest
  };
```

After removal, run typecheck — see Task 15.

### Task 15: Delete the JS shim files + verify the JS package compiles

**Files:**
- Delete: `packages/react-native/src/instrumentation/appstate.ts`
- Delete: `packages/react-native/__tests__/instr/appstate.test.ts`

- [ ] **Step 1: Delete the shim and its test**

```bash
rm packages/react-native/src/instrumentation/appstate.ts
rm packages/react-native/__tests__/instr/appstate.test.ts
```

- [ ] **Step 2: Run typecheck**

Run: `(cd packages/react-native && npx tsc --noEmit)`
Expected: clean — no references to `installAppStateInstrumentation` or the deleted `lifecycle?` field remain.

- [ ] **Step 3: Run the RN package test suite**

Run: `(cd packages/react-native && npx jest)`
Expected: PASS — all suites green, lower test count than before (the deleted `appstate.test.ts` is gone).

- [ ] **Step 4: Commit**

```bash
git add packages/react-native/src/index.ts \
        packages/react-native/src/bridge/types.ts
git rm packages/react-native/src/instrumentation/appstate.ts \
       packages/react-native/__tests__/instr/appstate.test.ts
git commit -m "refactor(rn): remove JS-side AppState shim + autoCapture.lifecycle

The JS-side installAppStateInstrumentation shim trips the RN 0.85
new-arch TurboModule init race and was never reliable. Native lifecycle
instrumentation (Android ProcessLifecycleOwner, iOS NotificationCenter
with applicationState late-init synthesis) gives us the same signal
without the race. Delete the shim, its test, and the autoCapture.lifecycle
field that gated it.

Per the project's no-tech-debt directive (and zero external consumers
since the SDK isn't published yet), removing the field rather than
deprecating it is the right call. Document in the changelog."
```

### Task 16: Remove the demo's `autoCapture: { lifecycle: false }` opt-out

**Files:**
- Modify: `examples/upstream-demo-app-rn/AstronomyShopRN/src/App.tsx`

- [ ] **Step 1: Find the opt-out + investigation comment**

In `App.tsx`, locate the `Dash0Mobile.start({...})` call (around line 30-50). The current shape includes a multi-line investigation comment followed by `autoCapture: { lifecycle: false }`:

```tsx
    Dash0Mobile.start({
      serviceName: otelConfig.serviceName,
      serviceVersion: otelConfig.serviceVersion,
      endpoint: endpointForPlatform(otelConfig.endpoint),
      authToken: otelConfig.authToken,
      dataset: otelConfig.dataset,
      // RN 0.85 new-arch TurboModule init order makes `AppState` unreliable
      // inside the first useEffect. Disable lifecycle auto-capture until
      // the init-order story stabilizes upstream.
      //
      // 2026-04-24 re-investigated: tried bumping the JS-side defer to
      // 1500ms. Still redboxes — the `Invariant Violation` from
      // `TurboModuleRegistry.getEnforcing('PlatformConstants')` fires
      // BEFORE the defer's setTimeout callback runs, and RN's `RCTFatal`
      // converts the JS throw to a native fatal that bypasses our
      // try/catch. This is upstream RN, not a defer-tuning problem.
      // Leave `lifecycle: false` in place. Gate 1 stays 🔴 with this
      // documented architectural cause.
      autoCapture: { lifecycle: false },
    }).catch(() => {
      // Non-RN runtime (tests, SSR) — safe to ignore
    });
```

- [ ] **Step 2: Remove the comment block + the opt-out line**

Replace the call with:

```tsx
    Dash0Mobile.start({
      serviceName: otelConfig.serviceName,
      serviceVersion: otelConfig.serviceVersion,
      endpoint: endpointForPlatform(otelConfig.endpoint),
      authToken: otelConfig.authToken,
      dataset: otelConfig.dataset,
      // Lifecycle auto-capture is native on both platforms (Android
      // ProcessLifecycleOwner + iOS NotificationCenter) — see Gate 1
      // closure 2026-04-29.
    }).catch(() => {
      // Non-RN runtime (tests, SSR) — safe to ignore
    });
```

- [ ] **Step 3: Run the demo's typecheck + tests**

Run: `(cd examples/upstream-demo-app-rn/AstronomyShopRN && npx tsc --noEmit && npx jest)`
Expected: PASS — typecheck clean (the field-deletion in Task 14 means the demo couldn't have referenced it anyway), all demo Jest tests green.

- [ ] **Step 4: Commit**

```bash
git add examples/upstream-demo-app-rn/AstronomyShopRN/src/App.tsx
git commit -m "feat(rn-demo): drop autoCapture lifecycle opt-out (native is now reliable)"
```

---

## Phase 5 — Manual on-device validation

### Task 17: Republish Android SDK to mavenLocal

**Files:** none (build action only)

- [ ] **Step 1: Publish updated lifecycle module to mavenLocal**

Run: `(cd examples/demo-app && ./gradlew :instrumentation-lifecycle:publishToMavenLocal :otel-android-mobile:publishToMavenLocal :otel-android-mobile-core:publishToMavenLocal)`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Verify the artifact has the new code**

Run: `unzip -p ~/.m2/repository/io/opentelemetry/android/instrumentation-lifecycle/*/instrumentation-lifecycle-*.aar classes.jar | jar -t 2>/dev/null | grep -i lifecycle | head`
Expected: includes `LifecycleInstrumentation.class`.

### Task 18: Rebuild RN Android demo + reinstall

**Files:** none (build/install action)

- [ ] **Step 1: Invalidate gradle bundle cache**

From the project root (`mobile-otel/`), run: `touch examples/upstream-demo-app-rn/AstronomyShopRN/index.js`

This works around a known gotcha: `createBundleReleaseJsAndAssets` doesn't track external `require()`'d JSON, so editing `otel-config.json` (or any source pulled in transitively) doesn't invalidate the gradle cache. Touching `index.js` forces a re-bundle. See [feedback_rn_gradle_bundle_cache.md](../../../.claude/projects/-Users-barrysolomon-Projects-Dash0-mobile-observability/memory/feedback_rn_gradle_bundle_cache.md).

- [ ] **Step 2: Rebuild the JS bundle**

Run:

```bash
( cd examples/upstream-demo-app-rn/AstronomyShopRN \
    && /opt/homebrew/bin/node node_modules/.bin/react-native bundle \
        --platform android --dev false --entry-file index.js \
        --bundle-output android/app/src/main/assets/index.android.bundle \
        --assets-dest android/app/src/main/res )
```

Expected: bundle file written, no errors.

- [ ] **Step 3: Build + install Release APK**

Run: `(cd examples/upstream-demo-app-rn/AstronomyShopRN/android && ./gradlew assembleRelease)`
Expected: BUILD SUCCESSFUL.

Then: `adb install -r -d examples/upstream-demo-app-rn/AstronomyShopRN/android/app/build/outputs/apk/release/app-release.apk`
Expected: `Success`.

### Task 19: Run the RN Android matchy-matchy Gate 1 scenario

**Files:** none (manual validation)

- [ ] **Step 1: Boot a Pixel_7 emulator if not running**

Run: `adb devices` — if empty, start with `nohup emulator -avd Pixel_7 -no-snapshot-save > /tmp/emu1.log 2>&1 &` and wait for boot.

- [ ] **Step 2: Activate the Dash0 profile + capture baseline**

Run: `dash0 config profiles activate mobile-test`

Then:

```bash
dash0 -X logs query --filter 'service.name is otel-rn-astronomy-shop' --from now-5m -o json > /tmp/baseline-rn-android.json
python3 -c '
import json
d = json.load(open("/tmp/baseline-rn-android.json"))
n = sum(len(sl.get("logRecords", [])) for rl in d.get("resourceLogs", []) for sl in rl.get("scopeLogs", []))
print(f"baseline lifecycle logs (last 5m): {n}")'
```

Expected: low number. Leftover lifecycle events from prior runs (within the last 5 minutes) are fine — the post-scenario query in step 4 uses an even wider window so it still captures the new events; we're just getting a sense of what's in flight.

Note the timestamp before starting the scenario so you can compare:

```bash
SCENARIO_START_TS=$(date -u +%FT%TZ)
echo "SCENARIO_START_TS=$SCENARIO_START_TS"
```

- [ ] **Step 3: Run the gate scenario**

Run:

```bash
adb shell am force-stop com.astronomyshoprn
adb logcat -c
adb shell am start -n com.astronomyshoprn/.MainActivity
sleep 6                                                 # cold launch settle
adb shell input keyevent KEYCODE_HOME ; sleep 4         # bg #1
adb shell am start -n com.astronomyshoprn/.MainActivity ; sleep 4   # fg #2
adb shell input keyevent KEYCODE_HOME ; sleep 4         # bg #2
adb shell am start -n com.astronomyshoprn/.MainActivity ; sleep 8   # fg #3 + flush
```

- [ ] **Step 4: Wait for the SDK's periodic flush to drain**

The RN demo's `App.tsx` schedules a `flushWindow(5)` every 10 seconds. Wait long enough for at least one flush to fire and the records to land in Dash0's ingestion + indexing pipeline.

```bash
sleep 15
```

- [ ] **Step 5: Query Dash0 for the gate evidence**

```bash
dash0 -X logs query --filter 'service.name is otel-rn-astronomy-shop' --from now-5m -o json > /tmp/rn-android-gate1.json
python3 << 'EOF'
import json
from collections import Counter
d = json.load(open("/tmp/rn-android-gate1.json"))
events = Counter()
for rl in d.get("resourceLogs", []):
    for sl in rl.get("scopeLogs", []):
        scope = sl.get("scope", {}).get("name", "?")
        if "lifecycle" not in scope: continue
        for lr in sl.get("logRecords", []):
            body = lr.get("body", {}).get("stringValue", "")
            events[body] += 1
print("lifecycle-scope events:", dict(events))
EOF
```

Expected output: `{'app.start': 1, 'app.foreground': 3, 'app.background': 2}` — matching the canonical Gate 1 pass criterion.

If the counts are short by 1 (e.g., `app.foreground: 2` instead of 3), the most likely cause is that the final flush hasn't landed yet. Re-run step 4's `sleep 15` and step 5's query once more before treating it as a real failure.

- [ ] **Step 6: Verify late-init type attribute on the first `app.start`**

```bash
python3 << 'EOF'
import json
d = json.load(open("/tmp/rn-android-gate1.json"))
for rl in d.get("resourceLogs", []):
    for sl in rl.get("scopeLogs", []):
        if "lifecycle" not in sl.get("scope", {}).get("name", ""): continue
        for lr in sl.get("logRecords", []):
            if lr.get("body", {}).get("stringValue") == "app.start":
                attrs = {a["key"]: a.get("value", {}) for a in lr.get("attributes", [])}
                print("app.start.type:", attrs.get("app.start.type", {}).get("stringValue"))
EOF
```

Expected: `instrumentation_late`.

### Task 20: Build + install + run iOS RN matchy-matchy Gate 1

**Files:** none (build + manual validation)

- [ ] **Step 1: Boot iPhone simulator**

Run:

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
SIM=$(xcrun simctl list devices available | grep "iPhone 17 " | head -1 | grep -oE '[A-F0-9-]{36}')
xcrun simctl boot "$SIM" 2>/dev/null || true
echo "SIM=$SIM"
```

- [ ] **Step 2: Rebuild + install AstronomyShopRN.app**

Run:

```bash
( cd examples/upstream-demo-app-rn/AstronomyShopRN/ios \
    && xcodebuild -workspace AstronomyShopRN.xcworkspace \
                  -scheme AstronomyShopRN \
                  -configuration Release \
                  -destination "platform=iOS Simulator,id=$SIM" \
                  build )
```

Expected: BUILD SUCCEEDED. Locate the .app from the build output and install:

```bash
APP=$(find ~/Library/Developer/Xcode/DerivedData -name "AstronomyShopRN.app" -path "*/Release-iphonesimulator/*" | head -1)
xcrun simctl install "$SIM" "$APP"
xcrun simctl launch "$SIM" com.astronomyshoprn.AstronomyShopRN
```

- [ ] **Step 3: Run the gate scenario**

iOS Simulator's bg/fg has to be driven through `Simulator.app` directly because `xcrun simctl` doesn't expose a "home button" command. The reliable script-friendly path is to use AppleScript to send the `Cmd+Shift+H` keystroke (the macOS "Home button" shortcut). This requires Accessibility permissions for the controlling Terminal/iTerm — if `osascript` returns "not authorized", grant permission once via System Settings → Privacy & Security → Accessibility.

```bash
osascript -e 'tell application "Simulator" to activate'
sleep 6                                                                       # cold launch settle

# bg #1
osascript -e 'tell application "System Events" to keystroke "h" using {command down, shift down}'
sleep 4

# fg #2
xcrun simctl launch "$SIM" com.astronomyshoprn.AstronomyShopRN
sleep 4

# bg #2
osascript -e 'tell application "System Events" to keystroke "h" using {command down, shift down}'
sleep 4

# fg #3 + flush
xcrun simctl launch "$SIM" com.astronomyshoprn.AstronomyShopRN
sleep 8
```

Expected: app cycles bg → fg → bg → fg visibly in Simulator.app. If keystrokes don't register, fall back to manually clicking the Simulator's Device → Home menu item between steps; the `dash0` query in the next step will still capture the events.

- [ ] **Step 4: Wait for the SDK's periodic flush to drain**

```bash
sleep 15
```

- [ ] **Step 5: Query Dash0 for evidence**

```bash
dash0 -X logs query --filter 'service.name is otel-rn-astronomy-shop' --from now-5m -o json > /tmp/rn-ios-gate1.json
python3 << 'EOF'
import json
from collections import Counter
d = json.load(open("/tmp/rn-ios-gate1.json"))
events = Counter()
fg_types = Counter()
for rl in d.get("resourceLogs", []):
    for sl in rl.get("scopeLogs", []):
        scope = sl.get("scope", {}).get("name", "?")
        if "lifecycle" not in scope: continue
        for lr in sl.get("logRecords", []):
            body = lr.get("body", {}).get("stringValue", "")
            events[body] += 1
            if body == "app.foreground":
                attrs = {a["key"]: a.get("value", {}) for a in lr.get("attributes", [])}
                fg_types[attrs.get("app.foreground.type", {}).get("stringValue", "<missing>")] += 1
print("lifecycle-scope events:", dict(events))
print("app.foreground types:", dict(fg_types))
EOF
```

Expected:

- `events` includes `app.launch: 1`, `app.foreground: 3`, `app.background: 2`.
- `fg_types` has `instrumentation_late: 1` (the cold-launch synthesis) and `natural: 2` (the two return-to-foreground transitions).

Same retry-on-short-count guidance as Task 19 step 5: re-run the sleep + query once before treating as failure.

### Task 21: Regression smoke for Android native

**Files:** none (manual validation, Pixel_7 emulator)

- [ ] **Step 1: Build + install the upstream-demo-app dash0 flavor**

Run:

```bash
( cd examples/upstream-demo-app && ./gradlew :app:assembleDash0Debug )
adb install -r -d examples/upstream-demo-app/app/build/outputs/apk/dash0/debug/app-dash0-debug.apk
```

Expected: success.

- [ ] **Step 2: Run the Gate 1 scenario**

Run:

```bash
adb shell am force-stop io.opentelemetry.android.demo.dash0
adb logcat -c
adb shell am start -n io.opentelemetry.android.demo.dash0/io.opentelemetry.android.demo.MainActivity
sleep 6
adb shell input keyevent KEYCODE_HOME ; sleep 4
adb shell am start -n io.opentelemetry.android.demo.dash0/io.opentelemetry.android.demo.MainActivity ; sleep 4
adb shell input keyevent KEYCODE_HOME ; sleep 4
adb shell am start -n io.opentelemetry.android.demo.dash0/io.opentelemetry.android.demo.MainActivity ; sleep 8
```

- [ ] **Step 3: Wait for periodic flush + query Dash0**

```bash
sleep 15
dash0 -X logs query --filter 'service.name is otel-android-astronomy-shop' --from now-5m -o json > /tmp/native-android-gate1.json
python3 << 'EOF'
import json
from collections import Counter
d = json.load(open("/tmp/native-android-gate1.json"))
events = Counter(); types = Counter()
for rl in d.get("resourceLogs", []):
    for sl in rl.get("scopeLogs", []):
        if "lifecycle" not in sl.get("scope", {}).get("name", ""): continue
        for lr in sl.get("logRecords", []):
            body = lr.get("body", {}).get("stringValue", "")
            events[body] += 1
            if body == "app.start":
                attrs = {a["key"]: a.get("value", {}) for a in lr.get("attributes", [])}
                types[attrs.get("app.start.type", {}).get("stringValue", "?")] += 1
print("events:", dict(events), "start types:", dict(types))
EOF
```

Expected: `app.start: 1`, `app.foreground: 3`, `app.background: 2`. `start types: {'cold': 1}` (NOT `instrumentation_late` — native installs from `Application.onCreate` so the cold-start path wins).

### Task 22: Regression smoke for iOS native

**Files:** none (manual validation, iPhone simulator)

- [ ] **Step 1: Build + run the iOS native AstronomyShop**

Run:

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
SIM=$(xcrun simctl list devices available | grep "iPhone 17 " | head -1 | grep -oE '[A-F0-9-]{36}')
( cd examples/upstream-demo-app-ios \
    && xcodebuild -project AstronomyShop.xcodeproj \
                  -scheme AstronomyShop \
                  -configuration Release \
                  -destination "platform=iOS Simulator,id=$SIM" \
                  build )
APP=$(find ~/Library/Developer/Xcode/DerivedData -name "AstronomyShop.app" -path "*/Release-iphonesimulator/*" | head -1)
xcrun simctl install "$SIM" "$APP"
xcrun simctl launch "$SIM" com.dash0.mobile.AstronomyShop
```

- [ ] **Step 2: Run the gate scenario** (same shape as Task 20 step 3 but for the native bundle id)

```bash
osascript -e 'tell application "Simulator" to activate'
sleep 6                                                                       # cold launch settle

osascript -e 'tell application "System Events" to keystroke "h" using {command down, shift down}'   # bg #1
sleep 4
xcrun simctl launch "$SIM" com.dash0.mobile.AstronomyShop                                          # fg #2
sleep 4
osascript -e 'tell application "System Events" to keystroke "h" using {command down, shift down}'   # bg #2
sleep 4
xcrun simctl launch "$SIM" com.dash0.mobile.AstronomyShop                                          # fg #3 + flush
sleep 8
```

Same Accessibility-permissions caveat as Task 20 step 3.

- [ ] **Step 3: Wait for periodic flush + query Dash0**

```bash
sleep 15
dash0 -X logs query --filter 'service.name is otel-ios-astronomy-shop' --from now-5m -o json > /tmp/native-ios-gate1.json
python3 << 'EOF'
import json
from collections import Counter
d = json.load(open("/tmp/native-ios-gate1.json"))
events = Counter(); types = Counter()
for rl in d.get("resourceLogs", []):
    for sl in rl.get("scopeLogs", []):
        if "lifecycle" not in sl.get("scope", {}).get("name", ""): continue
        for lr in sl.get("logRecords", []):
            body = lr.get("body", {}).get("stringValue", "")
            events[body] += 1
            if body == "app.foreground":
                attrs = {a["key"]: a.get("value", {}) for a in lr.get("attributes", [])}
                types[attrs.get("app.foreground.type", {}).get("stringValue", "?")] += 1
print("events:", dict(events), "fg types:", dict(types))
EOF
```

Expected: `app.launch: 1`, `app.foreground: 3`, `app.background: 2`. `fg types: {'natural': 3}` (NO `instrumentation_late` — native installs in `didFinishLaunching` before `didBecomeActive`, so the natural path wins).

---

## Phase 5.5 — Automated crash drivers for RN platforms (cross-platform parity)

Both native demos already have automated crash hooks: Android native uses `--ez gate3_crash true` on the launch intent ([upstream-demo-app MainActivity.kt:135](../../examples/upstream-demo-app/src/main/java/io/opentelemetry/android/demo/MainActivity.kt#L135)); iOS native uses `-DASH0_CRASH_NOW` on `CommandLine.arguments` ([upstream-demo-app-ios AstronomyShopApp.swift:90](../../examples/upstream-demo-app-ios/AstronomyShop/AstronomyShopApp.swift#L90)). Neither RN demo has an equivalent — Gate 3 validation requires a human tap, which breaks scripted matchy-matchy runs. Add the same hooks to RN iOS and RN Android so the crash test driver works the same way on all four platforms.

### Task A: Add `-DASH0_CRASH_NOW` hook to RN iOS

**Files:**
- Modify: `examples/upstream-demo-app-rn/AstronomyShopRN/ios/AstronomyShopRN/AppDelegate.swift`

- [ ] **Step 1: Add the crash hook in `didFinishLaunchingWithOptions`**

In `AppDelegate.swift`, find `application(_:didFinishLaunchingWithOptions:)`. After the existing `Dash0MobileModule.installSink { OTelMobileCallSink() }` line and before the `let delegate = ReactNativeDelegate()` line, add:

```swift
    // Test hook: if launched with -DASH0_CRASH_NOW, schedule a fatal
    // crash ~3s after boot. Mirrors the native iOS demo's hook so the
    // matchy-matchy Gate 3 runbook can drive a real signal-handler
    // crash on RN iOS without a human tap. Marker is written by the
    // SDK's signal handler; next launch's ErrorsInstrumentation.install
    // emits app.crash.
    if CommandLine.arguments.contains("-DASH0_CRASH_NOW") {
      DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) {
        let arr: [Int] = []
        _ = arr[42]   // triggers EXC_BREAKPOINT / SIGTRAP
      }
    }
```

The 3-second deadline (vs. the native demo's 1.5s) gives RN's bridge + JS bundle additional warmup time before the crash, so the SDK's RAM buffer has more events to crash-mirror to disk.

- [ ] **Step 2: Verify the iOS test suite still compiles + passes**

Run: `(cd otel-ios-mobile && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer swift test)`
Expected: PASS — this AppDelegate change doesn't touch any test target, but a quick run confirms nothing accidentally tangled.

- [ ] **Step 3: Commit**

```bash
git add examples/upstream-demo-app-rn/AstronomyShopRN/ios/AstronomyShopRN/AppDelegate.swift
git commit -m "feat(rn-ios-demo): add -DASH0_CRASH_NOW launch hook (matchy parity)

Mirrors the native iOS AstronomyShop's crash trigger so the matchy-matchy
Gate 3 runbook can script a real SIGTRAP on RN iOS without requiring
a human to tap the in-app Trigger Crash button. 3-second deadline
gives the RN bridge + JS bundle extra warmup time over the native
demo's 1.5s."
```

### Task B: Add `gate3_crash` intent extra hook to RN Android

**Files:**
- Modify: `examples/upstream-demo-app-rn/AstronomyShopRN/android/app/src/main/java/com/astronomyshoprn/MainActivity.kt`

- [ ] **Step 1: Override `onCreate` to read the intent extra**

In `MainActivity.kt`, replace the entire class body so it overrides `onCreate` (the default `ReactActivity` doesn't expose one) and reads the `gate3_crash` intent extra. Replace the existing class body:

```kotlin
class MainActivity : ReactActivity() {

  /**
   * Returns the name of the main component registered from JavaScript. This is used to schedule
   * rendering of the component.
   */
  override fun getMainComponentName(): String = "AstronomyShopRN"

  /**
   * Returns the instance of the [ReactActivityDelegate]. We use [DefaultReactActivityDelegate]
   * which allows you to enable New Architecture with a single boolean flags [fabricEnabled]
   */
  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)
}
```

with:

```kotlin
class MainActivity : ReactActivity() {

  /**
   * Returns the name of the main component registered from JavaScript. This is used to schedule
   * rendering of the component.
   */
  override fun getMainComponentName(): String = "AstronomyShopRN"

  /**
   * Returns the instance of the [ReactActivityDelegate]. We use [DefaultReactActivityDelegate]
   * which allows you to enable New Architecture with a single boolean flags [fabricEnabled]
   */
  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    super.onCreate(savedInstanceState)

    // Test hook: if launched with --ez gate3_crash true, schedule a fatal
    // crash ~3s after boot. Mirrors the native Android demo's hook
    // (and iOS's -DASH0_CRASH_NOW) so the matchy-matchy Gate 3 runbook
    // can drive a real uncaught exception on RN Android without a human
    // tap. The SDK's ErrorsInstrumentation captures the throw, mirrors
    // the RAM buffer to disk, and the next launch's recovery path emits
    // app.crash.
    // Use: adb shell am start -n com.astronomyshoprn/.MainActivity --ez gate3_crash true
    if (intent?.getBooleanExtra("gate3_crash", false) == true) {
      intent.removeExtra("gate3_crash")  // don't re-crash on re-launch
      android.util.Log.i("AstronomyShopRN", "Gate3: scheduling crash in 3s")
      android.os.Handler(mainLooper).postDelayed({
        android.util.Log.w("AstronomyShopRN", "Gate3: crashing now")
        throw RuntimeException("Dash0 RN Android Gate 3 test crash")
      }, 3000L)
    }
  }
}
```

- [ ] **Step 2: Rebuild + reinstall the RN Android APK to confirm it compiles**

```bash
touch examples/upstream-demo-app-rn/AstronomyShopRN/index.js
( cd examples/upstream-demo-app-rn/AstronomyShopRN \
    && /opt/homebrew/bin/node node_modules/.bin/react-native bundle \
        --platform android --dev false --entry-file index.js \
        --bundle-output android/app/src/main/assets/index.android.bundle \
        --assets-dest android/app/src/main/res )
( cd examples/upstream-demo-app-rn/AstronomyShopRN/android && ./gradlew assembleRelease )
adb install -r -d examples/upstream-demo-app-rn/AstronomyShopRN/android/app/build/outputs/apk/release/app-release.apk
```

Expected: BUILD SUCCESSFUL, install Success.

- [ ] **Step 3: Commit**

```bash
git add examples/upstream-demo-app-rn/AstronomyShopRN/android/app/src/main/java/com/astronomyshoprn/MainActivity.kt
git commit -m "feat(rn-android-demo): add gate3_crash intent extra (matchy parity)

Mirrors native Android's --ez gate3_crash true hook so the matchy-matchy
Gate 3 runbook can script a real uncaught exception on RN Android
without requiring a human to tap the in-app Trigger Crash button."
```

### Task C: Validate the new RN crash drivers end-to-end

**Files:** none (manual validation, both platforms)

- [ ] **Step 1: Run RN Android Gate 3 via the new intent extra**

```bash
adb shell am force-stop com.astronomyshoprn
adb logcat -c
adb shell am start -n com.astronomyshoprn/.MainActivity --ez gate3_crash true
sleep 8                                            # wait for the 3s deadline + crash + dropbox
adb shell am start -n com.astronomyshoprn/.MainActivity   # relaunch to drain crash mirror
sleep 15                                           # SDK periodic flush
```

Then query Dash0:

```bash
dash0 -X logs query --filter 'service.name is otel-rn-astronomy-shop' --from now-5m -o json > /tmp/rn-android-gate3-auto.json
python3 << 'EOF'
import json
from collections import Counter
d = json.load(open("/tmp/rn-android-gate3-auto.json"))
crashes = []
for rl in d.get("resourceLogs", []):
    for sl in rl.get("scopeLogs", []):
        scope = sl.get("scope", {}).get("name", "?")
        for lr in sl.get("logRecords", []):
            body = lr.get("body", {}).get("stringValue", "")
            if body in ("app.crash", "app.error"):
                attrs = {a["key"]: a.get("value", {}) for a in lr.get("attributes", [])}
                crashes.append({
                    "scope": scope, "body": body,
                    "exception_message": attrs.get("exception.message", {}).get("stringValue"),
                    "severity": lr.get("severityNumber"),
                })
for c in crashes: print(c)
EOF
```

Expected: at least 1 record with `body=app.crash` and an exception message containing `Dash0 RN Android Gate 3 test crash`.

- [ ] **Step 2: Run RN iOS Gate 3 via the new launch arg**

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
SIM=$(xcrun simctl list devices available | grep "iPhone 17 " | head -1 | grep -oE '[A-F0-9-]{36}')
xcrun simctl terminate "$SIM" com.astronomyshoprn.AstronomyShopRN 2>/dev/null || true
xcrun simctl launch "$SIM" com.astronomyshoprn.AstronomyShopRN -DASH0_CRASH_NOW
sleep 8                                            # wait for crash
xcrun simctl launch "$SIM" com.astronomyshoprn.AstronomyShopRN   # relaunch to drain
sleep 15                                           # SDK periodic flush
```

Then query Dash0:

```bash
dash0 -X logs query --filter 'service.name is otel-rn-astronomy-shop' --from now-5m -o json > /tmp/rn-ios-gate3-auto.json
python3 << 'EOF'
import json
d = json.load(open("/tmp/rn-ios-gate3-auto.json"))
for rl in d.get("resourceLogs", []):
    for sl in rl.get("scopeLogs", []):
        scope = sl.get("scope", {}).get("name", "?")
        for lr in sl.get("logRecords", []):
            body = lr.get("body", {}).get("stringValue", "")
            if body in ("app.crash", "app.error"):
                attrs = {a["key"]: a.get("value", {}) for a in lr.get("attributes", [])}
                print({"scope": scope, "body": body,
                       "crash_kind": attrs.get("crash.kind", {}).get("stringValue"),
                       "crash_name": attrs.get("crash.name", {}).get("stringValue")})
EOF
```

Expected: at least 1 record with `body=app.crash`, `crash.kind=signal`, `crash.name=SIGTRAP` (the array OOB triggers `EXC_BREAKPOINT` which the SDK's signal handler maps to SIGTRAP).

- [ ] **Step 3: Update both RN matchy-matchy runbooks to mention the new hooks**

In `docs/matchy-matchy/rn-android.md` §3 (Gate 3), find the "Trigger" line that today reads something like *"Same red 'Trigger Crash (Gate 3)' button from RN iOS"* and add a sibling line below it:

```text
**Automated trigger:** `adb shell am start -n com.astronomyshoprn/.MainActivity --ez gate3_crash true`
(mirrors native Android's hook; lands `app.crash` in Dash0 after relaunch)
```

In `docs/matchy-matchy/rn-ios.md` §3, similarly add:

```text
**Automated trigger:** `xcrun simctl launch "$SIM" com.astronomyshoprn.AstronomyShopRN -DASH0_CRASH_NOW`
(mirrors native iOS's hook; lands `app.crash` in Dash0 after relaunch)
```

- [ ] **Step 4: Commit the runbook updates**

```bash
git add docs/matchy-matchy/rn-android.md docs/matchy-matchy/rn-ios.md
git commit -m "docs(matchy-matchy): document automated Gate 3 crash drivers for RN platforms"
```

---

## Phase 6 — Documentation updates + final commit

### Task 23: Flip status in matchy-matchy runbooks + epic

**Files:**
- Modify: `docs/matchy-matchy/rn-android.md` (header status block + §1 Gate 1 section)
- Modify: `docs/matchy-matchy/rn-ios.md` (header status block + §1 Gate 1 section)
- Modify: `docs/matchy-matchy/README.md` (status table)
- Modify: `docs/epics/VALIDATION_MATRIX_EPIC.md` (RN Android + RN iOS Gate 1 cells in the SDK × Gate matrix)

- [ ] **Step 1: Update `docs/matchy-matchy/rn-android.md`**

Find the header status line (today): `**Status:** 🟢 Gate 2 · 🟢 Gate 3 · 🟢 Gate 4 · 🔴 Gate 1` and change to: `**Status:** 🟢 Gate 1 · 🟢 Gate 2 · 🟢 Gate 3 · 🟢 Gate 4 (4/4 verified 2026-04-29)`.

Find the `## 1. Gate 1 — Lifecycle 🔴` heading and change to `## 1. Gate 1 — Lifecycle 🟢 verified 2026-04-29`.

Inside the Gate 1 body, replace the existing "Architectural gap" / "Why" / "Remediation" prose with a one-paragraph closure note pointing at the spec/plan, and append the Dash0 evidence captured in Task 19 (events count + `app.start.type=instrumentation_late` excerpt).

- [ ] **Step 2: Update `docs/matchy-matchy/rn-ios.md`**

Same shape: flip the header status line and §1 heading; replace Gate 1 body with closure + Task 20 Dash0 evidence.

- [ ] **Step 3: Update `docs/matchy-matchy/README.md`**

In the platform status table, change the RN iOS row from `🟢 Gate 2 + 3 + 4 · 🔴 Gate 1 (architectural choice)` to `🟢 4/4 (verified 2026-04-29)`. Change the RN Android row similarly.

- [ ] **Step 4: Update `docs/epics/VALIDATION_MATRIX_EPIC.md`**

In the SDK × Gate matrix table, flip the Gate 1 cell for RN iOS and RN Android from 🔴 to 🟢 with the verification date and a 1-line evidence summary.

- [ ] **Step 5: Commit**

```bash
git add docs/matchy-matchy/rn-android.md \
        docs/matchy-matchy/rn-ios.md \
        docs/matchy-matchy/README.md \
        docs/epics/VALIDATION_MATRIX_EPIC.md
git commit -m "docs(matchy-matchy): flip Gate 1 🟢 for RN iOS + RN Android"
```

### Task 24: Update memory + final spec/plan reference

**Files:**
- Modify: `~/.claude/projects/-Users-barrysolomon-Projects-Dash0-mobile-observability/memory/MEMORY.md`
- Create: `~/.claude/projects/-Users-barrysolomon-Projects-Dash0-mobile-observability/memory/project_session_2026_04_29c.md` (or merge into existing 2026-04-29 session memory)

- [ ] **Step 1: Append a one-line index entry to MEMORY.md**

Add (preserving the file's index format — one line, ~150 chars max):

```
- [Session 2026-04-29c](project_session_2026_04_29c.md) — Gate 1 closed for RN iOS + RN Android; Android via ProcessLifecycleOwner, iOS via applicationState late-init synthesis; matrix 16/16 🟢
```

- [ ] **Step 2: Write the session memory file**

Create `project_session_2026_04_29c.md` with:

```markdown
---
name: Session 2026-04-29c
description: Gate 1 closure — Android ProcessLifecycleOwner + iOS late-init synthesis + RN bridge default + JS shim deletion. All 16 matrix cells 🟢.
type: project
---

Closed Gate 1 (the last red cell in the validation matrix) for both RN platforms.

## Root cause and fix

- **Android `LifecycleInstrumentation` install-time race.** Activity counter started from 0 but Activity already in started state when SDK initialized late (RN useEffect). Counter ran 0→-1 on first stop, -1→0 on first start, never satisfying the == 0 / == 1 emit predicates. Fixed by migrating to `androidx.lifecycle.ProcessLifecycleOwner`, which has at-attach replay and a 700ms debounce. New `app.start.type = "instrumentation_late"` for synthesized late-init starts.
- **iOS NotificationCenter has no at-attach replay.** Same problem on RN iOS: install runs from JS useEffect after `didBecomeActiveNotification` already fired. Added an `applicationStateProvider` test seam + `DispatchQueue.main.async` check that synthesizes the initial `app.foreground` if `applicationState == .active` at install. New `app.foreground.type = "natural" | "instrumentation_late"` attribute.
- **iOS RN bridge `parseAutoCaptureOptions` defaulted `.none`.** Now defaults to `[.lifecycle]`. Network/errors/screen still off-by-default (genuine RN event-loop conflicts).
- **Deleted JS shim.** `installAppStateInstrumentation`, the `autoCapture.lifecycle?: boolean` field, and the demo's opt-out — all gone.

## Validation evidence

Run on Pixel_7 emulator + iPhone 17 simulator (2026-04-29):
- RN Android: 1×app.start (type=instrumentation_late) + 3×app.foreground + 2×app.background ✓
- RN iOS: 1×app.launch + 3×app.foreground (1 instrumentation_late + 2 natural) + 2×app.background ✓
- Android native regression: 1×app.start (type=cold) + 3×app.foreground + 2×app.background ✓
- iOS native regression: 1×app.launch + 3×app.foreground (all natural) + 2×app.background ✓

## Files modified

- `instrumentation/lifecycle/build.gradle.kts` (+lifecycle-process)
- `instrumentation/lifecycle/consumer-rules.pro` (+R8 keep rules)
- `instrumentation/lifecycle/.../LifecycleInstrumentation.kt` (refactor)
- `instrumentation/lifecycle/.../LifecycleInstrumentationTest.kt` (8 tests; 5 retained, 2 rewritten, 1 new)
- `otel-ios-mobile/Sources/LifecycleInstrumentation/LifecycleInstrumentation.swift` (+applicationStateProvider seam)
- `otel-ios-mobile/Tests/LifecycleInstrumentationTests/...` (new test target, 3 tests)
- `otel-ios-mobile/Package.swift` (+test target)
- `examples/upstream-demo-app-rn/AstronomyShopRN/ios/AstronomyShopRN/OTelMobileCallSink.swift` (default `.lifecycle`)
- `examples/upstream-demo-app-rn/AstronomyShopRN/ios/AstronomyShopRN/AppDelegate.swift` (+`-DASH0_CRASH_NOW` hook, Phase 5.5)
- `examples/upstream-demo-app-rn/AstronomyShopRN/android/app/src/main/java/com/astronomyshoprn/MainActivity.kt` (+`gate3_crash` intent extra hook, Phase 5.5)
- `examples/upstream-demo-app-rn/AstronomyShopRN/src/App.tsx` (drop opt-out)
- `packages/react-native/src/index.ts` (drop AppState install + token)
- `packages/react-native/src/bridge/types.ts` (drop lifecycle field)
- DELETED: `packages/react-native/src/instrumentation/appstate.ts` + `__tests__/instr/appstate.test.ts`
- Docs: matchy-matchy/{rn-android,rn-ios,README}.md + epics/VALIDATION_MATRIX_EPIC.md (Gate 1 status flip + Gate 3 automated-trigger docs)
```

- [ ] **Step 3: Verify the memory files are in place**

The memory directory lives outside git — it's a dotfile-area filesystem write managed by Claude's own auto-memory system. No `git commit` needed; just confirm both files exist:

```bash
ls ~/.claude/projects/-Users-barrysolomon-Projects-Dash0-mobile-observability/memory/MEMORY.md \
   ~/.claude/projects/-Users-barrysolomon-Projects-Dash0-mobile-observability/memory/project_session_2026_04_29c.md
```

Expected: both paths listed, no errors.

---

## Done

After Task 24, all 16 cells of the Validation Matrix are 🟢. The spec, the plan, and every cell can be cross-referenced via:

- Spec: [`docs/superpowers/specs/2026-04-29-gate1-rn-lifecycle-design.md`](../specs/2026-04-29-gate1-rn-lifecycle-design.md)
- Plan: this file
- Evidence: matchy-matchy runbooks (rn-android.md §1, rn-ios.md §1) updated in Task 23

The project's "every supported platform passes all four gates in Dash0 with evidence" goal is met.
