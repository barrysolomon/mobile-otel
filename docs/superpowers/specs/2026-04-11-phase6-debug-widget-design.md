# Phase 6: Embeddable Debug Widget — Design Specification

**Date:** 2026-04-11
**Status:** Draft
**Scope:** In-app debug overlay showing real-time SDK status (buffer stats, export status, device health). Separate instrumentation module, opt-in via config.

---

## 1. Problem

During demos and debugging, there's no way to see SDK state without opening logcat or the separate RingBufferActivity. A lightweight always-on overlay showing buffer occupancy, export health, and device context would make demos more compelling and debugging faster.

## 2. Architecture

New instrumentation module at `instrumentation/debug-widget/`. Follows the same pattern as screenshot and wireframe — opt-in, non-OTel-standard, incubating.

### Module structure

```text
instrumentation/debug-widget/
├── build.gradle.kts
├── consumer-rules.pro
└── src/main/java/io/opentelemetry/android/mobile/debug/
    ├── DebugWidgetInstrumentation.kt   — MobileInstrumentation, ActivityLifecycleCallbacks
    ├── DebugBadgeView.kt               — 32px draggable circle overlay
    ├── DebugCardView.kt                — expanded 2-column stats card
    └── DebugWidgetDataSource.kt        — polls/listens for SDK + device state
```

### Integration flow

```text
App adds dependency:
  implementation(project(":instrumentation-debug-widget"))

MobileConfig includes:
  debugWidgetConfig = DebugWidgetConfig(enabled = true)

OTelMobile.start()
  → InstrumentationRegistry discovers DebugWidgetInstrumentation
  → install() registers ActivityLifecycleCallbacks
  → onActivityResumed: adds DebugBadgeView to Activity's decorView
  → onActivityPaused: removes badge from previous Activity
  → DebugWidgetDataSource starts polling every 2s
  → Badge color reflects export status (green/red/orange)
  → Tap badge → show DebugCardView with 2-column stats grid
  → Tap outside or badge again → collapse
```

### Key design decisions

- **Programmatic Views, no XML layouts** — avoids resource ID conflicts with host apps
- **decorView overlay via ActivityLifecycleCallbacks** — no permissions needed, follows LeakCanary/Flipper pattern. Badge attaches in `onActivityResumed`, detaches in `onActivityPaused`
- **No telemetry emitted** — purely observational. Prevents observer-effect feedback loops
- **Config-gated** — `DebugWidgetConfig(enabled = false)` default. Module on classpath does nothing unless enabled
- **Long-press to drag** — badge is repositionable to any corner

## 3. Config

Follows the screenshot/wireframe pattern:

```kotlin
@Incubating
data class DebugWidgetConfig(
    val enabled: Boolean = false,
    val refreshIntervalMs: Long = 2000,
    val initialCorner: Corner = Corner.TOP_RIGHT
) {
    enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
}
```

Added to `MobileConfig`:

```kotlin
data class MobileConfig(
    // ... existing fields ...
    @Incubating val debugWidgetConfig: DebugWidgetConfig = DebugWidgetConfig(enabled = false)
)
```

## 4. Components

### DebugWidgetInstrumentation

Implements `MobileInstrumentation`. Registers `ActivityLifecycleCallbacks` to attach/detach the badge across activity transitions.

```kotlin
class DebugWidgetInstrumentation(
    private val config: DebugWidgetConfig = DebugWidgetConfig()
) : MobileInstrumentation {
    override val instrumentationName = "io.opentelemetry.android.mobile.debug-widget"

    override fun install(application: Application, context: InstrumentationContext) {
        if (!config.enabled) return
        val dataSource = DebugWidgetDataSource(context)
        application.registerActivityLifecycleCallbacks(
            DebugWidgetLifecycleCallbacks(dataSource, config)
        )
    }

    override fun uninstall() {
        // Unregister callbacks, remove views
    }
}
```

### DebugBadgeView

32dp circle, semi-transparent dark background, border color reflects status:
- Green (`#4CAF50`) — last export succeeded
- Red (`#F44336`) — auth error or export failure
- Orange (`#FF9800`) — retrying

Supports:
- **Tap** → toggle DebugCardView visibility
- **Long-press + drag** → reposition to any screen edge
- **Remembers position** across activity transitions (stored in-memory)

Implementation: extends `View`, custom `onDraw()` with `Canvas.drawCircle()` + status icon. Added to `FrameLayout` (decorView's content) with `LayoutParams` for positioning.

### DebugCardView

Dark translucent card (95% opacity `#0F0F19`) with rounded corners. Appears anchored to the badge position. Contains:

**Header row:**
```text
● OTel Debug                      v1.0.0
```

**SDK state (2-column grid):**
```text
RAM        42/5000
Disk       18 events
Export     OK (24 evts)
Recovery   crash
```

**Device health (2-column grid):**
```text
Battery    78%
Memory     412 MB
Network    wifi
Last flush 3s ago
```

**Footer:**
```text
Session a3f2...81c0              ↻ 2s
```

Implementation: extends `FrameLayout`, builds child TextViews programmatically. Updates every `refreshIntervalMs` via a `Handler.postDelayed` loop.

### DebugWidgetDataSource

Aggregates state from multiple SDK APIs:

```kotlin
class DebugWidgetDataSource(private val context: InstrumentationContext) {

    data class WidgetState(
        val ramEvents: Int,
        val ramCapacity: Int,
        val diskEvents: Int,
        val exportStatus: ExportStatus?,
        val recoveryType: String?,
        val batteryPercent: Int,
        val memoryAvailableMb: Int,
        val networkType: String,
        val lastFlushAgoMs: Long,
        val sessionId: String
    )

    fun getState(): WidgetState { ... }
}
```

Data sources:
- **BufferStats** — `MobileOtel.getBufferStats()` (RAM count, disk count, capacities)
- **ExportStatus** — `ExportStatusManager.addListener()` (last status cached)
- **Recovery type** — `OTelMobile.getLastRecoveryType()`
- **Battery** — `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)`
- **Memory** — `ActivityManager.getMemoryInfo()` → `availMem / (1024 * 1024)`
- **Network** — `ConnectivityManager.getActiveNetwork()` → transport type
- **Last flush** — tracked via `ExportStatus.Success` timestamp
- **Session ID** — `context.sessionProvider.getSessionId()`

## 5. Files

### New files

| File | Purpose |
| --- | --- |
| `instrumentation/debug-widget/build.gradle.kts` | Module build config |
| `instrumentation/debug-widget/consumer-rules.pro` | Empty proguard rules |
| `.../debug/DebugWidgetInstrumentation.kt` | MobileInstrumentation impl |
| `.../debug/DebugBadgeView.kt` | Draggable circle badge |
| `.../debug/DebugCardView.kt` | Expanded stats card |
| `.../debug/DebugWidgetDataSource.kt` | State aggregation |
| `.../debug/DebugWidgetConfig.kt` | Config data class |

### Modified files

| File | Change |
| --- | --- |
| `otel-android-mobile/src/main/java/.../config/MobileConfig.kt` | Add `debugWidgetConfig` field |
| `examples/demo-app/settings.gradle.kts` | Include `:instrumentation-debug-widget` |
| `examples/demo-app/android/build.gradle.kts` | Add dependency |
| `examples/demo-app/.../DemoApp.kt` | Pass config with `debugWidgetConfig = DebugWidgetConfig(enabled = true)` |

## 6. Testing

- **Unit test:** `DebugWidgetDataSourceTest` — mock MobileOtel, verify WidgetState assembly
- **Unit test:** `DebugWidgetInstrumentationTest` — verify install/uninstall with config enabled/disabled
- **Manual test:** Run demo app, verify badge appears, tap expands card, drag repositions, data refreshes

## 7. What's NOT in Scope

- Flush control buttons in the widget (use RingBufferActivity for that)
- Event type histogram (use RingBufferActivity)
- Compose UI (programmatic Views only — simpler, no Compose dependency)
- Widget for iOS (future — when iOS SDK exists)
