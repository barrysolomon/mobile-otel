# Gate 1 — RN Lifecycle Unblock

**Status:** Spec
**Owner:** Barry Solomon
**Created:** 2026-04-29
**Closes:** Gate 1 🔴 cells in [Validation Matrix Epic](../../epics/VALIDATION_MATRIX_EPIC.md) for both RN iOS and RN Android.

## Summary

Gate 1 (auto-instrumented `app.start` / `app.foreground` / `app.background` lifecycle events) is currently red on both RN platforms. On-device investigation 2026-04-29 (Pixel_7 + RN AstronomyShop) showed the root cause is **not** a fundamental RN limitation; it's a combination of:

1. **An Android SDK bug.** `LifecycleInstrumentation` counts `Application.ActivityLifecycleCallbacks` from zero, but when `OTelMobile.start()` is invoked after `Application.onCreate` (any deferred-init pattern, including RN's JS-side `useEffect`), the host Activity is already started by the time the callback registers. The counter then runs in the wrong direction (0 → -1 on first stop, -1 → 0 on first start) and the `== 1` / `== 0` predicates that gate emission are never satisfied.
2. **An iOS RN bridge configuration choice.** `OTelMobileCallSink.swift` defaults `autoCaptureOptions` to `.none` to avoid known conflicts between native iOS instrumentation (URLProtocol swizzle, NSException/signal handlers) and RN's JS event loop. The blanket `.none` is too coarse — `.lifecycle` (NotificationCenter observers on `UIApplication.didBecomeActiveNotification` etc.) is safe with the RN event loop and was getting suppressed unnecessarily.
3. **A demo opt-out that's now obsolete.** `App.tsx` sets `autoCapture: { lifecycle: false }` to avoid the JS-side `installAppStateInstrumentation` shim, which trips the RN 0.85 new-arch TurboModule init race. After Parts 1, 2, and 3 below, the JS shim is unneeded — native instrumentation is reliable on both platforms.

The fix is concentrated changes across two SDK modules (Android lifecycle instrumentation, iOS lifecycle instrumentation) plus the iOS RN bridge, plus deleting now-obsolete JS-side AppState shim code. No new opt-in flag added; the existing `autoCapture.lifecycle` field is removed because it has no remaining behavior to control. After landing, all 16 cells in the validation matrix are 🟢.

## Goals

- Android `LifecycleInstrumentation` emits `app.start` / `app.foreground` / `app.background` correctly regardless of when `OTelMobile.start()` is called relative to `Application.onCreate`.
- iOS `LifecycleInstrumentation` emits an initial `app.foreground` even when `install()` is called after `UIApplication.didBecomeActiveNotification` has already fired (the iOS analog of the Android late-init problem).
- iOS RN bridge enables native `LifecycleInstrumentation` by default while preserving the existing safe-defaults posture for genuinely-conflicting capabilities (network, errors, screen swizzle).
- Both RN demo apps (AstronomyShop) drop the `autoCapture: { lifecycle: false }` opt-out; the JS-side `installAppStateInstrumentation` shim is deleted; the now-meaningless `autoCapture.lifecycle?: boolean` field is removed from the RN public API — net code and surface reduction.
- Gate 1 closes 🟢 on RN iOS AND RN Android, validated by running the matchy-matchy runbooks and capturing Dash0 evidence.

## Non-goals

- Restoring the JS-side AppState shim. RN 0.85's TurboModule init race makes it inherently unreliable, and native instrumentation gives us the same signal more accurately. Delete it.
- Adding a new `autoCapture` flag for "JS shim vs native lifecycle." The JS shim is being removed entirely; one flag, one effect.
- Solving the unrelated `autoCaptureOptions: .none` posture for iOS network/errors/screen — those genuinely conflict with RN's event loop and should remain off-by-default. This spec only re-enables `.lifecycle`.

---

## Architecture

### Part 1 — Android: migrate `LifecycleInstrumentation` to `ProcessLifecycleOwner`

**File:** `instrumentation/lifecycle/src/main/java/io/opentelemetry/android/mobile/instrumentation/LifecycleInstrumentation.kt`

**Why ProcessLifecycleOwner:** It's `androidx.lifecycle`'s first-class abstraction for "the entire app process is foregrounded vs backgrounded," with a built-in 700ms debounce so an Activity rotation doesn't spuriously emit `app.background`. It does the activity-counting bookkeeping internally — and crucially, **it observes the global state**, so it works regardless of when we subscribe. There is no install-time race to fix; the bug we have today simply ceases to exist.

**At-attach replay for late init:** when `addObserver(observer)` is called and the lifecycle is already in the STARTED state (process is foregrounded), the lifecycle library *automatically* fires the appropriate at-attach callbacks (`onCreate`, `onStart`) on the observer in order. This is documented behavior of `androidx.lifecycle.LifecycleRegistry`. So when `OTelMobile.start()` is invoked late from RN's `useEffect`, the observer's `onStart` fires immediately at attach time, giving us a real `app.foreground` event for the current already-running session — no manual synthesis needed for foreground/background. We only synthesize `app.start` (which has no `androidx.lifecycle` equivalent), giving each session exactly one `app.start` + the at-attach `app.foreground` from the observer.

**Implementation shape:**

```kotlin
class LifecycleInstrumentation : MobileInstrumentation {
    override val instrumentationName = "io.opentelemetry.android.mobile.lifecycle"

    private var lifecycleObserver: DefaultLifecycleObserver? = null
    private var activityCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var application: Application? = null
    // ... (logger, sessionProvider, instrumentationContext as today)

    override fun install(application: Application, context: InstrumentationContext) {
        // ... existing field assignments

        // app.start synthesis for late-init: if the process is already past
        // INITIALIZED state at install-time, an Activity already exists. Emit
        // app.start now with type="instrumentation_late" so the session has a
        // start event regardless of when start() was called. Sets
        // firstStartLogged so the activity-callback path below doesn't re-emit.
        emitAppStartIfLateInstall()

        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Fires either:
                //   (a) immediately at addObserver() time if process is already
                //       STARTED — gives late-init sessions their app.foreground;
                //   (b) when the process foregrounds (debounced 700ms).
                emitForeground()
            }
            override fun onStop(owner: LifecycleOwner) {
                emitBackground()
            }
        }
        lifecycleObserver = observer
        // addObserver is the at-attach replay point: if the process is already
        // STARTED, this call synchronously dispatches onCreate→onStart on the
        // observer before returning. That's how late-init sessions get their
        // app.foreground for free, without any manual synthesis path.
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)

        // Keep onActivityCreated for the cold-start app.start signal — the
        // only event ProcessLifecycleOwner doesn't cover. For native consumers
        // calling start() from Application.onCreate, emitAppStartIfLateInstall
        // is a no-op (process state INITIALIZED at install-time, no activity
        // yet) and this path emits app.start with type="cold" exactly as
        // today. firstStartLogged guards against double-emit.
        val cb = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) { emitAppStartIfFirstSeen(a) }
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
        lifecycleObserver?.let { ProcessLifecycleOwner.get().lifecycle.removeObserver(it) }
        activityCallbacks?.let { application?.unregisterActivityLifecycleCallbacks(it) }
        // ... clear fields
    }
}
```

**`emitAppStartIfLateInstall()` semantics:** if `ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)` at install time — meaning at least one Activity already exists — synthesize an `app.start` log immediately with `app.start.type = "instrumentation_late"`. Duration is measured as `installTimeMs - Process.getStartUptimeMillis()` (Android API 24+, available since `minSdk = 26`) so we can attribute the gap to "time from process start to SDK start" — fuzzy but at least anchored to a real OS-provided timestamp. Set `firstStartLogged = true` so the activity-callback path doesn't re-emit.

**`emitAppStartIfFirstSeen(activity)` semantics:** the existing cold-start path. If `firstStartLogged` is false (neither the late-install path nor a prior `onActivityCreated` fired it), emit `app.start` with `app.start.type = "cold"` and duration `now - installTimeMs`. Sets `firstStartLogged = true`. This is the canonical native path: `OTelMobile.start()` from `Application.onCreate`, then `MainActivity.onCreate` fires this callback, which emits `app.start` with a precise cold-start duration.

**Order-of-operations matters:** `emitAppStartIfLateInstall()` runs *before* `addObserver()` so that if late-install fires `app.start`, the `app.foreground` from the observer's at-attach replay arrives second — matching the natural cold-start ordering (`app.start` then `app.foreground`).

**Counter elimination:** `activeActivities`, `lastBackgroundAtMs`, the `== 1` / `== 0` predicates — all gone. ProcessLifecycleOwner's debounced `onStart`/`onStop` is the single source of truth for foreground/background.

**Retained state:** `firstStartLogged: Boolean` (volatile, default false) and `installTimeMs: Long` are kept as today. Both `emitAppStartIfLateInstall()` and `emitAppStartIfFirstSeen()` consult and set `firstStartLogged` so exactly one `app.start` log is emitted per session, regardless of which path wins.

**Dependency:** add `implementation("androidx.lifecycle:lifecycle-process:2.8.7")` to `instrumentation/lifecycle/build.gradle.kts`. The artifact is ~30 KB; we already pull in `androidx.lifecycle:lifecycle-runtime-ktx:2.9.4` transitively in the demo apps.

### Part 2 — iOS SDK: late-init synthesis in `LifecycleInstrumentation`

**File:** `otel-ios-mobile/Sources/LifecycleInstrumentation/LifecycleInstrumentation.swift`

**Why:** iOS's `NotificationCenter` (unlike Android's `androidx.lifecycle.LifecycleRegistry`) has **no at-attach replay**. `UIApplication.didBecomeActiveNotification` fires once per foreground transition; if the SDK installs after that notification has already fired (RN's `useEffect` case), the observer never sees it and no initial `app.foreground` event lands. iOS native happens to install before `didBecomeActive` in the standard `UIApplicationDelegate` lifecycle and therefore doesn't hit this — but RN iOS does.

**Change:** in `LifecycleInstrumentation.install()`, after registering the NotificationCenter observers, check `UIApplication.shared.applicationState` on the main thread. If `.active`, synchronously invoke the existing `handleForeground()` path with an attribute `app.foreground.type = "instrumentation_late"` distinguishing it from natural-transition foreground events. Existing dedup via the `foregroundActive` flag prevents a duplicate fire when (rarely) `didBecomeActive` arrives moments later.

**Implementation shape:**

`LifecycleInstrumentation` is a singleton today (`public static let shared`, `private init() {}`). To keep that public API while making the synthesis path testable, add an `internal` init that accepts a state-provider closure, and have the singleton use the default:

```swift
public final class LifecycleInstrumentation: @unchecked Sendable {
    public static let shared = LifecycleInstrumentation()

    typealias ApplicationStateProvider = () -> UIApplication.State
    private let applicationStateProvider: ApplicationStateProvider

    private init() {
        self.applicationStateProvider = { UIApplication.shared.applicationState }
    }

    // Test-only init. Internal so it's accessible from @testable import in
    // the test target but not from external consumers.
    internal init(applicationStateProvider: @escaping ApplicationStateProvider) {
        self.applicationStateProvider = applicationStateProvider
    }

    public func install(tracer: Tracer?, logger: Logger) {
        // ... existing observer registration

        // Late-init synthesis: if the app is already foregrounded at install
        // time (RN useEffect case, or any deferred init), NotificationCenter
        // won't replay didBecomeActive — we must synthesize the initial
        // foreground event ourselves. Mirrors the Android emitAppStartIfLateInstall
        // path. Dispatched to main because UIApplication state is main-thread-only.
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            if self.applicationStateProvider() == .active {
                self.handleForeground(lateInstall: true)
            }
        }
    }
}
```

`handleForeground(lateInstall:)` adds `app.foreground.type = "instrumentation_late"` to the emitted log when called with `lateInstall: true`. The existing natural-transition callers (`addObserver` closures for `didBecomeActiveNotification` and `didActivateNotification`) call it with `lateInstall: false` (default value), tagging `app.foreground.type = "natural"`. Both call sites go through the same `foregroundActive` dedup so only one log fires per session-start. The default-parameter approach keeps the existing call sites compiling with no change.

**Test injection pattern:** unit tests instantiate their own `LifecycleInstrumentation` via the internal init, e.g. `LifecycleInstrumentation(applicationStateProvider: { .active })`, then drive `install()`/observers and assert on emitted log records via the same logger-fake the existing tests use. Production code-path is untouched — `LifecycleInstrumentation.shared` continues to use `UIApplication.shared.applicationState`.

**No counterpart needed for `app.background`:** if the SDK installs while the app is backgrounded, no `app.background` event is missed — there was no foreground state to leave. The next natural foreground/background cycle starts fresh.

### Part 3 — iOS RN bridge: enable `.lifecycle` by default

**File:** `examples/upstream-demo-app-rn/AstronomyShopRN/ios/AstronomyShopRN/OTelMobileCallSink.swift`

**Change:** in `parseAutoCaptureOptions`, treat `lifecycle` as enabled by default rather than requiring the JS-side token. The current code parses tokens from `config.nativeAutoCapture` (an array). The new behavior:

```swift
private static func parseAutoCaptureOptions(_ tokens: [String]) -> AutoCaptureOptions {
    var opts: AutoCaptureOptions = [.lifecycle]   // safe-by-default — see comment
    for tok in tokens {
        switch tok {
        case "tap":        opts.insert(.tap)
        case "scroll":     opts.insert(.scroll)
        case "lifecycle":  opts.insert(.lifecycle)   // explicit opt-in is no-op now
        case "screen":     opts.insert(.screen)
        case "network":    opts.insert(.network)
        case "errors":     opts.insert(.errors)
        case "freeze":     opts.insert(.freeze)
        case "vitals":     opts.insert(.vitals)
        default: break
        }
    }
    return opts
}
```

**Comment update:** explain that `.lifecycle` is safe with RN's event loop because it observes `UIApplication` notifications via NotificationCenter (no swizzling, no fatal-handler chaining, no synchronous JS callbacks). The other capabilities still need explicit opt-in via `autoCapture: { network: true }` etc. on the JS side.

### Part 4 — Demo + JS shim cleanup

**Files:**

- `examples/upstream-demo-app-rn/AstronomyShopRN/src/App.tsx` — remove `autoCapture: { lifecycle: false }` and the surrounding investigation comment block (lines 35-47 today). Add a one-line comment that lifecycle is now native.
- `packages/react-native/src/index.ts` — remove the `if (auto.lifecycle !== false) { autoInstrUninstallers.push(installAppStateInstrumentation()) }` block. Remove the `installAppStateInstrumentation` import. Remove `'lifecycle'` from `NATIVE_AUTO_CAPTURE_FLAGS` (the JS-to-bridge token list) — the Swift side now defaults `.lifecycle` on, the Android side has no per-capability gating, so passing the token is meaningless on both sides.
- `packages/react-native/src/bridge/types.ts` — remove the `lifecycle?: boolean` field from the `StartConfig.autoCapture` type. Per the user's no-tech-debt directive, the flag has no remaining behavior to control (the JS shim it gated is being deleted; native is on by default), so it shouldn't linger as a no-op API surface.
- `packages/react-native/src/instrumentation/appstate.ts` — delete the file.
- `packages/react-native/__tests__/instr/appstate.test.ts` — delete the file.

## Data flow

After this change, for a fresh app launch (the matchy-matchy Gate 1 scenario):

| Platform | Cold-launch sequence | Source of each event |
| --- | --- | --- |
| **iOS native** | `app.launch` → `app.foreground (natural)` | `app.launch` emitted unconditionally inside `LifecycleInstrumentation.install()`. Install runs in `didFinishLaunching` so `applicationState != .active` at install time; late-init synthesis is a no-op; `didBecomeActiveNotification` fires shortly after and produces the natural-path foreground. (`app.start` is a separate signal from `VitalsInstrumentation` measuring cold-start duration; orthogonal to this spec.) |
| **Android native** | `app.start (cold)` → `app.foreground` | `onActivityCreated` → `app.start (cold)` via the activity-callback path; ProcessLifecycleOwner observer's first `onStart` → `app.foreground` |
| **RN iOS** | `app.launch` → `app.foreground (instrumentation_late)` | `app.launch` still fires at install-time (unconditional). Late-init check sees `applicationState == .active` and synthesizes `app.foreground` with `app.foreground.type = "instrumentation_late"`. |
| **RN Android** | `app.start (instrumentation_late)` → `app.foreground` | `emitAppStartIfLateInstall()` synthesizes `app.start` because `useEffect` runs after Activity is started; `addObserver()` immediately replays `onStart` for `app.foreground` |

For subsequent bg/fg cycles, all four platforms behave identically at the human-paced scenarios the matchy-matchy runbook tests: each transition emits exactly one `app.foreground` or `app.background` log. (One subtle non-parity worth knowing: Android's ProcessLifecycleOwner debounces transitions within a 700ms window — a stress test that bg→fg→bg in <700ms emits fewer Android events than iOS, where every NotificationCenter post fires. Out of scope for Gate 1 validation; flagging for future stress-test work.)

## Error handling

- **`androidx.lifecycle:lifecycle-process` artifact missing at runtime.** Prevented at compile-time by an `implementation`-dependency. Defended against minification by adding a `consumer-rules.pro` keep rule for `androidx.lifecycle.ProcessLifecycleOwner` and `androidx.lifecycle.ProcessLifecycleInitializer`. If somehow the class is still stripped, `ProcessLifecycleOwner.get()` throws `NoClassDefFoundError` — we let it propagate rather than catching, because silent fallback to "no lifecycle telemetry" is worse than a loud crash that surfaces the misconfiguration immediately.
- **`Lifecycle.State.currentState` returns INITIALIZED at install-time on rare timing edges.** Treated as "no activity yet" — `emitAppStartIfLateInstall` is a no-op, the cold-start `onActivityCreated` path emits `app.start` whenever the first Activity actually creates.
- **Multiple `install()` calls.** Already idempotent via the existing `OTelMobile.start()` guard (`if (provider == null)`); no change.
- **iOS `applicationState` race.** `UIApplication.shared.applicationState` is checked from `DispatchQueue.main.async`, which serializes against the main thread. If the natural `didBecomeActiveNotification` lands between scheduling the dispatch and executing it, the existing `foregroundActive` dedup absorbs the second emission — only one `app.foreground` lands per session-start regardless of which path won the race.

## Testing

### Unit tests (Android)

Update `instrumentation/lifecycle/src/test/java/io/opentelemetry/android/mobile/instrumentation/LifecycleInstrumentationTest.kt`. The existing tests cover the cold-start path (`onActivityCreated → app.start`); they must continue to pass after the migration. Add the following:

- **New: `install_when_process_already_started_emits_app_start_late_and_foreground`.** Use Robolectric to bring `ProcessLifecycleOwner.get().lifecycle` to `STARTED` (resume an Activity) BEFORE calling `install()`. Expect, in order: 1× `app.start` with `app.start.type = "instrumentation_late"` and a positive `app.start.duration_ms` (synthesized by `emitAppStartIfLateInstall`), then 1× `app.foreground` (from the observer's at-attach replay). This single test exercises the entire late-init scenario and is the most important regression guard.
- **New: `process_foreground_emits_app_foreground`.** Use `ProcessLifecycleOwner.get().lifecycle` and a `LifecycleRegistry` fake (or Robolectric's controllable lifecycle) to send `ON_START` *after* install. Expect 1× `app.foreground` log (the post-install transition, not the at-attach replay).
- **New: `process_background_emits_app_background`.** Symmetric — send `ON_STOP`, expect `app.background` log.
- **New: `uninstall_removes_observer`.** After `uninstall()`, sending `ON_START` to ProcessLifecycleOwner produces no further emissions.

### Unit tests (iOS)

Update `otel-ios-mobile/Tests/LifecycleInstrumentationTests/` (Swift Testing). The existing tests cover the natural-path foreground/background via NotificationCenter — they construct via `LifecycleInstrumentation.shared` and don't need to change. The new tests construct their own instance via the internal `init(applicationStateProvider:)` to drive the late-init path deterministically:

- **New: `install_when_app_already_active_emits_late_foreground`.** Construct `LifecycleInstrumentation(applicationStateProvider: { .active })`, call `install(tracer:logger:)` with a recording logger, await one main-queue tick, assert the recorded logs contain exactly one `app.foreground` with `app.foreground.type = "instrumentation_late"`.
- **New: `late_install_does_not_double_emit_when_natural_didBecomeActive_arrives`.** Same construction; after the late-init synthesis emits, post `UIApplication.didBecomeActiveNotification` synthetically. Assert: still one log (foregroundActive dedup absorbed the second). Then post `didEnterBackgroundNotification` followed by another `didBecomeActiveNotification` and assert two more logs (one background, one natural foreground).
- **New: `install_when_app_inactive_does_not_emit_foreground`.** Construct `LifecycleInstrumentation(applicationStateProvider: { .inactive })` (and a separate variant with `.background`), call `install`, await one main-queue tick, assert zero `app.foreground` logs recorded.

### Manual on-device validation (matchy-matchy)

Both RN runbooks ([rn-android.md](../../matchy-matchy/rn-android.md) §1, [rn-ios.md](../../matchy-matchy/rn-ios.md) §1) get re-run after the fix. Pass criteria are the canonical Gate 1: launch + 2 bg/fg cycles, with platform-specific event-name conventions:

- **Android (RN + native):** 1× `app.start` (body — `event.name` may be empty), 3× `app.foreground`, 2× `app.background`, scope `io.opentelemetry.android.mobile.lifecycle`. RN Android's first `app.start` will have `app.start.type = "instrumentation_late"`; subsequent runs after a process kill will too. Native Android's first `app.start` has `app.start.type = "cold"`.
- **iOS (RN + native):** 1× `app.launch`, 3× `app.foreground`, 2× `app.background`, scope `io.dash0.mobile.lifecycle`. RN iOS's first `app.foreground` will have `app.foreground.type = "instrumentation_late"`; subsequent ones tagged `"natural"`. iOS's `app.start` and `app.startup` span come from `VitalsInstrumentation`, not lifecycle instrumentation — orthogonal to this spec, but the runbook lists them so don't be confused if they appear in the same query.

Both native runbooks ([android-native.md](../../matchy-matchy/android-native.md), [ios-native.md](../../matchy-matchy/ios-native.md)) get a **regression smoke** — re-run Gate 1 to confirm the ProcessLifecycleOwner migration didn't break the existing cold-start path on Android, and the `applicationState`-check addition didn't double-emit on iOS.

### Cross-platform parity check

iOS Swift `LifecycleInstrumentation` already uses `UIApplication.didBecomeActiveNotification` / `UIApplication.didEnterBackgroundNotification` — those are process-level notifications, semantically equivalent to ProcessLifecycleOwner's `onStart`/`onStop`. The user-observable result of bg/fg cycles matches across platforms after this spec lands; the underlying mechanisms differ (Android's `addObserver` has at-attach replay; iOS NotificationCenter doesn't, which is why iOS gets an explicit `applicationState`-check synthesis path).

## Build sequence

1. Add the `lifecycle-process` dep to `instrumentation/lifecycle/build.gradle.kts`.
2. Refactor `LifecycleInstrumentation.kt` to use ProcessLifecycleOwner + late-install synthesis.
3. Update Android unit tests (4 new, existing pass).
4. `./gradlew :instrumentation-lifecycle:test` — green.
5. Edit `LifecycleInstrumentation.swift` for iOS late-init synthesis. Update iOS unit tests (3 new, existing pass).
6. `./run-tests.sh --ios` — green.
7. Publish SDK to mavenLocal so the RN-Android consumer can resolve the new artifact: `./gradlew :instrumentation-lifecycle:publishToMavenLocal` plus any modules that depend transitively (verify via `./gradlew :otel-android-mobile:dependencies` whether `otel-android-mobile` needs republishing for the POM to reference the new lifecycle artifact version).
8. Edit iOS bridge `OTelMobileCallSink.swift` `parseAutoCaptureOptions`.
9. Edit `App.tsx`, `packages/react-native/src/index.ts`, `packages/react-native/src/bridge/types.ts`. Delete `appstate.ts` + its test.
10. `./run-tests.sh --rn` — green (no appstate test references remaining; type-deletion compiles).
11. Rebuild RN demo bundle (`touch index.js`) + Android APK; rebuild iOS app via Xcode/xcodebuild.
12. Re-run matchy-matchy runbooks for all 4 platforms; capture Dash0 evidence; flip the matrix cells.

## Files modified summary

| File | Change |
| --- | --- |
| `instrumentation/lifecycle/build.gradle.kts` | +1 line: `implementation("androidx.lifecycle:lifecycle-process:2.8.7")` |
| `instrumentation/lifecycle/consumer-rules.pro` | +keep rules for `androidx.lifecycle.ProcessLifecycleOwner` and `androidx.lifecycle.ProcessLifecycleInitializer` to defend against R8 minification |
| `instrumentation/lifecycle/.../LifecycleInstrumentation.kt` | Refactor: counter logic → ProcessLifecycleOwner; add `emitAppStartIfLateInstall` synthesis |
| `instrumentation/lifecycle/.../LifecycleInstrumentationTest.kt` | +4 new tests; existing tests pass |
| `otel-ios-mobile/Sources/LifecycleInstrumentation/LifecycleInstrumentation.swift` | Add `applicationState`-check synthesis after observer registration; thread `app.foreground.type` attribute through `handleForeground` |
| `otel-ios-mobile/Tests/LifecycleInstrumentationTests/...` | +3 new tests for the late-init synthesis path |
| `examples/upstream-demo-app-rn/AstronomyShopRN/ios/.../OTelMobileCallSink.swift` | `parseAutoCaptureOptions` defaults to `[.lifecycle]` |
| `examples/upstream-demo-app-rn/AstronomyShopRN/src/App.tsx` | Remove `autoCapture: { lifecycle: false }` + investigation comment |
| `packages/react-native/src/index.ts` | Remove `installAppStateInstrumentation` call + import; remove `'lifecycle'` from `NATIVE_AUTO_CAPTURE_FLAGS` |
| `packages/react-native/src/bridge/types.ts` | Remove `lifecycle?: boolean` field from `StartConfig.autoCapture` |
| `packages/react-native/src/instrumentation/appstate.ts` | DELETE |
| `packages/react-native/__tests__/instr/appstate.test.ts` | DELETE |
| `docs/matchy-matchy/rn-android.md` | Header + §1 status flip 🔴 → 🟢 (after manual validation) |
| `docs/matchy-matchy/rn-ios.md` | Header + §1 status flip 🔴 → 🟢 (after manual validation) |
| `docs/matchy-matchy/README.md` | Status table cells |
| `docs/epics/VALIDATION_MATRIX_EPIC.md` | RN Android + RN iOS Gate 1 cells |

## Risks

- **`androidx.lifecycle:lifecycle-process` adds a transitive dep to a core SDK module.** ~30 KB. Acceptable — every app targeting modern Android already pulls it transitively via Jetpack.
- **Late-install synthesis path emits `app.start` with a less-precise duration** on Android (no anchor to `Application.onCreate` time when start() is deferred). Mitigation: `app.start.type = "instrumentation_late"` lets consumers filter; duration is best-effort against `Process.getStartUptimeMillis()`.
- **iOS late-init `app.foreground` may race with a natural `didBecomeActive`** if iOS posts the notification on the same run-loop tick as install. Mitigation: existing `foregroundActive` flag dedups; if both fire, only one log is emitted. Worst case is a missed-by-1ms `app.foreground.type = "natural"` instead of `"instrumentation_late"` — same event, different attribute.
- **Removing the `autoCapture.lifecycle?: boolean` field from the RN public API is technically a breaking change.** Per the user's explicit no-tech-debt directive (and zero known external consumers — the SDK isn't published yet), removing rather than deprecating is the right call. Document in the changelog so the next consumer knows the field is gone.
