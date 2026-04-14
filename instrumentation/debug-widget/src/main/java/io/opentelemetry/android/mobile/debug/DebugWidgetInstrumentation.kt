// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.debug

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import io.opentelemetry.android.mobile.instrumentation.InstrumentationContext
import io.opentelemetry.android.mobile.instrumentation.Incubating
import io.opentelemetry.android.mobile.instrumentation.MobileInstrumentation

/**
 * Incubating instrumentation that overlays a draggable debug badge on every activity.
 *
 * The badge shows the latest [ExportStatus][io.opentelemetry.android.mobile.export.ExportStatus]
 * at a glance (green/red/orange circle). Tapping it toggles a detail card with:
 *
 * - **SDK state**: RAM buffer occupancy, disk event count, export status, recovery type
 * - **Device health**: battery %, available memory, network type, time since last flush
 *
 * The widget attaches to the decor view of each resumed activity and detaches on pause,
 * preserving badge position across activity transitions. A periodic handler refreshes
 * the data on a configurable interval (default 2s).
 *
 * **Not part of the OTel spec.** Intended for development and demo builds only.
 */
@Incubating
class DebugWidgetInstrumentation(
    private val config: DebugWidgetConfig = DebugWidgetConfig()
) : MobileInstrumentation {

    override val instrumentationName = "io.opentelemetry.android.mobile.debug-widget"

    private var dataSource: DebugWidgetDataSource? = null
    private var badge: DebugBadgeView? = null
    private var card: DebugCardView? = null
    private var handler: Handler? = null
    private var refreshRunnable: Runnable? = null
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var currentActivity: Activity? = null
    private var installedApplication: Application? = null

    // Remember badge position across activities
    private var badgeX: Float = -1f
    private var badgeY: Float = -1f

    override fun install(application: Application, context: InstrumentationContext) {
        if (!config.enabled) return

        // Enable RingBufferActivity if it exists in the host app (disabled by default in manifest)
        try {
            val ringBufferClass = "${application.packageName}.ui.debug.RingBufferActivity"
            val component = android.content.ComponentName(application.packageName, ringBufferClass)
            application.packageManager.setComponentEnabledSetting(
                component,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) { /* RingBufferActivity not in this app */ }

        installedApplication = application
        dataSource = DebugWidgetDataSource(context).also { it.start() }
        handler = Handler(Looper.getMainLooper())

        lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                currentActivity = activity
                attachToActivity(activity)
                startRefresh()
            }
            override fun onActivityPaused(activity: Activity) {
                if (currentActivity == activity) {
                    saveBadgePosition()
                    detachFromActivity(activity)
                    stopRefresh()
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
    }

    override fun uninstall() {
        stopRefresh()
        currentActivity?.let { detachFromActivity(it) }
        lifecycleCallbacks?.let { installedApplication?.unregisterActivityLifecycleCallbacks(it) }
        lifecycleCallbacks = null
        dataSource?.stop()
        dataSource = null
        badge = null
        card = null
        installedApplication = null
    }

    private fun attachToActivity(activity: Activity) {
        val decorContent = activity.window.decorView as? FrameLayout ?: return

        val newCard = DebugCardView(activity)
        val newBadge = DebugBadgeView(activity) { newCard.toggle() }

        // Position badge
        val density = activity.resources.displayMetrics.density
        val margin = (8 * density)
        if (badgeX >= 0 && badgeY >= 0) {
            newBadge.x = badgeX
            newBadge.y = badgeY
        } else {
            // Initial position based on config corner
            val displayWidth = activity.resources.displayMetrics.widthPixels
            val badgeSize = (32 * density)
            when (config.initialCorner) {
                DebugWidgetConfig.Corner.TOP_RIGHT -> {
                    newBadge.x = displayWidth - badgeSize - margin
                    newBadge.y = (40 * density) // below status bar
                }
                DebugWidgetConfig.Corner.TOP_LEFT -> {
                    newBadge.x = margin
                    newBadge.y = (40 * density)
                }
                DebugWidgetConfig.Corner.BOTTOM_RIGHT -> {
                    newBadge.x = displayWidth - badgeSize - margin
                    newBadge.y = activity.resources.displayMetrics.heightPixels - badgeSize - (60 * density)
                }
                DebugWidgetConfig.Corner.BOTTOM_LEFT -> {
                    newBadge.x = margin
                    newBadge.y = activity.resources.displayMetrics.heightPixels - badgeSize - (60 * density)
                }
            }
        }

        // Position card relative to badge, clamped to screen bounds
        val screenW = activity.resources.displayMetrics.widthPixels.toFloat()
        val screenH = activity.resources.displayMetrics.heightPixels.toFloat()
        val cardW = 270 * density
        val cardH = 520 * density  // estimated max height with all rows + details button

        var cardX = newBadge.x - cardW + (32 * density)
        var cardY = newBadge.y + (40 * density)

        // Clamp horizontal: keep card within screen with 8dp margin
        if (cardX < margin) cardX = margin
        if (cardX + cardW > screenW - margin) cardX = screenW - cardW - margin

        // Clamp vertical: if card would overflow bottom, position above the badge
        if (cardY + cardH > screenH - margin) {
            cardY = newBadge.y - cardH - (8 * density)
        }
        // If still off top, just pin to top
        if (cardY < margin) cardY = margin

        newCard.x = cardX
        newCard.y = cardY

        decorContent.addView(newCard)
        decorContent.addView(newBadge)

        badge = newBadge
        card = newCard

        // Initial update
        refreshWidget()
    }

    private fun detachFromActivity(activity: Activity) {
        val decorContent = activity.window.decorView as? FrameLayout ?: return
        badge?.let { decorContent.removeView(it) }
        card?.let { decorContent.removeView(it) }
    }

    private fun saveBadgePosition() {
        badge?.let {
            badgeX = it.x
            badgeY = it.y
        }
    }

    private fun startRefresh() {
        val runnable = object : Runnable {
            override fun run() {
                refreshWidget()
                handler?.postDelayed(this, config.refreshIntervalMs)
            }
        }
        refreshRunnable = runnable
        handler?.postDelayed(runnable, config.refreshIntervalMs)
    }

    private fun stopRefresh() {
        refreshRunnable?.let { handler?.removeCallbacks(it) }
        refreshRunnable = null
    }

    private fun refreshWidget() {
        val state = dataSource?.getState() ?: return
        badge?.updateStatus(state.exportStatus)
        card?.update(state)
        // Reposition card relative to badge on each update (badge may have been dragged)
        badge?.let { b ->
            val density = b.resources.displayMetrics.density
            card?.x = b.x - (230 * density) + (32 * density)
            card?.y = b.y + (40 * density)
        }
    }
}
