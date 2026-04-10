// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity

internal class ComposeClickDetector(
    private val config: ComposeClickConfig,
    private val context: InstrumentationContext,
) {
    private val logger: Logger = context.logger("io.opentelemetry.android.mobile.compose.click")
    private val sessionProvider = context.sessionProvider
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var application: Application? = null
    private var semanticsWarningLogged = false

    // Resolved via reflection at install time
    private val composeViewClass: Class<*>? = try {
        Class.forName("androidx.compose.ui.platform.AndroidComposeView")
    } catch (_: ClassNotFoundException) {
        null
    }

    // Semantics key objects resolved via reflection
    private val semanticsPropertiesClass: Class<*>? = try {
        Class.forName("androidx.compose.ui.semantics.SemanticsProperties")
    } catch (_: ClassNotFoundException) {
        null
    }

    private val semanticsActionsClass: Class<*>? = try {
        Class.forName("androidx.compose.ui.semantics.SemanticsActions")
    } catch (_: ClassNotFoundException) {
        null
    }

    private val testTagKey: Any? = resolveStaticField(semanticsPropertiesClass, "INSTANCE", "getTestTag")
    private val contentDescriptionKey: Any? = resolveStaticField(semanticsPropertiesClass, "INSTANCE", "getContentDescription")
    private val roleKey: Any? = resolveStaticField(semanticsPropertiesClass, "INSTANCE", "getRole")
    private val onClickKey: Any? = resolveStaticField(semanticsActionsClass, "INSTANCE", "getOnClick")

    /**
     * Resolve a property from a Kotlin companion-style INSTANCE singleton.
     * E.g. SemanticsProperties.INSTANCE.getTestTag()
     */
    private fun resolveStaticField(clazz: Class<*>?, instanceField: String, getter: String): Any? {
        if (clazz == null) return null
        return try {
            val instance = clazz.getField(instanceField).get(null)
            clazz.getMethod(getter).invoke(instance)
        } catch (_: Exception) {
            null
        }
    }

    fun install(application: Application) {
        this.application = application
        val callbacks =
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    wrapWindowCallback(activity)
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

                override fun onActivityStarted(activity: Activity) {}

                override fun onActivityPaused(activity: Activity) {}

                override fun onActivityStopped(activity: Activity) {}

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

                override fun onActivityDestroyed(activity: Activity) {}
            }
        lifecycleCallbacks = callbacks
        application.registerActivityLifecycleCallbacks(callbacks)
    }

    fun uninstall() {
        lifecycleCallbacks?.let { application?.unregisterActivityLifecycleCallbacks(it) }
        lifecycleCallbacks = null
        application = null
    }

    private fun wrapWindowCallback(activity: Activity) {
        val window = activity.window ?: return
        val currentCallback = window.callback ?: return
        if (currentCallback is ComposeClickCallbackWrapper) return
        window.callback = ComposeClickCallbackWrapper(currentCallback, window)
    }

    private inner class ComposeClickCallbackWrapper(
        private val delegate: Window.Callback,
        private val window: Window,
    ) : Window.Callback by delegate {
        override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
            if (event != null && event.actionMasked == MotionEvent.ACTION_UP) {
                handleComposeClick(event, window)
            }
            return delegate.dispatchTouchEvent(event)
        }
    }

    private fun handleComposeClick(event: MotionEvent, window: Window) {
        val composeView = findComposeView(window) ?: return

        // Convert screen-absolute rawX/rawY to window-local coordinates.
        // getBoundsInWindow returns window-relative coordinates, so we must
        // subtract the window's screen offset to avoid mismatch in multi-window mode.
        val location = IntArray(2)
        composeView.getLocationOnScreen(location)
        val x = event.rawX - location[0]
        val y = event.rawY - location[1]

        try {
            // Access semanticsOwner via reflection (internal Compose API)
            val semanticsOwnerField = composeView::class.java.getDeclaredField("semanticsOwner")
            semanticsOwnerField.isAccessible = true
            val semanticsOwner = semanticsOwnerField.get(composeView) ?: return

            val getRootMethod = semanticsOwner::class.java.getMethod("getUnmergedRootSemanticsNode")
            val rootNode = getRootMethod.invoke(semanticsOwner) ?: return

            val node = findClickableNodeAtPosition(rootNode, x, y) ?: return

            val attrs =
                Attributes.builder()
                    .put("ui.element.framework", "compose")
                    .put("ui.element.has_click_action", true)
                    .put(MobileSemconv.SESSION_ID, sessionProvider.getSessionId())
                    .put(MobileSemconv.VIEW_ID, sessionProvider.getViewId())

            extractSemanticsAttributes(node, attrs)

            sessionProvider.getCurrentScreenName()?.let {
                attrs.put(MobileSemconv.SCREEN_NAME, it)
            }

            ComposeTapFlag.markHandled()

            logger.logRecordBuilder()
                .setBody("ui.tap")
                .setSeverity(Severity.INFO)
                .setAllAttributes(attrs.build())
                .emit()
        } catch (e: Exception) {
            if (!semanticsWarningLogged) {
                Log.w(TAG, "Compose semantics API changed -- composable identity not available", e)
                semanticsWarningLogged = true
            }
            emitFallbackTap()
        }
    }

    private fun emitFallbackTap() {
        ComposeTapFlag.markHandled()
        logger.logRecordBuilder()
            .setBody("ui.tap")
            .setSeverity(Severity.INFO)
            .setAllAttributes(
                Attributes.builder()
                    .put("ui.element.framework", "compose")
                    .put(MobileSemconv.SESSION_ID, sessionProvider.getSessionId())
                    .put(MobileSemconv.VIEW_ID, sessionProvider.getViewId())
                    .apply {
                        sessionProvider.getCurrentScreenName()?.let {
                            put(MobileSemconv.SCREEN_NAME, it)
                        }
                    }
                    .build(),
            )
            .emit()
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractSemanticsAttributes(node: Any, attrs: AttributesBuilder) {
        try {
            val configMethod = node::class.java.getMethod("getConfig")
            val semanticsConfig = configMethod.invoke(node) ?: return
            val getOrNullMethod = semanticsConfig::class.java.getMethod("getOrNull", Any::class.java)

            if (config.captureTestTag && testTagKey != null) {
                val testTag = getOrNullMethod.invoke(semanticsConfig, testTagKey) as? String
                testTag?.let { attrs.put("ui.element.test_tag", it) }
            }

            if (config.captureContentDescription && contentDescriptionKey != null) {
                val desc =
                    getOrNullMethod.invoke(semanticsConfig, contentDescriptionKey) as? List<String>
                desc?.firstOrNull()?.let { attrs.put("ui.element.content_description", it) }
            }

            if (config.captureRole && roleKey != null) {
                val role = getOrNullMethod.invoke(semanticsConfig, roleKey)
                role?.let { attrs.put("ui.element.role", it.toString()) }
            }
        } catch (_: Exception) {
            // Silently skip attribute extraction on failure
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun findClickableNodeAtPosition(rootNode: Any, x: Float, y: Float): Any? {
        try {
            val childrenMethod = rootNode::class.java.getMethod("getChildren")
            val children = childrenMethod.invoke(rootNode) as? Iterable<*> ?: return null

            // DFS — find deepest clickable node containing (x, y)
            for (child in children) {
                if (child == null) continue
                val found = findClickableNodeAtPosition(child, x, y)
                if (found != null) return found
            }

            // Check if this node is clickable and contains the position
            if (nodeContainsPosition(rootNode, x, y) && nodeIsClickable(rootNode)) {
                return rootNode
            }
        } catch (_: Exception) {
            // Reflection failure
        }
        return null
    }

    private fun nodeContainsPosition(node: Any, x: Float, y: Float): Boolean {
        return try {
            val boundsMethod = node::class.java.getMethod("getBoundsInWindow")
            val bounds = boundsMethod.invoke(node) ?: return false
            val left = bounds::class.java.getMethod("getLeft").invoke(bounds) as Float
            val top = bounds::class.java.getMethod("getTop").invoke(bounds) as Float
            val right = bounds::class.java.getMethod("getRight").invoke(bounds) as Float
            val bottom = bounds::class.java.getMethod("getBottom").invoke(bounds) as Float
            x in left..right && y in top..bottom
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun nodeIsClickable(node: Any): Boolean {
        if (onClickKey == null) return false
        return try {
            val configMethod = node::class.java.getMethod("getConfig")
            val config = configMethod.invoke(node) ?: return false
            val getOrNullMethod = config::class.java.getMethod("getOrNull", Any::class.java)
            getOrNullMethod.invoke(config, onClickKey) != null
        } catch (_: Exception) {
            false
        }
    }

    private fun findComposeView(window: Window): View? {
        val rootView = window.decorView ?: return null
        return findComposeViewInHierarchy(rootView)
    }

    private fun findComposeViewInHierarchy(view: View): View? {
        if (composeViewClass != null && composeViewClass.isInstance(view)) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findComposeViewInHierarchy(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "ComposeClickDetector"
    }
}
