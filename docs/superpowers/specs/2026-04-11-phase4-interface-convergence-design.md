# Phase 4: Interface Convergence — Design Specification

**Date:** 2026-04-11
**Status:** Draft
**Scope:** Bump upstream opentelemetry-android from 0.10.0-alpha to 1.2.0, converge `MobileInstrumentation` with `AndroidInstrumentation`, align context shapes, remove dead adapters.
**Parent Epic:** `docs/epics/UPSTREAM_SUPERSESSION_EPIC.md` (Phase 4, US-026 through US-031)

---

## 1. Problem

Our SDK depends on upstream `opentelemetry-android:0.10.0-alpha` — 12 versions behind the current `1.2.0` stable. The upstream API has evolved significantly:

- `AndroidInstrumentation` gained a `val name: String` property (the blocker for Phase 4)
- `InstallationContext` changed: `Application` → `Context`, added `Clock`, `SessionManager` → `SessionProvider`
- `SessionManager` removed from public API (moved to internal `android-agent`)
- Artifact coordinate changed: `io.opentelemetry.android:instrumentation-android-instrumentation` → `io.opentelemetry.android.instrumentation:android-instrumentation`

Our adapter layer (`UpstreamInstrumentationAdapter`, `MobileInstrumentationAdapter`, `SessionProviderAsSessionManager`) was designed as scaffolding for this convergence. Now that upstream has the `name` property, the scaffolding can be mostly removed.

## 2. Approach: Big Bang Convergence

Single coordinated change that:

1. Bumps upstream dependency to 1.2.0-alpha
2. Makes `MobileInstrumentation extend AndroidInstrumentation`
3. Aligns `InstrumentationContext` with upstream's `InstallationContext`
4. Simplifies `UpstreamInstrumentationAdapter` (no longer needs explicit name or session bridge)
5. Deletes `MobileInstrumentationAdapter` and `SessionProviderAsSessionManager`
6. Updates registry conflict resolution and builder discovery

Zero changes to the 18 instrumentation modules — the interface change is backward-compatible via default method implementations.

## 3. Dependency Bump

In `otel-android-mobile-core/build.gradle.kts`:

```kotlin
// Before
api("io.opentelemetry.android:session:0.10.0-alpha") {
    exclude(group = "io.opentelemetry", module = "opentelemetry-api-incubator")
}
api("io.opentelemetry.android:instrumentation-android-instrumentation:0.10.0-alpha") {
    exclude(group = "io.opentelemetry", module = "opentelemetry-api-incubator")
}

// After — note: group ID changed for instrumentation artifact
api("io.opentelemetry.android:session:1.2.0-alpha") {
    exclude(group = "io.opentelemetry", module = "opentelemetry-api-incubator")
}
api("io.opentelemetry.android.instrumentation:android-instrumentation:1.2.0-alpha") {
    exclude(group = "io.opentelemetry", module = "opentelemetry-api-incubator")
}
```

### Compatibility notes

- **Artifact coordinate change:** `io.opentelemetry.android:instrumentation-android-instrumentation` → `io.opentelemetry.android.instrumentation:android-instrumentation` (group ID changed at 0.11.0)
- **Session artifact:** Group stays `io.opentelemetry.android:session`
- **New transitive deps:** `services:1.2.0-alpha`, `agent-api:1.2.0-alpha`, instrumentation BOM `2.25.0-alpha`
- **Kotlin version:** Upstream uses `2.2.21`, we use `2.2.10` — binary compatible, Gradle resolves to higher
- **OTel SDK version:** Upstream managed by BOM (~1.59.0), our explicit `api("...sdk:1.58.0")` pins take precedence
- **Keep `opentelemetry-api-incubator` exclusion** — conservative, test later if still needed. May also need exclusion on new transitive deps (`services`, `agent-api`) if they pull it in.

## 4. Interface Convergence

### Upstream's current interface (1.2.0)

```kotlin
interface AndroidInstrumentation {
    val name: String
    fun install(ctx: InstallationContext)
    fun uninstall(ctx: InstallationContext) {}
}
```

### Upstream's InstallationContext (1.2.0)

```kotlin
class InstallationContext(
    val context: Context,          // was Application in 0.10.0
    val openTelemetry: OpenTelemetry,
    val sessionProvider: SessionProvider,  // was SessionManager in 0.10.0
    val clock: Clock,              // new in 1.x
) {
    val application: Application? = context as? Application  // nullable convenience
}
```

### Our current interface

```kotlin
interface MobileInstrumentation {
    val instrumentationName: String
    val instrumentationVersion: String get() = "1.0.0"
    fun install(application: Application, context: InstrumentationContext)
    fun uninstall() {}
}
```

### Converged interface

```kotlin
interface MobileInstrumentation : AndroidInstrumentation {
    val instrumentationName: String
    val instrumentationVersion: String get() = "1.0.0"

    // Bridge to upstream — name delegates to our identity property
    override val name: String get() = instrumentationName

    // Our rich install — what all 18 modules implement
    fun install(application: Application, context: InstrumentationContext)

    // Upstream's install — default bridges to ours
    override fun install(ctx: InstallationContext) {
        val app = ctx.application
            ?: throw IllegalStateException(
                "MobileInstrumentation requires Application context"
            )
        install(app, InstrumentationContext.fromInstallationContext(ctx))
    }

    // Upstream's uninstall — bridges to our no-arg version
    override fun uninstall(ctx: InstallationContext) {
        uninstall()
    }

    fun uninstall() {}
}
```

### Design decisions

- `instrumentationName` stays as the primary property. All 18 modules use it. `name` is a computed bridge via `override val name get() = instrumentationName`.
- `instrumentationVersion` stays — upstream doesn't have it, it's our extension.
- Default `install(InstallationContext)` bridges to `install(Application, InstrumentationContext)` so existing modules need zero changes.
- `IllegalStateException` if upstream passes non-Application `Context` — our modules require `Application` for lifecycle callbacks, `registerActivityLifecycleCallbacks`, etc.

### Impact on instrumentation modules

**Zero code changes to any of the 14 build-wired modules** (lifecycle, screen, tap, scroll, text-input, back-press, freeze, errors, network, vitals, screenshot, wireframe, screen-orientation, compose-click). They implement `install(application, context)`. The new upstream-facing methods have default implementations that bridge to the existing signatures.

4 additional stub modules (database, file-io, system-events, timber) have source files but are not wired into `settings.gradle.kts` — they should also compile unchanged but won't be verified in the build.

## 5. InstrumentationContext Alignment

### Factory method for upstream bridge

```kotlin
class InstrumentationContext(
    val openTelemetry: OpenTelemetry,
    val sessionProvider: MobileSessionProvider,
    val windowEventHub: WindowEventHub,
    val application: Application,
    val uiTelemetryMode: UiTelemetryMode = UiTelemetryMode.EVENTS,
    val breadcrumbManager: BreadcrumbManager? = if (BreadcrumbManager.isInitialized()) BreadcrumbManager else null,
    val clock: Clock? = null
) {
    companion object {
        /**
         * Build from upstream's InstallationContext.
         * Used by the default install(InstallationContext) bridge in MobileInstrumentation.
         */
        fun fromInstallationContext(ctx: InstallationContext): InstrumentationContext {
            val app = ctx.application
                ?: throw IllegalStateException("Application context required")
            // Create and install a WindowEventHubInstaller so touch/key events
            // are dispatched to modules installed via the upstream bridge path.
            val hub = WindowEventHub()
            WindowEventHubInstaller(app, hub).install()
            return InstrumentationContext(
                openTelemetry = ctx.openTelemetry,
                sessionProvider = UpstreamSessionProviderAdapter(ctx.sessionProvider),
                windowEventHub = hub,
                application = app,
                clock = ctx.clock
            )
        }
    }

    // Existing convenience methods unchanged:
    // addBreadcrumb(), tracer(), logger(), meter()
}
```

### Session bridge

`MobileSessionProvider` already extends `SessionProvider`:

```kotlin
interface MobileSessionProvider : SessionProvider { ... }
```

This means:

- **Our → upstream:** `MobileSessionProvider` IS a `SessionProvider`, passes directly to `InstallationContext`
- **Upstream → ours:** `UpstreamSessionProviderAdapter` wraps a bare `SessionProvider` as `MobileSessionProvider` (existing class, unchanged)
- **`SessionProviderAsSessionManager`:** Deleted. `SessionManager` no longer exists in upstream's public API.

## 6. UpstreamInstrumentationAdapter — Simplified

Still needed for upstream-only `AndroidInstrumentation` modules discovered via ServiceLoader (they don't implement `MobileInstrumentation`). But dramatically simplified:

```kotlin
// Before: needed explicit name, built SessionManager bridge
class UpstreamInstrumentationAdapter(
    private val upstream: AndroidInstrumentation,
    override val instrumentationName: String  // manual, upstream had no name
) : MobileInstrumentation {
    override fun install(application: Application, context: InstrumentationContext) {
        val sessionManager = SessionProviderAsSessionManager(context.sessionProvider)
        val installCtx = InstallationContext(application, context.openTelemetry, sessionManager)
        upstream.install(installCtx)
    }
}

// After: uses upstream.name, no session bridge
class UpstreamInstrumentationAdapter(
    private val upstream: AndroidInstrumentation
) : MobileInstrumentation {
    override val instrumentationName: String = upstream.name

    override fun install(application: Application, context: InstrumentationContext) {
        upstream.install(InstallationContext(
            application,
            context.openTelemetry,
            context.sessionProvider,  // MobileSessionProvider IS SessionProvider
            context.clock ?: Clock.getDefault()
        ))
    }
}
```

## 7. InstrumentationRegistry — Conflict Resolution

The current check `inst is UpstreamInstrumentationAdapter` is replaced with a name-based check that works regardless of wrapper type:

```kotlin
fun install(application: Application, context: InstrumentationContext) {
    sessionProvider = context.sessionProvider

    // Collect names that native modules supersede
    val supersededNames = mutableSetOf<String>()
    for (inst in instrumentations) {
        val ann = inst::class.java.getAnnotation(Supersedes::class.java)
        if (ann != null) {
            supersededNames.addAll(ann.names)
        }
    }

    for (inst in instrumentations) {
        // Skip modules whose name is superseded, unless they're the superseder
        // (modules WITH @Supersedes are our native replacements — they go through)
        if (inst.instrumentationName in supersededNames
            && inst::class.java.getAnnotation(Supersedes::class.java) == null
        ) {
            Log.i(TAG, "Skipping ${inst.instrumentationName} -- superseded")
            continue
        }
        inst.install(application, context)
        installed.add(inst)
    }
}
```

Logic: if a module's name is in the superseded set AND it doesn't carry `@Supersedes` itself, it's an upstream module being replaced. Modules WITH `@Supersedes` are our native replacements.

**Behavior change note:** The old logic only skipped `UpstreamInstrumentationAdapter` instances. The new logic is broader — it skips *any* module whose name is in the superseded set, regardless of type. In practice this doesn't matter because our module names (e.g., `io.opentelemetry.android.mobile.tap`) never collide with the short superseded names (e.g., `"view.click"`, `"crash"`). But if someone manually adds a `MobileInstrumentation` with `instrumentationName = "view.click"` and no `@Supersedes`, it would be silently skipped. This is acceptable — such a module should have `@Supersedes` if it intentionally replaces an upstream module.

## 8. OTelMobileBuilder — Discovery Updates

```kotlin
// discoverUpstreamInstrumentations() — simplified
fun discoverUpstreamInstrumentations(): OTelMobileBuilder = apply {
    ServiceLoader.load(
        AndroidInstrumentation::class.java,
        AndroidInstrumentation::class.java.classLoader
    ).forEach { upstream ->
        // Our modules extend both interfaces — skip them here to avoid double-registration
        if (upstream !is MobileInstrumentation) {
            instrumentations.add(UpstreamInstrumentationAdapter(upstream))
        }
    }
}

// discoverAllInstrumentations() — simplified
fun discoverAllInstrumentations(): OTelMobileBuilder = apply {
    discoverInstrumentations()
    val existingNames = instrumentations.map { it.instrumentationName }.toSet()
    ServiceLoader.load(
        AndroidInstrumentation::class.java,
        AndroidInstrumentation::class.java.classLoader
    ).forEach { upstream ->
        if (upstream !is MobileInstrumentation && upstream.name !in existingNames) {
            instrumentations.add(UpstreamInstrumentationAdapter(upstream))
        }
    }
}
```

Key change: `upstream !is MobileInstrumentation` guard prevents double-registration since our modules now implement both interfaces and are discovered by both ServiceLoaders.

## 9. Files

### Modified files

| File | Change |
| --- | --- |
| `otel-android-mobile-core/build.gradle.kts` | Bump deps to 1.2.0-alpha, new artifact coordinates |
| `MobileInstrumentation.kt` | Extend `AndroidInstrumentation`, add `name` bridge + default `install(ctx)` |
| `InstrumentationContext.kt` | Add `fromInstallationContext()` companion factory |
| `UpstreamInstrumentationAdapter.kt` | Simplify: use `upstream.name`, drop `SessionProviderAsSessionManager`, new `InstallationContext` constructor. Delete inner `SessionProviderAsSessionManager` class. |
| `InstrumentationRegistry.kt` | Name-based conflict resolution (drop `is UpstreamInstrumentationAdapter`) |
| `OTelMobileBuilder.kt` | `!is MobileInstrumentation` guard in discovery, drop explicit name extraction |
| `SupersedesConflictTest.kt` | Update conflict resolution assertions for name-based logic |
| `UpstreamInstrumentationAdapterTest.kt` | Update for simplified adapter (no explicit name param, new InstallationContext constructor) |

### Deleted files

| File | Reason |
| --- | --- |
| `MobileInstrumentationAdapter.kt` | Reverse bridge no longer needed — our modules ARE `AndroidInstrumentation` |
| `MobileInstrumentationAdapterTest.kt` | Tests for deleted adapter |

**Build sequencing:** `MobileInstrumentationAdapter.kt` and `MobileInstrumentationAdapterTest.kt` both import `SessionManager` from the old upstream API. They must be deleted *before* (or in the same commit as) the dependency bump, otherwise the build breaks with unresolved `SessionManager` import. Similarly, `UpstreamInstrumentationAdapter.kt` imports `SessionManager` and `SessionObserver` — the simplified replacement must land in the same commit as the bump.

### New files

| File | Purpose |
| --- | --- |
| `InterfaceConvergenceTest.kt` | Verify `MobileInstrumentation` IS `AndroidInstrumentation`, `name` delegates to `instrumentationName`, default `install(InstallationContext)` bridges correctly |

### Unchanged (14 build-wired instrumentation modules)

All 14 build-wired modules (lifecycle, screen, tap, scroll, text-input, back-press, freeze, errors, network, vitals, screenshot, wireframe, screen-orientation, compose-click) require **zero code changes**. The interface change is backward-compatible via default method implementations. 4 additional stubs (database, file-io, system-events, timber) are not build-wired but should also be source-compatible.

## 10. Testing

- **All 194+ behavioral config tests** — should pass with zero changes (modules unchanged)
- **`SupersedesConflictTest`** — update:
  - `fakeUpstream(name)` helper uses 2-arg constructor `UpstreamInstrumentationAdapter(upstream, name)` — change to 1-arg and mock `upstream.name` instead: `every { upstream.name } returns name`
  - Remove `UpstreamInstrumentationAdapter` type checks in assertions, verify name-based skip logic
- **`InterfaceConvergenceTest`** (new) — verify:
  - `MobileInstrumentation` instance IS `AndroidInstrumentation`
  - `name` property returns `instrumentationName`
  - Default `install(InstallationContext)` delegates to `install(Application, InstrumentationContext)`
  - Default `uninstall(InstallationContext)` delegates to `uninstall()`
  - `fromInstallationContext()` correctly builds `InstrumentationContext` with wired `WindowEventHub`
- **`UpstreamInstrumentationAdapterTest`** — update:
  - All `UpstreamInstrumentationAdapter(upstream, "name")` calls → `UpstreamInstrumentationAdapter(upstream)` with `every { upstream.name } returns "name"` on the mock
  - Change `it.sessionManager` references to `it.sessionProvider` (field renamed in upstream)
  - Update `InstallationContext` construction to 4-arg form `(context, otel, sessionProvider, clock)`
  - Update `it.application` assertions to handle nullable `Application?` type (use `==` not `===`, or assert non-null)
- **Compile check** — all 14 build-wired modules compile without changes

## 11. What's NOT in Scope

- Bumping our OTel SDK from 1.58.0 to 1.59.0 (upstream's version) — separate concern
- Removing the `opentelemetry-api-incubator` exclusion — test later
- Bumping semconv from 1.39.0 to 1.40.0 — separate concern
- Upstream contribution (Phase 6) — depends on Phase 4 completing
- iOS port impact (US-030) — noted in epic, separate spec
