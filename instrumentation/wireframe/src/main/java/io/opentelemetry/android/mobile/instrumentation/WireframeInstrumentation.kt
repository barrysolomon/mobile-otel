// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import java.lang.ref.WeakReference

/**
 * Captures a lightweight JSON wireframe of the view hierarchy at trigger points.
 *
 * A wireframe is a compact (~1–5 KB) structural snapshot of every visible [View] in the
 * current activity — class name, bounds, resource ID, clickable state — enough to
 * reconstruct a schematic rendering of the screen without any pixel data.
 *
 * **Trigger modes:**
 * - **Screen transition:** when [WireframeConfig.captureOnScreenView] is true, a wireframe is
 *   captured after each activity/fragment resume (post-layout, so geometry is final).
 * - **Tap:** when [WireframeConfig.captureOnTap] is true, captures after each tap event.
 * - **Error:** when [WireframeConfig.captureOnError] is true, captures on uncaught exceptions.
 * - **Manual:** call [captureWireframe] at any time.
 *
 * **Privacy:** wireframes contain geometry and view types only. Text content is never included
 * unless [WireframeConfig.includeTextHints] is enabled (and even then, only hint/placeholder
 * text — never user-entered values).
 *
 * **Journey replay:** because wireframes are so small, they can be captured on every screen
 * transition and stitched together in a dashboard to visualize the user's path through the app.
 */
@Incubating
class WireframeInstrumentation(
    private val config: WireframeConfig = WireframeConfig()
) : MobileInstrumentation, WindowEventListener {

    override val instrumentationName = "io.opentelemetry.android.mobile.wireframe"

    private var ctx: InstrumentationContext? = null
    private var logger: Logger? = null
    private var tracer: Tracer? = null
    private var uiTelemetryMode: UiTelemetryMode = UiTelemetryMode.EVENTS
    private var application: Application? = null
    private var callbacks: Application.ActivityLifecycleCallbacks? = null
    private var previousExceptionHandler: Thread.UncaughtExceptionHandler? = null

    @Volatile private var currentActivity: WeakReference<Activity>? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val rateLimiter = RateLimiter(config.maxCapturesPerMinute)

    // Sequence number for ordering wireframes within a session.
    @Volatile private var sequenceNumber: Long = 0

    // Content-hash dedup state. lastEmittedHash is the SHA-256 of the most-recently
    // EMITTED wireframe JSON (not just the most recently captured). lastEmittedId is
    // the public mobile.wireframe.id we attached to it, so dedup emits a lightweight
    // ui.wireframe.ref pointing at that id instead of the full JSON payload.
    @Volatile private var lastEmittedHash: String? = null
    @Volatile private var lastEmittedId: String? = null

    /** Current wireframe id, or null if no wireframe has been emitted yet in this session. */
    fun currentWireframeId(): String? = lastEmittedId

    override fun install(application: Application, context: InstrumentationContext) {
        if (!config.enabled) return

        this.application = application
        this.ctx = context
        this.logger = context.logger(instrumentationName)
        this.tracer = context.tracer(instrumentationName)
        this.uiTelemetryMode = context.uiTelemetryMode

        val cb = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                currentActivity = WeakReference(activity)
                if (config.captureOnScreenView) {
                    // Capture after layout is complete so geometry is final.
                    capturePostLayout(activity, "screen_view")
                }
            }
            override fun onActivityPaused(activity: Activity) {
                if (currentActivity?.get() === activity) {
                    currentActivity = null
                }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        callbacks = cb
        application.registerActivityLifecycleCallbacks(cb)

        // Listen for tap events if configured.
        if (config.captureOnTap) {
            context.windowEventHub.addListener(this)
        }

        // Auto-capture on uncaught exceptions.
        if (config.captureOnError) {
            previousExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    captureWireframeSync("error")
                } catch (_: Exception) {
                    // Best-effort.
                }
                previousExceptionHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    override fun uninstall() {
        callbacks?.let { application?.unregisterActivityLifecycleCallbacks(it) }
        callbacks = null

        if (config.captureOnTap) {
            ctx?.windowEventHub?.removeListener(this)
        }

        if (config.captureOnError && previousExceptionHandler != null) {
            Thread.setDefaultUncaughtExceptionHandler(previousExceptionHandler)
            previousExceptionHandler = null
        }

        currentActivity = null
        application = null
        ctx = null
        logger = null
        tracer = null
        uiTelemetryMode = UiTelemetryMode.EVENTS
        rateLimiter.reset()
    }

    override fun onTouchEvent(event: MotionEvent, window: Window) {
        if (!config.captureOnTap) return
        if (event.actionMasked != MotionEvent.ACTION_UP) return

        val activity = currentActivity?.get() ?: return
        val parentContext = Context.current()
        // Small delay to let any resulting layout changes settle.
        mainHandler.postDelayed({ captureFromActivity(activity, "tap", parentContext) }, 100)
    }

    /**
     * Manually capture a wireframe of the current foreground activity.
     *
     * Captures `Context.current()` at call time so the resulting log record
     * carries the active span's trace_id. This stitches the wireframe to
     * the journey span the user was in when it was triggered — see
     * [USER_JOURNEY_CAPTURES_EPIC](docs/epics/USER_JOURNEY_CAPTURES_EPIC.md).
     *
     * @param trigger Describes what triggered the capture (e.g., "manual",
     *   "button_press", "journey_start").
     */
    fun captureWireframe(trigger: String = "manual") {
        if (!config.enabled) return
        if (trigger.startsWith("policy_") && !config.captureOnPolicyMatch) {
            return
        }
        val activity = currentActivity?.get() ?: run {
            Log.d(TAG, "No foreground activity, skipping wireframe capture")
            return
        }
        captureFromActivity(activity, trigger, Context.current())
    }

    /** Synchronous capture — used from crash handler. */
    private fun captureWireframeSync(trigger: String) {
        if (!config.enabled) return
        val activity = currentActivity?.get() ?: return
        if (!rateLimiter.tryAcquire()) return

        val parentContext = Context.current()
        try {
            val rootView = activity.window?.decorView?.rootView ?: return
            val screenName = activity.javaClass.simpleName
            val node = buildTree(rootView, 0)
            emitWireframe(node, screenName, trigger, parentContext)
        } catch (e: Exception) {
            Log.w(TAG, "Sync wireframe capture failed: ${e.message}")
        }
    }

    /**
     * Schedule a capture after the next layout pass, ensuring view geometry is final.
     */
    private fun capturePostLayout(activity: Activity, trigger: String) {
        val rootView = activity.window?.decorView?.rootView ?: return
        val parentContext = Context.current()
        rootView.post {
            captureFromActivity(activity, trigger, parentContext)
        }
    }

    private fun captureFromActivity(activity: Activity, trigger: String, parentContext: Context) {
        if (!rateLimiter.tryAcquire()) {
            Log.d(TAG, "Wireframe rate limit exceeded, skipping capture")
            return
        }

        try {
            val rootView = activity.window?.decorView?.rootView ?: return
            if (rootView.width <= 0 || rootView.height <= 0) return

            val screenName = activity.javaClass.simpleName
            val node = buildTree(rootView, 0)
            emitWireframe(node, screenName, trigger, parentContext)
        } catch (e: Exception) {
            Log.w(TAG, "Wireframe capture failed: ${e.message}")
        }
    }

    /**
     * Recursively build a [WireframeNode] tree from the view hierarchy.
     */
    internal fun buildTree(view: View, depth: Int): WireframeNode {
        val location = IntArray(2)
        view.getLocationInWindow(location)

        val bounds = intArrayOf(
            location[0],
            location[1],
            location[0] + view.width,
            location[1] + view.height
        )

        val type = viewTypeName(view)

        val id = if (config.includeResourceIds) {
            resourceIdName(view)
        } else null

        val hint = if (config.includeTextHints) {
            extractHint(view)
        } else null

        val cd = if (config.includeContentDescription) {
            view.contentDescription?.toString()
        } else null

        val clickable = if (config.includeClickableState) view.isClickable else null
        val enabled = if (config.includeClickableState) view.isEnabled else null

        // Truncate at max depth.
        if (depth >= config.maxDepth) {
            val hasChildren = view is ViewGroup && view.childCount > 0
            return WireframeNode(
                type = type, bounds = bounds, id = id, hint = hint,
                contentDescription = cd, clickable = clickable, enabled = enabled,
                truncated = hasChildren
            )
        }

        val children = if (view is ViewGroup) {
            val list = mutableListOf<WireframeNode>()
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child.visibility == View.VISIBLE) {
                    list.add(buildTree(child, depth + 1))
                }
            }
            list
        } else emptyList()

        return WireframeNode(
            type = type, bounds = bounds, id = id, hint = hint,
            contentDescription = cd, clickable = clickable, enabled = enabled,
            children = children
        )
    }

    /**
     * Return a short, recognizable type name for the view.
     * Uses simple class name but strips common suffixes for compactness.
     */
    private fun viewTypeName(view: View): String {
        val name = view.javaClass.simpleName
        // For anonymous/generated classes, walk up to the first named superclass.
        if (name.isEmpty() || name.contains('$')) {
            var cls: Class<*> = view.javaClass.superclass ?: return "View"
            while (cls.simpleName.isEmpty() || cls.simpleName.contains('$')) {
                cls = cls.superclass ?: return "View"
            }
            return cls.simpleName
        }
        return name
    }

    /**
     * Extract the Android resource ID name (e.g., "btn_book"), or null.
     */
    private fun resourceIdName(view: View): String? {
        val id = view.id
        if (id == View.NO_ID || id == 0) return null
        return try {
            val name = view.resources.getResourceEntryName(id)
            // Filter out auto-generated IDs from framework.
            if (name.startsWith("android:")) null else name
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extract hint/placeholder text — never user-entered content.
     */
    private fun extractHint(view: View): String? {
        return when (view) {
            is EditText -> view.hint?.toString()
            is TextView -> {
                // Only include hint, not actual text — for privacy.
                view.hint?.toString()
            }
            else -> null
        }
    }

    private fun emitWireframe(
        node: WireframeNode,
        screenName: String,
        trigger: String,
        parentContext: Context
    ) {
        val context = ctx ?: return
        val sessionProvider = context.sessionProvider

        val json = node.toJson().toString()
        val hash = sha256Hex(json)
        val seq = sequenceNumber++

        // Content-hash dedup: if the wireframe hasn't changed, emit a lightweight ref log
        // pointing at the previously emitted full wireframe instead of resending the JSON.
        // Saves the 1–5 KB payload on no-op screen-resume / tap captures.
        if (config.dedupeByContentHash && hash == lastEmittedHash && lastEmittedId != null) {
            val refAttrs = Attributes.builder()
                .put(MobileSemconv.SESSION_ID, sessionProvider.getSessionId())
                .put(MobileSemconv.VIEW_ID, sessionProvider.getViewId())
                .put(MobileSemconv.SCREEN_NAME, screenName)
                .put(MobileSemconv.WIREFRAME_TRIGGER, trigger)
                .put(MobileSemconv.WIREFRAME_SEQUENCE, seq)
                .put(MobileSemconv.WIREFRAME_ID, lastEmittedId!!)
                .build()
            emitUiTelemetry(MobileSemconv.UI_WIREFRAME_REF, refAttrs, parentContext)
            return
        }

        // First emit or content changed — send the full payload. The id is the hash itself
        // so consumers can correlate ref logs to the originating wireframe deterministically.
        lastEmittedHash = hash
        lastEmittedId = hash

        val attrs = Attributes.builder()
            .put(MobileSemconv.SESSION_ID, sessionProvider.getSessionId())
            .put(MobileSemconv.VIEW_ID, sessionProvider.getViewId())
            .put(MobileSemconv.SCREEN_NAME, screenName)
            .put(MobileSemconv.WIREFRAME_ID, hash)
            .put(MobileSemconv.WIREFRAME_TRIGGER, trigger)
            .put(MobileSemconv.WIREFRAME_SEQUENCE, seq)
            .put(MobileSemconv.WIREFRAME_SIZE_BYTES, json.length.toLong())
            .put(MobileSemconv.WIREFRAME_NODE_COUNT, countNodes(node).toLong())
            .put(MobileSemconv.WIREFRAME_DATA, json)
            .build()

        emitUiTelemetry(MobileSemconv.UI_WIREFRAME, attrs, parentContext)
    }

    private fun sha256Hex(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        // Hex-encode (lowercase). Short enough that we can pass it as an attribute
        // value cheaply, long enough to make accidental collisions vanishingly rare.
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(((b.toInt() shr 4) and 0xF).toString(16))
            sb.append((b.toInt() and 0xF).toString(16))
        }
        return sb.toString()
    }

    private fun emitUiTelemetry(name: String, attrs: Attributes, parentContext: Context) {
        when (uiTelemetryMode) {
            UiTelemetryMode.EVENTS -> logger?.logRecordBuilder()
                ?.setContext(parentContext)
                ?.setBody(name)?.setSeverity(Severity.INFO)?.setAllAttributes(attrs)?.emit()
            UiTelemetryMode.SPANS -> tracer
                ?.spanBuilder(name)?.setParent(parentContext)
                ?.setSpanKind(SpanKind.INTERNAL)?.startSpan()
                ?.apply { setAllAttributes(attrs); end() }
            UiTelemetryMode.BOTH -> {
                logger?.logRecordBuilder()
                    ?.setContext(parentContext)
                    ?.setBody(name)?.setSeverity(Severity.INFO)?.setAllAttributes(attrs)?.emit()
                tracer
                    ?.spanBuilder(name)?.setParent(parentContext)
                    ?.setSpanKind(SpanKind.INTERNAL)?.startSpan()
                    ?.apply { setAllAttributes(attrs); end() }
            }
        }
    }

    /**
     * Test seam: emit a synthetic wireframe log without going through the
     * view-tree pipeline. Validates trace_id propagation through the emit
     * path without needing a real Activity.
     */
    internal fun emitForTesting(
        trigger: String,
        screenName: String,
        parentContext: Context = Context.current()
    ) {
        val context = ctx ?: return
        val sessionProvider = context.sessionProvider
        val attrs = Attributes.builder()
            .put(MobileSemconv.SESSION_ID, sessionProvider.getSessionId())
            .put(MobileSemconv.VIEW_ID, sessionProvider.getViewId())
            .put(MobileSemconv.SCREEN_NAME, screenName)
            .put(MobileSemconv.WIREFRAME_TRIGGER, trigger)
            .put(MobileSemconv.WIREFRAME_SEQUENCE, sequenceNumber++)
            .build()
        emitUiTelemetry(MobileSemconv.UI_WIREFRAME, attrs, parentContext)
    }

    /** Count total nodes in the tree (for the metadata attribute). */
    private fun countNodes(node: WireframeNode): Int {
        return 1 + node.children.sumOf { countNodes(it) }
    }

    /** Visible for testing. */
    internal val trackedActivity: Activity? get() = currentActivity?.get()
    internal val isInstalled: Boolean get() = callbacks != null

    companion object {
        private const val TAG = "OTel-Wireframe"
    }
}
